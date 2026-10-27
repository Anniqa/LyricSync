package com.lyricsync.app.overlay;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import com.lyricsync.app.util.LocaleHelper;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
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
import com.lyricsync.app.ai.client.LyricActionDelegate;
import com.lyricsync.app.ai.client.MainThreadLyricActionRouter;
import com.lyricsync.app.ai.client.AntigravityQuotaClient;
import com.lyricsync.app.ai.history.AiChatHistoryManager;
import com.lyricsync.app.ai.model.QuotaSummary;
import com.lyricsync.app.ai.service.SongChatManager;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor;
import com.lyricsync.app.ai.tools.LyricsEditor;
import com.lyricsync.app.ai.util.AiStreamingAnimator;
import com.lyricsync.app.ai.util.CleanMarkdownFormatter;

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
import com.lyricsync.app.ai.transliteration.AiSyllableTransliterationService;
import com.lyricsync.app.ai.transliteration.TransliterationRequest;
import com.lyricsync.app.ai.transliteration.ScriptDetector;
import com.lyricsync.app.lyrics.cache.LyricsCacheManager;
import com.lyricsync.app.lyrics.LyricsProviderManager;
import com.lyricsync.app.lyrics.translation.AiTranslationService;
import com.lyricsync.app.lyrics.translation.GoogleTranslateParser;
import com.lyricsync.app.lyrics.translation.GoogleTranslateService;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.renderer.AnimConfig;
import com.lyricsync.app.renderer.AnimStyleDef;
import com.lyricsync.app.renderer.SpringScroller;
import com.lyricsync.app.renderer.SyllableHighlighter;
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

