package com.lyricsync.app.overlay;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
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

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.lyricsync.app.LyricSyncApp;
import com.lyricsync.app.R;
import com.lyricsync.app.detection.MediaSessionTracker;
import com.lyricsync.app.lyrics.LyricsProviderManager;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.renderer.LyricAnimStyle;
import com.lyricsync.app.renderer.SpringScroller;
import com.lyricsync.app.renderer.SyllableHighlighter;
import com.lyricsync.app.ui.AlbumPalette;
import com.lyricsync.app.ui.Anim;
import com.lyricsync.app.ui.AnimStyleChips;
import com.lyricsync.app.ui.FontChips;
import com.lyricsync.app.util.AppFont;
import com.lyricsync.app.util.AppLog;
import com.lyricsync.app.util.Haptics;
import com.lyricsync.app.util.NowPlaying;
import com.lyricsync.app.util.Permissions;
import com.lyricsync.app.util.SeekBars;

import java.util.ArrayList;
import java.util.List;

public class FloatingOverlayService extends Service {
    private static final String TAG = "FloatingOverlay";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_STOP = "com.lyricsync.app.action.STOP";
    public static final String ACTION_TOGGLE = "com.lyricsync.app.action.TOGGLE";

    /** Sync slider is 0..3000 with 1500 meaning "no offset". */
    private static final int SYNC_LIMIT_MS = 1500;

    /** Read by MainActivity to render Start vs Stop without binding to the service. */
    private static volatile boolean running = false;

    public static boolean isRunning() {
        return running;
    }

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
    private View overlayHeaderActions;
    private boolean compactHeader;
    private ImageView overlayPrev;
    private ImageView overlayPlayPause;
    private ImageView overlayNext;
    private View overlaySettingsPanel;
    private SeekBar overlaySyncOffsetSlider;
    private TextView overlaySyncOffsetLabel;
    private LinearLayout overlayFontChips;
    private LinearLayout overlayAnimChips;
    private LinearLayout lyricsContainer;
    private ScrollView scrollView;
    private View overlayBody;
    private View overlaySkeleton;
    private View overlayEmpty;
    private TextView overlayEmptyTitle;
    private TextView overlayEmptyDesc;
    private WindowManager.LayoutParams overlayParams;
    private SharedPreferences sharedPrefs;

    private LyricsData currentLyrics;
    private TrackInfo currentTrack;
    private String lyricsStatusText;
    private volatile String pendingFetchKey = null;
    private boolean isDestroyed = false;
    private int lastActiveLineIndex = -1;
    private Typeface fontBold;
    private Typeface fontMedium;
    private int lyricAnimStyle = LyricAnimStyle.SPRING;
    private float overlayFontSizeSp = 13f;
    private int overlayWidthPercent = 88;
    private int lyricsHeightPx = 0;
    private boolean lyricsVisible = true;

    // Album-derived colouring
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private GradientDrawable overlayBackground;
    private ValueAnimator colorAnimator;
    private ObjectAnimator skeletonAnimator;
    private int overlayAccent = AlbumPalette.DEFAULT_ACCENT;
    private int overlayDeep = AlbumPalette.DEFAULT_DEEP;
    /** updateTrackInfo runs on metadata refreshes too; only re-run Palette on new art. */
    private Bitmap lastPaletteArt;
    /** Same guard for URI-loaded art; the palette runs on the decoded bitmap. */
    private String lastPaletteArtUri;
    /** Guards against a slow Glide load landing after the track already changed. */
    private String pendingArtUri;

