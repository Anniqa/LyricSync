package com.lyricsync.app.renderer;

/**
 * Pure-JVM geometry for the three palette effects that arrived after the first
 * twelve (Aurora, Meteor Shower, Ring Pulse). No android imports, so the unit
 * tests run on the bare JVM; GradientWordView turns these numbers into Paint
 * calls and reuses its own hash01 for per-word seeds (same formula).
 *
 * Everything is analytic in the playback clock: a seek re-renders exactly the
 * same frame instead of popping new particles, and no per-frame state is kept.
 */
public final class EffectFx {
    private EffectFx() {}

    /** Deterministic 0..1 hash — mirrors GradientWordView.hash01 (sin LCG). */
    public static float hash01(int n) {
        double h = Math.sin(n * 12.9898 + 78.233) * 43758.5453;
        return (float) (h - Math.floor(h));
    }

    /**
     * Trapezoid envelope 0..1 for an effect tied to a word's timing: ramps in over
     * {@code fadeInMs} after {@code startMs}, holds, and fades out over
     * {@code fadeOutMs} after {@code endMs}. Lets effects linger briefly past the
     * word end instead of being cut off on the frame the word finishes.
     */
    public static float envelope(long positionMs, long startMs, long endMs,
                                 float fadeInMs, float fadeOutMs) {
        float in = fadeInMs <= 0f ? (positionMs >= startMs ? 1f : 0f)
                : clamp01((positionMs - startMs) / fadeInMs);
        float out = fadeOutMs <= 0f ? (positionMs < endMs ? 1f : 0f)
                : clamp01(1f - (positionMs - endMs) / fadeOutMs);
        return in * out;
    }

    /** HSV (h in degrees, s/v 0..1) to opaque-less 0x00RRGGBB — no android.graphics.Color. */
    public static int hsvToRgb(float h, float s, float v) {
        h = ((h % 360f) + 360f) % 360f;
        s = clamp01(s);
        v = clamp01(v);
        float c = v * s;
        float hp = h / 60f;
        float x = c * (1f - Math.abs(hp % 2f - 1f));
        float r, g, b;
        if (hp < 1f)      { r = c; g = x; b = 0; }
        else if (hp < 2f) { r = x; g = c; b = 0; }
        else if (hp < 3f) { r = 0; g = c; b = x; }
        else if (hp < 4f) { r = 0; g = x; b = c; }
        else if (hp < 5f) { r = x; g = 0; b = c; }
        else              { r = c; g = 0; b = x; }
        float m = v - c;
        int ri = Math.round((r + m) * 255f);
        int gi = Math.round((g + m) * 255f);
        int bi = Math.round((b + m) * 255f);
        return (ri << 16) | (gi << 8) | bi;
    }

    // ── Aurora ────────────────────────────────────────────────────────────

    /** Colour stops across the aurora gradient (teal → blue → violet → pink → teal). */
    public static final int AURORA_STOPS = 5;
    /** Curtain ribbons drawn above the sung word. */
    public static final int AURORA_RIBBONS = 3;

    /**
     * Aurora hue for a frame in degrees 0..360: a slow 2.4s drift through
     * teal→blue→purple→pink, each word phase-shifted so a line shimmers in
     * waves instead of blinking in unison.
     */
    public static float auroraHue(long positionMs, int wordIndex) {
        double t = positionMs * 0.001 / 2.4 + wordIndex * 0.13;
        double frac = t - Math.floor(t);
        return (float) ((165.0 + 160.0 * (0.5 - 0.5 * Math.cos(frac * 2.0 * Math.PI))) % 360.0);
    }

    /**
     * Hue of gradient stop {@code stop} (0..AURORA_STOPS-1). Stops fan out 38°
     * apart from the base hue and wrap inside the aurora band (155..335°), so the
     * word always carries several sky colours at once instead of one flat tint.
     */
    public static float auroraStopHue(long positionMs, int wordIndex, int stop) {
        float base = auroraHue(positionMs, wordIndex);
        // Triangle-fold the offset back into the band so no stop drifts to green/yellow.
        float h = base + stop * 38f;
        float lo = 155f, hi = 335f, span = hi - lo;
        float u = (h - lo) % (2f * span);
        if (u < 0f) u += 2f * span;
        return lo + (u <= span ? u : 2f * span - u);
    }

    /** Aurora brightness envelope 0.55..1.0 breathing on the same cycle as the hue. */
    public static float auroraBreathe(long positionMs) {
        double t = positionMs * 0.001 / 2.4;
        double frac = t - Math.floor(t);
        return (float) (0.55 + 0.45 * (0.5 - 0.5 * Math.cos(frac * 2.0 * Math.PI)));
    }

    /** 0..1 flow phase that slides the colour bands sideways (one period per 3s). */
    public static float auroraFlow(long positionMs, int wordIndex) {
        double t = positionMs * 0.001 / 3.0 + wordIndex * 0.21;
        return (float) (t - Math.floor(t));
    }

