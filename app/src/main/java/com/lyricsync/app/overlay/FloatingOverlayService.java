package com.lyricsync.app.overlay;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.lyricsync.app.LyricSyncApp;
import com.lyricsync.app.R;
import com.lyricsync.app.detection.MediaSessionTracker;
import com.lyricsync.app.lyrics.LyricsProviderManager;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.renderer.SpringScroller;
import com.lyricsync.app.renderer.SyllableHighlighter;
import com.lyricsync.app.util.AppLog;
import com.lyricsync.app.util.Permissions;
import com.lyricsync.app.util.SeekBars;

import java.util.ArrayList;
import java.util.List;

public class FloatingOverlayService extends Service {
    private static final String TAG = "FloatingOverlay";
    private static final int NOTIFICATION_ID = 1001;

    private WindowManager windowManager;
    private View overlayView;
    private MediaSessionTracker sessionTracker;
    private LyricsProviderManager lyricsManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView overlayTitle;
    private TextView overlayArtist;
    private ImageView overlayCover;
    private ImageView overlayToggle;
    private ImageView overlayClose;
    private ImageView overlaySettings;
    private View overlaySettingsPanel;
    private SeekBar overlaySyncOffsetSlider;
    private TextView overlaySyncOffsetLabel;
    private LinearLayout lyricsContainer;
    private ScrollView scrollView;
    private WindowManager.LayoutParams overlayParams;
    private SharedPreferences sharedPrefs;

    private LyricsData currentLyrics;
    private TrackInfo currentTrack;
    private volatile String pendingFetchKey = null;
    private boolean isDestroyed = false;
    private int lastActiveLineIndex = -1;
    private Typeface fontBold;
    private Typeface fontMedium;
    private float overlayFontSizeSp = 13f;
    private int overlayWidthPercent = 88;
    private int lyricsHeightPx = 0;
    private boolean lyricsVisible = true;
    private final Runnable applySettingsRunnable = () -> applyRuntimeSettings(true);
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener = (prefs, key) -> {
        if ("font_scale".equals(key)
                || "overlay_width_percent".equals(key)
                || "overlay_height_percent".equals(key)) {
            handler.removeCallbacks(applySettingsRunnable);
            handler.postDelayed(applySettingsRunnable, 100);
        } else if ("sync_offset_ms".equals(key) && sessionTracker != null) {
            sessionTracker.setSyncOffsetMs(prefs.getLong("sync_offset_ms", 0));
        }
    };

    private SyllableHighlighter highlighter;
    private final List<SyllableHighlighter.LineView> lineViews = new ArrayList<>();
    private SpringScroller springScroller;

    private Choreographer choreographer;
    private boolean renderRunning = false;
    private boolean renderActive = false;
    private long lastFrameNanos = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        try {
            fontBold = Typeface.createFromAsset(getAssets(), "fonts/lyrics_font.ttf");
        } catch (Exception e) {
            fontBold = Typeface.create("sans-serif", Typeface.BOLD);
        }
        try {
            fontMedium = Typeface.createFromAsset(getAssets(), "fonts/lyrics_font_medium.ttf");
        } catch (Exception e) {
            fontMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        }

        sharedPrefs = getSharedPreferences("lyricsync", MODE_PRIVATE);
        overlayFontSizeSp = calculateFontSizeSp();
        highlighter = new SyllableHighlighter(this, fontBold, fontMedium, overlayFontSizeSp);
        choreographer = Choreographer.getInstance();
        sharedPrefs.registerOnSharedPreferenceChangeListener(settingsListener);

