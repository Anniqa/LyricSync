package com.lyricsync.app.renderer;

/**
 * Pure-JVM math for the per-motion "signature" moves layered on top of the
 * spline/spring motion system. Every function is analytic in its inputs (word
 * progress, letter progress, or seconds on the playback clock), so a seek
 * re-renders the exact same pose and nothing keeps per-frame state.
 *
 * GradientWordView turns these numbers into canvas transforms; the unit tests
 * pin the ranges/rest values so a signature can never leave a word displaced
 * or scaled once it has finished.
 */
public final class MotionFx {
    private MotionFx() {}

    // ── Wave: surf tilt ───────────────────────────────────────────────────

    /**
     * Word tilt in degrees while riding the travelling wave: follows the slope
     * (derivative) of the ripple, so words lean into the crest like a surfer.
     */
    public static float surfTilt(double angle, double amplitude) {
        return (float) (Math.cos(angle) * amplitude * 6.0);
    }

    // ── Heartbeat: looping lub-dub ────────────────────────────────────────

    /** Seconds per heartbeat while the word is sung (~73 bpm). */
    public static final float HEART_PERIOD = 0.82f;

    /**
     * Extra scale (0..~0.075) of a looping lub-dub beat {@code tSec} after the
     * word started: a strong "lub" then a softer "dub", repeating every
     * HEART_PERIOD for as long as the word is sung.
     */
    public static float heartbeat(float tSec) {
        if (tSec <= 0f) return 0f;
        float t = tSec % HEART_PERIOD;
        double lub = Math.exp(-sq((t - 0.07f) / 0.035f));
        double dub = Math.exp(-sq((t - 0.25f) / 0.045f));
        return (float) (0.075 * lub + 0.05 * dub);
    }

    // ── Squash-Stretch: jelly wobble ──────────────────────────────────────

    /**
     * Damped jelly oscillation {@code tSec} after the word started. Fills
     * {@code out} with {sx, sy}: starts squashed (wide/short), wobbles through
     * tall/narrow and settles to exactly 1,1. Area is roughly preserved.
     */
    public static void jelly(float tSec, float[] out) {
        if (tSec <= 0f || tSec >= JELLY_LIFE) {
            out[0] = 1f;
            out[1] = 1f;
            return;
        }
        double d = Math.exp(-3.6 * tSec) * Math.cos(2.0 * Math.PI * 2.7 * tSec);
        // Fade the last 15% so the wobble lands on 1,1 without a visible step.
        float tail = clamp01((JELLY_LIFE - tSec) / (JELLY_LIFE * 0.15f));
        out[0] = (float) (1.0 + 0.09 * d * tail);
        out[1] = (float) (1.0 - 0.15 * d * tail);
    }

    /** Seconds the jelly wobble lasts. */
    public static final float JELLY_LIFE = 1.1f;

    // ── Drop / Bounce: impact squash ──────────────────────────────────────

    /**
     * Landing squash from the vertical offset (fraction of view height, positive
     * = below rest). When the falling word overshoots past its rest line it
     * flattens on impact; above rest it stays round. Fills {sx, sy}.
     */
    public static void impactSquash(float yOffset, float[] out) {
        float d = Math.max(0f, Math.min(0.06f, yOffset));
        out[0] = 1f + 1.9f * d;
        out[1] = 1f - 2.8f * d;
    }

    /**
     * Fall stretch: while the word is still dropping from above (negative
     * offset) it stretches slightly tall, like a falling drop. Returns sy.
     */
    public static float fallStretch(float yOffset, float velocity) {
        if (yOffset >= 0f || velocity <= 0f) return 1f;
        return 1f + Math.min(0.08f, velocity * 0.02f);
    }

    // ── Spin Settle: 3D letter flip ───────────────────────────────────────

    /** Seconds one letter card-flip takes (time-based so short letters still flip). */
    public static final float FLIP_LIFE = 0.55f;

    /**
     * Y-axis flip angle (degrees, 0..360) {@code tSec} after a letter is struck:
     * one full card-flip with a cubic ease-out, then rests face-on. Before the
     * strike and after FLIP_LIFE the angle is exactly 0.
     */
    public static float flipDegrees(float tSec) {
        if (tSec <= 0f || tSec >= FLIP_LIFE) return 0f;
        float u = tSec / FLIP_LIFE;
        float e = 1f - (1f - u) * (1f - u) * (1f - u);
        return 360f * e;
    }

    // ── Scatter Pop: letters assemble from an explosion ───────────────────

    /** Seconds a scattered letter takes to fly home. */
    public static final float EXPLODE_LIFE = 0.52f;

    /**
     * Fly-in pose for letter {@code i} at normalised fly time {@code u} (0..1 of
     * EXPLODE_LIFE since the letter was struck). Fills {@code out} with
     * {dx, dy, rotDeg}: dx/dy in text-size units. Letters start scattered on a
     * random ring and fly home with a small overshoot; at {@code u <= 0} and
     * {@code u >= 1} the pose is exactly zero.
     */
    public static void explode(int seed, int i, float u, float[] out) {
        if (u <= 0f || u >= 1f) {
            out[0] = 0f;
            out[1] = 0f;
            out[2] = 0f;
            return;
        }
        float ang = EffectFx.hash01(seed + i * 17 + 3) * 6.2832f;
        float dist = 0.9f + 0.9f * EffectFx.hash01(seed + i * 17 + 4);
        float rot = (EffectFx.hash01(seed + i * 17 + 5) - 0.5f) * 140f;
        // easeOutBack remaining distance: 1 -> slight negative overshoot -> 0.
        float c = 1.6f;
        float back = 1f + (c + 1f) * cube(u - 1f) + c * sq(u - 1f);
        float rem = 1f - back;
        out[0] = (float) Math.cos(ang) * dist * rem;
        out[1] = (float) Math.sin(ang) * dist * rem;
        out[2] = rot * rem;
    }

