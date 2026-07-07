package com.lyricsync.app.renderer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.View;

/**
 * Spicy EX Android parity: computed dot animation (stagger + pulse + main spline).
 * No per-dot springs — dots use direct spline evaluation + breathing pulse.
 */
public class InterludeDotView extends View {
    private static final int DOT_COUNT = 3;
    private static final float PRE_HIDDEN_MS_RATIO = 0.075f; // Spicy EX: endMs - 7.5% of duration

    private final long startTime;
    private final long endTime;
    private final long duration;
    private final float dotRadiusPx;
    private final float dotSpacingPx;
    private final float density;

    private final Paint dotPaint;
    private final Paint glowPaint;

    private boolean visible = false;

    public InterludeDotView(Context context, long startTime, long endTime) {
        super(context);
        this.startTime = startTime;
        this.endTime = endTime;
        this.duration = endTime - startTime;
        this.dotRadiusPx = dpToPx(6);
        this.dotSpacingPx = dpToPx(11);
        this.density = context.getResources().getDisplayMetrics().density;

        dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(Color.WHITE);
        dotPaint.setStyle(Paint.Style.FILL);

        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setColor(Color.WHITE);
        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setMaskFilter(new android.graphics.BlurMaskFilter(dpToPx(6), android.graphics.BlurMaskFilter.Blur.NORMAL));

        setVisibility(GONE);
    }

    public void animate(long positionMs, double deltaTime) {
        float lineProgress = progress01(positionMs, startTime, endTime);
        boolean isActive = positionMs >= startTime && positionMs < endTime;
        boolean isPast = positionMs >= endTime;

        if (!isActive && !isPast) {
            if (visible) { setVisibility(GONE); visible = false; }
            return;
        }
        if (isPast) {
            if (visible) { setVisibility(GONE); visible = false; }
            return;
        }

        if (!visible) { setVisibility(VISIBLE); visible = true; }

        // Pre-hide near end (Spicy EX: PRE_HIDDEN_DOT_LINE_MS)
        float preHideRatio = 1f - PRE_HIDDEN_MS_RATIO;
        boolean preHide = duration > 0 && lineProgress >= preHideRatio;
        float mainScaleTarget = preHide ? 0f : dotMainScaleSpline(lineProgress);
        float mainOpacityTarget = preHide ? 0f : dotMainOpacitySpline(lineProgress);

        // Main scale spring (appear/disappear)
        mainScaleSpring.finalPosition = mainScaleTarget;
        mainOpacitySpring.finalPosition = mainOpacityTarget;
        if (deltaTime > 0) {
            mainScaleSpring.update(deltaTime);
            mainOpacitySpring.update(deltaTime);
        }

        this.currentLineProgress = lineProgress;
        this.currentPositionMs = positionMs;
        postInvalidate();
    }

