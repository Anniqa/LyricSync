package com.lyricsync.app.util;

import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;

/** Thin haptic helpers. Feedback is best-effort — a device without a motor just no-ops. */
public final class Haptics {
    private Haptics() {}

    /** Light tick for discrete steps: slider notches, toggles. */
    public static void tick(View v) {
        perform(v, HapticFeedbackConstants.CLOCK_TICK);
    }

    /** Firmer confirmation for primary actions: start/stop, seek. */
    public static void confirm(View v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            perform(v, HapticFeedbackConstants.CONFIRM);
        } else {
            perform(v, HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    /** Used when a drag latches onto a screen edge. */
    public static void snap(View v) {
        perform(v, HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private static void perform(View v, int constant) {
        if (v == null) return;
        try {
            v.performHapticFeedback(constant,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Exception ignored) {
            // Some OEM views throw when haptics are unavailable; never let that reach the UI.
        }
    }
}