    // ── Scatter: per-letter tilt ──────────────────────────────────────────

    /** Tilt (degrees) a scattered letter takes while it pops: sign/size per letter. */
    public static float scatterTilt(int i, float popAmount) {
        float h = EffectFx.hash01(i * 29 + 7) - 0.5f;
        return Math.max(-16f, Math.min(16f, h * 2f * 16f * clamp01(popAmount / 0.2f)));
    }

    // ── Elastic Pop: volume-preserving rubber stretch ─────────────────────

    /**
     * Rubber stretch for a letter at uniform scale {@code s}: overshoot (s>1)
     * stretches it tall and thin, undershoot squashes it wide. Fills {sx, sy}.
     */
    public static void rubber(float s, float[] out) {
        float k = 1f + 1.4f * (s - 1f);
        k = Math.max(0.7f, Math.min(1.35f, k));
        out[0] = s / (float) Math.sqrt(k);
        out[1] = s * (float) Math.sqrt(k);
    }

    // ── Bubble: floating line ─────────────────────────────────────────────

    /**
     * Gentle buoyant bob (fraction of view height) for a sung word of the
     * active line: each word floats on its own phase so the line drifts like
     * bubbles on water. Bounded to ±0.022.
     */
    public static float floatBob(long positionMs, int wordIndex) {
        double t = positionMs * 0.001;
        double a = Math.sin(t * 2.4 + wordIndex * 1.3);
        double b = Math.sin(t * 3.9 + wordIndex * 2.1);
        return (float) (0.015 * a + 0.007 * b);
    }

    // ── Classic: bouncing karaoke ball ────────────────────────────────────

    /**
     * Height (0..1) of the karaoke ball over a word at word progress
     * {@code progress}: it lands on the word start, arcs, and lands again at
     * the end (= the next word's start). Parabolic, so it reads as gravity.
     */
    public static float ballHeight(float progress) {
        float p = clamp01(progress);
        return 4f * p * (1f - p);
    }

    /** Ball squash (sy) on touchdown — flattens within 8% of each landing. */
    public static float ballSquash(float progress) {
        float p = clamp01(progress);
        float edge = Math.min(p, 1f - p);
        return edge >= 0.08f ? 1f : 0.7f + 0.3f * (edge / 0.08f);
    }

    // ── Typewriter: caret ─────────────────────────────────────────────────

    /**
     * Caret alpha 0..1: solid while letters are still being struck, then a
     * classic 530ms on/off blink with soft edges once the word is fully typed.
     */
    public static float caretAlpha(long positionMs, boolean typing) {
        if (typing) return 1f;
        double t = (positionMs % 1060L) / 1060.0;
        double s = 0.5 + 0.5 * Math.cos(t * 2.0 * Math.PI);
        return (float) clamp01((float) ((s - 0.25) / 0.5));
    }

    // ── Pulse: chromatic bass thump ───────────────────────────────────────

    /**
     * RGB-split distance (text-size units) for the Pulse thump: proportional
     * to how far the word is scaled past rest, zero at rest or below.
     */
    public static float chromaSplit(float scale) {
        return Math.max(0f, Math.min(0.09f, (scale - 1f) * 0.75f));
    }

    // ── Zoom: radial motion blur ──────────────────────────────────────────

    /** Zoom-blur strength 0..1 from the current word scale (peak zoom ≈ 1.16). */
    public static float zoomBlur(float scale) {
        return clamp01((scale - 1.02f) / 0.12f);
    }

    // ── Drop / Bounce: landing aftermath ──────────────────────────────────

    /** Seconds the landing splash / dust lasts. */
    public static final float LAND_LIFE = 0.65f;

    /**
     * Droplet {@code i} of a Drop splash {@code tSec} after landing. Fills
     * {@code out} with {dx, dy} in text-size units relative to the landing
     * point (word bottom-centre); droplets arc out both sides under gravity.
     */
    public static void droplet(int seed, int i, float tSec, float[] out) {
        float side = (i % 2 == 0) ? -1f : 1f;
        float vx = side * (0.9f + 1.3f * EffectFx.hash01(seed + i * 5 + 1));
        float vy = -(1.6f + 1.4f * EffectFx.hash01(seed + i * 5 + 2));
        float g = 7.5f;
        out[0] = vx * tSec;
        out[1] = vy * tSec + 0.5f * g * tSec * tSec;
    }

    // ── Rise: water reflection ────────────────────────────────────────────

    /** Reflection alpha 0..0.30 over the word: swells as it rises, then fades. */
    public static float reflection(float progress) {
        if (progress <= 0f || progress >= 1f) return 0f;
        return 0.30f * (float) Math.sin(Math.PI * progress);
    }

    static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float sq(float v) {
        return v * v;
    }

    private static float cube(float v) {
        return v * v * v;
    }
}
