package com.lyricsync.app.renderer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.TextView;

public class GradientTextView extends TextView {
    private static final float MIN_PROGRESS_DELTA = 0.001f;
    private static final float FADE_WIDTH = 0.2f;

    // SpicyLyrics: --gradient-alpha 0.85 (sung), --gradient-alpha-end 0.35 (not-sung)
    private int[] gradientColors = { 0xD9FFFFFF, 0x59FFFFFF };
    private float progress = 0f;

    private float cachedWidth = -1f;
    private float lastShaderProgress = -1f;
    private LinearGradient cachedShader;

    public GradientTextView(Context context) {
        super(context);
        getPaint().setShadowLayer(6f, 0, 0, 0x44FFFFFF);
    }

    public GradientTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        getPaint().setShadowLayer(6f, 0, 0, 0x44FFFFFF);
    }

    public GradientTextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        getPaint().setShadowLayer(6f, 0, 0, 0x44FFFFFF);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cachedWidth = -1f;
        lastShaderProgress = -1f;
        cachedShader = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Paint paint = getPaint();

        if (cachedWidth < 0) {
            cachedWidth = paint.measureText(getText().toString());
        }
        float width = cachedWidth;

        float gradientProgress = Math.max(0f, Math.min(progress, 1f));

        if (cachedShader == null || Math.abs(gradientProgress - lastShaderProgress) > MIN_PROGRESS_DELTA) {
            // SpicyLyrics: gradient-position = -20% + 120% * progress.
            // Bright (sung) edge at P, eased to dim over a +20% band.
            float p = -0.20f + 1.20f * gradientProgress;
            float dStop = p + FADE_WIDTH;

            LinearGradient shader;
            if (dStop <= 0f) {
                shader = new LinearGradient(0, 0, width, 0, gradientColors[1], gradientColors[1], Shader.TileMode.CLAMP);
            } else if (p >= 1f) {
                shader = new LinearGradient(0, 0, width, 0, gradientColors[0], gradientColors[0], Shader.TileMode.CLAMP);
            } else {
                float b = Math.max(0f, p);
                float d = Math.min(1f, dStop);
                shader = new LinearGradient(0, 0, width, 0,
                        new int[]{ gradientColors[0], gradientColors[1] },
                        new float[]{ b, Math.max(b + 0.001f, d) },
                        Shader.TileMode.CLAMP);
            }
            cachedShader = shader;
            lastShaderProgress = gradientProgress;
        }

        paint.setShader(cachedShader);

        super.onDraw(canvas);
    }

    public void setProgress(float progress) {
        float normalized = Math.max(0f, Math.min(progress / 100f, 1f));
        if (Math.abs(normalized - this.progress) < MIN_PROGRESS_DELTA) return;
        this.progress = normalized;
        postInvalidate();
    }
}
