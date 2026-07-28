package com.lyricsync.app.renderer;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @deprecated Transitional facade over {@link AnimStyleDef.Registry}. Kept only so
 * the UI can keep showing the 16 legacy flat styles (and read the legacy pref)
 * until the combination UI lands — all parameterisation now delegates to the
 * registry. The four legacy standalone EFFECT styles (glow/echo/shimmer/flicker)
 * are reproduced below as composite defs with their exact legacy motion values,
 * since they carried subtle motion tweaks the effect registry entries don't have.
 */
@Deprecated
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

    // ── Legacy standalone effect styles (exact legacy parameters) ────────────

    private static final AnimStyleDef LEGACY_GLOW = new AnimStyleDef.Builder(
            "glow", "Neon Glow", AnimStyleDef.Category.EFFECT)
            .glow(new double[]{0.0, 0.10, 0.6, 1.0}, new double[]{0.0, 1.0, 0.9, 0.0})
            .glowOn().boost(1.8f)
            .build();

    private static final AnimStyleDef LEGACY_ECHO = new AnimStyleDef.Builder(
            "echo", "Echo", AnimStyleDef.Category.EFFECT)
            .scale(new double[]{0.0, 0.60, 1.0}, new double[]{0.96, 1.05, 1.0})
            .yOff(new double[]{0.0, 0.70, 1.0}, new double[]{0.0, -0.02, 0.0})
            .glow(new double[]{0.0, 0.12, 0.55, 1.0}, new double[]{0.0, 0.7, 0.7, 0.0})
            .springs().glowOn().echo()
            .damp(0.56)
            .build();

    private static final AnimStyleDef LEGACY_SHIMMER = new AnimStyleDef.Builder(
            "shimmer", "Shimmer", AnimStyleDef.Category.EFFECT)
            .glow(new double[]{0.0, 1.0}, new double[]{0.0, 0.0})
            .shimmer()
            .build();

    private static final AnimStyleDef LEGACY_FLICKER = new AnimStyleDef.Builder(
            "flicker", "Neon Flicker", AnimStyleDef.Category.EFFECT)
            .glow(new double[]{0.0, 0.08, 1.0}, new double[]{0.0, 1.0, 0.85})
            .glowOn().flicker().boost(1.6f)
            .build();

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

    // ── Parameterisation — all delegated to the registry / legacy composites ──

    private static AnimStyleDef def(int style) {
        switch (style) {
            case GLOW: return LEGACY_GLOW;
            case ECHO: return LEGACY_ECHO;
            case SHIMMER: return LEGACY_SHIMMER;
            case FLICKER: return LEGACY_FLICKER;
            default: return AnimStyleDef.Registry.motionByKey(keyOf(style));
        }
    }

    /** Resolved render config for a legacy flat style id. The four standalone
     *  effect styles carry their own motion values, so the same def feeds both
     *  sides of the merge; motion styles pair with the "none" effect. */
    public static AnimConfig configOf(int style) {
        AnimStyleDef d = def(style);
        if (d.category == AnimStyleDef.Category.EFFECT) {
            return AnimConfig.combine(d, d);
        }
        return AnimConfig.combine(d, AnimStyleDef.Registry.none());
    }

    public static Spline scaleSpline(int style) { return def(style).scaleSpline; }

    public static Spline yOffsetSpline(int style) { return def(style).yOffsetSpline; }

    public static Spline glowSpline(int style) { return def(style).glowSpline; }

    public static Spline letterScaleSpline(int style) { return def(style).letterScaleSpline; }

    public static Spline letterYOffsetSpline(int style) { return def(style).letterYOffsetSpline; }

    public static boolean springsEnabled(int style) { return def(style).springsEnabled; }

    public static boolean glowEnabled(int style) { return def(style).glowEnabled; }

    public static float glowBoost(int style) { return def(style).glowBoost; }

    public static boolean letterEmphasisEnabled(int style) { return def(style).letterEmphasis; }

    public static long letterMinDuration(int style) { return def(style).letterMinDuration; }

    public static boolean binaryLetterReveal(int style) { return def(style).binaryLetters; }

    public static boolean waveEnabled(int style) { return def(style).wave; }

    public static boolean echoEnabled(int style) { return def(style).echo; }

    public static boolean shimmerEnabled(int style) { return def(style).shimmer; }

    public static boolean flickerEnabled(int style) { return def(style).flicker; }

    public static boolean swingEnabled(int style) { return def(style).swing; }

    public static boolean classicStyle(int style) { return def(style).classic; }

    public static boolean scatterEnabled(int style) { return def(style).scatter; }

    public static double scaleFreq(int style) { return def(style).scaleFreq; }

    public static double scaleDamp(int style) { return def(style).scaleDamp; }
}
