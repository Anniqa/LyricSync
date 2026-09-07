package com.lyricsync.app.overlay;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import com.lyricsync.app.util.LocaleHelper;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.lyricsync.app.ai.auth.AntigravityAuthManager;
import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.ai.client.AntigravityQuotaClient;
import com.lyricsync.app.ai.history.AiChatHistoryManager;
import com.lyricsync.app.ai.model.AiChatMessage;
import com.lyricsync.app.ai.model.QuotaSummary;
import com.lyricsync.app.ai.service.SongChatManager;
import com.lyricsync.app.ai.service.SongMeaningService;
import com.lyricsync.app.ai.service.SongMeaningService.SongMeaning;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor;
import com.lyricsync.app.ai.util.AiStreamingAnimator;
import com.lyricsync.app.ai.util.CleanMarkdownFormatter;
import android.text.method.LinkMovementMethod;

import androidx.annotation.Nullable;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.lyricsync.app.LyricSyncApp;
import com.lyricsync.app.R;
import com.lyricsync.app.detection.MediaSessionTracker;
import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.ai.transliteration.AiSyllableTransliterationService;
import com.lyricsync.app.ai.transliteration.ScriptDetector;
import com.lyricsync.app.lyrics.cache.LyricsCacheManager;
import com.lyricsync.app.lyrics.LyricsProviderManager;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.renderer.AnimConfig;
import com.lyricsync.app.renderer.SpringScroller;
import com.lyricsync.app.renderer.SyllableHighlighter;
import com.lyricsync.app.ui.AlbumPalette;
import com.lyricsync.app.ui.AndroidPrefs;
import com.lyricsync.app.ui.Anim;
import com.lyricsync.app.ui.AnimSelection;
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
    private LinearLayout overlaySettingsPanel;
    private SeekBar overlaySyncOffsetSlider;
    private TextView overlaySyncOffsetLabel;
    private LinearLayout overlayFontChips;
    private LinearLayout overlayAnimChips;
    private LinearLayout overlayAnimEffectChips;
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
    private AnimConfig lyricAnimConfig = AnimConfig.defaultConfig();
    private float overlayFontSizeSp = 13f;
    private int overlayWidthPercent = 88;
    private int lyricsHeightPx = 0;
    private boolean lyricsVisible = true;
    private View overlayResizeContainer;
    private ImageView overlayResizeHandle;
    private androidx.appcompat.widget.SwitchCompat overlayResizeSwitch;
    private boolean showResizeHandle = false;
    private com.lyricsync.app.lyrics.translation.GoogleTranslateService translateService;
    private com.lyricsync.app.lyrics.translation.AiTranslationService aiTranslationService;
    private androidx.appcompat.widget.SwitchCompat overlayTranslateSwitch;
    private boolean translationEnabled = false;
    private SyllableHighlighter highlighter;
    private AntigravityApiClient apiClient;
    private AiSyllableTransliterationService aiTransliterationService;
    private LyricsCacheManager lyricsCacheManager;
    private androidx.appcompat.widget.SwitchCompat overlayRomajiSwitch;
    private TextView overlayTransliterationLabel;
    private View romajiRow;
    private boolean isProgrammaticRomajiUpdate = false;

    // AI Insights UI & Subsystems
    private ImageView overlayAiButton;
    private TextView overlayMoodBadge;

    // Secondary Window: Floating AI Chat
    private FrameLayout aiChatContainer;
    private View aiChatView;
    private WindowManager.LayoutParams aiChatParams;
    private boolean isAiChatAttached = false;
    private TextView aiChatTitle;
    private TextView aiChatArtist;
    private ImageView aiMinimizeButton;
    private ImageView aiCloseButton;
    private ImageView aiWebSearchChip;
    private TextView aiMoodBadge;
    private ScrollView aiChatEmptyState;
    private TextView aiEmptyHintText;
    private LinearLayout aiPromptTemplatesContainer;
    private HorizontalScrollView aiWebSourcesScroll;
    private LinearLayout aiWebSourcesContainer;
    private ScrollView aiChatScroll;
    private LinearLayout aiChatMessages;
    private EditText aiChatInput;
    private ImageView aiChatSend;
    private TextView aiQuotaBadge;
    private LinearLayout aiToolStatusContainer;
    private ImageView aiToolStatusIcon;
    private TextView aiToolStatusText;
    private Runnable pendingToolStatusHide;

    // Secondary Window: Floating AI Bubble (Minimized mode)
    private View aiBubbleView;
    private WindowManager.LayoutParams aiBubbleParams;
    private boolean isAiBubbleAttached = false;

    private SongMeaningService songMeaningService;
    private SongChatManager songChatManager;
    private AiChatHistoryManager aiChatHistoryManager;
    private AntigravityQuotaClient quotaClient;
    private AntigravityApiClient.Cancellable activeMeaningCall;
    private AntigravityApiClient.Cancellable activeChatCall;
    private AntigravityApiClient.Cancellable activeTransliterationCall;
    private AiStreamingAnimator activeAiBubbleAnimator;
    private boolean aiWebSearchEnabled = false;

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
        } else if (AppFont.PREF_KEY.equals(key)
                || AnimSelection.PREF_MOTION.equals(key) || AnimSelection.PREF_EFFECT.equals(key)) {
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
                        AnimSelection.motionKey(new AndroidPrefs(prefs)));
            }
            if (overlayAnimEffectChips != null) {
                AnimStyleChips.refresh(overlayAnimEffectChips,
                        AnimSelection.effectKey(new AndroidPrefs(prefs)));
            }
        } else if ("sync_offset_ms".equals(key) && sessionTracker != null) {
            long offset = prefs.getLong("sync_offset_ms", 0);
            sessionTracker.setSyncOffsetMs(offset);
            syncOffsetSliderFromPrefs(offset);
        } else if (com.lyricsync.app.lyrics.translation.GoogleTranslateService.PREF_TRANSLATION_ENABLED.equals(key)) {
            boolean enabled = prefs.getBoolean(key, false);
            if (this.translationEnabled != enabled) {
                this.translationEnabled = enabled;
                if (overlayTranslateSwitch != null) {
                    overlayTranslateSwitch.setChecked(enabled);
                }
                if (highlighter != null) {
                    highlighter.setTranslationEnabled(enabled);
                }
                if (enabled && currentLyrics != null && currentLyrics.translationLang == null) {
                    fetchTranslationForCurrentLyrics();
                }
            }
        } else if (com.lyricsync.app.lyrics.translation.GoogleTranslateService.PREF_TARGET_LANG.equals(key)) {
            if (translationEnabled && currentLyrics != null) {
                fetchTranslationForCurrentLyrics();
            }
        } else if ("overlay_show_resize_handle".equals(key)) {
            boolean show = prefs.getBoolean(key, false);
            if (this.showResizeHandle != show) {
                this.showResizeHandle = show;
                if (overlayResizeSwitch != null) {
                    overlayResizeSwitch.setChecked(show);
                }
                updateResizeHandleVisibility();
            }
        } else if ("pref_romaji_enabled".equals(key)) {
            boolean enabled = prefs.getBoolean(key, true);
            if (highlighter != null) {
                highlighter.setTransliterationEnabled(enabled);
            }
            if (overlayRomajiSwitch != null && overlayRomajiSwitch.isChecked() != enabled) {
                isProgrammaticRomajiUpdate = true;
                overlayRomajiSwitch.setChecked(enabled);
                isProgrammaticRomajiUpdate = false;
            }
        } else if ("pref_original_script_enabled".equals(key)) {
            boolean enabled = prefs.getBoolean(key, true);
            if (highlighter != null) {
                highlighter.setOriginalScriptEnabled(enabled);
            }
        }
    };

    private final List<SyllableHighlighter.LineView> lineViews = new ArrayList<>();
    private SpringScroller springScroller;

    private Choreographer choreographer;
    private boolean renderRunning = false;
    private boolean renderActive = false;
    private long lastFrameNanos = 0;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        sharedPrefs = getSharedPreferences("lyricsync", MODE_PRIVATE);
        translateService = new com.lyricsync.app.lyrics.translation.GoogleTranslateService();
        com.lyricsync.app.ai.auth.AntigravityAuthManager authManager =
                com.lyricsync.app.ai.auth.AntigravityAuthManager.getInstance(this);
        apiClient = new com.lyricsync.app.ai.client.AntigravityApiClient(authManager);
        aiTranslationService = new com.lyricsync.app.lyrics.translation.AiTranslationService(
                this, authManager, apiClient, translateService);
        songMeaningService = new SongMeaningService(sharedPrefs, apiClient);
        songChatManager = new SongChatManager(apiClient);
        aiChatHistoryManager = new AiChatHistoryManager(this);
        songChatManager.setHistoryManager(aiChatHistoryManager);
        aiTransliterationService = new AiSyllableTransliterationService(this, apiClient);
        lyricsCacheManager = new LyricsCacheManager(this);
        quotaClient = new AntigravityQuotaClient(authManager);
        AntigravityToolExecutor.setChatModelSupplier(() ->
                sharedPrefs.getString("pref_ai_model", SongChatManager.DEFAULT_MODEL));
        translationEnabled = sharedPrefs.getBoolean(
                com.lyricsync.app.lyrics.translation.GoogleTranslateService.PREF_TRANSLATION_ENABLED, false);
        loadTypefaces();
        lyricAnimConfig = AnimSelection.current(new AndroidPrefs(sharedPrefs));

        overlayFontSizeSp = calculateFontSizeSp();
        highlighter = new SyllableHighlighter(this, fontBold, fontMedium, overlayFontSizeSp);
        highlighter.setAnimConfig(lyricAnimConfig);
        boolean romajiPref = sharedPrefs.getBoolean("pref_romaji_enabled", true);
        highlighter.setTransliterationEnabled(romajiPref);
        boolean origPref = sharedPrefs.getBoolean("pref_original_script_enabled", true);
        highlighter.setOriginalScriptEnabled(origPref);
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
        android.content.Context themedContext = new ContextThemeWrapper(this, R.style.Theme_LyricSync);
        overlayView = LayoutInflater.from(themedContext).inflate(R.layout.overlay_lyrics, null);

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
        overlayAnimEffectChips = overlayView.findViewById(R.id.overlay_anim_effect_chips);
        overlayTranslateSwitch = overlayView.findViewById(R.id.overlay_translate_switch);
        if (overlayTranslateSwitch != null) {
            overlayTranslateSwitch.setShowText(false);
            overlayTranslateSwitch.setTextOn("");
            overlayTranslateSwitch.setTextOff("");
            overlayTranslateSwitch.setChecked(translationEnabled);
            overlayTranslateSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
                translationEnabled = isChecked;
                sharedPrefs.edit().putBoolean(
                        com.lyricsync.app.lyrics.translation.GoogleTranslateService.PREF_TRANSLATION_ENABLED, isChecked).apply();
                if (highlighter != null) {
                    highlighter.setTranslationEnabled(isChecked);
                }
                if (isChecked && currentLyrics != null && currentLyrics.translationLang == null) {
                    fetchTranslationForCurrentLyrics();
                }
            });
        }
        romajiRow = overlayView.findViewById(R.id.overlay_romaji_row);
        overlayTransliterationLabel = overlayView.findViewById(R.id.overlay_transliteration_label);
        overlayRomajiSwitch = overlayView.findViewById(R.id.overlay_romaji_switch);
        if (overlayRomajiSwitch != null) {
            overlayRomajiSwitch.setShowText(false);
            overlayRomajiSwitch.setTextOn("");
            overlayRomajiSwitch.setTextOff("");
            isProgrammaticRomajiUpdate = true;
            boolean romajiPref = sharedPrefs.getBoolean("pref_romaji_enabled", true);
            overlayRomajiSwitch.setChecked(romajiPref);
            isProgrammaticRomajiUpdate = false;
            overlayRomajiSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
                if (isProgrammaticRomajiUpdate) return;
                if (highlighter != null) {
                    highlighter.setTransliterationEnabled(isChecked);
                }
                sharedPrefs.edit().putBoolean("pref_romaji_enabled", isChecked).apply();
                if (isChecked && currentLyrics != null && isLyricsNonLatin(currentLyrics) && !currentLyrics.hasTransliteration) {
                    performTransliteration();
                }
                String labelName = (overlayTransliterationLabel != null && overlayTransliterationLabel.getText() != null)
                        ? overlayTransliterationLabel.getText().toString()
                        : getString(R.string.toggle_romaji);
                Toast.makeText(this, labelName + (isChecked ? " diaktifkan" : " dinonaktifkan"), Toast.LENGTH_SHORT).show();
            });
        }
        if (romajiRow != null) {
            View.OnLongClickListener longClickListener = v -> {
                Haptics.tick(v);
                if (highlighter != null) {
                    boolean newOrig = !highlighter.isOriginalScriptEnabled();
                    highlighter.setOriginalScriptEnabled(newOrig);
                    sharedPrefs.edit().putBoolean("pref_original_script_enabled", newOrig).apply();
                    Toast.makeText(this, newOrig ? "Aksara asli ditampilkan" : "Aksara asli disembunyikan (Romaji Saja)", Toast.LENGTH_SHORT).show();
                }
                return true;
            };
            romajiRow.setOnLongClickListener(longClickListener);
            if (overlayRomajiSwitch != null) {
                overlayRomajiSwitch.setOnLongClickListener(longClickListener);
            }
        }
        overlayResizeContainer = overlayView.findViewById(R.id.overlay_resize_container);
        overlayResizeHandle = overlayView.findViewById(R.id.overlay_resize_handle);
        overlayResizeSwitch = overlayView.findViewById(R.id.overlay_resize_switch);
        showResizeHandle = sharedPrefs.getBoolean("overlay_show_resize_handle", false);
        if (overlayResizeSwitch != null) {
            overlayResizeSwitch.setShowText(false);
            overlayResizeSwitch.setTextOn("");
            overlayResizeSwitch.setTextOff("");
            overlayResizeSwitch.setChecked(showResizeHandle);
            overlayResizeSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
                showResizeHandle = isChecked;
                sharedPrefs.edit().putBoolean("overlay_show_resize_handle", isChecked).apply();
                updateResizeHandleVisibility();
            });
        }
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

        overlayAiButton = overlayView.findViewById(R.id.overlay_ai_button);
        overlayMoodBadge = overlayView.findViewById(R.id.overlay_mood_badge);
        if (overlayAiButton != null) {
            overlayAiButton.setOnClickListener(v -> {
                Haptics.tick(v);
                if (isAiChatAttached) {
                    minimizeAiChatToBubble();
                } else {
                    showAiChatOverlay();
                }
            });
        }
        if (overlayMoodBadge != null) {
            overlayMoodBadge.setOnClickListener(v -> {
                Haptics.tick(v);
                showAiChatOverlay();
            });
        }

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
        setupResizeHandling();
        updateResizeHandleVisibility();

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
        AnimConfig newAnimConfig = AnimSelection.current(new AndroidPrefs(sharedPrefs));
        Typeface prevBold = fontBold;
        loadTypefaces();
        boolean typefaceChanged = fontBold != prevBold;
        boolean styleChanged = newAnimConfig != lyricAnimConfig;
        lyricAnimConfig = newAnimConfig;

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
            highlighter.setAnimConfig(lyricAnimConfig);
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
        updateResizeHandleVisibility();

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
            updateResizeHandleVisibility(!visible);
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

    // ── Dual Window AI Overlay & Bubble ────────────────────────────────────

    private void showAiChatOverlay() {
        if (isDestroyed || windowManager == null) return;

        // If bubble is currently attached, remove it
        if (isAiBubbleAttached && aiBubbleView != null) {
            try {
                windowManager.removeView(aiBubbleView);
            } catch (Exception e) {
                AppLog.w(TAG, "Error removing bubble view: " + e.getMessage());
            }
            isAiBubbleAttached = false;
        }

        if (isAiChatAttached) {
            return;
        }

        if (aiChatView == null || aiChatContainer == null) {
            initAiChatView();
        }

        if (currentTrack != null) {
            if (aiChatTitle != null) aiChatTitle.setText(currentTrack.title);
            if (aiChatArtist != null) aiChatArtist.setText(currentTrack.artist);
        }

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        int chatWidth = Math.min((int) (screenWidth * 0.92f), dpToPx(440));
        int chatHeight = dpToPx(460);

        aiChatParams = new WindowManager.LayoutParams(
                chatWidth,
                chatHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        aiChatParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        aiChatParams.gravity = Gravity.CENTER;

        try {
            windowManager.addView(aiChatContainer, aiChatParams);
            isAiChatAttached = true;
            Anim.bubbleIn(aiChatView);
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to add AI chat overlay", e);
            isAiChatAttached = false;
            return;
        }

        fetchQuotaIfAuthenticated();
        if (aiChatTitle != null) {
            aiChatTitle.setText(R.string.ai_insights_title);
        }
        if (currentTrack != null) {
            if (aiChatArtist != null) {
                aiChatArtist.setText(currentTrack.title + " • " + currentTrack.artist);
            }
            if (songChatManager != null) {
                songChatManager.setAppLanguage(LocaleHelper.getLanguage(this));
            }
        }
        if (songChatManager != null && songChatManager.getMeaning() != null) {
            SongMeaning existing = songChatManager.getMeaning();
            if (existing.mood != null && !existing.mood.trim().isEmpty()) {
                if (aiMoodBadge != null) {
                    aiMoodBadge.setText(existing.mood.trim());
                    aiMoodBadge.setVisibility(View.VISIBLE);
                }
            }
            if (existing.webSources != null && !existing.webSources.isEmpty()) {
                displayWebSources(existing.webSources);
            }
        }
        boolean hasHistory = songChatManager != null && !songChatManager.getHistory().isEmpty();
        if (aiChatEmptyState != null) {
            aiChatEmptyState.setVisibility(hasHistory ? View.GONE : View.VISIBLE);
        }
        if (aiChatScroll != null) {
            aiChatScroll.setVisibility(hasHistory ? View.VISIBLE : View.GONE);
        }
        if (!hasHistory) {
            setupAiPromptTemplates();
        }
    }

    private void initAiChatView() {
        android.content.Context themedContext = new ContextThemeWrapper(this, R.style.Theme_LyricSync);
        aiChatContainer = new FrameLayout(themedContext) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        minimizeAiChatToBubble();
                        return true;
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };

        aiChatView = LayoutInflater.from(themedContext).inflate(R.layout.overlay_ai_chat, aiChatContainer, false);
        aiChatContainer.addView(aiChatView);

        aiChatTitle = aiChatView.findViewById(R.id.ai_chat_title);
        aiChatArtist = aiChatView.findViewById(R.id.ai_chat_artist);
        aiMinimizeButton = aiChatView.findViewById(R.id.ai_minimize_button);
        aiCloseButton = aiChatView.findViewById(R.id.ai_close_button);
        aiWebSearchChip = aiChatView.findViewById(R.id.ai_web_search_chip);
        aiMoodBadge = aiChatView.findViewById(R.id.ai_mood_badge);
        aiChatEmptyState = aiChatView.findViewById(R.id.ai_chat_empty_state);
        aiEmptyHintText = aiChatView.findViewById(R.id.ai_empty_hint_text);
        aiPromptTemplatesContainer = aiChatView.findViewById(R.id.ai_prompt_templates_container);
        aiWebSourcesScroll = aiChatView.findViewById(R.id.ai_web_sources_scroll);
        aiWebSourcesContainer = aiChatView.findViewById(R.id.ai_web_sources_container);
        aiChatScroll = aiChatView.findViewById(R.id.ai_chat_scroll);
        aiChatMessages = aiChatView.findViewById(R.id.ai_chat_messages);
        aiChatInput = aiChatView.findViewById(R.id.ai_chat_input);
        aiChatSend = aiChatView.findViewById(R.id.ai_chat_send);
        aiQuotaBadge = aiChatView.findViewById(R.id.ai_quota_badge);
        aiToolStatusContainer = aiChatView.findViewById(R.id.ai_tool_status_container);
        aiToolStatusIcon = aiChatView.findViewById(R.id.ai_tool_status_icon_view);
        aiToolStatusText = aiChatView.findViewById(R.id.ai_tool_status_text);

        if (aiMinimizeButton != null) {
            aiMinimizeButton.setOnClickListener(v -> {
                Haptics.tick(v);
                minimizeAiChatToBubble();
            });
        }
        if (aiCloseButton != null) {
            aiCloseButton.setOnClickListener(v -> {
                Haptics.tick(v);
                dismissAiChatOverlay();
            });
        }
        View aiBtnNewChat = aiChatView.findViewById(R.id.ai_btn_new_chat);
        if (aiBtnNewChat != null) {
            aiBtnNewChat.setOnClickListener(v -> {
                Haptics.tick(v);
                handleAiNewChatOverlay();
            });
        }
        View aiBtnHistory = aiChatView.findViewById(R.id.ai_btn_history);
        if (aiBtnHistory != null) {
            aiBtnHistory.setOnClickListener(v -> {
                Haptics.tick(v);
                showAiHistoryDialogOverlay();
            });
        }
        if (aiWebSearchChip != null) {
            aiWebSearchChip.setOnClickListener(v -> {
                Haptics.tick(v);
                aiWebSearchEnabled = !aiWebSearchEnabled;
                aiWebSearchChip.setAlpha(aiWebSearchEnabled ? 1.0f : 0.5f);
                Toast.makeText(this,
                        getString(aiWebSearchEnabled ? R.string.ai_web_search_active : R.string.ai_web_search_inactive),
                        Toast.LENGTH_SHORT).show();
            });
        }
        setupAiPromptTemplates();
        if (aiChatSend != null) {
            aiChatSend.setOnClickListener(v -> {
                Haptics.tick(v);
                submitAiChatInput();
            });
        }
        if (aiChatInput != null) {
            aiChatInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    Haptics.tick(v);
                    submitAiChatInput();
                    return true;
                }
                return false;
            });
        }
        setupAiChatDragging();
    }

    private void setupAiChatDragging() {
        if (aiChatView == null) return;
        View header = aiChatView.findViewById(R.id.ai_chat_header);
        if (header == null) return;

        final int[] touchX = new int[1];
        final int[] touchY = new int[1];
        final int[] paramX = new int[1];
        final int[] paramY = new int[1];
        final boolean[] isDragging = new boolean[1];
        final int touchSlop = dpToPx(6);

        header.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    touchX[0] = (int) event.getRawX();
                    touchY[0] = (int) event.getRawY();
                    paramX[0] = aiChatParams != null ? aiChatParams.x : 0;
                    paramY[0] = aiChatParams != null ? aiChatParams.y : 0;
                    isDragging[0] = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - touchX[0];
                    float dy = event.getRawY() - touchY[0];
                    if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                        isDragging[0] = true;
                    }
                    if (isDragging[0] && aiChatParams != null && windowManager != null) {
                        DisplayMetrics dm = getResources().getDisplayMetrics();
                        int maxX = Math.max(0, (dm.widthPixels - aiChatParams.width) / 2);
                        int maxY = Math.max(0, (dm.heightPixels - aiChatParams.height) / 2);
                        int targetX = paramX[0] + (int) dx;
                        int targetY = paramY[0] + (int) dy;
                        aiChatParams.x = Math.max(-maxX, Math.min(maxX, targetX));
                        aiChatParams.y = Math.max(-maxY, Math.min(maxY, targetY));
                        try {
                            windowManager.updateViewLayout(aiChatContainer, aiChatParams);
                        } catch (Exception e) {
                            AppLog.w(TAG, "AI chat drag updateViewLayout failed: " + e.getMessage());
                        }
                        return true;
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (isDragging[0]) {
                        Haptics.tick(v);
                    }
                    return isDragging[0];
            }
            return false;
        });
    }

    private void minimizeAiChatToBubble() {
        if (isDestroyed || windowManager == null) return;

        if (isAiChatAttached && aiChatContainer != null) {
            try {
                windowManager.removeView(aiChatContainer);
            } catch (Exception e) {
                AppLog.w(TAG, "Error removing aiChatContainer: " + e.getMessage());
            }
            isAiChatAttached = false;
        }

        if (isAiBubbleAttached) {
            return;
        }

        if (aiBubbleView == null) {
            initAiBubbleView();
        }

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int bubbleSize = dpToPx(50);

        aiBubbleParams = new WindowManager.LayoutParams(
                bubbleSize,
                bubbleSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        aiBubbleParams.gravity = Gravity.TOP | Gravity.START;
        aiBubbleParams.x = sharedPrefs.getInt("ai_bubble_x", metrics.widthPixels - bubbleSize - dpToPx(16));
        aiBubbleParams.y = sharedPrefs.getInt("ai_bubble_y", metrics.heightPixels / 2);

        try {
            windowManager.addView(aiBubbleView, aiBubbleParams);
            isAiBubbleAttached = true;
            Anim.bubbleIn(aiBubbleView);
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to add bubble view", e);
            isAiBubbleAttached = false;
        }
    }

    private void initAiBubbleView() {
        android.content.Context themedContext = new ContextThemeWrapper(this, R.style.Theme_LyricSync);
        aiBubbleView = LayoutInflater.from(themedContext).inflate(R.layout.overlay_ai_bubble, null);

        final int[] touchX = new int[1];
        final int[] touchY = new int[1];
        final int[] paramX = new int[1];
        final int[] paramY = new int[1];
        final boolean[] isDragging = new boolean[1];
        final int touchSlop = dpToPx(6);

        aiBubbleView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    touchX[0] = (int) event.getRawX();
                    touchY[0] = (int) event.getRawY();
                    paramX[0] = aiBubbleParams != null ? aiBubbleParams.x : 0;
                    paramY[0] = aiBubbleParams != null ? aiBubbleParams.y : 0;
                    isDragging[0] = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - touchX[0];
                    float dy = event.getRawY() - touchY[0];
                    if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                        isDragging[0] = true;
                    }
                    if (isDragging[0] && aiBubbleParams != null && windowManager != null) {
                        DisplayMetrics dm = getResources().getDisplayMetrics();
                        int bubbleSize = dpToPx(50);
                        int maxX = Math.max(0, dm.widthPixels - bubbleSize);
                        int maxY = Math.max(0, dm.heightPixels - bubbleSize);

                        aiBubbleParams.x = Math.max(0, Math.min(maxX, paramX[0] + (int) dx));
                        aiBubbleParams.y = Math.max(0, Math.min(maxY, paramY[0] + (int) dy));
                        try {
                            windowManager.updateViewLayout(aiBubbleView, aiBubbleParams);
                        } catch (Exception e) {
                            AppLog.w(TAG, "Bubble drag updateViewLayout failed: " + e.getMessage());
                        }
                        return true;
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (isDragging[0]) {
                        if (aiBubbleParams != null) {
                            DisplayMetrics dm = getResources().getDisplayMetrics();
                            int bubbleSize = dpToPx(50);
                            int middleX = dm.widthPixels / 2;
                            int targetX = (aiBubbleParams.x + bubbleSize / 2 < middleX)
                                    ? dpToPx(8) : (dm.widthPixels - bubbleSize - dpToPx(8));
                            aiBubbleParams.x = targetX;
                            try {
                                windowManager.updateViewLayout(aiBubbleView, aiBubbleParams);
                            } catch (Exception ignored) {}

                            sharedPrefs.edit()
                                    .putInt("ai_bubble_x", aiBubbleParams.x)
                                    .putInt("ai_bubble_y", aiBubbleParams.y)
                                    .apply();
                        }
                        Haptics.tick(v);
                    } else {
                        Haptics.confirm(v);
                        showAiChatOverlay();
                    }
                    return true;
            }
            return false;
        });
    }

    private void dismissAiChatOverlay() {
        if (isAiChatAttached && aiChatContainer != null && windowManager != null) {
            try {
                windowManager.removeView(aiChatContainer);
            } catch (Exception e) {
                AppLog.w(TAG, "Error removing aiChatContainer: " + e.getMessage());
            }
            isAiChatAttached = false;
        }
        if (isAiBubbleAttached && aiBubbleView != null && windowManager != null) {
            try {
                windowManager.removeView(aiBubbleView);
            } catch (Exception e) {
                AppLog.w(TAG, "Error removing aiBubbleView: " + e.getMessage());
            }
            isAiBubbleAttached = false;
        }
    }

    private void fetchQuotaIfAuthenticated() {
        if (quotaClient == null) return;
        quotaClient.fetchQuota(new AntigravityQuotaClient.QuotaCallback() {
            @Override
            public void onQuotaLoaded(QuotaSummary summary) {
                handler.post(() -> {
                    if (aiQuotaBadge != null && summary != null) {
                        int pct = Math.round(summary.geminiWeeklyFraction * 100f);
                        aiQuotaBadge.setText("AI Quota: " + pct + "%");
                        aiQuotaBadge.setVisibility(View.VISIBLE);
                    }
                });
            }

            @Override
            public void onError(String error) {
                // Silently ignore or leave hidden
            }
        });
    }

    private void triggerSongMeaningAnalysis() {
        if (currentTrack == null || songMeaningService == null) return;

        AntigravityAuthManager auth = AntigravityAuthManager.getInstance(this);
        if (auth == null || !auth.isAuthenticated()) {
            if (aiEmptyHintText != null) {
                CleanMarkdownFormatter.applyMarkdown(aiEmptyHintText,
                        "Please login to Google Antigravity in LyricSync to use AI features.", false);
            }
            return;
        }

        if (activeMeaningCall != null) {
            activeMeaningCall.cancel();
            activeMeaningCall = null;
        }

        if (aiEmptyHintText != null) {
            CleanMarkdownFormatter.applyMarkdown(aiEmptyHintText, getString(R.string.ai_empty_hint), false);
        }
        if (aiMoodBadge != null) {
            aiMoodBadge.setVisibility(View.GONE);
        }
        if (overlayMoodBadge != null) {
            overlayMoodBadge.setVisibility(View.GONE);
        }

        StringBuilder sb = new StringBuilder();
        if (currentLyrics != null && currentLyrics.lines != null) {
            for (LyricsData.LyricsLine line : currentLyrics.lines) {
                if (line != null && line.text != null && !line.isInterlude) {
                    sb.append(line.text).append("\n");
                }
            }
        }
        String fullLyrics = sb.toString().trim();

        activeMeaningCall = songMeaningService.getSongMeaning(
                currentTrack.title,
                currentTrack.artist,
                fullLyrics,
                aiWebSearchEnabled,
                new SongMeaningService.MeaningCallback() {
                    @Override
                    public void onResult(SongMeaning meaning, boolean fromCache) {
                        handler.post(() -> {
                            if (meaning == null) return;
                            if (meaning.mood != null && !meaning.mood.trim().isEmpty()) {
                                String moodText = meaning.mood.trim();
                                if (aiMoodBadge != null) {
                                    aiMoodBadge.setText(moodText);
                                    aiMoodBadge.setVisibility(View.VISIBLE);
                                }
                                if (overlayMoodBadge != null) {
                                    overlayMoodBadge.setText(moodText);
                                    overlayMoodBadge.setVisibility(View.VISIBLE);
                                }
                            }
                            if (meaning.webSources != null && !meaning.webSources.isEmpty()) {
                                displayWebSources(meaning.webSources);
                            }
                            if (songChatManager != null && currentTrack != null) {
                                songChatManager.setCurrentSong(currentTrack.title, currentTrack.artist, fullLyrics, meaning);
                            }
                        });
                    }

                    @Override
                    public void onError(Exception e) {
                        handler.post(() -> {
                            if (aiEmptyHintText != null) {
                                CleanMarkdownFormatter.applyMarkdown(aiEmptyHintText,
                                        "Error: " + (e != null ? e.getMessage() : "Unknown error"), false);
                            }
                        });
                    }
                });
    }

    private void displayWebSources(List<String> sources) {
        if (aiWebSourcesContainer == null || aiWebSourcesScroll == null) return;
        aiWebSourcesContainer.removeAllViews();
        if (sources == null || sources.isEmpty()) {
            aiWebSourcesScroll.setVisibility(View.GONE);
            return;
        }
        aiWebSourcesScroll.setVisibility(View.VISIBLE);

        TextView label = new TextView(this);
        label.setText(getString(R.string.ai_web_sources_label) + ": ");
        label.setTextColor(getResources().getColor(R.color.text_dim));
        label.setTextSize(10f);
        label.setPadding(0, 0, dpToPx(4), 0);
        aiWebSourcesContainer.addView(label);

        for (String source : sources) {
            if (source == null || source.trim().isEmpty()) continue;
            String cleanUrl = source.trim();
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl;
            }
            final String url = cleanUrl;
            String domain = url;
            try {
                java.net.URI uri = new java.net.URI(url);
                String host = uri.getHost();
                if (host != null) {
                    domain = host.startsWith("www.") ? host.substring(4) : host;
                }
            } catch (Exception ignored) {}

            TextView chip = new TextView(this);
            chip.setText(domain);
            chip.setTextColor(getResources().getColor(R.color.accent));
            chip.setTextSize(10f);
            chip.setBackgroundResource(R.drawable.bg_tip_box);
            chip.setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dpToPx(6));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                Haptics.tick(v);
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(FloatingOverlayService.this, getString(R.string.ai_link_error, url), Toast.LENGTH_SHORT).show();
                }
            });
            aiWebSourcesContainer.addView(chip);
        }
    }

    private void setupAiPromptTemplates() {
        if (aiPromptTemplatesContainer == null) return;
        aiPromptTemplatesContainer.removeAllViews();
        if (currentLyrics != null && currentLyrics.type == LyricsData.Type.SYLLABLE && isLyricsNonLatin(currentLyrics)) {
            // Option 1: Transliterasi & Arti
            View itemWithTrans = LayoutInflater.from(this).inflate(R.layout.item_prompt_template, aiPromptTemplatesContainer, false);
            ImageView icon1 = itemWithTrans.findViewById(R.id.prompt_icon);
            TextView title1 = itemWithTrans.findViewById(R.id.prompt_title);
            TextView desc1 = itemWithTrans.findViewById(R.id.prompt_desc);
            title1.setText(R.string.ai_chip_transliterate_with_translation);
            desc1.setText(R.string.ai_chip_transliterate_with_translation_desc);
            icon1.setImageResource(R.drawable.ic_sparkles);
            itemWithTrans.setOnClickListener(v -> {
                Haptics.tick(v);
                performTransliteration(true);
            });
            aiPromptTemplatesContainer.addView(itemWithTrans);

            // Option 2: Transliterasi Saja
            View itemOnly = LayoutInflater.from(this).inflate(R.layout.item_prompt_template, aiPromptTemplatesContainer, false);
            ImageView icon2 = itemOnly.findViewById(R.id.prompt_icon);
            TextView title2 = itemOnly.findViewById(R.id.prompt_title);
            TextView desc2 = itemOnly.findViewById(R.id.prompt_desc);
            title2.setText(R.string.ai_chip_transliterate_only);
            desc2.setText(R.string.ai_chip_transliterate_only_desc);
            icon2.setImageResource(R.drawable.ic_sparkles);
            itemOnly.setOnClickListener(v -> {
                Haptics.tick(v);
                performTransliteration(false, true);
            });
            aiPromptTemplatesContainer.addView(itemOnly);

            // Option 3: Romaji Saja (Tanpa Huruf Asli)
            View itemPure = LayoutInflater.from(this).inflate(R.layout.item_prompt_template, aiPromptTemplatesContainer, false);
            ImageView icon3 = itemPure.findViewById(R.id.prompt_icon);
            TextView title3 = itemPure.findViewById(R.id.prompt_title);
            TextView desc3 = itemPure.findViewById(R.id.prompt_desc);
            title3.setText(R.string.ai_chip_transliterate_pure_romaji);
            desc3.setText(R.string.ai_chip_transliterate_pure_romaji_desc);
            icon3.setImageResource(R.drawable.ic_sparkles);
            itemPure.setOnClickListener(v -> {
                Haptics.tick(v);
                performTransliteration(false, false);
            });
            aiPromptTemplatesContainer.addView(itemPure);
        }
        String lang = LocaleHelper.getLanguage(this);
        List<SongChatManager.PromptTemplate> templates = SongChatManager.getPromptTemplates(lang);
        for (final SongChatManager.PromptTemplate template : templates) {
            View itemView = LayoutInflater.from(this).inflate(R.layout.item_prompt_template, aiPromptTemplatesContainer, false);
            ImageView iconView = itemView.findViewById(R.id.prompt_icon);
            TextView titleView = itemView.findViewById(R.id.prompt_title);
            TextView descView = itemView.findViewById(R.id.prompt_desc);

            titleView.setText(template.title);
            descView.setText(template.description);

            int iconRes = getResources().getIdentifier(template.iconName, "drawable", getPackageName());
            if (iconRes != 0) {
                iconView.setImageResource(iconRes);
            } else {
                iconView.setImageResource(R.drawable.ic_sparkles);
            }

            itemView.setOnClickListener(v -> {
                Haptics.tick(v);
                sendAiChatMessage(template.promptText);
            });
            aiPromptTemplatesContainer.addView(itemView);
        }
    }

    private boolean isLyricsNonLatin(LyricsData lyrics) {
        if (lyrics == null || lyrics.lines == null || lyrics.lines.isEmpty()) return false;
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (LyricsData.LyricsLine line : lyrics.lines) {
            if (line != null && !line.isInterlude && line.text != null) {
                sb.append(line.text).append(" ");
                count++;
                if (count >= 25) break;
            }
        }
        return ScriptDetector.isNonLatin(sb.toString());
    }

    private void performTransliteration() {
        performTransliteration(true, true);
    }

    private void performTransliteration(final boolean includeTranslation) {
        performTransliteration(includeTranslation, true);
    }

    private void performTransliteration(final boolean includeTranslation, final boolean showOriginalScript) {
        if (currentLyrics == null || aiTransliterationService == null || currentTrack == null) return;
        if (activeTransliterationCall != null) {
            activeTransliterationCall.cancel();
            activeTransliterationCall = null;
        }

        if (currentLyrics.hasTransliteration) {
            if (highlighter != null) {
                highlighter.setTransliterationEnabled(true);
                highlighter.setOriginalScriptEnabled(showOriginalScript);
                if (includeTranslation) {
                    highlighter.setTranslationEnabled(true);
                }
            }
            sharedPrefs.edit()
                    .putBoolean("pref_romaji_enabled", true)
                    .putBoolean("pref_original_script_enabled", showOriginalScript)
                    .apply();
            if (overlayRomajiSwitch != null) {
                isProgrammaticRomajiUpdate = true;
                overlayRomajiSwitch.setChecked(true);
                isProgrammaticRomajiUpdate = false;
            }
            updateTransliterationToggleUi();
            Toast.makeText(this, R.string.ai_transliteration_complete, Toast.LENGTH_SHORT).show();
            return;
        }

        final TrackInfo track = currentTrack;
        Toast.makeText(this, R.string.ai_transliterating, Toast.LENGTH_SHORT).show();
        String title = currentTrack.title != null ? currentTrack.title : "";
        String artist = currentTrack.artist != null ? currentTrack.artist : "";
        String targetLang = LocaleHelper.getLanguage(this);
        activeTransliterationCall = aiTransliterationService.transliterateSyllablesAsync(
                currentLyrics, title, artist, targetLang, includeTranslation,
                new AiSyllableTransliterationService.Callback() {
                    @Override
                    public void onSuccess(LyricsData enrichedData) {
                        activeTransliterationCall = null;
                        if (currentTrack == null || !java.util.Objects.equals(track.title, currentTrack.title)
                                || !java.util.Objects.equals(track.artist, currentTrack.artist)) return;
                        currentLyrics = enrichedData;
                        sharedPrefs.edit()
                                .putBoolean("pref_romaji_enabled", true)
                                .putBoolean("pref_original_script_enabled", showOriginalScript)
                                .apply();
                        if (includeTranslation) {
                            translationEnabled = true;
                            sharedPrefs.edit()
                                    .putBoolean(com.lyricsync.app.lyrics.translation.GoogleTranslateService.PREF_TRANSLATION_ENABLED, true)
                                    .apply();
                            if (overlayTranslateSwitch != null) {
                                overlayTranslateSwitch.setChecked(true);
                            }
                        }
                        if (overlayRomajiSwitch != null) {
                            isProgrammaticRomajiUpdate = true;
                            overlayRomajiSwitch.setChecked(true);
                            isProgrammaticRomajiUpdate = false;
                        }
                        if (lyricsCacheManager != null && currentTrack != null) {
                            lyricsCacheManager.cache(currentTrack, enrichedData);
                        }
                        renderOverlayLyrics(currentLyrics);
                        Toast.makeText(FloatingOverlayService.this, R.string.ai_transliteration_complete, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String message) {
                        activeTransliterationCall = null;
                        Toast.makeText(FloatingOverlayService.this, message != null ? message : "Transliteration failed", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private String getTransliterationLabelForScript(ScriptDetector.ScriptType script) {
        if (script == null) return getString(R.string.toggle_transliteration);
        switch (script) {
            case JAPANESE:
                return getString(R.string.toggle_romaji);
            case CHINESE:
                return getString(R.string.toggle_pinyin);
            case KOREAN:
                return getString(R.string.toggle_romaja);
            case CYRILLIC:
                return getString(R.string.toggle_cyrillic_translit);
            default:
                return getString(R.string.toggle_transliteration);
        }
    }

    private void updateTransliterationToggleUi() {
        if (overlayRomajiSwitch == null) return;
        boolean isNonLatin = isLyricsNonLatin(currentLyrics);
        if (romajiRow != null) {
            romajiRow.setVisibility(isNonLatin ? View.VISIBLE : View.GONE);
        }
        if (!isNonLatin) return;

        boolean enabled = highlighter != null ? highlighter.isTransliterationEnabled()
                : sharedPrefs.getBoolean("pref_romaji_enabled", true);
        isProgrammaticRomajiUpdate = true;
        overlayRomajiSwitch.setChecked(enabled);
        isProgrammaticRomajiUpdate = false;

        if (overlayTransliterationLabel != null) {
            ScriptDetector.ScriptType script = ScriptDetector.ScriptType.LATIN;
            if (currentLyrics != null && currentLyrics.sourceScript != null) {
                try {
                    script = ScriptDetector.ScriptType.valueOf(currentLyrics.sourceScript);
                } catch (Exception ignored) {}
            }
            if (script == ScriptDetector.ScriptType.LATIN && currentLyrics != null && currentLyrics.lines != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(25, currentLyrics.lines.size()); i++) {
                    LyricsData.LyricsLine l = currentLyrics.lines.get(i);
                    if (l != null && l.text != null && !l.isInterlude) {
                        sb.append(l.text).append(" ");
                    }
                }
                script = ScriptDetector.detectScript(sb.toString());
            }
            overlayTransliterationLabel.setText(getTransliterationLabelForScript(script));
        }
    }

    private void autoFetchTransliterationForCurrentLyrics() {
        if (currentLyrics == null || aiTransliterationService == null || currentTrack == null) return;
        if (activeTransliterationCall != null) {
            activeTransliterationCall.cancel();
            activeTransliterationCall = null;
        }
        final String fetchKey = pendingFetchKey;
        final TrackInfo track = currentTrack;
        final LyricsData targetLyrics = currentLyrics;
        String title = track.title != null ? track.title : "";
        String artist = track.artist != null ? track.artist : "";
        String targetLang = LocaleHelper.getLanguage(this);

        activeTransliterationCall = aiTransliterationService.transliterateSyllablesAsync(
                targetLyrics, title, artist, targetLang, true,
                new AiSyllableTransliterationService.Callback() {
                    @Override
                    public void onSuccess(LyricsData enrichedData) {
                        activeTransliterationCall = null;
                        if (isDestroyed || !fetchKey.equals(pendingFetchKey)) return;
                        currentLyrics = enrichedData;
                        if (lyricsCacheManager != null && track != null) {
                            lyricsCacheManager.cache(track, enrichedData);
                        }
                        renderOverlayLyrics(currentLyrics);
                    }

                    @Override
                    public void onError(String message) {
                        activeTransliterationCall = null;
                        AppLog.w(TAG, "Auto-transliteration error: " + message);
                    }
                });
    }

    public void toggleRomaji() {
        if (overlayRomajiSwitch != null) {
            overlayRomajiSwitch.toggle();
        } else if (highlighter != null) {
            boolean newState = !highlighter.isTransliterationEnabled();
            highlighter.setTransliterationEnabled(newState);
            sharedPrefs.edit().putBoolean("pref_romaji_enabled", newState).apply();
            String labelName = (overlayTransliterationLabel != null && overlayTransliterationLabel.getText() != null)
                    ? overlayTransliterationLabel.getText().toString()
                    : getString(R.string.toggle_romaji);
            Toast.makeText(this, labelName + (newState ? " diaktifkan" : " dinonaktifkan"), Toast.LENGTH_SHORT).show();
        }
    }

    private void submitAiChatInput() {
        if (aiChatInput == null) return;
        String text = aiChatInput.getText().toString().trim();
        if (text.isEmpty()) return;
        aiChatInput.setText("");
        sendAiChatMessage(text);
    }

    private void sendAiChatMessage(String userQuery) {
        if (songChatManager == null || userQuery == null || userQuery.trim().isEmpty()) return;

        AntigravityAuthManager auth = AntigravityAuthManager.getInstance(this);
        if (auth == null || !auth.isAuthenticated()) {
            Toast.makeText(this, getString(R.string.ai_login_required), Toast.LENGTH_SHORT).show();
            return;
        }

        if (aiChatEmptyState != null) {
            aiChatEmptyState.setVisibility(View.GONE);
        }
        if (aiChatScroll != null) {
            aiChatScroll.setVisibility(View.VISIBLE);
        }

        addUserChatBubble(userQuery);

        final TextView aiBubble = addAiChatBubble("");
        if (activeAiBubbleAnimator != null) {
            activeAiBubbleAnimator.stopAll();
        }
        activeAiBubbleAnimator = new AiStreamingAnimator(aiBubble, getResources().getColor(R.color.accent));
        activeAiBubbleAnimator.startThinking(getString(R.string.ai_thinking));

        if (activeChatCall != null) {
            activeChatCall.cancel();
            activeChatCall = null;
        }

        if (userQuery != null && currentLyrics != null && currentLyrics.type == LyricsData.Type.SYLLABLE && isLyricsNonLatin(currentLyrics)) {
            String lower = userQuery.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("tanpa huruf") || lower.contains("tanpa aksara") || lower.contains("hide original") || lower.contains("tanpa jepang") || lower.contains("romaji saja") || lower.contains("romaji only") || lower.contains("hanya romaji")) {
                performTransliteration(false, false);
            } else if (lower.contains("saja") || lower.contains("tanpa terjemahan") || lower.contains("tanpa arti") || lower.contains("only")) {
                if (lower.contains("romaji") || lower.contains("pinyin") || lower.contains("transliterasi") || lower.contains("romaja")) {
                    performTransliteration(false, true);
                }
            } else if (lower.contains("romaji") || lower.contains("pinyin") || lower.contains("transliterasi") || lower.contains("romaja")) {
                if (lower.contains("terjemahan") || lower.contains("arti") || lower.contains("dengan") || lower.contains("dan") || lower.contains("with")) {
                    performTransliteration(true, true);
                }
            } else if (lower.contains("tampilkan huruf asli") || lower.contains("tampilkan aksara") || lower.contains("show original")) {
                if (highlighter != null) {
                    highlighter.setOriginalScriptEnabled(true);
                }
                sharedPrefs.edit().putBoolean("pref_original_script_enabled", true).apply();
            }
        }

        String currentModel = sharedPrefs.getString("pref_ai_model", SongChatManager.DEFAULT_MODEL);
        songChatManager.setModelId(currentModel);

        final StringBuilder responseAcc = new StringBuilder();
        activeChatCall = songChatManager.sendMessage(userQuery, aiWebSearchEnabled,
                new AntigravityApiClient.StreamListener() {
                    @Override
                    public void onToolExecuting(String toolName, String queryOrUrl) {
                        handler.post(() -> {
                            if (isDestroyed || aiToolStatusContainer == null) return;
                            cancelPendingToolStatusHide();
                            if ("web_fetch".equals(toolName)) {
                                if (aiToolStatusIcon != null) {
                                    aiToolStatusIcon.setImageResource(R.drawable.ic_search_globe);
                                }
                                String host = queryOrUrl;
                                try {
                                    android.net.Uri uri = android.net.Uri.parse(queryOrUrl);
                                    if (uri.getHost() != null) host = uri.getHost();
                                } catch (Exception ignored) {}
                                if (aiToolStatusText != null) {
                                    aiToolStatusText.setText(getString(R.string.ai_tool_fetching) + (host != null ? " (" + host + ")" : ""));
                                }
                            } else {
                                if (aiToolStatusIcon != null) {
                                    aiToolStatusIcon.setImageResource(R.drawable.ic_search);
                                }
                                String query = queryOrUrl != null ? queryOrUrl : "";
                                if (query.length() > 50) query = query.substring(0, 50) + "…";
                                if (aiToolStatusText != null) {
                                    aiToolStatusText.setText(getString(R.string.ai_tool_searching) + " \"" + query + "\"");
                                }
                            }
                            aiToolStatusContainer.setAlpha(0f);
                            aiToolStatusContainer.setVisibility(View.VISIBLE);
                            aiToolStatusContainer.animate().alpha(1f).setDuration(220).start();
                        });
                    }

                    @Override
                    public void onToolCompleted(String toolName, int resultsCount) {
                        handler.post(() -> {
                            if (isDestroyed || aiToolStatusContainer == null) return;
                            if (aiToolStatusIcon != null) {
                                aiToolStatusIcon.setImageResource(R.drawable.ic_check);
                            }
                            if (aiToolStatusText != null) {
                                if ("web_fetch".equals(toolName)) {
                                    aiToolStatusText.setText(getString(R.string.ai_tool_fetch_done));
                                } else {
                                    aiToolStatusText.setText(getString(R.string.ai_tool_search_done, resultsCount));
                                }
                            }
                            scheduleToolStatusHide();
                        });
                    }

                    @Override
                    public void onDelta(String textDelta) {
                        handler.post(() -> {
                            responseAcc.append(textDelta);
                            if (activeAiBubbleAnimator != null) {
                                activeAiBubbleAnimator.updateStream(responseAcc.toString());
                            }
                            if (aiChatScroll != null) {
                                aiChatScroll.post(() -> aiChatScroll.fullScroll(View.FOCUS_DOWN));
                            }
                        });
                    }

                    @Override
                    public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                        handler.post(() -> {
                            activeChatCall = null;
                            hideToolStatusNow();
                            String finalContent = (fullText != null && !fullText.trim().isEmpty())
                                    ? fullText : responseAcc.toString();
                            if (activeAiBubbleAnimator != null) {
                                activeAiBubbleAnimator.complete(finalContent);
                                activeAiBubbleAnimator = null;
                            }
                            if (webSources != null && !webSources.isEmpty()) {
                                displayWebSources(webSources);
                            }
                            addSourceChipsBelowBubble(aiBubble, webSources);
                            if (aiChatScroll != null) {
                                aiChatScroll.post(() -> aiChatScroll.fullScroll(View.FOCUS_DOWN));
                            }
                        });
                    }

                    @Override
                    public void onError(Exception error) {
                        handler.post(() -> {
                            activeChatCall = null;
                            hideToolStatusNow();
                            if (activeAiBubbleAnimator != null) {
                                activeAiBubbleAnimator.error(error != null ? error.getMessage() : "Unknown error");
                                activeAiBubbleAnimator = null;
                            }
                        });
                    }
                });
    }

    private void cancelPendingToolStatusHide() {
        if (pendingToolStatusHide != null) {
            handler.removeCallbacks(pendingToolStatusHide);
            pendingToolStatusHide = null;
        }
    }

    private void scheduleToolStatusHide() {
        cancelPendingToolStatusHide();
        pendingToolStatusHide = () -> {
            pendingToolStatusHide = null;
            hideToolStatusNow();
        };
        handler.postDelayed(pendingToolStatusHide, 1200);
    }

    private void hideToolStatusNow() {
        cancelPendingToolStatusHide();
        if (aiToolStatusContainer != null) {
            aiToolStatusContainer.setVisibility(View.GONE);
        }
    }

    /** Clickable web-source chips appended below the last AI chat bubble (max 5). */
    private void addSourceChipsBelowBubble(TextView aiBubble, List<String> webSources) {
        if (aiChatMessages == null || webSources == null) return;
        int added = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String source : webSources) {
            if (added >= 5) break;
            CleanMarkdownFormatter.SourceChipData chipData = CleanMarkdownFormatter.resolveSourceChip(source, this);
            if (chipData == null) continue;
            String key = chipData.displayLabel.toLowerCase(java.util.Locale.ROOT);
            if (seen.contains(key)) continue;
            seen.add(key);

            final String url = chipData.targetUrl;

            TextView chip = new TextView(this);
            chip.setText(chipData.displayLabel);
            chip.setTextColor(getResources().getColor(R.color.accent));
            chip.setTextSize(10f);
            chip.setBackgroundResource(R.drawable.bg_prompt_card);
            chip.setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4));
            chip.setMaxLines(1);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.START;
            lp.setMargins(0, dpToPx(2), dpToPx(32), dpToPx(2));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                Haptics.tick(v);
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(FloatingOverlayService.this, "Cannot open: " + url, Toast.LENGTH_SHORT).show();
                }
            });
            aiChatMessages.addView(chip);
            added++;
        }
    }

    private void addUserChatBubble(String text) {
        if (aiChatMessages == null) return;
        TextView bubble = new TextView(this);
        CleanMarkdownFormatter.applyMarkdown(bubble, text, false);
        bubble.setTextColor(getResources().getColor(R.color.text_primary));
        bubble.setTextSize(12f);
        bubble.setBackgroundResource(R.drawable.bg_chat_bubble_user);
        bubble.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.setMargins(dpToPx(32), dpToPx(4), 0, dpToPx(4));
        bubble.setLayoutParams(lp);

        aiChatMessages.addView(bubble);
        if (aiChatScroll != null) {
            aiChatScroll.post(() -> aiChatScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private TextView addAiChatBubble(String initialText) {
        if (aiChatMessages == null) return null;
        TextView bubble = new TextView(this);
        CleanMarkdownFormatter.applyMarkdown(bubble, initialText, false);
        bubble.setTextColor(getResources().getColor(R.color.text_secondary));
        bubble.setTextSize(12f);
        bubble.setBackgroundResource(R.drawable.bg_chat_bubble_assistant);
        bubble.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        bubble.setMovementMethod(io.noties.markwon.ext.tables.TableAwareMovementMethod.create());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.START;
        lp.setMargins(0, dpToPx(4), dpToPx(32), dpToPx(4));
        bubble.setLayoutParams(lp);

        aiChatMessages.addView(bubble);
        if (aiChatScroll != null) {
            aiChatScroll.post(() -> aiChatScroll.fullScroll(View.FOCUS_DOWN));
        }
        return bubble;
    }

    private void handleAiNewChatOverlay() {
        if (activeChatCall != null) {
            try {
                activeChatCall.cancel();
            } catch (Exception ignored) {}
            activeChatCall = null;
        }
        if (activeAiBubbleAnimator != null) {
            activeAiBubbleAnimator.stopAll();
            activeAiBubbleAnimator = null;
        }
        hideToolStatusNow();

        if (songChatManager != null) {
            songChatManager.startNewSession();
        }
        if (aiChatMessages != null) {
            aiChatMessages.removeAllViews();
        }
        if (aiChatScroll != null) {
            aiChatScroll.setVisibility(View.GONE);
        }
        if (aiChatEmptyState != null) {
            aiChatEmptyState.setVisibility(View.VISIBLE);
        }
        if (aiPromptTemplatesContainer != null) {
            aiPromptTemplatesContainer.setVisibility(View.VISIBLE);
        }
        if (aiChatInput != null) {
            aiChatInput.setText("");
        }
        Toast.makeText(this, R.string.ai_new_chat_started, Toast.LENGTH_SHORT).show();
    }

    private void restoreChatSessionOverlay(AiChatHistoryManager.ChatSession session) {
        if (session == null || songChatManager == null) return;
        if (activeChatCall != null) {
            try {
                activeChatCall.cancel();
            } catch (Exception ignored) {}
            activeChatCall = null;
        }
        if (activeAiBubbleAnimator != null) {
            activeAiBubbleAnimator.stopAll();
            activeAiBubbleAnimator = null;
        }
        hideToolStatusNow();

        songChatManager.loadSession(session);

        if (aiChatMessages != null) {
            aiChatMessages.removeAllViews();
            if (aiChatScroll != null) {
                aiChatScroll.setVisibility(View.VISIBLE);
            }
            if (aiChatEmptyState != null) {
                aiChatEmptyState.setVisibility(View.GONE);
            }
            if (aiPromptTemplatesContainer != null) {
                aiPromptTemplatesContainer.setVisibility(View.GONE);
            }

            if (session.messages != null) {
                for (AiChatMessage msg : session.messages) {
                    if (msg == null || msg.content == null || msg.content.trim().isEmpty()) continue;
                    boolean isUser = "user".equalsIgnoreCase(msg.role);
                    if (isUser) {
                        addUserChatBubble(msg.content);
                    } else {
                        TextView bubble = addAiChatBubble("");
                        CleanMarkdownFormatter.applyMarkdown(bubble, msg.content, false);
                    }
                }
            }

            if (session.webSources != null && !session.webSources.isEmpty()) {
                addSourceChipsBelowBubble(null, session.webSources);
            }

            if (aiChatScroll != null) {
                aiChatScroll.post(() -> aiChatScroll.fullScroll(View.FOCUS_DOWN));
            }
        }
        Toast.makeText(this, R.string.ai_history_restore, Toast.LENGTH_SHORT).show();
    }

    private void showAiHistoryDialogOverlay() {
        if (aiChatHistoryManager == null) {
            aiChatHistoryManager = new AiChatHistoryManager(this);
        }

        final Context themedContext = new ContextThemeWrapper(this, R.style.Theme_LyricSync);
        View dialogView = LayoutInflater.from(themedContext).inflate(R.layout.dialog_ai_chat_history, null);
        AlertDialog dialog = new AlertDialog.Builder(themedContext)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            } else {
                dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_PHONE);
            }
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        TextView btnClearAll = dialogView.findViewById(R.id.btn_history_clear_all);
        View btnClose = dialogView.findViewById(R.id.btn_history_close);
        View emptyView = dialogView.findViewById(R.id.layout_history_empty);
        ScrollView scrollView = dialogView.findViewById(R.id.scroll_history_sessions);
        LinearLayout sessionsContainer = dialogView.findViewById(R.id.container_history_sessions);

        Runnable refreshList = new Runnable() {
            @Override
            public void run() {
                if (sessionsContainer == null) return;
                sessionsContainer.removeAllViews();
                List<AiChatHistoryManager.ChatSession> sessions = aiChatHistoryManager.getSessions();
                if (sessions.isEmpty()) {
                    if (emptyView != null) emptyView.setVisibility(View.VISIBLE);
                    if (scrollView != null) scrollView.setVisibility(View.GONE);
                    if (btnClearAll != null) btnClearAll.setVisibility(View.GONE);
                } else {
                    if (emptyView != null) emptyView.setVisibility(View.GONE);
                    if (scrollView != null) scrollView.setVisibility(View.VISIBLE);
                    if (btnClearAll != null) btnClearAll.setVisibility(View.VISIBLE);

                    for (final AiChatHistoryManager.ChatSession session : sessions) {
                        View itemView = LayoutInflater.from(themedContext)
                                .inflate(R.layout.item_ai_history_session, sessionsContainer, false);

                        TextView tvTitle = itemView.findViewById(R.id.tv_history_song_title);
                        TextView tvArtistDate = itemView.findViewById(R.id.tv_history_artist_date);
                        TextView tvSnippet = itemView.findViewById(R.id.tv_history_snippet);
                        TextView tvCount = itemView.findViewById(R.id.tv_history_message_count);
                        ImageView btnDelete = itemView.findViewById(R.id.btn_delete_history_session);

                        String titleText = (session.songTitle != null && !session.songTitle.trim().isEmpty())
                                ? session.songTitle.trim() : getString(R.string.ai_insights_title);
                        if (tvTitle != null) tvTitle.setText(titleText);

                        StringBuilder sub = new StringBuilder();
                        if (session.songArtist != null && !session.songArtist.trim().isEmpty()) {
                            sub.append(session.songArtist.trim()).append(" · ");
                        }
                        sub.append(session.getFormattedDate());
                        if (tvArtistDate != null) tvArtistDate.setText(sub.toString());

                        String snip = session.getSnippet();
                        if (tvSnippet != null) {
                            if (!snip.isEmpty()) {
                                tvSnippet.setText(snip);
                                tvSnippet.setVisibility(View.VISIBLE);
                            } else {
                                tvSnippet.setVisibility(View.GONE);
                            }
                        }

                        int count = session.getMessageCount();
                        if (tvCount != null) {
                            tvCount.setText(getString(R.string.ai_history_session_messages, count));
                        }

                        itemView.setOnClickListener(v -> {
                            Haptics.tick(v);
                            dialog.dismiss();
                            restoreChatSessionOverlay(session);
                        });

                        if (btnDelete != null) {
                            btnDelete.setOnClickListener(v -> {
                                Haptics.tick(v);
                                aiChatHistoryManager.deleteSession(session.id);
                                Toast.makeText(FloatingOverlayService.this, R.string.ai_history_deleted_one, Toast.LENGTH_SHORT).show();
                                run();
                            });
                        }

                        sessionsContainer.addView(itemView);
                    }
                }
            }
        };

        refreshList.run();

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> {
                Haptics.tick(v);
                dialog.dismiss();
            });
        }

        if (btnClearAll != null) {
            btnClearAll.setOnClickListener(v -> {
                Haptics.tick(v);
                AlertDialog confirmDialog = new AlertDialog.Builder(themedContext)
                        .setTitle(R.string.ai_history_clear_confirm_title)
                        .setMessage(R.string.ai_history_clear_confirm_msg)
                        .setPositiveButton(R.string.ai_history_clear_all, (d, which) -> {
                            aiChatHistoryManager.clearAllSessions();
                            Toast.makeText(FloatingOverlayService.this, R.string.ai_history_clear_success, Toast.LENGTH_SHORT).show();
                            refreshList.run();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();
                if (confirmDialog.getWindow() != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        confirmDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                    } else {
                        confirmDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_PHONE);
                    }
                }
                confirmDialog.show();
            });
        }

        dialog.show();
    }

    public void showEditSyllablesDialog(int lineIndex, LyricsData.LyricsLine line) {
        if (line == null) return;

        final Context themedContext = new ContextThemeWrapper(this, R.style.Theme_LyricSync);
        View dialogView = LayoutInflater.from(themedContext).inflate(R.layout.dialog_edit_syllables, null);
        AlertDialog dialog = new AlertDialog.Builder(themedContext)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            } else {
                dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_PHONE);
            }
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        TextView textLineIndex = dialogView.findViewById(R.id.dialog_line_index);
        TextView textLineOriginal = dialogView.findViewById(R.id.text_line_original);
        LinearLayout containerSyllables = dialogView.findViewById(R.id.container_syllables);
        EditText editLineTranslation = dialogView.findViewById(R.id.edit_line_translation);
        TextView btnAiRegenerate = dialogView.findViewById(R.id.btn_ai_regenerate);
        TextView btnCancel = dialogView.findViewById(R.id.btn_cancel);
        TextView btnSave = dialogView.findViewById(R.id.btn_save);

        if (textLineIndex != null) {
            textLineIndex.setText(String.format(java.util.Locale.getDefault(), "#%d", lineIndex + 1));
        }
        if (textLineOriginal != null) {
            textLineOriginal.setText(line.text != null ? line.text : "");
        }
        if (editLineTranslation != null) {
            editLineTranslation.setText(line.translation != null ? line.translation : "");
        }

        List<EditText> syllableEditList = new ArrayList<>();
        Runnable populateChips = () -> {
            if (containerSyllables == null) return;
            containerSyllables.removeAllViews();
            syllableEditList.clear();
            if (line.words != null && !line.words.isEmpty()) {
                for (LyricsData.Word word : line.words) {
                    View chipView = LayoutInflater.from(themedContext).inflate(R.layout.item_edit_syllable_chip, containerSyllables, false);
                    TextView textSyllable = chipView.findViewById(R.id.text_syllable_original);
                    EditText editSyllable = chipView.findViewById(R.id.edit_syllable_romanji);
                    textSyllable.setText(word.text != null ? word.text : "");
                    editSyllable.setText(word.romanji != null ? word.romanji : "");
                    syllableEditList.add(editSyllable);
                    containerSyllables.addView(chipView);
                }
            } else {
                View chipView = LayoutInflater.from(themedContext).inflate(R.layout.item_edit_syllable_chip, containerSyllables, false);
                TextView textSyllable = chipView.findViewById(R.id.text_syllable_original);
                EditText editSyllable = chipView.findViewById(R.id.edit_syllable_romanji);
                textSyllable.setText(line.text != null ? line.text : "");
                editSyllable.setText(line.romanji != null ? line.romanji : "");
                syllableEditList.add(editSyllable);
                containerSyllables.addView(chipView);
            }
        };
        populateChips.run();

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }

        if (btnAiRegenerate != null) {
            btnAiRegenerate.setOnClickListener(v -> {
                if (aiTransliterationService == null) return;
                Toast.makeText(this, R.string.ai_transliterating, Toast.LENGTH_SHORT).show();
                btnAiRegenerate.setEnabled(false);
                LyricsData singleData = new LyricsData(LyricsData.Type.SYLLABLE);
                LyricsData.LyricsLine testLine = new LyricsData.LyricsLine();
                testLine.text = line.text;
                testLine.startTime = line.startTime;
                testLine.endTime = line.endTime;
                if (line.words != null) {
                    for (LyricsData.Word w : line.words) {
                        LyricsData.Word nw = new LyricsData.Word();
                        nw.text = w.text;
                        nw.startTime = w.startTime;
                        nw.endTime = w.endTime;
                        testLine.words.add(nw);
                    }
                }
                singleData.lines.add(testLine);

                String title = currentTrack != null ? currentTrack.title : "";
                String artist = currentTrack != null ? currentTrack.artist : "";
                String targetLang = LocaleHelper.getLanguage(this);
                aiTransliterationService.transliterateSyllablesAsync(
                        singleData, title, artist, targetLang,
                        new AiSyllableTransliterationService.Callback() {
                            @Override
                            public void onSuccess(LyricsData enrichedData) {
                                btnAiRegenerate.setEnabled(true);
                                if (enrichedData.lines != null && !enrichedData.lines.isEmpty()) {
                                    LyricsData.LyricsLine resLine = enrichedData.lines.get(0);
                                    if (resLine.translation != null && editLineTranslation != null) {
                                        editLineTranslation.setText(resLine.translation);
                                    }
                                    if (resLine.words != null && resLine.words.size() == syllableEditList.size()) {
                                        for (int i = 0; i < syllableEditList.size(); i++) {
                                            syllableEditList.get(i).setText(resLine.words.get(i).romanji);
                                        }
                                    } else if (!syllableEditList.isEmpty() && resLine.romanji != null) {
                                        syllableEditList.get(0).setText(resLine.romanji);
                                    }
                                }
                                Toast.makeText(FloatingOverlayService.this, R.string.ai_transliteration_complete, Toast.LENGTH_SHORT).show();
                            }

                            @Override
                            public void onError(String message) {
                                btnAiRegenerate.setEnabled(true);
                                Toast.makeText(FloatingOverlayService.this, message != null ? message : "Error", Toast.LENGTH_SHORT).show();
                            }
                        });
            });
        }

        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                if (editLineTranslation != null) {
                    line.translation = editLineTranslation.getText().toString().trim();
                }
                List<String> wordsRomanji = new ArrayList<>();
                StringBuilder sbRomaji = new StringBuilder();
                if (line.words != null && !line.words.isEmpty()) {
                    for (int i = 0; i < line.words.size() && i < syllableEditList.size(); i++) {
                        String r = syllableEditList.get(i).getText().toString().trim();
                        line.words.get(i).romanji = r;
                        wordsRomanji.add(r);
                        if (!r.isEmpty()) {
                            if (sbRomaji.length() > 0) sbRomaji.append(" ");
                            sbRomaji.append(r);
                        }
                    }
                    line.romanji = sbRomaji.toString();
                } else if (!syllableEditList.isEmpty()) {
                    String r = syllableEditList.get(0).getText().toString().trim();
                    line.romanji = r;
                    wordsRomanji.add(r);
                }

                if (highlighter != null) {
                    highlighter.updateLineTransliteration(lineIndex, wordsRomanji, line.romanji);
                    highlighter.updateLineTranslation(lineIndex, line.translation);
                }

                if (lyricsCacheManager != null && currentTrack != null && currentLyrics != null) {
                    lyricsCacheManager.cache(currentTrack, currentLyrics);
                }

                dialog.dismiss();
            });
        }

        dialog.show();
    }

    /** Font + animation chips inside the settings panel; choices apply live via prefs. */
    private void buildOverlayChips() {
        if (overlayFontChips != null) {
            FontChips.build(this, overlayFontChips,
                    AppFont.currentKey(sharedPrefs), true,
                    style -> Haptics.tick(overlayFontChips));
        }
        if (overlayAnimChips != null) {
            AnimStyleChips.buildCompact(this, overlayAnimChips, overlayAnimEffectChips,
                    new AndroidPrefs(sharedPrefs),
                    (m, e) -> Haptics.tick(overlayAnimChips));
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

    // ── Resizing ───────────────────────────────────────────────────────────

    private void updateResizeHandleVisibility() {
        boolean settingsOpen = overlaySettingsPanel != null && overlaySettingsPanel.getVisibility() == View.VISIBLE;
        updateResizeHandleVisibility(settingsOpen);
    }

    private void updateResizeHandleVisibility(boolean settingsOpen) {
        if (overlayResizeContainer == null) return;
        boolean shouldShow = (showResizeHandle || settingsOpen) && lyricsVisible;
        overlayResizeContainer.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
    }

    private void setupResizeHandling() {
        if (overlayResizeHandle == null) return;

        final DisplayMetrics metrics = getResources().getDisplayMetrics();
        final float[] initialRawX = new float[1];
        final float[] initialRawY = new float[1];
        final int[] initialWidth = new int[1];
        final int[] initialHeight = new int[1];
        final int[] initialParamX = new int[1];
        final boolean[] isResizing = new boolean[1];
        final int touchSlop = dpToPx(6);

        overlayResizeHandle.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialRawX[0] = event.getRawX();
                    initialRawY[0] = event.getRawY();
                    initialWidth[0] = overlayParams != null ? overlayParams.width : overlayView.getWidth();
                    initialHeight[0] = overlayBody != null && overlayBody.getHeight() > 0
                            ? overlayBody.getHeight() : lyricsHeightPx;
                    initialParamX[0] = overlayParams != null ? overlayParams.x : 0;
                    isResizing[0] = false;
                    Haptics.tick(v);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - initialRawX[0];
                    float dy = event.getRawY() - initialRawY[0];
                    if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                        isResizing[0] = true;
                    }
                    if (isResizing[0] && overlayParams != null && windowManager != null) {
                        int minWidth = dpToPx(220);
                        int maxWidth = Math.max(minWidth, (int) (metrics.widthPixels * 0.96f));
                        int targetWidth = (int) (initialWidth[0] + dx);
                        int clampedWidth = Math.max(minWidth, Math.min(maxWidth, targetWidth));

                        int widthDelta = clampedWidth - initialWidth[0];
                        overlayParams.width = clampedWidth;
                        overlayParams.x = initialParamX[0] + (widthDelta / 2);
                        clampOverlayPosition(overlayParams);

                        if (overlayBody != null && lyricsVisible) {
                            int minHeight = dpToPx(140);
                            int maxHeight = Math.max(minHeight, (int) (metrics.heightPixels * 0.70f));
                            int targetHeight = (int) (initialHeight[0] + dy);
                            int clampedHeight = Math.max(minHeight, Math.min(maxHeight, targetHeight));

                            ViewGroup.LayoutParams bodyParams = overlayBody.getLayoutParams();
                            bodyParams.height = clampedHeight;
                            overlayBody.setLayoutParams(bodyParams);
                            lyricsHeightPx = clampedHeight;
                        }

                        try {
                            windowManager.updateViewLayout(overlayView, overlayParams);
                        } catch (Exception e) {
                            AppLog.w(TAG, "Resize updateViewLayout failed: " + e.getMessage());
                        }
                        return true;
                    }
                    return false;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (isResizing[0] && overlayParams != null) {
                        int widthPct = Math.round((float) overlayParams.width * 100f / metrics.widthPixels);
                        int heightPct = Math.round((float) lyricsHeightPx * 100f / metrics.heightPixels);
                        widthPct = clamp(widthPct, 45, 96);
                        heightPct = clamp(heightPct, 20, 70);

                        overlayWidthPercent = widthPct;
                        sharedPrefs.edit()
                                .putInt("overlay_width_percent", widthPct)
                                .putInt("overlay_height_percent", heightPct)
                                .apply();

                        persistOverlayPosition(overlayParams);
                        applyResponsiveHeaderSizing();
                        applyResponsiveLyricsSpacing();
                        if (springScroller != null) {
                            springScroller.setScrollPositionRatio(calculateScrollPositionRatio());
                        }
                        Haptics.snap(v);
                    }
                    return isResizing[0];
            }
            return false;
        });
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
        if (translateService != null) {
            translateService.cancel();
        }
        if (aiTranslationService != null) {
            aiTranslationService.cancel();
        }
        if (activeMeaningCall != null) {
            activeMeaningCall.cancel();
            activeMeaningCall = null;
        }
        if (activeChatCall != null) {
            activeChatCall.cancel();
            activeChatCall = null;
        }
        if (activeTransliterationCall != null) {
            activeTransliterationCall.cancel();
            activeTransliterationCall = null;
        }
        if (songChatManager != null && track != null) {
            songChatManager.setCurrentSong(track.title, track.artist, "", null);
        }
        if (aiChatMessages != null) {
            aiChatMessages.removeAllViews();
        }
        if (aiMoodBadge != null) {
            aiMoodBadge.setVisibility(View.GONE);
        }
        if (overlayMoodBadge != null) {
            overlayMoodBadge.setVisibility(View.GONE);
        }
        if (aiWebSourcesContainer != null) {
            aiWebSourcesContainer.removeAllViews();
        }
        if (aiWebSourcesScroll != null) {
            aiWebSourcesScroll.setVisibility(View.GONE);
        }
        if (aiChatTitle != null) {
            aiChatTitle.setText(R.string.ai_insights_title);
        }
        if (aiChatArtist != null && track != null) {
            aiChatArtist.setText(track.title + " • " + track.artist);
        }
        if (aiChatEmptyState != null) {
            aiChatEmptyState.setVisibility(View.VISIBLE);
        }
        if (aiChatScroll != null) {
            aiChatScroll.setVisibility(View.GONE);
        }
        setupAiPromptTemplates();
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
                    setupAiPromptTemplates();
                    publishNowPlaying();
                    if (songChatManager != null && track != null) {
                        StringBuilder sb = new StringBuilder();
                        if (lyrics != null && lyrics.lines != null) {
                            for (LyricsData.LyricsLine l : lyrics.lines) {
                                if (l != null && l.text != null && !l.isInterlude) {
                                    sb.append(l.text).append("\n");
                                }
                            }
                        }
                        songChatManager.setCurrentSong(track.title, track.artist, sb.toString().trim(), null);
                    }
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
        highlighter.setTranslationEnabled(translationEnabled);
        boolean romajiPref = sharedPrefs.getBoolean("pref_romaji_enabled", true);
        highlighter.setTransliterationEnabled(romajiPref);
        boolean origPref = sharedPrefs.getBoolean("pref_original_script_enabled", true);
        highlighter.setOriginalScriptEnabled(origPref);

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
                lv.rootView.setOnLongClickListener(v -> {
                    showEditSyllablesDialog(index, line);
                    return true;
                });
            }

            lyricsContainer.addView(lv.rootView);
        }

        showLyricsState();
        if (lyricsVisible) {
            startRenderLoop();
            jumpToCurrentLineAfterLayout();
        }
        AppLog.i(TAG, "Rendered " + lyrics.lines.size() + " lyric lines, Choreographer loop started");
        updateTransliterationToggleUi();
        if (romajiPref && isLyricsNonLatin(lyrics) && !lyrics.hasTransliteration) {
            autoFetchTransliterationForCurrentLyrics();
        }
        if (translationEnabled) {
            String targetLang = sharedPrefs.getString(
                    com.lyricsync.app.lyrics.translation.GoogleTranslateService.PREF_TARGET_LANG,
                    com.lyricsync.app.lyrics.translation.GoogleTranslateService.DEFAULT_TARGET_LANG);
            String resolvedTarget = com.lyricsync.app.lyrics.translation.GoogleTranslateService.resolveTargetLang(
                    targetLang, java.util.Locale.getDefault());
            if (lyrics.translationLang == null || !resolvedTarget.equalsIgnoreCase(lyrics.translationLang)) {
                fetchTranslationForCurrentLyrics();
            }
        }
    }

    private void fetchTranslationForCurrentLyrics() {
        if (currentLyrics == null || currentLyrics.isEmpty()) return;
        final String targetLang = sharedPrefs.getString(
                com.lyricsync.app.lyrics.translation.GoogleTranslateService.PREF_TARGET_LANG,
                com.lyricsync.app.lyrics.translation.GoogleTranslateService.DEFAULT_TARGET_LANG);
        final String fetchKey = pendingFetchKey;
        final LyricsData targetLyrics = currentLyrics;
        final TrackInfo targetTrack = currentTrack;

        com.lyricsync.app.lyrics.translation.GoogleTranslateService.TranslationCallback callback =
                new com.lyricsync.app.lyrics.translation.GoogleTranslateService.TranslationCallback() {
            @Override
            public void onSuccess(com.lyricsync.app.lyrics.translation.GoogleTranslateParser.ParseResult result, String resolvedLang) {
                if (isDestroyed || !fetchKey.equals(pendingFetchKey)) return;
                if (result.isSameLanguage) {
                    AppLog.i(TAG, "Lyrics already in target language (" + result.detectedSourceLang + ")");
                    return;
                }
                targetLyrics.translationLang = resolvedLang;
                if (highlighter != null) {
                    for (int i = 0; i < targetLyrics.lines.size(); i++) {
                        highlighter.updateLineTranslation(i, targetLyrics.lines.get(i).translation);
                    }
                    highlighter.setTranslationEnabled(translationEnabled);
                }
                if (lyricsManager != null && targetTrack != null) {
                    lyricsManager.updateCachedLyrics(targetTrack, targetLyrics);
                }
            }

            @Override
            public void onError(Exception error) {
                AppLog.w(TAG, "Translation error: " + error.getMessage());
            }
        };

        boolean aiEnabled = sharedPrefs.getBoolean(
                com.lyricsync.app.lyrics.translation.AiTranslationService.PREF_AI_TRANSLATION_ENABLED, true);
        com.lyricsync.app.ai.auth.AntigravityAuthManager auth =
                com.lyricsync.app.ai.auth.AntigravityAuthManager.getInstance(this);
        boolean isAuthenticated = auth != null && auth.isAuthenticated();

        if (aiEnabled && isAuthenticated && aiTranslationService != null) {
            aiTranslationService.translateLyrics(targetLyrics.lines, targetLang, callback);
        } else if (translateService != null) {
            translateService.translateLines(targetLyrics.lines, targetLang, callback);
        }
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
            while (end < n && end < lines.size() && lines.get(end).startTime - position < 3000) {
                end++;
            }
            end = Math.min(n, end + 2);
        }
        if (highlighter != null) {
            highlighter.updateHighlight(position, deltaTime, start, end);
        }

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
        if (activeTransliterationCall != null) {
            activeTransliterationCall.cancel();
            activeTransliterationCall = null;
        }
        pendingFetchKey = null;
        currentTrack = null;
        lyricsStatusText = null;
        lastPaletteArt = null;
        lastPaletteArtUri = null;
        pendingArtUri = null;
        clearLyricsOnly();
        Anim.setTextAnimated(overlayTitle, getString(R.string.no_music));
        Anim.setTextAnimated(overlayArtist, "");
        if (overlayMoodBadge != null) overlayMoodBadge.setVisibility(View.GONE);
        if (aiMoodBadge != null) aiMoodBadge.setVisibility(View.GONE);
        if (aiChatTitle != null) aiChatTitle.setText(R.string.ai_insights_title);
        if (aiChatArtist != null) aiChatArtist.setText("");
        if (aiChatMessages != null) aiChatMessages.removeAllViews();
        if (aiWebSourcesContainer != null) aiWebSourcesContainer.removeAllViews();
        if (aiWebSourcesScroll != null) aiWebSourcesScroll.setVisibility(View.GONE);
        if (aiChatEmptyState != null) aiChatEmptyState.setVisibility(View.VISIBLE);
        if (aiChatScroll != null) aiChatScroll.setVisibility(View.GONE);
        setupAiPromptTemplates();
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
        if (aiTranslationService != null) {
            aiTranslationService.cancel();
        }
        if (translateService != null) {
            translateService.cancel();
        }
        if (activeMeaningCall != null) {
            activeMeaningCall.cancel();
            activeMeaningCall = null;
        }
        if (activeChatCall != null) {
            activeChatCall.cancel();
            activeChatCall = null;
        }
        if (activeTransliterationCall != null) {
            activeTransliterationCall.cancel();
            activeTransliterationCall = null;
        }
        if (activeAiBubbleAnimator != null) {
            activeAiBubbleAnimator.stopAll();
            activeAiBubbleAnimator = null;
        }
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
        dismissAiChatOverlay();
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                AppLog.e(TAG, "Error removing overlay", e);
            }
        }
    }
}
