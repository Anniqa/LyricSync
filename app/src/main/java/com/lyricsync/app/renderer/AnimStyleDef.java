package com.lyricsync.app.renderer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable definition of one lyric animation style. All 16 styles live here as
 * DATA in the {@link Registry} — adding a style means adding one entry, not
 * editing switch statements (this class replaces the old switch-heavy
 * LyricAnimStyle parameterisation).
 *
 * Styles split into two categories for the combination feature:
 *  - MOTION (12): how words/letters move — spring, bubble, wave, pulse, rise,
 *    typewriter, drop, zoom, swing, scatter, calm, classic.
 *  - EFFECT (5): a visual overlay — none, glow, echo, shimmer, flicker.
 *
 * A motion and an effect are merged into an {@link AnimConfig} for rendering.
 *
 * Pure JVM: no android imports, so the self-tests compile with plain javac.
 */
public final class AnimStyleDef {

    public enum Category { MOTION, EFFECT }

    public final String key;
    public final String displayName;
    public final Category category;

    // Motion parameters (meaningful for MOTION styles; EFFECT styles leave defaults).
    public final Spline scaleSpline;
    public final Spline yOffsetSpline;
    public final Spline letterScaleSpline;
    public final Spline letterYOffsetSpline;
    public final boolean springsEnabled;
    public final boolean letterEmphasis;
    public final boolean binaryLetters;
    public final boolean wave;
    public final boolean swing;
    public final boolean classic;
    public final boolean scatter;
    public final double scaleFreq;
    public final double scaleDamp;
    public final long letterMinDuration;

    // Glow/effect parameters.
    /** Null means "inherit the motion style's glow spline" (shimmer, none). */
    public final Spline glowSpline;
    public final boolean glowEnabled;
    public final float glowBoost;
    public final boolean echo;
    public final boolean shimmer;
    public final boolean flicker;

    private AnimStyleDef(Builder b) {
        key = b.key;
        displayName = b.displayName;
        category = b.category;
        scaleSpline = b.scaleSpline;
        yOffsetSpline = b.yOffsetSpline;
        letterScaleSpline = b.letterScaleSpline;
        letterYOffsetSpline = b.letterYOffsetSpline;
        springsEnabled = b.springsEnabled;
        letterEmphasis = b.letterEmphasis;
        binaryLetters = b.binaryLetters;
        wave = b.wave;
        swing = b.swing;
        classic = b.classic;
        scatter = b.scatter;
        scaleFreq = b.scaleFreq;
        scaleDamp = b.scaleDamp;
        letterMinDuration = b.letterMinDuration;
        glowSpline = b.glowSpline;
        glowEnabled = b.glowEnabled;
        glowBoost = b.glowBoost;
        echo = b.echo;
        shimmer = b.shimmer;
        flicker = b.flicker;
    }

    private static Spline spline(double[] times, double[] values) {
        List<Double> t = new ArrayList<>(times.length);
        for (double v : times) t.add(v);
        List<Double> val = new ArrayList<>(values.length);
        for (double v : values) val.add(v);
        return new Spline(t, val);
    }

    // Package-private so the transitional LyricAnimStyle facade can build the four
    // legacy standalone effect styles with their exact legacy motion values.
    static final class Builder {
        final String key;
        final String displayName;
        final Category category;

        // Defaults: neutral "no motion" behaviour; every flag off.
        Spline scaleSpline = spline(new double[]{0.0, 1.0}, new double[]{1.0, 1.0});
        Spline yOffsetSpline = spline(new double[]{0.0, 1.0}, new double[]{0.0, 0.0});
        Spline letterScaleSpline = spline(new double[]{0.0, 0.65, 1.0}, new double[]{0.95, 1.22, 1.0});
        Spline letterYOffsetSpline = spline(new double[]{0.0, 0.85, 1.0}, new double[]{0.012, -0.028, 0.0});
        boolean springsEnabled = false;
        boolean letterEmphasis = false;
        boolean binaryLetters = false;
        boolean wave = false;
        boolean swing = false;
        boolean classic = false;
        boolean scatter = false;
        double scaleFreq = 0.95;
        double scaleDamp = 0.58;
        long letterMinDuration = 1000;

        Spline glowSpline = null;
        boolean glowEnabled = false;
        float glowBoost = 1.0f;
        boolean echo = false;
        boolean shimmer = false;
        boolean flicker = false;

        Builder(String key, String displayName, Category category) {
            this.key = key;
            this.displayName = displayName;
            this.category = category;
        }

