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
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

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
import com.lyricsync.app.util.NowPlaying;
import com.lyricsync.app.util.Permissions;
import com.lyricsync.app.util.SeekBars;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements NowPlaying.Listener {
    private static final String TAG = "MainActivity";
    private static final int REQ_POST_NOTIFICATIONS = 42;

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

        initViews();
        applyWindowInsets();
        setupListeners();
        setupSliders();
        setupAppearanceChips();
        setupDeveloperSection();
        setupLogViewer();

        AppLog.i(TAG, "App started");
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
        updateStartButton();
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
                findViewById(R.id.card_permissions),
                findViewById(R.id.card_appearance),
                findViewById(R.id.card_sync),
                startButton,
                findViewById(R.id.card_dev));

        heroGlow.setAlpha(0f);
        heroGlow.animate().alpha(1f).setDuration(900).setInterpolator(Anim.DECEL).start();
    }

    private void setupListeners() {
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

    private void updatePermissionStatus() {
        boolean notifGranted = Permissions.isNotificationListenerEnabled(this);
        boolean overlayGranted = Settings.canDrawOverlays(this);

        boolean notifChanged = permissionStateKnown && notifGranted != lastNotificationGranted;
        boolean overlayChanged = permissionStateKnown && overlayGranted != lastOverlayGranted;

        applyPill(notificationPill, notificationPillIcon, notificationStatus, notifGranted, notifChanged);
        applyPill(overlayPill, overlayPillIcon, overlayStatus, overlayGranted, overlayChanged);

        permissionsSummary.setText(notifGranted && overlayGranted
                ? R.string.permissions_all_set : R.string.permissions_needed);
        permissionsSummary.setTextColor(ContextCompat.getColor(this,
                notifGranted && overlayGranted ? R.color.state_ok : R.color.text_dim));

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
