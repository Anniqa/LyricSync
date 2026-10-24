package com.lyricsync.app.renderer;

/**
 * Pure-JVM geometry for the three palette effects that arrived after the first
 * twelve (Aurora, Meteor Shower, Ring Pulse). No android imports, so the unit
 * tests run on the bare JVM; GradientWordView turns these numbers into Paint
 * calls and reuses its own hash01 for per-word seeds (same formula).
 */
public final class EffectFx {
    private EffectFx() {}

    /** Deterministic 0..1 hash — mirrors GradientWordView.hash01 (sin LCG). */
    public static float hash01(int n) {
        double h = Math.sin(n * 12.9898 + 78.233) * 43758.5453;
        return (float) (h - Math.floor(h));
    }

    // ── Aurora ────────────────────────────────────────────────────────────

    /**
     * Aurora hue for a frame in degrees 0..360: a slow 2.4s drift through
     * teal→blue→purple→pink, each word phase-shifted so a line shimmers in
     * waves instead of blinking in unison.
     */
    public static float auroraHue(long positionMs, int wordIndex) {
        double t = positionMs * 0.001 / 2.4 + wordIndex * 0.13;
        double frac = t - Math.floor(t);
        return (float) ((190.0 + 130.0 * (0.5 - 0.5 * Math.cos(frac * 2.0 * Math.PI))) % 360.0);
    }

    /** Aurora brightness envelope 0.55..1.0 breathing on the same cycle as the hue. */
    public static float auroraBreathe(long positionMs) {
        double t = positionMs * 0.001 / 2.4;
        double frac = t - Math.floor(t);
        return (float) (0.55 + 0.45 * (0.5 - 0.5 * Math.cos(frac * 2.0 * Math.PI)));
    }

    // ── Meteor Shower ─────────────────────────────────────────────────────

    /**
     * Parametric state for meteor {@code k} of a word. Fills {@code out}:
     * {startX, startY, dx, dy, streakLen} — positions are fractions (startX of
     * the sung width, startY of text size above the glyph top; dx/dy/len in
     * multiples of text size per unit of life). Deterministic per seed.
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
     * 1.35s fall with meteors staggered 0.45s apart, analytic in the clock so
     * a seek re-renders the same rain instead of popping new streaks.
     */
    public static float meteorLife(long positionMs, int k) {
        double t = (positionMs * 0.001 + k * 0.45) / 1.35;
        return (float) (t - Math.floor(t));
    }

    // ── Ring Pulse ────────────────────────────────────────────────────────

    /** Expanding ring radius at {@code tSec} after word end, in text-size units. */
    public static float ringRadius(float tSec, float ts) {
        float t = clamp01(tSec / 0.55f);
        float eased = 1f - (1f - t) * (1f - t);
        return ts * (0.55f + 2.3f * eased);
    }

    /** Ring alpha 0..1 at {@code tSec} after word end — eases out with the radius. */
    public static float ringAlpha(float tSec) {
        if (tSec <= 0f) return 0f;
        float t = clamp01(tSec / 0.55f);
        return 0.55f * (1f - t) * (1f - t);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
