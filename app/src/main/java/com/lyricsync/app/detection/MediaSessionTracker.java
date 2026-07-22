package com.lyricsync.app.detection;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.util.AppLog;

import java.util.List;
import java.util.Objects;

public class MediaSessionTracker implements MediaSessionManager.OnActiveSessionsChangedListener {
    private static final String TAG = "MediaSessionTracker";
    private static final String SPOTIFY_PKG = "com.spotify.music";
    private static final String YTMUSIC_PKG = "com.google.android.apps.youtube.music";
    private static final long POLL_INTERVAL_MS = 16;
    private static final int[] BURST_TIMINGS = {50, 100, 150, 750};
    // A player's getLastPositionUpdateTime() is only trusted when "now - updateElapsed"
    // falls inside this window. Some players report it in a different timebase
    // (uptimeMillis, wall-clock) or stamp it at load time, which — if trusted — makes
    // the interpolated position race ahead by seconds. Outside the window we anchor to now.
    private static final long UPDATE_TIME_TRUST_WINDOW_MS = 5_000;
    // Auto-resync: when a fresh snapshot's real position differs from what we are
    // currently projecting by more than this, snap the anchor back to the truth
    // (this is what removes the "1-2s ahead until you replay" drift automatically).
    private static final long RESYNC_THRESHOLD_MS = 400;
    // Hard cap on how far past the last anchor we allow free-running extrapolation.
    // Players push a new snapshot roughly every second, so >2s of pure projection
    // means we lost the anchor — clamp instead of drifting away from the audio.
    private static final long MAX_EXTRAPOLATION_MS = 2_000;

    private final Context context;
    private MediaSessionManager sessionManager;
    private MediaController currentController;
    private TrackCallback trackCallback;
    private PlaybackCallback playbackCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private HandlerThread pollThread;
    private Handler pollHandler;
    private TrackInfo currentTrack;
    private volatile int currentState = PlaybackState.STATE_NONE;
    private volatile long currentPosition = 0;
    private volatile long lastPositionUpdateElapsed = 0;
    private volatile boolean positionInitialized = false;
    private volatile float playbackSpeed = 1.0f;
    // Track length (ms) used to clamp the extrapolated position to the end of the song.
    private volatile long trackDurationMs = 0;
    // User calibration: positive value shifts the reported position backwards to
    // compensate apps (e.g. Spotify) whose reported position leads the actual audio.
    private volatile long syncOffsetMs = 0;

    private boolean polling = false;
    private int burstIndex = 0;
    private boolean bursting = false;

    public interface TrackCallback {
        void onTrackChanged(TrackInfo track);
        void onTrackUpdated(TrackInfo track);
        void onTrackCleared();
    }

    public interface PlaybackCallback {
        void onPlaybackStateChanged(int state, long position);
    }

    public MediaSessionTracker(Context context) {
        this.context = context;
    }

    public void start(TrackCallback trackCallback, PlaybackCallback playbackCallback) {
        this.trackCallback = trackCallback;
        this.playbackCallback = playbackCallback;

        pollThread = new HandlerThread("MediaSessionPoll");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());

        sessionManager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (sessionManager == null) {
            AppLog.e(TAG, "MediaSessionManager not available");
            return;
        }