    /**
     * Curtain height 0..1 of ribbon {@code ribbon} at horizontal fraction
     * {@code x01} of the word: two travelling sines (different speeds, opposite
     * directions) so the curtain folds and ripples like real aurora light.
     */
    public static float auroraRibbon(float x01, long positionMs, int wordIndex, int ribbon) {
        double t = positionMs * 0.001;
        double ph = ribbon * 2.1 + wordIndex * 0.9;
        double a = Math.sin(x01 * 6.3 + t * 1.7 + ph);
        double b = Math.sin(x01 * 13.1 - t * 2.6 + ph * 1.7);
        return clamp01((float) (0.55 + 0.28 * a + 0.17 * b));
    }

    // ── Meteor Shower ─────────────────────────────────────────────────────

    /** Meteors alive per word at any moment. */
    public static final int METEOR_COUNT = 5;
    /** Seconds for one meteor fall. */
    public static final double METEOR_PERIOD = 1.2;

    /**
     * Parametric state for meteor {@code k} of a word. Fills {@code out}:
     * {startX, startY, dx, dy, streakLen} — positions are fractions (startX of
     * the sung width, startY of text size above the glyph top; dx/dy/len in
     * multiples of text size per unit of life). Deterministic per seed; pass
     * {@link #meteorSeed} so every fall cycle picks a fresh path.
     */
    public static void meteorState(int seed, int k, float[] out) {
        out[0] = 0.05f + 0.55f * hash01(seed * 7 + k * 131 + 11);
        out[1] = -0.10f - 0.35f * hash01(seed * 13 + k * 211 + 29);
        out[2] = 0.85f + 0.55f * hash01(seed + k * 71 + 3);
        out[3] = 0.45f + 0.40f * hash01(seed - k * 57 + 5);
        out[4] = 0.55f + 0.75f * hash01(seed + k * 97 + 8);
    }

    /**
     * Cycle-local life 0..1 for meteor {@code k} at the playback clock — a
     * 1.2s fall with the meteors evenly staggered across the period, analytic in
     * the clock so a seek re-renders the same rain instead of popping new streaks.
     */
    public static float meteorLife(long positionMs, int k) {
        double t = positionMs * 0.001 / METEOR_PERIOD + (double) k / METEOR_COUNT;
        return (float) (t - Math.floor(t));
    }

    /** Which fall cycle meteor {@code k} is in — changes exactly when its life wraps. */
    public static int meteorCycle(long positionMs, int k) {
        double t = positionMs * 0.001 / METEOR_PERIOD + (double) k / METEOR_COUNT;
        return (int) Math.floor(t);
    }

    /** Per-cycle seed: each new fall re-rolls position, angle, length and colour. */
    public static int meteorSeed(int wordSeed, int k, int cycle) {
        return wordSeed + cycle * 977 + k * 53;
    }

    /**
     * Meteor brightness 0..1 over its life: a quick 12% flare-in (no hard pop when
     * the cycle wraps) then a smooth burn-out to zero at the end of the fall.
     */
    public static float meteorAlpha(float life) {
        if (life <= 0f || life >= 1f) return 0f;
        float in = clamp01(life / 0.12f);
        float out = 1f - life;
        return in * (float) Math.pow(out, 1.2);
    }

    // ── Ring Pulse ────────────────────────────────────────────────────────

    /** Concentric rings fired per word end. */
    public static final int RING_COUNT = 3;
    /** Delay between successive rings (s). */
    public static final float RING_STAGGER = 0.09f;
    /** Life of one ring (s). */
    public static final float RING_LIFE = 0.55f;
    /** Total visible window after the word end (s): last ring start + its life. */
    public static final float RING_WINDOW = RING_LIFE + (RING_COUNT - 1) * RING_STAGGER;

    /** Expanding ring radius at {@code tSec} after word end, in text-size units. */
    public static float ringRadius(float tSec, float ts) {
        float t = clamp01(tSec / RING_LIFE);
        float eased = 1f - (1f - t) * (1f - t);
        return ts * (0.55f + 2.3f * eased);
    }

    /** Ring alpha 0..0.55 at {@code tSec} after word end — eases out with the radius. */
    public static float ringAlpha(float tSec) {
        if (tSec <= 0f) return 0f;
        float t = clamp01(tSec / RING_LIFE);
        return 0.55f * (1f - t) * (1f - t);
    }

    /** Local time of ring {@code k} (negative before it fires). */
    public static float ringLocal(float tSec, int k) {
        return tSec - k * RING_STAGGER;
    }

    /**
     * Distance (text-size units) a burst spark has travelled at {@code tSec}:
     * flies out faster than the rings (cubic ease-out) so sparks lead the pulse.
     */
    public static float sparkDistance(float tSec, float ts) {
        float t = clamp01(tSec / RING_WINDOW);
        float u = 1f - t;
        return ts * 3.1f * (1f - u * u * u);
    }

    /** Spark alpha 0..1: bright start, fades out across the whole ring window. */
    public static float sparkAlpha(float tSec) {
        if (tSec <= 0f) return 0f;
        float t = clamp01(tSec / RING_WINDOW);
        return (1f - t) * (1f - t);
    }

    static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
