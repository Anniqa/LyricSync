package com.lyricsync.app.renderer;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The user-selectable lyric animation variants. A style is a bundle of motion
 * parameters (per-word scale / rise / glow splines) plus feature switches
 * (springs, glow bloom, letter emphasis) consumed by GradientWordView.
 *
 * Variants:
 *  - SPRING: the default SpicyLyrics-like pop — scale overshoot, rise and glow.
 *  - BUBBLE: bouncier, exaggerated squash-and-stretch for a playful feel.
 *  - CALM:   no springs at all — a pure left-to-right karaoke sweep.
 *  - GLOW:   no scale, but a strong neon bloom that follows the sung edge.
 */
public final class LyricAnimStyle {
    private LyricAnimStyle() {}

    public static final String PREF_KEY = "lyric_anim_style";

    public static final int SPRING = 0;
    public static final int BUBBLE = 1;
    public static final int CALM = 2;
    public static final int GLOW = 3;
    public static final int WAVE = 4;
    public static final int PULSE = 5;
    public static final int RISE = 6;
    public static final int TYPEWRITER = 7;

    public static final class Variant {
        public final int id;
        public final String key;
        public final String displayName;

        Variant(int id, String key, String displayName) {
            this.id = id;
            this.key = key;
            this.displayName = displayName;
        }
    }

    private static final List<Variant> VARIANTS;

    static {
        List<Variant> v = new ArrayList<>();
        v.add(new Variant(SPRING, "spring", "Spring Pop"));
        v.add(new Variant(BUBBLE, "bubble", "Bubble"));
        v.add(new Variant(WAVE, "wave", "Wave"));
        v.add(new Variant(PULSE, "pulse", "Pulse"));
        v.add(new Variant(RISE, "rise", "Rise"));
        v.add(new Variant(TYPEWRITER, "typewriter", "Typewriter"));
        v.add(new Variant(CALM, "calm", "Calm"));
        v.add(new Variant(GLOW, "glow", "Neon Glow"));
        VARIANTS = Collections.unmodifiableList(v);
    }

    public static List<Variant> variants() {
        return VARIANTS;
    }

    public static int byKey(String key) {
        if (key != null) {
            for (Variant v : VARIANTS) {
                if (v.key.equals(key)) return v.id;
            }
        }
        return SPRING;
    }

    public static String keyOf(int style) {
        for (Variant v : VARIANTS) {
            if (v.id == style) return v.key;
        }
        return "spring";
    }

    public static String displayNameOf(int style) {
        for (Variant v : VARIANTS) {
            if (v.id == style) return v.displayName;
        }
        return "Spring Pop";
    }

    public static int current(SharedPreferences prefs) {
        return byKey(prefs.getString(PREF_KEY, "spring"));
    }

    // ── Motion parameterisation ────────────────────────────────────────────

    /** Per-word scale spline: (rest, peak, settle). Peaks stay modest so an
     *  expanding word never rides over its neighbour and reads as "jumping forward". */
    public static Spline scaleSpline(int style) {
        switch (style) {
            case BUBBLE:
                return spline(new double[]{0.0, 0.62, 1.0}, new double[]{0.94, 1.14, 1.0});
            case PULSE:
                // Heartbeat: thump-thump, then rest.
                return spline(new double[]{0.0, 0.12, 0.28, 0.45, 1.0},
                        new double[]{0.97, 1.12, 0.99, 1.06, 1.0});
            case RISE:
                return spline(new double[]{0.0, 0.5, 1.0}, new double[]{0.98, 1.02, 1.0});
            case CALM:
            case GLOW:
            case WAVE:
            case TYPEWRITER:
                return spline(new double[]{0.0, 1.0}, new double[]{1.0, 1.0});
            case SPRING:
            default:
                return spline(new double[]{0.0, 0.65, 1.0}, new double[]{0.95, 1.10, 1.0});
        }
    }

    /** Per-word vertical offset spline, as a fraction of the view height. Starts at
     *  zero (no initial downward dip) so the word rises cleanly off its baseline. */
    public static Spline yOffsetSpline(int style) {
        switch (style) {
            case BUBBLE:
                return spline(new double[]{0.0, 0.75, 1.0}, new double[]{0.0, -0.045, 0.0});
            case RISE:
                // Words start noticeably below the baseline and land on it.
                return spline(new double[]{0.0, 0.62, 1.0}, new double[]{0.09, -0.05, 0.0});
            case PULSE:
                return spline(new double[]{0.0, 0.5, 1.0}, new double[]{0.0, -0.02, 0.0});
            case CALM:
            case GLOW:
            case WAVE:
            case TYPEWRITER:
                return spline(new double[]{0.0, 1.0}, new double[]{0.0, 0.0});
            case SPRING:
            default:
                return spline(new double[]{0.0, 0.85, 1.0}, new double[]{0.012, -0.026, 0.0});
        }
    }

