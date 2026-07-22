package com.lyricsync.app.detection;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * Exists purely to hold the notification-access grant so that
 * MediaSessionManager.getActiveSessions() works. It does not process
 * notifications itself. Enabled-state is checked via Settings.Secure.
 */
public class MediaNotificationListener extends NotificationListenerService {
    private static final String TAG = "MediaNotifListener";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "NotificationListener created");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "NotificationListener destroyed");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
    }
}
