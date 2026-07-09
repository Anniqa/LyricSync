package com.lyricsync.app.detection;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

public class MediaNotificationListener extends NotificationListenerService {
    private static final String TAG = "MediaNotifListener";

    private static volatile boolean isListening = false;

    public static boolean isListenerEnabled() {
        return isListening;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isListening = true;
        Log.d(TAG, "NotificationListener created");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isListening = false;
        Log.d(TAG, "NotificationListener destroyed");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
    }
}
