package com.lyricsync.app.overlay;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.lyricsync.app.LyricSyncApp;
import com.lyricsync.app.R;
import com.lyricsync.app.detection.MediaNotificationListener;
import com.lyricsync.app.detection.MediaSessionTracker;
import com.lyricsync.app.lyrics.LyricsProviderManager;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.renderer.SpringScroller;
import com.lyricsync.app.renderer.SyllableHighlighter;
import com.lyricsync.app.util.AppLog;

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
    private LinearLayout lyricsContainer;
    private ScrollView scrollView;
    private WindowManager.LayoutParams overlayParams;
    private SharedPreferences sharedPrefs;

    private LyricsData currentLyrics;
    private TrackInfo currentTrack;
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
        }
    };

    private SyllableHighlighter highlighter;
    private final List<SyllableHighlighter.LineView> lineViews = new ArrayList<>();
    private SpringScroller springScroller;

    private Choreographer choreographer;
    private boolean renderRunning = false;
    private long lastFrameNanos = 0;

    @Override
    public void onCreate() {
        super.onCreate();
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

        if (!isNotificationListenerEnabled()) {
            AppLog.w(TAG, "Notification listener not enabled, opening settings");
            Toast.makeText(this, "Please enable LyricSync notification access", Toast.LENGTH_LONG).show();
            Intent settingsIntent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(settingsIntent);
            stopSelf();
            return;
        }

        createOverlay();
        startTracker();
        startForeground(NOTIFICATION_ID, buildNotification());
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
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createOverlay() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_lyrics, null);

        overlayTitle = overlayView.findViewById(R.id.overlay_title);
        overlayArtist = overlayView.findViewById(R.id.overlay_artist);
        overlayCover = overlayView.findViewById(R.id.overlay_cover);
        overlayToggle = overlayView.findViewById(R.id.overlay_toggle);
        overlayClose = overlayView.findViewById(R.id.overlay_close);
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
        windowManager.addView(overlayView, params);
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private float calculateFontSizeSp() {
        float screenWidthDp = getResources().getConfiguration().screenWidthDp;
        float baseFontSize = Math.max(11f, Math.min(22f, screenWidthDp * 0.038f));
        SharedPreferences prefs = sharedPrefs != null ? sharedPrefs : getSharedPreferences("lyricsync", MODE_PRIVATE);
        float fontScale = Math.max(0.5f, Math.min(2.0f, prefs.getFloat("font_scale", 1.0f)));
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
                        windowManager.updateViewLayout(overlayView, params);
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

        sessionTracker.start(
                new MediaSessionTracker.TrackCallback() {
                    @Override
                    public void onTrackChanged(TrackInfo track) {
                        currentTrack = track;
                        AppLog.i(TAG, "Track changed: " + track.title + " - " + track.artist);
                        handler.post(() -> {
                            updateTrackInfo(track);
                            clearLyricsOnly();
                            fetchLyrics(track);
                        });
                    }

                    @Override
                    public void onTrackUpdated(TrackInfo track) {
                        currentTrack = track;
                        AppLog.d(TAG, "Track info updated: " + track.title + " - " + track.artist);
                        handler.post(() -> updateTrackInfo(track));
                    }

                    @Override
                    public void onTrackCleared() {
                        AppLog.i(TAG, "Track cleared");
                        handler.post(() -> clearOverlay());
                    }
                },
                (state, position) -> {}
        );
    }

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!renderRunning) return;

            if (sessionTracker.isPlaying() && currentLyrics != null && !lineViews.isEmpty()) {
                long position = sessionTracker.getCurrentPosition();

                double dt;
                if (lastFrameNanos > 0) {
                    dt = Math.min((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0, 0.1);
                } else {
                    dt = 1.0 / 60.0;
                }
                lastFrameNanos = frameTimeNanos;

                updateHighlight(position, dt);
                highlighter.animateInterlude(position, dt);
            }

            choreographer.postFrameCallback(this);
        }
    };

    private void startRenderLoop() {
        stopRenderLoop();
        renderRunning = true;
        lastFrameNanos = 0;
        choreographer.postFrameCallback(frameCallback);
    }

    private void stopRenderLoop() {
        renderRunning = false;
        choreographer.removeFrameCallback(frameCallback);
    }

    private void updateTrackInfo(TrackInfo track) {
        overlayTitle.setText(track.title);
        overlayArtist.setText(track.artist);

        if (track.albumArtBitmap != null) {
            Glide.with(this)
                    .load(track.albumArtBitmap)
                    .dontAnimate()
                    .circleCrop()
                    .into(overlayCover);
        } else if (track.albumArtUri != null && !track.albumArtUri.trim().isEmpty()) {
            Glide.with(this)
                    .load(track.albumArtUri)
                    .dontAnimate()
                    .circleCrop()
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

    private void fetchLyrics(TrackInfo track) {
        AppLog.i(TAG, "Fetching lyrics for: " + track.title);
        lyricsManager.fetchLyrics(track, new LyricsProviderManager.LyricsCallback() {
            @Override
            public void onLyricsLoaded(LyricsData lyrics) {
                currentLyrics = lyrics;
                AppLog.i(TAG, "Lyrics loaded: " + lyrics.lines.size()
                        + " lines from " + lyrics.provider
                        + " type=" + lyrics.type);
                handler.post(() -> {
                    if (currentTrack != null) updateTrackInfo(currentTrack);
                    renderOverlayLyrics(lyrics);
                });
            }

            @Override
            public void onLyricsError(String error) {
                AppLog.w(TAG, "Lyrics error: " + error);
                handler.post(() -> {
                    lyricsContainer.removeAllViews();
                    highlighter.clear();
                    lineViews.clear();
                    TextView errorText = new TextView(FloatingOverlayService.this);
                    errorText.setText("No lyrics");
                    errorText.setTextColor(0x66FFFFFF);
                    errorText.setTextSize(11);
                    lyricsContainer.addView(errorText);
                });
            }
        });
    }

    private void renderOverlayLyrics(LyricsData lyrics) {
        if (lyrics == null || lyrics.lines == null || lyrics.lines.isEmpty()) {
            clearLyricsOnly();
            TextView emptyText = new TextView(FloatingOverlayService.this);
            emptyText.setText("No lyrics");
            emptyText.setTextColor(0x66FFFFFF);
            emptyText.setTextSize(11);
            lyricsContainer.addView(emptyText);
            return;
        }

        lyricsContainer.removeAllViews();
        highlighter.clear();
        lineViews.clear();
        lastActiveLineIndex = -1;

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
        if (currentLyrics == null || lineViews.isEmpty()) return;

        highlighter.updateHighlight(position, deltaTime);

        int activeIndex = findActiveLine(position);
        if (activeIndex >= 0) {
            View activeView = lyricsContainer.getChildAt(activeIndex);
            if (activeView != null && springScroller != null) {
                if (activeIndex != lastActiveLineIndex) {
                    lastActiveLineIndex = activeIndex;
                }
                springScroller.followView(activeView, deltaTime);
            }
        }
    }

    private int findActiveLine(long position) {
        if (currentLyrics == null || currentLyrics.lines == null || currentLyrics.lines.isEmpty()) return -1;
        int lastIndex = currentLyrics.lines.size() - 1;
        for (int i = lastIndex; i >= 0; i--) {
            LyricsData.LyricsLine line = currentLyrics.lines.get(i);
            if (position >= line.startTime) {
                return Math.min(i, lyricsContainer.getChildCount() - 1);
            }
        }
        return 0;
    }

    private void clearOverlay() {
        stopRenderLoop();
        lyricsContainer.removeAllViews();
        highlighter.clear();
        lineViews.clear();
        overlayTitle.setText("No music");
        overlayArtist.setText("");
        currentLyrics = null;
        lastActiveLineIndex = -1;
    }

    private boolean isNotificationListenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (flat != null) {
            ComponentName cn = new ComponentName(this, MediaNotificationListener.class);
            return flat.contains(cn.flattenToString());
        }
        return false;
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
