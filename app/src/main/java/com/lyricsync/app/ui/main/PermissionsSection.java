package com.lyricsync.app.ui.main;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.lyricsync.app.R;
import com.lyricsync.app.ui.Anim;
import com.lyricsync.app.util.Permissions;

/**
 * Permission checklist. The whole card exists only while a permission is missing:
 * it collapses away once both are granted and re-expands if one is revoked.
 */
public final class PermissionsSection {

    private final Activity activity;
    private final View card;
    private final TextView summary;
    private final Row notification;
    private final Row overlay;

    private boolean known;
    private boolean lastNotif;
    private boolean lastOverlay;

    /** One permission row: step tile (number or check) + status pill. */
    private static final class Row {
        final View stepTile;
        final View stepNum;
        final View stepCheck;
        final View pill;
        final ImageView pillIcon;
        final TextView pillLabel;

        Row(Activity a, int tile, int num, int check, int pill, int icon, int label) {
            stepTile = a.findViewById(tile);
            stepNum = a.findViewById(num);
            stepCheck = a.findViewById(check);
            this.pill = a.findViewById(pill);
            pillIcon = a.findViewById(icon);
            pillLabel = a.findViewById(label);
        }
    }

    public PermissionsSection(Activity activity) {
        this.activity = activity;
        card = activity.findViewById(R.id.card_permissions);
        summary = activity.findViewById(R.id.permissions_summary);
        notification = new Row(activity, R.id.notification_step, R.id.notification_step_num,
                R.id.notification_step_check, R.id.notification_pill, R.id.notification_pill_icon,
                R.id.notification_status);
        overlay = new Row(activity, R.id.overlay_step, R.id.overlay_step_num,
                R.id.overlay_step_check, R.id.overlay_pill, R.id.overlay_pill_icon,
                R.id.overlay_status);
    }

    public View card() {
        return card;
    }

    public boolean allGranted() {
        return Permissions.isNotificationListenerEnabled(activity) && Settings.canDrawOverlays(activity);
    }

    /** Re-check both permissions; animate only what changed since the last call. */
    public void refresh() {
        boolean notif = Permissions.isNotificationListenerEnabled(activity);
        boolean draw = Settings.canDrawOverlays(activity);
        boolean all = notif && draw;

        apply(notification, notif, known && notif != lastNotif);
        apply(overlay, draw, known && draw != lastOverlay);
        summary.setText(all ? R.string.permissions_all_set : R.string.permissions_needed);

        if (!known) {
            card.setVisibility(all ? View.GONE : View.VISIBLE);
        } else if (all) {
            Anim.collapse(card);
        } else {
            Anim.expand(card);
        }

        lastNotif = notif;
        lastOverlay = draw;
        known = true;
    }

    private void apply(Row row, boolean granted, boolean animate) {
        int color = ContextCompat.getColor(activity, granted ? R.color.ls_primary : R.color.ls_warn);
        row.pill.setBackgroundResource(granted ? R.drawable.ls_bg_pill_ok : R.drawable.ls_bg_pill_warn);
        row.pillIcon.setImageResource(granted ? R.drawable.ic_check : R.drawable.ic_alert);
        row.pillIcon.setColorFilter(color);
        row.pillLabel.setTextColor(color);
        row.pillLabel.setText(granted ? R.string.enabled : R.string.disabled);
        row.stepTile.setBackgroundResource(granted ? R.drawable.ls_bg_tile_ok : R.drawable.ls_bg_tile);
        row.stepNum.setVisibility(granted ? View.GONE : View.VISIBLE);
        row.stepCheck.setVisibility(granted ? View.VISIBLE : View.GONE);
        if (animate) Anim.pop(row.pill);
    }

    /** Small shake to point at whatever is blocking the user. */
    public void nudge() {
        if (card.getVisibility() != View.VISIBLE) {
            // Not shown yet: revealing it is the hint. A shake would fight the expand.
            Anim.expand(card);
            card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            return;
        }
        ObjectAnimator shake = ObjectAnimator.ofFloat(card, View.TRANSLATION_X,
                0f, -12f, 12f, -7f, 7f, 0f);
        shake.setDuration(420);
        shake.setInterpolator(new AccelerateDecelerateInterpolator());
        shake.start();
        card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }
}
