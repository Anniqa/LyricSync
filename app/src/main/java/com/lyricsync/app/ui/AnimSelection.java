package com.lyricsync.app.ui;

import com.lyricsync.app.renderer.AnimConfig;
import com.lyricsync.app.renderer.AnimStyleDef;

/**
 * Reads/writes the lyric animation combination (1 motion key + 1 effect key)
 * and migrates the legacy single-style pref on first read.
 *
 * Storage goes through the tiny {@link Prefs} interface instead of
 * SharedPreferences directly, so this class stays android-free and
 * JVM-testable; production code passes {@link AndroidPrefs}.
 */
public final class AnimSelection {
    private AnimSelection() {}

    public interface Prefs {
        String get(String key);              // null when absent
        void put(String key, String value);
        void remove(String key);
    }

    public static final String PREF_MOTION = "lyric_anim_motion";
    public static final String PREF_EFFECT = "lyric_anim_effect";
    /** Legacy single-style key written by builds before the combo feature. */
    public static final String LEGACY_KEY = "lyric_anim_style";

    public static String motionKey(Prefs p) {
        migrateIfNeeded(p);
        String key = p.get(PREF_MOTION);
        return key != null ? key : "spring";
    }

    public static String effectKey(Prefs p) {
        migrateIfNeeded(p);
        String key = p.get(PREF_EFFECT);
        return key != null ? key : "none";
    }

    /** The resolved render configuration for the current selection. */
    public static AnimConfig current(Prefs p) {
        return AnimConfig.combine(
                AnimStyleDef.Registry.motionByKey(motionKey(p)),
                AnimStyleDef.Registry.effectByKey(effectKey(p)));
    }

    public static void save(Prefs p, String motionKey, String effectKey) {
        p.put(PREF_MOTION, motionKey);
        p.put(PREF_EFFECT, effectKey);
        p.remove(LEGACY_KEY);
    }

    /**
     * Maps a legacy style key to a {motion, effect} pair. The four effect-only
     * styles were "no motion + overlay", so they become calm x effect.
     */
    public static String[] migrate(String legacyKey) {
        if (legacyKey == null) return new String[]{"spring", "none"};
        switch (legacyKey) {
            case "glow":
            case "echo":
            case "shimmer":
            case "flicker":
                return new String[]{"calm", legacyKey};
            default:
                return new String[]{legacyKey, "none"};
        }
    }

    /** One-time lazy migration: legacy key present and no new keys yet. */
    private static void migrateIfNeeded(Prefs p) {
        if (p.get(PREF_MOTION) == null) {
            String legacy = p.get(LEGACY_KEY);
            if (legacy != null) {
                String[] pair = migrate(legacy);
                p.put(PREF_MOTION, pair[0]);
                p.put(PREF_EFFECT, pair[1]);
                p.remove(LEGACY_KEY);
            }
        }
    }
}
