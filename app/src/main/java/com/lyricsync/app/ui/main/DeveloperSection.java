package com.lyricsync.app.ui.main;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.lyricsync.app.R;
import com.lyricsync.app.ui.Anim;
import com.lyricsync.app.util.AppLog;
import com.lyricsync.app.util.Haptics;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Collapsible developer card: live log viewer with clear / share. */
public final class DeveloperSection {
    private static final String TAG = "MainActivity";

    private final Activity activity;
    private final TextView logText;
    private final ScrollView logScroll;

    public DeveloperSection(Activity activity, SharedPreferences prefs, Handler handler) {
        this.activity = activity;
        View content = activity.findViewById(R.id.dev_content);
        ImageView chevron = activity.findViewById(R.id.dev_chevron);
        logText = activity.findViewById(R.id.log_text);
        logScroll = activity.findViewById(R.id.log_scroll);

        boolean expanded = prefs.getBoolean(MainPrefs.DEV_EXPANDED, false);
        content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        chevron.setRotation(expanded ? 180f : 0f);
        activity.findViewById(R.id.dev_header).setOnClickListener(v -> {
            Haptics.tick(v);
            boolean nowExpanded = content.getVisibility() != View.VISIBLE;
            if (nowExpanded) {
                Anim.expand(content);
                scrollToBottom();
            } else {
                Anim.collapse(content);
            }
            Anim.rotateTo(chevron, nowExpanded ? 180f : 0f);
            prefs.edit().putBoolean(MainPrefs.DEV_EXPANDED, nowExpanded).apply();
        });

        activity.findViewById(R.id.btn_clear_logs).setOnClickListener(v -> {
            Haptics.tick(v);
            AppLog.clear();
            logText.setText("");
            AppLog.i(TAG, "Logs cleared");
        });
        activity.findViewById(R.id.btn_share_logs).setOnClickListener(v -> {
            Haptics.tick(v);
            shareLogs();
        });

        logText.setText(AppLog.getAllText());
        scrollToBottom();
        AppLog.setListener(entry -> handler.post(() -> {
            logText.append(entry.toString() + "\n");
            scrollToBottom();
        }));
    }

    /**
     * Pin the log to its newest line. Plain scrollTo on purpose: fullScroll(FOCUS_DOWN)
     * moves focus to the selectable log text, which drags the whole page down with it.
     */
    private void scrollToBottom() {
        logScroll.post(() -> logScroll.scrollTo(0, Math.max(0, logText.getHeight() - logScroll.getHeight()
                + logScroll.getPaddingTop() + logScroll.getPaddingBottom())));
    }

    private void shareLogs() {
        try {
            String logs = AppLog.getAllText();
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String header = "=== LyricSync Log " + timestamp + " ===\n"
                    + "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                    + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n\n";

            File logFile = new File(activity.getCacheDir(), "lyricsync_log_" + timestamp + ".txt");
            try (FileWriter fw = new FileWriter(logFile)) {
                fw.write(header + logs);
            }

            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", logFile);

            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, "LyricSync Log");
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(share, activity.getString(R.string.share_logs)));

            AppLog.i(TAG, "Logs shared");
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to share logs: " + e.getMessage());
            Toast.makeText(activity, R.string.share_logs_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** Detach the static log listener so it cannot outlive the activity. */
    public void release() {
        AppLog.setListener(null);
    }
}