        try {
            ComponentName listenerComponent = new ComponentName(context, MediaNotificationListener.class);
            sessionManager.addOnActiveSessionsChangedListener(this, listenerComponent, mainHandler);

            List<MediaController> sessions = sessionManager.getActiveSessions(listenerComponent);
            MediaController target = findTargetController(sessions);
            if (target != null) {
                switchController(target);
            }
        } catch (SecurityException e) {
            AppLog.e(TAG, "SecurityException: need MEDIA_CONTENT_CONTROL or notification listener", e);
        }
    }

    public void stop() {
        stopPolling();
        if (sessionManager != null) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(this);
            } catch (Exception e) {
                AppLog.e(TAG, "Error removing listener", e);
            }
        }
        if (currentController != null) {
            currentController.unregisterCallback(controllerCallback);
            currentController = null;
        }
        if (pollThread != null) {
            pollThread.quitSafely();
            pollThread = null;
            pollHandler = null;
        }
    }

    @Override
    public void onActiveSessionsChanged(List<MediaController> controllers) {
        if (controllers == null) return;
        MediaController target = findTargetController(controllers);
        if (target != null) {
            switchController(target);
        } else {
            clearCurrentTrack();
        }
    }

    private MediaController findTargetController(List<MediaController> controllers) {
        MediaController spotify = null;
        MediaController ytmusic = null;
        MediaController anyPlaying = null;

        for (MediaController controller : controllers) {
            String pkg = controller.getPackageName();
            PlaybackState state = controller.getPlaybackState();

            if (SPOTIFY_PKG.equals(pkg)) {
                spotify = controller;
            } else if (YTMUSIC_PKG.equals(pkg)) {
                ytmusic = controller;
            }

            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                if (anyPlaying == null) {
                    anyPlaying = controller;
                }
            }
        }

        if (spotify != null && spotify.getPlaybackState() != null && spotify.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) return spotify;
        if (ytmusic != null && ytmusic.getPlaybackState() != null && ytmusic.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) return ytmusic;
        if (anyPlaying != null) return anyPlaying;
        if (spotify != null) return spotify;
        if (ytmusic != null) return ytmusic;
        return controllers.isEmpty() ? null : controllers.get(0);
    }

    private void switchController(MediaController controller) {
        if (currentController != null) {
            currentController.unregisterCallback(controllerCallback);
        }

        currentController = controller;
        controller.registerCallback(controllerCallback, mainHandler);
        updateFromController(controller);
        startBurstPolling();
    }

    private void updateFromController(MediaController controller) {
        MediaMetadata metadata = controller.getMetadata();
        PlaybackState state = controller.getPlaybackState();

        if (metadata != null) {
            TrackInfo track = new TrackInfo();
            track.title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            track.artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            track.album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
            track.albumArtUri = firstMetadataString(metadata,
                    MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                    MediaMetadata.METADATA_KEY_ART_URI,
                    MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            track.albumArtBitmap = firstMetadataBitmap(metadata,
                    MediaMetadata.METADATA_KEY_ALBUM_ART,
                    MediaMetadata.METADATA_KEY_ART,
                    MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            track.duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            track.packageName = controller.getPackageName();
            trackDurationMs = Math.max(0, track.duration);

            String mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            if (mediaId != null) {
                track.trackId = mediaId;
            }

            if (track.isValid()) {
                boolean changed = currentTrack == null ||
                        !Objects.equals(track.title, currentTrack.title) ||
                        !Objects.equals(track.artist, currentTrack.artist);
                boolean updated = !changed && hasDisplayInfoChanged(currentTrack, track);

                currentTrack = track;

                if (changed && trackCallback != null) {
                    currentPosition = 0;
                    lastPositionUpdateElapsed = SystemClock.elapsedRealtime();
                    positionInitialized = false;
                    AppLog.i(TAG, "Track: " + track.title + " - " + track.artist
                            + " [" + track.packageName + "] id=" + track.trackId);
                    mainHandler.post(() -> trackCallback.onTrackChanged(track));
                } else if (updated && trackCallback != null) {
                    AppLog.d(TAG, "Track display info updated: artUri=" + track.albumArtUri
                            + " bitmap=" + (track.albumArtBitmap != null));
                    mainHandler.post(() -> trackCallback.onTrackUpdated(track));
                }
            }
        }

        if (state != null) {
            updatePlaybackState(state);

            if (playbackCallback != null) {
                long callbackPosition = getCurrentPosition();
                mainHandler.post(() -> playbackCallback.onPlaybackStateChanged(currentState, callbackPosition));
            }
        }
    }

    private String firstMetadataString(MediaMetadata metadata, String... keys) {
        for (String key : keys) {
            String value = metadata.getString(key);
            if (value != null && !value.trim().isEmpty()) return value;
        }
        return null;
    }

    private Bitmap firstMetadataBitmap(MediaMetadata metadata, String... keys) {
        for (String key : keys) {
            Bitmap value = metadata.getBitmap(key);
            if (value != null) return value;
        }
        return null;
    }

    private boolean hasDisplayInfoChanged(TrackInfo previous, TrackInfo next) {
        if (previous == null || next == null) return true;
        if (!same(previous.albumArtUri, next.albumArtUri)) return true;
        if (!same(previous.trackId, next.trackId)) return true;
        return bitmapSignature(previous.albumArtBitmap) != bitmapSignature(next.albumArtBitmap);
    }

    private boolean same(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    private int bitmapSignature(Bitmap bitmap) {
        if (bitmap == null) return 0;
        return bitmap.getWidth() * 31 + bitmap.getHeight();
    }

    private void pollPosition() {
        if (currentController == null) return;

        PlaybackState state = currentController.getPlaybackState();
        if (state != null) {
            updatePlaybackState(state);
        }
    }

    private void updatePlaybackState(PlaybackState state) {
        int previousState = currentState;
        currentState = state.getState();
        // Resuming from a pause/buffer stall: force a fresh time anchor so the elapsed
        // timer does not include the stalled interval and make the position jump ahead.
        boolean resumedPlaying = currentState == PlaybackState.STATE_PLAYING
                && previousState != PlaybackState.STATE_PLAYING;
        long newPosition = Math.max(0, state.getPosition());
        long updateElapsed = state.getLastPositionUpdateTime();
        long now = SystemClock.elapsedRealtime();
        // MediaController.getPlaybackState() returns a cached snapshot that only changes
        // when the player pushes a new one (~1s), while we poll every 16ms. We must not
        // re-anchor on every identical re-read (that would freeze interpolation), only on
        // genuine new samples.
        //
        // Trust the player-supplied update time only when it is a sane elapsedRealtime
        // value AND recent (within the trust window). Some players report this in a
        // different timebase or stamp it at load time; trusting a stale/mistimed value
        // makes "now - updateElapsed" huge and the interpolated position races ahead.
        boolean updateTimeUsable = updateElapsed > 0
                && updateElapsed <= now
                && (now - updateElapsed) <= UPDATE_TIME_TRUST_WINDOW_MS;

        if (updateTimeUsable) {
            lastPositionUpdateElapsed = updateElapsed;
            currentPosition = newPosition;
            positionInitialized = true;
        } else {
            // No usable timestamp. Decide whether this snapshot is a genuine new sample.
            long projectedNow = projectedPosition(now);
            long drift = Math.abs(newPosition - projectedNow);
            boolean positionChanged = newPosition != currentPosition;
            // Auto-resync: the reported (truth) position drifted from our projection
            // beyond the threshold -> snap the anchor back to the real position. This
            // corrects the "1-2s ahead" case on its own the moment the player pushes a
            // fresh snapshot, without needing the user to replay the song.
            boolean needsResync = positionInitialized
                    && currentState == PlaybackState.STATE_PLAYING
                    && drift > RESYNC_THRESHOLD_MS;

            if (positionChanged || !positionInitialized || resumedPlaying || needsResync) {
                lastPositionUpdateElapsed = now;
                currentPosition = newPosition;
                positionInitialized = true;
            }
            // else: stale snapshot (same position, small drift) -> keep anchor so the
            // already-running interpolation keeps advancing smoothly.
        }
        playbackSpeed = state.getPlaybackSpeed();
        // Guard against players reporting garbage speeds (0 while playing, or absurdly
        // large values) which would freeze or fling the interpolated position.
        if (playbackSpeed <= 0 && currentState == PlaybackState.STATE_PLAYING) {
            playbackSpeed = 1.0f;
        }
        if (playbackSpeed < 0f) playbackSpeed = 0f;
        if (playbackSpeed > 4f) playbackSpeed = 4f;
    }

    private void startBurstPolling() {
        stopPolling();
        if (pollHandler == null) return;
        burstIndex = 0;
        bursting = true;
        scheduleBurstTick();
    }

    private void scheduleBurstTick() {
        if (pollHandler == null) return;
        if (burstIndex >= BURST_TIMINGS.length) {
            bursting = false;
            startSteadyPolling();
            return;
        }

        int delay = BURST_TIMINGS[burstIndex];
        burstIndex++;
        pollHandler.postDelayed(() -> {
            if (!bursting) return;
            pollPosition();
            scheduleBurstTick();
        }, delay);
    }

    private void startSteadyPolling() {
        if (polling) return;
        polling = true;
        pollHandler.post(pollRunnable);
    }

    private void stopPolling() {
        polling = false;
        bursting = false;
        if (pollHandler != null) {
            pollHandler.removeCallbacksAndMessages(null);
        }
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            pollPosition();
            if (pollHandler != null) {
                pollHandler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    private void clearCurrentTrack() {
        stopPolling();
        if (currentController != null) {
            currentController.unregisterCallback(controllerCallback);
            currentController = null;
        }
        currentTrack = null;
        currentState = PlaybackState.STATE_NONE;
        currentPosition = 0;
        lastPositionUpdateElapsed = 0;
        positionInitialized = false;
        trackDurationMs = 0;

        if (trackCallback != null) {
            mainHandler.post(trackCallback::onTrackCleared);
        }
    }

    public TrackInfo getCurrentTrack() {
        return currentTrack;
    }

    public long getCurrentPosition() {
        long pos = projectedPosition(SystemClock.elapsedRealtime());
        long adjusted = pos - syncOffsetMs;
        return adjusted < 0 ? 0 : adjusted;
    }

    /**
     * Project the current playback position (before user sync offset). Only extrapolates
     * while actually PLAYING, caps free-running extrapolation so a lost anchor cannot
     * drift away from the audio, and clamps to the track length.
     */
    private long projectedPosition(long nowElapsed) {
        long pos = currentPosition;
        if (currentState == PlaybackState.STATE_PLAYING) {
            long elapsed = Math.max(0, nowElapsed - lastPositionUpdateElapsed);
            // Cap how far we trust pure projection: past this we likely lost the anchor.
            elapsed = Math.min(elapsed, MAX_EXTRAPOLATION_MS);
            pos += (long) (elapsed * playbackSpeed);
        }
        if (pos < 0) pos = 0;
        long dur = trackDurationMs;
        if (dur > 0 && pos > dur) pos = dur;
        return pos;
    }

    public long getLastPollNanoTime() {
        long elapsed = lastPositionUpdateElapsed;
        if (elapsed == 0) return System.nanoTime();
        return System.nanoTime() - (SystemClock.elapsedRealtime() - elapsed) * 1_000_000L;
    }

    public float getPlaybackSpeed() {
        return playbackSpeed;
    }

    public void setSyncOffsetMs(long offsetMs) {
        this.syncOffsetMs = offsetMs;
    }

    public long getSyncOffsetMs() {
        return syncOffsetMs;
    }

    public boolean isPlaying() {
        return currentState == PlaybackState.STATE_PLAYING
                || currentState == PlaybackState.STATE_BUFFERING;
    }

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            if (currentController != null) {
                updateFromController(currentController);
            }
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (currentController != null) {
                updateFromController(currentController);
            }
        }

        @Override
        public void onSessionDestroyed() {
            clearCurrentTrack();
        }
    };
}
