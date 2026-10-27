package com.lyricsync.app;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.lyricsync.app.ai.service.SongChatManager;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor;
import com.lyricsync.app.overlay.FloatingOverlayService;
import com.lyricsync.app.ui.Anim;
import com.lyricsync.app.ui.main.AiAccountSection;
import com.lyricsync.app.ui.main.AppearanceSection;
import com.lyricsync.app.ui.main.DeveloperSection;
import com.lyricsync.app.ui.main.HeroSection;
import com.lyricsync.app.ui.main.LanguageSection;
import com.lyricsync.app.ui.main.LyricsApiSection;
import com.lyricsync.app.ui.main.MainPrefs;
import com.lyricsync.app.ui.main.ModelCatalog;
import com.lyricsync.app.ui.main.PermissionsSection;
import com.lyricsync.app.ui.main.SyncSection;
import com.lyricsync.app.ui.main.TranslationSection;
import com.lyricsync.app.util.AppLog;
import com.lyricsync.app.util.Haptics;
import com.lyricsync.app.util.LocaleHelper;
import com.lyricsync.app.util.NowPlaying;

import java.util.List;

/**
 * Main screen host. Each card is owned by a section controller in {@code ui.main};
 * this class wires lifecycle, insets, the entrance and the start/stop flow.
 * Design spec: LyricSync-docs/design/main-ui-spec.md.
 */
public class MainActivity extends AppCompatActivity implements NowPlaying.Listener {
    private static final String TAG = "MainActivity";
    private static final int REQ_POST_NOTIFICATIONS = 42;
    /** The service flips its running flag asynchronously; re-read this long after start/stop. */
    private static final long SERVICE_STATE_RECHECK_MS = 350;

    /** Curated Antigravity models (kept public for existing callers; owned by ModelCatalog). */
    public static final List<String> BASE_ANTIGRAVITY_MODELS = ModelCatalog.BASE_MODELS;

    /** Kept for API compatibility; the picker now uses {@link ModelCatalog.Entry}. */
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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private HeroSection hero;
    private PermissionsSection permissions;
    private AiAccountSection ai;
    private DeveloperSection developer;
    private boolean entrancePlayed;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(MainPrefs.FILE, MODE_PRIVATE);
        AntigravityToolExecutor.setSearchModel(
                prefs.getString(MainPrefs.AI_SEARCH_MODEL, AntigravityToolExecutor.UNIFIED_SEARCH_MODEL));
        AntigravityToolExecutor.setChatModelSupplier(() ->
                prefs.getString(MainPrefs.AI_MODEL, SongChatManager.DEFAULT_MODEL));

        applyWindowInsets();
        hero = new HeroSection(this);
        permissions = new PermissionsSection(this);
        new AppearanceSection(this, prefs);
        new SyncSection(this, prefs);
        new TranslationSection(this, prefs);
        new LyricsApiSection(this, prefs);
        ai = new AiAccountSection(this, prefs);
        new LanguageSection(this);
        developer = new DeveloperSection(this, prefs, handler);
        setupStartButton();
        setupPermissionRows();
        showVersion();