        Builder scale(double[] t, double[] v) { scaleSpline = spline(t, v); return this; }
        Builder yOff(double[] t, double[] v) { yOffsetSpline = spline(t, v); return this; }
        Builder lScale(double[] t, double[] v) { letterScaleSpline = spline(t, v); return this; }
        Builder lYOff(double[] t, double[] v) { letterYOffsetSpline = spline(t, v); return this; }
        Builder glow(double[] t, double[] v) { glowSpline = spline(t, v); return this; }
        Builder springs() { springsEnabled = true; return this; }
        Builder letters() { letterEmphasis = true; return this; }
        Builder binary() { binaryLetters = true; return this; }
        Builder wave() { wave = true; return this; }
        Builder swing() { swing = true; return this; }
        Builder classic() { classic = true; return this; }
        Builder scatter() { scatter = true; return this; }
        Builder freq(double f) { scaleFreq = f; return this; }
        Builder damp(double d) { scaleDamp = d; return this; }
        Builder minDur(long ms) { letterMinDuration = ms; return this; }
        Builder glowOn() { glowEnabled = true; return this; }
        Builder boost(float b) { glowBoost = b; return this; }
        Builder echo() { echo = true; return this; }
        Builder shimmer() { shimmer = true; return this; }
        Builder flicker() { flicker = true; return this; }

        AnimStyleDef build() { return new AnimStyleDef(this); }
    }

    /** Shared glow envelope used by several motion styles. */
    private static final double[] GLOW_STD_T = {0.0, 0.12, 0.55, 1.0};
    private static final double[] GLOW_STD_V = {0.0, 1.0, 1.0, 0.0};
    private static final double[] GLOW_SOFT_T = {0.0, 0.14, 0.55, 1.0};
    private static final double[] GLOW_SOFT_V = {0.0, 0.9, 0.9, 0.0};

    /** Holds the style definitions and answers lookups by key. */
    public static final class Registry {
        private static final List<AnimStyleDef> MOTION;
        private static final List<AnimStyleDef> EFFECT;
        private static final AnimStyleDef NONE;

