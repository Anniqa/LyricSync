package com.lyricsync.app.renderer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM checks for the post-palette effect math (Aurora / Meteor / Ring
 * Pulse): deterministic, in-range, and monotonic where the draw code needs it.
 */
public class EffectFxTest {

    @Test
    public void hashStaysInTheUnitInterval() {
        for (int i = 0; i < 5000; i++) {
            float h = EffectFx.hash01(i * 31 + 7);
            assertTrue("hash in [0,1) at " + i, h >= 0f && h < 1f);
        }
    }

    @Test
    public void auroraHueAndBreatheAreDeterministicAndInRange() {
        for (long t = 0; t < 12000; t += 37) {
            for (int word = 0; word < 9; word++) {
                float hue = EffectFx.auroraHue(t, word);
                assertTrue("hue in [0,360) t=" + t, hue >= 0f && hue < 360f);
                assertEquals("hue deterministic",
                        EffectFx.auroraHue(t, word), hue, 0f);
                float breathe = EffectFx.auroraBreathe(t);
                assertTrue("breathe in [0.55,1] t=" + t,
                        breathe >= 0.55f && breathe <= 1f);
                assertEquals("breathe deterministic",
                        EffectFx.auroraBreathe(t), breathe, 0f);
            }
        }
        // Per-word phase shift: identical clocks, different words differ.
        assertTrue("words phase-shifted",
                EffectFx.auroraHue(1500, 0) != EffectFx.auroraHue(1500, 4));
    }

    @Test
    public void meteorStateStaysInDesignRanges() {
        float[] out = new float[5];
        for (int seed = 0; seed < 200; seed++) {
            for (int k = 0; k < 3; k++) {
                EffectFx.meteorState(seed, k, out);
                assertTrue("startX in [0.05,0.60], got " + out[0],
                        out[0] >= 0.05f && out[0] <= 0.60f);
                assertTrue("startY above the glyph top, got " + out[1],
                        out[1] <= -0.10f && out[1] >= -0.45f);
                assertTrue("dx positive, got " + out[2], out[2] >= 0.85f && out[2] <= 1.40f);
                assertTrue("dy downward, got " + out[3], out[3] >= 0.45f && out[3] <= 0.85f);
                assertTrue("streak length positive, got " + out[4],
                        out[4] >= 0.55f && out[4] <= 1.30f);
            }
        }
        // Deterministic: same seed, same state.
        float[] again = new float[5];
        EffectFx.meteorState(123, 2, out);
        EffectFx.meteorState(123, 2, again);
        for (int i = 0; i < 5; i++) {
            assertEquals("meteor state deterministic", out[i], again[i], 0f);
        }
    }

    @Test
    public void meteorLifeIsStableAndStaggered() {
        for (long t = 0; t < 8000; t += 111) {
            for (int k = 0; k < 3; k++) {
                float life = EffectFx.meteorLife(t, k);
                assertTrue("life in [0,1) t=" + t + " k=" + k, life >= 0f && life < 1f);
                assertEquals("life deterministic", EffectFx.meteorLife(t, k), life, 0f);
            }
        }
        // Staggered phases never line up: at any clock the three meteors differ.
        for (long t = 0; t < 4000; t += 211) {
            float l0 = EffectFx.meteorLife(t, 0);
            float l1 = EffectFx.meteorLife(t, 1);
            float l2 = EffectFx.meteorLife(t, 2);
            assertTrue("meteors staggered", l0 != l1 && l1 != l2 && l0 != l2);
        }
    }

    @Test
    public void ringRadiusGrowsWhileAlphaFades() {
        float prevR = -1f;
        for (int i = 0; i <= 11; i++) {
            float t = i / 20f; // 0 .. 0.55
            float r = EffectFx.ringRadius(t, 10f);
            float a = EffectFx.ringAlpha(t);
            assertTrue("radius grows monotonically", r > prevR);
            prevR = r;
            assertTrue("alpha in [0,0.55]", a >= 0f && a <= 0.55f);
            if (i >= 2) {
                // i==1 legitimately rises off the t<=0 floor (alpha 0 at t=0).
                assertTrue("alpha fades monotonically",
                        a < EffectFx.ringAlpha((i - 1) / 20f));
            }
        }
        assertEquals(0f, EffectFx.ringAlpha(0.55f), 1e-4f);
        assertEquals(0f, EffectFx.ringAlpha(1.2f), 1e-4f);
        assertEquals(0f, EffectFx.ringAlpha(-0.3f), 1e-4f);
    }
}