        AppLog.i(TAG, "App started");
    }

    @Override
    protected void onResume() {
        super.onResume();
        permissions.refresh();
        hero.updateRunning(FloatingOverlayService.isRunning());
        ai.refresh();
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
        hero.stopEqualizer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        developer.release();
        handler.removeCallbacksAndMessages(null);
        hero.release();
        ai.release();
    }

    // ── Setup ──────────────────────────────────────────────────────────────

    /** Draw behind the system bars, then pad the scrolling content back into view. */
    private void applyWindowInsets() {
        final View content = findViewById(R.id.main_content);
        final View scrim = findViewById(R.id.status_scrim);
        final int extraBottom = getResources().getDimensionPixelSize(R.dimen.ls_space_8);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), bars.top, v.getPaddingRight(), bars.bottom + extraBottom);
            ViewGroup.LayoutParams lp = scrim.getLayoutParams();
            if (lp.height != bars.top) {
                lp.height = bars.top;
                scrim.setLayoutParams(lp);
            }
            return insets;
        });

        // The ambient glow belongs to the header: let it scroll away with the content.
        final View glow = findViewById(R.id.hero_glow);
        findViewById(R.id.main_scroll).setOnScrollChangeListener((v, x, y, oldX, oldY) ->
                glow.setTranslationY(-Math.min(y, glow.getHeight())));
    }

    private void playEntrance() {
        // Respect "Remove animations": no fade-in at all rather than a frozen invisible screen.
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        Anim.enterStagger(40, 45,
                findViewById(R.id.header),
                hero.card(),
                permissions.card().getVisibility() == View.VISIBLE ? permissions.card() : null,
                findViewById(R.id.section_appearance),
                findViewById(R.id.card_appearance),
                findViewById(R.id.card_dimensions),
                findViewById(R.id.section_lyrics),
                findViewById(R.id.card_sync),
                findViewById(R.id.card_translation),
                findViewById(R.id.card_lyrics_api),
                findViewById(R.id.section_ai),
                findViewById(R.id.card_ai_account),
                findViewById(R.id.section_more),
                findViewById(R.id.card_language),
                findViewById(R.id.card_dev));

        View glow = findViewById(R.id.hero_glow);
        glow.setAlpha(0f);
        glow.animate().alpha(1f).setDuration(900).setInterpolator(Anim.DECEL).start();
    }

    private void setupPermissionRows() {
        findViewById(R.id.btn_notification_access).setOnClickListener(v -> {
            Haptics.tick(v);
            startSettings(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        });
        findViewById(R.id.btn_overlay_permission).setOnClickListener(v -> {
            Haptics.tick(v);
            startSettings(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        });
    }

    private void setupStartButton() {
        hero.startButton().setOnClickListener(v -> {
            Haptics.confirm(v);
            if (FloatingOverlayService.isRunning()) {
                stopService(new Intent(this, FloatingOverlayService.class));
                Toast.makeText(this, R.string.overlay_stopped, Toast.LENGTH_SHORT).show();
            } else {
                if (!permissions.allGranted()) {
                    Toast.makeText(this, R.string.grant_permissions_first, Toast.LENGTH_SHORT).show();
                    permissions.nudge();
                    return;
                }
                requestPostNotificationsIfNeeded();
                startForegroundService(new Intent(this, FloatingOverlayService.class));
                Toast.makeText(this, R.string.overlay_started, Toast.LENGTH_SHORT).show();
            }
            handler.postDelayed(() -> hero.updateRunning(FloatingOverlayService.isRunning()),
                    SERVICE_STATE_RECHECK_MS);
        });
    }

    private void showVersion() {
        TextView version = findViewById(R.id.tv_version);
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            version.setText(getString(R.string.main_version, info.versionName));
        } catch (PackageManager.NameNotFoundException e) {
            version.setVisibility(View.GONE);
        }
    }

    private void startSettings(Intent intent) {
        try {
            startActivity(intent);
        } catch (Exception e) {
            AppLog.e(TAG, "Cannot open settings screen: " + e.getMessage());
            Toast.makeText(this, R.string.settings_open_failed, Toast.LENGTH_SHORT).show();
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
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissionsArr,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissionsArr, grantResults);
        if (requestCode == REQ_POST_NOTIFICATIONS) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            AppLog.i(TAG, "POST_NOTIFICATIONS " + (granted ? "granted" : "denied"));
        }
    }

    // ── Now playing ────────────────────────────────────────────────────────

    @Override
    public void onNowPlaying(NowPlaying.Snapshot snapshot) {
        if (isFinishing() || isDestroyed()) return;
        hero.onNowPlaying(snapshot);
    }
}
