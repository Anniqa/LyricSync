package com.lyricsync.app.ui;

import com.lyricsync.app.renderer.AnimConfig;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AnimSelectionSelfTest {

    private static final class MapPrefs implements AnimSelection.Prefs {
        final Map<String, String> map = new HashMap<>();
        public String get(String key) { return map.get(key); }
        public void put(String key, String value) { map.put(key, value); }
        public void remove(String key) { map.remove(key); }
    }

    @Test
    public void defaultsAndLegacyEffectMigration() {
        // Defaults with empty prefs.
        MapPrefs p = new MapPrefs();
        assertEquals("spring", AnimSelection.motionKey(p));
        assertEquals("none", AnimSelection.effectKey(p));

        // Legacy effect-style migrates to calm x effect and clears the legacy key.
        p = new MapPrefs();
        p.put(AnimSelection.LEGACY_KEY, "glow");
        assertEquals("calm", AnimSelection.motionKey(p));
        assertEquals("glow", AnimSelection.effectKey(p));
        assertNull("legacy key removed", p.get(AnimSelection.LEGACY_KEY));
        assertEquals("calm", p.get(AnimSelection.PREF_MOTION));
    }

    @Test
    public void legacyMotionAndNewKeysWin() {
        // Legacy motion-style migrates to itself x none.
        MapPrefs p = new MapPrefs();
        p.put(AnimSelection.LEGACY_KEY, "typewriter");
        assertEquals("typewriter", AnimSelection.motionKey(p));
        assertEquals("none", AnimSelection.effectKey(p));

        // Existing new keys win over a stale legacy key.
        p = new MapPrefs();
        p.put(AnimSelection.PREF_MOTION, "wave");
        p.put(AnimSelection.PREF_EFFECT, "echo");
        p.put(AnimSelection.LEGACY_KEY, "glow");
        assertEquals("wave", AnimSelection.motionKey(p));
        assertEquals("echo", AnimSelection.effectKey(p));
    }

    @Test
    public void saveClearsLegacyAndCurrentResolves() {
        // Save round-trips and clears legacy.
        MapPrefs p = new MapPrefs();
        p.put(AnimSelection.LEGACY_KEY, "bubble");
        AnimSelection.save(p, "drop", "shimmer");
        assertEquals("drop", AnimSelection.motionKey(p));
        assertEquals("shimmer", AnimSelection.effectKey(p));
        assertNull("save clears legacy", p.get(AnimSelection.LEGACY_KEY));

        // current() resolves a real config.
        AnimConfig cfg = AnimSelection.current(p);
        assertTrue("current config keys",
                "drop".equals(cfg.motionKey) && "shimmer".equals(cfg.effectKey));
        assertTrue("config flags", cfg.shimmer && !cfg.echo);

        // migrate() pure mapping.
        String[] m = AnimSelection.migrate("flicker");
        assertTrue("migrate flicker", "calm".equals(m[0]) && "flicker".equals(m[1]));
        m = AnimSelection.migrate(null);
        assertTrue("migrate null", "spring".equals(m[0]) && "none".equals(m[1]));

        // Round-trip a combo made entirely of new keys.
        p = new MapPrefs();
        AnimSelection.save(p, "bounce", "sparkle");
        AnimConfig nc = AnimSelection.current(p);
        assertTrue("new combo round-trip",
                "bounce".equals(nc.motionKey) && "sparkle".equals(nc.effectKey));
        assertTrue("new combo flags", nc.sparkle && !nc.flash);
    }
}
