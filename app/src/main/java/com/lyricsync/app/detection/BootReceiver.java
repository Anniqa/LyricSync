package com.lyricsync.app.detection;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;

import com.lyricsync.app.overlay.FloatingOverlayService;
import com.lyricsync.app.util.AppLog;
import com.lyricsync.app.util.Permissions;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        SharedPreferences prefs = context.getSharedPreferences("lyricsync", Context.MODE_PRIVATE);
        boolean autoStart = prefs.getBoolean("auto_start_overlay", false);
        if (!autoStart
                || !Permissions.isNotificationListenerEnabled(context)
                || !Settings.canDrawOverlays(context)) {
            return;
        }

        try {
            Intent serviceIntent = new Intent(context, FloatingOverlayService.class);
            context.startForegroundService(serviceIntent);
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to auto-start overlay on boot", e);
        }
    }
}