    // Container springs for smooth appear/disappear
    private final Spring mainScaleSpring = new Spring(0, 5.0, 0.7);
    private final Spring mainOpacitySpring = new Spring(0, 5.0, 0.7);
    private float currentLineProgress = 0f;
    private long currentPositionMs = 0;

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!visible) return;

        float mainScale = (float) mainScaleSpring.position;
        float mainOpacity = (float) Math.max(0, Math.min(mainOpacitySpring.position, 1));
        if (mainScale <= 0.001f || mainOpacity <= 0.001f) return;

        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;

        float totalWidth = DOT_COUNT * dotRadiusPx * 2 + (DOT_COUNT - 1) * dotSpacingPx;
        float startX = centerX - totalWidth / 2f + dotRadiusPx;

        for (int i = 0; i < DOT_COUNT; i++) {
            // Spicy EX: stagger = clamp(lineProgress * 1.25 - i * 0.09, 0, 1)
            float stagger = Math.max(0f, Math.min(1f, currentLineProgress * 1.25f - i * 0.09f));
            float pulse = dotPulse(currentPositionMs, i);

            float dotScale = mainScale * dotScaleSpline(stagger) * pulse;
            float dotY = dotRadiusPx * 4 * dotYOffsetSpline(stagger); // offset relative to dot size
            float glow = dotGlowSpline(stagger) * mainOpacity;
            float opacity = mainOpacity * dotOpacitySpline(stagger);

            // Gradient: -20 + 120 * stagger (Spicy EX exact)
            float gradientPos = -20f + 120f * stagger;
            float glowBrightAlpha = 0.85f + 0.15f * glow;
            int dotAlpha = Math.round(opacity * glowBrightAlpha * 255);
            int glowAlphaInt = Math.round(glow * opacity * 255);

            float dotX = startX + i * (dotRadiusPx * 2 + dotSpacingPx);

            canvas.save();
            canvas.translate(0, dotY);
            canvas.scale(dotScale, dotScale, dotX, centerY);

            if (glowAlphaInt > 1) {
                glowPaint.setAlpha(glowAlphaInt);
                canvas.drawCircle(dotX, centerY, dotRadiusPx * 1.5f, glowPaint);
            }

            dotPaint.setAlpha(Math.max(1, dotAlpha));
            canvas.drawCircle(dotX, centerY, dotRadiusPx, dotPaint);

            canvas.restore();
        }
    }

    // --- Spicy EX Android LyricAnimations dot splines (exact values) ---

    /** Main scale: [0,0] -> [0.2,1.05] -> [0.925,1.15] -> [1,0] */
    private static float dotMainScaleSpline(float t) {
        if (t <= 0.2f) return lerp(0f, 1.05f, t / 0.2f);
        if (t <= 0.925f) return lerp(1.05f, 1.15f, (t - 0.2f) / 0.725f);
        return lerp(1.15f, 0f, (t - 0.925f) / 0.075f);
    }

    /** Main opacity: [0,0] -> [0.5,1] -> [0.925,1] -> [1,0] */
    private static float dotMainOpacitySpline(float t) {
        if (t <= 0.5f) return lerp(0f, 1f, t / 0.5f);
        if (t <= 0.925f) return 1f;
        return lerp(1f, 0f, (t - 0.925f) / 0.075f);
    }

    /** Per-dot scale: [0,0.75] -> [0.7,1.10] -> [1,1.0] */
    private static float dotScaleSpline(float t) {
        if (t <= 0.7f) return lerp(0.75f, 1.10f, t / 0.7f);
        return lerp(1.10f, 1.0f, (t - 0.7f) / 0.3f);
    }

    /** Per-dot Y offset: [0,0.03] -> [0.7,-0.035] -> [1,0] (relative to dot size) */
    private static float dotYOffsetSpline(float t) {
        if (t <= 0.7f) return lerp(0.03f, -0.035f, t / 0.7f);
        return lerp(-0.035f, 0f, (t - 0.7f) / 0.3f);
    }

    /** Per-dot glow: [0,0] -> [0.18,1] -> [0.7,1] -> [1,0] */
    private static float dotGlowSpline(float t) {
        if (t <= 0.18f) return lerp(0f, 1f, t / 0.18f);
        if (t <= 0.7f) return 1f;
        return lerp(1f, 0f, (t - 0.7f) / 0.3f);
    }

    /** Per-dot opacity: [0,0] -> [0.18,1] -> [0.85,1] -> [1,0.88] */
    private static float dotOpacitySpline(float t) {
        if (t <= 0.18f) return lerp(0f, 1f, t / 0.18f);
        if (t <= 0.85f) return 1f;
        return lerp(1f, 0.88f, (t - 0.85f) / 0.15f);
    }

    /** Slow breathing pulse: 0.90 <-> 1.05 with phase offset per dot. */
    private static float dotPulse(long positionMs, int index) {
        double phase = ((positionMs / 1000d) + index * 0.18d) % 2.25d;
        double normalized = phase / 2.25d;
        if (normalized < 0.34d) return lerp(0.90f, 1.05f, (float) (normalized / 0.34d));
        if (normalized < 0.68d) return lerp(1.05f, 0.90f, (float) ((normalized - 0.34d) / 0.34d));
        return 0.90f;
    }

    private static float progress01(long positionMs, long startMs, long endMs) {
        if (endMs <= startMs) return positionMs >= endMs ? 1f : 0f;
        return Math.max(0f, Math.min(1f, (positionMs - startMs) / (float) (endMs - startMs)));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * Math.max(0f, Math.min(1f, t));
    }

    private float dpToPx(int dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }
}
