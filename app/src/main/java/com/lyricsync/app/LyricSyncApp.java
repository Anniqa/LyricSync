package com.lyricsync.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

public class LyricSyncApp extends Application {
    public static final String CHANNEL_ID = "lyricsync_overlay";
    public static final String CHANNEL_NAME = "Lyrics Overlay";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Shows lyrics overlay notification");
        channel.setShowBadge(false);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
