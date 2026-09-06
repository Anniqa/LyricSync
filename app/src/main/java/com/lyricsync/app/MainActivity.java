package com.lyricsync.app;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.app.AlertDialog;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import com.google.android.material.card.MaterialCardView;
import com.lyricsync.app.ai.auth.AntigravityAuthManager;
import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.ai.client.AntigravityQuotaClient;
import com.lyricsync.app.ai.history.AiChatHistoryManager;
import com.lyricsync.app.ai.model.AiChatMessage;
import com.lyricsync.app.ai.model.AntigravityModel;
import com.lyricsync.app.ai.model.QuotaSummary;
import com.lyricsync.app.ai.service.SongChatManager;
import com.lyricsync.app.ai.service.SongMeaningService;
import com.lyricsync.app.ai.service.SongMeaningService.SongMeaning;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor;
import com.lyricsync.app.ai.util.AiStreamingAnimator;
import com.lyricsync.app.ai.util.CleanMarkdownFormatter;
import android.text.method.LinkMovementMethod;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.lyricsync.app.overlay.FloatingOverlayService;
import com.lyricsync.app.ui.AlbumPalette;
import com.lyricsync.app.ui.AndroidPrefs;
import com.lyricsync.app.ui.Anim;
import com.lyricsync.app.ui.AnimStyleChips;
import com.lyricsync.app.ui.FontChips;
import com.lyricsync.app.util.AppFont;
import com.lyricsync.app.util.AppLog;
import com.lyricsync.app.util.Haptics;
import com.lyricsync.app.util.LocaleHelper;
import com.lyricsync.app.util.NowPlaying;
import com.lyricsync.app.util.Permissions;
import com.lyricsync.app.util.SeekBars;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements NowPlaying.Listener {
    private static final String TAG = "MainActivity";
    private static final int REQ_POST_NOTIFICATIONS = 42;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase));
    }

    /** Sync slider is 0..SYNC_RANGE with the midpoint meaning "no offset". */
    private static final int SYNC_LIMIT_MS = 1500;
    private static final int SYNC_RANGE = SYNC_LIMIT_MS * 2;

    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private View heroGlow;
    private View npEqualizer;
    private ImageView npArt;
    private ImageView npArtPlaceholder;
    private TextView npTitle;
    private TextView npArtist;
    private TextView npStatus;

    private View notificationPill;
    private ImageView notificationPillIcon;
    private TextView notificationStatus;
    private View overlayPill;
    private ImageView overlayPillIcon;
    private TextView overlayStatus;
    private TextView permissionsSummary;
    private View permissionsContent;
    private ImageView permissionsChevron;

    private MaterialButton startButton;
    private View devContent;
    private ImageView devChevron;
    private TextView logText;
    private ScrollView logScroll;

    private TextView syncOffsetLabel;
    private SeekBar syncOffsetSlider;
    private android.widget.LinearLayout fontChips;
    private android.widget.LinearLayout animChips;
    private android.widget.LinearLayout animEffectChips;
    private TextView animComboLabel;

    private TextView btnLangEn;
    private TextView btnLangId;

    // ── AI Account Views & Subsystems ──────────────────────────────────────
    private View layoutAiDisconnected;
    private View layoutAiConnected;
    private TextView tvAiEmail;

    public static final List<String> BASE_ANTIGRAVITY_MODELS = Collections.unmodifiableList(Arrays.asList(
            "gemini-3.8-flash-high",
            "gemini-3.8-flash-medium",
            "gemini-3.8-flash-low",
            "gemini-3.7-flash-high",
            "gemini-3.7-flash-medium",
            "gemini-3.7-flash-low",
            "gemini-3.6-flash-high",
            "gemini-3.6-flash-medium",
            "gemini-3.6-flash-low",
            "gemini-3.5-flash-low",
            "gemini-3.5-flash-extra-low",
            "gemini-3-pro-high",
            "gemini-3-pro-low",
            "gemini-3-flash-agent",
            "gemini-pro-agent",
            "gemini-3.1-pro-low",
            "gemini-3.1-flash-lite",
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "claude-sonnet-4-6",
            "claude-opus-4-6-thinking",
            "claude-3-7-sonnet",
            "claude-3-5-sonnet",
            "gpt-oss-120b-medium"
    ));
    private ProgressBar quotaWeeklyProgress;
    private TextView tvQuotaWeekly;
    private ProgressBar quota5hProgress;
    private TextView tvQuota5h;
    private TextView tvAiTokens;

    // ── AI Song Studio Views & Subsystems ──────────────────────────────────
    private MaterialCardView cardAiStudio;
    private View aiStudioHeader;
    private View aiStudioContent;
    private ImageView aiStudioChevron;
    private TextView aiStudioTrackInfo;
    private TextView aiStudioMoodBadge;
    private TextView aiStudioQuotaBadge;
    private TextView aiStudioSummary;
    private HorizontalScrollView aiStudioWebSourcesScroll;
    private LinearLayout aiStudioWebSourcesContainer;
    private HorizontalScrollView aiStudioQuickChips;
    private LinearLayout aiStudioQuickChipsContainer;
    private ScrollView aiStudioChatScroll;
    private LinearLayout aiStudioChatContainer;
    private ImageView aiStudioWebSearchChip;
    private EditText aiStudioChatInput;
    private ImageView aiStudioChatSend;
    private LinearLayout aiStudioToolStatusContainer;
    private ImageView aiStudioToolStatusIcon;
    private TextView aiStudioToolStatusText;
    private Runnable pendingToolStatusHide;
    private View aiStudioModelSelectorPill;
    private ImageView aiStudioModelPillIcon;
    private TextView aiStudioModelPillName;
    private TextView aiStudioModelDescBadge;
    private ImageView aiStudioBtnNewChat;
    private ImageView aiStudioBtnHistory;
    private AiChatHistoryManager aiChatHistoryManager;

    private AntigravityAuthManager authManager;
    private AntigravityApiClient apiClient;
    private AntigravityQuotaClient quotaClient;
    private SongMeaningService songMeaningService;
    private SongChatManager songChatManager;
    private AntigravityApiClient.Cancellable activeMeaningCall;
    private AntigravityApiClient.Cancellable activeChatCall;
    private AiStreamingAnimator activeStudioAnimator;
    private boolean aiWebSearchEnabled = false;
    private String currentTrackTitle;
    private String currentTrackArtist;
    private List<AntigravityModel> availableAiModels = new ArrayList<>();

    private ObjectAnimator[] eqAnimators;
    private ValueAnimator heroTintAnimator;
    private ValueAnimator startTintAnimator;

    private int heroAccent = AlbumPalette.DEFAULT_ACCENT;
    /** Playback-state pushes repeat the same bitmap; only re-run Palette when it changes. */
    private Bitmap lastPaletteArt;
    private boolean lastNotificationGranted;
    private boolean lastOverlayGranted;
    private boolean permissionStateKnown = false;
    private boolean lastServiceRunning;
    private boolean startButtonStateKnown = false;
    private boolean entrancePlayed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("lyricsync", MODE_PRIVATE);
        String savedSearchModel = prefs.getString("pref_ai_search_model", AntigravityToolExecutor.UNIFIED_SEARCH_MODEL);
        AntigravityToolExecutor.setSearchModel(savedSearchModel);
        AntigravityToolExecutor.setChatModelSupplier(() ->
                prefs.getString("pref_ai_model", SongChatManager.DEFAULT_MODEL));

        initViews();
        applyWindowInsets();
        setupListeners();
        setupSliders();
        setupAppearanceChips();
        setupLanguageUi();
        setupTranslationUi();
        setupAiAccountUi();
        setupAiStudioUi();
        setupDeveloperSection();
        setupLogViewer();

        AppLog.i(TAG, "App started");
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
        updateStartButton();
        updateAiAccountState();
        NowPlaying.addListener(this);
        if (!entrancePlayed) {
            entrancePlayed = true;
            playEntrance();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        NowPlaying.removeListener(this);
        stopEqualizer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppLog.setListener(null);
        handler.removeCallbacksAndMessages(null);
        stopEqualizer();
        if (heroTintAnimator != null) heroTintAnimator.cancel();
        if (startTintAnimator != null) startTintAnimator.cancel();
        if (activeMeaningCall != null) {
            activeMeaningCall.cancel();
            activeMeaningCall = null;
        }
        if (activeChatCall != null) {
            activeChatCall.cancel();
            activeChatCall = null;
        }
        if (activeStudioAnimator != null) {
            activeStudioAnimator.stopAll();
            activeStudioAnimator = null;
        }
    }

    // ── Setup ──────────────────────────────────────────────────────────────

    private void initViews() {
        heroGlow = findViewById(R.id.hero_glow);
        npEqualizer = findViewById(R.id.np_equalizer);
        npArt = findViewById(R.id.np_art);
        npArtPlaceholder = findViewById(R.id.np_art_placeholder);
        npTitle = findViewById(R.id.np_title);
        npArtist = findViewById(R.id.np_artist);
        npStatus = findViewById(R.id.np_status);

        notificationPill = findViewById(R.id.notification_pill);
        notificationPillIcon = findViewById(R.id.notification_pill_icon);
        notificationStatus = findViewById(R.id.notification_status);
        overlayPill = findViewById(R.id.overlay_pill);
        overlayPillIcon = findViewById(R.id.overlay_pill_icon);
        overlayStatus = findViewById(R.id.overlay_status);
        permissionsSummary = findViewById(R.id.permissions_summary);
        permissionsContent = findViewById(R.id.permissions_content);
        permissionsChevron = findViewById(R.id.permissions_chevron);

        startButton = findViewById(R.id.btn_start);
        devContent = findViewById(R.id.dev_content);
        devChevron = findViewById(R.id.dev_chevron);
        logText = findViewById(R.id.log_text);
        logScroll = findViewById(R.id.log_scroll);

        syncOffsetLabel = findViewById(R.id.sync_offset_label);
        syncOffsetSlider = findViewById(R.id.sync_offset_slider);
        fontChips = findViewById(R.id.font_chips);
        animChips = findViewById(R.id.anim_chips);
        animEffectChips = findViewById(R.id.anim_effect_chips);
        animComboLabel = findViewById(R.id.anim_combo_label);
    }

    /** Draw behind the system bars, then pad the scrolling content back into view. */
    private void applyWindowInsets() {
        final View content = findViewById(R.id.main_content);
        final int extraBottom = dpToPx(32);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), bars.top,
                    v.getPaddingRight(), bars.bottom + extraBottom);
            return insets;
        });
    }

    private void playEntrance() {
        Anim.enterStagger(60, 70,
                findViewById(R.id.header),
                findViewById(R.id.card_now_playing),
                findViewById(R.id.card_ai_studio),
                findViewById(R.id.card_permissions),
                startButton,
                findViewById(R.id.card_appearance),
                findViewById(R.id.card_dimensions),
                findViewById(R.id.card_sync),
                findViewById(R.id.card_translation),
                findViewById(R.id.card_ai_account),
                findViewById(R.id.card_dev));

        heroGlow.setAlpha(0f);
        heroGlow.animate().alpha(1f).setDuration(900).setInterpolator(Anim.DECEL).start();
    }

    private void setupListeners() {
        View permissionsHeader = findViewById(R.id.permissions_header);
        if (permissionsHeader != null) {
            permissionsHeader.setOnClickListener(v -> {
                Haptics.tick(v);
                togglePermissionsSection();
            });
        }

        findViewById(R.id.btn_notification_access).setOnClickListener(v -> {
            Haptics.tick(v);
            startSettings(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        });

        findViewById(R.id.btn_overlay_permission).setOnClickListener(v -> {
            Haptics.tick(v);
            startSettings(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        });

        startButton.setOnClickListener(v -> {
            Haptics.confirm(v);
            if (FloatingOverlayService.isRunning()) {
                stopService(new Intent(this, FloatingOverlayService.class));
                Toast.makeText(this, R.string.overlay_stopped, Toast.LENGTH_SHORT).show();
            } else {
                if (!hasAllPermissions()) {
                    Toast.makeText(this, R.string.grant_permissions_first, Toast.LENGTH_SHORT).show();
                    nudge(findViewById(R.id.card_permissions));
                    return;
                }
                requestPostNotificationsIfNeeded();
                startForegroundService(new Intent(this, FloatingOverlayService.class));
                Toast.makeText(this, R.string.overlay_started, Toast.LENGTH_SHORT).show();
            }
            // The service flips its running flag asynchronously; re-read shortly after.
            handler.postDelayed(this::updateStartButton, 350);
        });

        findViewById(R.id.btn_clear_logs).setOnClickListener(v -> {
            Haptics.tick(v);
            AppLog.clear();
            logText.setText("");
            AppLog.i(TAG, "Logs cleared");
        });

        findViewById(R.id.btn_share_logs).setOnClickListener(v -> {
            Haptics.tick(v);
            shareLogs();
        });

        findViewById(R.id.btn_sync_reset).setOnClickListener(v -> {
            Haptics.tick(v);
            syncOffsetSlider.setProgress(SYNC_LIMIT_MS);
            Anim.pop(syncOffsetLabel);
        });
    }

    private void startSettings(Intent intent) {
        try {
            startActivity(intent);
        } catch (Exception e) {
            AppLog.e(TAG, "Cannot open settings screen: " + e.getMessage());
            Toast.makeText(this, "Couldn't open that settings screen", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_POST_NOTIFICATIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_POST_NOTIFICATIONS) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            AppLog.i(TAG, "POST_NOTIFICATIONS " + (granted ? "granted" : "denied"));
        }
    }

    // ── Appearance chips (font + lyric animation) ──────────────────────────

    private void setupAppearanceChips() {
        FontChips.build(this, fontChips, AppFont.currentKey(prefs), false,
                style -> Haptics.tick(fontChips));
        AnimStyleChips.buildWithPreviews(this, animChips, animEffectChips, animComboLabel,
                new AndroidPrefs(prefs), (m, e) -> Haptics.tick(animChips));
    }

    // ── Sliders ────────────────────────────────────────────────────────────

    private void setupSliders() {
        setupFontSizeSlider();
        setupOverlaySizeSliders();
        setupSyncOffsetSlider();
    }

    private void setupFontSizeSlider() {
        SeekBar slider = findViewById(R.id.font_size_slider);
        TextView label = findViewById(R.id.font_size_label);
        int saved = clamp((int) (prefs.getFloat("font_scale", 1.0f) * 100), 50, 200);
        label.setText(saved + "%");

        SeekBars.bind(slider, saved, (progress, fromUser) -> {
            int value = clamp(progress, 50, 200);
            label.setText(value + "%");
            prefs.edit().putFloat("font_scale", value / 100f).apply();
            if (fromUser) Haptics.tick(slider);
        });
    }

    private void setupOverlaySizeSliders() {
        SeekBar widthSlider = findViewById(R.id.overlay_width_slider);
        TextView widthLabel = findViewById(R.id.overlay_width_label);
        int widthPercent = clamp(prefs.getInt("overlay_width_percent", 88), 55, 100);
        widthLabel.setText(widthPercent + "%");
        SeekBars.bind(widthSlider, widthPercent, (progress, fromUser) -> {
            int value = clamp(progress, 55, 100);
            widthLabel.setText(value + "%");
            prefs.edit().putInt("overlay_width_percent", value).apply();
            if (fromUser) Haptics.tick(widthSlider);
        });

        SeekBar heightSlider = findViewById(R.id.overlay_height_slider);
        TextView heightLabel = findViewById(R.id.overlay_height_label);
        int heightPercent = clamp(prefs.getInt("overlay_height_percent", 36), 20, 70);
        heightLabel.setText(heightPercent + "%");
        SeekBars.bind(heightSlider, heightPercent, (progress, fromUser) -> {
            int value = clamp(progress, 20, 70);
            heightLabel.setText(value + "%");
            prefs.edit().putInt("overlay_height_percent", value).apply();
            if (fromUser) Haptics.tick(heightSlider);
        });
    }

    /**
     * The slider runs 0..3000 and the offset is progress - 1500. The previous mapping
     * paired android:min="-1500" with the same subtraction, which made every positive
     * offset unreachable.
     */
    private void setupSyncOffsetSlider() {
        int saved = clamp((int) prefs.getLong("sync_offset_ms", 0), -SYNC_LIMIT_MS, SYNC_LIMIT_MS);
        syncOffsetLabel.setText(formatOffset(saved));

        SeekBars.bind(syncOffsetSlider, saved + SYNC_LIMIT_MS, (progress, fromUser) -> {
            int offset = clamp(progress - SYNC_LIMIT_MS, -SYNC_LIMIT_MS, SYNC_LIMIT_MS);
            syncOffsetLabel.setText(formatOffset(offset));
            prefs.edit().putLong("sync_offset_ms", offset).apply();
            if (fromUser) Haptics.tick(syncOffsetSlider);
        });
    }

    private String formatOffset(int ms) {
        if (ms == 0) return "0 ms · " + getString(R.string.in_sync);
        String sign = ms > 0 ? "+" : "";
        String meaning = getString(ms > 0 ? R.string.lyrics_later : R.string.lyrics_earlier);
        return sign + ms + " ms · " + meaning;
    }

    private void setupTranslationUi() {
        androidx.appcompat.widget.SwitchCompat switchTrans = findViewById(R.id.switch_main_translation);
        android.widget.Spinner spinnerLang = findViewById(R.id.spinner_target_lang);
        if (switchTrans == null || spinnerLang == null) return;

        String[] langNames = {
                "Auto (System Language)",
                "Bahasa Indonesia (id)",
                "English (en)",
                "日本語 (ja)",
                "한국어 (ko)",
                "Español (es)",
                "简体中文 (zh-CN)",
                "Français (fr)",
                "Deutsch (de)"
        };
        String[] langCodes = {"auto", "id", "en", "ja", "ko", "es", "zh-CN", "fr", "de"};

        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                this, R.layout.item_spinner_lang, langNames);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        spinnerLang.setAdapter(adapter);

        String currentLang = prefs.getString(
                com.lyricsync.app.lyrics.translation.GoogleTranslateService.PREF_TARGET_LANG,
                com.lyricsync.app.lyrics.translation.GoogleTranslateService.DEFAULT_TARGET_LANG);
        for (int i = 0; i < langCodes.length; i++) {
            if (langCodes[i].equalsIgnoreCase(currentLang)) {
                spinnerLang.setSelection(i);
                break;
            }
        }

        switchTrans.setChecked(prefs.getBoolean(
                com.lyricsync.app.lyrics.translation.GoogleTranslateService.PREF_TRANSLATION_ENABLED, false));
        switchTrans.setOnCheckedChangeListener((btn, isChecked) -> {
            Haptics.tick(btn);
            prefs.edit().putBoolean(
                    com.lyricsync.app.lyrics.translation.GoogleTranslateService.PREF_TRANSLATION_ENABLED, isChecked).apply();
        });

        spinnerLang.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putString(
                        com.lyricsync.app.lyrics.translation.GoogleTranslateService.PREF_TARGET_LANG, langCodes[position]).apply();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        androidx.appcompat.widget.SwitchCompat switchAiTrans = findViewById(R.id.switch_ai_translation);
        if (switchAiTrans != null) {
            switchAiTrans.setChecked(prefs.getBoolean(
                    com.lyricsync.app.lyrics.translation.AiTranslationService.PREF_AI_TRANSLATION_ENABLED, true));
            switchAiTrans.setOnCheckedChangeListener((btn, isChecked) -> {
                Haptics.tick(btn);
                prefs.edit().putBoolean(
                        com.lyricsync.app.lyrics.translation.AiTranslationService.PREF_AI_TRANSLATION_ENABLED, isChecked).apply();
            });
        }
    }

    // ── AI Account & Subsystems ────────────────────────────────────────────

    private void setupAiAccountUi() {
        authManager = AntigravityAuthManager.getInstance(this);
        quotaClient = new AntigravityQuotaClient(authManager);

        layoutAiDisconnected = findViewById(R.id.layout_ai_disconnected);
        layoutAiConnected = findViewById(R.id.layout_ai_connected);
        tvAiEmail = findViewById(R.id.tv_ai_email);
        quotaWeeklyProgress = findViewById(R.id.quota_weekly_progress);
        tvQuotaWeekly = findViewById(R.id.tv_quota_weekly);
        quota5hProgress = findViewById(R.id.quota_5h_progress);
        tvQuota5h = findViewById(R.id.tv_quota_5h);
        tvAiTokens = findViewById(R.id.tv_ai_tokens);

        MaterialButton btnConnect = findViewById(R.id.btn_connect_google);
        if (btnConnect != null) {
            btnConnect.setOnClickListener(v -> {
                Haptics.tick(v);
                Toast.makeText(this, "Membuka browser untuk login Google...", Toast.LENGTH_SHORT).show();
                authManager.startBrowserLogin(this, new AntigravityAuthManager.AuthCallback() {
                    @Override
                    public void onSuccess(String email) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Berhasil login: " + email, Toast.LENGTH_SHORT).show();
                            updateAiAccountState();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Gagal login: " + message, Toast.LENGTH_LONG).show();
                        });
                    }
                });
            });
        }

        View btnManual = findViewById(R.id.btn_manual_token);
        if (btnManual != null) {
            btnManual.setOnClickListener(v -> {
                Haptics.tick(v);
                showManualTokenDialog();
            });
        }

        View btnDisconnect = findViewById(R.id.btn_disconnect_google);
        if (btnDisconnect != null) {
            btnDisconnect.setOnClickListener(v -> {
                Haptics.tick(v);
                authManager.logout();
                Toast.makeText(this, "Berhasil logout", Toast.LENGTH_SHORT).show();
                updateAiAccountState();
            });
        }

        updateAiAccountState();
    }

    private void updateAiAccountState() {
        if (authManager == null) return;
        boolean authenticated = authManager.isAuthenticated();

        if (layoutAiDisconnected != null) {
            layoutAiDisconnected.setVisibility(authenticated ? View.GONE : View.VISIBLE);
        }
        if (layoutAiConnected != null) {
            layoutAiConnected.setVisibility(authenticated ? View.VISIBLE : View.GONE);
        }

        if (authenticated) {
            String email = authManager.getEmail();
            if (tvAiEmail != null) {
                tvAiEmail.setText(email != null && !email.isEmpty() ? email : "Google Antigravity Connected");
            }

            // Fetch live quota
            if (quotaClient != null) {
                quotaClient.fetchQuota(new AntigravityQuotaClient.QuotaCallback() {
                    @Override
                    public void onQuotaLoaded(QuotaSummary summary) {
                        runOnUiThread(() -> {
                            if (summary == null) return;
                            int weeklyPct = Math.round(summary.geminiWeeklyFraction * 100f);
                            if (quotaWeeklyProgress != null) quotaWeeklyProgress.setProgress(weeklyPct);
                            if (tvQuotaWeekly != null) tvQuotaWeekly.setText(weeklyPct + "%");

                            int h5Pct = Math.round(summary.gemini5hFraction * 100f);
                            if (quota5hProgress != null) quota5hProgress.setProgress(h5Pct);
                            if (tvQuota5h != null) tvQuota5h.setText(h5Pct + "%");

                            if (aiStudioQuotaBadge != null) {
                                aiStudioQuotaBadge.setText("AI Quota: " + h5Pct + "%");
                                aiStudioQuotaBadge.setVisibility(View.VISIBLE);
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        // Keep current
                    }
                });

                // Fetch dynamic models
                quotaClient.fetchModels(new AntigravityQuotaClient.ModelDiscoveryCallback() {
                    @Override
                    public void onModelsLoaded(List<AntigravityModel> models) {
                        runOnUiThread(() -> {
                            if (models == null || models.isEmpty()) return;
                            availableAiModels = models;
                        });
                    }

                    @Override
                    public void onError(String error) {
                        // Keeps BASE_ANTIGRAVITY_MODELS
                    }
                });
            }

            if (tvAiTokens != null) {
                int tokens = prefs.getInt("pref_ai_total_tokens", 0);
                if (tokens > 0) {
                    tvAiTokens.setText(getString(R.string.ai_total_tokens, tokens));
                    tvAiTokens.setVisibility(View.VISIBLE);
                } else {
                    tvAiTokens.setVisibility(View.GONE);
                }
            }
        } else {
            if (aiStudioQuotaBadge != null) {
                aiStudioQuotaBadge.setVisibility(View.GONE);
            }
        }
    }

    private void showManualTokenDialog() {
        final EditText input = new EditText(this);
        input.setHint(R.string.ai_manual_token_hint);
        input.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        input.setTextColor(getResources().getColor(R.color.text_primary));
        input.setHintTextColor(getResources().getColor(R.color.text_dim));

        new AlertDialog.Builder(this)
                .setTitle(R.string.ai_manual_token_title)
                .setView(input)
                .setPositiveButton("Simpan", (dialog, which) -> {
                    String token = input.getText().toString().trim();
                    if (!token.isEmpty()) {
                        authManager.manualTokenLogin(token, new AntigravityAuthManager.AuthCallback() {
                            @Override
                            public void onSuccess(String email) {
                                runOnUiThread(() -> {
                                    Toast.makeText(MainActivity.this, "Berhasil terhubung: " + email, Toast.LENGTH_SHORT).show();
                                    updateAiAccountState();
                                });
                            }

                            @Override
                            public void onError(String message) {
                                runOnUiThread(() -> {
                                    Toast.makeText(MainActivity.this, "Gagal: " + message, Toast.LENGTH_LONG).show();
                                });
                            }
                        });
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    // ── Developer section ──────────────────────────────────────────────────

    private void setupDeveloperSection() {
        boolean expanded = prefs.getBoolean("dev_expanded", false);
        devContent.setVisibility(expanded ? View.VISIBLE : View.GONE);
        devChevron.setRotation(expanded ? 180f : 0f);

        findViewById(R.id.dev_header).setOnClickListener(v -> {
            Haptics.tick(v);
            boolean nowExpanded = devContent.getVisibility() != View.VISIBLE;
            if (nowExpanded) {
                Anim.expand(devContent);
                scrollToBottom();
            } else {
                Anim.collapse(devContent);
            }
            Anim.rotateTo(devChevron, nowExpanded ? 180f : 0f);
            prefs.edit().putBoolean("dev_expanded", nowExpanded).apply();
        });
    }

    private void setupLogViewer() {
        logText.setText(AppLog.getAllText());
        scrollToBottom();

        AppLog.setListener(entry -> handler.post(() -> {
            logText.append(entry.toString() + "\n");
            scrollToBottom();
        }));
    }

    private void scrollToBottom() {
        logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void shareLogs() {
        try {
            String logs = AppLog.getAllText();
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String header = "=== LyricSync Log " + timestamp + " ===\n"
                    + "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                    + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n\n";

            File logFile = new File(getCacheDir(), "lyricsync_log_" + timestamp + ".txt");
            try (FileWriter fw = new FileWriter(logFile)) {
                fw.write(header + logs);
            }

            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", logFile);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "LyricSync Log");
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_logs)));

            AppLog.i(TAG, "Logs shared");
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to share logs: " + e.getMessage());
            Toast.makeText(this, R.string.share_logs_failed, Toast.LENGTH_SHORT).show();
        }
    }

    // ── Permission + service state ─────────────────────────────────────────

    private void togglePermissionsSection() {
        if (permissionsContent == null) return;
        boolean nowExpanded = permissionsContent.getVisibility() != View.VISIBLE;
        if (nowExpanded) {
            Anim.expand(permissionsContent);
        } else {
            Anim.collapse(permissionsContent);
        }
        if (permissionsChevron != null) {
            Anim.rotateTo(permissionsChevron, nowExpanded ? 180f : 0f);
        }
    }

    private void updatePermissionStatus() {
        boolean notifGranted = Permissions.isNotificationListenerEnabled(this);
        boolean overlayGranted = Settings.canDrawOverlays(this);
        boolean allGranted = notifGranted && overlayGranted;

        boolean notifChanged = permissionStateKnown && notifGranted != lastNotificationGranted;
        boolean overlayChanged = permissionStateKnown && overlayGranted != lastOverlayGranted;

        applyPill(notificationPill, notificationPillIcon, notificationStatus, notifGranted, notifChanged);
        applyPill(overlayPill, overlayPillIcon, overlayStatus, overlayGranted, overlayChanged);

        permissionsSummary.setText(allGranted
                ? R.string.permissions_all_set : R.string.permissions_needed);
        permissionsSummary.setTextColor(ContextCompat.getColor(this,
                allGranted ? R.color.state_ok : R.color.text_dim));

        if (!permissionStateKnown) {
            if (permissionsContent != null) {
                permissionsContent.setVisibility(allGranted ? View.GONE : View.VISIBLE);
            }
            if (permissionsChevron != null) {
                permissionsChevron.setRotation(allGranted ? 0f : 180f);
            }
        } else if ((notifChanged || overlayChanged) && allGranted) {
            if (permissionsContent != null && permissionsContent.getVisibility() == View.VISIBLE) {
                Anim.collapse(permissionsContent);
                if (permissionsChevron != null) Anim.rotateTo(permissionsChevron, 0f);
            }
        } else if (!allGranted && permissionsContent != null && permissionsContent.getVisibility() != View.VISIBLE) {
            Anim.expand(permissionsContent);
            if (permissionsChevron != null) Anim.rotateTo(permissionsChevron, 180f);
        }

        lastNotificationGranted = notifGranted;
        lastOverlayGranted = overlayGranted;
        permissionStateKnown = true;
    }

    private void applyPill(View pill, ImageView icon, TextView label, boolean granted, boolean animate) {
        pill.setBackgroundResource(granted ? R.drawable.bg_pill_ok : R.drawable.bg_pill_warn);
        icon.setImageResource(granted ? R.drawable.ic_check : R.drawable.ic_alert);
        int color = ContextCompat.getColor(this, granted ? R.color.state_ok : R.color.state_warn);
        icon.setColorFilter(color);
        label.setTextColor(color);
        label.setText(granted ? R.string.enabled : R.string.disabled);
        if (animate) Anim.pop(pill);
    }

    private void updateStartButton() {
        boolean running = FloatingOverlayService.isRunning();
        if (startButtonStateKnown && running == lastServiceRunning) return;

        startButton.setText(running ? R.string.stop : R.string.start);
        startButton.setIconResource(running ? R.drawable.ic_stop : R.drawable.ic_play);

        int from = ContextCompat.getColor(this, lastServiceRunning ? R.color.state_error : R.color.accent);
        int to = ContextCompat.getColor(this, running ? R.color.state_error : R.color.accent);
        int content = ContextCompat.getColor(this, running ? R.color.white : R.color.black);
        startButton.setTextColor(content);
        startButton.setIconTint(ColorStateList.valueOf(content));

        if (startTintAnimator != null) startTintAnimator.cancel();
        if (startButtonStateKnown) {
            startTintAnimator = Anim.color(from, to, Anim.D_MED,
                    color -> startButton.setBackgroundTintList(ColorStateList.valueOf(color)));
            Anim.pop(startButton);
        } else {
            startButton.setBackgroundTintList(ColorStateList.valueOf(to));
        }

        lastServiceRunning = running;
        startButtonStateKnown = true;
    }

    private boolean hasAllPermissions() {
        return Permissions.isNotificationListenerEnabled(this) && Settings.canDrawOverlays(this);
    }

    /** Small shake to point at whatever is blocking the user. */
    private void nudge(View v) {
        if (v == null) return;
        if (v.getId() == R.id.card_permissions && permissionsContent != null
                && permissionsContent.getVisibility() != View.VISIBLE) {
            Anim.expand(permissionsContent);
            if (permissionsChevron != null) Anim.rotateTo(permissionsChevron, 180f);
        }
        v.animate().cancel();
        ObjectAnimator shake = ObjectAnimator.ofFloat(v, View.TRANSLATION_X,
                0f, -12f, 12f, -7f, 7f, 0f);
        shake.setDuration(420);
        shake.setInterpolator(new AccelerateDecelerateInterpolator());
        shake.start();
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    // ── Now playing mirror ─────────────────────────────────────────────────

    @Override
    public void onNowPlaying(NowPlaying.Snapshot snapshot) {
        if (isFinishing() || isDestroyed()) return;

        if (!snapshot.hasTrack) {
            Anim.setTextAnimated(npTitle, getString(R.string.nothing_playing));
            Anim.setTextAnimated(npArtist, getString(R.string.nothing_playing_desc));
            npStatus.setVisibility(View.GONE);
            npArt.setImageDrawable(null);
            npArtPlaceholder.setVisibility(View.VISIBLE);
            lastPaletteArt = null;
            stopEqualizer();
            npEqualizer.setVisibility(View.INVISIBLE);
            tintHero(AlbumPalette.DEFAULT_ACCENT);
            onNowPlayingChanged(snapshot);
            return;
        }

        Anim.setTextAnimated(npTitle, snapshot.title == null ? "" : snapshot.title);
        Anim.setTextAnimated(npArtist, snapshot.artist == null ? "" : snapshot.artist);

        if (snapshot.lyricsStatus != null && !snapshot.lyricsStatus.isEmpty()) {
            npStatus.setText(snapshot.lyricsStatus);
            if (npStatus.getVisibility() != View.VISIBLE) {
                npStatus.setVisibility(View.VISIBLE);
                Anim.pop(npStatus);
            }
        } else {
            npStatus.setVisibility(View.GONE);
        }

        Bitmap art = snapshot.art;
        if (art != null && !art.isRecycled()) {
            npArt.setImageBitmap(art);
            npArtPlaceholder.setVisibility(View.GONE);
            if (art != lastPaletteArt) {
                lastPaletteArt = art;
                AlbumPalette.from(art, (accent, deep) -> tintHero(accent));
            }
        } else {
            npArt.setImageDrawable(null);
            npArtPlaceholder.setVisibility(View.VISIBLE);
            lastPaletteArt = null;
            tintHero(AlbumPalette.DEFAULT_ACCENT);
        }

        if (snapshot.playing) {
            npEqualizer.setVisibility(View.VISIBLE);
            startEqualizer();
        } else {
            stopEqualizer();
            npEqualizer.setVisibility(View.INVISIBLE);
        }

        onNowPlayingChanged(snapshot);
    }

    // ── App Language ────────────────────────────────────────────────────────

    private void setupLanguageUi() {
        btnLangEn = findViewById(R.id.btn_lang_en);
        btnLangId = findViewById(R.id.btn_lang_id);
        if (btnLangEn == null || btnLangId == null) return;

        String currentLang = LocaleHelper.getLanguage(this);
        updateLanguageSelectionUi(currentLang);

        btnLangEn.setOnClickListener(v -> {
            Haptics.tick(v);
            if (LocaleHelper.LANG_EN.equalsIgnoreCase(LocaleHelper.getLanguage(MainActivity.this))) {
                return;
            }
            LocaleHelper.setLanguage(MainActivity.this, LocaleHelper.LANG_EN);
            recreate();
        });

        btnLangId.setOnClickListener(v -> {
            Haptics.tick(v);
            if (LocaleHelper.LANG_ID.equalsIgnoreCase(LocaleHelper.getLanguage(MainActivity.this))) {
                return;
            }
            LocaleHelper.setLanguage(MainActivity.this, LocaleHelper.LANG_ID);
            recreate();
        });
    }

    private void updateLanguageSelectionUi(String langCode) {
        boolean isId = LocaleHelper.LANG_ID.equalsIgnoreCase(langCode);
        if (btnLangEn != null) {
            btnLangEn.setBackgroundResource(isId ? R.drawable.bg_icon_button : R.drawable.bg_pill_ok);
            btnLangEn.setTextColor(ContextCompat.getColor(this, isId ? R.color.text_secondary : R.color.text_primary));
        }
        if (btnLangId != null) {
            btnLangId.setBackgroundResource(isId ? R.drawable.bg_pill_ok : R.drawable.bg_icon_button);
            btnLangId.setTextColor(ContextCompat.getColor(this, isId ? R.color.text_primary : R.color.text_secondary));
        }
    }

    // ── AI Song Studio ─────────────────────────────────────────────────────

    private void setupAiStudioUi() {
        if (authManager == null) {
            authManager = AntigravityAuthManager.getInstance(this);
        }
        if (apiClient == null) {
            apiClient = new AntigravityApiClient(authManager);
        }
        if (songMeaningService == null) {
            songMeaningService = new SongMeaningService(prefs, apiClient);
        }
        if (songChatManager == null) {
            songChatManager = new SongChatManager(apiClient);
        }
        songChatManager.setAppLanguage(LocaleHelper.getLanguage(this));
        aiChatHistoryManager = new AiChatHistoryManager(this);
        songChatManager.setHistoryManager(aiChatHistoryManager);

        cardAiStudio = findViewById(R.id.card_ai_studio);
        aiStudioHeader = findViewById(R.id.ai_studio_header);
        aiStudioContent = findViewById(R.id.ai_studio_content);
        aiStudioChevron = findViewById(R.id.ai_studio_chevron);
        aiStudioBtnNewChat = findViewById(R.id.ai_studio_btn_new_chat);
        aiStudioBtnHistory = findViewById(R.id.ai_studio_btn_history);
        aiStudioTrackInfo = findViewById(R.id.ai_studio_track_info);
        aiStudioMoodBadge = findViewById(R.id.ai_studio_mood_badge);
        aiStudioQuotaBadge = findViewById(R.id.ai_studio_quota_badge);
        aiStudioSummary = findViewById(R.id.ai_studio_summary);
        aiStudioWebSourcesScroll = findViewById(R.id.ai_studio_web_sources_scroll);
        aiStudioWebSourcesContainer = findViewById(R.id.ai_studio_web_sources_container);
        aiStudioQuickChips = findViewById(R.id.ai_studio_quick_chips);
        aiStudioQuickChipsContainer = findViewById(R.id.ai_studio_quick_chips_container);
        aiStudioChatScroll = findViewById(R.id.ai_studio_chat_scroll);
        aiStudioChatContainer = findViewById(R.id.ai_studio_chat_container);
        aiStudioWebSearchChip = findViewById(R.id.ai_studio_web_search_chip);
        aiStudioChatInput = findViewById(R.id.ai_studio_chat_input);
        aiStudioChatSend = findViewById(R.id.ai_studio_chat_send);
        aiStudioToolStatusContainer = findViewById(R.id.ai_tool_status_container);
        aiStudioToolStatusIcon = findViewById(R.id.ai_tool_status_icon);
        aiStudioToolStatusText = findViewById(R.id.ai_tool_status_text);

        aiStudioModelSelectorPill = findViewById(R.id.ai_studio_model_selector_pill);
        aiStudioModelPillIcon = findViewById(R.id.ai_studio_model_pill_icon);
        aiStudioModelPillName = findViewById(R.id.ai_studio_model_pill_name);
        aiStudioModelDescBadge = findViewById(R.id.ai_studio_model_desc_badge);

        String savedModel = prefs.getString("pref_ai_model", SongChatManager.DEFAULT_MODEL);
        updateAiStudioModelUi(savedModel);

        if (aiStudioModelSelectorPill != null) {
            aiStudioModelSelectorPill.setOnClickListener(v -> {
                Haptics.tick(v);
                showModelSelectorDialog();
            });
        }

        boolean studioExpanded = prefs.getBoolean("ai_studio_expanded", true);
        if (aiStudioContent != null) {
            aiStudioContent.setVisibility(studioExpanded ? View.VISIBLE : View.GONE);
        }
        if (aiStudioChevron != null) {
            aiStudioChevron.setRotation(studioExpanded ? 180f : 0f);
        }
        if (aiStudioHeader != null) {
            aiStudioHeader.setOnClickListener(v -> {
                Haptics.tick(v);
                if (aiStudioContent == null) return;
                boolean nowExpanded = aiStudioContent.getVisibility() != View.VISIBLE;
                if (nowExpanded) {
                    Anim.expand(aiStudioContent);
                } else {
                    Anim.collapse(aiStudioContent);
                }
                if (aiStudioChevron != null) {
                    Anim.rotateTo(aiStudioChevron, nowExpanded ? 180f : 0f);
                }
                prefs.edit().putBoolean("ai_studio_expanded", nowExpanded).apply();
            });
        }

        if (aiStudioBtnNewChat != null) {
            aiStudioBtnNewChat.setOnClickListener(v -> {
                Haptics.tick(v);
                handleAiNewChat();
            });
        }

        if (aiStudioBtnHistory != null) {
            aiStudioBtnHistory.setOnClickListener(v -> {
                Haptics.tick(v);
                showAiHistoryDialog();
            });
        }

        aiWebSearchEnabled = prefs.getBoolean("pref_ai_web_search", false);
        if (aiStudioWebSearchChip != null) {
            aiStudioWebSearchChip.setAlpha(aiWebSearchEnabled ? 1.0f : 0.5f);
            aiStudioWebSearchChip.setOnClickListener(v -> {
                Haptics.tick(v);
                aiWebSearchEnabled = !aiWebSearchEnabled;
                aiStudioWebSearchChip.setAlpha(aiWebSearchEnabled ? 1.0f : 0.5f);
                prefs.edit().putBoolean("pref_ai_web_search", aiWebSearchEnabled).apply();
                Toast.makeText(MainActivity.this,
                        getString(aiWebSearchEnabled ? R.string.ai_web_search_active : R.string.ai_web_search_inactive),
                        Toast.LENGTH_SHORT).show();
            });
        }

        setupAiQuickChips();

        if (aiStudioChatSend != null) {
            aiStudioChatSend.setOnClickListener(v -> {
                Haptics.tick(v);
                submitAiChatInput();
            });
        }

        if (aiStudioChatInput != null) {
            aiStudioChatInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submitAiChatInput();
                    return true;
                }
                return false;
            });
        }
    }

    private void setupAiQuickChips() {
        if (aiStudioQuickChipsContainer == null) return;
        aiStudioQuickChipsContainer.removeAllViews();
        String lang = LocaleHelper.getLanguage(this);
        List<SongChatManager.PromptTemplate> templates = SongChatManager.getPromptTemplates(lang);
        for (final SongChatManager.PromptTemplate template : templates) {
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackgroundResource(R.drawable.bg_prompt_card);
            int padH = dpToPx(10);
            int padV = dpToPx(6);
            chip.setPadding(padH, padV, padH, padV);

            ImageView iconView = new ImageView(this);
            int iconRes = getResources().getIdentifier(template.iconName, "drawable", getPackageName());
            if (iconRes != 0) {
                iconView.setImageResource(iconRes);
            } else {
                iconView.setImageResource(R.drawable.ic_sparkles);
            }
            iconView.setColorFilter(ContextCompat.getColor(this, R.color.accent));
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dpToPx(14), dpToPx(14));
            iconLp.setMarginEnd(dpToPx(6));
            chip.addView(iconView, iconLp);

            TextView titleView = new TextView(this);
            titleView.setText(template.title);
            titleView.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            titleView.setTextSize(11f);
            titleView.setTypeface(null, android.graphics.Typeface.BOLD);
            chip.addView(titleView);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dpToPx(8));
            chip.setLayoutParams(lp);

            chip.setOnClickListener(v -> {
                Haptics.tick(v);
                sendAiChatMessage(template.promptText);
            });
            aiStudioQuickChipsContainer.addView(chip);
        }
    }

    public static class ModelMeta {
        public final String id;
        public final String displayName;
        public final String description;
        public final int iconRes;

        public ModelMeta(String id, String displayName, String description, int iconRes) {
            this.id = id;
            this.displayName = displayName;
            this.description = description;
            this.iconRes = iconRes;
        }
    }

    private ModelMeta resolveModelMeta(String modelId) {
        if (modelId == null || modelId.trim().isEmpty()) {
            modelId = SongChatManager.DEFAULT_MODEL;
        }
        String id = modelId.trim();
        String mLower = id.toLowerCase(Locale.ROOT);

        int icon = mLower.contains("claude") ? R.drawable.ic_model_claude : R.drawable.ic_model_gemini;
        String name = id;
        String desc = "Google Antigravity";

        if (mLower.startsWith("gemini-3.8-flash")) {
            if (mLower.contains("high")) {
                name = "Gemini 3.8 Flash (High)";
                desc = "Extended Thinking · High Effort";
            } else if (mLower.contains("medium")) {
                name = "Gemini 3.8 Flash (Med)";
                desc = "Thinking · Balanced Reasoning";
            } else if (mLower.contains("low")) {
                name = "Gemini 3.8 Flash (Low)";
                desc = "Ultra Low Latency · Fast Reasoning";
            } else {
                name = "Gemini 3.8 Flash";
                desc = "Thinking · Adaptable Reasoning";
            }
        } else if (mLower.startsWith("gemini-3.7-flash")) {
            if (mLower.contains("high")) {
                name = "Gemini 3.7 Flash (High)";
                desc = "Extended Thinking · High Effort";
            } else if (mLower.contains("medium")) {
                name = "Gemini 3.7 Flash (Med)";
                desc = "Thinking · Balanced Reasoning";
            } else if (mLower.contains("low")) {
                name = "Gemini 3.7 Flash (Low)";
                desc = "Low Latency · Fast";
            } else {
                name = "Gemini 3.7 Flash";
                desc = "Thinking · Adaptable Reasoning";
            }
        } else if (mLower.startsWith("gemini-3.6-flash")) {
            if (mLower.contains("high")) {
                name = "Gemini 3.6 Flash (High)";
                desc = "Thinking · High Effort";
            } else if (mLower.contains("medium")) {
                name = "Gemini 3.6 Flash (Med)";
                desc = "Thinking · Balanced Reasoning";
            } else if (mLower.contains("low")) {
                name = "Gemini 3.6 Flash (Low)";
                desc = "Low Latency · Fast";
            } else {
                name = "Gemini 3.6 Flash";
                desc = "Thinking · Adaptable Reasoning";
            }
        } else if (mLower.startsWith("gemini-3.5-flash") || mLower.equals("gemini-3-flash-agent")) {
            name = mLower.contains("agent") ? "Gemini 3.5 Flash (High)" : (mLower.contains("extra-low") ? "Gemini 3.5 Flash (Low)" : "Gemini 3.5 Flash (Med)");
            desc = mLower.contains("agent") ? "Agentic Reasoning · High Effort" : (mLower.contains("extra-low") ? "Low Latency · Low Cost" : "Balanced Reasoning");
        } else if (mLower.startsWith("gemini-3-pro")) {
            if (mLower.contains("high")) {
                name = "Gemini 3 Pro (High)";
                desc = "Deep Reasoning · High Effort";
            } else {
                name = "Gemini 3 Pro (Low)";
                desc = "Pro Reasoning · Low Latency";
            }
        } else if (mLower.startsWith("gemini-3-flash")) {
            name = "Gemini 3 Flash";
            desc = "Fast & Balanced Reasoning";
        } else if (mLower.startsWith("gemini-pro-agent") || mLower.startsWith("gemini-3.1-pro")) {
            name = mLower.contains("agent") ? "Gemini 3.1 Pro (High)" : "Gemini 3.1 Pro (Low)";
            desc = mLower.contains("agent") ? "Deep Analysis · Agentic High Effort" : "Pro Reasoning · Low Latency";
        } else if (mLower.startsWith("gemini-3.1-flash-lite")) {
            name = "Gemini 3.1 Flash Lite";
            desc = "Ultra Lightweight · Instant Response";
        } else if (mLower.equals("gemini-2.5-pro")) {
            name = "Gemini 2.5 Pro";
            desc = "Complex Reasoning & Analysis";
        } else if (mLower.startsWith("gemini-2.5-flash")) {
            name = mLower.contains("thinking") ? "Gemini 2.5 Flash Thinking" : "Gemini 2.5 Flash";
            desc = "Fast & Efficient · High Quota";
        } else if (mLower.startsWith("claude-sonnet-4-6")) {
            name = "Claude Sonnet 4.6";
            desc = "Extended Thinking · Deep Understanding";
        } else if (mLower.startsWith("claude-opus-4-6")) {
            name = "Claude Opus 4.6";
            desc = "Maximum Intelligence · Extended Thinking";
        } else if (mLower.startsWith("claude-3-7-sonnet")) {
            name = "Claude 3.7 Sonnet";
            desc = "Hybrid Reasoning & Poetic Lyrics";
        } else if (mLower.startsWith("claude-3-5-sonnet")) {
            name = "Claude 3.5 Sonnet";
            desc = "Nuanced Lyrics Understanding";
        } else if (mLower.startsWith("gpt-oss")) {
            icon = R.drawable.ic_ai_sparkle;
            name = "GPT-OSS 120B";
            desc = "Open Weights · Medium Effort";
        }

        if (availableAiModels != null) {
            for (AntigravityModel am : availableAiModels) {
                if (am.id != null && am.id.equalsIgnoreCase(id)) {
                    if (am.displayName != null && !am.displayName.trim().isEmpty() && !am.displayName.equalsIgnoreCase("undefined")) {
                        name = am.displayName.trim();
                    }
                    break;
                }
            }
        }

        return new ModelMeta(id, name, desc, icon);
    }

    private void updateAiStudioModelUi(String modelId) {
        ModelMeta meta = resolveModelMeta(modelId);
        if (aiStudioModelPillIcon != null) {
            aiStudioModelPillIcon.setImageResource(meta.iconRes);
        }
        if (aiStudioModelPillName != null) {
            aiStudioModelPillName.setText(meta.displayName);
        }
        if (aiStudioModelDescBadge != null) {
            aiStudioModelDescBadge.setText(meta.description);
        }
    }

    private void showModelSelectorDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_model_selector, null);
        LinearLayout container = dialogView.findViewById(R.id.model_options_container);
        View btnCustom = dialogView.findViewById(R.id.btn_add_custom_model);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        String currentModel = prefs.getString("pref_ai_model", SongChatManager.DEFAULT_MODEL);

        List<ModelMeta> options = new ArrayList<>();
        Set<String> addedIds = new HashSet<>();

        // 1. Add curated base models (Gemini 3.8, 3.7, 3.6, 3.1, 2.5, Claude, GPT-OSS)
        for (String id : BASE_ANTIGRAVITY_MODELS) {
            options.add(resolveModelMeta(id));
            addedIds.add(id.toLowerCase(Locale.ROOT));
        }

        // 2. Add dynamically discovered models from Antigravity API
        if (availableAiModels != null) {
            for (AntigravityModel am : availableAiModels) {
                if (am.id != null && !addedIds.contains(am.id.toLowerCase(Locale.ROOT))) {
                    options.add(resolveModelMeta(am.id));
                    addedIds.add(am.id.toLowerCase(Locale.ROOT));
                }
            }
        }

        // 3. If currently selected model is custom, ensure it is shown
        if (!addedIds.contains(currentModel.toLowerCase(Locale.ROOT))) {
            options.add(0, resolveModelMeta(currentModel));
        }

        for (final ModelMeta opt : options) {
            View itemView = LayoutInflater.from(this).inflate(R.layout.item_model_option, container, false);
            ImageView iconView = itemView.findViewById(R.id.model_option_icon);
            TextView titleView = itemView.findViewById(R.id.model_option_title);
            TextView descView = itemView.findViewById(R.id.model_option_desc);
            ImageView checkView = itemView.findViewById(R.id.model_option_check);

            iconView.setImageResource(opt.iconRes);
            titleView.setText(opt.displayName);
            descView.setText(opt.description);

            boolean isSelected = opt.id.equalsIgnoreCase(currentModel);
            checkView.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            if (isSelected) {
                itemView.setBackgroundResource(R.drawable.bg_tip_box);
            } else {
                itemView.setBackgroundResource(R.drawable.bg_prompt_card);
            }

            itemView.setOnClickListener(v -> {
                Haptics.tick(v);
                prefs.edit().putString("pref_ai_model", opt.id).apply();
                if (songChatManager != null) {
                    songChatManager.setModelId(opt.id);
                }
                updateAiStudioModelUi(opt.id);
                dialog.dismiss();
            });

            container.addView(itemView);
        }

        if (btnCustom != null) {
            btnCustom.setOnClickListener(v -> {
                Haptics.tick(v);
                dialog.dismiss();
                showCustomModelInputDialog();
            });
        }

        View btnSearchModel = dialogView.findViewById(R.id.btn_select_search_model);
        TextView tvSearchModelSub = dialogView.findViewById(R.id.tv_dialog_search_model_sub);
        String currentSearchModel = prefs.getString("pref_ai_search_model", AntigravityToolExecutor.UNIFIED_SEARCH_MODEL);
        if (tvSearchModelSub != null) {
            if (AntigravityToolExecutor.UNIFIED_SEARCH_MODEL.equalsIgnoreCase(currentSearchModel)) {
                String activeChat = prefs.getString("pref_ai_model", SongChatManager.DEFAULT_MODEL);
                tvSearchModelSub.setText(getString(R.string.ai_search_model_unified_sub, activeChat));
            } else {
                tvSearchModelSub.setText(currentSearchModel);
            }
        }
        if (btnSearchModel != null) {
            btnSearchModel.setOnClickListener(v -> {
                Haptics.tick(v);
                showSearchModelSelectorDialog(tvSearchModelSub);
            });
        }

        dialog.show();
    }

    private void showSearchModelSelectorDialog(TextView tvSearchModelSub) {
        String activeChat = prefs.getString("pref_ai_model", SongChatManager.DEFAULT_MODEL);
        final String[] searchModelIds = {
                AntigravityToolExecutor.UNIFIED_SEARCH_MODEL,
                "gemini-2.5-flash",
                "gemini-3.8-flash-low",
                "gemini-3.7-flash-low",
                "gemini-3.1-flash-lite",
                "gemini-2.5-pro"
        };
        final String[] searchModelLabels = {
                getString(R.string.ai_search_model_unified) + " (" + activeChat + ")",
                "Gemini 2.5 Flash (Default Grounding)",
                "Gemini 3.8 Flash (Low Latency Grounding)",
                "Gemini 3.7 Flash (Fast Grounding)",
                "Gemini 3.1 Flash Lite (Ultra Fast Grounding)",
                "Gemini 2.5 Pro (Deep Grounding)"
        };

        String currentSearchModel = prefs.getString("pref_ai_search_model", AntigravityToolExecutor.UNIFIED_SEARCH_MODEL);
        int checkedItem = 0;
        for (int i = 0; i < searchModelIds.length; i++) {
            if (searchModelIds[i].equalsIgnoreCase(currentSearchModel)) {
                checkedItem = i;
                break;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.ai_search_model_label)
                .setSingleChoiceItems(searchModelLabels, checkedItem, (d, which) -> {
                    if (which >= 0 && which < searchModelIds.length) {
                        String chosen = searchModelIds[which];
                        prefs.edit().putString("pref_ai_search_model", chosen).apply();
                        AntigravityToolExecutor.setSearchModel(chosen);
                        if (tvSearchModelSub != null) {
                            if (AntigravityToolExecutor.UNIFIED_SEARCH_MODEL.equalsIgnoreCase(chosen)) {
                                tvSearchModelSub.setText(getString(R.string.ai_search_model_unified_sub, activeChat));
                            } else {
                                tvSearchModelSub.setText(chosen);
                            }
                        }
                        d.dismiss();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showCustomModelInputDialog() {
        final EditText input = new EditText(this);
        input.setHint(R.string.ai_custom_model_hint);
        input.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        input.setTextColor(getResources().getColor(R.color.text_primary));
        input.setHintTextColor(getResources().getColor(R.color.text_dim));

        new AlertDialog.Builder(this)
                .setTitle(R.string.ai_custom_model_title)
                .setView(input)
                .setPositiveButton("Simpan", (d, which) -> {
                    String customId = input.getText().toString().trim();
                    if (!customId.isEmpty()) {
                        prefs.edit().putString("pref_ai_model", customId).apply();
                        if (songChatManager != null) {
                            songChatManager.setModelId(customId);
                        }
                        updateAiStudioModelUi(customId);
                        Toast.makeText(this, "Model AI diatur ke: " + customId, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    public void onNowPlayingChanged(NowPlaying.Snapshot track) {
        if (track == null || !track.hasTrack || track.title == null || track.title.trim().isEmpty()) {
            if (currentTrackTitle != null) {
                currentTrackTitle = null;
                currentTrackArtist = null;
                if (activeMeaningCall != null) {
                    activeMeaningCall.cancel();
                    activeMeaningCall = null;
                }
                if (activeChatCall != null) {
                    activeChatCall.cancel();
                    activeChatCall = null;
                }
                if (songChatManager != null) {
                    songChatManager.setCurrentSong(null, null, null, null);
                }
                if (aiStudioSummary != null) {
                    aiStudioSummary.setText(R.string.ai_no_track);
                }
                if (aiStudioMoodBadge != null) {
                    aiStudioMoodBadge.setVisibility(View.GONE);
                }
                if (aiStudioWebSourcesScroll != null) {
                    aiStudioWebSourcesScroll.setVisibility(View.GONE);
                }
                if (aiStudioWebSourcesContainer != null) {
                    aiStudioWebSourcesContainer.removeAllViews();
                }
                if (aiStudioChatContainer != null) {
                    aiStudioChatContainer.removeAllViews();
                }
                if (aiStudioChatScroll != null) {
                    aiStudioChatScroll.setVisibility(View.GONE);
                }
                if (aiStudioTrackInfo != null) {
                    aiStudioTrackInfo.setVisibility(View.GONE);
                }
            }
            return;
        }

        if (aiStudioTrackInfo != null) {
            String artistStr = (track.artist != null && !track.artist.trim().isEmpty()) ? " · " + track.artist.trim() : "";
            aiStudioTrackInfo.setText(track.title.trim() + artistStr);
            aiStudioTrackInfo.setVisibility(View.VISIBLE);
        }

        boolean sameTrack = currentTrackTitle != null
                && currentTrackTitle.equals(track.title)
                && ((currentTrackArtist == null && track.artist == null)
                    || (currentTrackArtist != null && currentTrackArtist.equals(track.artist)));
        if (sameTrack) {
            return;
        }

        currentTrackTitle = track.title;
        currentTrackArtist = track.artist != null ? track.artist : "";

        if (activeMeaningCall != null) {
            activeMeaningCall.cancel();
            activeMeaningCall = null;
        }
        if (activeChatCall != null) {
            activeChatCall.cancel();
            activeChatCall = null;
        }

        if (songChatManager != null) {
            songChatManager.setAppLanguage(LocaleHelper.getLanguage(this));
            songChatManager.setCurrentSong(currentTrackTitle, currentTrackArtist, "", null);
        }

        if (aiStudioChatContainer != null) {
            aiStudioChatContainer.removeAllViews();
        }
        if (aiStudioChatScroll != null) {
            aiStudioChatScroll.setVisibility(View.GONE);
        }
        if (aiStudioWebSourcesContainer != null) {
            aiStudioWebSourcesContainer.removeAllViews();
        }
        if (aiStudioWebSourcesScroll != null) {
            aiStudioWebSourcesScroll.setVisibility(View.GONE);
        }

        if (aiStudioSummary != null) {
            aiStudioSummary.setText(R.string.ai_empty_hint);
        }
        setupAiQuickChips();
    }

    public void onNowPlayingChanged(NowPlaying track) {
        onNowPlayingChanged(NowPlaying.current());
    }

    private void fetchSongMeaning(final String title, final String artist) {
        if (authManager == null) {
            authManager = AntigravityAuthManager.getInstance(this);
        }
        if (!authManager.isAuthenticated()) {
            if (aiStudioSummary != null) {
                aiStudioSummary.setText(R.string.ai_login_required);
            }
            if (aiStudioMoodBadge != null) {
                aiStudioMoodBadge.setVisibility(View.GONE);
            }
            return;
        }

        if (songMeaningService == null) return;

        if (aiStudioSummary != null) {
            aiStudioSummary.setText(CleanMarkdownFormatter.formatClean(getString(R.string.ai_thinking)));
        }
        if (aiStudioMoodBadge != null) {
            aiStudioMoodBadge.setVisibility(View.GONE);
        }

        activeMeaningCall = songMeaningService.getSongMeaning(
                title,
                artist,
                "",
                aiWebSearchEnabled,
                new SongMeaningService.MeaningCallback() {
                    @Override
                    public void onResult(SongMeaning meaning, boolean fromCache) {
                        runOnUiThread(() -> {
                            activeMeaningCall = null;
                            if (isFinishing() || isDestroyed()) return;
                            if (title == null || !title.equals(currentTrackTitle)) return;
                            if (meaning == null) return;

                            if (meaning.mood != null && !meaning.mood.trim().isEmpty()) {
                                if (aiStudioMoodBadge != null) {
                                    aiStudioMoodBadge.setText(meaning.mood.trim());
                                    aiStudioMoodBadge.setVisibility(View.VISIBLE);
                                }
                            }
                            if (aiStudioSummary != null && meaning.summary != null) {
                                aiStudioSummary.setText(CleanMarkdownFormatter.formatClean(meaning.summary));
                            }
                            if (meaning.webSources != null && !meaning.webSources.isEmpty()) {
                                displayWebSources(meaning.webSources);
                            }
                            if (songChatManager != null && title.equals(currentTrackTitle)) {
                                songChatManager.setCurrentSong(title, artist, "", meaning);
                            }
                        });
                    }

                    @Override
                    public void onError(Exception e) {
                        runOnUiThread(() -> {
                            activeMeaningCall = null;
                            if (isFinishing() || isDestroyed()) return;
                            if (title == null || !title.equals(currentTrackTitle)) return;
                            if (aiStudioSummary != null) {
                                aiStudioSummary.setText(CleanMarkdownFormatter.formatClean(
                                        "Error: " + (e != null ? e.getMessage() : "Failed to analyze")));
                            }
                        });
                    }
                });
    }

    private void displayWebSources(List<String> sources) {
        if (aiStudioWebSourcesContainer == null || aiStudioWebSourcesScroll == null) return;
        aiStudioWebSourcesContainer.removeAllViews();
        if (sources == null || sources.isEmpty()) {
            aiStudioWebSourcesScroll.setVisibility(View.GONE);
            return;
        }
        aiStudioWebSourcesScroll.setVisibility(View.VISIBLE);

        TextView label = new TextView(this);
        label.setText(getString(R.string.ai_web_sources_label) + ": ");
        label.setTextColor(ContextCompat.getColor(this, R.color.text_dim));
        label.setTextSize(10f);
        label.setPadding(0, 0, dpToPx(4), 0);
        aiStudioWebSourcesContainer.addView(label);

        Set<String> seen = new HashSet<>();
        int added = 0;
        for (String source : sources) {
            if (added >= 5) break;
            CleanMarkdownFormatter.SourceChipData chipData = CleanMarkdownFormatter.resolveSourceChip(source, this);
            if (chipData == null) continue;
            String key = chipData.displayLabel.toLowerCase(Locale.ROOT);
            if (seen.contains(key)) continue;
            seen.add(key);

            final String url = chipData.targetUrl;
            TextView chip = new TextView(this);
            chip.setText(chipData.displayLabel);
            chip.setTextColor(ContextCompat.getColor(this, R.color.accent));
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
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, getString(R.string.ai_link_error, url), Toast.LENGTH_SHORT).show();
                }
            });
            aiStudioWebSourcesContainer.addView(chip);
            added++;
        }
    }

    private void submitAiChatInput() {
        if (aiStudioChatInput == null) return;
        String text = aiStudioChatInput.getText().toString().trim();
        if (text.isEmpty()) return;
        aiStudioChatInput.setText("");
        sendAiChatMessage(text);
    }

    private void sendAiChatMessage(String userQuery) {
        if (songChatManager == null || userQuery == null || userQuery.trim().isEmpty()) return;

        if (authManager == null || !authManager.isAuthenticated()) {
            Toast.makeText(this, getString(R.string.ai_login_required), Toast.LENGTH_SHORT).show();
            return;
        }

        if (aiStudioChatScroll != null) {
            aiStudioChatScroll.setVisibility(View.VISIBLE);
        }

        addUserChatBubble(userQuery);

        final TextView aiBubble = addAiChatBubble("");
        if (activeStudioAnimator != null) {
            activeStudioAnimator.stopAll();
        }
        activeStudioAnimator = new AiStreamingAnimator(aiBubble, ContextCompat.getColor(this, R.color.accent));
        activeStudioAnimator.startThinking(getString(R.string.ai_thinking));

        if (activeChatCall != null) {
            activeChatCall.cancel();
            activeChatCall = null;
        }

        String currentModel = prefs.getString("pref_ai_model", SongChatManager.DEFAULT_MODEL);
        songChatManager.setModelId(currentModel);

        final StringBuilder responseAcc = new StringBuilder();
        activeChatCall = songChatManager.sendMessage(userQuery, aiWebSearchEnabled,
                new AntigravityApiClient.StreamListener() {
                    @Override
                    public void onToolExecuting(String toolName, String queryOrUrl) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed() || aiStudioToolStatusContainer == null) return;
                            cancelPendingToolStatusHide();
                            if ("web_fetch".equals(toolName)) {
                                if (aiStudioToolStatusIcon != null) {
                                    aiStudioToolStatusIcon.setImageResource(R.drawable.ic_search_globe);
                                }
                                String host = queryOrUrl;
                                try {
                                    Uri uri = Uri.parse(queryOrUrl);
                                    if (uri.getHost() != null) host = uri.getHost();
                                } catch (Exception ignored) {}
                                if (aiStudioToolStatusText != null) {
                                    aiStudioToolStatusText.setText(getString(R.string.ai_tool_fetching) + (host != null ? " (" + host + ")" : ""));
                                }
                            } else {
                                if (aiStudioToolStatusIcon != null) {
                                    aiStudioToolStatusIcon.setImageResource(R.drawable.ic_search);
                                }
                                String query = queryOrUrl != null ? queryOrUrl : "";
                                if (query.length() > 50) query = query.substring(0, 50) + "…";
                                if (aiStudioToolStatusText != null) {
                                    aiStudioToolStatusText.setText(getString(R.string.ai_tool_searching) + " \"" + query + "\"");
                                }
                            }
                            aiStudioToolStatusContainer.setAlpha(0f);
                            aiStudioToolStatusContainer.setVisibility(View.VISIBLE);
                            aiStudioToolStatusContainer.animate().alpha(1f).setDuration(220).start();
                        });
                    }

                    @Override
                    public void onToolCompleted(String toolName, int resultsCount) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed() || aiStudioToolStatusContainer == null) return;
                            if (aiStudioToolStatusIcon != null) {
                                aiStudioToolStatusIcon.setImageResource(R.drawable.ic_check);
                            }
                            if (aiStudioToolStatusText != null) {
                                if ("web_fetch".equals(toolName)) {
                                    aiStudioToolStatusText.setText(getString(R.string.ai_tool_fetch_done));
                                } else {
                                    aiStudioToolStatusText.setText(getString(R.string.ai_tool_search_done, resultsCount));
                                }
                            }
                            scheduleToolStatusHide();
                        });
                    }

                    @Override
                    public void onDelta(String textDelta) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            responseAcc.append(textDelta);
                            if (activeStudioAnimator != null) {
                                activeStudioAnimator.updateStream(responseAcc.toString());
                            }
                            if (aiStudioChatScroll != null) {
                                aiStudioChatScroll.post(() -> aiStudioChatScroll.fullScroll(View.FOCUS_DOWN));
                            }
                        });
                    }

                    @Override
                    public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                        runOnUiThread(() -> {
                            activeChatCall = null;
                            if (isFinishing() || isDestroyed()) return;
                            hideToolStatusNow();
                            String finalContent = (fullText != null && !fullText.trim().isEmpty())
                                    ? fullText : responseAcc.toString();
                            if (activeStudioAnimator != null) {
                                activeStudioAnimator.complete(finalContent);
                                activeStudioAnimator = null;
                            }
                            if (webSources != null && !webSources.isEmpty()) {
                                displayWebSources(webSources);
                            }
                            addSourceChipsBelowBubble(aiBubble, webSources);
                            if (aiStudioChatScroll != null) {
                                aiStudioChatScroll.post(() -> aiStudioChatScroll.fullScroll(View.FOCUS_DOWN));
                            }
                        });
                    }

                    @Override
                    public void onError(Exception error) {
                        runOnUiThread(() -> {
                            activeChatCall = null;
                            if (isFinishing() || isDestroyed()) return;
                            hideToolStatusNow();
                            if (activeStudioAnimator != null) {
                                activeStudioAnimator.error(error != null ? error.getMessage() : "Unknown error");
                                activeStudioAnimator = null;
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
        if (aiStudioToolStatusContainer != null) {
            aiStudioToolStatusContainer.setVisibility(View.GONE);
        }
    }

    /** Clickable web-source chips appended below the last AI chat bubble (max 5). */
    private void addSourceChipsBelowBubble(TextView aiBubble, List<String> webSources) {
        if (aiStudioChatContainer == null || webSources == null) return;
        int added = 0;
        Set<String> seen = new HashSet<>();
        for (String source : webSources) {
            if (added >= 5) break;
            CleanMarkdownFormatter.SourceChipData chipData = CleanMarkdownFormatter.resolveSourceChip(source, this);
            if (chipData == null) continue;
            String key = chipData.displayLabel.toLowerCase(Locale.ROOT);
            if (seen.contains(key)) continue;
            seen.add(key);

            final String url = chipData.targetUrl;

            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackgroundResource(R.drawable.bg_prompt_card);
            chip.setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3));

            ImageView linkIcon = new ImageView(this);
            linkIcon.setImageResource(R.drawable.ic_link_source);
            linkIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent));
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dpToPx(11), dpToPx(11));
            iconLp.setMarginEnd(dpToPx(4));
            chip.addView(linkIcon, iconLp);

            TextView text = new TextView(this);
            text.setText(chipData.displayLabel);
            text.setTextColor(ContextCompat.getColor(this, R.color.accent));
            text.setTextSize(10f);
            text.setMaxLines(1);
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.addView(text);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.START;
            lp.setMargins(0, dpToPx(2), dpToPx(32), dpToPx(2));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                Haptics.tick(v);
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, getString(R.string.ai_link_error, url), Toast.LENGTH_SHORT).show();
                }
            });
            aiStudioChatContainer.addView(chip);
            added++;
        }
    }

    private void addUserChatBubble(String text) {
        if (aiStudioChatContainer == null) return;
        TextView bubble = new TextView(this);
        bubble.setText(CleanMarkdownFormatter.formatClean(text));
        bubble.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        bubble.setTextSize(12f);
        bubble.setBackgroundResource(R.drawable.bg_chat_bubble_user);
        bubble.setPadding(dpToPx(12), dpToPx(7), dpToPx(12), dpToPx(7));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.setMargins(dpToPx(36), dpToPx(4), 0, dpToPx(4));
        bubble.setLayoutParams(lp);

        aiStudioChatContainer.addView(bubble);
        if (aiStudioChatScroll != null) {
            aiStudioChatScroll.post(() -> aiStudioChatScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private TextView addAiChatBubble(String initialText) {
        if (aiStudioChatContainer == null) return null;
        TextView bubble = new TextView(this);
        bubble.setText(CleanMarkdownFormatter.formatClean(initialText));
        bubble.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        bubble.setTextSize(12f);
        bubble.setBackgroundResource(R.drawable.bg_chat_bubble_assistant);
        bubble.setPadding(dpToPx(12), dpToPx(7), dpToPx(12), dpToPx(7));
        bubble.setMovementMethod(LinkMovementMethod.getInstance());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.START;
        lp.setMargins(0, dpToPx(4), dpToPx(36), dpToPx(4));
        bubble.setLayoutParams(lp);

        aiStudioChatContainer.addView(bubble);
        if (aiStudioChatScroll != null) {
            aiStudioChatScroll.post(() -> aiStudioChatScroll.fullScroll(View.FOCUS_DOWN));
        }
        return bubble;
    }

    private void handleAiNewChat() {
        if (activeChatCall != null) {
            try {
                activeChatCall.cancel();
            } catch (Exception ignored) {}
            activeChatCall = null;
        }
        if (activeStudioAnimator != null) {
            activeStudioAnimator.stopAll();
            activeStudioAnimator = null;
        }
        hideToolStatusNow();

        if (songChatManager != null) {
            songChatManager.startNewSession();
        }

        if (aiStudioChatContainer != null) {
            aiStudioChatContainer.removeAllViews();
        }
        if (aiStudioChatScroll != null) {
            aiStudioChatScroll.setVisibility(View.GONE);
        }
        if (aiStudioQuickChips != null) {
            aiStudioQuickChips.setVisibility(View.VISIBLE);
        }
        if (aiStudioChatInput != null) {
            aiStudioChatInput.setText("");
        }
        Toast.makeText(this, R.string.ai_new_chat_started, Toast.LENGTH_SHORT).show();
    }

    private void restoreChatSession(AiChatHistoryManager.ChatSession session) {
        if (session == null || songChatManager == null) return;
        if (activeChatCall != null) {
            try {
                activeChatCall.cancel();
            } catch (Exception ignored) {}
            activeChatCall = null;
        }
        if (activeStudioAnimator != null) {
            activeStudioAnimator.stopAll();
            activeStudioAnimator = null;
        }
        hideToolStatusNow();

        songChatManager.loadSession(session);

        if (aiStudioChatContainer != null) {
            aiStudioChatContainer.removeAllViews();
            if (aiStudioChatScroll != null) {
                aiStudioChatScroll.setVisibility(View.VISIBLE);
            }
            if (aiStudioQuickChips != null) {
                aiStudioQuickChips.setVisibility(View.GONE);
            }

            if (session.messages != null) {
                for (AiChatMessage msg : session.messages) {
                    if (msg == null || msg.content == null || msg.content.trim().isEmpty()) continue;
                    boolean isUser = "user".equalsIgnoreCase(msg.role);
                    if (isUser) {
                        addUserChatBubble(msg.content);
                    } else {
                        TextView bubble = addAiChatBubble("");
                        CleanMarkdownFormatter.formatClean(msg.content, false, bubble);
                    }
                }
            }

            if (session.webSources != null && !session.webSources.isEmpty()) {
                addSourceChipsBelowBubble(null, session.webSources);
            }

            if (aiStudioChatScroll != null) {
                aiStudioChatScroll.post(() -> aiStudioChatScroll.fullScroll(View.FOCUS_DOWN));
            }
        }
        Toast.makeText(this, R.string.ai_history_restore, Toast.LENGTH_SHORT).show();
    }

    private void showAiHistoryDialog() {
        if (aiChatHistoryManager == null) {
            aiChatHistoryManager = new AiChatHistoryManager(this);
        }

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_ai_chat_history, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
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
                        View itemView = LayoutInflater.from(MainActivity.this)
                                .inflate(R.layout.item_ai_history_session, sessionsContainer, false);

                        TextView tvTitle = itemView.findViewById(R.id.tv_history_song_title);
                        TextView tvArtistDate = itemView.findViewById(R.id.tv_history_artist_date);
                        TextView tvSnippet = itemView.findViewById(R.id.tv_history_snippet);
                        TextView tvCount = itemView.findViewById(R.id.tv_history_message_count);
                        ImageView btnDelete = itemView.findViewById(R.id.btn_delete_history_session);

                        String titleText = (session.songTitle != null && !session.songTitle.trim().isEmpty())
                                ? session.songTitle.trim() : getString(R.string.ai_studio_title);
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
                            restoreChatSession(session);
                        });

                        if (btnDelete != null) {
                            btnDelete.setOnClickListener(v -> {
                                Haptics.tick(v);
                                aiChatHistoryManager.deleteSession(session.id);
                                Toast.makeText(MainActivity.this, R.string.ai_history_deleted_one, Toast.LENGTH_SHORT).show();
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
                new AlertDialog.Builder(this)
                        .setTitle(R.string.ai_history_clear_confirm_title)
                        .setMessage(R.string.ai_history_clear_confirm_msg)
                        .setPositiveButton(R.string.ai_history_clear_all, (d, which) -> {
                            aiChatHistoryManager.clearAllSessions();
                            Toast.makeText(MainActivity.this, R.string.ai_history_clear_success, Toast.LENGTH_SHORT).show();
                            refreshList.run();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }

        dialog.show();
    }

    /** Re-colour the ambient header wash. SRC_IN tinting keeps the gradient's alpha ramp. */
    private void tintHero(int accent) {
        if (heroGlow == null || accent == heroAccent) return;
        if (heroTintAnimator != null) heroTintAnimator.cancel();
        final int from = heroAccent;
        heroAccent = accent;
        heroTintAnimator = Anim.color(from, accent, 700, color -> {
            Drawable bg = heroGlow.getBackground();
            if (bg == null) return;
            Drawable mutated = bg.mutate();
            DrawableCompat.setTint(mutated, color);
            heroGlow.setBackground(mutated);
            setEqualizerColor(color);
        });
    }

    private void setEqualizerColor(int color) {
        tintBar(R.id.eq_bar_1, color);
        tintBar(R.id.eq_bar_2, color);
        tintBar(R.id.eq_bar_3, color);
    }

    private void tintBar(int id, int color) {
        View bar = findViewById(id);
        if (bar == null) return;
        Drawable bg = bar.getBackground();
        if (bg == null) return;
        Drawable mutated = bg.mutate();
        DrawableCompat.setTint(mutated, color);
        bar.setBackground(mutated);
    }

    private void startEqualizer() {
        if (eqAnimators != null) return;
        View[] bars = {
                findViewById(R.id.eq_bar_1),
                findViewById(R.id.eq_bar_2),
                findViewById(R.id.eq_bar_3)
        };
        float[] floors = {0.30f, 0.62f, 0.42f};
        long[] durations = {430, 560, 350};

        eqAnimators = new ObjectAnimator[bars.length];
        for (int i = 0; i < bars.length; i++) {
            final View bar = bars[i];
            if (bar == null) continue;
            // Bars grow from the baseline, so the pivot must sit at the bottom edge.
            bar.post(() -> bar.setPivotY(bar.getHeight()));
            ObjectAnimator a = ObjectAnimator.ofFloat(bar, View.SCALE_Y, floors[i], 1f);
            a.setDuration(durations[i]);
            a.setRepeatMode(ValueAnimator.REVERSE);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setInterpolator(new AccelerateDecelerateInterpolator());
            a.start();
            eqAnimators[i] = a;
        }
    }

    private void stopEqualizer() {
        if (eqAnimators == null) return;
        for (ObjectAnimator a : eqAnimators) {
            if (a != null) a.cancel();
        }
        eqAnimators = null;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static int clamp(int value, int lo, int hi) {
        return value < lo ? lo : (value > hi ? hi : value);
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}