import java.lang.ref.WeakReference;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class FloatingOverlayService extends Service implements LyricActionDelegate {
    private static final String TAG = "FloatingOverlay";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_STOP = "com.lyricsync.app.action.STOP";
    public static final String ACTION_TOGGLE = "com.lyricsync.app.action.TOGGLE";

    /** Every overlay tunable (sizes, gestures, timings, theme). Swap for a custom build. */
    private final OverlayConfig config = OverlayConfig.DEFAULT;

    /** Read by MainActivity to render Start vs Stop without binding to the service. */
    private static volatile boolean running = false;

    /**
     * Weak reference to the live service. A strong static field would pin the Service
     * (a Context) and everything it owns even after onDestroy; a WeakReference lets GC
     * reclaim it, so a stale static link can never keep a Context alive.
     */
    private static volatile WeakReference<FloatingOverlayService> activeInstanceRef = null;

    public static boolean isRunning() {
        return running;
    }

    /** Returns the live service, or null once it has been destroyed/collected. Callers must null-check. */
    public static FloatingOverlayService getInstance() {
        WeakReference<FloatingOverlayService> ref = activeInstanceRef;
        return ref != null ? ref.get() : null;
    }

    private WindowManager windowManager;
    private View overlayView;
    private OverlayScreen screen;
    private OverlayWindowController window;
    private OverlayChatWindows chatWindows;
    private OverlayTheme theme;
    private OverlayRenderLoop renderLoop;
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
    // Retry state machine
    public static final int RETRY_STATE_IDLE = 0;
    public static final int RETRY_STATE_STANDARD = 1;

    public static final int STATE_IDLE = RETRY_STATE_IDLE;
    public static final int STATE_STANDARD_RETRYING = RETRY_STATE_STANDARD;
    public static final int STATE_LOADED = 3;
    public static final int STATE_FAILED = 4;

    private int currentRetryState = RETRY_STATE_IDLE;
    private View overlayRetryActionsContainer;
    private TextView btnRetryStandard;
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
    private float overlayFontSizeSp;
    private int overlayWidthPercent;
    private int lyricsHeightPx = 0;
    private boolean lyricsVisible = true;
    private View overlayResizeContainer;
    private ImageView overlayResizeHandle;
    private androidx.appcompat.widget.SwitchCompat overlayResizeSwitch;
    private boolean showResizeHandle = false;
    private GoogleTranslateService translateService;
    private AiTranslationService aiTranslationService;
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
    private boolean isProgrammaticTranslateUpdate = false;
    /** The AI's edits and their undo history for the current lyrics. */
    private final LyricsEditor lyricsEditor = new LyricsEditor();

    // AI Insights UI & Subsystems
    private ImageView overlayAiButton;

    // Secondary windows (floating AI chat + its minimised bubble): see OverlayChatWindows.
    private TextView aiChatTitle;
    private TextView aiChatArtist;
    private ImageView aiWebSearchChip;
    ScrollView aiChatEmptyState;
    LinearLayout aiPromptTemplatesContainer;
    private HorizontalScrollView aiWebSourcesScroll;
    private LinearLayout aiWebSourcesContainer;
    ScrollView aiChatScroll;
    LinearLayout aiChatMessages;
    EditText aiChatInput;
    private ImageView aiChatSend;
    private TextView aiQuotaBadge;
    private LinearLayout aiToolStatusContainer;
    private ImageView aiToolStatusIcon;
    private TextView aiToolStatusText;
    private Runnable pendingToolStatusHide;

    SongChatManager songChatManager;
    AiChatHistoryManager aiChatHistoryManager;
    private AntigravityQuotaClient quotaClient;
    AntigravityApiClient.Cancellable activeChatCall;
    private final OverlayChatHistoryController chatHistoryController = new OverlayChatHistoryController(this);
    private AntigravityApiClient.Cancellable activeTransliterationCall;
    AiStreamingAnimator activeAiBubbleAnimator;
    private boolean aiWebSearchEnabled = false;

    private ObjectAnimator skeletonAnimator;
    private ValueAnimator bodyAnimator;
    /** updateTrackInfo runs on metadata refreshes too; only re-run Palette on new art. */
    private Bitmap lastPaletteArt;
    /** Same guard for URI-loaded art; the palette runs on the decoded bitmap. */
    private String lastPaletteArtUri;
    /** In-flight Glide target for URI album art; cleared on replacement and in onDestroy. */
    private com.bumptech.glide.request.target.CustomTarget<Bitmap> coverArtTarget;
    /** RequestManager owning coverArtTarget, so it can be cleared without re-resolving Glide. */
    private com.bumptech.glide.RequestManager glideRequestManager;
    /** Guards against a slow Glide load landing after the track already changed. */
    private String pendingArtUri;

    private final Runnable applySettingsRunnable = () -> applyRuntimeSettings(true);
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener = (prefs, key) -> {
        if (OverlayPrefKeys.FONT_SCALE.equals(key)
                || OverlayPrefKeys.WIDTH_PERCENT.equals(key)
                || OverlayPrefKeys.HEIGHT_PERCENT.equals(key)) {
            scheduleApplySettings();
        } else if (AppFont.PREF_KEY.equals(key)
                || AnimSelection.PREF_MOTION.equals(key) || AnimSelection.PREF_EFFECT.equals(key)) {
            // Typeface / animation-style changes need a full highlighter rebuild,
            // and the panel chips must reflect choices made in the activity.
            scheduleApplySettings();
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
        } else if (OverlayPrefKeys.SYNC_OFFSET_MS.equals(key) && sessionTracker != null) {
            long offset = prefs.getLong(OverlayPrefKeys.SYNC_OFFSET_MS, 0);
            sessionTracker.setSyncOffsetMs(offset);
            syncOffsetSliderFromPrefs(offset);
        } else if (GoogleTranslateService.PREF_TRANSLATION_ENABLED.equals(key)) {
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
        } else if (GoogleTranslateService.PREF_TARGET_LANG.equals(key)) {
            if (translationEnabled && currentLyrics != null) {
                fetchTranslationForCurrentLyrics();
            }
        } else if (OverlayPrefKeys.SHOW_RESIZE_HANDLE.equals(key)) {
            boolean show = prefs.getBoolean(key, false);
            if (this.showResizeHandle != show) {
                this.showResizeHandle = show;
                if (overlayResizeSwitch != null) {
                    overlayResizeSwitch.setChecked(show);
                }
                updateResizeHandleVisibility();
            }
        } else if (OverlayPrefKeys.ROMAJI_ENABLED.equals(key)) {
            boolean enabled = prefs.getBoolean(key, true);
            if (highlighter != null) {
                highlighter.setTransliterationEnabled(enabled);
            }
            if (overlayRomajiSwitch != null && overlayRomajiSwitch.isChecked() != enabled) {
                isProgrammaticRomajiUpdate = true;
                overlayRomajiSwitch.setChecked(enabled);
                isProgrammaticRomajiUpdate = false;
            }
        } else if (OverlayPrefKeys.ORIGINAL_SCRIPT_ENABLED.equals(key)) {
            boolean enabled = prefs.getBoolean(key, true);
            if (highlighter != null) {
                highlighter.setOriginalScriptEnabled(enabled);
            }
        }
    };

    private void scheduleApplySettings() {
        handler.removeCallbacks(applySettingsRunnable);
        handler.postDelayed(applySettingsRunnable, config.settingsDebounceMs);
    }

    private final List<SyllableHighlighter.LineView> lineViews = new ArrayList<>();
    private SpringScroller springScroller;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        activeInstanceRef = new WeakReference<>(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        screen = new OverlayScreen(windowManager, getResources());

        sharedPrefs = getSharedPreferences(OverlayPrefKeys.FILE, MODE_PRIVATE);
        translateService = new GoogleTranslateService();
        AntigravityAuthManager authManager =
                AntigravityAuthManager.getInstance(this);
        apiClient = new AntigravityApiClient(authManager);
        apiClient.setLyricActionDelegate(new MainThreadLyricActionRouter(handler, this));
        aiTranslationService = new AiTranslationService(
                this, authManager, apiClient, translateService);
        songChatManager = new SongChatManager(apiClient);
        aiChatHistoryManager = new AiChatHistoryManager(this);
        songChatManager.setHistoryManager(aiChatHistoryManager);
        aiTransliterationService = new AiSyllableTransliterationService(this, apiClient);
        lyricsCacheManager = new LyricsCacheManager(this);
        quotaClient = new AntigravityQuotaClient(authManager);
        // Capture the SharedPreferences instance, not the service, so this static supplier
        // does not keep the Service (a Context) alive after onDestroy.
        final SharedPreferences chatModelPrefs = sharedPrefs;
        AntigravityToolExecutor.setChatModelSupplier(() ->
                chatModelPrefs.getString(OverlayPrefKeys.AI_MODEL, SongChatManager.DEFAULT_MODEL));
        translationEnabled = sharedPrefs.getBoolean(
                GoogleTranslateService.PREF_TRANSLATION_ENABLED, false);
        loadTypefaces();
        lyricAnimConfig = AnimSelection.current(new AndroidPrefs(sharedPrefs));

        overlayWidthPercent = OverlayGeometry.widthPercent(config,
                sharedPrefs.getInt(OverlayPrefKeys.WIDTH_PERCENT, config.widthPercentDefault));
        overlayFontSizeSp = calculateFontSizeSp();
        highlighter = new SyllableHighlighter(this, fontBold, fontMedium, overlayFontSizeSp);
        highlighter.setAnimConfig(lyricAnimConfig);
        highlighter.setTransliterationEnabled(romajiPref());
        highlighter.setOriginalScriptEnabled(originalScriptPref());
        renderLoop = new OverlayRenderLoop(config, renderFrame);
        theme = new OverlayTheme(config, screen, this::applyAccent);
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

    @SuppressLint("InflateParams") // a window root has no parent to inflate against
    private boolean createOverlay() {
        android.content.Context themedContext = new ContextThemeWrapper(this, R.style.Theme_LyricSync);
        overlayView = LayoutInflater.from(themedContext).inflate(R.layout.overlay_lyrics, null);
        window = new OverlayWindowController(windowManager, screen, sharedPrefs, config, windowHost);
        chatWindows = new OverlayChatWindows(themedContext, windowManager, screen, sharedPrefs, config, chatHost);

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
                if (isProgrammaticTranslateUpdate) return;
                translationEnabled = isChecked;
                sharedPrefs.edit().putBoolean(
                        GoogleTranslateService.PREF_TRANSLATION_ENABLED, isChecked).apply();
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
            overlayRomajiSwitch.setChecked(romajiPref());
            isProgrammaticRomajiUpdate = false;
            overlayRomajiSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
                if (isProgrammaticRomajiUpdate) return;
                if (highlighter != null) {
                    highlighter.setTransliterationEnabled(isChecked);
                }
                sharedPrefs.edit().putBoolean(OverlayPrefKeys.ROMAJI_ENABLED, isChecked).apply();
                if (isChecked && currentLyrics != null && isLyricsNonLatin(currentLyrics) && !currentLyrics.hasTransliteration) {
                    performTransliteration(TransliterationRequest.KEEP, null);
                }
                String labelName = (overlayTransliterationLabel != null && overlayTransliterationLabel.getText() != null)
                        ? overlayTransliterationLabel.getText().toString()
                        : getString(R.string.toggle_latin_transliteration);
                Toast.makeText(this, labelName + (isChecked ? " diaktifkan" : " dinonaktifkan"), Toast.LENGTH_SHORT).show();
            });
        }
        if (romajiRow != null) {
            View.OnLongClickListener longClickListener = v -> {
                Haptics.tick(v);
                if (highlighter != null) {
                    boolean newOrig = !highlighter.isOriginalScriptEnabled();
                    highlighter.setOriginalScriptEnabled(newOrig);
                    sharedPrefs.edit().putBoolean(OverlayPrefKeys.ORIGINAL_SCRIPT_ENABLED, newOrig).apply();
                    Toast.makeText(this, newOrig ? "Aksara asli ditampilkan" : "Aksara asli disembunyikan (Latin Saja)", Toast.LENGTH_SHORT).show();
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
        showResizeHandle = sharedPrefs.getBoolean(OverlayPrefKeys.SHOW_RESIZE_HANDLE, false);
        if (overlayResizeSwitch != null) {
            overlayResizeSwitch.setShowText(false);
            overlayResizeSwitch.setTextOn("");
            overlayResizeSwitch.setTextOff("");
            overlayResizeSwitch.setChecked(showResizeHandle);
            overlayResizeSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
                showResizeHandle = isChecked;
                sharedPrefs.edit().putBoolean(OverlayPrefKeys.SHOW_RESIZE_HANDLE, isChecked).apply();
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
        overlayRetryActionsContainer = overlayView.findViewById(R.id.overlay_retry_actions_container);
        btnRetryStandard = overlayView.findViewById(R.id.btn_retry_standard);
        setupRetryViews();

        lyricsVisible = sharedPrefs.getBoolean(OverlayPrefKeys.LYRICS_VISIBLE, true);
        overlayWidthPercent = window.widthPercentPref();
        lyricsHeightPx = window.bodyHeightFromPrefs();
        setBodyHeight(lyricsHeightPx);

        theme.install(overlayView);
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
        compactHeader = sharedPrefs.getBoolean(OverlayPrefKeys.HEADER_COMPACT, false);
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
        if (overlayAiButton != null) {
            overlayAiButton.setOnClickListener(v -> {
                Haptics.tick(v);
                if (chatWindows.isChatShown()) {
                    chatWindows.minimizeToBubble();
                } else {
                    chatWindows.showChat();
                }
            });
        }

        setLyricsVisible(lyricsVisible, false, false);

        // Until a media session reports in, say so rather than showing an empty box.
        overlayTitle.setText(R.string.no_music);
        overlayArtist.setText("");
        showIdleState();

        updateResizeHandleVisibility();
        renderLoop.setDisplayView(overlayView);

        if (!window.attach(overlayView, overlayBody, overlayResizeHandle)) {
            overlayView = null;
            return false;
        }

        // Bubble entrance: the overlay inflates from a blob with a rubbery overshoot.
        Anim.bubbleIn(overlayView);
        return true;
    }

    private void animateOutAndStop() {
        if (overlayView == null || window == null) {
            stopSelf();
            return;
        }
        window.animateOut(this::stopSelf);
    }

    /** Rotation, fold, split-screen: re-fit every window and restyle for the new size. */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (isDestroyed) return;
        if (window != null) window.onConfigurationChanged();
        if (chatWindows != null) chatWindows.onConfigurationChanged();
    }

    /** Window callbacks: resize gesture feedback and screen changes. */
    private final OverlayWindowController.Host windowHost = new OverlayWindowController.Host() {
        @Override
        public void onBodyResized(int bodyHeightPx) {
            lyricsHeightPx = bodyHeightPx;
        }

        @Override
        public void onResizeCommitted() {
            // The width%/height% prefs were written; the pref listener (debounced) restyles.
            // Apply now as well so the release lands without waiting for the debounce.
            applyRuntimeSettings(true);
        }

        @Override
        public void onScreenChanged() {
            applyRuntimeSettings(true);
        }
    };

    private void setBodyHeight(int heightPx) {
        if (overlayBody == null) return;
        ViewGroup.LayoutParams lp = overlayBody.getLayoutParams();
        if (lp.height == heightPx) return;
        lp.height = heightPx;
        overlayBody.setLayoutParams(lp);
    }

    // ── Colour ─────────────────────────────────────────────────────────────

    /** Album accent reaching the controls; called for every frame of a colour fade. */
    private void applyAccent(ColorStateList tint, int accent) {
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
            handler.postDelayed(this::updatePlayPauseIcon, config.playPauseRefreshDelayMs);
        });
        overlayPlayPause.setColorFilter(theme.accent());
    }

    private void updatePlayPauseIcon() {
        if (overlayPlayPause == null) return;
        boolean playing = sessionTracker != null && sessionTracker.isPlaying();
        overlayPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        overlayPlayPause.setColorFilter(theme.accent());
    }

    // ── Sizing ─────────────────────────────────────────────────────────────
    // Window and body size live in OverlayWindowController; the formulas in
    // OverlayGeometry. Screen metrics come from OverlayScreen so they follow rotation.

    private int dpToPx(int dp) {
        return screen.px(dp);
    }

    private float spToPx(float sp) {
        return OverlayGeometry.spPx(sp, screen.scaledDensity());
    }

    private float calculateFontSizeSp() {
        return OverlayGeometry.fontSizeSp(config, screen.widthDp(),
                sharedPrefs.getFloat(OverlayPrefKeys.FONT_SCALE, config.fontScaleDefault),
                sharedPrefs.getInt(OverlayPrefKeys.WIDTH_PERCENT, config.widthPercentDefault));
    }

    private boolean romajiPref() {
        return sharedPrefs.getBoolean(OverlayPrefKeys.ROMAJI_ENABLED, true);
    }

    private boolean originalScriptPref() {
        return sharedPrefs.getBoolean(OverlayPrefKeys.ORIGINAL_SCRIPT_ENABLED, true);
    }

    /** (Re)load the user's chosen typeface pair through the shared registry. */
    private void loadTypefaces() {
        AppFont.FontPair pair = AppFont.current(this);
        fontBold = pair.bold;
        fontMedium = pair.medium;
    }

    private void applyRuntimeSettings(boolean rebuildLyrics) {
        if (overlayView == null || overlayBody == null || lyricsContainer == null || window == null) return;

        float newFontSizeSp = calculateFontSizeSp();
        AnimConfig newAnimConfig = AnimSelection.current(new AndroidPrefs(sharedPrefs));
        Typeface prevBold = fontBold;
        loadTypefaces();
        boolean typefaceChanged = fontBold != prevBold;
        boolean styleChanged = newAnimConfig != lyricAnimConfig;
        lyricAnimConfig = newAnimConfig;

        boolean fontChanged = Math.abs(newFontSizeSp - overlayFontSizeSp) > config.fontRebuildThresholdSp;
        overlayFontSizeSp = newFontSizeSp;
        overlayWidthPercent = window.widthPercentPref();
        lyricsHeightPx = window.bodyHeightFromPrefs();

        // Never resize a body that is mid open/close animation; it lands on lyricsHeightPx.
        if (lyricsVisible && (bodyAnimator == null || !bodyAnimator.isRunning())) {
            setBodyHeight(lyricsHeightPx);
        }
        window.applyPrefWidth();

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
        float density = screen.density();
        float scaled = screen.scaledDensity();
        int coverSize = OverlayGeometry.coverSizePx(config, overlayFontSizeSp, overlayWidthPercent, density, scaled);
        ViewGroup.LayoutParams coverParams = overlayCover.getLayoutParams();
        if (coverParams.width != coverSize || coverParams.height != coverSize) {
            coverParams.width = coverSize;
            coverParams.height = coverSize;
            overlayCover.setLayoutParams(coverParams);
        }
        overlayTitle.setTextSize(OverlayGeometry.titleSp(config, overlayFontSizeSp));
        overlayArtist.setTextSize(OverlayGeometry.artistSp(config, overlayFontSizeSp));
    }

    private void applyResponsiveLyricsSpacing() {
        float density = screen.density();
        float scaled = screen.scaledDensity();
        int v = OverlayGeometry.lyricsPadVerticalPx(config, overlayFontSizeSp, density, scaled);
        int h = OverlayGeometry.lyricsPadHorizontalPx(config, overlayFontSizeSp, density, scaled);
        lyricsContainer.setPadding(h, v, h, v);
        // Scale the fade band with the body height: at the minimum body a fixed
        // band swallowed half the panel and the active line faded out.
        scrollView.setFadingEdgeLength(OverlayGeometry.fadeEdgePx(config, lyricsHeightPx, density));
    }

    private float calculateScrollPositionRatio() {
        return OverlayGeometry.scrollAnchorRatio(config, spToPx(overlayFontSizeSp), lyricsHeightPx,
                overlayWidthPercent, screen.density());
    }

    // ── Collapse / expand ──────────────────────────────────────────────────

    /**
     * Compact header: hide the toggle/settings/close buttons so long titles get the
     * full overlay width (two lines instead of an ellipsis). Tap the title block to
     * bring the buttons back. Persisted across restarts.
     */
    private void setHeaderCompact(boolean compact, boolean animate) {
        compactHeader = compact;
        sharedPrefs.edit().putBoolean(OverlayPrefKeys.HEADER_COMPACT, compact).apply();
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
            sharedPrefs.edit().putBoolean(OverlayPrefKeys.LYRICS_VISIBLE, visible).apply();
        }
        Anim.rotateTo(overlayToggle, visible ? 0f : config.toggleCollapsedRotationDeg);
        overlayToggle.setAlpha(visible ? 1.0f : config.toggleCollapsedAlpha);
        updateResizeHandleVisibility();

        if (animate) {
            animateBody(visible);
        } else {
            if (bodyAnimator != null) bodyAnimator.cancel();
            setBodyHeight(lyricsHeightPx);
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
        // A fast double-tap reverses from wherever the running animation is now.
        if (bodyAnimator != null) bodyAnimator.cancel();

        int current = overlayBody.getVisibility() == View.VISIBLE ? Math.max(0, overlayBody.getHeight()) : 0;
        int from = show ? (current < lyricsHeightPx ? current : 0) : Math.max(1, current);
        int to = show ? lyricsHeightPx : 0;
        if (show) {
            lp.height = from;
            overlayBody.setLayoutParams(lp);
            overlayBody.setVisibility(View.VISIBLE);
        }

        ValueAnimator anim = ValueAnimator.ofInt(from, to);
        anim.setDuration(Anim.D_MED);
        // Expanding gets a gentle rubber-band overshoot; collapsing stays smooth.
        anim.setInterpolator(show
                ? new android.view.animation.OvershootInterpolator(config.bodyExpandOvershoot)
                : Anim.STANDARD);
        anim.addUpdateListener(a -> {
            lp.height = (int) a.getAnimatedValue();
            overlayBody.setLayoutParams(lp);
        });
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (bodyAnimator == animation) bodyAnimator = null;
                if (cancelled) return; // the replacing animation owns the body now
                if (!show) overlayBody.setVisibility(View.GONE);
                lp.height = lyricsHeightPx;
                overlayBody.setLayoutParams(lp);
            }
        });
        overlayBody.animate().alpha(show ? 1f : 0f).setDuration(Anim.D_MED).start();
        bodyAnimator = anim;
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
        int saved = OverlayGeometry.syncOffset(config, sharedPrefs.getLong(OverlayPrefKeys.SYNC_OFFSET_MS, 0));
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
            Anim.rotateTo(overlaySettings, visible ? 0f : config.settingsOpenRotationDeg);
            updateResizeHandleVisibility(!visible);
        });

        // The slider spans [0, 2 × limit]; its midpoint is "no offset".
        int limit = config.syncOffsetLimitMs;
        overlaySyncOffsetSlider.setMax(limit * 2);
        SeekBars.bind(overlaySyncOffsetSlider, saved + limit, (progress, fromUser) -> {
            int offset = OverlayGeometry.syncOffset(config, progress - limit);
            updateSyncOffsetLabel(offset);
            sharedPrefs.edit().putLong(OverlayPrefKeys.SYNC_OFFSET_MS, offset).apply();
            if (sessionTracker != null) {
                sessionTracker.setSyncOffsetMs(offset);
            }
            if (fromUser) Haptics.tick(overlaySyncOffsetSlider);
        });
    }

    /** Keeps the overlay slider in step when the offset is changed from the activity. */
    private void syncOffsetSliderFromPrefs(long offset) {
        if (overlaySyncOffsetSlider == null) return;
        int clamped = OverlayGeometry.syncOffset(config, offset);
        int progress = clamped + config.syncOffsetLimitMs;
        if (overlaySyncOffsetSlider.getProgress() != progress) {
            overlaySyncOffsetSlider.setProgress(progress);
        }
        updateSyncOffsetLabel(clamped);
    }

    // ── AI chat content ────────────────────────────────────────────────────
    // The chat / bubble windows themselves (attach, size, drag, snap, rotation)
    // live in OverlayChatWindows; this section owns what is inside the chat.

    private final OverlayChatWindows.Host chatHost = new OverlayChatWindows.Host() {
        @Override
        public View createChatContent(FrameLayout container) {
            return initAiChatView(container);
        }

        @Override
        public void onChatShown() {
            onAiChatShown();
        }
    };

    private void onAiChatShown() {
        fetchQuotaIfAuthenticated();
        if (aiChatTitle != null) {
            aiChatTitle.setText(R.string.ai_insights_title);
        }
        if (currentTrack != null) {
            if (aiChatArtist != null) {
                aiChatArtist.setText(getString(R.string.overlay_track_line, currentTrack.title, currentTrack.artist));
            }
            if (songChatManager != null) {
                songChatManager.setAppLanguage(LocaleHelper.getLanguage(this));
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

    private View initAiChatView(FrameLayout container) {
        View aiChatView = LayoutInflater.from(container.getContext())
                .inflate(R.layout.overlay_ai_chat, container, false);
        container.addView(aiChatView);

        aiChatTitle = aiChatView.findViewById(R.id.ai_chat_title);
        aiChatArtist = aiChatView.findViewById(R.id.ai_chat_artist);
        ImageView aiMinimizeButton = aiChatView.findViewById(R.id.ai_minimize_button);
        ImageView aiCloseButton = aiChatView.findViewById(R.id.ai_close_button);
        aiWebSearchChip = aiChatView.findViewById(R.id.ai_web_search_chip);
        aiChatEmptyState = aiChatView.findViewById(R.id.ai_chat_empty_state);
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
                chatWindows.minimizeToBubble();
            });
        }
        if (aiCloseButton != null) {
            aiCloseButton.setOnClickListener(v -> {
                Haptics.tick(v);
                chatWindows.dismiss();
            });
        }
        View aiBtnNewChat = aiChatView.findViewById(R.id.ai_btn_new_chat);
        if (aiBtnNewChat != null) {
            aiBtnNewChat.setOnClickListener(v -> {
                Haptics.tick(v);
                chatHistoryController.handleAiNewChatOverlay();
            });
        }
        View aiBtnHistory = aiChatView.findViewById(R.id.ai_btn_history);
        if (aiBtnHistory != null) {
            aiBtnHistory.setOnClickListener(v -> {
                Haptics.tick(v);
                chatHistoryController.showAiHistoryDialogOverlay();
            });
        }
        if (aiWebSearchChip != null) {
            aiWebSearchChip.setOnClickListener(v -> {
                Haptics.tick(v);
                aiWebSearchEnabled = !aiWebSearchEnabled;
                aiWebSearchChip.setAlpha(aiWebSearchEnabled ? 1.0f : config.chatWebSearchOffAlpha);
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
        return aiChatView;
    }

    private void fetchQuotaIfAuthenticated() {
        if (quotaClient == null) return;
        quotaClient.fetchQuota(new AntigravityQuotaClient.QuotaCallback() {
            @Override
            public void onQuotaLoaded(QuotaSummary summary) {
                handler.post(() -> {
                    if (aiQuotaBadge != null && summary != null) {
                        int pct = Math.round(summary.geminiWeeklyFraction * 100f);
                        aiQuotaBadge.setText(getString(R.string.overlay_ai_quota, pct));
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

    private void displayWebSources(List<String> sources) {
        if (aiWebSourcesContainer == null || aiWebSourcesScroll == null) return;
        aiWebSourcesContainer.removeAllViews();
        if (sources == null || sources.isEmpty()) {
            aiWebSourcesScroll.setVisibility(View.GONE);
            return;
        }
        aiWebSourcesScroll.setVisibility(View.VISIBLE);

        TextView label = new TextView(this);
        label.setText(getString(R.string.overlay_web_sources_prefix, getString(R.string.ai_web_sources_label)));
        label.setTextColor(ContextCompat.getColor(this, R.color.text_dim));
        label.setTextSize(config.chatChipTextSp);
        label.setPadding(0, 0, dpToPx(config.chatWebLabelGapDp), 0);
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
            chip.setTextColor(ContextCompat.getColor(this, R.color.accent));
            chip.setTextSize(config.chatChipTextSp);
            chip.setBackgroundResource(R.drawable.bg_tip_box);
            chip.setPadding(dpToPx(config.chatWebChipPadHorizontalDp), dpToPx(config.chatWebChipPadVerticalDp),
                    dpToPx(config.chatWebChipPadHorizontalDp), dpToPx(config.chatWebChipPadVerticalDp));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dpToPx(config.chatWebChipGapDp));
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
        if (currentLyrics != null && isLyricsNonLatin(currentLyrics)) {
            // Option 1: Transliterasi & Arti
            aiPromptTemplatesContainer.addView(createPromptTemplateView(
                    getText(R.string.ai_chip_transliterate_with_translation),
                    getText(R.string.ai_chip_transliterate_with_translation_desc),
                    R.drawable.ic_sparkles,
                    v -> { Haptics.tick(v); performTransliteration(TransliterationRequest.WITH_TRANSLATION, null); }));

            // Option 2: Transliterasi Saja
            aiPromptTemplatesContainer.addView(createPromptTemplateView(
                    getText(R.string.ai_chip_transliterate_only),
                    getText(R.string.ai_chip_transliterate_only_desc),
                    R.drawable.ic_sparkles,
                    v -> { Haptics.tick(v); performTransliteration(TransliterationRequest.WITH_ORIGINAL, null); }));

            // Option 3: Latin Saja (Tanpa Huruf Asli)
            aiPromptTemplatesContainer.addView(createPromptTemplateView(
                    getText(R.string.ai_chip_transliterate_pure_romaji),
                    getText(R.string.ai_chip_transliterate_pure_romaji_desc),
                    R.drawable.ic_sparkles,
                    v -> { Haptics.tick(v); performTransliteration(TransliterationRequest.LATIN_ONLY, null); }));
        }
        String lang = LocaleHelper.getLanguage(this);
        List<SongChatManager.PromptTemplate> templates = SongChatManager.getPromptTemplates(lang);
        for (final SongChatManager.PromptTemplate template : templates) {
            int iconRes = getResources().getIdentifier(template.iconName, "drawable", getPackageName());
            if (iconRes == 0) iconRes = R.drawable.ic_sparkles;
            aiPromptTemplatesContainer.addView(createPromptTemplateView(
                    template.title, template.description, iconRes,
                    v -> { Haptics.tick(v); sendAiChatMessage(template.promptText); }));
        }
    }

    /** Builds one prompt-template row: title, description, icon and click handler. */
    private View createPromptTemplateView(CharSequence title, CharSequence desc, int iconRes, View.OnClickListener onClick) {
        View item = LayoutInflater.from(this).inflate(R.layout.item_prompt_template, aiPromptTemplatesContainer, false);
        ((TextView) item.findViewById(R.id.prompt_title)).setText(title);
        ((TextView) item.findViewById(R.id.prompt_desc)).setText(desc);
        ((ImageView) item.findViewById(R.id.prompt_icon)).setImageResource(iconRes);
        item.setOnClickListener(onClick);
        return item;
    }

    private boolean isLyricsNonLatin(LyricsData lyrics) {
        if (lyrics == null || lyrics.lines == null || lyrics.lines.isEmpty()) return false;
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (LyricsData.LyricsLine line : lyrics.lines) {
            if (line != null && !line.isInterlude && line.text != null) {
                sb.append(line.text).append(" ");
                count++;
                if (count >= config.scriptSampleLines) break;
            }
        }
        return ScriptDetector.isNonLatin(sb.toString());
    }
    private void performTransliteration() {
        performTransliteration(TransliterationRequest.WITH_TRANSLATION, null);
    }

    /**
     * Shows the Latin line as {@code req} asks — with/without the original script and the
     * translation. Runs the AI transliteration first when the lyrics have none yet.
     */
    private void performTransliteration(final TransliterationRequest req,
                                        final LyricActionDelegate.ActionCallback actionCallback) {
        if (currentLyrics == null || aiTransliterationService == null || currentTrack == null) {
            if (actionCallback != null) actionCallback.onError("Lirik belum dimuat");
            return;
        }
        if (activeTransliterationCall != null) {
            activeTransliterationCall.cancel();
            activeTransliterationCall = null;
        }
        if (currentLyrics.hasTransliteration) {
            applyTransliterationDisplay(req);
            Toast.makeText(this, transliterationLabel(req), Toast.LENGTH_SHORT).show();
            if (actionCallback != null) actionCallback.onSuccess("Tampilan diterapkan: " + req.describe() + ".");
            return;
        }

        final String fetchKey = pendingFetchKey;
        final TrackInfo track = currentTrack;
        Toast.makeText(this, R.string.ai_transliterating, Toast.LENGTH_SHORT).show();
        activeTransliterationCall = aiTransliterationService.transliterateSyllablesAsync(
                currentLyrics,
                track.title != null ? track.title : "",
                track.artist != null ? track.artist : "",
                null, // the song is translated separately, with full context
                new AiSyllableTransliterationService.Callback() {
                    @Override
                    public void onSuccess(LyricsData enrichedData) {
                        activeTransliterationCall = null;
                        // Null-safe: pendingFetchKey is null when no track fetch is in flight.
                        if (isDestroyed || !java.util.Objects.equals(fetchKey, pendingFetchKey)) {
                            if (actionCallback != null) actionCallback.onError("Konteks lagu telah kedaluwarsa");
                            return;
                        }
                        currentLyrics = enrichedData;
                        if (lyricsCacheManager != null) lyricsCacheManager.cache(track, enrichedData);
                        renderOverlayLyrics(currentLyrics);
                        updateSongChatLyrics(currentLyrics);
                        applyTransliterationDisplay(req);
                        Toast.makeText(FloatingOverlayService.this,
                                transliterationLabel(req), Toast.LENGTH_SHORT).show();
                        if (actionCallback != null) {
                            actionCallback.onSuccess("Transliterasi selesai, tampilan: " + req.describe() + ".");
                        }
                    }

                    @Override
                    public void onError(String message) {
                        activeTransliterationCall = null;
                        Toast.makeText(FloatingOverlayService.this,
                                message != null ? message : "Transliteration failed", Toast.LENGTH_SHORT).show();
                        if (actionCallback != null) actionCallback.onError("Transliterasi gagal: " + message);
                    }
                });
    }

    /** Latin line on; original script and translation set as requested (null = unchanged). */
    private void applyTransliterationDisplay(TransliterationRequest req) {
        SharedPreferences.Editor e = sharedPrefs.edit().putBoolean(OverlayPrefKeys.ROMAJI_ENABLED, true);
        if (highlighter != null) highlighter.setTransliterationEnabled(true);
        if (req.showOriginal != null) {
            e.putBoolean(OverlayPrefKeys.ORIGINAL_SCRIPT_ENABLED, req.showOriginal);
            if (highlighter != null) highlighter.setOriginalScriptEnabled(req.showOriginal);
        }
        if (req.showTranslation != null) {
            boolean on = req.showTranslation;
            translationEnabled = on;
            e.putBoolean(GoogleTranslateService.PREF_TRANSLATION_ENABLED, on);
            if (highlighter != null) highlighter.setTranslationEnabled(on);
            // The switch listener fetches the translation when this turns it on.
            if (overlayTranslateSwitch != null) overlayTranslateSwitch.setChecked(on);
            else if (on && currentLyrics != null && currentLyrics.translationLang == null) fetchTranslationForCurrentLyrics();
        }
        e.apply();
        if (overlayRomajiSwitch != null) {
            isProgrammaticRomajiUpdate = true;
            overlayRomajiSwitch.setChecked(true);
            isProgrammaticRomajiUpdate = false;
        }
        updateTransliterationToggleUi();
    }

    /** "Lirik ditampilkan: Latin + terjemahan", in the UI language. */
    private String transliterationLabel(TransliterationRequest req) {
        String what;
        if (Boolean.FALSE.equals(req.showOriginal) && !Boolean.TRUE.equals(req.showTranslation)) {
            what = getString(R.string.translit_latin_only);
        } else {
            what = getString(R.string.translit_latin);
            if (!Boolean.FALSE.equals(req.showOriginal)) what += getString(R.string.translit_plus_original);
            if (Boolean.TRUE.equals(req.showTranslation)) what += getString(R.string.translit_plus_translation);
        }
        return getString(R.string.ai_transliteration_applied, what);
    }


    private void updateTransliterationToggleUi() {
        if (overlayRomajiSwitch == null) return;
        boolean isNonLatin = isLyricsNonLatin(currentLyrics);
        if (romajiRow != null) {
            romajiRow.setVisibility(isNonLatin ? View.VISIBLE : View.GONE);
        }
        if (!isNonLatin) return;

        boolean enabled = highlighter != null ? highlighter.isTransliterationEnabled()
                : romajiPref();
        isProgrammaticRomajiUpdate = true;
        overlayRomajiSwitch.setChecked(enabled);
        isProgrammaticRomajiUpdate = false;

        if (overlayTransliterationLabel != null) {
            overlayTransliterationLabel.setText(R.string.toggle_latin_transliteration);
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

        activeTransliterationCall = aiTransliterationService.transliterateSyllablesAsync(
                targetLyrics, title, artist, null,
                new AiSyllableTransliterationService.Callback() {
                    @Override
                    public void onSuccess(LyricsData enrichedData) {
                        activeTransliterationCall = null;
                        // Null-safe guard: see performTransliteration.
                        if (isDestroyed || !java.util.Objects.equals(fetchKey, pendingFetchKey)) return;
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


    // ---------------------------------------------------------------- AI harness (LyricActionDelegate)
    // The router posts every call here onto the main thread. Results are written for the model.

    @Override
    public void getState(LyricActionDelegate.ActionCallback cb) {
        cb.onSuccess(appState().toString());
    }

    /** Track, playback, lyrics and display state; also attached to every chat message. */
    private JsonObject appState() {
        JsonObject o = new JsonObject();
        JsonObject track = new JsonObject();
        track.addProperty("title", currentTrack != null && currentTrack.title != null ? currentTrack.title : "");
        track.addProperty("artist", currentTrack != null && currentTrack.artist != null ? currentTrack.artist : "");
        if (currentTrack != null && currentTrack.duration > 0) track.addProperty("duration_ms", currentTrack.duration);
        o.add("track", track);

        JsonObject playback = new JsonObject();
        boolean player = sessionTracker != null && sessionTracker.hasController();
        playback.addProperty("player_connected", player);
        if (player) {
            long pos = sessionTracker.getCurrentPosition();
            playback.addProperty("playing", sessionTracker.isPlaying());
            playback.addProperty("position_ms", pos);
            int active = currentLyrics != null && lyricsContainer != null ? findActiveLine(pos) : -1;
            if (active >= 0) playback.addProperty("active_line", active);
        }
        o.add("playback", playback);

        JsonObject lyrics = new JsonObject();
        lyrics.addProperty("loaded", currentLyrics != null && !currentLyrics.isEmpty());
        if (currentLyrics != null && currentLyrics.lines != null) {
            int withRomaji = 0, withTranslation = 0, withWordRomaji = 0;
            for (LyricsData.LyricsLine l : currentLyrics.lines) {
                if (l.romanji != null && !l.romanji.trim().isEmpty()) withRomaji++;
                if (l.translation != null && !l.translation.trim().isEmpty()) withTranslation++;
                if (highlighter != null && highlighter.hasAnyWordRomanji(l)) withWordRomaji++;
            }
            lyrics.addProperty("type", currentLyrics.type != null ? currentLyrics.type.name() : "");
            lyrics.addProperty("provider", currentLyrics.provider != null ? currentLyrics.provider : "");
            lyrics.addProperty("lines", currentLyrics.lines.size());
            lyrics.addProperty("non_latin_script", isLyricsNonLatin(currentLyrics));
            lyrics.addProperty("lines_with_romaji", withRomaji);
            lyrics.addProperty("lines_with_word_romaji", withWordRomaji);
            lyrics.addProperty("lines_with_translation", withTranslation);
            if (currentLyrics.translationLang != null) lyrics.addProperty("translation_language", currentLyrics.translationLang);
            lyrics.addProperty("undo_steps", lyricsEditor.undoSteps(currentLyrics));
        }
        o.add("lyrics", lyrics);

        JsonObject display = new JsonObject();
        display.addProperty("translation", translationEnabled);
        display.addProperty("romaji", romajiPref());
        display.addProperty("original_script", originalScriptPref());
        display.addProperty("font_scale", sharedPrefs.getFloat(OverlayPrefKeys.FONT_SCALE, config.fontScaleDefault));
        display.addProperty("width_percent", sharedPrefs.getInt(OverlayPrefKeys.WIDTH_PERCENT, config.widthPercentDefault));
        display.addProperty("height_percent", sharedPrefs.getInt(OverlayPrefKeys.HEIGHT_PERCENT, config.heightPercentDefault));
        display.addProperty("sync_offset_ms", OverlayGeometry.syncOffset(config, sharedPrefs.getLong(OverlayPrefKeys.SYNC_OFFSET_MS, 0)));
        AndroidPrefs animPrefs = new AndroidPrefs(sharedPrefs);
        display.addProperty("motion", AnimSelection.motionKey(animPrefs));
        display.addProperty("effect", AnimSelection.effectKey(animPrefs));
        display.addProperty("target_language", GoogleTranslateService.resolveTargetLang(
                sharedPrefs.getString(GoogleTranslateService.PREF_TARGET_LANG, GoogleTranslateService.DEFAULT_TARGET_LANG),
                java.util.Locale.getDefault()));
        o.add("display", display);
        return o;
    }

    @Override
    public void readLyrics(int from, int to, boolean words, LyricActionDelegate.ActionCallback cb) {
        if (currentLyrics == null || currentLyrics.isEmpty()) {
            cb.onError("No lyrics are loaded.");
            return;
        }
        cb.onSuccess(LyricsEditor.read(currentLyrics, from, to, words).toString());
    }

    @Override
    public void editLyrics(JsonArray ops, LyricActionDelegate.ActionCallback cb) {
        LyricsEditor.Result r;
        try {
            r = lyricsEditor.apply(currentLyrics, ops);
            cancelBackgroundLyricJobs();
        } catch (IllegalArgumentException e) {
            cb.onError(e.getMessage());
            return;
        }
        // Mark what the AI wrote as this song's translation/reading, so the re-render
        // does not auto-fetch over it.
        if (r.wroteTranslation) {
            currentLyrics.translationLang = GoogleTranslateService.resolveTargetLang(sharedPrefs.getString(
                    GoogleTranslateService.PREF_TARGET_LANG, GoogleTranslateService.DEFAULT_TARGET_LANG),
                    java.util.Locale.getDefault());
            setTranslationVisible(true);
        }
        if (r.wroteRomaji) {
            currentLyrics.hasTransliteration = true;
            setRomajiVisible(true);
        }
        renderLyricsEdit();
        cb.onSuccess(r.summary);
    }

    @Override
    public void undoEdit(boolean all, LyricActionDelegate.ActionCallback cb) {
        String msg;
        try {
            msg = lyricsEditor.undo(currentLyrics, all);
            cancelBackgroundLyricJobs();
        } catch (IllegalStateException e) {
            cb.onError(e.getMessage());
            return;
        }
        renderLyricsEdit();
        cb.onSuccess(msg);
    }

    @Override
    public void translateLyrics(String targetLang, LyricActionDelegate.ActionCallback cb) {
        if (currentLyrics == null || currentLyrics.isEmpty()) {
            cb.onError("No lyrics are loaded.");
            return;
        }
        String lang = targetLang != null && !targetLang.trim().isEmpty() ? targetLang.trim() : "id";
        sharedPrefs.edit().putString(GoogleTranslateService.PREF_TARGET_LANG, lang).apply();
        setTranslationVisible(true);
        fetchTranslationForCurrentLyrics(cb);
    }

    @Override
    public void transliterateLyrics(String mode, boolean showOriginalScript, LyricActionDelegate.ActionCallback cb) {
        if (currentLyrics == null || currentLyrics.isEmpty()) {
            cb.onError("No lyrics are loaded.");
            return;
        }
        performTransliteration(TransliterationRequest.fromTool(mode, showOriginalScript), cb);
    }

    @Override
    public void setDisplay(LyricActionDelegate.Display d, LyricActionDelegate.ActionCallback cb) {
        List<String> done = new ArrayList<>();
        if (d.motion != null && !hasStyle(AnimStyleDef.Registry.motionStyles(), d.motion)
                || d.effect != null && !hasStyle(AnimStyleDef.Registry.effectStyles(), d.effect)) {
            cb.onError("Unknown motion or effect; use one of the values listed in the tool schema.");
            return;
        }
        if (d.translation != null) {
            setTranslationVisible(d.translation);
            if (d.translation && !hasAnyTranslation(currentLyrics)) fetchTranslationForCurrentLyrics();
            done.add("translation " + onOff(d.translation));
        }
        if (d.romaji != null) {
            setRomajiVisible(d.romaji);
            done.add("romaji " + onOff(d.romaji));
        }
        if (d.originalScript != null) {
            sharedPrefs.edit().putBoolean(OverlayPrefKeys.ORIGINAL_SCRIPT_ENABLED, d.originalScript).apply();
            if (highlighter != null) highlighter.setOriginalScriptEnabled(d.originalScript);
            done.add("original script " + onOff(d.originalScript));
        }
        if (d.syncOffsetMs != null) {
            int ms = OverlayGeometry.syncOffset(config, d.syncOffsetMs);
            sharedPrefs.edit().putLong(OverlayPrefKeys.SYNC_OFFSET_MS, ms).apply();
            if (sessionTracker != null) sessionTracker.setSyncOffsetMs(ms);
            updateSyncOffsetLabel(ms);
            syncOffsetSliderFromPrefs(ms);
            done.add("sync offset " + ms + " ms");
        }
        // Size and animation prefs are applied by settingsListener (one rebuild for all).
        SharedPreferences.Editor e = sharedPrefs.edit();
        if (d.fontScale != null) {
            float v = OverlayGeometry.fontScale(config, d.fontScale);
            e.putFloat(OverlayPrefKeys.FONT_SCALE, v);
            done.add("font scale " + v);
        }
        if (d.widthPercent != null) {
            int v = OverlayGeometry.widthPercent(config, d.widthPercent);
            e.putInt(OverlayPrefKeys.WIDTH_PERCENT, v);
            done.add("width " + v + "%");
        }
        if (d.heightPercent != null) {
            int v = OverlayGeometry.heightPercent(config, d.heightPercent);
            e.putInt(OverlayPrefKeys.HEIGHT_PERCENT, v);
            done.add("height " + v + "%");
        }
        e.apply();
        if (d.motion != null || d.effect != null) {
            AndroidPrefs animPrefs = new AndroidPrefs(sharedPrefs);
            String motion = d.motion != null ? d.motion : AnimSelection.motionKey(animPrefs);
            String effect = d.effect != null ? d.effect : AnimSelection.effectKey(animPrefs);
            AnimSelection.save(animPrefs, motion, effect);
            done.add("animation " + motion + " + " + effect);
        }
        cb.onSuccess("Display updated: " + String.join(", ", done) + ".");
    }

    /**
     * An edit swaps in new line objects; a transliteration or translation still running
     * would write into the old ones, so it is dropped and the edit wins.
     */
    private void cancelBackgroundLyricJobs() {
        if (activeTransliterationCall != null) {
            activeTransliterationCall.cancel();
            activeTransliterationCall = null;
        }
        if (aiTranslationService != null) aiTranslationService.cancel();
    }

    private static boolean hasStyle(List<AnimStyleDef> styles, String key) {
        for (AnimStyleDef s : styles) if (s.key.equals(key)) return true;
        return false;
    }

    private static String onOff(boolean on) {
        return on ? "on" : "off";
    }

    private static boolean hasAnyTranslation(LyricsData lyrics) {
        if (lyrics == null || lyrics.lines == null) return false;
        for (LyricsData.LyricsLine l : lyrics.lines) {
            if (l.translation != null && !l.translation.trim().isEmpty()) return true;
        }
        return false;
    }

    /** Translation layer on/off, kept in sync with its pref and switch (without the switch's auto-fetch). */
    private void setTranslationVisible(boolean on) {
        translationEnabled = on;
        sharedPrefs.edit().putBoolean(GoogleTranslateService.PREF_TRANSLATION_ENABLED, on).apply();
        if (highlighter != null) highlighter.setTranslationEnabled(on);
        if (overlayTranslateSwitch != null) {
            isProgrammaticTranslateUpdate = true;
            overlayTranslateSwitch.setChecked(on);
            isProgrammaticTranslateUpdate = false;
        }
    }

    /** Latin layer on/off, kept in sync with its pref and switch. */
    private void setRomajiVisible(boolean on) {
        sharedPrefs.edit().putBoolean(OverlayPrefKeys.ROMAJI_ENABLED, on).apply();
        if (highlighter != null) highlighter.setTransliterationEnabled(on);
        if (overlayRomajiSwitch != null) {
            isProgrammaticRomajiUpdate = true;
            overlayRomajiSwitch.setChecked(on);
            isProgrammaticRomajiUpdate = false;
        }
    }

    @Override
    public void controlPlayback(String action, int lineIndex, long positionMs, LyricActionDelegate.ActionCallback cb) {
        if (sessionTracker == null || !sessionTracker.hasController()) {
            cb.onError("No music player is connected.");
            return;
        }
        String act = action != null ? action.trim().toLowerCase(java.util.Locale.ROOT) : "";
        switch (act) {
            case "seek_to_line":
                if (currentLyrics == null || currentLyrics.lines == null
                        || lineIndex < 0 || lineIndex >= currentLyrics.lines.size()) {
                    cb.onError("line_index " + lineIndex + " is out of range.");
                    return;
                }
                sessionTracker.seekTo(currentLyrics.lines.get(lineIndex).startTime);
                cb.onSuccess("Jumped to line " + lineIndex + ".");
                return;
            case "seek_to_position":
                sessionTracker.seekTo(Math.max(0, positionMs));
                cb.onSuccess("Jumped to " + Math.max(0, positionMs) + " ms.");
                return;
            case "play":
                sessionTracker.play();
                break;
            case "pause":
                sessionTracker.pause();
                break;
            case "skip_next":
                sessionTracker.skipNext();
                break;
            case "skip_previous":
                sessionTracker.skipPrevious();
                break;
            default:
                cb.onError("Unknown action: " + action);
                return;
        }
        cb.onSuccess("Done: " + act + ".");
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

        // "romaji saja", "latin tanpa huruf asli", "romaji dengan arti": a display command,
        // applied here directly. Sending it to the model as well could undo it with a
        // different tool call, so the model is not asked.
        TransliterationRequest displayCmd = isLyricsNonLatin(currentLyrics)
                ? TransliterationRequest.fromChat(userQuery) : null;
        if (displayCmd != null) {
            if (aiChatEmptyState != null) aiChatEmptyState.setVisibility(View.GONE);
            if (aiChatScroll != null) aiChatScroll.setVisibility(View.VISIBLE);
            addUserChatBubble(userQuery);
            showInlineToolStatus("transliterate_lyrics", displayCmd.describe());
            performTransliteration(displayCmd, new LyricActionDelegate.ActionCallback() {
                @Override
                public void onSuccess(String message) {
                    completeInlineToolStatus("transliterate_lyrics", 1, false);
                    addAiChatBubble(transliterationLabel(displayCmd));
                }

                @Override
                public void onError(String error) {
                    completeInlineToolStatus("transliterate_lyrics", 0, true);
                    addAiChatBubble(error);
                }
            });
            return;
        }

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
        activeAiBubbleAnimator = new AiStreamingAnimator(aiBubble, ContextCompat.getColor(this, R.color.accent));
        activeAiBubbleAnimator.startThinking(getString(R.string.ai_thinking));

        if (activeChatCall != null) {
            activeChatCall.cancel();
            activeChatCall = null;
        }

        String currentModel = sharedPrefs.getString(OverlayPrefKeys.AI_MODEL, SongChatManager.DEFAULT_MODEL);
        songChatManager.setModelId(currentModel);
        songChatManager.setAppState(appState().toString());

        final StringBuilder responseAcc = new StringBuilder();
        activeChatCall = songChatManager.sendMessage(userQuery, aiWebSearchEnabled,
                new AntigravityApiClient.StreamListener() {
                    @Override
                    public void onToolExecuting(String toolName, String queryOrUrl) {
                        handler.post(() -> {
                            if (isDestroyed) return;
                            showInlineToolStatus(toolName, queryOrUrl);
                            if (aiToolStatusContainer != null) {
                                cancelPendingToolStatusHide();
                                CleanMarkdownFormatter.ToolDisplayInfo info =
                                        CleanMarkdownFormatter.formatTool(toolName, queryOrUrl, 0, FloatingOverlayService.this);
                                if (aiToolStatusIcon != null) aiToolStatusIcon.setImageResource(info.iconRes);
                                if (aiToolStatusText != null) aiToolStatusText.setText(info.runningText);
                                aiToolStatusContainer.setAlpha(0f);
                                aiToolStatusContainer.setVisibility(View.VISIBLE);
                                aiToolStatusContainer.animate().alpha(1f).setDuration(config.toolStatusFadeMs).start();
                            }
                        });
                    }

                    @Override
                    public void onToolCompleted(String toolName, int resultsCount, boolean isError) {
                        handler.post(() -> {
                            if (isDestroyed) return;
                            completeInlineToolStatus(toolName, resultsCount, isError);
                            if (aiToolStatusContainer != null) {
                                if (aiToolStatusIcon != null) {
                                    aiToolStatusIcon.setImageResource(isError ? R.drawable.ic_alert : R.drawable.ic_check);
                                }
                                CleanMarkdownFormatter.ToolDisplayInfo info =
                                        CleanMarkdownFormatter.formatTool(toolName, "", resultsCount, FloatingOverlayService.this);
                                if (aiToolStatusText != null) {
                                    aiToolStatusText.setText(isError ? getString(R.string.ai_tool_failed) : info.doneText);
                                }
                                scheduleToolStatusHide();
                            }
                        });
                    }

                    @Override
                    public void onDelta(String textDelta) {
                        handler.post(() -> {
                            responseAcc.append(textDelta);
                            if (activeAiBubbleAnimator != null) {
                                activeAiBubbleAnimator.updateStream(responseAcc.toString());
                            }
                            followAiChatIfAtBottom();
                        });
                    }

                    @Override
                    public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                        handler.post(() -> {
                            activeChatCall = null;
                            scheduleToolStatusHide();
                            String finalContent = (fullText != null && !fullText.trim().isEmpty())
                                    ? fullText : responseAcc.toString();
                            if (finalContent == null || finalContent.trim().isEmpty()) {
                                finalContent = getString(R.string.ai_tool_generic_done);
                            }
                            if (activeAiBubbleAnimator != null) {
                                activeAiBubbleAnimator.complete(finalContent);
                                activeAiBubbleAnimator = null;
                            }
                            if (webSources != null && !webSources.isEmpty()) {
                                displayWebSources(webSources);
                            }
                            addSourceChipsBelowBubble(webSources);
                            scrollAiChatToBottom();
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
        handler.postDelayed(pendingToolStatusHide, config.toolStatusHideDelayMs);
    }

    void hideToolStatusNow() {
        cancelPendingToolStatusHide();
        if (aiToolStatusContainer != null) {
            aiToolStatusContainer.setVisibility(View.GONE);
        }
    }

    /** Clickable web-source chips appended below the last AI chat bubble (max 5). */
    void addSourceChipsBelowBubble(List<String> webSources) {
        if (aiChatMessages == null || webSources == null) return;
        int added = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String source : webSources) {
            if (added >= config.chatSourceChipMax) break;
            CleanMarkdownFormatter.SourceChipData chipData = CleanMarkdownFormatter.resolveSourceChip(source, this);
            if (chipData == null) continue;
            String key = chipData.displayLabel.toLowerCase(java.util.Locale.ROOT);
            if (seen.contains(key)) continue;
            seen.add(key);

            final String url = chipData.targetUrl;

            TextView chip = new TextView(this);
            chip.setText(chipData.displayLabel);
            chip.setTextColor(ContextCompat.getColor(this, R.color.accent));
            chip.setTextSize(config.chatChipTextSp);
            chip.setBackgroundResource(R.drawable.bg_prompt_card);
            chip.setPadding(dpToPx(config.chatSourceChipPadHorizontalDp), dpToPx(config.chatSourceChipPadVerticalDp),
                    dpToPx(config.chatSourceChipPadHorizontalDp), dpToPx(config.chatSourceChipPadVerticalDp));
            chip.setMaxLines(1);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.START;
            lp.setMargins(0, dpToPx(config.chatSourceChipGapDp), dpToPx(config.chatBubbleInsetDp),
                    dpToPx(config.chatSourceChipGapDp));
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

    private TextView activeOverlayToolCard = null;

    private void showInlineToolStatus(String toolName, String queryOrUrl) {
        if (aiChatMessages == null) return;
        CleanMarkdownFormatter.ToolDisplayInfo info =
                CleanMarkdownFormatter.formatTool(toolName, queryOrUrl, 0, this);

        int accent = ContextCompat.getColor(this, R.color.accent);
        TextView card = new TextView(this);
        card.setText(info.runningText);
        card.setTextSize(config.chatToolTextSp);
        card.setTextColor(accent);
        card.setBackgroundResource(R.drawable.bg_tool_status_pill);
        card.setPadding(dpToPx(config.chatToolPadHorizontalDp), dpToPx(config.chatToolPadVerticalDp),
                dpToPx(config.chatToolPadHorizontalDp), dpToPx(config.chatToolPadVerticalDp));
        setToolCardIcon(card, info.iconRes != 0 ? info.iconRes : R.drawable.ic_bolt, accent);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.START;
        lp.setMargins(0, dpToPx(config.chatToolGapDp), dpToPx(config.chatBubbleInsetDp), dpToPx(config.chatToolGapDp));
        card.setLayoutParams(lp);

        int count = aiChatMessages.getChildCount();
        int insertPos = Math.max(0, count - 1);
        aiChatMessages.addView(card, insertPos);
        activeOverlayToolCard = card;

        scrollAiChatToBottom();
    }

    private void completeInlineToolStatus(String toolName, int resultsCount, boolean isError) {
        if (activeOverlayToolCard != null) {
            CleanMarkdownFormatter.ToolDisplayInfo info =
                    CleanMarkdownFormatter.formatTool(toolName, "", resultsCount, this);
            int color = isError ? ContextCompat.getColor(this, R.color.state_error) : config.chatToolDoneColor;
            setToolCardIcon(activeOverlayToolCard, isError ? R.drawable.ic_alert : R.drawable.ic_check, color);
            activeOverlayToolCard.setText(isError ? getString(R.string.ai_tool_failed) : info.doneText);
            activeOverlayToolCard.setTextColor(color);
            activeOverlayToolCard = null;
        }
    }

    private void setToolCardIcon(TextView card, int iconRes, int tint) {
        android.graphics.drawable.Drawable icon = ContextCompat.getDrawable(this, iconRes);
        if (icon == null) return;
        icon = icon.mutate();
        int size = dpToPx(config.chatToolIconDp);
        icon.setBounds(0, 0, size, size);
        icon.setTint(tint);
        card.setCompoundDrawables(icon, null, null, null);
        card.setCompoundDrawablePadding(dpToPx(config.chatToolIconGapDp));
    }

    void addUserChatBubble(String text) {
        if (aiChatMessages == null) return;
        TextView bubble = new TextView(this);
        CleanMarkdownFormatter.applyMarkdown(bubble, text, false);
        bubble.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        bubble.setTextSize(config.chatTextSp);
        bubble.setBackgroundResource(R.drawable.bg_chat_bubble_user);
        bubble.setPadding(dpToPx(config.chatUserPadHorizontalDp), dpToPx(config.chatUserPadVerticalDp),
                dpToPx(config.chatUserPadHorizontalDp), dpToPx(config.chatUserPadVerticalDp));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.setMargins(dpToPx(config.chatBubbleInsetDp), dpToPx(config.chatMessageGapDp), 0,
                dpToPx(config.chatMessageGapDp));
        bubble.setLayoutParams(lp);

        aiChatMessages.addView(bubble);
        scrollAiChatToBottom();
    }

    TextView addAiChatBubble(String initialText) {
        if (aiChatMessages == null) return null;
        TextView bubble = new TextView(this);
        CleanMarkdownFormatter.applyMarkdown(bubble, initialText, false);
        bubble.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        bubble.setTextSize(config.chatTextSp);
        bubble.setLineSpacing(0f, config.chatAiLineSpacing);
        bubble.setPadding(dpToPx(config.chatAiPadHorizontalDp), 0, dpToPx(config.chatAiPadHorizontalDp), 0);
        bubble.setMovementMethod(io.noties.markwon.ext.tables.TableAwareMovementMethod.create());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dpToPx(config.chatAiTopGapDp), 0, dpToPx(config.chatAiBottomGapDp));
        bubble.setLayoutParams(lp);

        aiChatMessages.addView(bubble);
        scrollAiChatToBottom();
        return bubble;
    }

    /** Keeps a streaming reply in view, unless the user scrolled up to read something. */
    private void followAiChatIfAtBottom() {
        if (aiChatScroll == null || aiChatScroll.getChildCount() == 0) return;
        View content = aiChatScroll.getChildAt(0);
        int distance = content.getBottom() - (aiChatScroll.getScrollY() + aiChatScroll.getHeight());
        if (distance <= dpToPx(config.chatAutoFollowDp)) scrollAiChatToBottom();
    }

    void scrollAiChatToBottom() {
        if (aiChatScroll != null) {
            aiChatScroll.post(() -> aiChatScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    @SuppressLint("InflateParams") // AlertDialog supplies the parent via setView
    public void showEditSyllablesDialog(int lineIndex, LyricsData.LyricsLine line) {
        if (line == null) return;

        final Context themedContext = new ContextThemeWrapper(this, R.style.Theme_LyricSync);
        View dialogView = LayoutInflater.from(themedContext).inflate(R.layout.dialog_edit_syllables, null);
        AlertDialog dialog = new AlertDialog.Builder(themedContext)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
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
                String translateTo = AiTranslationService.languageName(GoogleTranslateService.resolveTargetLang(
                        sharedPrefs.getString(GoogleTranslateService.PREF_TARGET_LANG, GoogleTranslateService.DEFAULT_TARGET_LANG),
                        java.util.Locale.getDefault()));
                aiTransliterationService.transliterateSyllablesAsync(
                        singleData, title, artist, translateTo,
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
        overlaySyncOffsetLabel.setText(ms > 0
                ? getString(R.string.overlay_sync_offset_positive, ms)
                : getString(R.string.overlay_sync_offset_value, ms));
    }

    // ── Resize handle ──────────────────────────────────────────────────────
    // Drag / resize gestures live in OverlayWindowController.

    private void updateResizeHandleVisibility() {
        boolean settingsOpen = overlaySettingsPanel != null && overlaySettingsPanel.getVisibility() == View.VISIBLE;
        updateResizeHandleVisibility(settingsOpen);
    }

    private void updateResizeHandleVisibility(boolean settingsOpen) {
        if (overlayResizeContainer == null) return;
        boolean shouldShow = (showResizeHandle || settingsOpen) && lyricsVisible;
        overlayResizeContainer.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
    }

    // ── Tracking ───────────────────────────────────────────────────────────

    private void startTracker() {
        lyricsManager = new LyricsProviderManager(this);
        sessionTracker = new MediaSessionTracker(this);
        sessionTracker.setSyncOffsetMs(
                OverlayGeometry.syncOffset(config, sharedPrefs.getLong(OverlayPrefKeys.SYNC_OFFSET_MS, 0)));

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
                    // Position/state come from the tracker; this signal only wakes the
                    // render loop if it had gone idle. Values are re-read per frame.
                    if (isDestroyed) return;
                    renderLoop.wake();
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

    /** One render-loop frame: highlight + scroll + interlude dots. */
    private final OverlayRenderLoop.Frame renderFrame = new OverlayRenderLoop.Frame() {
        @Override
        public boolean isPlaying() {
            return sessionTracker != null && sessionTracker.isPlaying()
                    && currentLyrics != null && !lineViews.isEmpty();
        }

        @Override
        public boolean isSettled() {
            return springScroller == null || springScroller.isSettled();
        }

        @Override
        public void step(double dtSec) {
            if (isDestroyed) return;
            long position = sessionTracker != null ? sessionTracker.getCurrentPosition() : 0;
            updateHighlight(position, dtSec);
            if (highlighter != null) highlighter.animateInterlude(position, dtSec);
        }
    };

    private void startRenderLoop() {
        if (renderLoop != null) renderLoop.start();
    }

    /** Force frames even while paused (seek, edits) so the highlight follows at once. */
    private void wakeRenderLoop() {
        if (renderLoop != null) renderLoop.wake();
    }

    private void stopRenderLoop() {
        if (renderLoop != null) renderLoop.stop();
    }

    private void updateTrackInfo(TrackInfo track) {
        if (isDestroyed) return;
        clearCoverArtTarget();
        Anim.setTextAnimated(overlayTitle, track.title);
        Anim.setTextAnimated(overlayArtist, track.artist);

        if (track.albumArtBitmap != null) {
            pendingArtUri = null;
            Glide.with(this)
                    .load(track.albumArtBitmap)
                    .transition(DrawableTransitionOptions.withCrossFade(config.coverCrossfadeMs))
                    .transform(new RoundedCorners(dpToPx(config.coverCornerDp)))
                    .into(overlayCover);
            if (track.albumArtBitmap != lastPaletteArt) {
                lastPaletteArt = track.albumArtBitmap;
                lastPaletteArtUri = null;
                theme.fromArt(track.albumArtBitmap);
            }
        } else if (track.albumArtUri != null && !track.albumArtUri.trim().isEmpty()) {
            // Decode through a target (not .into(ImageView)) so the same bitmap can
            // feed both the cover view and the palette — previously URI art never
            // re-tinted the overlay at all.
            final String artUri = track.albumArtUri;
            pendingArtUri = artUri;
            glideRequestManager = Glide.with(this);
            com.bumptech.glide.request.target.CustomTarget<Bitmap> target =
                    new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                        @Override
                        public void onResourceReady(@androidx.annotation.NonNull Bitmap resource,
                                                    @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                            if (isDestroyed || !artUri.equals(pendingArtUri)) return;
                            overlayCover.setAlpha(0f);
                            overlayCover.setImageBitmap(resource);
                            overlayCover.animate().alpha(1f).setDuration(config.coverCrossfadeMs).start();
                            if (!artUri.equals(lastPaletteArtUri)) {
                                lastPaletteArtUri = artUri;
                                lastPaletteArt = null;
                                theme.fromArt(resource);
                            }
                        }

                        @Override
                        public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                        }
                    };
            glideRequestManager
                    .asBitmap()
                    .load(artUri)
                    .transform(new RoundedCorners(dpToPx(config.coverCornerDp)))
                    .into(target);
            coverArtTarget = target;
        } else {
            pendingArtUri = null;
            lastPaletteArtUri = null;
            Glide.with(this).clear(overlayCover);
            overlayCover.setImageDrawable(null);
        }
    }

    /**
     * Clears (through the owning RequestManager) any in-flight URI album-art Glide request so a
     * late callback cannot touch a torn-down view. Safe to call repeatedly and after destroy.
     */
    private void clearCoverArtTarget() {
        com.bumptech.glide.request.target.CustomTarget<Bitmap> target = coverArtTarget;
        if (target == null) return;
        coverArtTarget = null;
        com.bumptech.glide.RequestManager manager = glideRequestManager;
        if (manager != null) {
            manager.clear(target);
        } else {
            Glide.with(this).clear(target);
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
        setRetryState(STATE_LOADED);
        if (overlayEmpty != null && overlayEmpty.getVisibility() == View.VISIBLE) {
            overlayEmpty.animate().alpha(0f).setDuration(Anim.D_FAST).withEndAction(() -> {
                if (overlayEmpty != null) overlayEmpty.setVisibility(View.GONE);
            }).start();
        } else if (overlayEmpty != null) {
            overlayEmpty.setVisibility(View.GONE);
        }
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
        setRetryState(RETRY_STATE_IDLE);
        if (overlayRetryActionsContainer != null) {
            overlayRetryActionsContainer.setVisibility(titleRes == R.string.nothing_playing ? View.GONE : View.VISIBLE);
        }
        overlayEmpty.setVisibility(View.VISIBLE);
        overlayEmpty.setAlpha(0f);
        overlayEmpty.animate().alpha(1f).setDuration(Anim.D_MED).start();
    }

    private void startSkeletonShimmer() {
        if (skeletonAnimator != null) return;
        skeletonAnimator = ObjectAnimator.ofFloat(overlaySkeleton, View.ALPHA,
                config.skeletonAlphaLow, config.skeletonAlphaHigh);
        skeletonAnimator.setDuration(config.skeletonPulseMs);
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

    // ── Retry ────────────────────────────────────────────────

    private void setupRetryViews() {
        if (btnRetryStandard != null) {
            btnRetryStandard.setOnClickListener(v -> {
                Haptics.tick(v);
                if (currentTrack == null || !currentTrack.isValid()) return;
                setRetryState(RETRY_STATE_STANDARD);
                showLoadingState();
                fetchLyrics(currentTrack);
            });
        }
        setRetryState(RETRY_STATE_IDLE);
    }

    public void setRetryState(int state) {
        this.currentRetryState = state;
        if (overlayRetryActionsContainer == null) return;
        switch (state) {
            case RETRY_STATE_STANDARD:
                overlayRetryActionsContainer.setVisibility(View.VISIBLE);
                if (btnRetryStandard != null) btnRetryStandard.setEnabled(false);
                break;
            case STATE_LOADED:
                overlayRetryActionsContainer.setVisibility(View.GONE);
                break;
            case STATE_FAILED:
            case RETRY_STATE_IDLE:
            default:
                overlayRetryActionsContainer.setVisibility(View.VISIBLE);
                if (btnRetryStandard != null) btnRetryStandard.setEnabled(true);
                break;
        }
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
        if (activeChatCall != null) {
            activeChatCall.cancel();
            activeChatCall = null;
        }
        if (activeTransliterationCall != null) {
            activeTransliterationCall.cancel();
            activeTransliterationCall = null;
        }
        if (songChatManager != null && track != null) {
            songChatManager.setCurrentSong(track.title, track.artist, "");
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
            aiChatArtist.setText(getString(R.string.overlay_track_line, track.title, track.artist));
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
                // Quality guard: NEVER downgrade from SYLLABLE to LINE or STATIC!
                if (currentLyrics != null && currentLyrics.type == LyricsData.Type.SYLLABLE
                        && lyrics != null && lyrics.type != LyricsData.Type.SYLLABLE) {
                    AppLog.w(TAG, "Ignoring incoming " + lyrics.type + " lyrics because overlay already has SYLLABLE lyrics!");
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
                        songChatManager.setCurrentSong(track.title, track.artist, chatLyrics(lyrics));
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
        boolean romajiPref = romajiPref();
        highlighter.setTransliterationEnabled(romajiPref);
        highlighter.setOriginalScriptEnabled(originalScriptPref());
        int lineGapPx = OverlayGeometry.lineGapPx(config, overlayFontSizeSp,
                screen.density(), screen.scaledDensity());

        for (int i = 0; i < lyrics.lines.size(); i++) {
            LyricsData.LyricsLine line = lyrics.lines.get(i);
            if (highlighter.hasAnyWordRomanji(line)) {
                AiSyllableTransliterationService.computeWordBoundaries(line);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            // Line gap scales with the font: at small sizes a hairline margin plus
            // the active line's out-of-bounds glow read as overlap.
            lp.bottomMargin = lineGapPx;

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
                    GoogleTranslateService.PREF_TARGET_LANG,
                    GoogleTranslateService.DEFAULT_TARGET_LANG);
            String resolvedTarget = GoogleTranslateService.resolveTargetLang(
                    targetLang, java.util.Locale.getDefault());
            if (lyrics.translationLang == null || !resolvedTarget.equalsIgnoreCase(lyrics.translationLang)) {
                fetchTranslationForCurrentLyrics();
            }
        }
    }

    private void fetchTranslationForCurrentLyrics() {
        fetchTranslationForCurrentLyrics(null);
    }

    private void fetchTranslationForCurrentLyrics(final LyricActionDelegate.ActionCallback actionCallback) {
        if (currentLyrics == null || currentLyrics.isEmpty()) {
            if (actionCallback != null) actionCallback.onError("Lirik belum dimuat");
            return;
        }
        final String targetLang = sharedPrefs.getString(
                GoogleTranslateService.PREF_TARGET_LANG,
                GoogleTranslateService.DEFAULT_TARGET_LANG);
        final String fetchKey = pendingFetchKey;
        final LyricsData targetLyrics = currentLyrics;
        final TrackInfo targetTrack = currentTrack;

        GoogleTranslateService.TranslationCallback callback =
                new GoogleTranslateService.TranslationCallback() {
            @Override
            public void onSuccess(GoogleTranslateParser.ParseResult result, String resolvedLang) {
                if (isDestroyed || !java.util.Objects.equals(fetchKey, pendingFetchKey)) {
                    if (actionCallback != null) actionCallback.onError("Konteks lagu telah kedaluwarsa");
                    return;
                }
                if (result.isSameLanguage) {
                    AppLog.i(TAG, "Lyrics already in target language (" + result.detectedSourceLang + ")");
                    if (actionCallback != null) actionCallback.onSuccess("Lirik sudah dalam bahasa target (" + result.detectedSourceLang + ").");
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
                updateSongChatLyrics(targetLyrics);
                if (actionCallback != null) {
                    actionCallback.onSuccess("Lirik berhasil diterjemahkan ke " + resolvedLang + " dan ditampilkan di overlay.");
                }
            }

            @Override
            public void onError(Exception error) {
                AppLog.w(TAG, "Translation error: " + error.getMessage());
                if (actionCallback != null) {
                    actionCallback.onError("Gagal menerjemahkan: " + (error != null ? error.getMessage() : "unknown error"));
                }
            }
        };

        // Uses the AI when enabled and signed in, Google Translate otherwise or on failure.
        aiTranslationService.translateLyrics(targetLyrics.lines, targetLang, callback);
    }

    /** Re-render, re-cache and re-publish the current lyrics after an in-place edit. */
    private void renderLyricsEdit() {
        renderOverlayLyrics(currentLyrics);
        if (lyricsCacheManager != null && currentTrack != null) {
            lyricsCacheManager.cache(currentTrack, currentLyrics);
        }
        updateSongChatLyrics(currentLyrics);
    }

    private void updateSongChatLyrics(LyricsData lyrics) {
        if (songChatManager != null && currentTrack != null && lyrics != null && lyrics.lines != null) {
            songChatManager.updateFullLyrics(chatLyrics(lyrics));
        }
    }

    /** The lyrics as the chat model sees them: "[i] text | r: romaji | t: translation" per line. */
    private static String chatLyrics(LyricsData lyrics) {
        StringBuilder sb = new StringBuilder();
        if (lyrics == null || lyrics.lines == null) return "";
        for (int i = 0; i < lyrics.lines.size(); i++) {
            LyricsData.LyricsLine l = lyrics.lines.get(i);
            if (l == null || l.text == null || l.isInterlude) continue;
            sb.append('[').append(i).append("] ").append(l.text);
            if (l.romanji != null && !l.romanji.trim().isEmpty()) sb.append(" | r: ").append(l.romanji.trim());
            if (l.translation != null && !l.translation.trim().isEmpty()) sb.append(" | t: ").append(l.translation.trim());
            sb.append('\n');
        }
        return sb.toString().trim();
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
        wakeRenderLoop();
        if (springScroller != null && lineView != null) {
            springScroller.scrollToView(lineView, true);
        }
    }

    /** Start time of lyric line i; one shared instance so the per-frame pass allocates nothing. */
    private final java.util.function.IntToLongFunction lineStartMs = i -> currentLyrics.lines.get(i).startTime;

    private void updateHighlight(long position, double deltaTime) {
        if (isDestroyed || currentLyrics == null || lineViews.isEmpty()) return;

        int activeIndex = findActiveLine(position);
        if (activeIndex < 0) return;

        int start = OverlayGeometry.highlightStart(config, activeIndex, lastActiveLineIndex);
        int end = OverlayGeometry.highlightEnd(config, activeIndex, lastActiveLineIndex, lineViews.size(),
                currentLyrics.lines.size(), lineStartMs, position);
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
        theme.reset();
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
        clearCoverArtTarget();
        WeakReference<FloatingOverlayService> ref = activeInstanceRef;
        if (ref != null && ref.get() == this) activeInstanceRef = null;
        super.onDestroy();
        if (aiTranslationService != null) {
            aiTranslationService.cancel();
        }
        if (translateService != null) {
            translateService.cancel();
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
        stopToolStatusAnimation();
        if (bodyAnimator != null) bodyAnimator.cancel();
        if (theme != null) theme.release();
        handler.removeCallbacksAndMessages(null);
        if (sharedPrefs != null) {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(settingsListener);
        }
        if (sessionTracker != null) sessionTracker.stop();
        if (lyricsManager != null) lyricsManager.shutdown();
        if (springScroller != null) springScroller.destroy();
        NowPlaying.clear();
        if (chatWindows != null) chatWindows.release();
        if (overlayView != null) {
            // Running view animations (bubble pulse, cross-fades) hold the view; stop them.
            overlayView.animate().cancel();
            if (overlayCover != null) overlayCover.animate().cancel();
            if (overlayTitle != null) overlayTitle.animate().cancel();
            if (overlayArtist != null) overlayArtist.animate().cancel();
            if (overlayBody != null) overlayBody.animate().cancel();
        }
        if (window != null) window.detach();
    }

    private void stopToolStatusAnimation() {
        if (aiToolStatusContainer != null) aiToolStatusContainer.animate().cancel();
    }
}
