package com.lyricsync.app.overlay;

import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

/**
 * Live screen metrics for the overlay windows.
 *
 * Sizes come from the {@link Display} on every call, so they follow rotation,
 * folding and display-size changes immediately. The service's own Resources are
 * not used for screen size: {@code LocaleHelper.applyLocale} wraps the service in
 * a configuration context whose copied Configuration can pin the creation-time
 * orientation. Font scaling (SP) does come from the service Resources, because
 * that is exactly what the overlay's TextViews use to resolve SP sizes.
 *
 * Holds the service's WindowManager / Resources only for the service's lifetime;
 * it is an instance field of the service, never static.
 */
final class OverlayScreen {

    private final WindowManager windowManager;
    private final Resources resources;
    private final DisplayMetrics scratch = new DisplayMetrics();

    OverlayScreen(WindowManager windowManager, Resources resources) {
        this.windowManager = windowManager;
        this.resources = resources;
    }

    /** Current metrics of the display the overlay is on (app area, current rotation). */
    @SuppressWarnings("deprecation") // Display#getMetrics is the only API 26-29 path
    private DisplayMetrics metrics() {
        Display display = windowManager != null ? windowManager.getDefaultDisplay() : null;
        if (display == null) return resources.getDisplayMetrics();
        display.getMetrics(scratch);
        if (scratch.widthPixels <= 0 || scratch.heightPixels <= 0) return resources.getDisplayMetrics();
        return scratch;
    }

    int widthPx() {
        return metrics().widthPixels;
    }

    int heightPx() {
        return metrics().heightPixels;
    }

    float density() {
        return metrics().density;
    }

    /** SP → px factor, matching what the overlay's TextViews use. */
    @SuppressWarnings("deprecation") // scaledDensity is what TypedValue uses for SP
    float scaledDensity() {
        return resources.getDisplayMetrics().scaledDensity;
    }

    float widthDp() {
        DisplayMetrics m = metrics();
        return m.widthPixels / m.density;
    }

    int px(int dp) {
        return OverlayGeometry.px(dp, density());
    }
}