    private final Runnable applySettingsRunnable = () -> applyRuntimeSettings(true);
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener = (prefs, key) -> {
        if ("font_scale".equals(key)
                || "overlay_width_percent".equals(key)
                || "overlay_height_percent".equals(key)) {
            handler.removeCallbacks(applySettingsRunnable);
            handler.postDelayed(applySettingsRunnable, 100);
        } else if (AppFont.PREF_KEY.equals(key) || LyricAnimStyle.PREF_KEY.equals(key)) {
            // Typeface / animation-style changes need a full highlighter rebuild,
            // and the panel chips must reflect choices made in the activity.
            handler.removeCallbacks(applySettingsRunnable);
            handler.postDelayed(applySettingsRunnable, 100);
            if (overlayFontChips != null) {
                FontChips.refresh(overlayFontChips,
                        prefs.getString(AppFont.PREF_KEY, AppFont.DEFAULT_KEY));
            }
            if (overlayAnimChips != null) {
                AnimStyleChips.refresh(overlayAnimChips,
                        LyricAnimStyle.current(prefs));
            }
        } else if ("sync_offset_ms".equals(key) && sessionTracker != null) {
            long offset = prefs.getLong("sync_offset_ms", 0);
            sessionTracker.setSyncOffsetMs(offset);
            syncOffsetSliderFromPrefs(offset);
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
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        sharedPrefs = getSharedPreferences("lyricsync", MODE_PRIVATE);
        loadTypefaces();
        lyricAnimStyle = LyricAnimStyle.current(sharedPrefs);

        overlayFontSizeSp = calculateFontSizeSp();
        highlighter = new SyllableHighlighter(this, fontBold, fontMedium, overlayFontSizeSp);
        highlighter.setAnimStyle(lyricAnimStyle);
        choreographer = Choreographer.getInstance();
        sharedPrefs.registerOnSharedPreferenceChangeListener(settingsListener);

        // Start foreground before any early-exit path so the system never sees a
        // foreground service that failed to post its notification.
        startForeground(NOTIFICATION_ID, buildNotification());

        if (!Permissions.isNotificationListenerEnabled(this)) {
            AppLog.w(TAG, "Notification listener not enabled, opening settings");
            Toast.makeText(this, "Please enable LyricSync notification access", Toast.LENGTH_LONG).show();
            Intent settingsIntent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(settingsIntent);
            stopSelf();
            return;
        }

        if (!Settings.canDrawOverlays(this)) {
            AppLog.w(TAG, "Overlay permission missing, cannot draw");
            Toast.makeText(this, "Please allow display over other apps", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }

        if (!createOverlay()) {
            stopSelf();
            return;
        }
        startTracker();
        running = true;
        AppLog.i(TAG, "Overlay service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_STOP:
                    animateOutAndStop();
                    return START_NOT_STICKY;
                case ACTION_TOGGLE:
                    if (overlayView != null) setLyricsVisible(!lyricsVisible, true, true);
                    return START_STICKY;
                default:
                    break;
            }
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ── Overlay construction ───────────────────────────────────────────────

    private boolean createOverlay() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_lyrics, null);

        overlayTitle = overlayView.findViewById(R.id.overlay_title);
        overlayArtist = overlayView.findViewById(R.id.overlay_artist);
        overlayCover = overlayView.findViewById(R.id.overlay_cover);
        overlayToggle = overlayView.findViewById(R.id.overlay_toggle);
        overlayClose = overlayView.findViewById(R.id.overlay_close);
        overlaySettings = overlayView.findViewById(R.id.overlay_settings);
        overlayHeaderActions = overlayView.findViewById(R.id.overlay_header_actions);
        overlayPrev = overlayView.findViewById(R.id.overlay_prev);
        overlayPlayPause = overlayView.findViewById(R.id.overlay_play_pause);
        overlayNext = overlayView.findViewById(R.id.overlay_next);
        overlaySettingsPanel = overlayView.findViewById(R.id.overlay_settings_panel);
        overlaySyncOffsetSlider = overlayView.findViewById(R.id.overlay_sync_offset_slider);
        overlaySyncOffsetLabel = overlayView.findViewById(R.id.overlay_sync_offset_label);
        overlayFontChips = overlayView.findViewById(R.id.overlay_font_chips);
        overlayAnimChips = overlayView.findViewById(R.id.overlay_anim_chips);
        lyricsContainer = overlayView.findViewById(R.id.overlay_lyrics_container);
        // Word/letter springs and the glow bloom overshoot view bounds; clipping at
        // the container would slice glyphs mid-animation ("cut letters").
        lyricsContainer.setClipChildren(false);
        lyricsContainer.setClipToPadding(false);
        scrollView = overlayView.findViewById(R.id.overlay_scroll);
        overlayBody = overlayView.findViewById(R.id.overlay_body);
        overlaySkeleton = overlayView.findViewById(R.id.overlay_skeleton);
        overlayEmpty = overlayView.findViewById(R.id.overlay_empty);
        overlayEmptyTitle = overlayView.findViewById(R.id.overlay_empty_title);
        overlayEmptyDesc = overlayView.findViewById(R.id.overlay_empty_desc);

        lyricsVisible = sharedPrefs.getBoolean("lyrics_visible", true);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int widthPercent = clamp(sharedPrefs.getInt("overlay_width_percent", 88), 55, 100);
        overlayWidthPercent = widthPercent;
        int heightPercent = clamp(sharedPrefs.getInt("overlay_height_percent", 36), 20, 70);
        int overlayWidth = Math.max(dpToPx(220), metrics.widthPixels * widthPercent / 100);
        lyricsHeightPx = Math.max(dpToPx(140), metrics.heightPixels * heightPercent / 100);

        ViewGroup.LayoutParams bodyParams = overlayBody.getLayoutParams();
        bodyParams.height = lyricsHeightPx;
        overlayBody.setLayoutParams(bodyParams);

        installOverlayBackground();
        applyResponsiveHeaderSizing();
        applyResponsiveLyricsSpacing();

        springScroller = new SpringScroller(scrollView);
        springScroller.setScrollPositionRatio(calculateScrollPositionRatio());

        overlayToggle.setOnClickListener(v -> {
            Haptics.tick(v);
            setLyricsVisible(!lyricsVisible, true, true);
        });
        overlayClose.setOnClickListener(v -> {
            Haptics.confirm(v);
            animateOutAndStop();
        });

        // Tap the title block (or the cover) to hide/show the header buttons, so a
        // long song title can use the full overlay width instead of being ellipsized.
        compactHeader = sharedPrefs.getBoolean("overlay_header_compact", false);
        View.OnClickListener compactToggle = v -> {
            Haptics.tick(v);
            setHeaderCompact(!compactHeader, true);
        };
        overlayView.findViewById(R.id.overlay_header_text).setOnClickListener(compactToggle);
        overlayCover.setOnClickListener(compactToggle);
        setHeaderCompact(compactHeader, false);

        setupTransportControls();
        setupSyncOffsetUi();
        setLyricsVisible(lyricsVisible, false, false);

        // Until a media session reports in, say so rather than showing an empty box.
        overlayTitle.setText(R.string.no_music);
        overlayArtist.setText("");
        showIdleState();

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                overlayWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.x = sharedPrefs.getInt("overlay_x", 0);
        params.y = sharedPrefs.getInt("overlay_y", dpToPx(64));
        overlayParams = params;

        setupDragging(params);

        try {
            windowManager.addView(overlayView, params);
        } catch (Exception e) {
            // A revoked overlay grant (or an OEM restriction) throws here; don't crash.
            AppLog.e(TAG, "Failed to add overlay view", e);
            overlayView = null;
            return false;
        }

        playOverlayEntrance();
        return true;
    }

    /** Replaces the static XML shape with a drawable we can recolour per album. */
    private void installOverlayBackground() {
        overlayBackground = new GradientDrawable();
        overlayBackground.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        overlayBackground.setCornerRadius(dpToPx(28));
        applyOverlayColors(overlayAccent, overlayDeep);
        overlayView.setBackground(overlayBackground);
    }

    private void playOverlayEntrance() {
        // Bubble entrance: the overlay inflates from a blob with a rubbery overshoot.
        Anim.bubbleIn(overlayView);
    }

    private void animateOutAndStop() {
        if (overlayView == null) {
            stopSelf();
            return;
        }
        overlayView.animate()
                .alpha(0f)
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(200)
                .setInterpolator(Anim.STANDARD)
                .withEndAction(this::stopSelf)
                .start();
    }

    // ── Colour ─────────────────────────────────────────────────────────────

    private void applyAlbumColors(Bitmap art) {
        AlbumPalette.from(art, (accent, deep) -> {
            if (isDestroyed) return;
            animateOverlayColors(accent, deep);
        });
    }

    private void animateOverlayColors(int accent, int deep) {
        if (accent == overlayAccent && deep == overlayDeep) return;
        if (colorAnimator != null) colorAnimator.cancel();

        final int fromAccent = overlayAccent;
        final int fromDeep = overlayDeep;
        final int toAccent = accent;
        final int toDeep = deep;
        overlayAccent = accent;
        overlayDeep = deep;

        colorAnimator = ValueAnimator.ofFloat(0f, 1f);
        colorAnimator.setDuration(650);
        colorAnimator.setInterpolator(Anim.STANDARD);
        colorAnimator.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            int ac = (Integer) argb.evaluate(f, fromAccent, toAccent);
            int dp = (Integer) argb.evaluate(f, fromDeep, toDeep);
            applyOverlayColors(ac, dp);
        });
        colorAnimator.start();
    }

    private void applyOverlayColors(int accent, int deep) {
        if (overlayBackground != null) {
            // Top edge leans toward the accent, the body stays near-black so lyrics
            // keep their contrast whatever the album art is.
            int top = AlbumPalette.withAlpha(AlbumPalette.blend(deep, accent, 0.18f), 0xF5);
            int mid = AlbumPalette.withAlpha(deep, 0xF0);
            int bottom = AlbumPalette.withAlpha(AlbumPalette.blend(deep, 0xFF000000, 0.45f), 0xF7);
            overlayBackground.setColors(new int[]{top, mid, bottom});
            overlayBackground.setStroke(Math.max(1, dpToPx(1)),
                    AlbumPalette.withAlpha(accent, 0x4D));
        }
        ColorStateList tint = ColorStateList.valueOf(accent);
        if (overlaySyncOffsetSlider != null) {
            overlaySyncOffsetSlider.setProgressTintList(tint);
            overlaySyncOffsetSlider.setThumbTintList(tint);
        }
        if (overlaySyncOffsetLabel != null) {
            overlaySyncOffsetLabel.setTextColor(accent);
        }
        if (overlayPlayPause != null) {
            overlayPlayPause.setColorFilter(accent);
        }
    }

    // ── Transport ──────────────────────────────────────────────────────────

    private void setupTransportControls() {
        overlayPrev.setOnClickListener(v -> {
            Haptics.tick(v);
            if (sessionTracker != null) sessionTracker.skipPrevious();
        });
        overlayNext.setOnClickListener(v -> {
            Haptics.tick(v);
            if (sessionTracker != null) sessionTracker.skipNext();
        });
        overlayPlayPause.setOnClickListener(v -> {
            Haptics.confirm(v);
            if (sessionTracker != null) sessionTracker.togglePlayPause();
            Anim.pop(v);
            handler.postDelayed(this::updatePlayPauseIcon, 180);
        });
        overlayPlayPause.setColorFilter(overlayAccent);
    }

    private void updatePlayPauseIcon() {
        if (overlayPlayPause == null) return;
        boolean playing = sessionTracker != null && sessionTracker.isPlaying();
        overlayPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        overlayPlayPause.setColorFilter(overlayAccent);
    }

    // ── Sizing ─────────────────────────────────────────────────────────────

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int clamp(int value, int lo, int hi) {
        return value < lo ? lo : (value > hi ? hi : value);
    }

    private float calculateFontSizeSp() {
        float screenWidthDp = getResources().getConfiguration().screenWidthDp;
        float baseFontSize = Math.max(11f, Math.min(22f, screenWidthDp * 0.038f));
        float fontScale = Math.max(0.5f, Math.min(2.0f, sharedPrefs.getFloat("font_scale", 1.0f)));
        return Math.max(9f, Math.min(30f, baseFontSize * fontScale));
    }

    /** (Re)load the user's chosen typeface pair through the shared registry. */
    private void loadTypefaces() {
        AppFont.FontPair pair = AppFont.current(this);
        fontBold = pair.bold;
        fontMedium = pair.medium;
    }

    private void applyRuntimeSettings(boolean rebuildLyrics) {
        if (overlayView == null || overlayBody == null || lyricsContainer == null) return;

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int newWidthPercent = clamp(sharedPrefs.getInt("overlay_width_percent", 88), 55, 100);
        int newHeightPercent = clamp(sharedPrefs.getInt("overlay_height_percent", 36), 20, 70);
        float newFontSizeSp = calculateFontSizeSp();
        int newAnimStyle = LyricAnimStyle.current(sharedPrefs);
        Typeface prevBold = fontBold;
        loadTypefaces();
        boolean typefaceChanged = fontBold != prevBold;
        boolean styleChanged = newAnimStyle != lyricAnimStyle;
        lyricAnimStyle = newAnimStyle;

        boolean fontChanged = Math.abs(newFontSizeSp - overlayFontSizeSp) > 0.05f;
        overlayFontSizeSp = newFontSizeSp;
        overlayWidthPercent = newWidthPercent;
        lyricsHeightPx = Math.max(dpToPx(140), metrics.heightPixels * newHeightPercent / 100);

        if (lyricsVisible) {
            ViewGroup.LayoutParams bodyParams = overlayBody.getLayoutParams();
            bodyParams.height = lyricsHeightPx;
            overlayBody.setLayoutParams(bodyParams);
        }

        if (overlayParams != null && windowManager != null) {
            overlayParams.width = Math.max(dpToPx(220), metrics.widthPixels * newWidthPercent / 100);
            clampOverlayPosition(overlayParams);
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

        if (rebuildLyrics && currentLyrics != null && (fontChanged || typefaceChanged || styleChanged)) {
            highlighter = new SyllableHighlighter(this, fontBold, fontMedium, overlayFontSizeSp);
            highlighter.setAnimStyle(lyricAnimStyle);
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

    // ── Collapse / expand ──────────────────────────────────────────────────

    /**
     * Compact header: hide the toggle/settings/close buttons so long titles get the
     * full overlay width (two lines instead of an ellipsis). Tap the title block to
     * bring the buttons back. Persisted across restarts.
     */
    private void setHeaderCompact(boolean compact, boolean animate) {
        compactHeader = compact;
        sharedPrefs.edit().putBoolean("overlay_header_compact", compact).apply();
        if (overlayHeaderActions == null) return;

        // An open settings panel would be stranded without its gear button.
        if (compact && overlaySettingsPanel.getVisibility() == View.VISIBLE) {
            Anim.collapse(overlaySettingsPanel);
            Anim.rotateTo(overlaySettings, 0f);
        }

        overlayHeaderActions.animate().cancel();
        if (animate) {
            if (!compact) {
                overlayHeaderActions.setAlpha(0f);
                overlayHeaderActions.setVisibility(View.VISIBLE);
            }
            overlayHeaderActions.animate()
                    .alpha(compact ? 0f : 1f)
                    .setDuration(Anim.D_FAST)
                    .withEndAction(() -> {
                        if (compact) overlayHeaderActions.setVisibility(View.GONE);
                    })
                    .start();
        } else {
            overlayHeaderActions.setAlpha(compact ? 0f : 1f);
            overlayHeaderActions.setVisibility(compact ? View.GONE : View.VISIBLE);
        }

        overlayTitle.setMaxLines(compact ? 2 : 1);
    }

    private void setLyricsVisible(boolean visible, boolean persist, boolean animate) {
        lyricsVisible = visible;
        if (persist) {
            sharedPrefs.edit().putBoolean("lyrics_visible", visible).apply();
        }
        Anim.rotateTo(overlayToggle, visible ? 0f : 180f);
        overlayToggle.setAlpha(visible ? 1.0f : 0.65f);

        if (animate) {
            animateBody(visible);
        } else {
            ViewGroup.LayoutParams lp = overlayBody.getLayoutParams();
            lp.height = lyricsHeightPx;
            overlayBody.setLayoutParams(lp);
            overlayBody.setAlpha(visible ? 1f : 0f);
            overlayBody.setVisibility(visible ? View.VISIBLE : View.GONE);
        }

        if (visible) {
            startRenderLoop();
            jumpToCurrentLineAfterLayout();
        } else {
            stopRenderLoop();
        }
        updateNotification();
    }

    /**
     * Animates the body open/closed. The height is restored to the configured lyrics
     * height (not WRAP_CONTENT) so the next expand starts from the right size.
     */
    private void animateBody(final boolean show) {
        final ViewGroup.LayoutParams lp = overlayBody.getLayoutParams();
        if (lp == null) return;

        int from = show ? 0 : Math.max(1, overlayBody.getHeight());
        int to = show ? lyricsHeightPx : 0;
        if (show) {
            lp.height = 0;
            overlayBody.setLayoutParams(lp);
            overlayBody.setVisibility(View.VISIBLE);
        }

        ValueAnimator anim = ValueAnimator.ofInt(from, to);
        anim.setDuration(Anim.D_MED);
        // Expanding gets a gentle rubber-band overshoot; collapsing stays smooth.
        anim.setInterpolator(show ? new android.view.animation.OvershootInterpolator(0.8f)
                : Anim.STANDARD);
        anim.addUpdateListener(a -> {
            lp.height = (int) a.getAnimatedValue();
            overlayBody.setLayoutParams(lp);
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!show) overlayBody.setVisibility(View.GONE);
                lp.height = lyricsHeightPx;
                overlayBody.setLayoutParams(lp);
            }
        });
        overlayBody.animate().alpha(show ? 1f : 0f).setDuration(Anim.D_MED).start();
        anim.start();
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

    // ── Sync offset UI ─────────────────────────────────────────────────────

    private void setupSyncOffsetUi() {
        int saved = clamp((int) sharedPrefs.getLong("sync_offset_ms", 0), -SYNC_LIMIT_MS, SYNC_LIMIT_MS);
        updateSyncOffsetLabel(saved);
        buildOverlayChips();

        overlaySettings.setOnClickListener(v -> {
            Haptics.tick(v);
            boolean visible = overlaySettingsPanel.getVisibility() == View.VISIBLE;
            if (visible) {
                Anim.collapse(overlaySettingsPanel);
            } else {
                Anim.expand(overlaySettingsPanel);
                updatePlayPauseIcon();
            }
            Anim.rotateTo(overlaySettings, visible ? 0f : 90f);
        });

        SeekBars.bind(overlaySyncOffsetSlider, saved + SYNC_LIMIT_MS, (progress, fromUser) -> {
            int offset = clamp(progress - SYNC_LIMIT_MS, -SYNC_LIMIT_MS, SYNC_LIMIT_MS);
            updateSyncOffsetLabel(offset);
            sharedPrefs.edit().putLong("sync_offset_ms", offset).apply();
            if (sessionTracker != null) {
                sessionTracker.setSyncOffsetMs(offset);
            }
            if (fromUser) Haptics.tick(overlaySyncOffsetSlider);
        });
    }

    /** Keeps the overlay slider in step when the offset is changed from the activity. */
    private void syncOffsetSliderFromPrefs(long offset) {
        if (overlaySyncOffsetSlider == null) return;
        int clamped = clamp((int) offset, -SYNC_LIMIT_MS, SYNC_LIMIT_MS);
        if (overlaySyncOffsetSlider.getProgress() != clamped + SYNC_LIMIT_MS) {
            overlaySyncOffsetSlider.setProgress(clamped + SYNC_LIMIT_MS);
        }
        updateSyncOffsetLabel(clamped);
    }

    /** Font + animation chips inside the settings panel; choices apply live via prefs. */
    private void buildOverlayChips() {
        if (overlayFontChips != null) {
            FontChips.build(this, overlayFontChips,
                    AppFont.currentKey(sharedPrefs), true,
                    style -> Haptics.tick(overlayFontChips));
        }
        if (overlayAnimChips != null) {
            AnimStyleChips.buildCompact(this, overlayAnimChips,
                    LyricAnimStyle.current(sharedPrefs),
                    variant -> Haptics.tick(overlayAnimChips));
        }
    }

    private void updateSyncOffsetLabel(int ms) {
        if (ms == 0) {
            overlaySyncOffsetLabel.setText("0 ms");
            return;
        }
        overlaySyncOffsetLabel.setText((ms > 0 ? "+" : "") + ms + " ms");
    }

    // ── Dragging ───────────────────────────────────────────────────────────

    private void setupDragging(WindowManager.LayoutParams params) {
        final int[] touchX = new int[1];
        final int[] touchY = new int[1];
        final int[] paramX = new int[1];
        final int[] paramY = new int[1];
        final boolean[] isDragging = new boolean[1];
        final boolean[] wasClamped = new boolean[1];
        final int touchSlop = dpToPx(10);

        overlayView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    touchX[0] = (int) event.getRawX();
                    touchY[0] = (int) event.getRawY();
                    paramX[0] = params.x;
                    paramY[0] = params.y;
                    isDragging[0] = false;
                    wasClamped[0] = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - touchX[0];
                    float dy = event.getRawY() - touchY[0];
                    if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                        isDragging[0] = true;
                    }
                    if (isDragging[0]) {
                        params.x = paramX[0] + (int) dx;
                        params.y = paramY[0] + (int) dy;
                        // Without this the overlay can be flung off-screen and never recovered.
                        boolean clamped = clampOverlayPosition(params);
                        if (clamped && !wasClamped[0]) {
                            Haptics.snap(v);
                        }
                        wasClamped[0] = clamped;
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
                    if (isDragging[0]) {
                        persistOverlayPosition(params);
                        // Let the bubble "land" after a drag instead of stopping dead.
                        Anim.bubbleSettle(overlayView);
                    }
                    return isDragging[0];
            }
            return false;
        });
    }

    /** Keeps the whole overlay on screen. Returns true if a bound had to be applied. */
    private boolean clampOverlayPosition(WindowManager.LayoutParams params) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int viewWidth = overlayView != null && overlayView.getWidth() > 0
                ? overlayView.getWidth() : params.width;
        int viewHeight = overlayView != null && overlayView.getHeight() > 0
                ? overlayView.getHeight() : dpToPx(200);

        // Gravity is TOP|CENTER_HORIZONTAL, so x is an offset from the horizontal centre.
        int maxX = Math.max(0, (metrics.widthPixels - viewWidth) / 2);
        int maxY = Math.max(0, metrics.heightPixels - viewHeight);

        int newX = Math.max(-maxX, Math.min(maxX, params.x));
        int newY = Math.max(0, Math.min(maxY, params.y));
        boolean clamped = newX != params.x || newY != params.y;
        params.x = newX;
        params.y = newY;
        return clamped;
    }

    private void persistOverlayPosition(WindowManager.LayoutParams params) {
        sharedPrefs.edit()
                .putInt("overlay_x", params.x)
                .putInt("overlay_y", params.y)
                .apply();
    }

    // ── Tracking ───────────────────────────────────────────────────────────

    private void startTracker() {
        lyricsManager = new LyricsProviderManager(this);
        sessionTracker = new MediaSessionTracker(this);
        sessionTracker.setSyncOffsetMs(
                clamp((int) sharedPrefs.getLong("sync_offset_ms", 0), -SYNC_LIMIT_MS, SYNC_LIMIT_MS));

        sessionTracker.start(
                new MediaSessionTracker.TrackCallback() {
                    @Override
                    public void onTrackChanged(TrackInfo track) {
                        AppLog.i(TAG, "Track changed: " + track.title + " - " + track.artist);
                        handler.post(() -> {
                            if (isDestroyed) return;
                            currentTrack = track;
                            lyricsStatusText = null;
                            updateTrackInfo(track);
                            clearLyricsOnly();
                            showLoadingState();
                            fetchLyrics(track);
                            updateNotification();
                            publishNowPlaying();
                            // Squash-and-bounce once so a new song visibly "lands".
                            if (overlayView != null) Anim.bubblePulse(overlayView);
                        });
                    }

                    @Override
                    public void onTrackUpdated(TrackInfo track) {
                        AppLog.d(TAG, "Track info updated: " + track.title + " - " + track.artist);
                        handler.post(() -> {
                            if (isDestroyed) return;
                            currentTrack = track;
                            updateTrackInfo(track);
                            publishNowPlaying();
                        });
                    }

                    @Override
                    public void onTrackCleared() {
                        AppLog.i(TAG, "Track cleared");
                        handler.post(() -> {
                            if (isDestroyed) return;
                            clearOverlay();
                        });
                    }
                },
                (state, position) -> {
                    // Position/state come from the tracker; we only need this signal to
                    // wake the render loop if it had gone idle. Values are re-read per frame.
                    if (renderRunning && !renderActive) {
                        startFrameCallback();
                    }
                    updatePlayPauseIcon();
                    publishNowPlaying();
                }
        );
    }

    private void publishNowPlaying() {
        TrackInfo track = currentTrack;
        if (track == null) {
            NowPlaying.clear();
            return;
        }
        boolean playing = sessionTracker != null && sessionTracker.isPlaying();
        NowPlaying.publish(new NowPlaying.Snapshot(
                track.title, track.artist, lyricsStatusText, track.albumArtBitmap, playing, true));
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
                dt = Math.min((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0, 0.1);
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
        Anim.setTextAnimated(overlayTitle, track.title);
        Anim.setTextAnimated(overlayArtist, track.artist);

        if (track.albumArtBitmap != null) {
            pendingArtUri = null;
            Glide.with(this)
                    .load(track.albumArtBitmap)
                    .transition(DrawableTransitionOptions.withCrossFade(220))
                    .transform(new RoundedCorners(dpToPx(10)))
                    .into(overlayCover);
            if (track.albumArtBitmap != lastPaletteArt) {
                lastPaletteArt = track.albumArtBitmap;
                lastPaletteArtUri = null;
                applyAlbumColors(track.albumArtBitmap);
            }
        } else if (track.albumArtUri != null && !track.albumArtUri.trim().isEmpty()) {
            // Decode through a target (not .into(ImageView)) so the same bitmap can
            // feed both the cover view and the palette — previously URI art never
            // re-tinted the overlay at all.
            final String artUri = track.albumArtUri;
            pendingArtUri = artUri;
            Glide.with(this)
                    .asBitmap()
                    .load(artUri)
                    .transform(new RoundedCorners(dpToPx(10)))
                    .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                        @Override
                        public void onResourceReady(@androidx.annotation.NonNull Bitmap resource,
                                                    @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                            if (isDestroyed || !artUri.equals(pendingArtUri)) return;
                            overlayCover.setAlpha(0f);
                            overlayCover.setImageBitmap(resource);
                            overlayCover.animate().alpha(1f).setDuration(220).start();
                            if (!artUri.equals(lastPaletteArtUri)) {
                                lastPaletteArtUri = artUri;
                                lastPaletteArt = null;
                                applyAlbumColors(resource);
                            }
                        }

                        @Override
                        public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                        }
                    });
        } else {
            pendingArtUri = null;
            lastPaletteArtUri = null;
            Glide.with(this).clear(overlayCover);
            overlayCover.setImageDrawable(null);
        }
    }

    // ── Body states ────────────────────────────────────────────────────────

    private void showLoadingState() {
        if (overlaySkeleton == null) return;
        scrollView.setVisibility(View.INVISIBLE);
        overlayEmpty.setVisibility(View.GONE);
        overlaySkeleton.setVisibility(View.VISIBLE);
        startSkeletonShimmer();
    }

    private void showLyricsState() {
        if (overlaySkeleton == null) return;
        stopSkeletonShimmer();
        overlaySkeleton.setVisibility(View.GONE);
        overlayEmpty.setVisibility(View.GONE);
        if (scrollView.getVisibility() != View.VISIBLE) {
            scrollView.setVisibility(View.VISIBLE);
            scrollView.setAlpha(0f);
            scrollView.animate().alpha(1f).setDuration(Anim.D_MED).start();
        }
    }

    /** Nothing is playing at all — different message from "this track has no lyrics". */
    private void showIdleState() {
        showEmptyState(R.string.nothing_playing, R.string.nothing_playing_desc);
    }

    private void showEmptyState() {
        showEmptyState(R.string.lyrics_none, R.string.lyrics_none_desc);
    }

    private void showEmptyState(int titleRes, int descRes) {
        if (overlaySkeleton == null) return;
        if (overlayEmptyTitle != null) overlayEmptyTitle.setText(titleRes);
        if (overlayEmptyDesc != null) overlayEmptyDesc.setText(descRes);
        stopSkeletonShimmer();
        overlaySkeleton.setVisibility(View.GONE);
        scrollView.setVisibility(View.INVISIBLE);
        overlayEmpty.setVisibility(View.VISIBLE);
        overlayEmpty.setAlpha(0f);
        overlayEmpty.animate().alpha(1f).setDuration(Anim.D_MED).start();
    }

    private void startSkeletonShimmer() {
        if (skeletonAnimator != null) return;
        skeletonAnimator = ObjectAnimator.ofFloat(overlaySkeleton, View.ALPHA, 0.35f, 0.9f);
        skeletonAnimator.setDuration(760);
        skeletonAnimator.setRepeatMode(ValueAnimator.REVERSE);
        skeletonAnimator.setRepeatCount(ValueAnimator.INFINITE);
        skeletonAnimator.setInterpolator(Anim.STANDARD);
        skeletonAnimator.start();
    }

    private void stopSkeletonShimmer() {
        if (skeletonAnimator == null) return;
        skeletonAnimator.cancel();
        skeletonAnimator = null;
        if (overlaySkeleton != null) overlaySkeleton.setAlpha(1f);
    }

    private void clearLyricsOnly() {
        stopRenderLoop();
        currentLyrics = null;
        lastActiveLineIndex = -1;
        lyricsContainer.removeAllViews();
        highlighter.clear();
        lineViews.clear();
    }

    // ── Lyrics ─────────────────────────────────────────────────────────────

    private void fetchLyrics(TrackInfo track) {
        AppLog.i(TAG, "Fetching lyrics for: " + track.title);
        final String fetchKey = track.title + "|||" + track.artist;
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
                    lyricsStatusText = describeLyrics(lyrics);
                    if (currentTrack != null) updateTrackInfo(currentTrack);
                    renderOverlayLyrics(lyrics);
                    publishNowPlaying();
                });
            }

            @Override
            public void onLyricsError(String error) {
                if (!fetchKey.equals(pendingFetchKey)) return;
                AppLog.w(TAG, "Lyrics error: " + error);
                handler.post(() -> {
                    if (isDestroyed || !fetchKey.equals(pendingFetchKey)) return;
                    lyricsStatusText = null;
                    clearLyricsOnly();
                    showEmptyState();
                    publishNowPlaying();
                });
            }
        });
    }

    private String describeLyrics(LyricsData lyrics) {
        String kind;
        if (lyrics.type == LyricsData.Type.SYLLABLE) {
            kind = "WORD-BY-WORD";
        } else if (lyrics.type == LyricsData.Type.LINE) {
            kind = "LINE-SYNCED";
        } else {
            kind = "UNSYNCED";
        }
        return lyrics.provider == null || lyrics.provider.isEmpty()
                ? kind : kind + " · " + lyrics.provider.toUpperCase(java.util.Locale.ROOT);
    }

    private void renderOverlayLyrics(LyricsData lyrics) {
        if (isDestroyed) return;
        if (lyrics == null || lyrics.lines == null || lyrics.lines.isEmpty()) {
            clearLyricsOnly();
            showEmptyState();
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

            if (!line.isInterlude) {
                final int index = i;
                lv.rootView.setOnClickListener(v -> seekToLine(index));
            }

            lyricsContainer.addView(lv.rootView);
        }

        showLyricsState();
        if (lyricsVisible) {
            startRenderLoop();
            jumpToCurrentLineAfterLayout();
        }
        AppLog.i(TAG, "Rendered " + lyrics.lines.size() + " lyric lines, Choreographer loop started");
    }

    /** Tap a line to jump the player there. No-op on players that don't support seeking. */
    private void seekToLine(int index) {
        if (currentLyrics == null || currentLyrics.lines == null || sessionTracker == null) return;
        if (index < 0 || index >= currentLyrics.lines.size()) return;
        if (!sessionTracker.hasController()) return;

        long target = Math.max(0, currentLyrics.lines.get(index).startTime);
        sessionTracker.seekTo(target);
        AppLog.i(TAG, "Seek to line " + index + " @ " + target + "ms");

        View lineView = lyricsContainer.getChildAt(index);
        if (lineView != null) {
            Haptics.confirm(lineView);
            Anim.pop(lineView);
        }
        lastActiveLineIndex = index;
        // Force a frame even while paused so the highlight follows the seek immediately.
        startFrameCallback();
        if (springScroller != null && lineView != null) {
            springScroller.scrollToView(lineView, true);
        }
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
        pendingFetchKey = null;
        currentTrack = null;
        lyricsStatusText = null;
        lastPaletteArt = null;
        lastPaletteArtUri = null;
        pendingArtUri = null;
        clearLyricsOnly();
        Anim.setTextAnimated(overlayTitle, getString(R.string.no_music));
        Anim.setTextAnimated(overlayArtist, "");
        Glide.with(this).clear(overlayCover);
        overlayCover.setImageDrawable(null);
        showIdleState();
        animateOverlayColors(AlbumPalette.DEFAULT_ACCENT, AlbumPalette.DEFAULT_DEEP);
        NowPlaying.clear();
        updateNotification();
    }

    // ── Notification ───────────────────────────────────────────────────────

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, com.lyricsync.app.MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent toggleIntent = new Intent(this, FloatingOverlayService.class).setAction(ACTION_TOGGLE);
        PendingIntent togglePi = PendingIntent.getService(this, 1, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, FloatingOverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        TrackInfo track = currentTrack;
        String title = track != null && track.title != null ? track.title : getString(R.string.app_name);
        String text = track != null && track.artist != null
                ? track.artist : getString(R.string.notif_active);

        return new NotificationCompat.Builder(this, LyricSyncApp.CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(0, getString(lyricsVisible ? R.string.notif_hide : R.string.notif_show), togglePi)
                .addAction(0, getString(R.string.notif_stop), stopPi)
                .build();
    }

    private void updateNotification() {
        if (isDestroyed) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        try {
            nm.notify(NOTIFICATION_ID, buildNotification());
        } catch (Exception e) {
            AppLog.w(TAG, "Notification update failed: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        isDestroyed = true;
        running = false;
        super.onDestroy();
        stopRenderLoop();
        stopSkeletonShimmer();
        if (colorAnimator != null) colorAnimator.cancel();
        handler.removeCallbacksAndMessages(null);
        if (sharedPrefs != null) {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(settingsListener);
        }
        if (sessionTracker != null) sessionTracker.stop();
        if (lyricsManager != null) lyricsManager.shutdown();
        if (springScroller != null) springScroller.destroy();
        NowPlaying.clear();
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                AppLog.e(TAG, "Error removing overlay", e);
            }
        }
    }
}
