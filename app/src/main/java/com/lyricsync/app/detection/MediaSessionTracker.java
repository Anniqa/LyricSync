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
        // Spotify (and some other players) do not reliably populate getLastPositionUpdateTime()
        // in the SystemClock.elapsedRealtime() timebase: it can be a wall-clock value
        // (currentTimeMillis, far larger than elapsedRealtime) or a future/garbage timestamp.
        // Only trust it when it is a sane elapsedRealtime value in the past; otherwise anchor
        // interpolation to the moment we actually read the state.
        //
        // Critical detail: MediaController.getPlaybackState() returns a *cached snapshot* that
        // only changes when Spotify pushes a new one (~1s). Our 16ms poll therefore mostly re-reads
        // the same snapshot with the same position. If we re-anchored the clock on every poll
        // (the old behaviour), currentPosition would stay frozen between Spotify's pushes while the
        // anchor kept resetting to "now", so interpolation would advance only a few ms per poll ->
        // the lyrics would stutter/lag ("frozen" symptom). Instead, when the timestamp is unusable
        // we only re-anchor when the REPORTED POSITION actually changed (a genuine new update from
        // Spotify); on a stale unchanged snapshot we keep the previous anchor so interpolation
        // continues smoothly until the next real update arrives.
        if (updateElapsed > 0 && updateElapsed <= now) {
            lastPositionUpdateElapsed = updateElapsed;
            currentPosition = newPosition;
            positionInitialized = true;
        } else {
            boolean positionChanged = newPosition != currentPosition;
            // Re-anchor on a genuine position change, on first init, OR when we just
            // resumed playback — otherwise a resume after buffering would keep the stale
            // anchor and the interpolated position would leap forward by the stall duration.
            if (positionChanged || !positionInitialized || resumedPlaying) {
                lastPositionUpdateElapsed = now;
                currentPosition = newPosition;
                positionInitialized = true;
            }
            // else: stale snapshot (same position) -> keep anchor + currentPosition so the
            // already-running interpolation keeps advancing instead of freezing.
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

        if (trackCallback != null) {
            mainHandler.post(trackCallback::onTrackCleared);
        }
    }

    public TrackInfo getCurrentTrack() {
        return currentTrack;
    }

    public long getCurrentPosition() {
        int state = currentState;
        long pos = currentPosition;
        // Only extrapolate while actually PLAYING. During BUFFERING the audio is stalled
        // (network hiccup, track change) but the reported position does not advance, so
        // extrapolating here made the lyrics race ahead and then snap back once the player
        // pushed the real position. Hold the last known position instead while buffering.
        if (state == PlaybackState.STATE_PLAYING) {
            long elapsed = Math.max(0, SystemClock.elapsedRealtime() - lastPositionUpdateElapsed);
            pos += (long) (elapsed * playbackSpeed);
        }
        long adjusted = pos - syncOffsetMs;
        return adjusted < 0 ? 0 : adjusted;
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
