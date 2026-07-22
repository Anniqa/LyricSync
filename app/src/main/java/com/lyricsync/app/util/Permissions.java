package com.lyricsync.app.util;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;

import com.lyricsync.app.detection.MediaNotificationListener;

/** Shared permission checks used by the activity, service and boot receiver. */
public final class Permissions {
    private Permissions() {}

    public static boolean isNotificationListenerEnabled(Context context) {
        String flat = Settings.Secure.getString(
                context.getContentResolver(), "enabled_notification_listeners");
        if (flat == null) return false;
        ComponentName cn = new ComponentName(context, MediaNotificationListener.class);
        return flat.contains(cn.flattenToString());
    }
}
