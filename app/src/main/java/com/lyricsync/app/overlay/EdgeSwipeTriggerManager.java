package com.lyricsync.app.overlay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.lyricsync.app.util.AppLog;
import com.lyricsync.app.util.Haptics;

import java.util.Collections;

/**
 * Manages transparent edge trigger windows on both left and right screen borders.
 * Detects "Swipe & Hold" gestures (~20dp inward swipe held for 250ms) to trigger
 * floating overlay expansion without conflicting with Android system Back gestures.
 */
public class EdgeSwipeTriggerManager {

    private static final String TAG = "EdgeSwipeTrigger";
    private static final long HOLD_DURATION_MS = 250L;

    public interface OnTriggerListener {
        void onEdgeTriggered();
    }

    private final Context context;
    private final WindowManager windowManager;
    private final OnTriggerListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private View leftTriggerView;
    private View rightTriggerView;
    private WindowManager.LayoutParams leftParams;
    private WindowManager.LayoutParams rightParams;
    private volatile boolean isAttached = false;
    private volatile boolean isEnabled = true;

    public EdgeSwipeTriggerManager(Context context, OnTriggerListener listener) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.listener = listener;
    }

    public synchronized void attach() {
        if (isAttached || windowManager == null) return;

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int triggerWidth = dpToPx(12);
        int triggerHeight = (int) (dm.heightPixels * 0.45f);
        int triggerY = (int) (dm.heightPixels * 0.28f);

        leftParams = createLayoutParams(triggerWidth, triggerHeight, triggerY, Gravity.TOP | Gravity.START);
        rightParams = createLayoutParams(triggerWidth, triggerHeight, triggerY, Gravity.TOP | Gravity.END);

        leftTriggerView = createTriggerView(true);
        rightTriggerView = createTriggerView(false);

        try {
            windowManager.addView(leftTriggerView, leftParams);
            windowManager.addView(rightTriggerView, rightParams);
            isAttached = true;
            updateExclusionRects();
            AppLog.i(TAG, "Edge triggers attached successfully");
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to attach edge triggers: " + e.getMessage());
            detach();
        }
    }

    public synchronized void detach() {
        handler.removeCallbacksAndMessages(null);
        if (windowManager != null) {
            if (leftTriggerView != null) {
                try {
                    windowManager.removeView(leftTriggerView);
                } catch (Exception ignored) {}
            }
            if (rightTriggerView != null) {
                try {
                    windowManager.removeView(rightTriggerView);
                } catch (Exception ignored) {}
            }
        }
        leftTriggerView = null;
        rightTriggerView = null;
        isAttached = false;
        AppLog.i(TAG, "Edge triggers detached");
    }

    public synchronized void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
        if (enabled && !isAttached) {
            attach();
        } else if (!enabled && isAttached) {
            detach();
        }
    }

    public boolean isAttached() {
        return isAttached;
    }

    private WindowManager.LayoutParams createLayoutParams(int width, int height, int y, int gravity) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = gravity;
        params.x = 0;
        params.y = y;
        return params;
    }

    @SuppressLint("ClickableViewAccessibility")
    private View createTriggerView(final boolean isLeft) {
        View view = new View(context);
        view.setBackgroundColor(Color.TRANSPARENT);

        final float[] downX = new float[1];
        final float[] downY = new float[1];
        final long[] downTime = new long[1];
        final boolean[] isArmed = new boolean[1];
        final boolean[] isCancelled = new boolean[1];
        final boolean[] hasTriggered = new boolean[1];
        final float minSwipeDistance = dpToPx(20);

        final Runnable holdRunnable = () -> {
            if (!isAttached || !isEnabled) return;
            if (isArmed[0] && !isCancelled[0] && !hasTriggered[0]) {
                hasTriggered[0] = true;
                Haptics.tick(view);
                AppLog.i(TAG, "Swipe & Hold triggered from " + (isLeft ? "LEFT" : "RIGHT") + " edge");
                if (listener != null) {
                    listener.onEdgeTriggered();
                }
            }
        };

        view.setOnTouchListener((v, event) -> {
            if (!isEnabled) return false;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = event.getRawX();
                    downY[0] = event.getRawY();
                    downTime[0] = System.currentTimeMillis();
                    isArmed[0] = false;
                    isCancelled[0] = false;
                    hasTriggered[0] = false;
                    handler.postDelayed(holdRunnable, HOLD_DURATION_MS);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = Math.abs(event.getRawX() - downX[0]);
                    float dy = Math.abs(event.getRawY() - downY[0]);

                    if (dy > dx * 1.5f) {
                        isCancelled[0] = true;
                        handler.removeCallbacks(holdRunnable);
                    } else if (dx >= minSwipeDistance && !isCancelled[0] && !hasTriggered[0]) {
                        isArmed[0] = true;
                        if (System.currentTimeMillis() - downTime[0] >= HOLD_DURATION_MS) {
                            handler.removeCallbacks(holdRunnable);
                            holdRunnable.run();
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(holdRunnable);
                    isArmed[0] = false;
                    return true;
            }
            return false;
        });

        return view;
    }

    private void updateExclusionRects() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (leftTriggerView != null && leftParams != null) {
                leftTriggerView.post(() -> {
                    if (leftTriggerView != null) {
                        Rect rect = new Rect(0, 0, leftTriggerView.getWidth(), leftTriggerView.getHeight());
                        leftTriggerView.setSystemGestureExclusionRects(Collections.singletonList(rect));
                    }
                });
            }
            if (rightTriggerView != null && rightParams != null) {
                rightTriggerView.post(() -> {
                    if (rightTriggerView != null) {
                        Rect rect = new Rect(0, 0, rightTriggerView.getWidth(), rightTriggerView.getHeight());
                        rightTriggerView.setSystemGestureExclusionRects(Collections.singletonList(rect));
                    }
                });
            }
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
