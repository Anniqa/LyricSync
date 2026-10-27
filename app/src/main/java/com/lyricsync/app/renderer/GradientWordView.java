package com.lyricsync.app.renderer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.TypedValue;

import androidx.appcompat.widget.AppCompatTextView;

public class GradientWordView extends AppCompatTextView {
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
    // Clock at which the line last went inactive (Long.MIN_VALUE = not yet).
    private long lineOffMs = Long.MIN_VALUE;
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
    private boolean auroraStyle;
    private boolean meteorStyle;
    private boolean ringPulseStyle;
    private boolean neonStyle;
    private AnimStyleDef.Signature signature = AnimStyleDef.Signature.NONE;
    // Drop / Bounce landing detector: clock of the last touchdown + previous offset.
    private long landMs = Long.MIN_VALUE;
    private float prevLandY = 0f;
    // Reused pose buffers for MotionFx (zero per-frame allocation).
    private final float[] pose = new float[2];
    private final float[] pose3 = new float[3];
    // Generic text paint for motion ghosts (chroma split, zoom blur, reflection, glitch)
    // and a shape paint for motion props (ball, bubbles, droplets, caret).
    private final Paint fxTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fxShapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint neonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Neon Glow tube colours (hot pink, cyan, violet, lime, amber) - one per word.
    private static final int[] NEON_COLORS = {0x00FF4FC3, 0x0000E5FF, 0x00B388FF, 0x0076FF7A, 0x00FFC24F};
    private static final int[] ECHO_COLORS = {0x0080DEEA, 0x00B388FF, 0x00FF80AB};
    private static final int[] SPARKLE_COLORS = {0x00FFFFFF, 0x00FFE082, 0x0080DEEA, 0x00F8BBD0};
    // Blur Trail: this frame's word yOffset plus the previous two frames'
    // values (ghost positions). frameYOffset mirrors the frameFlicker pattern
    // (computed once per onDraw, readable from drawEffects).
    private float frameYOffset;
    // NaN = this word has no rendered frame yet. Seeding them with 0 instead makes the
    // first frame of a line look like a huge move (ghost at the resting baseline) and
    // pops a stray trail copy for Blur Trail x any lifting motion.
    private float trailY1 = Float.NaN;
    private float trailY2 = Float.NaN;
    // Neon-flicker intensity for the current frame, computed once in onDraw so the
    // word path and the letter path flicker in sync within the same 70ms bucket.
    private float frameFlicker = 1f;
    // Total word scale this frame (spring x signature), read by the zoom/chroma ghosts.
    private float frameScale = 1f;
    // Neon Flicker glitch strength (0 = clean frame) for the current 70ms bucket.
    private float frameGlitch = 0f;
    // Spin Settle: reused 3D camera + matrix for the per-letter card flip.
    private final android.graphics.Camera flipCamera = new android.graphics.Camera();
    private final android.graphics.Matrix flipMatrix = new android.graphics.Matrix();
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
    private final Paint auroraPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint meteorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint auroraRayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Reused meteor state buffer - zero allocation per frame.
    private final float[] meteorState = new float[5];
    // Reused aurora colour stops (LinearGradient copies the array) and ring rect.
    private final int[] auroraColors = new int[EffectFx.AURORA_STOPS];
    private final android.graphics.RectF ringRect = new android.graphics.RectF();
    private final android.graphics.RectF ringRectProp = new android.graphics.RectF();
    // Aurora curtain ribbon path + its reused 3-stop vertical gradient colours.
    private final Path auroraPath = new Path();
    private final int[] curtainColors = new int[3];
    private static final float[] CURTAIN_POS = {0f, 0.78f, 1f};
    // Fade windows after the word end so effects bow out instead of being cut.
    private static final float AURORA_FADE_MS = 1100f;
    private static final float METEOR_FADE_MS = 450f;
    private static final float FIREWORKS_WINDOW_MS = 1100f;
    // Longest bubble rise (1300 + 600ms) so the last bubble always finishes.
    private static final long BUBBLE_TAIL_MS = 1950L;
    // Meteor palette: ice blue, white, violet, warm gold.
    private static final int[] METEOR_COLORS = {0x0081D4FA, 0x00FFFFFF, 0x00B39DFF, 0x00FFE082};
    // Ring Pulse ring tints: white, cyan, pink; spark tints: gold, white, cyan.
    private static final int[] RING_COLORS = {0x00FFFFFF, 0x0080DEEA, 0x00F8BBD0};
    private static final int[] SPARK_COLORS = {0x00FFE082, 0x00FFFFFF, 0x0080DEEA};
    // Reused line buffer for sparkle stars — zero per-frame allocation.
    private final float[] sparkleLines = new float[16];
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
    private boolean joinHorizontally;
    private long[] letterStartTimes;
    private long[] letterEndTimes;
    private float[] letterProgress;
    private float[] letterCenters;
    private float[] letterWidths;
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
        auroraStyle = config.aurora;
        meteorStyle = config.meteor;
        ringPulseStyle = config.ringPulse;
        neonStyle = config.neon;
        signature = config.signature != null ? config.signature : AnimStyleDef.Signature.NONE;
        landMs = Long.MIN_VALUE;
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
            letterCenters = null;
            letterWidths = null;
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

    public long getStartTime() {
        return startTime;
    }

    /** Index of this word within its line; the Wave variant uses it as ripple phase. */
    public void setWordIndex(int index) {
        wordIndex = index;
    }

    /** Whether the line containing this word is currently active (Wave amplitude). */
    public void setLineActive(boolean active) {
        // Neon tubes dim when the line lets go - repaint even if nothing else moves.
        if (lineActive != active) invalidate();
        // Remember when the line let go so Aurora on its early words fades out
        // from that moment instead of snapping off (their own end is long past).
        if (lineActive && !active) lineOffMs = lastPositionMs;
        else if (active) lineOffMs = Long.MIN_VALUE;
        lineActive = active;
    }

    public void setBackgroundMode(boolean bg) {
        backgroundMode = bg;
        brightColor = bg ? BG_COLOR_BRIGHT : COLOR_BRIGHT;
        dimColor = bg ? BG_COLOR_DIM : COLOR_DIM;
    }

    /**
     * Space-less scripts (Japanese, Chinese, Korean) are rendered as several word views per
     * line so the line can wrap. The default per-view horizontal padding + the flow margin
     * then show up as a gap between every pair of characters, which reads as broken
     * spacing; joined views sit flush so the line looks like normal CJK text again.
     */
    public void setJoinHorizontally(boolean join) {
        if (joinHorizontally == join) return;
        joinHorizontally = join;
        applyHorizontalPadding();
    }