        if (!Permissions.isNotificationListenerEnabled(this)) {
            AppLog.w(TAG, "Notification listener not enabled");
            Toast.makeText(this, "Please enable LyricSync notification access", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            AppLog.w(TAG, "Overlay permission not granted");
            stopSelf();
            return;
        }

        if (!createOverlay()) return;
        startTracker();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                AppLog.w(TAG, "POST_NOTIFICATIONS permission not granted");
            }
        }
        AppLog.i(TAG, "Overlay service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean createOverlay() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_lyrics, null);

        overlayTitle = overlayView.findViewById(R.id.overlay_title);
        overlayArtist = overlayView.findViewById(R.id.overlay_artist);
        overlayCover = overlayView.findViewById(R.id.overlay_cover);
        overlayToggle = overlayView.findViewById(R.id.overlay_toggle);
        overlayClose = overlayView.findViewById(R.id.overlay_close);
        overlaySettings = overlayView.findViewById(R.id.overlay_settings);
        overlaySettingsPanel = overlayView.findViewById(R.id.overlay_settings_panel);
        overlaySyncOffsetSlider = overlayView.findViewById(R.id.overlay_sync_offset_slider);
        overlaySyncOffsetLabel = overlayView.findViewById(R.id.overlay_sync_offset_label);
        lyricsContainer = overlayView.findViewById(R.id.overlay_lyrics_container);
        scrollView = overlayView.findViewById(R.id.overlay_scroll);
        lyricsVisible = sharedPrefs.getBoolean("lyrics_visible", true);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int widthPercent = Math.max(55, Math.min(100, sharedPrefs.getInt("overlay_width_percent", 88)));
        overlayWidthPercent = widthPercent;
        int heightPercent = Math.max(20, Math.min(70, sharedPrefs.getInt("overlay_height_percent", 36)));
        int overlayWidth = Math.max(dpToPx(220), metrics.widthPixels * widthPercent / 100);
        lyricsHeightPx = Math.max(dpToPx(140), metrics.heightPixels * heightPercent / 100);

        ViewGroup.LayoutParams scrollParams = scrollView.getLayoutParams();
        scrollParams.height = lyricsHeightPx;
        scrollView.setLayoutParams(scrollParams);
        applyResponsiveHeaderSizing();
        applyResponsiveLyricsSpacing();

        springScroller = new SpringScroller(scrollView);
        springScroller.setScrollPositionRatio(calculateScrollPositionRatio());

        overlayToggle.setOnClickListener(v -> setLyricsVisible(!lyricsVisible));
        overlayClose.setOnClickListener(v -> stopSelf());
        setupSyncOffsetUi();
        setLyricsVisible(lyricsVisible, false);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                overlayWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = 100;
        overlayParams = params;

        setupDragging(params);
        try {
            windowManager.addView(overlayView, params);
            return true;
        } catch (SecurityException | WindowManager.BadTokenException e) {
            AppLog.e(TAG, "Unable to attach overlay", e);
            overlayView = null;
            stopSelf();
            return false;
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private float calculateFontSizeSp() {
        float screenWidthDp = getResources().getConfiguration().screenWidthDp;
        float baseFontSize = Math.max(11f, Math.min(22f, screenWidthDp * 0.038f));
        float fontScale = Math.max(0.5f, Math.min(2.0f, sharedPrefs.getFloat("font_scale", 1.0f)));
        return Math.max(9f, Math.min(30f, baseFontSize * fontScale));
    }

    private void applyRuntimeSettings(boolean rebuildLyrics) {
        if (overlayView == null || scrollView == null || lyricsContainer == null) return;

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int newWidthPercent = Math.max(55, Math.min(100, sharedPrefs.getInt("overlay_width_percent", 88)));
        int newHeightPercent = Math.max(20, Math.min(70, sharedPrefs.getInt("overlay_height_percent", 36)));
        float newFontSizeSp = calculateFontSizeSp();

        boolean fontChanged = Math.abs(newFontSizeSp - overlayFontSizeSp) > 0.05f;
        overlayFontSizeSp = newFontSizeSp;
        overlayWidthPercent = newWidthPercent;
        lyricsHeightPx = Math.max(dpToPx(140), metrics.heightPixels * newHeightPercent / 100);

        ViewGroup.LayoutParams scrollParams = scrollView.getLayoutParams();
        scrollParams.height = lyricsHeightPx;
        scrollView.setLayoutParams(scrollParams);

        if (overlayParams != null && windowManager != null) {
            overlayParams.width = Math.max(dpToPx(220), metrics.widthPixels * newWidthPercent / 100);
            try {
                windowManager.updateViewLayout(overlayView, overlayParams);
            } catch (Exception e) {
                AppLog.w(TAG, "Failed to update overlay layout: " + e.getMessage());
            }
        }

        applyResponsiveHeaderSizing();
        applyResponsiveLyricsSpacing();
        if (springScroller != null) {
            springScroller.setScrollPositionRatio(calculateScrollPositionRatio());
        }
        if (currentTrack != null) {
            updateTrackInfo(currentTrack);
        }

        if (rebuildLyrics && currentLyrics != null && fontChanged) {
            highlighter = new SyllableHighlighter(this, fontBold, fontMedium, overlayFontSizeSp);
            renderOverlayLyrics(currentLyrics);
        } else {
            jumpToCurrentLineAfterLayout();
        }
    }

    private void applyResponsiveHeaderSizing() {
        float widthFactor = Math.max(0.78f, Math.min(1.08f, overlayWidthPercent / 88f));
        int coverSize = Math.round(Math.max(dpToPx(28), Math.min(dpToPx(54), spToPx(overlayFontSizeSp * 2.65f) * widthFactor)));

        ViewGroup.LayoutParams coverParams = overlayCover.getLayoutParams();
        coverParams.width = coverSize;
        coverParams.height = coverSize;
        overlayCover.setLayoutParams(coverParams);

        overlayTitle.setTextSize(Math.max(11f, Math.min(18f, overlayFontSizeSp * 0.92f)));
        overlayArtist.setTextSize(Math.max(9f, Math.min(15f, overlayFontSizeSp * 0.78f)));
    }

    private float spToPx(float sp) {
        return sp * getResources().getDisplayMetrics().scaledDensity;
    }

    private void applyResponsiveLyricsSpacing() {
        int verticalPadding = Math.max(dpToPx(14), Math.round(spToPx(overlayFontSizeSp * 1.2f)));
        int horizontalPadding = Math.max(dpToPx(6), Math.round(spToPx(overlayFontSizeSp * 0.35f)));
        lyricsContainer.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
    }

    private float calculateScrollPositionRatio() {
        float fontPx = spToPx(overlayFontSizeSp);
        float height = Math.max(dpToPx(140), lyricsHeightPx);
        float fontHeightRatio = Math.max(0.04f, Math.min(0.18f, fontPx / height));
        float compactBoost = overlayWidthPercent < 75 ? 0.05f : 0f;
        float tallOverlayPullUp = height > dpToPx(320) ? -0.04f : 0f;
        return 0.34f + fontHeightRatio + compactBoost + tallOverlayPullUp;
    }

    private void setLyricsVisible(boolean visible) {
        setLyricsVisible(visible, true);
    }

    private void setLyricsVisible(boolean visible, boolean persist) {
        lyricsVisible = visible;
        if (persist) {
            getSharedPreferences("lyricsync", MODE_PRIVATE)
                    .edit()
                    .putBoolean("lyrics_visible", visible)
                    .apply();
        }
        scrollView.setVisibility(visible ? View.VISIBLE : View.GONE);
        overlayToggle.setAlpha(visible ? 1.0f : 0.65f);
        overlayToggle.setRotation(visible ? 90f : 270f);
        if (visible) {
            startRenderLoop();
            jumpToCurrentLineAfterLayout();
        } else {
            stopRenderLoop();
        }
    }

    private void jumpToCurrentLineAfterLayout() {
        if (currentLyrics == null || lineViews.isEmpty() || springScroller == null) return;
        scrollView.post(() -> {
            int activeIndex = findActiveLine(sessionTracker != null ? sessionTracker.getCurrentPosition() : 0);
            if (activeIndex >= 0 && activeIndex < lyricsContainer.getChildCount()) {
                View activeView = lyricsContainer.getChildAt(activeIndex);
                springScroller.jumpToView(activeView);
                lastActiveLineIndex = activeIndex;
            }
        });
    }

    private void setupSyncOffsetUi() {
        long saved = sharedPrefs.getLong("sync_offset_ms", 0);
        saved = Math.max(-1500, Math.min(1500, saved));
        overlaySyncOffsetSlider.setProgress((int) saved + 1500);
        updateSyncOffsetLabel((int) saved);

        overlaySettings.setOnClickListener(v -> {
            boolean visible = overlaySettingsPanel.getVisibility() == View.VISIBLE;
            overlaySettingsPanel.setVisibility(visible ? View.GONE : View.VISIBLE);
        });

        SeekBars.bind(overlaySyncOffsetSlider, (int) saved + 1500, progress -> {
            int offset = progress - 1500;
            offset = Math.max(-1500, Math.min(1500, offset));
            updateSyncOffsetLabel(offset);
            sharedPrefs.edit().putLong("sync_offset_ms", offset).apply();
            if (sessionTracker != null) {
                sessionTracker.setSyncOffsetMs(offset);
            }
        });
    }

    private void updateSyncOffsetLabel(int ms) {
        overlaySyncOffsetLabel.setText(ms + " ms (lyrics " + (ms >= 0 ? "later" : "earlier") + ")");
    }

    private void setupDragging(WindowManager.LayoutParams params) {
        final int[] touchX = new int[1];
        final int[] touchY = new int[1];
        final int[] paramX = new int[1];
        final int[] paramY = new int[1];
        final boolean[] isDragging = new boolean[1];

        overlayView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    touchX[0] = (int) event.getRawX();
                    touchY[0] = (int) event.getRawY();
                    paramX[0] = params.x;
                    paramY[0] = params.y;
                    isDragging[0] = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - touchX[0];
                    float dy = event.getRawY() - touchY[0];
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging[0] = true;
                    }
                    if (isDragging[0]) {
                        params.x = paramX[0] + (int) dx;
                        params.y = paramY[0] + (int) dy;
                        try {
                            windowManager.updateViewLayout(overlayView, params);
                        } catch (Exception e) {
                            // View may have been detached mid-drag during teardown.
                            AppLog.w(TAG, "Drag updateViewLayout failed: " + e.getMessage());
                        }
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return isDragging[0];
            }
            return false;
        });
    }

    private void startTracker() {
        lyricsManager = new LyricsProviderManager(this);
        sessionTracker = new MediaSessionTracker(this);
        sessionTracker.setSyncOffsetMs(sharedPrefs.getLong("sync_offset_ms", 0));

        sessionTracker.start(
                new MediaSessionTracker.TrackCallback() {
                    @Override
                    public void onTrackChanged(TrackInfo track) {
                        handler.post(() -> {
                            if (isDestroyed) return;
                            currentTrack = track;
                            AppLog.i(TAG, "Track changed: " + track.title + " - " + track.artist);
                            updateTrackInfo(track);
                            clearLyricsOnly();
                            fetchLyrics(track);
                        });
                    }

                    @Override
                    public void onTrackUpdated(TrackInfo track) {
                        handler.post(() -> {
                            if (isDestroyed) return;
                            currentTrack = track;
                            AppLog.d(TAG, "Track info updated: " + track.title + " - " + track.artist);
                            updateTrackInfo(track);
                        });
                    }

                    @Override
                    public void onTrackCleared() {
                        handler.post(() -> {
                            if (isDestroyed) return;
                            AppLog.i(TAG, "Track cleared");
                            currentTrack = null;
                            pendingFetchKey = null;
                            if (lyricsManager != null) lyricsManager.cancelPending();
                            clearOverlay();
                        });
                    }
                },
                (state, position) -> {
                    if (isDestroyed) return;
                    // Position/state come from the tracker; we only need this signal to
                    // wake the render loop if it had gone idle. Values are re-read per frame.
                    if (renderRunning && !renderActive) {
                        startFrameCallback();
                    }
                }
        );
    }

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!renderActive) return;

            boolean playing = sessionTracker != null
                    && sessionTracker.isPlaying()
                    && currentLyrics != null
                    && !lineViews.isEmpty();
            boolean settled = springScroller == null || springScroller.isSettled();

            if (!playing && settled) {
                renderActive = false;
                lastFrameNanos = 0;
                return;
            }

            double dt;
            if (lastFrameNanos > 0) {
                dt = Math.min(Math.max(
                        (frameTimeNanos - lastFrameNanos) / 1_000_000_000.0, 0.0),
                        1.0 / 30.0);
            } else {
                dt = 1.0 / 60.0;
            }
            lastFrameNanos = frameTimeNanos;

            long position = sessionTracker != null ? sessionTracker.getCurrentPosition() : 0;
            updateHighlight(position, dt);
            highlighter.animateInterlude(position, dt);

            choreographer.postFrameCallback(this);
        }
    };

    private void startRenderLoop() {
        renderRunning = true;
        startFrameCallback();
    }

    private void startFrameCallback() {
        if (renderActive) return;
        renderActive = true;
        lastFrameNanos = 0;
        choreographer.postFrameCallback(frameCallback);
    }

    private void stopRenderLoop() {
        renderRunning = false;
        renderActive = false;
        choreographer.removeFrameCallback(frameCallback);
    }

    private void updateTrackInfo(TrackInfo track) {
        if (isDestroyed) return;
        overlayTitle.setText(track.title);
        overlayArtist.setText(track.artist);

        if (track.albumArtBitmap != null) {
            Glide.with(this)
                    .load(track.albumArtBitmap)
                    .transition(DrawableTransitionOptions.withCrossFade(220))
                    .transform(new RoundedCorners(dpToPx(10)))
                    .into(overlayCover);
        } else if (track.albumArtUri != null && !track.albumArtUri.trim().isEmpty()) {
            Glide.with(this)
                    .load(track.albumArtUri)
                    .transition(DrawableTransitionOptions.withCrossFade(220))
                    .transform(new RoundedCorners(dpToPx(10)))
                    .into(overlayCover);
        } else {
            Glide.with(this).clear(overlayCover);
            overlayCover.setImageDrawable(null);
        }
    }

    private void clearLyricsOnly() {
        stopRenderLoop();
        currentLyrics = null;
        lastActiveLineIndex = -1;
        lyricsContainer.removeAllViews();
        highlighter.clear();
        lineViews.clear();
    }

    private void showNoLyrics() {
        lyricsContainer.removeAllViews();
        highlighter.clear();
        lineViews.clear();
        TextView noLyrics = new TextView(this);
        noLyrics.setText("No lyrics");
        noLyrics.setTextColor(0x66FFFFFF);
        noLyrics.setTextSize(11);
        lyricsContainer.addView(noLyrics);
    }

    private void fetchLyrics(TrackInfo track) {
        AppLog.i(TAG, "Fetching lyrics for: " + track.title);
        final String fetchKey = track.stableKey();
        pendingFetchKey = fetchKey;
        lyricsManager.fetchLyrics(track, new LyricsProviderManager.LyricsCallback() {
            @Override
            public void onLyricsLoaded(LyricsData lyrics) {
                if (!fetchKey.equals(pendingFetchKey)) {
                    AppLog.d(TAG, "Stale lyrics ignored for: " + track.title);
                    return;
                }
                AppLog.i(TAG, "Lyrics loaded: " + lyrics.lines.size()
                        + " lines from " + lyrics.provider
                        + " type=" + lyrics.type);
                handler.post(() -> {
                    if (isDestroyed || !fetchKey.equals(pendingFetchKey)) return;
                    currentLyrics = lyrics;
                    if (currentTrack != null) updateTrackInfo(currentTrack);
                    renderOverlayLyrics(lyrics);
                });
            }

            @Override
            public void onLyricsError(String error) {
                if (!fetchKey.equals(pendingFetchKey)) return;
                AppLog.w(TAG, "Lyrics error: " + error);
                handler.post(() -> {
                    if (isDestroyed || !fetchKey.equals(pendingFetchKey)) return;
                    showNoLyrics();
                });
            }
        });
    }

    private void renderOverlayLyrics(LyricsData lyrics) {
        if (isDestroyed) return;
        if (lyrics == null || lyrics.lines == null || lyrics.lines.isEmpty()) {
            clearLyricsOnly();
            showNoLyrics();
            return;
        }

        lyricsContainer.removeAllViews();
        highlighter.clear();
        lineViews.clear();
        lastActiveLineIndex = -1;

        highlighter.setSyllableMode(lyrics.type == LyricsData.Type.SYLLABLE);

        for (int i = 0; i < lyrics.lines.size(); i++) {
            LyricsData.LyricsLine line = lyrics.lines.get(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Math.max(1, Math.round(spToPx(overlayFontSizeSp * 0.08f)));

            SyllableHighlighter.LineView lv = highlighter.createLineView(line, lp);
            lineViews.add(lv);
            lyricsContainer.addView(lv.rootView);
        }

        if (lyricsVisible) {
            startRenderLoop();
            jumpToCurrentLineAfterLayout();
        }
        AppLog.i(TAG, "Rendered " + lyrics.lines.size() + " lyric lines, Choreographer loop started");
    }

    private void updateHighlight(long position, double deltaTime) {
        if (isDestroyed || currentLyrics == null || lineViews.isEmpty()) return;

        int activeIndex = findActiveLine(position);
        if (activeIndex < 0) return;

        int n = lineViews.size();
        boolean seeked = Math.abs(activeIndex - lastActiveLineIndex) > 1;
        int start = seeked ? 0 : Math.max(0, activeIndex - 2);
        int end;
        if (seeked) {
            end = n;
        } else {
            List<LyricsData.LyricsLine> lines = currentLyrics.lines;
            end = activeIndex;
            while (end < n && lines.get(end).startTime - position < 3000) {
                end++;
            }
            end = Math.min(n, end + 2);
        }
        highlighter.updateHighlight(position, deltaTime, start, end);

        View activeView = lyricsContainer.getChildAt(activeIndex);
        if (activeView != null && springScroller != null) {
            lastActiveLineIndex = activeIndex;
            springScroller.followView(activeView, deltaTime);
        }
    }

    private int findActiveLine(long position) {
        if (currentLyrics == null || currentLyrics.lines == null || currentLyrics.lines.isEmpty()) return -1;
        int lastIndex = currentLyrics.lines.size() - 1;
        List<LyricsData.LyricsLine> lines = currentLyrics.lines;
        int lo = 0, hi = lastIndex, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).startTime <= position) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (ans < 0) return 0;
        return Math.min(ans, lyricsContainer.getChildCount() - 1);
    }

    private void clearOverlay() {
        if (isDestroyed) return;
        clearLyricsOnly();
        overlayTitle.setText("No music");
        overlayArtist.setText("");
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, com.lyricsync.app.MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, LyricSyncApp.CHANNEL_ID)
                .setContentTitle("LyricSync")
                .setContentText("Lyrics overlay active")
                .setSmallIcon(R.drawable.ic_close)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        isDestroyed = true;
        pendingFetchKey = null;
        super.onDestroy();
        stopRenderLoop();
        handler.removeCallbacksAndMessages(null);
        if (sharedPrefs != null) {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(settingsListener);
        }
        if (sessionTracker != null) sessionTracker.stop();
        if (lyricsManager != null) lyricsManager.shutdown();
        if (springScroller != null) springScroller.destroy();
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                AppLog.e(TAG, "Error removing overlay", e);
            }
        }
    }
}
