package com.lyricsync.app.renderer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.TypedValue;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class GradientWordView extends TextView {
    // SpicyLyrics: --gradient-alpha 0.85 (sung/bright), --gradient-alpha-end 0.35 (not-sung/dim)
    // bg-line: --gradient-alpha 0.6, --gradient-alpha-end 0.3 (dimmer, secondary)
    private static final float FADE_WIDTH = 0.20f;
    private static final int COLOR_BRIGHT = 0xD9FFFFFF; // 0.85 alpha white
    private static final int COLOR_DIM = 0x59FFFFFF;   // 0.35 alpha white
    private static final int BG_COLOR_BRIGHT = 0x99FFFFFF; // 0.6
    private static final int BG_COLOR_DIM = 0x4DFFFFFF;   // 0.3

    private int brightColor = COLOR_BRIGHT;
    private int dimColor = COLOR_DIM;
    private boolean backgroundMode = false;

    // Springs tuned for a slightly livelier, springier pop while staying smooth.
    private static final double SCALE_FREQ = 0.95;
    private static final double SCALE_DAMP = 0.58;
    private static final double YOFFSET_FREQ = 1.55;
    private static final double YOFFSET_DAMP = 0.42;
    private static final double GLOW_FREQ = 1.25;
    private static final double GLOW_DAMP = 0.5;

    // SpicyLyrics-inspired cubic spline ranges, tuned for a more expressive lift.
    // Scale: rest 0.95, overshoot ~1.10 near the sung peak, settle back to 1.0.
    private static final Spline SCALE_SPLINE = makeSpline(
            Arrays.asList(0.0, 0.65, 1.0), Arrays.asList(0.95, 1.10, 1.0));
    // YOffset: gentle dip up (negative = rise) then settle.
    private static final Spline YOFFSET_SPLINE = makeSpline(
            Arrays.asList(0.0, 0.85, 1.0), Arrays.asList(0.012, -0.026, 0.0));
    // Glow: quick bloom in, hold, fade out.
    private static final Spline GLOW_SPLINE = makeSpline(
            Arrays.asList(0.0, 0.12, 0.55, 1.0), Arrays.asList(0.0, 1.0, 1.0, 0.0));

    // Letter-level emphasis (SpicyLyrics IsLetterCapable + Emphasize)
    private static final long LETTER_MIN_DURATION = 1000;
    // SpicyLyrics non-SLM IsLetterCapable: only duration >= 1000, no count cap.
    // Distribute the per-letter emphasis across the FULL word so the last letter does
    // not finish early and snap back to idle (the old 250ms end trim left a dead zone).
    private static final long LETTER_SUBSTRACT_START = 0;
    private static final long LETTER_SUBSTRACT_END = 0;
    // SpicyLyrics Emphasize: LetterGlowMultiplier_Opacity = 185 (percent, clamped to 100)
    private static final float LETTER_GLOW_MULTIPLIER = 1.85f;
    private static final float LETTER_IDLE_SCALE = 0.95f;

    // Spicy EX Android: letterScaleRange 0->0.95, 0.7->1.18, 1->1.0
    private static final Spline LETTER_SCALE_SPLINE = makeSpline(
            Arrays.asList(0.0, 0.65, 1.0), Arrays.asList(0.95, 1.22, 1.0));
    // Spicy EX Android: letterYOffsetRange 0->0.01, 0.9->-1/50, 1->0
    private static final Spline LETTER_YOFFSET_SPLINE = makeSpline(
            Arrays.asList(0.0, 0.85, 1.0), Arrays.asList(0.012, -0.028, 0.0));

    private long startTime;
    private long endTime;
    private float progress;
    private boolean isActive;
    private boolean isPast;
    private float cachedTextWidth = -1f;
    private float lastShaderProgress = -1f;
    private LinearGradient cachedShader;
    private float appliedSizeSp = -1f;
    private android.graphics.Typeface appliedTypeface = null;

    private final Spring scaleSpring;
    private final Spring yOffsetSpring;
    private final Spring glowSpring;

    private final Paint glowPaint;
    // Soft blurred bloom drawn under the active word for a real light-glow look.
    private final Paint bloomPaint;
    private float bloomRadiusPx = 0f;

    // Letter-level emphasis state
    private boolean letterCapable;
    private String[] letters;
    private long[] letterStartTimes;
    private long[] letterEndTimes;
    private float[] letterProgress;
    private Spring[] letterScaleSprings;
    private Spring[] letterGlowSprings;

    public GradientWordView(Context context) {
        super(context);
        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setColor(brightColor);
        bloomPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bloomPaint.setColor(0xFFFFFFFF);
        scaleSpring = new Spring(0.95, SCALE_DAMP, SCALE_FREQ);
        yOffsetSpring = new Spring(0, YOFFSET_DAMP, YOFFSET_FREQ);
        glowSpring = new Spring(0, GLOW_DAMP, GLOW_FREQ);
    }

    public void setTiming(long startTime, long endTime) {
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public void setBackgroundMode(boolean bg) {
        backgroundMode = bg;
        brightColor = bg ? BG_COLOR_BRIGHT : COLOR_BRIGHT;
        dimColor = bg ? BG_COLOR_DIM : COLOR_DIM;
    }

    public void setWordStyle(float sizeSp, int color, android.graphics.Typeface typeface) {
        setTextColor(color);
        // Only touch text size / typeface / padding when they actually change, so a
        // colour-only state change (active/past/upcoming) doesn't trigger a relayout.
        boolean styleChanged = Math.abs(appliedSizeSp - sizeSp) > 0.01f || typeface != appliedTypeface;
        if (styleChanged) {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
            setTypeface(typeface);
            setIncludeFontPadding(true);
            glowPaint.setTextSize(getPaint().getTextSize());
            glowPaint.setTypeface(typeface);
            bloomPaint.setTextSize(getPaint().getTextSize());
            bloomPaint.setTypeface(typeface);
            bloomRadiusPx = Math.max(2f, getPaint().getTextSize() * 0.14f);
            int horizontalPad = Math.max(2, Math.round(getPaint().getTextSize() * 0.04f));
            int verticalPad = Math.max(2, Math.round(getPaint().getTextSize() * 0.10f));
            setPadding(horizontalPad, verticalPad, horizontalPad, verticalPad);
            appliedSizeSp = sizeSp;
            appliedTypeface = typeface;
            cachedTextWidth = -1f;
        }
    }

    public void initLetterEmphasis(String wordText, long wordStart, long wordEnd) {
        long duration = wordEnd - wordStart - LETTER_SUBSTRACT_START - LETTER_SUBSTRACT_END;
        if (!isLetterCapable(wordText.length(), duration)) {
            letterCapable = false;
            return;
        }
        letterCapable = true;
        letters = splitLetters(wordText);
        int n = letters.length;
        letterStartTimes = new long[n];
        letterEndTimes = new long[n];
        letterProgress = new float[n];
        letterScaleSprings = new Spring[n];
        letterGlowSprings = new Spring[n];

        long adjStart = wordStart + LETTER_SUBSTRACT_START;
        long adjEnd = wordEnd - LETTER_SUBSTRACT_END;
        long letterDur = (adjEnd - adjStart) / n;

        for (int i = 0; i < n; i++) {
            letterStartTimes[i] = adjStart + i * letterDur;
            letterEndTimes[i] = letterStartTimes[i] + letterDur;
            letterProgress[i] = 0f;
            letterScaleSprings[i] = new Spring(LETTER_IDLE_SCALE, SCALE_DAMP, SCALE_FREQ);
            letterGlowSprings[i] = new Spring(0, GLOW_DAMP, GLOW_FREQ);
        }
    }

    private static boolean isLetterCapable(int letterCount, long duration) {
        // SpicyLyrics non-SLM IsLetterCapable: duration >= 1000ms, no letter count limit.
        if (letterCount <= 0) return false;
        return duration >= LETTER_MIN_DURATION;
    }

    private static String[] splitLetters(String text) {
        if (text == null || text.isEmpty()) return new String[0];
        // Split by grapheme cluster, not UTF-16 code unit. charAt(i) breaks composed
        // characters (e.g. "é" = e + combining accent) and supplementary characters
        // (emoji, some CJK), which then measure/draw at the wrong width.
        java.text.BreakIterator it = java.text.BreakIterator.getCharacterInstance();
        it.setText(text);
        java.util.List<String> result = new java.util.ArrayList<>();
        int start = it.first();
        for (int end = it.next(); end != java.text.BreakIterator.DONE; start = end, end = it.next()) {
            result.add(text.substring(start, end));
        }
        return result.toArray(new String[0]);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cachedTextWidth = -1f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() == 0 || getHeight() == 0) return;

        float scale = (float) scaleSpring.position;
        float yOffset = (float) (yOffsetSpring.position * getHeight());
        float glowAlpha = (float) Math.max(0, Math.min(glowSpring.position, 1));

        canvas.save();
        canvas.translate(0, yOffset);
        if (scale != 1f) {
            canvas.scale(scale, scale, getWidth() / 2f, getHeight() / 2f);
        }

        String text = getText().toString();
        if (cachedTextWidth < 0) {
            cachedTextWidth = getPaint().measureText(text);
        }
        float viewW = Math.max(1f, getWidth() - getPaddingLeft() - getPaddingRight());

        if (letterCapable && letters != null && letterScaleSprings != null) {
            drawLetterEmphasis(canvas, viewW, glowAlpha);
        } else {
            drawWordGradient(canvas, text, glowAlpha);
        }

        canvas.restore();
    }

    // Spicy EX Android: gradient-position in -20..100 space; glow nudges the sung edge toward
    // full white (0.85 + 0.15*glow alpha), matching resolveShader in SpicyAnimatedTextView.
    private void drawWordGradient(Canvas canvas, String text, float glowAlpha) {
        float p = -0.20f + 1.20f * progress;
        float dStop = p + FADE_WIDTH;
        float drawX = getPaddingLeft();
        float shaderW = Math.max(1f, cachedTextWidth);

        // Soft bloom halo around the sung text — a genuine light-glow, drawn via a text
        // shadow layer (hardware-accelerated-safe) modulated by the glow spring.
        if (glowAlpha > 0.02f && !backgroundMode) {
            float baseline = getBaseline();
            int haloAlpha = Math.round(Math.max(0f, Math.min(1f, glowAlpha)) * 150f);
            bloomPaint.setShader(null);
            bloomPaint.setColor(0x00FFFFFF);
            bloomPaint.setShadowLayer(bloomRadiusPx * (0.6f + 0.9f * glowAlpha), 0, 0,
                    (haloAlpha << 24) | 0x00FFFFFF);
            canvas.drawText(text, drawX, baseline, bloomPaint);
            bloomPaint.clearShadowLayer();
        }

        // Spicy EX: startAlpha = round(255 * (0.85 + 0.15 * glow) * brightness)
        // glow pushes the sung edge toward full white
        int glowBrightAlpha = Math.round(255f * (0.85f + 0.15f * Math.max(0f, Math.min(1f, glowAlpha))));
        int glowBrightColor = (glowBrightAlpha << 24) | 0x00FFFFFF;

        LinearGradient shader;
        if (dStop <= 0f) {
            shader = new LinearGradient(drawX, 0, drawX + shaderW, 0, dimColor, dimColor, Shader.TileMode.CLAMP);
        } else if (p >= 1f) {
            shader = new LinearGradient(drawX, 0, drawX + shaderW, 0, glowBrightColor, glowBrightColor, Shader.TileMode.CLAMP);
        } else {
            float b = Math.max(0f, p);
            float d = Math.min(1f, dStop);
            shader = new LinearGradient(drawX, 0, drawX + shaderW, 0,
                    new int[]{glowBrightColor, dimColor},
                    new float[]{b, Math.max(b + 0.001f, d)},
                    Shader.TileMode.CLAMP);
        }

        getPaint().setShader(shader);
        canvas.drawText(text, drawX, getBaseline(), getPaint());
    }

    // Smooth left-to-right sweep: each letter's brightness is sampled from a continuous
    // gradient across the whole word (same sweep math as drawWordGradient), so the bright
    // edge glides across letters instead of snapping per-letter. The active letter still
    // pops in scale/glow for emphasis.
    private void drawLetterEmphasis(Canvas canvas, float viewW, float wordGlowAlpha) {
        float baseline = getBaseline();
        float totalTextW = cachedTextWidth;
        float startX = getPaddingLeft() + Math.max(0f, (viewW - totalTextW) / 2f);

        // Continuous sweep position in the -0.20..1.0 space (matches drawWordGradient).
        float p = -0.20f + 1.20f * progress;
        float dStop = p + FADE_WIDTH;

        int n = letters.length;
        float[] centers = new float[n];
        float cursor = startX;
        float denom = Math.max(1f, totalTextW);
        for (int i = 0; i < n; i++) {
            float letterW = getPaint().measureText(letters[i]);
            centers[i] = (cursor + letterW / 2f - startX) / denom;
            cursor += letterW;
        }

        int activeIndex = -1;
        for (int i = 0; i < n; i++) {
            if (letterProgress[i] > 0 && letterProgress[i] < 1) {
                activeIndex = i;
                break;
            }
        }
        float activeLetterPct = activeIndex >= 0 ? letterProgress[activeIndex] : 0;

        cursor = startX;
        for (int i = 0; i < n; i++) {
            String letter = letters[i];
            float letterW = getPaint().measureText(letter);

            // Continuous brightness across the word for a smooth left-to-right sweep.
            float f = centers[i];
            float a = (dStop - f) / FADE_WIDTH;
            a = Math.max(0f, Math.min(1f, a));
            int lo = (dimColor >> 24) & 0xFF;
            int hi = (brightColor >> 24) & 0xFF;
            int alpha = (int) (lo + (hi - lo) * a);
            int letterColor = (alpha << 24) | 0x00FFFFFF;

            float lScale = (float) letterScaleSprings[i].position;
            float lGlow = (float) Math.max(0, Math.min(letterGlowSprings[i].position, 1));
            // Per-letter vertical wave: the active letter lifts, neighbours follow with falloff.
            float lYOffset = 0f;

            if (activeIndex >= 0 && i != activeIndex) {
                int dist = Math.abs(i - activeIndex);
                // SpicyLyrics: falloff = 1/(1+dist^2.8) (scale), 1/(1+dist*0.9) (glow)
                double scaleFalloff = 1.0 / (1.0 + Math.pow(dist, 2.8));
                double glowFalloff = 1.0 / (1.0 + dist * 0.9);
                double yFalloff = 1.0 / (1.0 + Math.pow(dist, 1.8));
                float baseScale = (float) LETTER_SCALE_SPLINE.at(activeLetterPct);
                float resting = (float) LETTER_SCALE_SPLINE.at(0);
                float targetScale = resting + (baseScale - resting) * (float) scaleFalloff;
                lScale = Math.max(lScale, targetScale);
                lGlow = Math.max(lGlow, (float) (glowFalloff * LETTER_GLOW_MULTIPLIER));
                lYOffset = (float) (LETTER_YOFFSET_SPLINE.at(activeLetterPct) * yFalloff);
            } else if (i == activeIndex) {
                lYOffset = (float) LETTER_YOFFSET_SPLINE.at(activeLetterPct);
            }

            canvas.save();
            float cx = cursor + letterW / 2f;
            float cy = baseline / 2f;
            if (lYOffset != 0f) {
                canvas.translate(0, lYOffset * getHeight());
            }
            if (lScale != 1f) {
                canvas.scale(lScale, lScale, cx, cy);
            }

            if (lGlow > 0.01f) {
                // Soft bloom via shadow layer instead of a hard white overdraw, so the
                // emphasised letter radiates light rather than turning into a flat block.
                float g = (float) Math.min(lGlow, 1.0);
                int haloAlpha = (int) (g * 165);
                glowPaint.setShader(null);
                glowPaint.setColor(0x00FFFFFF);
                glowPaint.setShadowLayer(bloomRadiusPx * (0.7f + 1.1f * g), 0, 0,
                        (haloAlpha << 24) | 0x00FFFFFF);
                canvas.drawText(letter, cursor, baseline, glowPaint);
                glowPaint.clearShadowLayer();
            }

            getPaint().setShader(null);
            getPaint().setColor(letterColor);
            canvas.drawText(letter, cursor, baseline, getPaint());

            canvas.restore();
            cursor += letterW;
        }
    }

    private static float easeSinOut(float x) {
        return (float) Math.sin((x * Math.PI) / 2.0);
    }

    public void updateState(long position, double deltaTime) {
        if (endTime <= startTime) {
            progress = position >= startTime ? 1f : 0f;
        } else {
            progress = (float) (position - startTime) / (float) (endTime - startTime);
            progress = Math.max(0f, Math.min(progress, 1f));
        }

        float scaleTarget = (float) SCALE_SPLINE.at(progress);
        float yOffsetTarget = (float) YOFFSET_SPLINE.at(progress);
        float glowTarget = (float) GLOW_SPLINE.at(progress);

        scaleSpring.finalPosition = scaleTarget;
        yOffsetSpring.finalPosition = yOffsetTarget;
        glowSpring.finalPosition = glowTarget;

        if (deltaTime > 0) {
            scaleSpring.update(deltaTime);
            yOffsetSpring.update(deltaTime);
            glowSpring.update(deltaTime);
        }

        // Update letter emphasis
        if (letterCapable && letterScaleSprings != null) {
            for (int i = 0; i < letters.length; i++) {
                float lp;
                if (letterEndTimes[i] <= letterStartTimes[i]) {
                    lp = position >= letterStartTimes[i] ? 1f : 0f;
                } else {
                    lp = (float) (position - letterStartTimes[i]) / (float) (letterEndTimes[i] - letterStartTimes[i]);
                    lp = Math.max(0f, Math.min(lp, 1f));
                }
                letterProgress[i] = lp;

                float lScaleTarget = (float) LETTER_SCALE_SPLINE.at(lp);
                float lGlowTarget = (float) GLOW_SPLINE.at(lp);

                letterScaleSprings[i].finalPosition = lScaleTarget;
                letterGlowSprings[i].finalPosition = lGlowTarget;

                if (deltaTime > 0) {
                    letterScaleSprings[i].update(deltaTime);
                    letterGlowSprings[i].update(deltaTime);
                }
            }
        }

        boolean wasActive = isActive;
        isActive = position >= startTime && position < endTime;
        isPast = position >= endTime;

        if (isActive || wasActive || (progress > 0 && progress < 1)) {
            postInvalidate();
        }
    }

    public void resetState() {
        scaleSpring.set(0.95);
        yOffsetSpring.set(0);
        glowSpring.set(0);
        progress = 0;
        isActive = false;
        isPast = false;
        letterCapable = false;
        letters = null;
        letterScaleSprings = null;
        letterGlowSprings = null;
        letterProgress = null;
        letterStartTimes = null;
        letterEndTimes = null;
        getPaint().setShader(null);
        getPaint().setColor(brightColor);
        invalidate();
    }

    private static Spline makeSpline(List<Double> times, List<Double> values) {
        return new Spline(new ArrayList<>(times), new ArrayList<>(values));
    }
}
