package com.lyricsync.app.ui;

import com.lyricsync.app.renderer.AnimConfig;
import java.util.HashMap;
import java.util.Map;

public class AnimSelectionSelfTest {

    private static final class MapPrefs implements AnimSelection.Prefs {
        final Map<String, String> map = new HashMap<>();
        public String get(String key) { return map.get(key); }
        public void put(String key, String value) { map.put(key, value); }
        public void remove(String key) { map.remove(key); }
    }

    public static void main(String[] args) {
        // Defaults with empty prefs.
        MapPrefs p = new MapPrefs();
        check("spring".equals(AnimSelection.motionKey(p)), "default motion spring");
        check("none".equals(AnimSelection.effectKey(p)), "default effect none");

        // Legacy effect-style migrates to calm x effect and clears the legacy key.
        p = new MapPrefs();
        p.put(AnimSelection.LEGACY_KEY, "glow");
        check("calm".equals(AnimSelection.motionKey(p)), "glow legacy -> calm");
        check("glow".equals(AnimSelection.effectKey(p)), "glow legacy -> glow effect");
        check(p.get(AnimSelection.LEGACY_KEY) == null, "legacy key removed");
        check("calm".equals(p.get(AnimSelection.PREF_MOTION)), "migration persisted motion");

        // Legacy motion-style migrates to itself x none.
        p = new MapPrefs();
        p.put(AnimSelection.LEGACY_KEY, "typewriter");
        check("typewriter".equals(AnimSelection.motionKey(p)), "typewriter stays motion");
        check("none".equals(AnimSelection.effectKey(p)), "no effect");

        // Existing new keys win over a stale legacy key.
        p = new MapPrefs();
        p.put(AnimSelection.PREF_MOTION, "wave");
        p.put(AnimSelection.PREF_EFFECT, "echo");
        p.put(AnimSelection.LEGACY_KEY, "glow");
        check("wave".equals(AnimSelection.motionKey(p)), "new motion wins");
        check("echo".equals(AnimSelection.effectKey(p)), "new effect wins");

        // Save round-trips and clears legacy.
        p = new MapPrefs();
        p.put(AnimSelection.LEGACY_KEY, "bubble");
        AnimSelection.save(p, "drop", "shimmer");
        check("drop".equals(AnimSelection.motionKey(p)), "saved motion");
        check("shimmer".equals(AnimSelection.effectKey(p)), "saved effect");
        check(p.get(AnimSelection.LEGACY_KEY) == null, "save clears legacy");

        // current() resolves a real config.
        AnimConfig cfg = AnimSelection.current(p);
        check("drop".equals(cfg.motionKey) && "shimmer".equals(cfg.effectKey), "current config keys");
        check(cfg.shimmer && !cfg.echo, "config flags");

        // migrate() pure mapping.
        String[] m = AnimSelection.migrate("flicker");
        check("calm".equals(m[0]) && "flicker".equals(m[1]), "migrate flicker");
        m = AnimSelection.migrate(null);
        check("spring".equals(m[0]) && "none".equals(m[1]), "migrate null");

        // Round-trip a combo made entirely of new keys.
        p = new MapPrefs();
        AnimSelection.save(p, "bounce", "sparkle");
        AnimConfig nc = AnimSelection.current(p);
        check("bounce".equals(nc.motionKey) && "sparkle".equals(nc.effectKey), "new combo round-trip");
        check(nc.sparkle && !nc.flash, "new combo flags");

        System.out.println("AnimSelectionSelfTest OK");
    }

    private static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
    }
}
