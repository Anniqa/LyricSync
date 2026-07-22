package com.lyricsync.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.lyricsync.app.util.SeekBars;

import com.google.android.material.button.MaterialButton;
import com.lyricsync.app.overlay.FloatingOverlayService;
import com.lyricsync.app.util.AppLog;
import com.lyricsync.app.util.Permissions;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private TextView notificationStatus;
    private TextView overlayStatus;
    private TextView currentTrackInfo;
    private MaterialButton startButton;

    private TextView logText;
    private ScrollView logScroll;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupListeners();
        setupLogViewer();

        AppLog.i(TAG, "App started");
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppLog.setListener(null);
    }

    private void initViews() {
        notificationStatus = findViewById(R.id.notification_status);
        overlayStatus = findViewById(R.id.overlay_status);
        currentTrackInfo = findViewById(R.id.current_track_info);
        startButton = findViewById(R.id.btn_start);
        logText = findViewById(R.id.log_text);
        logScroll = findViewById(R.id.log_scroll);
        setupFontSizeSlider();
        setupOverlaySizeSliders();
        setupSyncOffsetSlider();
    }

    private void setupFontSizeSlider() {
        SeekBar slider = findViewById(R.id.font_size_slider);
        TextView label = findViewById(R.id.font_size_label);
        SharedPreferences prefs = getSharedPreferences("lyricsync", MODE_PRIVATE);
        int savedProgress = Math.max(50, Math.min(200, (int) (prefs.getFloat("font_scale", 1.0f) * 100)));
        label.setText(savedProgress + "%");

        SeekBars.bind(slider, savedProgress, progress -> {
            progress = Math.max(50, Math.min(200, progress));
            label.setText(progress + "%");
            prefs.edit().putFloat("font_scale", progress / 100f).apply();
        });
    }

    private void setupOverlaySizeSliders() {
        SharedPreferences prefs = getSharedPreferences("lyricsync", MODE_PRIVATE);

        SeekBar widthSlider = findViewById(R.id.overlay_width_slider);
        TextView widthLabel = findViewById(R.id.overlay_width_label);
        int widthPercent = Math.max(55, Math.min(100, prefs.getInt("overlay_width_percent", 88)));
        widthLabel.setText("Width: " + widthPercent + "%");
        SeekBars.bind(widthSlider, widthPercent, progress -> {
            progress = Math.max(55, Math.min(100, progress));
            widthLabel.setText("Width: " + progress + "%");
            prefs.edit().putInt("overlay_width_percent", progress).apply();
        });

        SeekBar heightSlider = findViewById(R.id.overlay_height_slider);
        TextView heightLabel = findViewById(R.id.overlay_height_label);
        int heightPercent = Math.max(20, Math.min(70, prefs.getInt("overlay_height_percent", 36)));
        heightLabel.setText("Height: " + heightPercent + "%");
        SeekBars.bind(heightSlider, heightPercent, progress -> {
            progress = Math.max(20, Math.min(70, progress));
            heightLabel.setText("Height: " + progress + "%");
            prefs.edit().putInt("overlay_height_percent", progress).apply();
        });
    }

    private void setupSyncOffsetSlider() {
        SeekBar slider = findViewById(R.id.sync_offset_slider);
        TextView label = findViewById(R.id.sync_offset_label);
        SharedPreferences prefs = getSharedPreferences("lyricsync", MODE_PRIVATE);
        int saved = (int) prefs.getLong("sync_offset_ms", 0);
        saved = Math.max(-1500, Math.min(1500, saved));
        label.setText(saved + " ms (lyrics " + (saved >= 0 ? "later" : "earlier") + ")");

        SeekBars.bind(slider, saved + 1500, progress -> {
            int offset = progress - 1500;
            offset = Math.max(-1500, Math.min(1500, offset));
            label.setText(offset + " ms (lyrics " + (offset >= 0 ? "later" : "earlier") + ")");
            prefs.edit().putLong("sync_offset_ms", offset).apply();
        });
    }

    private void setupListeners() {
        findViewById(R.id.btn_notification_access).setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        });

        findViewById(R.id.btn_overlay_permission).setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });

        startButton.setOnClickListener(v -> {
            if (!checkPermissions()) {
                Toast.makeText(this, "Please grant all permissions first", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent serviceIntent = new Intent(this, FloatingOverlayService.class);
            startForegroundService(serviceIntent);
            Toast.makeText(this, "Lyrics overlay started", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_clear_logs).setOnClickListener(v -> {
            AppLog.clear();
            logText.setText("");
            AppLog.i(TAG, "Logs cleared");
        });

        findViewById(R.id.btn_share_logs).setOnClickListener(v -> shareLogs());
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
            startActivity(Intent.createChooser(shareIntent, "Share logs"));

            AppLog.i(TAG, "Logs shared");
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to share logs: " + e.getMessage());
            Toast.makeText(this, "Failed to share logs", Toast.LENGTH_SHORT).show();
        }
    }

    private void updatePermissionStatus() {
        boolean notifEnabled = Permissions.isNotificationListenerEnabled(this);
        boolean overlayEnabled = Settings.canDrawOverlays(this);

        notificationStatus.setText(notifEnabled ? "Enabled" : "Disabled");
        notificationStatus.setTextColor(notifEnabled ? 0xFF1ED760 : 0xFFFF4444);

        overlayStatus.setText(overlayEnabled ? "Enabled" : "Disabled");
        overlayStatus.setTextColor(overlayEnabled ? 0xFF1ED760 : 0xFFFF4444);
    }

    private boolean checkPermissions() {
        return Permissions.isNotificationListenerEnabled(this) && Settings.canDrawOverlays(this);
    }
}