    private void applyHorizontalPadding() {
        int horizontalPad = joinHorizontally
                ? 0 : Math.max(2, Math.round(getPaint().getTextSize() * 0.10f));
        setPadding(horizontalPad, getPaddingTop(), horizontalPad, getPaddingBottom());
        cachedTextWidth = -1f;
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
            // Aurora repaints the glyphs, so it needs the real font metrics too —
            // without these it drew a default 12px copy over the word.
            auroraPaint.setTextSize(getPaint().getTextSize());
            auroraPaint.setTypeface(typeface);
            ringPaint.setTextSize(getPaint().getTextSize());
            ringPaint.setTypeface(typeface);
            weldingPaint.setTextSize(getPaint().getTextSize());
            weldingPaint.setTypeface(typeface);
            fxTextPaint.setTextSize(getPaint().getTextSize());
            fxTextPaint.setTypeface(typeface);
            neonPaint.setTextSize(getPaint().getTextSize());
            neonPaint.setTypeface(typeface);
            bloomRadiusPx = Math.max(2f, getPaint().getTextSize() * 0.14f);
            // Generous padding: word scale peaks (~1.10-1.14x) and letter pops draw
            // outside the raw text bounds, so give the glyph room before the parent's
            // (disabled) clipping ever matters. Prevents "cut off" letters.
            int verticalPad = Math.max(2, Math.round(getPaint().getTextSize() * 0.16f));
            setPadding(0, verticalPad, 0, verticalPad);
            applyHorizontalPadding();
            appliedSizeSp = sizeSp;
            appliedTypeface = typeface;
            cachedTextWidth = -1f;
            if (letterWidths != null && letters != null) {
                for (int i = 0; i < letters.length; i++) {
                    letterWidths[i] = getPaint().measureText(letters[i]);
                }
            }
        }
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        cachedTextWidth = -1f;
    }

    public void initLetterEmphasis(String wordText, long wordStart, long wordEnd) {
        cachedTextWidth = -1f;
        if (wordText == null || wordText.isEmpty()) {
            clearLetterEmphasis();
            return;
        }
        long duration = wordEnd - wordStart - LETTER_SUBSTRACT_START - LETTER_SUBSTRACT_END;
        if (!letterEmphasisEnabled || !isLetterCapable(wordText.length(), duration)) {
            clearLetterEmphasis();
            return;
        }
        letters = splitLetters(wordText);
        int n = letters.length;
        if (n == 0) {
            clearLetterEmphasis();
            return;
        }
        letterCapable = true;
        letterStartTimes = new long[n];
        letterEndTimes = new long[n];
        letterProgress = new float[n];
        letterScaleSprings = new Spring[n];
        letterGlowSprings = new Spring[n];
        letterWidths = new float[n];
        for (int i = 0; i < n; i++) {
            letterWidths[i] = getPaint().measureText(letters[i]);
        }

        long adjStart = wordStart + LETTER_SUBSTRACT_START;
        long adjEnd = wordEnd - LETTER_SUBSTRACT_END;
        long totalDur = Math.max(1, adjEnd - adjStart);

        for (int i = 0; i < n; i++) {
            letterStartTimes[i] = adjStart + (totalDur * i) / n;
            letterEndTimes[i] = (i == n - 1) ? adjEnd : adjStart + (totalDur * (i + 1)) / n;
            letterProgress[i] = 0f;
            letterScaleSprings[i] = new Spring(LETTER_IDLE_SCALE, scaleDamp, scaleFreq);
            letterGlowSprings[i] = new Spring(0, GLOW_DAMP, GLOW_FREQ);
        }

        if (scatterStyle) {
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

    private void clearLetterEmphasis() {
        letterCapable = false;
        letters = null;
        letterStartTimes = null;
        letterEndTimes = null;
        letterProgress = null;
        letterCenters = null;
        letterWidths = null;
        letterScaleSprings = null;
        letterGlowSprings = null;
        letterScatter = null;
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
        float surfTilt = 0f;
        if (waveStyle) {
            // Travelling ripple: phase advances with the playback clock, each word
            // trails its neighbour so the wave appears to flow along the line, and
            // leans into the slope like a surfer riding the crest.
            double angle = lastPositionMs * 0.0075 - wordIndex * 0.85;
            yOffset = (float) (Math.sin(angle) * waveAmpSpring.position * 0.055 * getHeight());
            surfTilt = MotionFx.surfTilt(angle, waveAmpSpring.position);
        } else {
            yOffset = (float) (yOffsetSpring.position * getHeight());
        }
        if (signature == AnimStyleDef.Signature.FLOAT) {
            // Bubble: every word of the active line bobs on its own buoyant phase.
            yOffset += (float) (MotionFx.floatBob(lastPositionMs, wordIndex)
                    * waveAmpSpring.position * getHeight());
        }
        frameYOffset = yOffset;
        float glowAlpha = (float) Math.max(0, Math.min(glowSpring.position, 1));

        // Signature scale shaping: separate X/Y factors layered over the spring scale.
        float sx = 1f;
        float sy = 1f;
        boolean singing = progress > 0f && progress < 1f && !backgroundMode;
        switch (signature) {
            case STRETCH:
                // Spring: stretch along the spring's own velocity - tall while
                // growing, flattened while it snaps back.
                if (springsEnabled && singing) {
                    float v = (float) Math.max(-0.07, Math.min(0.07, scaleSpring.velocity * 0.11));
                    sy = 1f + v;
                    sx = 1f - v * 0.6f;
                }
                break;
            case HEARTBEAT: {
                // Lub-dub keeps beating as long as the word is sung, then lets go
                // over 120ms so the last beat never cuts off mid-swell.
                if (singing) {
                    float tSec = (lastPositionMs - startTime) / 1000f;
                    float tail = Math.min(1f, (endTime - lastPositionMs) / 120f);
                    float beat = MotionFx.heartbeat(tSec) * Math.max(0f, tail);
                    sx = sy = 1f + beat;
                    glowAlpha = Math.min(1f, glowAlpha + beat * 9f);
                }
                break;
            }
            case SPLASH:
            case DUST:
                if (singing) {
                    MotionFx.impactSquash((float) yOffsetSpring.position, pose);
                    sx = pose[0];
                    sy = pose[1] * MotionFx.fallStretch((float) yOffsetSpring.position,
                            (float) yOffsetSpring.velocity);
                }
                break;
            default:
                break;
        }
        if (squashStyle && !backgroundMode && progress > 0f) {
            // Squash-Stretch: jelly wobble from the word start - squashed, springs
            // tall, wobbles and lands exactly on 1:1.
            MotionFx.jelly((lastPositionMs - startTime) / 1000f, pose);
            sx *= pose[0];
            sy *= pose[1];
        }
        frameScale = scale * Math.max(sx, sy);

        // FLICKER: neon-sign sputter. Deterministic per 70ms bucket so every frame in
        // the same bucket agrees; occasional deep dips sell the failing-tube feel.
        frameFlicker = 1f;
        if (flickerStyle && progress > 0f && progress < 1f) {
            double bucket = Math.floor(lastPositionMs / 70.0) + wordIndex * 31.0;
            double h = Math.sin(bucket * 12.9898 + 4.1414) * 43758.5453;
            double frac = h - Math.floor(h);
            frameFlicker = (frac < 0.14) ? 0.35f + 0.5f * (float) (frac / 0.14)
                    : 0.85f + 0.15f * (float) frac;
            // A dip doubles as a glitch frame: the tube tears into RGB slices.
            frameGlitch = frac < 0.14 ? (float) (1.0 - frac / 0.14) : 0f;
        } else {
            frameGlitch = 0f;
        }
        glowAlpha *= frameFlicker;

        canvas.save();
        canvas.translate(0, yOffset);
        if (sx != 1f || sy != 1f) {
            // Squash pivots on the baseline so a landing word flattens onto the
            // floor instead of shrinking toward its middle.
            float pivotY = (signature == AnimStyleDef.Signature.SPLASH
                    || signature == AnimStyleDef.Signature.DUST) ? getBaseline() : getHeight() / 2f;
            canvas.scale(scale * sx, scale * sy, getWidth() / 2f, pivotY);
        } else if (scale != 1f) {
            canvas.scale(scale, scale, getWidth() / 2f, getHeight() / 2f);
        }
        if (surfTilt != 0f && !backgroundMode) {
            canvas.rotate(surfTilt, getWidth() / 2f, getBaseline());
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
        if (letterCapable && letters != null && letterScaleSprings != null) {
            drawLetterEmphasis(canvas);
        } else {
            drawWordGradient(canvas, text, glowAlpha);
        }

        canvas.restore();

        // Motion props live in view space (not scaled with the word): ball,
        // bubbles, splash droplets, dust puffs, water reflection.
        if (!backgroundMode && signature != AnimStyleDef.Signature.NONE) {
            drawMotionProps(canvas, text, yOffset);
        }

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
            drawMotionGhosts(canvas, text, drawX, baseline);
            getPaint().setShader(null);
            getPaint().setColor(progress > 0f ? brightColor : dimColor);
            canvas.drawText(text, drawX, baseline, getPaint());
            drawEffects(canvas, text, drawX, baseline, shaderW, p, false);
            return;
        }

        // Soft bloom halo around the sung text — a genuine light-glow, drawn via a text
        // shadow layer (hardware-accelerated-safe) modulated by the glow spring.
        if (glowEnabled && glowAlpha > 0.02f && !backgroundMode) {
            int haloAlpha = Math.round(Math.max(0f, Math.min(1f, glowAlpha)) * 150f * glowBoost);
            bloomPaint.setShader(null);
            bloomPaint.setColor(0x00FFFFFF);
            bloomPaint.setShadowLayer(bloomRadiusPx * glowBoost * (0.6f + 0.9f * glowAlpha), 0, 0,
                    (Math.min(255, haloAlpha) << 24) | glowRgb());
            canvas.drawText(text, drawX, baseline, bloomPaint);
            bloomPaint.clearShadowLayer();
        }

        drawMotionGhosts(canvas, text, drawX, baseline);

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
        getPaint().setShader(null);

        drawEffects(canvas, text, drawX, baseline, shaderW, p, false);
    }

    /** Effect overlays (Echo ghosts, Shimmer band) drawn over the just-filled word.
     *  Called from both the word-gradient and the letter-emphasis paths so combos
     *  work identically whichever motion path renders the fill. */
    private void drawEffects(Canvas canvas, String text, float drawX, float baseline,
                             float shaderW, float p, boolean letterFill) {
        // True visible fill edge for THIS combination's render path: sweep motions fill
        // up to the sweep, TYPEWRITER to the struck run, CLASSIC flips the whole word at
        // once (see EffectLayout). Every overlay that must stay on the sung side of the
        // fill — Shimmer, Sparkle, Welding, Burning — clips to THIS edge, never to the
        // raw sweep, so an effect is clipped the same whichever motion it is combined
        // with. The fire effects used to be the only ones doing this.
        boolean binaryStruck = binaryLetters && letters != null && letterProgress != null;
        float edgeP = -1f;
        if (!backgroundMode && (shimmerStyle || sparkleStyle || weldingStyle || burningStyle
                || auroraStyle || meteorStyle || neonStyle)) {
            edgeP = EffectLayout.fillEdge(progress, p, classicStyle, binaryStruck,
                    binaryStruck ? struckFraction(shaderW) : 0f);
        }

        // NEON GLOW: the sung part becomes a lit neon tube - a coloured stroke
        // outline with a wide coloured halo, one tube colour per word. The tube
        // hums (slow brightness ripple) and dims when its line lets go.
        // On the letter path the tube is drawn per letter (drawLetterEmphasis) so
        // it follows every pop/spin; here only the word path draws it.
        if (neonStyle && !letterFill && !backgroundMode && progress > 0f && edgeP > 0f) {
            canvas.save();
            canvas.clipRect(drawX - neonPaint.getTextSize(), 0,
                    drawX + edgeP * shaderW + 2f, getHeight());
            drawNeonTube(canvas, text, drawX, baseline);
            canvas.restore();
        }

        // NEON FLICKER glitch: on a sputter frame the word tears into cyan and
        // magenta slices jumping sideways (deterministic per 70ms bucket).
        if (flickerStyle && frameGlitch > 0f && !backgroundMode) {
            float ts = fxTextPaint.getTextSize();
            int bucket = (int) (lastPositionMs / 70) + wordIndex * 31;
            float top = baseline - ts;
            for (int k = 0; k < 2; k++) {
                float y0 = top + hash01(bucket * 3 + k) * ts * 0.8f;
                float hgt = ts * (0.12f + 0.25f * hash01(bucket * 5 + k));
                float dx = (hash01(bucket * 7 + k) - 0.5f) * ts * 0.35f * frameGlitch;
                canvas.save();
                canvas.clipRect(drawX - ts, y0, drawX + shaderW + ts, y0 + hgt);
                fxTextPaint.setShader(null);
                fxTextPaint.setColor((Math.round(200f * frameGlitch) << 24) | 0x0000E5FF);
                canvas.drawText(text, drawX + dx, baseline, fxTextPaint);
                fxTextPaint.setColor((Math.round(200f * frameGlitch) << 24) | 0x00FF4FC3);
                canvas.drawText(text, drawX - dx * 0.7f, baseline, fxTextPaint);
                canvas.restore();
            }
        }

        // TYPEWRITER caret: a glowing text cursor riding the struck run while the
        // word is typed, fading out right after the last key.
        if (signature == AnimStyleDef.Signature.CARET && !backgroundMode && progress > 0f
                && lastPositionMs < endTime + 260) {
            float ts = fxTextPaint.getTextSize();
            float edge = (binaryStruck ? struckFraction(shaderW) : Math.max(0f, Math.min(1f, p)));
            float cx = drawX + edge * shaderW + ts * 0.04f;
            float ca = lastPositionMs < endTime ? 1f : 1f - (lastPositionMs - endTime) / 260f;
            fxShapePaint.setStyle(Paint.Style.FILL);
            fxShapePaint.setShader(null);
            fxShapePaint.setColor((Math.round(ca * 90f) << 24) | 0x0080DEEA);
            canvas.drawRect(cx - ts * 0.04f, baseline - ts * 0.82f, cx + ts * 0.075f,
                    baseline + ts * 0.12f, fxShapePaint);
            fxShapePaint.setColor((Math.round(ca * 240f) << 24) | 0x00E0F7FA);
            canvas.drawRect(cx, baseline - ts * 0.78f, cx + ts * 0.035f,
                    baseline + ts * 0.08f, fxShapePaint);
        }

        // ECHO: three tinted ghost copies (cyan / violet / pink) rippling up and
        // sideways off the sung word like reverb - each delayed on its own sine so
        // the echoes breathe instead of hanging still.
        if (echoStyle && progress > 0f && progress < 1f && !backgroundMode) {
            float fade = (float) Math.sin(progress * Math.PI); // in/out over the word
            float h = getHeight();
            for (int t = 1; t <= 3; t++) {
                double ph = lastPositionMs * 0.006 - t * 0.9 + wordIndex;
                float dy = -t * 0.05f * h * (0.8f + 0.2f * (float) Math.sin(ph));
                float dx = (float) Math.sin(ph * 0.7) * t * 0.012f * h;
                int alpha = Math.round((0.22f - 0.05f * t) * 255f * fade);
                int rgb = ECHO_COLORS[(t - 1) % ECHO_COLORS.length];
                echoPaint.setShader(new LinearGradient(drawX, 0, drawX + shaderW, 0,
                        (alpha << 24) | rgb, rgb, Shader.TileMode.CLAMP));
                canvas.drawText(text, drawX + dx, baseline + dy, echoPaint);
            }
            echoPaint.setShader(null);
        }

        // SHIMMER: a narrow light band that rides the fill edge across the already-sung
        // part of the word. Its position comes from the SAME edge the clip uses, so the
        // whole band stays inside the sung region; mapping it in its own 0.05..0.98
        // progress space instead ran it ahead of the clip and the effect rendered as
        if (shimmerStyle && progress > 0.05f && progress < 0.98f && !backgroundMode && edgeP > 0f) {
            float cx = drawX + EffectLayout.shimmerCenter(progress, edgeP, classicStyle) * shaderW;
            float halfW = EffectLayout.SHIMMER_HALF_WIDTH * shaderW;
            // Prismatic band: a white-hot core flanked by cyan and pink fringes,
            // like light catching the edge of a glass prism.
            shimmerPaint.setShader(new LinearGradient(cx - halfW * 1.6f, 0, cx + halfW * 1.6f, 0,
                    new int[]{0x0000E5FF, 0x5580DEEA, 0xB3FFFFFF, 0x55FF80AB, 0x00FF4FC3},
                    new float[]{0f, 0.3f, 0.5f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            // Clip the band to the sung region only (left of the fill edge).
            canvas.save();
            canvas.clipRect(drawX, 0, drawX + edgeP * shaderW + 2f, getHeight());
            canvas.drawText(text, drawX, baseline, shimmerPaint);
            canvas.restore();
            shimmerPaint.setShader(null);
        }

        // OUTLINE POP: two neon stroke outlines burst off the word at its start
        // (cyan + pink, offset), expand and fade - a cut-out sticker pop.
        if (outlineStyle && progress > 0f && progress < 0.40f && !backgroundMode) {
            float t = progress / 0.40f;
            float ts = outlinePaint.getTextSize();
            outlinePaint.setStyle(Paint.Style.STROKE);
            outlinePaint.setStrokeJoin(Paint.Join.ROUND);
            for (int k = 0; k < 2; k++) {
                float u = Math.max(0f, Math.min(1f, t * 1.15f - k * 0.15f));
                if (u <= 0f || u >= 1f) continue;
                float grow = 1f + 0.18f * (1f - (1f - u) * (1f - u));
                int alpha = Math.round((1f - u) * (0.85f - 0.25f * k) * 255f);
                outlinePaint.setStrokeWidth(Math.max(1.5f, ts * (0.05f - 0.015f * k)));
                outlinePaint.setColor((alpha << 24) | (k == 0 ? 0x0080DEEA : 0x00FF80AB));
                canvas.save();
                canvas.scale(grow, grow, drawX + shaderW / 2f, baseline - 0.35f * ts);
                canvas.drawText(text, drawX, baseline, outlinePaint);
                canvas.restore();
            }
            outlinePaint.setStyle(Paint.Style.FILL);
        }

        // COLOR FLASH: a strobe of hue flashes over the sung word - white flare
        // on the hit, then it cycles through magenta / cyan / gold and settles.
        if (flashStyle && progress > 0f && progress < 0.45f && !backgroundMode) {
            float t = progress / 0.45f;
            float strobe = 0.6f + 0.4f * (float) Math.cos(t * Math.PI * 6);
            int alpha = Math.round((1f - t) * (1f - t) * 0.75f * strobe * 255f);
            int rgb = t < 0.12f ? 0x00FFFFFF
                    : EffectFx.hsvToRgb(300f + t * 420f + wordIndex * 40f, 0.55f, 1f);
            flashPaint.setShader(null);
            flashPaint.setColor((alpha << 24) | rgb);
            flashPaint.setShadowLayer(bloomRadiusPx * 1.6f, 0, 0, (Math.min(255, alpha) << 24) | rgb);
            canvas.drawText(text, drawX, baseline, flashPaint);
            flashPaint.clearShadowLayer();
        }

        // BLUR TRAIL: three ghost copies smeared along the word's recent motion,
        // tinted from cyan to violet - motion blur with a chromatic tail. Also
        // trails a faint copy while the word scales, so static motions still smear.
        if (trailStyle && progress > 0f && progress < 1f && !backgroundMode && !Float.isNaN(trailY1)) {
            float d1 = trailY1 - frameYOffset;
            float d2 = trailY2 - frameYOffset;
            float gate = 0.0025f * getHeight();
            trailPaint.setShader(null);
            if (Math.abs(d1) > gate) {
                trailPaint.setColor(0x4D80DEEA); // 0.30 cyan
                canvas.drawText(text, drawX, baseline + d1 * 1.4f, trailPaint);
                if (Math.abs(d2) > gate) {
                    trailPaint.setColor(0x33B388FF); // 0.20 violet
                    canvas.drawText(text, drawX, baseline + d2 * 1.6f, trailPaint);
                    trailPaint.setColor(0x1AFF80AB); // 0.10 pink
                    canvas.drawText(text, drawX, baseline + d2 * 2.4f, trailPaint);
                }
            }
            // Horizontal speed smear trailing the fill edge.
            float sweep = (float) Math.sin(progress * Math.PI);
            int sa = Math.round(sweep * 0.16f * 255f);
            trailPaint.setColor((sa << 24) | 0x0080DEEA);
            float smear = trailPaint.getTextSize() * 0.06f;
            canvas.drawText(text, drawX - smear, baseline, trailPaint);
            trailPaint.setColor(((sa / 2) << 24) | 0x00B388FF);
            canvas.drawText(text, drawX - smear * 2.2f, baseline, trailPaint);
        }

        // SPARKLE: twelve eight-point glints (white / gold / cyan / pink) that pop
        // along the sung word, drift upward and twinkle with a soft core halo.
        // Positions are deterministic from the word seed; the line buffer is
        // reused (zero allocation). Clipped to the sung region so the stars never
        // twinkle over the dimmed upcoming lyric.
        if (sparkleStyle && progress > 0f && !backgroundMode && edgeP > 0f
                && lastPositionMs < endTime + 500) {
            float ts = sparklePaint.getTextSize();
            sparklePaint.setStrokeCap(Paint.Cap.ROUND);
            int seed = wordIndex * 31 + text.length() * 7;
            long dur = Math.max(1L, endTime - startTime);
            canvas.save();
            canvas.clipRect(drawX - ts * 0.2f, baseline - 2.2f * ts,
                    drawX + edgeP * shaderW + ts * 0.2f, baseline + ts);
            for (int i = 0; i < 12; i++) {
                // Stagger star births across the word; each lives 520ms.
                float birth = startTime + dur * (i / 12f) * 0.9f;
                float lp = (lastPositionMs - birth) / 520f;
                if (lp <= 0f || lp >= 1f) continue;
                float x01 = hash01(seed + i);
                if (x01 > edgeP + 0.02f) continue;
                float hx = drawX + x01 * shaderW;
                float hy = baseline - ts * (0.10f + 0.70f * hash01(seed + i + 100))
                        - lp * 0.45f * ts;
                float tw = (float) Math.sin(lp * Math.PI) * (0.75f + 0.25f
                        * (float) Math.sin(lastPositionMs * 0.04 + i));
                float r = ts * (0.07f + 0.05f * hash01(seed + i + 200)) * tw;
                int alpha = Math.round(tw * 0.95f * 255f);
                int rgb = SPARKLE_COLORS[i % SPARKLE_COLORS.length];
                // Halo
                sparklePaint.setStyle(Paint.Style.FILL);
                sparklePaint.setColor(((alpha / 4) << 24) | rgb);
                canvas.drawCircle(hx, hy, r * 0.9f, sparklePaint);
                // Long cross + short diagonal = eight-point star
                sparklePaint.setStyle(Paint.Style.STROKE);
                sparklePaint.setStrokeWidth(Math.max(1f, ts * 0.022f));
                sparklePaint.setColor((alpha << 24) | rgb);
                float d = r * 0.45f;
                sparkleLines[0] = hx - r; sparkleLines[1] = hy;
                sparkleLines[2] = hx + r; sparkleLines[3] = hy;
                sparkleLines[4] = hx;     sparkleLines[5] = hy - r;
                sparkleLines[6] = hx;     sparkleLines[7] = hy + r;
                sparkleLines[8] = hx - d; sparkleLines[9] = hy - d;
                sparkleLines[10] = hx + d; sparkleLines[11] = hy + d;
                sparkleLines[12] = hx - d; sparkleLines[13] = hy + d;
                sparkleLines[14] = hx + d; sparkleLines[15] = hy - d;
                canvas.drawLines(sparkleLines, sparklePaint);
                sparklePaint.setStyle(Paint.Style.FILL);
                sparklePaint.setColor((alpha << 24) | 0x00FFFFFF);
                canvas.drawCircle(hx, hy, Math.max(1f, r * 0.18f), sparklePaint);
            }
            canvas.restore();
            sparklePaint.setStyle(Paint.Style.FILL);
        }

        // FIREWORKS: a two-stage show celebrating the word finishing - a rocket
        // streaks up from the word, then bursts into 36 hue-shifted particles
        // (per-word palette) with gravity, followed by a crackling glitter that
        // blinks as it falls. Analytic in the karaoke clock, so it survives seeks.
        if (fireworksStyle && !backgroundMode && endTime > startTime) {
            float tAll = (lastPositionMs - endTime) / 1000f;
            float ts = getPaint().getTextSize();
            float ox = drawX + shaderW / 2f;
            float launchY = baseline - 0.3f * ts;
            float oy = baseline - 1.5f * ts;            // burst point above the word
            float rocket = 0.16f;                        // rocket flight (s)
            if (tAll > 0f && tAll < rocket) {
                float u = tAll / rocket;
                float ry = launchY + (oy - launchY) * (1f - (1f - u) * (1f - u));
                fireworksPaint.setStyle(Paint.Style.STROKE);
                fireworksPaint.setStrokeCap(Paint.Cap.ROUND);
                fireworksPaint.setStrokeWidth(Math.max(2f, ts * 0.045f));
                fireworksPaint.setColor(0xCCFFE082);
                canvas.drawLine(ox, Math.min(launchY, ry + ts * 0.45f), ox, ry, fireworksPaint);
                fireworksPaint.setStyle(Paint.Style.FILL);
                fireworksPaint.setColor(0xFFFFFFFF);
                canvas.drawCircle(ox, ry, Math.max(2f, ts * 0.05f), fireworksPaint);
            }
            float tSec = tAll - rocket;
            if (tSec > 0f && tSec < 0.9f) {
                float g = 2.2f * ts;
                int seed = wordIndex * 31 + text.length() * 7;
                float life = tSec / 0.9f;
                int baseAlpha = Math.round((1f - life * life) * 0.95f * 255f);
                float hueBase = hash01(seed + 999) * 360f;
                // Bright flash core at the burst moment.
                if (tSec < 0.12f) {
                    int fa = Math.round((1f - tSec / 0.12f) * 200f);
                    fireworksPaint.setStyle(Paint.Style.FILL);
                    fireworksPaint.setColor((fa << 24) | 0x00FFFFFF);
                    canvas.drawCircle(ox, oy, ts * (0.15f + 0.9f * tSec), fireworksPaint);
                }
                fireworksPaint.setStyle(Paint.Style.STROKE);
                fireworksPaint.setStrokeWidth(Math.max(1.5f, ts * 0.035f));
                fireworksPaint.setStrokeCap(Paint.Cap.ROUND);
                for (int i = 0; i < 36; i++) {
                    float ang = (i + 0.5f * hash01(seed + i * 2)) / 36f * 6.2832f;
                    float spd = (1.3f + 1.2f * hash01(seed + i * 2 + 1)) * ts;
                    float vx = (float) Math.cos(ang) * spd;
                    float vy = (float) Math.sin(ang) * spd - 0.6f * ts;
                    // Air drag: velocity decays so the shell holds its round shape.
                    float drag = (1f - (float) Math.exp(-2.6f * tSec)) / 2.6f;
                    float drag0 = (1f - (float) Math.exp(-2.6f * Math.max(0f, tSec - 0.1f))) / 2.6f;
                    float t0 = Math.max(0f, tSec - 0.1f);
                    particleLines[i * 4]     = ox + vx * drag0;
                    particleLines[i * 4 + 1] = oy + vy * drag0 + 0.5f * g * t0 * t0;
                    particleLines[i * 4 + 2] = ox + vx * drag;
                    particleLines[i * 4 + 3] = oy + vy * drag + 0.5f * g * tSec * tSec;
                    int rgb = EffectFx.hsvToRgb(hueBase + (i % 3) * 40f, i % 4 == 0 ? 0.15f : 0.7f, 1f);
                    fireworksPaint.setColor((baseAlpha << 24) | rgb);
                    canvas.drawLines(particleLines, i * 4, 4, fireworksPaint);
                }
                // Crackling glitter in the second half of the burst.
                if (life > 0.35f) {
                    fireworksPaint.setStyle(Paint.Style.FILL);
                    for (int i = 0; i < 18; i++) {
                        float on = hash01((int) (lastPositionMs / 45) + i * 7 + seed);
                        if (on < 0.45f) continue;
                        float ang = hash01(seed + i * 3 + 500) * 6.2832f;
                        float dist = (0.8f + 1.0f * hash01(seed + i * 3 + 501)) * ts;
                        float drag = (1f - (float) Math.exp(-2.6f * tSec)) / 2.6f;
                        float gx = ox + (float) Math.cos(ang) * dist * drag * 1.6f;
                        float gy = oy + (float) Math.sin(ang) * dist * drag * 1.6f + 0.5f * g * tSec * tSec;
                        int ga = Math.round((1f - life) * 255f * on);
                        fireworksPaint.setColor((ga << 24) | 0x00FFF8E1);
                        canvas.drawCircle(gx, gy, Math.max(1f, ts * 0.025f), fireworksPaint);
                    }
                }
                fireworksPaint.setStyle(Paint.Style.FILL);
            }
        }

        // WELDING: the karaoke fill edge becomes a welding torch — a flickering
        // white-hot core riding the sweep edge, spitting long amber sparks with
        // gravity. Crackle comes from deterministic 50/260ms clock ticks; all
        // distances scale with the text size so sparks stay visible at any font.
        if (weldingStyle && !backgroundMode && progress > 0f && progress < 1f && edgeP > 0f) {
            float ts = getPaint().getTextSize();
            float wx = drawX + edgeP * shaderW;
            float wy = baseline - 0.35f * ts;
            float fl = hash01((int) (lastPositionMs / 50 & 0x7fffffff));
            // Molten seam: the freshly welded letters glow white -> amber -> red,
            // cooling back to the normal fill a little behind the torch.
            float cool = ts * 2.2f;
            weldingPaint.setStyle(Paint.Style.FILL);
            weldingPaint.setShader(new LinearGradient(wx - cool, 0, wx, 0,
                    new int[]{0x00E53935, 0xB3FF6D00, 0xE6FFB300, 0xFFFFF8E1},
                    new float[]{0f, 0.45f, 0.8f, 1f}, Shader.TileMode.CLAMP));
            canvas.save();
            canvas.clipRect(Math.max(drawX, wx - cool), 0, wx + 1f, getHeight());
            canvas.drawText(text, drawX, baseline, weldingPaint);
            canvas.restore();
            weldingPaint.setShader(null);
            // Electric arc: a jagged blue-white bolt crackling from the torch,
            // re-shaped every 50ms tick.
            weldingPaint.setStyle(Paint.Style.STROKE);
            weldingPaint.setStrokeCap(Paint.Cap.ROUND);
            weldingPaint.setStrokeJoin(Paint.Join.ROUND);
            int tick = (int) (lastPositionMs / 50 & 0x7fffffff);
            for (int b = 0; b < 2; b++) {
                flamePath.rewind();
                flamePath.moveTo(wx, wy);
                float bx = wx, by = wy;
                float dir = hash01(tick * 3 + b) * 6.2832f;
                for (int seg = 0; seg < 5; seg++) {
                    float a = dir + (hash01(tick * 11 + b * 7 + seg) - 0.5f) * 1.6f;
                    float step = ts * (0.09f + 0.07f * hash01(tick * 13 + seg + b));
                    bx += (float) Math.cos(a) * step;
                    by += (float) Math.sin(a) * step;
                    flamePath.lineTo(bx, by);
                }
                weldingPaint.setStrokeWidth(Math.max(2.5f, ts * 0.05f));
                weldingPaint.setColor(0x5590CAF9);
                canvas.drawPath(flamePath, weldingPaint);
                weldingPaint.setStrokeWidth(Math.max(1f, ts * 0.016f));
                weldingPaint.setColor(0xF2E3F2FD);
                canvas.drawPath(flamePath, weldingPaint);
            }
            weldingPaint.setStyle(Paint.Style.FILL);
            // Arc halo first, white-hot core on top.
            float cr = ts * (0.08f + 0.06f * fl);
            weldingPaint.setColor(0x8F90CAF9); // 0.56 alpha blue arc
            canvas.drawCircle(wx, wy, cr * 2.4f, weldingPaint);
            weldingPaint.setColor(0xE6FFFFFF); // 0.9 alpha white core
            canvas.drawCircle(wx, wy, cr, weldingPaint);
            // Fourteen staggered sparks, 520ms life each, re-seeded every cycle.
            // Two-pass trail (wide glow + hot core) so sparks read at any font size.
            weldingPaint.setStrokeCap(Paint.Cap.ROUND);
            float g = 5.0f * ts;
            float strokeW = Math.max(2.5f, ts * 0.04f);
            weldingPaint.setStyle(Paint.Style.STROKE);
            for (int i = 0; i < 14; i++) {
                float local = ((lastPositionMs + i * 37) % 520) / 520f;
                int s = (int) ((lastPositionMs + i * 37) / 520) * 14 + i * 13 + wordIndex * 7;
                float ang = -2.7f + 2.2f * hash01(s);            // wide upward fan
                float spd = (1.4f + 1.4f * hash01(s + 50)) * ts;
                float vx = (float) Math.cos(ang) * spd;
                float vy = (float) Math.sin(ang) * spd;
                float lt = local * 0.52f;                        // life in seconds
                float t0 = Math.max(0f, lt - 0.06f);             // trail 60ms behind
                float hx = wx + vx * lt;
                float hy = wy + vy * lt + 0.5f * g * lt * lt;
                particleLines[0] = wx + vx * t0;
                particleLines[1] = wy + vy * t0 + 0.5f * g * t0 * t0;
                particleLines[2] = hx;
                particleLines[3] = hy;
                int alpha = Math.round((1f - local * 0.8f) * 0.95f * 255f);
                // Glow underpass, then the hot core line.
                weldingPaint.setStrokeWidth(strokeW * 2.2f);
                weldingPaint.setColor(((alpha / 3) << 24) | 0x00FF6D00);
                canvas.drawLines(particleLines, 0, 4, weldingPaint);
                weldingPaint.setStrokeWidth(strokeW);
                weldingPaint.setColor((alpha << 24) | 0x00FFB300);
                canvas.drawLines(particleLines, 0, 4, weldingPaint);
                // Hot white-yellow head so each spark reads as a flying ember.
                weldingPaint.setStyle(Paint.Style.FILL);
                weldingPaint.setColor((alpha << 24) | 0x00FFE082);
                canvas.drawCircle(hx, hy, strokeW, weldingPaint);
                weldingPaint.setStyle(Paint.Style.STROKE);
            }
            weldingPaint.setStyle(Paint.Style.FILL);
        }

        // BURNING: the sung part of the word ignites. On the word-gradient path the
        // fire gradient is overd drawn over the straight fill (clip = true sung edge,
        // Shimmer pattern). On the letter path the sung letters carry the fire
        // gradient themselves (inside drawLetterEmphasis, so pops/spins/waves stay
        // aligned); here we only add the flame tongues above the true sung edge.
        if (burningStyle && !backgroundMode && progress > 0f && edgeP > 0f) {
            float ts = burningPaint.getTextSize();
            float sungW = edgeP * shaderW;
            if (sungW > 2f) {
                if (!letterFill) {
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
                }

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
                    // White-hot inner tongue.
                    flamePath.rewind();
                    float iw = w * 0.45f;
                    flamePath.moveTo(fx - iw, base);
                    flamePath.quadTo(fx - iw * 0.6f, base - h * 0.3f, fx + wob * 0.6f, base - h * 0.6f);
                    flamePath.quadTo(fx + iw * 0.6f, base - h * 0.3f, fx + iw, base);
                    flamePath.close();
                    burningPaint.setColor(((fa * 3 / 4) << 24) | 0x00FFF8E1);
                    canvas.drawPath(flamePath, burningPaint);
                }
                // Embers: sparks lifting off the fire, swaying as they cool
                // from gold to red and wink out (each on its own 900ms loop).
                for (int i = 0; i < 10; i++) {
                    float x01 = hash01(seed + i * 9 + 300);
                    if (x01 * shaderW > sungW) continue;
                    float period = 700f + 500f * hash01(seed + i * 9 + 301);
                    float lt = ((lastPositionMs + (long) (hash01(seed + i * 9 + 302) * period))
                            % (long) period) / period;
                    float ex = drawX + x01 * shaderW
                            + (float) Math.sin(lt * 6.2832f * 1.5f + i) * ts * 0.08f;
                    float ey = baseline - ts * 0.8f - lt * ts * 1.4f;
                    int ea = Math.round((1f - lt) * 230f);
                    int rgb = lt < 0.4f ? 0x00FFE082 : (lt < 0.75f ? 0x00FF9100 : 0x00E53935);
                    burningPaint.setColor(((ea / 3) << 24) | rgb);
                    canvas.drawCircle(ex, ey, Math.max(2f, ts * 0.045f), burningPaint);
                    burningPaint.setColor((ea << 24) | rgb);
                    canvas.drawCircle(ex, ey, Math.max(1f, ts * 0.018f), burningPaint);
                }
            }
        }

        // AURORA: the sung region repaints in a flowing multi-colour sky band
        // (teal->blue->violet->pink sliding sideways, breathing brightness),
        // while rippling light curtains rise above the word and tiny stars
        // twinkle in them. Everything stays clipped to the true sung edge.
        // Sung words of the active line keep shimmering (phase-shifted per word,
        // so the line ripples in waves); after the line it fades out smoothly.
        float auroraEnv = auroraStyle ? auroraEnvelope() : 0f;
        if (auroraEnv > 0f && edgeP > 0f) {
            float sungW = edgeP * shaderW;
            if (sungW > 1f) {
                float ts = getPaint().getTextSize();
                float breathe = EffectFx.auroraBreathe(lastPositionMs + wordIndex * 180L);
                canvas.save();
                canvas.clipRect(drawX, baseline - 3f * ts, drawX + sungW + 2f, baseline + ts);

                // 1) Light curtains: three wavy ribbons, each a vertical gradient
                //    peaking just above the glyph tops and fading up into the sky.
                float base = baseline + getPaint().ascent() * 0.72f;
                int segs = Math.max(6, Math.min(28, (int) (sungW / (ts * 0.14f))));
                auroraRayPaint.setStyle(Paint.Style.FILL);
                for (int r = 0; r < EffectFx.AURORA_RIBBONS; r++) {
                    float top = base - ts * 1.35f;
                    float bottom = base + ts * 0.22f;
                    auroraPath.rewind();
                    auroraPath.moveTo(drawX, bottom);
                    for (int j = 0; j <= segs; j++) {
                        float x = drawX + sungW * j / segs;
                        float x01 = (x - drawX) / shaderW;
                        float taper = Math.min(1f, (x - drawX) / (ts * 0.35f))
                                * Math.min(1f, (drawX + sungW - x) / (ts * 0.35f));
                        float h = ts * (0.30f + 0.85f * EffectFx.auroraRibbon(
                                x01, lastPositionMs, wordIndex, r)) * (1f - 0.18f * r);
                        auroraPath.lineTo(x, base - h * (0.25f + 0.75f * taper));
                    }
                    auroraPath.lineTo(drawX + sungW, bottom);
                    auroraPath.close();
                    int rgb = EffectFx.hsvToRgb(
                            EffectFx.auroraStopHue(lastPositionMs, wordIndex, r * 2), 0.65f, 1f);
                    int ca = Math.round(auroraEnv * (0.20f + 0.16f * breathe) * (1f - 0.2f * r) * 255f);
                    curtainColors[0] = rgb;                 // transparent top
                    curtainColors[1] = (ca << 24) | rgb;    // brightest at the base
                    curtainColors[2] = rgb;                 // fades again below
                    auroraRayPaint.setShader(new LinearGradient(0, top, 0, bottom,
                            curtainColors, CURTAIN_POS, Shader.TileMode.CLAMP));
                    canvas.drawPath(auroraPath, auroraRayPaint);
                }
                auroraRayPaint.setShader(null);

                // 2) Flowing colour band over the glyphs (word path only: the
                //    letter path paints it per letter so pops/spins stay aligned).
                if (!letterFill) {
                    auroraPaint.setColor(0xFFFFFFFF);
                    auroraPaint.setShader(auroraShader(drawX, shaderW, ts, auroraEnv));
                    canvas.drawText(text, drawX, baseline, auroraPaint);
                    // Soft white sheen on the upper half - reads as light on ice.
                    int sa = Math.round(auroraEnv * (0.18f + 0.22f * breathe) * 255f);
                    auroraPaint.setShader(new LinearGradient(0, baseline - ts, 0, baseline - 0.35f * ts,
                            (sa << 24) | 0x00FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                    canvas.drawText(text, drawX, baseline, auroraPaint);
                    auroraPaint.setShader(null);
                }

                // 3) Twinkling stars scattered through the curtains.
                int seed = wordIndex * 31 + text.length() * 7;
                for (int i = 0; i < 6; i++) {
                    float x01 = 0.05f + 0.9f * hash01(seed + i * 17 + 400);
                    if (x01 * shaderW > sungW) continue;
                    double ph = lastPositionMs * 0.001 / (0.9 + 0.8 * hash01(seed + i * 17 + 401))
                            + hash01(seed + i * 17 + 402);
                    float tw = (float) (0.5 - 0.5 * Math.cos((ph - Math.floor(ph)) * 2.0 * Math.PI));
                    tw *= tw;
                    int sa = Math.round(auroraEnv * tw * 230f);
                    if (sa < 4) continue;
                    float sx = drawX + x01 * shaderW;
                    float sy = base - ts * (0.15f + 0.85f * hash01(seed + i * 17 + 403));
                    auroraRayPaint.setColor((sa << 24) | 0x00FFFFFF);
                    canvas.drawCircle(sx, sy, Math.max(1.2f, ts * (0.018f + 0.014f * tw)), auroraRayPaint);
                    auroraRayPaint.setColor(((sa / 4) << 24) | 0x00B2EBF2);
                    canvas.drawCircle(sx, sy, Math.max(2.5f, ts * 0.06f * tw), auroraRayPaint);
                }
                canvas.restore();
            }
        }

        // METEOR SHOWER: five staggered meteors rain across the sung region -
        // each with a three-layer tapering tail, a glowing head and sparkling
        // debris, tinted ice/white/violet/gold. Every fall cycle re-rolls the
        // path (analytic in the clock, so a seek re-renders the same rain), the
        // heads land inside the sung region, and the shower bows out over
        // METEOR_FADE_MS after the word instead of vanishing on its last frame.
        float meteorEnv = (meteorStyle && !backgroundMode && progress > 0f)
                ? EffectFx.envelope(lastPositionMs, startTime, endTime, 150f, METEOR_FADE_MS) : 0f;
        if (meteorEnv > 0f && edgeP > 0f) {
            float ts = getPaint().getTextSize();
            float sungW = edgeP * shaderW;
            meteorPaint.setStrokeCap(Paint.Cap.ROUND);
            int wordSeed = wordIndex * 31 + text.length() * 7;
            canvas.save();
            // Tails may stream in over the (already sung) previous word; the right
            // side stays clipped to the sung edge so the dim lyric stays clean.
            canvas.clipRect(drawX - 2f * ts, baseline - 3f * ts, drawX + sungW + 2f, baseline + ts);
            for (int k = 0; k < EffectFx.METEOR_COUNT; k++) {
                float life = EffectFx.meteorLife(lastPositionMs, k);
                float alpha = EffectFx.meteorAlpha(life) * meteorEnv;
                if (alpha <= 0.01f) continue;
                int s = EffectFx.meteorSeed(wordSeed, k, EffectFx.meteorCycle(lastPositionMs, k));
                EffectFx.meteorState(s, k, meteorState);
                float vx = meteorState[2] * ts;
                float vy = meteorState[3] * ts;
                // Shift the start back so the head lands inside the sung run.
                float sx = drawX + meteorState[0] * sungW - 0.6f * vx;
                float sy = baseline - 0.75f * ts + meteorState[1] * ts;
                float px = sx + vx * life;
                float py = sy + vy * life;
                float len = meteorState[4] * ts * (0.45f + 0.55f * EffectFx.clamp01(life / 0.25f));
                float mag = (float) Math.hypot(vx, vy);
                float nx = vx / mag;
                float ny = vy / mag;
                int rgb = METEOR_COLORS[(int) (hash01(s + 5) * METEOR_COLORS.length) % METEOR_COLORS.length];

                // Tail: wide coloured glow -> mid -> white-hot core, each shorter.
                meteorPaint.setStyle(Paint.Style.STROKE);
                meteorPaint.setStrokeWidth(Math.max(4f, ts * 0.085f));
                meteorPaint.setColor((Math.round(60f * alpha) << 24) | rgb);
                canvas.drawLine(px - nx * len, py - ny * len, px, py, meteorPaint);
                meteorPaint.setStrokeWidth(Math.max(2.2f, ts * 0.045f));
                meteorPaint.setColor((Math.round(140f * alpha) << 24) | rgb);
                canvas.drawLine(px - nx * len * 0.6f, py - ny * len * 0.6f, px, py, meteorPaint);
                meteorPaint.setStrokeWidth(Math.max(1.2f, ts * 0.022f));
                meteorPaint.setColor((Math.round(235f * alpha) << 24) | 0x00FFFFFF);
                canvas.drawLine(px - nx * len * 0.3f, py - ny * len * 0.3f, px, py, meteorPaint);

                // Glowing head: soft halo + bright core.
                meteorPaint.setStyle(Paint.Style.FILL);
                meteorPaint.setColor((Math.round(70f * alpha) << 24) | rgb);
                canvas.drawCircle(px, py, Math.max(3f, ts * 0.10f), meteorPaint);
                meteorPaint.setColor((Math.round(250f * alpha) << 24) | 0x00FFFFFF);
                canvas.drawCircle(px, py, Math.max(1.5f, ts * 0.035f), meteorPaint);

                // Debris: three flickering sparks shed along the tail.
                for (int d = 0; d < 3; d++) {
                    float along = len * (0.35f + 0.22f * d);
                    float jitter = (hash01(s + d * 11 + 70) - 0.5f) * ts * 0.12f;
                    float fx = px - nx * along - ny * jitter;
                    float fy = py - ny * along + nx * jitter;
                    float flick = hash01((int) (lastPositionMs / 60) + s + d * 7);
                    int da = Math.round(alpha * (1f - 0.25f * d) * (0.35f + 0.65f * flick) * 200f);
                    meteorPaint.setColor((da << 24) | (d == 0 ? 0x00FFFFFF : rgb));
                    canvas.drawCircle(fx, fy, Math.max(1f, ts * (0.022f - 0.004f * d)), meteorPaint);
                }
            }
            canvas.restore();
            meteorPaint.setStyle(Paint.Style.FILL);
        }

        // RING PULSE: the instant a word finishes, three staggered tinted rings
        // ripple outward from it (ovals hugging the word, so long words are
        // circled too instead of a ring born inside the text), a burst of sparks
        // flies out ahead of them, and the word itself flashes. Phase is analytic
        // in the karaoke clock (like Fireworks), so it survives seeks.
        if (ringPulseStyle && !backgroundMode && endTime > startTime) {
            float tSec = (lastPositionMs - endTime) / 1000f;
            if (tSec > 0f && tSec < EffectFx.RING_WINDOW) {
                float ts = getPaint().getTextSize();
                float cx = drawX + shaderW / 2f;
                float cy = baseline - 0.35f * ts;
                float halfW = shaderW * 0.5f;

                // Word flash: a quick bright overdraw on the beat.
                float flash = 1f - EffectFx.clamp01(tSec / 0.22f);
                if (flash > 0f) {
                    ringPaint.setStyle(Paint.Style.FILL);
                    ringPaint.setShader(null);
                    ringPaint.setColor((Math.round(flash * flash * 150f) << 24) | 0x00FFFFFF);
                    canvas.drawText(text, drawX, baseline, ringPaint);
                }

                ringPaint.setStyle(Paint.Style.STROKE);
                for (int k = 0; k < EffectFx.RING_COUNT; k++) {
                    float lt = EffectFx.ringLocal(tSec, k);
                    if (lt <= 0f || lt >= EffectFx.RING_LIFE) continue;
                    float r = EffectFx.ringRadius(lt, ts) * (1f - 0.12f * k);
                    int a = Math.round(EffectFx.ringAlpha(lt) * (1f - 0.22f * k) * 255f);
                    int rgb = RING_COLORS[k % RING_COLORS.length];
                    ringRect.set(cx - halfW * 0.85f - r, cy - r * 0.78f,
                            cx + halfW * 0.85f + r, cy + r * 0.78f);
                    // Faint wide halo underpass, then the bright core ring.
                    ringPaint.setStrokeWidth(Math.max(5f, ts * 0.13f));
                    ringPaint.setColor(((a / 3) << 24) | rgb);
                    canvas.drawOval(ringRect, ringPaint);
                    ringPaint.setStrokeWidth(Math.max(1.5f, ts * (0.04f - 0.008f * k)));
                    ringPaint.setColor((a << 24) | rgb);
                    canvas.drawOval(ringRect, ringPaint);
                }

                // Spark burst: 16 short radial streaks on the same oval spread.
                float sa = EffectFx.sparkAlpha(tSec);
                if (sa > 0.01f) {
                    int seed = wordIndex * 31 + text.length() * 7;
                    float dist = EffectFx.sparkDistance(tSec, ts);
                    ringPaint.setStrokeCap(Paint.Cap.ROUND);
                    ringPaint.setStrokeWidth(Math.max(1.5f, ts * 0.03f));
                    for (int i = 0; i < 16; i++) {
                        float ang = (i + 0.6f * hash01(seed + i * 3)) / 16f * 6.2832f;
                        float d = dist * (0.65f + 0.55f * hash01(seed + i * 3 + 1));
                        float c = (float) Math.cos(ang);
                        float sn = (float) Math.sin(ang);
                        float ex = halfW * 0.85f * c;
                        float x1 = cx + ex + c * d;
                        float y1 = cy + sn * d * 0.78f;
                        float x0 = cx + ex + c * d * 0.72f;
                        float y0 = cy + sn * d * 0.78f * 0.72f;
                        ringPaint.setColor((Math.round(sa * 230f) << 24)
                                | SPARK_COLORS[i % SPARK_COLORS.length]);
                        canvas.drawLine(x0, y0, x1, y1, ringPaint);
                    }
                    ringPaint.setStrokeCap(Paint.Cap.BUTT);
                }
                ringPaint.setStyle(Paint.Style.FILL);
            }
        }
    }

    /** Neon tube strength 0..1: hums while lit, dims to 25% once its line lets go. */
    private float neonLevel() {
        float hum = 0.85f + 0.15f * (float) Math.sin(lastPositionMs * 0.011 + wordIndex * 1.7);
        float lit = lineActive ? 1f
                : Math.max(0.25f, 1f - (lastPositionMs - Math.max(endTime, lineOffMs)) / 900f);
        return hum * lit;
    }

    /** One neon tube pass: coloured stroke with a coloured halo, plus a white core. */
    private void drawNeonTube(Canvas canvas, String glyphs, float x, float baseline) {
        float ts = neonPaint.getTextSize();
        int rgb = glowRgb();
        int a = Math.round(neonLevel() * 255f);
        neonPaint.setStyle(Paint.Style.STROKE);
        neonPaint.setStrokeJoin(Paint.Join.ROUND);
        neonPaint.setStrokeWidth(Math.max(2f, ts * 0.05f));
        neonPaint.setColor((Math.round(a * 0.9f) << 24) | rgb);
        neonPaint.setShadowLayer(bloomRadiusPx * 1.8f, 0, 0, (Math.round(a * 0.95f) << 24) | rgb);
        canvas.drawText(glyphs, x, baseline, neonPaint);
        neonPaint.clearShadowLayer();
        neonPaint.setStrokeWidth(Math.max(1f, ts * 0.016f));
        neonPaint.setColor((Math.round(a * 0.85f) << 24) | 0x00FFFFFF);
        canvas.drawText(glyphs, x, baseline, neonPaint);
        neonPaint.setStyle(Paint.Style.FILL);
    }

    /** Bloom/halo tint: Neon Glow gives every word its own tube colour, else white. */
    private int glowRgb() {
        return neonStyle ? NEON_COLORS[Math.abs(wordIndex) % NEON_COLORS.length] : 0x00FFFFFF;
    }

    /**
     * Motion signatures drawn as ghost copies of the glyphs, under the main
     * fill (inside the word's scale/translate transform):
     *  - Pulse: cyan/magenta RGB split proportional to the scale thump;
     *  - Zoom: radial zoom-blur echoes while the word is zoomed in.
     */
    private void drawMotionGhosts(Canvas canvas, String text, float drawX, float baseline) {
        if (backgroundMode || progress <= 0f || progress >= 1f) return;
        float ts = fxTextPaint.getTextSize();
        fxTextPaint.setShader(null);
        fxTextPaint.setStyle(Paint.Style.FILL);
        if (signature == AnimStyleDef.Signature.CHROMA) {
            float d = MotionFx.chromaSplit(frameScale) * ts;
            if (d > 0.3f) {
                int a = Math.round(Math.min(1f, d / (ts * 0.05f)) * 150f);
                fxTextPaint.setColor((a << 24) | 0x0000E5FF);
                canvas.drawText(text, drawX - d, baseline, fxTextPaint);
                fxTextPaint.setColor((a << 24) | 0x00FF2D95);
                canvas.drawText(text, drawX + d, baseline, fxTextPaint);
            }
        } else if (signature == AnimStyleDef.Signature.ZOOM_BLUR) {
            float blur = MotionFx.zoomBlur(frameScale);
            if (blur > 0.02f) {
                float cx = getWidth() / 2f;
                float cy = getHeight() / 2f;
                for (int k = 1; k <= 3; k++) {
                    float s2 = 1f + blur * 0.07f * k;
                    int a = Math.round(blur * (70f - 18f * k));
                    fxTextPaint.setColor((a << 24) | 0x00FFFFFF);
                    canvas.save();
                    canvas.scale(s2, s2, cx, cy);
                    canvas.drawText(text, drawX, baseline, fxTextPaint);
                    canvas.restore();
                }
            }
        }
    }

    /**
     * Motion signature props drawn in view space, outside the word's own scale
     * transform: the Classic karaoke ball, Bubble's rising bubbles, the Drop
     * splash, Bounce dust puffs and Rise's water reflection. All analytic in the
     * clock / progress; each fades fully out so nothing lingers on screen.
     */
    private void drawMotionProps(Canvas canvas, String text, float yOffset) {
        float ts = getPaint().getTextSize();
        float drawX = getPaddingLeft();
        float w = Math.max(1f, cachedTextWidth);
        float baseline = getBaseline();
        fxShapePaint.setShader(null);
        fxShapePaint.setStyle(Paint.Style.FILL);
        switch (signature) {
            case BALL: {
                // Karaoke ball: hops word to word, landing on each word start.
                if (progress <= 0f || progress >= 1f) return;
                float x = drawX + w * progress;
                float hop = MotionFx.ballHeight(progress) * ts * 0.55f;
                float r = Math.max(3f, ts * 0.11f);
                float y = baseline - ts * 0.92f - hop - r;
                float sq = MotionFx.ballSquash(progress);
                fxShapePaint.setColor(0x33FFE082);
                canvas.drawCircle(x, y, r * 2.1f, fxShapePaint);
                fxShapePaint.setShader(new RadialGradient(x - r * 0.35f, y - r * 0.35f, r * 1.3f,
                        0xFFFFFFFF, 0xFFFFB300, Shader.TileMode.CLAMP));
                canvas.save();
                canvas.scale(1f / sq, sq, x, y + r);
                canvas.drawCircle(x, y, r, fxShapePaint);
                canvas.restore();
                fxShapePaint.setShader(null);
                break;
            }
            case FLOAT: {
                // Bubbles: soft rings rising off the sung word, wobbling as they go.
                if (progress <= 0f || lastPositionMs > endTime + BUBBLE_TAIL_MS) return;
                int seed = wordIndex * 31 + text.length() * 7;
                fxShapePaint.setStyle(Paint.Style.STROKE);
                for (int i = 0; i < 6; i++) {
                    float period = 1300f + 600f * hash01(seed + i * 5);
                    long born = startTime + (long) (i * 160);
                    if (lastPositionMs < born) continue;
                    long age = lastPositionMs - born;
                    float lt = (age % (long) period) / period;
                    // After the word ends a bubble may only finish the rise it was on.
                    if (lastPositionMs > endTime
                            && (endTime < born || age / (long) period > (endTime - born) / (long) period)) {
                        continue;
                    }
                    float bx = drawX + w * hash01(seed + i * 5 + 1)
                            + (float) Math.sin(lt * 9f + i) * ts * 0.1f;
                    float by = baseline - ts * 0.6f + yOffset - lt * ts * 1.8f;
                    float r = ts * (0.05f + 0.06f * hash01(seed + i * 5 + 2)) * (0.6f + 0.6f * lt);
                    int a = Math.round((float) Math.sin(lt * Math.PI) * 170f);
                    fxShapePaint.setStrokeWidth(Math.max(1f, ts * 0.018f));
                    fxShapePaint.setColor((a << 24) | 0x00B2EBF2);
                    canvas.drawCircle(bx, by, r, fxShapePaint);
                    fxShapePaint.setStyle(Paint.Style.FILL);
                    fxShapePaint.setColor((a << 24) | 0x00FFFFFF);
                    canvas.drawCircle(bx - r * 0.35f, by - r * 0.35f, Math.max(0.8f, r * 0.22f), fxShapePaint);
                    fxShapePaint.setStyle(Paint.Style.STROKE);
                }
                fxShapePaint.setStyle(Paint.Style.FILL);
                break;
            }
            case SPLASH:
            case DUST: {
                if (landMs == Long.MIN_VALUE) return;
                float tSec = (lastPositionMs - landMs) / 1000f;
                if (tSec <= 0f || tSec >= MotionFx.LAND_LIFE) return;
                float life = tSec / MotionFx.LAND_LIFE;
                float gx = drawX + w / 2f;
                float gy = baseline + ts * 0.08f;
                int seed = wordIndex * 31 + text.length() * 7;
                if (signature == AnimStyleDef.Signature.SPLASH) {
                    // Ripple ring on the "water line" + droplets arcing out.
                    fxShapePaint.setStyle(Paint.Style.STROKE);
                    fxShapePaint.setStrokeWidth(Math.max(1.2f, ts * 0.025f));
                    fxShapePaint.setColor((Math.round((1f - life) * 160f) << 24) | 0x0080DEEA);
                    float rx = w * 0.4f + ts * 1.3f * life;
                    ringRectProp.set(gx - rx, gy - rx * 0.12f, gx + rx, gy + rx * 0.12f);
                    canvas.drawOval(ringRectProp, fxShapePaint);
                    fxShapePaint.setStyle(Paint.Style.FILL);
                    for (int i = 0; i < 10; i++) {
                        MotionFx.droplet(seed, i, tSec, pose);
                        float px = gx + (i % 2 == 0 ? -1f : 1f) * w * 0.25f + pose[0] * ts;
                        float py = gy + pose[1] * ts;
                        int a = Math.round((1f - life) * 220f);
                        fxShapePaint.setColor((a << 24) | (i % 3 == 0 ? 0x00FFFFFF : 0x00B3E5FC));
                        canvas.drawCircle(px, py, Math.max(1.2f, ts * (0.03f + 0.02f * hash01(seed + i))), fxShapePaint);
                    }
                } else {
                    // Dust puffs billowing sideways from both ends of the word.
                    for (int i = 0; i < 8; i++) {
                        float side = i % 2 == 0 ? -1f : 1f;
                        float spread = 0.6f + 0.8f * hash01(seed + i * 3);
                        float ease = 1f - (1f - life) * (1f - life);
                        float px = gx + side * (w * 0.45f + ease * ts * 1.1f * spread);
                        float py = gy - ease * ts * (0.15f + 0.25f * hash01(seed + i * 3 + 1));
                        float r = ts * (0.07f + 0.14f * ease) * (0.7f + 0.5f * hash01(seed + i * 3 + 2));
                        int a = Math.round((1f - life) * (1f - life) * 110f);
                        fxShapePaint.setColor((a << 24) | 0x00E0D6CC);
                        canvas.drawCircle(px, py, r, fxShapePaint);
                    }
                }
                break;
            }
            case REFLECT: {
                // Water reflection: a flipped, fading copy below the rising word.
                float ra = MotionFx.reflection(progress);
                if (ra <= 0.01f) return;
                float waterY = baseline + ts * 0.18f;
                canvas.save();
                canvas.clipRect(0, waterY, getWidth(), getHeight() + ts);
                canvas.translate(0, yOffset * -0.6f);
                canvas.scale(1f, -0.55f, 0, waterY);
                fxTextPaint.setStyle(Paint.Style.FILL);
                int a = Math.round(ra * 255f);
                fxTextPaint.setShader(new LinearGradient(0, waterY, 0, waterY - ts * 0.9f,
                        (a << 24) | 0x0080DEEA, 0x0080DEEA, Shader.TileMode.CLAMP));
                // Ripple: a tiny horizontal wobble per frame.
                float wob = (float) Math.sin(lastPositionMs * 0.012) * ts * 0.03f;
                canvas.drawText(text, drawX + wob, baseline, fxTextPaint);
                fxTextPaint.setShader(null);
                canvas.restore();
                break;
            }
            default:
                break;
        }
    }

    /**
     * Aurora strength 0..1 for this word: fades in as it starts, holds while its
     * line is active (so the whole sung line keeps shimmering), and fades out
     * over AURORA_FADE_MS after the word end once the line is no longer active.
     */
    private float auroraEnvelope() {
        if (!auroraStyle || backgroundMode || progress <= 0f) return 0f;
        float in = EffectFx.clamp01((lastPositionMs - startTime) / 250f);
        if (lineActive) return in;
        return in * EffectFx.clamp01(1f - (lastPositionMs - auroraFadeAnchor()) / AURORA_FADE_MS);
    }

    /** Aurora fades from the later of the word end and the moment its line went inactive. */
    private long auroraFadeAnchor() {
        return Math.max(endTime, lineOffMs);
    }

    /** Flowing aurora colour band across the word (MIRROR tile, slides with the clock). */
    private LinearGradient auroraShader(float x0, float w, float ts, float env) {
        float breathe = EffectFx.auroraBreathe(lastPositionMs + wordIndex * 180L);
        int a = Math.round(env * (150f + 95f * breathe));
        for (int i = 0; i < EffectFx.AURORA_STOPS; i++) {
            int rgb = EffectFx.hsvToRgb(
                    EffectFx.auroraStopHue(lastPositionMs, wordIndex, i), 0.58f, 1f);
            auroraColors[i] = (a << 24) | rgb;
        }
        float len = Math.max(ts * 2.2f, w) * 0.85f;
        float shift = EffectFx.auroraFlow(lastPositionMs, wordIndex) * 2f * len;
        return new LinearGradient(x0 - shift, 0, x0 - shift + len, 0,
                auroraColors, null, Shader.TileMode.MIRROR);
    }

    /**
     * Whether any clock-driven effect still needs frames after (or between) the
     * word's own progress window. Without this the view stopped invalidating at
     * the word end and Ring Pulse / Fireworks froze mid-burst on screen.
     */
    private boolean effectsLive(long position) {
        if (backgroundMode) return false;
        long sinceEnd = position - endTime;
        if (endTime > startTime && sinceEnd > 0) {
            if (ringPulseStyle && sinceEnd < EffectFx.RING_WINDOW * 1000f + 32) return true;
            if (fireworksStyle && sinceEnd < FIREWORKS_WINDOW_MS + 32) return true;
        }
        if (meteorStyle && position >= startTime && sinceEnd < METEOR_FADE_MS + 32) return true;
        if (position >= startTime) {
            if (sparkleStyle && sinceEnd < 532) return true;
            if (squashStyle && position - startTime < MotionFx.JELLY_LIFE * 1000f + 32) return true;
            if ((spinStyle || signature == AnimStyleDef.Signature.EXPLODE) && sinceEnd < 632) return true;
            if (signature == AnimStyleDef.Signature.CARET && sinceEnd < 292) return true;
            if (signature == AnimStyleDef.Signature.FLOAT && sinceEnd < BUBBLE_TAIL_MS + 32) return true;
            if (landMs != Long.MIN_VALUE && position - landMs < MotionFx.LAND_LIFE * 1000f + 32) return true;
            if (neonStyle && !lineActive && position - Math.max(endTime, lineOffMs) < 932) return true;
        }
        return auroraStyle && progress > 0f
                && (lineActive || position - auroraFadeAnchor() < AURORA_FADE_MS + 32);
    }

    // Smooth left-to-right sweep: each letter's brightness is sampled from a continuous
    // gradient across the whole word (same sweep math as drawWordGradient), so the bright
    // edge glides across letters instead of snapping per-letter. The active letter still
    // pops in scale/glow for emphasis.
    private void drawLetterEmphasis(Canvas canvas) {
        float baseline = getBaseline();
        float totalTextW = cachedTextWidth;
        float startX = getPaddingLeft();

        // Continuous sweep position in the -0.20..1.0 space (matches drawWordGradient).
        float p = -0.20f + 1.20f * progress;
        float dStop = p + FADE_WIDTH;

        int n = letters.length;
        // Reused across frames; only (re)allocated when the letter count changes.
        float[] centers = (letterCenters == null || letterCenters.length != n)
                ? (letterCenters = new float[n]) : letterCenters;
        float cursor = startX;
        float denom = Math.max(1f, totalTextW);
        for (int i = 0; i < n; i++) {
            float letterW = getPaint().measureText(letters[i]);
            centers[i] = (cursor + letterW / 2f - startX) / denom;
            cursor += letterW;
        }

        int activeIndex = -1;
        for (int i = 0; i < n; i++) {
            if (letterProgress[i] > 0 && (letterProgress[i] < 1 || i == n - 1)) {
                activeIndex = i;
                break;
            }
        }
        float activeLetterPct = activeIndex >= 0 ? letterProgress[activeIndex] : 0;

        // One aurora band shared by every sung letter this frame (built once, not per letter).
        float auroraLetterEnv = (auroraStyle && !burningStyle) ? auroraEnvelope() : 0f;
        LinearGradient auroraLetterShader = auroraLetterEnv > 0f
                ? auroraShader(startX, totalTextW, getPaint().getTextSize(), auroraLetterEnv) : null;

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
            float cy = baseline + getPaint().ascent() * 0.45f;
            if (lYOffset != 0f) {
                canvas.translate(0, lYOffset * getHeight());
            }
            float sinceStrike = (lastPositionMs - letterStartTimes[i]) / 1000f;
            if (signature == AnimStyleDef.Signature.EXPLODE && !backgroundMode) {
                // Scatter Pop: letters burst out of place and fly home spinning.
                MotionFx.explode(wordIndex * 31 + n * 7, i,
                        sinceStrike / MotionFx.EXPLODE_LIFE, pose3);
                float ts = getPaint().getTextSize();
                if (pose3[0] != 0f || pose3[1] != 0f) canvas.translate(pose3[0] * ts, pose3[1] * ts);
                if (pose3[2] != 0f) canvas.rotate(pose3[2], cx, cy);
            }
            if (signature == AnimStyleDef.Signature.RUBBER && lScale != 1f) {
                // Elastic Pop: rubber stretch - overshoot pulls tall and thin,
                // undershoot squashes wide, pivoting on the baseline.
                MotionFx.rubber(lScale, pose);
                canvas.scale(pose[0], pose[1], cx, baseline);
            } else if (lScale != 1f) {
                canvas.scale(lScale, lScale, cx, cy);
            }
            if (signature == AnimStyleDef.Signature.TILT && !backgroundMode && progress > 0f) {
                // Scatter: each popping letter cocks its head its own way.
                float tilt = MotionFx.scatterTilt(i, Math.abs(lScale - 1f));
                if (tilt != 0f) canvas.rotate(tilt, cx, baseline);
            }

            if (spinStyle && !backgroundMode) {
                // Spin Settle: each letter does a full 3D card-flip on its strike
                // and lands face-on, plus the old spring tilt while it settles.
                float deg = MotionFx.flipDegrees(sinceStrike);
                if (deg != 0f) {
                    flipCamera.save();
                    flipCamera.rotateY(deg);
                    flipCamera.getMatrix(flipMatrix);
                    flipCamera.restore();
                    flipMatrix.preTranslate(-cx, -cy);
                    flipMatrix.postTranslate(cx, cy);
                    canvas.concat(flipMatrix);
                }
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
                        (Math.min(255, haloAlpha) << 24) | glowRgb());
                canvas.drawText(letter, cursor, baseline, glowPaint);
                glowPaint.clearShadowLayer();
            }

            getPaint().setShader(null);
            // BURNING on the letter path: sung letters carry the fire gradient
            // themselves, so the flames follow every pop/spin/wave transform.
            if (burningStyle && !backgroundMode && progress > 0f) {
                boolean sung = binaryLetters ? letterProgress[i] > 0 : centers[i] < p;
                if (sung) {
                    float ts = getPaint().getTextSize();
                    float flick = 0.75f + 0.15f * (float) Math.sin(lastPositionMs * 0.02);
                    int fa = Math.round(flick * 255f);
                    getPaint().setShader(new LinearGradient(0, baseline - ts, 0, baseline,
                            new int[]{(fa << 24) | 0x00FFD54F, (fa << 24) | 0x00FF7043,
                                    (fa << 24) | 0x00E53935},
                            new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                }
            }
            // AURORA on the letter path: sung letters carry the flowing sky band.
            if (auroraLetterShader != null) {
                boolean sung = binaryLetters ? letterProgress[i] > 0 : centers[i] < p;
                if (sung) getPaint().setShader(auroraLetterShader);
            }
            getPaint().setColor(letterColor);
            canvas.drawText(letter, cursor, baseline, getPaint());
            getPaint().setShader(null);
            if (neonStyle && !backgroundMode && progress > 0f
                    && (binaryLetters ? letterProgress[i] > 0 : centers[i] < p)) {
                drawNeonTube(canvas, letter, cursor, baseline);
            }

            canvas.restore();
            cursor += letterW;
        }

        // Effect overlays ride over the letter fill too (combo feature).
        drawEffects(canvas, getText().toString(), startX, baseline, totalTextW, p, true);
    }

    public void updateState(long position, double deltaTime) {
        lastPositionMs = position;
        if (waveStyle || signature == AnimStyleDef.Signature.FLOAT) {
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

        // Drop / Bounce touchdown: the falling word first crosses its rest line
        // from above while sung - that frame fires the splash / dust.
        if (signature == AnimStyleDef.Signature.SPLASH || signature == AnimStyleDef.Signature.DUST) {
            float y = (float) yOffsetSpring.position;
            if (position < startTime) {
                landMs = Long.MIN_VALUE;
            } else if (progress > 0f && progress < 1f && prevLandY < 0f && y >= 0f
                    && (landMs == Long.MIN_VALUE || landMs < startTime)) {
                landMs = position;
            }
            prevLandY = y;
        }

        boolean wasActive = isActive;
        isActive = position >= startTime && position < endTime;

        // Keep redrawing while the springs are still settling. The scale spring
        // overshoots past 1.0, so when progress hits 1.0 the word is still visually
        // enlarged and needs several more frames to relax back — otherwise it
        // freezes mid-animation and never returns to its resting size.
        if (isActive || wasActive || (progress > 0 && progress < 1) || springsSettling()
                || effectsLive(position)) {
            postInvalidate();
        }
    }

    private boolean springsSettling() {
        if ((waveStyle || signature == AnimStyleDef.Signature.FLOAT) && (isSpringUnsettled(waveAmpSpring)
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

    /**
     * Width of the struck-letter run as a fraction of the word — TYPEWRITER's visible
     * fill edge, which trails the raw sweep because letters strike one at a time.
     */
    private float struckFraction(float shaderW) {
        float struckW = 0f;
        if (letterWidths != null) {
            int limit = Math.min(letters.length, letterWidths.length);
            for (int i = 0; i < limit; i++) {
                if (letterProgress[i] > 0) {
                    struckW += letterWidths[i];
                } else {
                    break;
                }
            }
        }
        return Math.min(1f, struckW / Math.max(1f, shaderW));
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
        trailY1 = Float.NaN;
        trailY2 = Float.NaN;
        lineOffMs = Long.MIN_VALUE;
        landMs = Long.MIN_VALUE;
        prevLandY = 0f;
        progress = 0;
        isActive = false;
        letterCapable = false;
        letters = null;
        letterCenters = null;
        letterWidths = null;
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
