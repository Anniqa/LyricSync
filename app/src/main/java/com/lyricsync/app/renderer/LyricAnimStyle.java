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
    public static final int DROP = 8;
    public static final int ZOOM = 9;
    public static final int ECHO = 10;
    public static final int SHIMMER = 11;
    public static final int FLICKER = 12;
    public static final int SWING = 13;
    public static final int CLASSIC = 14;
    public static final int SCATTER = 15;

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
        v.add(new Variant(DROP, "drop", "Drop"));
        v.add(new Variant(ZOOM, "zoom", "Zoom"));
        v.add(new Variant(ECHO, "echo", "Echo"));
        v.add(new Variant(SHIMMER, "shimmer", "Shimmer"));
        v.add(new Variant(SWING, "swing", "Swing"));
        v.add(new Variant(SCATTER, "scatter", "Scatter"));
        v.add(new Variant(FLICKER, "flicker", "Neon Flicker"));
        v.add(new Variant(CLASSIC, "classic", "Classic"));
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
            case DROP:
                // Slight anticipation shrink, then a firm landing pop.
                return spline(new double[]{0.0, 0.55, 1.0}, new double[]{0.92, 1.06, 1.0});
            case ZOOM:
                // Reverse-feel pop: idle at 1.0 (matching neighbours), spikes early,
                // then shrinks back — reads as the word coming at you and settling.
                return spline(new double[]{0.0, 0.28, 1.0}, new double[]{1.0, 1.16, 1.0});
            case ECHO:
                return spline(new double[]{0.0, 0.60, 1.0}, new double[]{0.96, 1.05, 1.0});
            case SWING:
                return spline(new double[]{0.0, 0.55, 1.0}, new double[]{0.97, 1.06, 1.0});
            case CALM:
            case GLOW:
            case WAVE:
            case TYPEWRITER:
            case SHIMMER:
            case FLICKER:
            case CLASSIC:
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
            case DROP:
                // Falls from well above the line and lands with a tiny rebound.
                return spline(new double[]{0.0, 0.70, 1.0}, new double[]{-0.30, 0.03, 0.0});
            case ZOOM:
                return spline(new double[]{0.0, 0.60, 1.0}, new double[]{0.01, -0.01, 0.0});
            case ECHO:
                return spline(new double[]{0.0, 0.70, 1.0}, new double[]{0.0, -0.02, 0.0});
            case SWING:
            case CALM:
            case GLOW:
            case WAVE:
            case TYPEWRITER:
            case SHIMMER:
            case FLICKER:
            case CLASSIC:
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
            case DROP:
            case ZOOM:
            case SWING:
            case SCATTER:
                return spline(new double[]{0.0, 0.14, 0.55, 1.0}, new double[]{0.0, 0.9, 0.9, 0.0});
            case ECHO:
                return spline(new double[]{0.0, 0.12, 0.55, 1.0}, new double[]{0.0, 0.7, 0.7, 0.0});
            case FLICKER:
                // Held high the whole word — the flicker modulation happens per-frame.
                return spline(new double[]{0.0, 0.08, 1.0}, new double[]{0.0, 1.0, 0.85});
            case CALM:
            case TYPEWRITER:
            case SHIMMER:
            case CLASSIC:
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
            case SCATTER:
                // Pop amplitude is additionally randomised per letter at draw time.
                return spline(new double[]{0.0, 0.55, 1.0}, new double[]{0.88, 1.20, 1.0});
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
            case SCATTER:
                // Bigger lift; the per-letter hash factor spreads the heights out.
                return spline(new double[]{0.0, 0.65, 1.0}, new double[]{0.0, -0.07, 0.0});
            case SPRING:
            default:
                return spline(new double[]{0.0, 0.85, 1.0}, new double[]{0.012, -0.028, 0.0});
        }
    }

    /** Whether the word/letter springs run. CALM/GLOW have no spring motion; WAVE
     *  drives its offset procedurally from the playback clock instead. */
    public static boolean springsEnabled(int style) {
        return style != CALM && style != GLOW && style != WAVE
                && style != SHIMMER && style != FLICKER && style != CLASSIC;
    }

    /** Whether the sung-edge light bloom is drawn. */
    public static boolean glowEnabled(int style) {
        return style == SPRING || style == BUBBLE || style == GLOW
                || style == WAVE || style == PULSE || style == RISE
                || style == DROP || style == ZOOM || style == ECHO
                || style == SWING || style == SCATTER || style == FLICKER;
    }

    /** Multiplier on the bloom radius; GLOW reads as neon, others stay subtle. */
    public static float glowBoost(int style) {
        if (style == GLOW) return 1.8f;
        if (style == FLICKER) return 1.6f;
        return 1.0f;
    }

    /** Letter-by-letter emphasis only makes sense when springs are active. */
    public static boolean letterEmphasisEnabled(int style) {
        return style == SPRING || style == BUBBLE || style == TYPEWRITER || style == SCATTER;
    }

    /** Minimum word duration (ms) for letter-by-letter emphasis. SPRING/BUBBLE follow
     *  SpicyLyrics (>= 1000ms) so the wave only decorates long held words; TYPEWRITER's
     *  and SCATTER's whole identity is per-letter, so they must work on EVERY word —
     *  otherwise short words silently fall back to the plain sweep and the variant
     *  looks identical to Calm. */
    public static long letterMinDuration(int style) {
        return (style == TYPEWRITER || style == SCATTER) ? 0 : 1000;
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

    /** ECHO draws fading ghost copies trailing above the active word. */
    public static boolean echoEnabled(int style) {
        return style == ECHO;
    }

    /** SHIMMER sweeps a light band across the sung part of the word. */
    public static boolean shimmerEnabled(int style) {
        return style == SHIMMER;
    }

    /** FLICKER modulates the glow with a deterministic neon-sign sputter. */
    public static boolean flickerEnabled(int style) {
        return style == FLICKER;
    }

    /** SWING rocks the active word a few degrees like a hanging sign. */
    public static boolean swingEnabled(int style) {
        return style == SWING;
    }

    /** CLASSIC is old-school karaoke: an instant dim->bright swap, zero motion. */
    public static boolean classicStyle(int style) {
        return style == CLASSIC;
    }

    /** SCATTER randomises each letter's pop amplitude (draw-time hash). */
    public static boolean scatterEnabled(int style) {
        return style == SCATTER;
    }

    /** Spring tuning for the scale spring; each variant keeps its own bounce
     *  character but settles fast enough that words never lurch past their slot. */
    public static double scaleFreq(int style) {
        switch (style) {
            case BUBBLE: return 0.85;
            case PULSE: return 1.15;
            case RISE: return 1.20;
            case TYPEWRITER: return 1.30;
            case DROP: return 1.00;
            case ZOOM: return 1.10;
            case ECHO: return 0.95;
            case SWING: return 0.90;
            case SCATTER: return 1.10;
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
            case DROP: return 0.42;   // bouncy landing
            case ZOOM: return 0.55;
            case ECHO: return 0.56;
            case SWING: return 0.50;
            case SCATTER: return 0.48;
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
