package com.lyricsync.app.renderer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Hologram, Sakura and the combination effects: registry wiring and bounded, seek-safe motion. */
public class NewEffectsTest {

    private static AnimStyleDef effect(String key) {
        return AnimStyleDef.Registry.effectByKey(key);
    }

    @Test
    public void comboEffectsLayerTheirParts() {
        AnimStyleDef nova = effect("supernova"), cosmic = effect("cosmic"), inferno = effect("inferno"), cyber = effect("cyberpunk");
        assertTrue(nova.neon && nova.ringPulse && nova.fireworks);
        assertTrue(cosmic.aurora && cosmic.meteor && cosmic.sparkle);
        assertTrue(inferno.burning && inferno.welding && inferno.ringPulse);
        assertTrue(cyber.hologram && cyber.flicker && cyber.trail);
        for (AnimStyleDef e : new AnimStyleDef[]{nova, cosmic, inferno, cyber}) assertTrue(e.key, e.isCombo());
        assertFalse(effect("hologram").isCombo());
        assertFalse(effect("sakura").isCombo());
        AnimConfig c = AnimConfig.combine(AnimStyleDef.Registry.motionByKey("spring"), cosmic);
        assertTrue("combo flags reach the renderer", c.aurora && c.meteor && c.sparkle);
        assertTrue("after-word effects keep their overflow", c.drawsPastWordEnd());
        assertFalse(AnimConfig.combine(AnimStyleDef.Registry.motionByKey("spring"), effect("echo")).drawsPastWordEnd());
    }

    @Test
    public void sakuraPetalsStayNearTheWordAndFadeAtBothEnds() {
        float[] pose = new float[5];
        for (int seed = 0; seed < 50; seed++) {
            for (int i = 0; i <= 100; i++) {
                EffectFx.sakuraPose(seed, i / 100f, pose);
                assertTrue("x stays near the sung run", pose[0] > -0.3f && pose[0] < 1.3f);
                assertTrue("falls from above to below", pose[1] >= -0.86f && pose[1] <= 0.91f);
                assertTrue(pose[3] >= 0f && pose[3] <= 1f);
                assertTrue(pose[4] >= 0f && pose[4] <= 1f);
            }
            EffectFx.sakuraPose(seed, 0f, pose);
            assertEquals("no pop-in", 0f, pose[4], 1e-6);
            EffectFx.sakuraPose(seed, 1f, pose);
            assertEquals("no pop-out", 0f, pose[4], 1e-6);
        }
        assertEquals("same clock, same petal", EffectFx.sakuraLife(12345, 3), EffectFx.sakuraLife(12345, 3), 0f);
        assertTrue(EffectFx.sakuraCycle(5000, 0) != EffectFx.sakuraCycle(5000 + 2300, 0));
    }

    @Test
    public void hologramIsBoundedAndMostlyStable() {
        int glitches = 0;
        for (long t = 0; t < 90_000; t += 90) {
            float g = EffectFx.holoGlitch(t, 2);
            assertTrue(g >= 0f && g <= 1f);
            if (g > 0f) glitches++;
            float hue = EffectFx.holoHue(t, 2);
            assertTrue("cyan..violet", hue >= 185f - 1e-3 && hue <= 275f + 1e-3);
            float off = EffectFx.holoScanOffset(t);
            assertTrue(off >= 0f && off < EffectFx.HOLO_LINE_GAP + 1e-6);
        }
        assertTrue("glitches are occasional: " + glitches, glitches > 20 && glitches < 300);
    }
}
