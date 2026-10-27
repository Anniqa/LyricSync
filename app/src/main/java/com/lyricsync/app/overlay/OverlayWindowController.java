package com.lyricsync.app.overlay;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.lyricsync.app.ui.Anim;
import com.lyricsync.app.util.AppLog;
import com.lyricsync.app.util.Haptics;

/**
 * Owns the main overlay window: creation, position, drag, resize, screen clamping
 * and re-fitting after rotation / fold / multi-window changes. All sizes come from
 * {@link OverlayConfig}; the math lives in {@link OverlayGeometry}.
 *
 * The screen size is re-read ({@link OverlayScreen}) on every use, so an orientation
 * change never leaves the window sized or clamped for the old screen.
 */
final class OverlayWindowController {

    /** Reports user resizes back to the service so it can restyle the lyrics. */
    interface Host {
        /** Live feedback while the handle is dragged: the body height changed. */
        void onBodyResized(int bodyHeightPx);

        /** The gesture ended and the new width% / height% are persisted. */
        void onResizeCommitted();

        /** Screen bounds changed (rotation, fold, split screen); the window was re-fitted. */
        void onScreenChanged();
    }

    private static final String TAG = "OverlayWindow";

    private final WindowManager windowManager;
    private final OverlayScreen screen;
    private final SharedPreferences prefs;
    private final OverlayConfig config;
    private final Host host;

    private View root;
    private View body;
    private WindowManager.LayoutParams params;
    private boolean attached;
    private int lastScreenWidth;
    private int lastScreenHeight;

    OverlayWindowController(WindowManager windowManager, OverlayScreen screen, SharedPreferences prefs,
                            OverlayConfig config, Host host) {
        this.windowManager = windowManager;
        this.screen = screen;
        this.prefs = prefs;
        this.config = config;
        this.host = host;
    }

    // ── Screen ───────────────────────────────────────────────────────────────

    private float density() {
        return screen.density();
    }

    private int px(int dp) {
        return screen.px(dp);
    }

