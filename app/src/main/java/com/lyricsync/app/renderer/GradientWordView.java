package com.lyricsync.app.renderer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.TypedValue;
import android.widget.TextView;

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
    private static final double YOFFSET_FREQ = 1.55;
    private static final double YOFFSET_DAMP = 0.42;
    private static final double GLOW_FREQ = 1.25;
    private static final double GLOW_DAMP = 0.5;

    // Motion parameters for the default SPRING variant. setAnimConfig() swaps these
    // from the resolved motion×effect combination.
    private AnimConfig animConfig = AnimConfig.defaultConfig();
    private Spline scaleSpline = animConfig.scaleSpline;
    private Spline yOffsetSpline = animConfig.yOffsetSpline;
    private Spline glowSplineTable = animConfig.glowSpline;
    private Spline letterScaleSpline = animConfig.letterScaleSpline;
    private Spline letterYOffsetSpline = animConfig.letterYOffsetSpline;
    private boolean springsEnabled = animConfig.springsEnabled;
    private boolean glowEnabled = animConfig.glowEnabled;
    private float glowBoost = animConfig.glowBoost;
    private boolean letterEmphasisEnabled = animConfig.letterEmphasis;
    private long letterMinDuration = animConfig.letterMinDuration;
    private double scaleFreq = animConfig.scaleFreq;
    private double scaleDamp = animConfig.scaleDamp;

    // Letter-level emphasis (SpicyLyrics IsLetterCapable + Emphasize)
    // Min word duration comes from the resolved AnimConfig (letterMinDuration): SPRING/
    // BUBBLE use SpicyLyrics' 1000ms, TYPEWRITER strikes letters on every word.
    private static final int TYPEWRITER_DIM = 0x2EFFFFFF; // 0.18 alpha "paper" before the strike
    // SpicyLyrics non-SLM IsLetterCapable: only duration >= 1000, no count cap.
    // Distribute the per-letter emphasis across the FULL word so the last letter does
    // not finish early and snap back to idle (the old 250ms end trim left a dead zone).
    private static final long LETTER_SUBSTRACT_START = 0;
    private static final long LETTER_SUBSTRACT_END = 0;
    // SpicyLyrics Emphasize: LetterGlowMultiplier_Opacity = 185 (percent, clamped to 100)
    private static final float LETTER_GLOW_MULTIPLIER = 1.85f;
    private static final float LETTER_IDLE_SCALE = 0.95f;

    private long startTime;
    private long endTime;
    private float progress;
    private boolean isActive;
    private float cachedTextWidth = -1f;
    private float appliedSizeSp = -1f;
    private android.graphics.Typeface appliedTypeface = null;

    private final Spring scaleSpring;
    private final Spring yOffsetSpring;
    private final Spring glowSpring;

    // Wave variant: the Y offset is a travelling sine along the line, driven by the
    // playback clock (not a progress spline), with amplitude eased so the ripple
    // never pops in/out on line changes.
    private final Spring waveAmpSpring;
    private int wordIndex;
    private boolean lineActive;
    private long lastPositionMs;
    private boolean waveStyle;
    private boolean binaryLetters;
    private boolean echoStyle;
    private boolean shimmerStyle;
    private boolean flickerStyle;
    private boolean swingStyle;
    private boolean classicStyle;
    private boolean scatterStyle;
    private boolean spinStyle;
    private boolean squashStyle;
    private boolean outlineStyle;
    private boolean flashStyle;
    private boolean trailStyle;
    private boolean sparkleStyle;
    private boolean fireworksStyle;
    private boolean weldingStyle;
    private boolean burningStyle;
    // Blur Trail: this frame's word yOffset plus the previous two frames'
    // values (ghost positions). frameYOffset mirrors the frameFlicker pattern
    // (computed once per onDraw, readable from drawEffects).
    private float frameYOffset;
    private float trailY1;
    private float trailY2;
    // Neon-flicker intensity for the current frame, computed once in onDraw so the
    // word path and the letter path flicker in sync within the same 70ms bucket.
    private float frameFlicker = 1f;
    private float[] letterScatter;
    private final Paint echoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sparklePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fireworksPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint weldingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint burningPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Reused line buffer for sparkle stars — zero per-frame allocation.
    private final float[] sparkleLines = new float[8];
    // Reused particle trail buffer (Fireworks 36 particles + Welding sparks)
    // and flame tongue path (Burning) — zero per-frame allocation.
    private final float[] particleLines = new float[144];
    private final Path flamePath = new Path();

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
        scaleSpring = new Spring(0.95, animConfig.scaleDamp, animConfig.scaleFreq);
        yOffsetSpring = new Spring(0, YOFFSET_DAMP, YOFFSET_FREQ);
        glowSpring = new Spring(0, GLOW_DAMP, GLOW_FREQ);
        waveAmpSpring = new Spring(0, 0.60, 1.20);
    }

    /** Switch the resolved motion×effect combination. Safe to call before show. */
    public void setAnimConfig(AnimConfig config) {
        animConfig = config;
        scaleSpline = config.scaleSpline;
        yOffsetSpline = config.yOffsetSpline;
        glowSplineTable = config.glowSpline;
        letterScaleSpline = config.letterScaleSpline;
        letterYOffsetSpline = config.letterYOffsetSpline;
        springsEnabled = config.springsEnabled;
        glowEnabled = config.glowEnabled;
        glowBoost = config.glowBoost;
        letterEmphasisEnabled = config.letterEmphasis;
        letterMinDuration = config.letterMinDuration;
        scaleFreq = config.scaleFreq;
        scaleDamp = config.scaleDamp;
        waveStyle = config.wave;
        binaryLetters = config.binaryLetters;
        echoStyle = config.echo;
        shimmerStyle = config.shimmer;
        flickerStyle = config.flicker;
        swingStyle = config.swing;
        classicStyle = config.classic;
        scatterStyle = config.scatter;
        spinStyle = config.spin;
        squashStyle = config.squash;
        outlineStyle = config.outline;
        flashStyle = config.flash;
        trailStyle = config.trail;
        sparkleStyle = config.sparkle;
        fireworksStyle = config.fireworks;
        weldingStyle = config.welding;
        burningStyle = config.burning;
        // Re-tune the scale spring for the combination's bounce character.
        scaleSpring.dampingRatio = config.scaleDamp;
        scaleSpring.frequency = config.scaleFreq;
        if (!springsEnabled) {
            // Pin every spring to rest so nothing drifts when updates skip them.
            scaleSpring.set(1.0);
            yOffsetSpring.set(0);
            glowSpring.set(0);
        }
        if (!letterEmphasisEnabled) {
            letterCapable = false;
            letters = null;
            letterScaleSprings = null;
            letterGlowSprings = null;
        }
        cachedTextWidth = -1f;
        invalidate();
    }

    public void setTiming(long startTime, long endTime) {
        this.startTime = startTime;
        this.endTime = endTime;
    }

    /** Index of this word within its line; the Wave variant uses it as ripple phase. */
    public void setWordIndex(int index) {
        wordIndex = index;
    }

    /** Whether the line containing this word is currently active (Wave amplitude). */
    public void setLineActive(boolean active) {
        lineActive = active;
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
            echoPaint.setTextSize(getPaint().getTextSize());
            echoPaint.setTypeface(typeface);
            shimmerPaint.setTextSize(getPaint().getTextSize());
            shimmerPaint.setTypeface(typeface);
            outlinePaint.setTextSize(getPaint().getTextSize());
            outlinePaint.setTypeface(typeface);
            flashPaint.setTextSize(getPaint().getTextSize());
            flashPaint.setTypeface(typeface);
            trailPaint.setTextSize(getPaint().getTextSize());
            trailPaint.setTypeface(typeface);
            sparklePaint.setTextSize(getPaint().getTextSize());
            sparklePaint.setTypeface(typeface);
            burningPaint.setTextSize(getPaint().getTextSize());
            burningPaint.setTypeface(typeface);
            bloomRadiusPx = Math.max(2f, getPaint().getTextSize() * 0.14f);
            // Generous padding: word scale peaks (~1.10-1.14x) and letter pops draw
            // outside the raw text bounds, so give the glyph room before the parent's
            // (disabled) clipping ever matters. Prevents "cut off" letters.
            int horizontalPad = Math.max(2, Math.round(getPaint().getTextSize() * 0.10f));
            int verticalPad = Math.max(2, Math.round(getPaint().getTextSize() * 0.16f));
            setPadding(horizontalPad, verticalPad, horizontalPad, verticalPad);
            appliedSizeSp = sizeSp;
            appliedTypeface = typeface;
            cachedTextWidth = -1f;
        }
    }

    public void initLetterEmphasis(String wordText, long wordStart, long wordEnd) {
        long duration = wordEnd - wordStart - LETTER_SUBSTRACT_START - LETTER_SUBSTRACT_END;
        if (!letterEmphasisEnabled
                || !isLetterCapable(wordText.length(), duration)) {
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
            letterScaleSprings[i] = new Spring(LETTER_IDLE_SCALE, scaleDamp, scaleFreq);
            letterGlowSprings[i] = new Spring(0, GLOW_DAMP, GLOW_FREQ);
        }

        if (scatterStyle) {
            // Deterministic per-letter amplitude spread (0.55..1.35): same word always
            // scatters the same way, so the effect looks choreographed, not noisy.
            letterScatter = new float[n];
            for (int i = 0; i < n; i++) {
                double h = Math.sin(i * 12.9898 + 78.233) * 43758.5453;
                double frac = h - Math.floor(h);
                letterScatter[i] = 0.55f + 0.80f * (float) frac;
            }
        } else {
            letterScatter = null;
        }
    }

    private boolean isLetterCapable(int letterCount, long duration) {
        // SpicyLyrics non-SLM IsLetterCapable: no letter count limit; the duration
        // threshold is per-variant (TYPEWRITER: none — it must strike every word).
        if (letterCount <= 0) return false;
        return duration >= letterMinDuration;
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
        float yOffset;
        if (waveStyle) {
            // Travelling ripple: phase advances with the playback clock, each word
            // trails its neighbour so the wave appears to flow along the line.
            double angle = lastPositionMs * 0.0075 - wordIndex * 0.85;
            yOffset = (float) (Math.sin(angle) * waveAmpSpring.position * 0.055 * getHeight());
        } else {
            yOffset = (float) (yOffsetSpring.position * getHeight());
        }
        frameYOffset = yOffset;
        float glowAlpha = (float) Math.max(0, Math.min(glowSpring.position, 1));

        // FLICKER: neon-sign sputter. Deterministic per 70ms bucket so every frame in
        // the same bucket agrees; occasional deep dips sell the failing-tube feel.
        frameFlicker = 1f;
        if (flickerStyle && progress > 0f && progress < 1f) {
            double bucket = Math.floor(lastPositionMs / 70.0) + wordIndex * 31.0;
            double h = Math.sin(bucket * 12.9898 + 4.1414) * 43758.5453;
            double frac = h - Math.floor(h);
            frameFlicker = (frac < 0.14) ? 0.35f + 0.5f * (float) (frac / 0.14)
                    : 0.85f + 0.15f * (float) frac;
        }
        glowAlpha *= frameFlicker;

        canvas.save();
        canvas.translate(0, yOffset);
        if (squashStyle && progress > 0f && progress < 0.35f) {
            // Squash-and-stretch at word start: vertical squash + slight horizontal
            // stretch, pivoting at the word's own centre (no positional movement).
            float sq = 1f - progress / 0.35f;
            canvas.scale(scale * (1f + 0.08f * sq), scale * (1f - 0.15f * sq),
                    getWidth() / 2f, getHeight() / 2f);
        } else if (scale != 1f) {
            canvas.scale(scale, scale, getWidth() / 2f, getHeight() / 2f);
        }
        if (swingStyle && progress > 0 && progress < 1) {
            // Decaying pendulum rock driven by the word's own progress: deterministic,
            // settles exactly when the word ends. Pivots near the top like a hanging sign.
            float angle = 5f * (float) Math.sin(progress * Math.PI * 2.5) * (1f - progress);
            canvas.rotate(angle, getWidth() / 2f, getHeight() * 0.15f);
        }

        String text = getText().toString();
        if (cachedTextWidth < 0) {
            cachedTextWidth = getPaint().measureText(text);
        }
        float viewW = Math.max(1f, getWidth() - getPaddingLeft() - getPaddingRight());

        if (letterCapable && letters != null && letterScaleSprings != null) {
            drawLetterEmphasis(canvas, viewW);
        } else {
            drawWordGradient(canvas, text, glowAlpha);
        }

        canvas.restore();

        trailY2 = trailY1;
        trailY1 = frameYOffset;
    }

    // Spicy EX Android: gradient-position in -20..100 space; glow nudges the sung edge toward
    // full white (0.85 + 0.15*glow alpha), matching resolveShader in SpicyAnimatedTextView.
    private void drawWordGradient(Canvas canvas, String text, float glowAlpha) {
        float p = -0.20f + 1.20f * progress;
        float dStop = p + FADE_WIDTH;
        float drawX = getPaddingLeft();
        float shaderW = Math.max(1f, cachedTextWidth);
        float baseline = getBaseline();

        // CLASSIC: old-school karaoke. No sweep — the whole word flips dim -> bright
        // the instant it starts being sung. Falls through to drawEffects below so
        // Classic × Echo/Shimmer combos still get their overlay.
        if (classicStyle) {
            getPaint().setShader(null);
            getPaint().setColor(progress > 0f ? brightColor : dimColor);
            canvas.drawText(text, drawX, baseline, getPaint());
            drawEffects(canvas, text, drawX, baseline, shaderW, p);
            return;
        }

        // Soft bloom halo around the sung text — a genuine light-glow, drawn via a text
        // shadow layer (hardware-accelerated-safe) modulated by the glow spring.
        if (glowEnabled && glowAlpha > 0.02f && !backgroundMode) {
            int haloAlpha = Math.round(Math.max(0f, Math.min(1f, glowAlpha)) * 150f * glowBoost);
            bloomPaint.setShader(null);
            bloomPaint.setColor(0x00FFFFFF);
            bloomPaint.setShadowLayer(bloomRadiusPx * glowBoost * (0.6f + 0.9f * glowAlpha), 0, 0,
                    (Math.min(255, haloAlpha) << 24) | 0x00FFFFFF);
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
        canvas.drawText(text, drawX, baseline, getPaint());

        drawEffects(canvas, text, drawX, baseline, shaderW, p);
    }

    /** Effect overlays (Echo ghosts, Shimmer band) drawn over the just-filled word.
     *  Called from both the word-gradient and the letter-emphasis paths so combos
     *  work identically whichever motion path renders the fill. */
    private void drawEffects(Canvas canvas, String text, float drawX, float baseline,
                             float shaderW, float p) {
        // ECHO: two fading ghost copies trailing above the active word, like reverb.
        // Drawn after the main text so they read as afterimages of the sung edge.
        if (echoStyle && progress > 0f && progress < 1f && !backgroundMode) {
            float fade = (float) Math.sin(progress * Math.PI); // in/out over the word
            for (int t = 1; t <= 2; t++) {
                int alpha = Math.round((t == 1 ? 0.10f : 0.05f) * 255f * fade);
                echoPaint.setShader(new LinearGradient(drawX, 0, drawX + shaderW, 0,
                        (alpha << 24) | 0x00FFFFFF, 0x00000000, Shader.TileMode.CLAMP));
                canvas.drawText(text, drawX, baseline - t * 0.045f * getHeight(), echoPaint);
            }
            echoPaint.setShader(null);
        }

        // SHIMMER: a narrow light band that sweeps across the already-sung part of
        // the word once per word, trailing the fill edge.
        if (shimmerStyle && progress > 0.05f && progress < 0.98f && !backgroundMode) {
            float band = (progress - 0.05f) / 0.93f;          // 0..1 across the word
            float cx = drawX + band * shaderW;
            float halfW = 0.06f * shaderW;
            shimmerPaint.setShader(new LinearGradient(cx - halfW, 0, cx + halfW, 0,
                    new int[]{0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            // Clip the band to the sung region only (left of the fill edge).
            canvas.save();
            canvas.clipRect(drawX, 0, drawX + Math.max(0f, p) * shaderW + 2f, getHeight());
            canvas.drawText(text, drawX, baseline, shimmerPaint);
            canvas.restore();
            shimmerPaint.setShader(null);
        }

        // OUTLINE POP: a brief stroke ring around the word at its start.
        if (outlineStyle && progress > 0f && progress < 0.22f && !backgroundMode) {
            int alpha = Math.round((1f - progress / 0.22f) * 0.55f * 255f);
            outlinePaint.setStyle(Paint.Style.STROKE);
            outlinePaint.setStrokeWidth(Math.max(1.5f, outlinePaint.getTextSize() * 0.03f));
            outlinePaint.setColor((alpha << 24) | 0x00FFFFFF);
            canvas.drawText(text, drawX, baseline, outlinePaint);
            outlinePaint.setStyle(Paint.Style.FILL);
        }

        // COLOR FLASH: a bright white flare over the word at its start.
        if (flashStyle && progress > 0f && progress < 0.15f && !backgroundMode) {
            int alpha = Math.round((1f - progress / 0.15f) * 0.50f * 255f);
            flashPaint.setShader(null);
            flashPaint.setColor((alpha << 24) | 0x00FFFFFF);
            canvas.drawText(text, drawX, baseline, flashPaint);
        }

        // BLUR TRAIL: two ghost copies at the word's previous vertical positions,
        // drawn only while the word is moving fast — reads as motion blur.
        if (trailStyle && progress > 0f && progress < 1f && !backgroundMode) {
            float d1 = trailY1 - frameYOffset;
            float d2 = trailY2 - frameYOffset;
            float gate = 0.0035f * getHeight();
            if (Math.abs(d1) > gate) {
                trailPaint.setShader(null);
                trailPaint.setColor(0x2EFFFFFF); // 0.18 alpha
                canvas.drawText(text, drawX, baseline + d1, trailPaint);
                if (Math.abs(d2) > gate) {
                    trailPaint.setColor(0x17FFFFFF); // 0.09 alpha
                    canvas.drawText(text, drawX, baseline + d2, trailPaint);
                }
            }
        }

        // SPARKLE: up to six tiny four-point stars popping along the sung word.
        // Positions are deterministic from the word seed; the line buffer is
        // reused, so this costs at most 6 small line-draws with zero allocation.
        // Clipped to the already-sung region (same as Shimmer) so the bright
        // stars never twinkle over the dimmed upcoming lyric.
        if (sparkleStyle && progress > 0f && progress < 0.85f && !backgroundMode) {
            float ts = sparklePaint.getTextSize();
            sparklePaint.setStyle(Paint.Style.STROKE);
            sparklePaint.setStrokeWidth(Math.max(1f, ts * 0.02f));
            sparklePaint.setStrokeCap(Paint.Cap.ROUND);
            int seed = wordIndex * 31 + text.length() * 7;
            canvas.save();
            canvas.clipRect(drawX, 0, drawX + Math.max(0f, p) * shaderW + 2f, getHeight());
            for (int i = 0; i < 6; i++) {
                float lp = (progress - i * 0.09f) / 0.30f;
                if (lp <= 0f || lp >= 1f) continue;
                float hx = drawX + hash01(seed + i) * shaderW;
                float hy = baseline - ts * (0.15f + 0.45f * hash01(seed + i + 100))
                        - lp * 0.25f * ts;
                float r = ts * 0.06f * (1f - 0.5f * lp);
                int alpha = Math.round((1f - lp) * 0.70f * 255f);
                sparklePaint.setColor((alpha << 24) | 0x00FFFFFF);
                sparkleLines[0] = hx - r; sparkleLines[1] = hy;
                sparkleLines[2] = hx + r; sparkleLines[3] = hy;
                sparkleLines[4] = hx;     sparkleLines[5] = hy - r;
                sparkleLines[6] = hx;     sparkleLines[7] = hy + r;
                canvas.drawLines(sparkleLines, sparklePaint);
            }
            canvas.restore();
            sparklePaint.setStyle(Paint.Style.FILL);
        }

        // FIREWORKS: a 36-particle burst celebrating the word finishing. Particle
        // parabolas are analytic (seed direction/speed from hash01, phase in real
        // seconds from the karaoke clock), so the burst survives seeks with zero
        // per-frame state. Distances scale with text size so the burst reads big.
        if (fireworksStyle && !backgroundMode && endTime > startTime) {
            float tSec = (lastPositionMs - endTime) / 1000f;
            if (tSec > 0f && tSec < 0.9f) {
                float ts = getPaint().getTextSize();
                float ox = drawX + shaderW / 2f;
                float oy = baseline - 0.5f * ts;
                float g = 2.2f * ts;
                int seed = wordIndex * 31 + text.length() * 7;
                int baseAlpha = Math.round((1f - tSec / 0.9f) * 0.9f * 255f);
                fireworksPaint.setStyle(Paint.Style.STROKE);
                fireworksPaint.setStrokeWidth(Math.max(1.5f, ts * 0.03f));
                fireworksPaint.setStrokeCap(Paint.Cap.ROUND);
                for (int i = 0; i < 36; i++) {
                    float ang = hash01(seed + i * 2) * 6.2832f;
                    float spd = (1.2f + 1.4f * hash01(seed + i * 2 + 1)) * ts;
                    float vx = (float) Math.cos(ang) * spd;
                    float vy = (float) Math.sin(ang) * spd - 0.8f * ts;
                    float t0 = Math.max(0f, tSec - 0.09f);   // trail 90ms behind
                    particleLines[i * 4]     = ox + vx * t0;
                    particleLines[i * 4 + 1] = oy + vy * t0 + 0.5f * g * t0 * t0;
                    particleLines[i * 4 + 2] = ox + vx * tSec;
                    particleLines[i * 4 + 3] = oy + vy * tSec + 0.5f * g * tSec * tSec;
                    int color = i % 3 == 0 ? 0x00FFFFFF : (i % 3 == 1 ? 0x00FFD54F : 0x00FF7043);
                    fireworksPaint.setColor((baseAlpha << 24) | color);
                    canvas.drawLines(particleLines, i * 4, 4, fireworksPaint);
                }
                fireworksPaint.setStyle(Paint.Style.FILL);
            }
        }

        // WELDING: the karaoke fill edge becomes a welding torch — a flickering
        // white-hot core riding the sweep edge, spitting long amber sparks with
        // gravity. Crackle comes from deterministic 50/260ms clock ticks; all
        // distances scale with the text size so sparks stay visible at any font.
        if (weldingStyle && !backgroundMode && progress > 0f && progress < 1f) {
            float ts = getPaint().getTextSize();
            float wx = drawX + Math.max(0f, p) * shaderW;
            float wy = baseline - 0.35f * ts;
            float fl = hash01((int) (lastPositionMs / 50 & 0x7fffffff));
            weldingPaint.setStyle(Paint.Style.FILL);
            // Arc halo first, white-hot core on top.
            float cr = ts * (0.08f + 0.06f * fl);
            weldingPaint.setColor(0x8F90CAF9); // 0.56 alpha blue arc
            canvas.drawCircle(wx, wy, cr * 2.4f, weldingPaint);
            weldingPaint.setColor(0xE6FFFFFF); // 0.9 alpha white core
            canvas.drawCircle(wx, wy, cr, weldingPaint);
            // Twelve staggered sparks, 380ms life each, re-seeded every cycle.
            weldingPaint.setStrokeCap(Paint.Cap.ROUND);
            float g = 5.0f * ts;
            float strokeW = Math.max(2f, ts * 0.035f);
            weldingPaint.setStrokeWidth(strokeW);
            weldingPaint.setStyle(Paint.Style.STROKE);
            for (int i = 0; i < 12; i++) {
                float local = ((lastPositionMs + i * 41) % 380) / 380f;
                int s = (int) ((lastPositionMs + i * 41) / 380) * 12 + i * 13 + wordIndex * 7;
                float ang = -2.6f + 2.0f * hash01(s);            // wide upward fan
                float spd = (1.2f + 1.2f * hash01(s + 50)) * ts;
                float vx = (float) Math.cos(ang) * spd;
                float vy = (float) Math.sin(ang) * spd;
                float lt = local * 0.38f;                        // life in seconds
                float t0 = Math.max(0f, lt - 0.05f);             // trail 50ms behind
                float hx = wx + vx * lt;
                float hy = wy + vy * lt + 0.5f * g * lt * lt;
                particleLines[0] = wx + vx * t0;
                particleLines[1] = wy + vy * t0 + 0.5f * g * t0 * t0;
                particleLines[2] = hx;
                particleLines[3] = hy;
                int alpha = Math.round((1f - local * 0.85f) * 0.95f * 255f);
                weldingPaint.setColor((alpha << 24) | 0x00FFB300);
                canvas.drawLines(particleLines, 0, 4, weldingPaint);
                // Hot white-yellow head so each spark reads as a flying ember.
                weldingPaint.setStyle(Paint.Style.FILL);
                weldingPaint.setColor((alpha << 24) | 0x00FFE082);
                canvas.drawCircle(hx, hy, strokeW * 0.9f, weldingPaint);
                weldingPaint.setStyle(Paint.Style.STROKE);
            }
            weldingPaint.setStyle(Paint.Style.FILL);
        }

        // TERBAKAR: the sung part of the word ignites. The fire gradient is clipped
        // to the sung region (Shimmer pattern) so the dimmed upcoming lyric keeps
        // its normal look; flame tongues flicker above the sung letters.
        if (burningStyle && !backgroundMode && progress > 0f) {
            float ts = burningPaint.getTextSize();
            float sungW = Math.max(0f, p) * shaderW;
            if (sungW > 2f) {
                float flick = 0.75f + 0.15f * (float) Math.sin(lastPositionMs * 0.02);
                int alpha = Math.round(flick * 255f);
                burningPaint.setShader(new LinearGradient(0, baseline - ts, 0, baseline,
                        new int[]{(alpha << 24) | 0x00FFD54F, (alpha << 24) | 0x00FF7043,
                                (alpha << 24) | 0x00E53935},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                canvas.save();
                canvas.clipRect(drawX, 0, drawX + sungW + 2f, getHeight());
                canvas.drawText(text, drawX, baseline, burningPaint);
                canvas.restore();
                burningPaint.setShader(null);

                // Flame tongues: wobbling teardrops rising from the sung letters,
                // one reused Path, heights/phases deterministic per word.
                int seed = wordIndex * 31 + text.length() * 7;
                int tongues = Math.min(12, Math.max(1, (int) (sungW / (ts * 0.5f))));
                burningPaint.setStyle(Paint.Style.FILL);
                float slot = sungW / tongues;
                for (int i = 0; i < tongues; i++) {
                    float fx = drawX + (i + 0.2f + 0.6f * hash01(seed + i)) * slot;
                    float base = baseline - ts * 0.75f;
                    float h = ts * (0.10f + 0.20f * hash01(seed + i + 40))
                            * (0.7f + 0.3f * (float) Math.sin(lastPositionMs * 0.015 + i * 1.7f));
                    float wob = (float) Math.sin(lastPositionMs * 0.012 + i * 2.3f) * ts * 0.04f;
                    float w = ts * 0.07f;
                    flamePath.rewind();
                    flamePath.moveTo(fx - w, base);
                    flamePath.quadTo(fx - w * 0.6f, base - h * 0.5f, fx + wob, base - h);
                    flamePath.quadTo(fx + w * 0.6f, base - h * 0.5f, fx + w, base);
                    flamePath.close();
                    int fa = Math.round((0.55f + 0.25f
                            * (float) Math.sin(lastPositionMs * 0.02 + i)) * 255f);
                    burningPaint.setColor((fa << 24) | (i % 2 == 0 ? 0x00FF7043 : 0x00FFD54F));
                    canvas.drawPath(flamePath, burningPaint);
                }
            }
        }
    }

    // Smooth left-to-right sweep: each letter's brightness is sampled from a continuous
    // gradient across the whole word (same sweep math as drawWordGradient), so the bright
    // edge glides across letters instead of snapping per-letter. The active letter still
    // pops in scale/glow for emphasis.
    private void drawLetterEmphasis(Canvas canvas, float viewW) {
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

            int letterColor;
            if (binaryLetters) {
                // Typewriter: each letter strikes in as a binary faint -> bright switch,
                // no continuous sweep — the pop carries the motion. Unstruck letters
                // sit at a faint "paper" alpha so the strike reads as typing, not
                // merely brightening an already-visible word.
                boolean struck = letterProgress[i] > 0;
                letterColor = struck ? brightColor
                        : (backgroundMode ? dimColor : TYPEWRITER_DIM);
            } else {
                // Continuous brightness across the word for a smooth left-to-right sweep.
                float f = centers[i];
                float a = (dStop - f) / FADE_WIDTH;
                a = Math.max(0f, Math.min(1f, a));
                int lo = (dimColor >> 24) & 0xFF;
                int hi = (brightColor >> 24) & 0xFF;
                int alpha = (int) (lo + (hi - lo) * a);
                letterColor = (alpha << 24) | 0x00FFFFFF;
            }

            // Unsung words rest at full size: pop-style splines start letters tiny
            // (0.0-0.2 at t=0) for the grow-in effect, but that rest value would
            // otherwise shrink/hide the upcoming words of the lyric. The springs
            // still slide to the spline start so the grow-in plays once the word
            // actually starts (progress > 0).
            float lScale = progress <= 0f ? 1f : (float) letterScaleSprings[i].position;
            float lGlow = (float) Math.max(0, Math.min(letterGlowSprings[i].position, 1))
                    * frameFlicker;
            // Per-letter vertical wave: the active letter lifts, neighbours follow with falloff.
            float lYOffset = 0f;

            // SCATTER: deterministic per-letter amplitude spread, applied to both the
            // lift and the pop so each letter jumps to its own height/strength.
            float scatter = (letterScatter != null && i < letterScatter.length)
                    ? letterScatter[i] : 1f;

            if (activeIndex >= 0 && i != activeIndex) {
                int dist = Math.abs(i - activeIndex);
                // SpicyLyrics: falloff = 1/(1+dist^2.8) (scale), 1/(1+dist*0.9) (glow)
                double scaleFalloff = 1.0 / (1.0 + Math.pow(dist, 2.8));
                double glowFalloff = 1.0 / (1.0 + dist * 0.9);
                double yFalloff = 1.0 / (1.0 + Math.pow(dist, 1.8));
                float baseScale = (float) letterScaleSpline.at(activeLetterPct);
                float resting = (float) letterScaleSpline.at(0);
                float targetScale = resting + (baseScale - resting) * (float) scaleFalloff;
                lScale = Math.max(lScale, 1f + (targetScale - 1f) * scatter);
                lGlow = Math.max(lGlow, (float) (glowFalloff * LETTER_GLOW_MULTIPLIER));
                lYOffset = (float) (letterYOffsetSpline.at(activeLetterPct) * yFalloff) * scatter;
            } else if (i == activeIndex) {
                lYOffset = (float) letterYOffsetSpline.at(activeLetterPct) * scatter;
                lScale = 1f + (lScale - 1f) * scatter;
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

            if (spinStyle) {
                // Spin Settle: rotation slaved to the letter's scale spring — the
                // letter starts tilted (resting scale 0.85) and unwinds through a
                // small counter-overshoot as the spring settles past 1.0.
                double sh = Math.sin(i * 12.9898 + 78.233) * 43758.5453;
                float sign = (sh - Math.floor(sh)) < 0.5 ? -1f : 1f;
                float angle = sign * 12f * (1.0f - lScale) / 0.15f;
                angle = Math.max(-14f, Math.min(14f, angle));
                if (angle != 0f) {
                    canvas.rotate(angle, cx, cy);
                }
            }

            if (glowEnabled && lGlow > 0.01f) {
                // Soft bloom via shadow layer instead of a hard white overdraw, so the
                // emphasised letter radiates light rather than turning into a flat block.
                float g = (float) Math.min(lGlow, 1.0);
                int haloAlpha = (int) (g * 165 * glowBoost);
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

        // Effect overlays ride over the letter fill too (combo feature).
        drawEffects(canvas, getText().toString(), startX, baseline, totalTextW, p);
    }

    public void updateState(long position, double deltaTime) {
        lastPositionMs = position;
        if (waveStyle) {
            waveAmpSpring.finalPosition = lineActive ? 1.0 : 0.0;
            if (deltaTime > 0) waveAmpSpring.update(deltaTime);
        }
        if (endTime <= startTime) {
            progress = position >= startTime ? 1f : 0f;
        } else {
            progress = (float) (position - startTime) / (float) (endTime - startTime);
            progress = Math.max(0f, Math.min(progress, 1f));
        }

        float scaleTarget = (float) scaleSpline.at(progress);
        float yOffsetTarget = (float) yOffsetSpline.at(progress);
        float glowTarget = (float) glowSplineTable.at(progress);

        if (springsEnabled) {
            scaleSpring.finalPosition = scaleTarget;
            yOffsetSpring.finalPosition = yOffsetTarget;
            glowSpring.finalPosition = glowTarget;

            if (deltaTime > 0) {
                scaleSpring.update(deltaTime);
                yOffsetSpring.update(deltaTime);
                glowSpring.update(deltaTime);
            }
        } else {
            // Variants without spring motion (Calm, Neon Glow) snap straight to the
            // spline values so the sweep itself stays the only movement.
            scaleSpring.set(scaleTarget);
            yOffsetSpring.set(yOffsetTarget);
            if (glowEnabled) {
                glowSpring.finalPosition = glowTarget;
                if (deltaTime > 0) glowSpring.update(deltaTime);
            } else {
                glowSpring.set(0);
            }
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

                float lScaleTarget = (float) letterScaleSpline.at(lp);
                float lGlowTarget = (float) glowSplineTable.at(lp);

                if (binaryLetters) {
                    // Typewriter: track the spline exactly. A lagging spring never
                    // reaches full size within a short letter slot, so letters would
                    // stay shrunk while sung and grow late, after the word passed.
                    letterScaleSprings[i].set(lScaleTarget);
                    letterGlowSprings[i].set(0);
                } else {
                    letterScaleSprings[i].finalPosition = lScaleTarget;
                    letterGlowSprings[i].finalPosition = lGlowTarget;

                    if (deltaTime > 0) {
                        letterScaleSprings[i].update(deltaTime);
                        letterGlowSprings[i].update(deltaTime);
                    }
                }
            }
        }

        boolean wasActive = isActive;
        isActive = position >= startTime && position < endTime;

        // Keep redrawing while the springs are still settling. The scale spring
        // overshoots past 1.0, so when progress hits 1.0 the word is still visually
        // enlarged and needs several more frames to relax back — otherwise it
        // freezes mid-animation and never returns to its resting size.
        if (isActive || wasActive || (progress > 0 && progress < 1) || springsSettling()) {
            postInvalidate();
        }
    }

    private boolean springsSettling() {
        if (waveStyle && (isSpringUnsettled(waveAmpSpring)
                || (lineActive && waveAmpSpring.position > 0.01))) {
            // The ripple keeps moving while the line is active and the amplitude
            // eases out after deactivation, so keep redrawing in both cases.
            return true;
        }
        if (isSpringUnsettled(scaleSpring) || isSpringUnsettled(yOffsetSpring)
                || isSpringUnsettled(glowSpring)) {
            return true;
        }
        if (letterCapable && letterScaleSprings != null) {
            for (int i = 0; i < letterScaleSprings.length; i++) {
                if (isSpringUnsettled(letterScaleSprings[i])
                        || isSpringUnsettled(letterGlowSprings[i])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isSpringUnsettled(Spring s) {
        return Math.abs(s.position - s.finalPosition) > 0.002 || Math.abs(s.velocity) > 0.01;
    }

    /** Deterministic 0..1 hash, same formula as the flicker/scatter seeds. */
    private static float hash01(int n) {
        double h = Math.sin(n * 12.9898 + 78.233) * 43758.5453;
        return (float) (h - Math.floor(h));
    }

    public void resetState() {
        scaleSpring.set(scaleSpline.at(0));
        yOffsetSpring.set(0);
        glowSpring.set(0);
        waveAmpSpring.set(0);
        trailY1 = 0;
        trailY2 = 0;
        progress = 0;
        isActive = false;
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

}