    /** Per-word glow envelope spline. */
    public static Spline glowSpline(int style) {
        switch (style) {
            case GLOW:
                return spline(new double[]{0.0, 0.10, 0.6, 1.0}, new double[]{0.0, 1.0, 0.9, 0.0});
            case WAVE:
            case RISE:
                return spline(new double[]{0.0, 0.15, 0.6, 1.0}, new double[]{0.0, 0.8, 0.8, 0.0});
            case PULSE:
                return spline(new double[]{0.0, 0.10, 0.5, 1.0}, new double[]{0.0, 1.0, 1.0, 0.0});
            case CALM:
            case TYPEWRITER:
                return spline(new double[]{0.0, 1.0}, new double[]{0.0, 0.0});
            case SPRING:
            case BUBBLE:
            default:
                return spline(new double[]{0.0, 0.12, 0.55, 1.0}, new double[]{0.0, 1.0, 1.0, 0.0});
        }
    }

    /** Letter-level emphasis spline (only used when letters are enabled). */
    public static Spline letterScaleSpline(int style) {
        switch (style) {
            case BUBBLE:
                return spline(new double[]{0.0, 0.60, 1.0}, new double[]{0.94, 1.18, 1.0});
            case TYPEWRITER:
                // Each letter snaps in from small with a quick pop, like a key strike.
                return spline(new double[]{0.0, 0.35, 1.0}, new double[]{0.60, 1.16, 1.0});
            case SPRING:
            default:
                return spline(new double[]{0.0, 0.65, 1.0}, new double[]{0.95, 1.22, 1.0});
        }
    }

    public static Spline letterYOffsetSpline(int style) {
        switch (style) {
            case BUBBLE:
                return spline(new double[]{0.0, 0.75, 1.0}, new double[]{0.0, -0.04, 0.0});
            case TYPEWRITER:
                // No initial downward dip: keys strike in place, they don't slide up.
                return spline(new double[]{0.0, 0.50, 1.0}, new double[]{0.0, -0.02, 0.0});
            case SPRING:
            default:
                return spline(new double[]{0.0, 0.85, 1.0}, new double[]{0.012, -0.028, 0.0});
        }
    }

    /** Whether the word/letter springs run. CALM/GLOW have no spring motion; WAVE
     *  drives its offset procedurally from the playback clock instead. */
    public static boolean springsEnabled(int style) {
        return style != CALM && style != GLOW && style != WAVE;
    }

    /** Whether the sung-edge light bloom is drawn. */
    public static boolean glowEnabled(int style) {
        return style == SPRING || style == BUBBLE || style == GLOW
                || style == WAVE || style == PULSE || style == RISE;
    }

    /** Multiplier on the bloom radius; GLOW reads as neon, others stay subtle. */
    public static float glowBoost(int style) {
        return style == GLOW ? 1.8f : 1.0f;
    }

    /** Letter-by-letter emphasis only makes sense when springs are active. */
    public static boolean letterEmphasisEnabled(int style) {
        return style == SPRING || style == BUBBLE || style == TYPEWRITER;
    }

    /** Minimum word duration (ms) for letter-by-letter emphasis. SPRING/BUBBLE follow
     *  SpicyLyrics (>= 1000ms) so the wave only decorates long held words; TYPEWRITER's
     *  whole identity is the per-letter strike, so it must work on EVERY word —
     *  otherwise short words silently fall back to the plain sweep and the variant
     *  looks identical to Calm. */
    public static long letterMinDuration(int style) {
        return style == TYPEWRITER ? 0 : 1000;
    }

    /** TYPEWRITER reveals letters as a binary strike (dim -> bright) instead of the
     *  continuous left-to-right sweep used by the springy variants. */
    public static boolean binaryLetterReveal(int style) {
        return style == TYPEWRITER;
    }

    /** WAVE animates word Y offsets as a travelling sine along the line. */
    public static boolean waveEnabled(int style) {
        return style == WAVE;
    }

    /** Spring tuning for the scale spring; each variant keeps its own bounce
     *  character but settles fast enough that words never lurch past their slot. */
    public static double scaleFreq(int style) {
        switch (style) {
            case BUBBLE: return 0.85;
            case PULSE: return 1.15;
            case RISE: return 1.20;
            case TYPEWRITER: return 1.30;
            case SPRING:
            default: return 0.95;
        }
    }

    public static double scaleDamp(int style) {
        switch (style) {
            case BUBBLE: return 0.52;
            case PULSE: return 0.50;
            case RISE: return 0.50;
            case TYPEWRITER: return 0.55;
            case SPRING:
            default: return 0.58;
        }
    }

    private static Spline spline(double[] times, double[] values) {
        List<Double> t = new ArrayList<>(times.length);
        for (double v : times) t.add(v);
        List<Double> val = new ArrayList<>(values.length);
        for (double v : values) val.add(v);
        return new Spline(t, val);
    }
}
