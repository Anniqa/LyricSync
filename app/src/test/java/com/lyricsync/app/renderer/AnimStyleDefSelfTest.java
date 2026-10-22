package com.lyricsync.app.renderer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AnimStyleDefSelfTest {

    private static AnimStyleDef motion(String key) {
        return AnimStyleDef.Registry.motionByKey(key);
    }

    private static AnimStyleDef effect(String key) {
        return AnimStyleDef.Registry.effectByKey(key);
    }

    @Test
    public void registryCountsAndLegacyKeysResolve() {
        assertEquals(18, AnimStyleDef.Registry.motionStyles().size());
        assertEquals(12, AnimStyleDef.Registry.effectStyles().size());
        assertEquals("none", AnimStyleDef.Registry.effectStyles().get(0).key);
        assertTrue("none is EFFECT", AnimStyleDef.Registry.none().category == AnimStyleDef.Category.EFFECT);

        // Every legacy LyricAnimStyle key must resolve (migration depends on it).
        String[] legacyKeys = {"spring", "bubble", "calm", "glow", "wave", "pulse", "rise",
                "typewriter", "drop", "zoom", "echo", "shimmer", "flicker", "swing", "classic", "scatter"};
        for (String k : legacyKeys) {
            boolean found = false;
            for (AnimStyleDef d : AnimStyleDef.Registry.motionStyles()) found |= k.equals(d.key);
            for (AnimStyleDef d : AnimStyleDef.Registry.effectStyles()) found |= k.equals(d.key);
            assertTrue("legacy key resolves: " + k, found);
        }
    }

    @Test
    public void categoriesAndDefaults() {
        String[] motions = {"spring", "bubble", "wave", "pulse", "rise", "typewriter",
                "drop", "zoom", "swing", "scatter", "calm", "classic"};
        for (String k : motions) {
            assertTrue(k + " is MOTION", motion(k).category == AnimStyleDef.Category.MOTION);
        }
        for (String k : new String[]{"glow", "echo", "shimmer", "flicker"}) {
            assertTrue(k + " is EFFECT", effect(k).category == AnimStyleDef.Category.EFFECT);
        }

        // New "Besar" batch styles.
        for (String k : new String[]{"bounce", "elastic", "heartbeat", "spin", "squash", "pop"}) {
            assertTrue(k + " is MOTION", motion(k).category == AnimStyleDef.Category.MOTION);
        }
        for (String k : new String[]{"outline", "trail", "flash", "sparkle", "fireworks", "welding", "burning"}) {
            assertTrue(k + " is EFFECT", effect(k).category == AnimStyleDef.Category.EFFECT);
        }

        assertEquals("spring", motion("nope").key);
        assertEquals("spring", motion(null).key);
        assertEquals("none", effect("nope").key);
        assertEquals("none", effect(null).key);
    }

    @Test
    public void mechanismFlags() {
        assertTrue("spin flag set", motion("spin").spin);
        assertFalse("spring has no spin", motion("spring").spin);
        assertTrue("squash flag set", motion("squash").squash);
        assertFalse("bounce has no squash", motion("bounce").squash);
        assertTrue("pop reuses scatter", motion("pop").scatter);
        assertTrue("outline flag", effect("outline").outline);
        assertTrue("trail flag", effect("trail").trail);
        assertTrue("flash flag", effect("flash").flash);
        assertTrue("sparkle flag", effect("sparkle").sparkle);
        assertFalse("glow has no sparkle", effect("glow").sparkle);
        assertTrue("fireworks flag", effect("fireworks").fireworks);
        assertTrue("welding flag", effect("welding").welding);
        assertTrue("burning flag", effect("burning").burning);
        assertFalse("sparkle has no fireworks", effect("sparkle").fireworks);
        assertFalse("fireworks has no sparkle", effect("fireworks").sparkle);

        // New effects define no glow spline: they inherit the motion's, like shimmer.
        for (String k : new String[]{"outline", "trail", "flash", "sparkle", "fireworks", "welding", "burning"}) {
            assertNull(k + " glowSpline null (inherits motion)", effect(k).glowSpline);
        }

        // Nullable glowSpline: shimmer + none inherit; glow/echo/flicker define their own.
        assertNull("shimmer glowSpline null", effect("shimmer").glowSpline);
        assertNull("none glowSpline null", AnimStyleDef.Registry.none().glowSpline);
        assertTrue("glow glowSpline set", effect("glow").glowSpline != null);
        assertTrue("echo glowSpline set", effect("echo").glowSpline != null);
        assertTrue("flicker glowSpline set", effect("flicker").glowSpline != null);
    }

    @Test
    public void splineAndLegacyValueSpotChecks() {
        assertTrue("bounce starts -0.50",
                Math.abs(motion("bounce").yOffsetSpline.at(0.0) - (-0.50)) < 1e-6);
        assertTrue("heartbeat first beat",
                Math.abs(motion("heartbeat").scaleSpline.at(0.12) - 1.08) < 0.02);
        assertTrue("heartbeat second beat",
                Math.abs(motion("heartbeat").scaleSpline.at(0.36) - 1.08) < 0.02);
        assertTrue("elastic starts 0.20",
                Math.abs(motion("elastic").letterScaleSpline.at(0.0) - 0.20) < 1e-6);
        assertTrue("pop starts at 0", Math.abs(motion("pop").letterScaleSpline.at(0.0)) < 1e-6);

        // Spot-check legacy values survived the move.
        assertTrue("spring scale peak ~1.10", Math.abs(motion("spring").scaleSpline.at(0.65) - 1.10) < 0.02);
        assertTrue("drop starts at -0.30", Math.abs(motion("drop").yOffsetSpline.at(0.0) - (-0.30)) < 1e-6);
        assertEquals(0, motion("typewriter").letterMinDuration);
        assertEquals(1000, motion("spring").letterMinDuration);
        assertTrue("flicker boost", Math.abs(effect("flicker").glowBoost - 1.6f) < 1e-6);
        assertTrue("glow boost", Math.abs(effect("glow").glowBoost - 1.8f) < 1e-6);
    }
}
