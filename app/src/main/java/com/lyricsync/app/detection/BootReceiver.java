package com.lyricsync.app.detection;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences prefs = context.getSharedPreferences("lyricsync_prefs", Context.MODE_PRIVATE);
            boolean autoStart = prefs.getBoolean("auto_start_overlay", false);

            if (autoStart && MediaNotificationListener.isListenerEnabled()) {
                Intent serviceIntent = new Intent(context, com.lyricsync.app.overlay.FloatingOverlayService.class);
                context.startForegroundService(serviceIntent);
            }
        }
    }
}