    private int[] screenSize() {
        return new int[]{screen.widthPx(), screen.heightPx()};
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /** Adds the window; returns false (and logs) if the system refuses it. */
    boolean attach(View root, View body, View resizeHandle) {
        this.root = root;
        this.body = body;
        int[] size = screenSize();
        lastScreenWidth = size[0];
        lastScreenHeight = size[1];

        params = new WindowManager.LayoutParams(
                windowWidthPx(size[0]),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.x = prefs.getInt(OverlayPrefKeys.POSITION_X, 0);
        params.y = prefs.getInt(OverlayPrefKeys.POSITION_Y, px(config.windowDefaultYDp));
        clampPosition();

        installDrag();
        installResize(resizeHandle);

        try {
            windowManager.addView(root, params);
            attached = true;
            return true;
        } catch (Exception e) {
            // A revoked overlay grant (or an OEM restriction) throws here; don't crash.
            AppLog.e(TAG, "Failed to add overlay view", e);
            attached = false;
            return false;
        }
    }

    void detach() {
        if (!attached || root == null) return;
        attached = false;
        root.setOnTouchListener(null);
        try {
            windowManager.removeView(root);
        } catch (Exception e) {
            AppLog.e(TAG, "Error removing overlay", e);
        }
    }

    boolean isAttached() {
        return attached;
    }

    // ── Sizing ───────────────────────────────────────────────────────────────

    int widthPercentPref() {
        return OverlayGeometry.widthPercent(config,
                prefs.getInt(OverlayPrefKeys.WIDTH_PERCENT, config.widthPercentDefault));
    }

    int heightPercentPref() {
        return OverlayGeometry.heightPercent(config,
                prefs.getInt(OverlayPrefKeys.HEIGHT_PERCENT, config.heightPercentDefault));
    }

    int bodyHeightFromPrefs() {
        return OverlayGeometry.bodyHeightPx(config, screenSize()[1], heightPercentPref(), density());
    }

    private int windowWidthPx(int screenWidth) {
        return OverlayGeometry.windowWidthPx(config, screenWidth, widthPercentPref(), density());
    }

    /** Re-applies the pref width (and screen clamp) — after settings or a rotation. */
    void applyPrefWidth() {
        if (params == null) return;
        int oldW = params.width;
        int oldX = params.x;
        int oldY = params.y;
        params.width = windowWidthPx(screenSize()[0]);
        clampPosition();
        // Skip the WindowManager round-trip when nothing moved (most pref changes).
        if (params.width != oldW || params.x != oldX || params.y != oldY) relayout();
    }

    /**
     * Called from the service's onConfigurationChanged. Re-fits only when the usable
     * screen actually changed, so a theme/locale change does not cause a relayout.
     */
    void onConfigurationChanged() {
        if (params == null) return;
        int[] size = screenSize();
        if (size[0] == lastScreenWidth && size[1] == lastScreenHeight) return;
        lastScreenWidth = size[0];
        lastScreenHeight = size[1];
        params.width = windowWidthPx(size[0]);
        clampPosition();
        relayout();
        host.onScreenChanged();
        // The measured height is still the old screen's until the next layout pass;
        // re-clamp once it lands so the window can't hang off the new bottom edge.
        if (root != null) {
            root.post(() -> {
                if (attached && clampPosition()) relayout();
            });
        }
    }

    // ── Position ─────────────────────────────────────────────────────────────

    /** Keeps the whole window on screen. Returns true if a bound had to be applied. */
    private boolean clampPosition() {
        int[] size = screenSize();
        int w = root != null && root.getWidth() > 0 ? root.getWidth() : params.width;
        int h = root != null && root.getHeight() > 0 ? root.getHeight() : px(config.windowUnmeasuredHeightDp);
        int x = OverlayGeometry.clampCentered(params.x, size[0], w);
        int y = OverlayGeometry.clampFromEdge(params.y, size[1], h);
        boolean clamped = x != params.x || y != params.y;
        params.x = x;
        params.y = y;
        return clamped;
    }

    private void persistPosition() {
        prefs.edit()
                .putInt(OverlayPrefKeys.POSITION_X, params.x)
                .putInt(OverlayPrefKeys.POSITION_Y, params.y)
                .apply();
    }

    private void relayout() {
        if (!attached || root == null) return;
        try {
            windowManager.updateViewLayout(root, params);
        } catch (Exception e) {
            // The view may have been detached mid-gesture during teardown.
            AppLog.w(TAG, "updateViewLayout failed: " + e.getMessage());
        }
    }

    // ── Drag ─────────────────────────────────────────────────────────────────

    /** Drag the window by any non-consuming area; snaps (haptic) on hitting a screen edge. */
    @SuppressLint("ClickableViewAccessibility")
    private void installDrag() {
        root.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private int startX;
            private int startY;
            private boolean dragging;
            private boolean wasClamped;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = params.x;
                        startY = params.y;
                        dragging = false;
                        wasClamped = false;
                        return false;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        int slop = px(config.dragSlopDp);
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) dragging = true;
                        if (!dragging) return false;
                        params.x = startX + (int) dx;
                        params.y = startY + (int) dy;
                        // Without this the window can be flung off-screen and never recovered.
                        boolean clamped = clampPosition();
                        if (clamped && !wasClamped) Haptics.snap(v);
                        wasClamped = clamped;
                        relayout();
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            persistPosition();
                            // Let the bubble "land" after a drag instead of stopping dead.
                            Anim.bubbleSettle(root);
                        }
                        return dragging;
                    default:
                        return false;
                }
            }
        });
    }

    // ── Resize ───────────────────────────────────────────────────────────────

    /**
     * Bottom-right handle: horizontal drag resizes the window around its centre,
     * vertical drag resizes the lyrics body. Limits are recomputed per gesture from
     * the current screen, so they stay correct after a rotation.
     */
    @SuppressLint("ClickableViewAccessibility")
    private void installResize(View handle) {
        if (handle == null) return;
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private int startWidth;
            private int startBody;
            private int startX;
            private boolean resizing;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startWidth = params.width > 0 ? params.width : root.getWidth();
                        startBody = body != null && body.getHeight() > 0
                                ? body.getHeight() : bodyHeightFromPrefs();
                        startX = params.x;
                        resizing = false;
                        Haptics.tick(v);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        int slop = px(config.resizeSlopDp);
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) resizing = true;
                        if (!resizing) return false;
                        int[] size = screenSize();
                        float d = density();
                        int width = OverlayGeometry.clamp((int) (startWidth + dx),
                                OverlayGeometry.resizeMinWidthPx(config, size[0], d),
                                OverlayGeometry.resizeMaxWidthPx(config, size[0], d));
                        // Centre gravity: shift by half the growth so the left edge stays put.
                        params.width = width;
                        params.x = startX + (width - startWidth) / 2;
                        clampPosition();
                        if (body != null && body.getVisibility() == View.VISIBLE) {
                            int bodyPx = OverlayGeometry.clamp((int) (startBody + dy),
                                    OverlayGeometry.resizeMinBodyPx(config, size[1], d),
                                    OverlayGeometry.resizeMaxBodyPx(config, size[1], d));
                            ViewGroup.LayoutParams lp = body.getLayoutParams();
                            if (lp.height != bodyPx) {
                                lp.height = bodyPx;
                                body.setLayoutParams(lp);
                                host.onBodyResized(bodyPx);
                            }
                        }
                        relayout();
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (resizing) {
                            commitResize();
                            Haptics.snap(v);
                        }
                        return resizing;
                    default:
                        return false;
                }
            }
        });
    }

    private void commitResize() {
        int[] size = screenSize();
        int bodyPx = body != null && body.getLayoutParams() != null && body.getLayoutParams().height > 0
                ? body.getLayoutParams().height : bodyHeightFromPrefs();
        prefs.edit()
                .putInt(OverlayPrefKeys.WIDTH_PERCENT,
                        OverlayGeometry.widthPercentFromPx(config, params.width, size[0]))
                .putInt(OverlayPrefKeys.HEIGHT_PERCENT,
                        OverlayGeometry.heightPercentFromPx(config, bodyPx, size[1]))
                .apply();
        persistPosition();
        host.onResizeCommitted();
    }

    // ── Exit ─────────────────────────────────────────────────────────────────

    void animateOut(Runnable endAction) {
        if (root == null) {
            endAction.run();
            return;
        }
        root.animate()
                .alpha(0f)
                .scaleX(config.exitScale)
                .scaleY(config.exitScale)
                .setDuration(config.exitDurationMs)
                .setInterpolator(Anim.STANDARD)
                .withEndAction(endAction)
                .start();
    }
}