        static {
            List<AnimStyleDef> m = new ArrayList<>();
            m.add(new Builder("spring", "Spring Pop", Category.MOTION)
                    .scale(new double[]{0.0, 0.65, 1.0}, new double[]{0.95, 1.10, 1.0})
                    .yOff(new double[]{0.0, 0.85, 1.0}, new double[]{0.012, -0.026, 0.0})
                    .glow(GLOW_STD_T, GLOW_STD_V)
                    .springs().glowOn().letters()
                    .build());
            m.add(new Builder("bubble", "Bubble", Category.MOTION)
                    .scale(new double[]{0.0, 0.62, 1.0}, new double[]{0.94, 1.14, 1.0})
                    .yOff(new double[]{0.0, 0.75, 1.0}, new double[]{0.0, -0.045, 0.0})
                    .glow(GLOW_STD_T, GLOW_STD_V)
                    .lScale(new double[]{0.0, 0.60, 1.0}, new double[]{0.94, 1.18, 1.0})
                    .lYOff(new double[]{0.0, 0.75, 1.0}, new double[]{0.0, -0.04, 0.0})
                    .springs().glowOn().letters()
                    .freq(0.85).damp(0.52)
                    .build());
            m.add(new Builder("wave", "Wave", Category.MOTION)
                    .glow(new double[]{0.0, 0.15, 0.6, 1.0}, new double[]{0.0, 0.8, 0.8, 0.0})
                    .glowOn().wave()
                    .build());
            m.add(new Builder("pulse", "Pulse", Category.MOTION)
                    .scale(new double[]{0.0, 0.12, 0.28, 0.45, 1.0},
                            new double[]{0.97, 1.12, 0.99, 1.06, 1.0})
                    .yOff(new double[]{0.0, 0.5, 1.0}, new double[]{0.0, -0.02, 0.0})
                    .glow(new double[]{0.0, 0.10, 0.5, 1.0}, new double[]{0.0, 1.0, 1.0, 0.0})
                    .springs().glowOn()
                    .freq(1.15).damp(0.50)
                    .build());
            m.add(new Builder("rise", "Rise", Category.MOTION)
                    .scale(new double[]{0.0, 0.5, 1.0}, new double[]{0.98, 1.02, 1.0})
                    .yOff(new double[]{0.0, 0.62, 1.0}, new double[]{0.09, -0.05, 0.0})
                    .glow(new double[]{0.0, 0.15, 0.6, 1.0}, new double[]{0.0, 0.8, 0.8, 0.0})
                    .springs().glowOn()
                    .freq(1.20).damp(0.50)
                    .build());
            m.add(new Builder("typewriter", "Typewriter", Category.MOTION)
                    .glow(new double[]{0.0, 1.0}, new double[]{0.0, 0.0})
                    .lScale(new double[]{0.0, 0.35, 1.0}, new double[]{0.60, 1.16, 1.0})
                    .lYOff(new double[]{0.0, 0.50, 1.0}, new double[]{0.0, -0.02, 0.0})
                    .springs().letters().binary()
                    .minDur(0)
                    .freq(1.30).damp(0.55)
                    .build());
            m.add(new Builder("drop", "Drop", Category.MOTION)
                    .scale(new double[]{0.0, 0.55, 1.0}, new double[]{0.92, 1.06, 1.0})
                    .yOff(new double[]{0.0, 0.70, 1.0}, new double[]{-0.30, 0.03, 0.0})
                    .glow(GLOW_SOFT_T, GLOW_SOFT_V)
                    .springs().glowOn()
                    .freq(1.00).damp(0.42)
                    .build());
            m.add(new Builder("zoom", "Zoom", Category.MOTION)
                    .scale(new double[]{0.0, 0.28, 1.0}, new double[]{1.0, 1.16, 1.0})
                    .yOff(new double[]{0.0, 0.60, 1.0}, new double[]{0.01, -0.01, 0.0})
                    .glow(GLOW_SOFT_T, GLOW_SOFT_V)
                    .springs().glowOn()
                    .freq(1.10).damp(0.55)
                    .build());
            m.add(new Builder("swing", "Swing", Category.MOTION)
                    .scale(new double[]{0.0, 0.55, 1.0}, new double[]{0.97, 1.06, 1.0})
                    .glow(GLOW_SOFT_T, GLOW_SOFT_V)
                    .springs().glowOn().swing()
                    .freq(0.90).damp(0.50)
                    .build());
            m.add(new Builder("scatter", "Scatter", Category.MOTION)
                    .glow(GLOW_SOFT_T, GLOW_SOFT_V)
                    .lScale(new double[]{0.0, 0.55, 1.0}, new double[]{0.88, 1.20, 1.0})
                    .lYOff(new double[]{0.0, 0.65, 1.0}, new double[]{0.0, -0.07, 0.0})
                    .springs().glowOn().letters().scatter()
                    .minDur(0)
                    .freq(1.10).damp(0.48)
                    .build());
            m.add(new Builder("calm", "Calm", Category.MOTION)
                    .glow(new double[]{0.0, 1.0}, new double[]{0.0, 0.0})
                    .build());
            m.add(new Builder("classic", "Classic", Category.MOTION)
                    .glow(new double[]{0.0, 1.0}, new double[]{0.0, 0.0})
                    .classic()
                    .build());
            MOTION = Collections.unmodifiableList(m);

            NONE = new Builder("none", "Tanpa", Category.EFFECT).build();
            List<AnimStyleDef> e = new ArrayList<>();
            e.add(NONE);
            e.add(new Builder("glow", "Neon Glow", Category.EFFECT)
                    .glow(new double[]{0.0, 0.10, 0.6, 1.0}, new double[]{0.0, 1.0, 0.9, 0.0})
                    .glowOn().boost(1.8f)
                    .build());
            e.add(new Builder("echo", "Echo", Category.EFFECT)
                    .glow(new double[]{0.0, 0.12, 0.55, 1.0}, new double[]{0.0, 0.7, 0.7, 0.0})
                    .glowOn().echo()
                    .build());
            e.add(new Builder("shimmer", "Shimmer", Category.EFFECT)
                    .shimmer()   // glowSpline stays null: inherit the motion style's glow.
                    .build());
            e.add(new Builder("flicker", "Neon Flicker", Category.EFFECT)
                    .glow(new double[]{0.0, 0.08, 1.0}, new double[]{0.0, 1.0, 0.85})
                    .glowOn().flicker().boost(1.6f)
                    .build());
            EFFECT = Collections.unmodifiableList(e);
        }

        private Registry() {}

        public static List<AnimStyleDef> motionStyles() {
            return MOTION;
        }

        public static List<AnimStyleDef> effectStyles() {
            return EFFECT;
        }

        public static AnimStyleDef none() {
            return NONE;
        }

        public static AnimStyleDef motionByKey(String key) {
            if (key != null) {
                for (AnimStyleDef d : MOTION) {
                    if (d.key.equals(key)) return d;
                }
            }
            return MOTION.get(0); // spring
        }

        public static AnimStyleDef effectByKey(String key) {
            if (key != null) {
                for (AnimStyleDef d : EFFECT) {
                    if (d.key.equals(key)) return d;
                }
            }
            return NONE;
        }
    }
}
