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
            for (int k = 0; k < EffectFx.METEOR_COUNT; k++) {
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
        int n = EffectFx.METEOR_COUNT;
        for (long t = 0; t < 8000; t += 111) {
            for (int k = 0; k < n; k++) {
                float life = EffectFx.meteorLife(t, k);
                assertTrue("life in [0,1) t=" + t + " k=" + k, life >= 0f && life < 1f);
                assertEquals("life deterministic", EffectFx.meteorLife(t, k), life, 0f);
            }
        }
        // Staggered phases never line up: at any clock every meteor differs.
        for (long t = 0; t < 4000; t += 211) {
            for (int a = 0; a < n; a++) {
                for (int b = a + 1; b < n; b++) {
                    assertTrue("meteors staggered",
                            EffectFx.meteorLife(t, a) != EffectFx.meteorLife(t, b));
                }
            }
        }
    }

    @Test
    public void meteorCycleAdvancesExactlyWhenLifeWraps() {
        for (int k = 0; k < EffectFx.METEOR_COUNT; k++) {
            float prevLife = EffectFx.meteorLife(0, k);
            int prevCycle = EffectFx.meteorCycle(0, k);
            for (long t = 1; t < 6000; t++) {
                float life = EffectFx.meteorLife(t, k);
                int cycle = EffectFx.meteorCycle(t, k);
                boolean wrapped = life < prevLife;
                assertEquals("cycle steps on wrap only (k=" + k + " t=" + t + ")",
                        prevCycle + (wrapped ? 1 : 0), cycle);
                prevLife = life;
                prevCycle = cycle;
            }
        }
        // A new cycle re-rolls the path.
        float[] a = new float[5];
        float[] b = new float[5];
        EffectFx.meteorState(EffectFx.meteorSeed(40, 1, 3), 1, a);
        EffectFx.meteorState(EffectFx.meteorSeed(40, 1, 4), 1, b);
        assertTrue("fresh path per cycle", a[0] != b[0] || a[1] != b[1]);
    }

    @Test
    public void meteorAlphaFadesInAndOutWithoutPopping() {
        assertEquals(0f, EffectFx.meteorAlpha(0f), 0f);
        assertEquals(0f, EffectFx.meteorAlpha(1f), 0f);
        // No hard pop at the wrap: both ends of the life are near zero.
        assertTrue(EffectFx.meteorAlpha(0.005f) < 0.06f);
        assertTrue(EffectFx.meteorAlpha(0.995f) < 0.02f);
        float peak = 0f;
        for (int i = 1; i < 100; i++) {
            float a = EffectFx.meteorAlpha(i / 100f);
            assertTrue("alpha in [0,1]", a >= 0f && a <= 1f);
            peak = Math.max(peak, a);
        }
        assertTrue("meteor gets bright", peak > 0.8f);
    }

    @Test
    public void envelopeRampsHoldsAndFades() {
        assertEquals(0f, EffectFx.envelope(-10, 0, 1000, 100f, 400f), 0f);
        assertEquals(0.5f, EffectFx.envelope(50, 0, 1000, 100f, 400f), 1e-4f);
        assertEquals(1f, EffectFx.envelope(500, 0, 1000, 100f, 400f), 0f);
        assertEquals(0.5f, EffectFx.envelope(1200, 0, 1000, 100f, 400f), 1e-4f);
        assertEquals(0f, EffectFx.envelope(1400, 0, 1000, 100f, 400f), 0f);
        assertEquals(0f, EffectFx.envelope(99999, 0, 1000, 100f, 400f), 0f);
    }

    @Test
    public void auroraStopsStayInTheSkyBandAndCurtainsInRange() {
        for (long t = 0; t < 10000; t += 53) {
            for (int w = 0; w < 7; w++) {
                for (int s = 0; s < EffectFx.AURORA_STOPS; s++) {
                    float h = EffectFx.auroraStopHue(t, w, s);
                    assertTrue("stop hue in aurora band, got " + h, h >= 155f && h <= 335f);
                }
                float flow = EffectFx.auroraFlow(t, w);
                assertTrue("flow in [0,1)", flow >= 0f && flow < 1f);
                for (int r = 0; r < EffectFx.AURORA_RIBBONS; r++) {
                    float c = EffectFx.auroraRibbon((t % 100) / 100f, t, w, r);
                    assertTrue("ribbon in [0,1]", c >= 0f && c <= 1f);
                }
            }
        }
        // Several distinct colours at once, not one flat tint.
        assertTrue(EffectFx.auroraStopHue(700, 0, 0) != EffectFx.auroraStopHue(700, 0, 2));
    }

    @Test
    public void hsvToRgbMatchesKnownColours() {
        assertEquals(0xFF0000, EffectFx.hsvToRgb(0f, 1f, 1f));
        assertEquals(0x00FF00, EffectFx.hsvToRgb(120f, 1f, 1f));
        assertEquals(0x0000FF, EffectFx.hsvToRgb(240f, 1f, 1f));
        assertEquals(0xFFFFFF, EffectFx.hsvToRgb(200f, 0f, 1f));
        assertEquals(0x000000, EffectFx.hsvToRgb(77f, 1f, 0f));
        assertEquals(0xFF0000, EffectFx.hsvToRgb(360f, 1f, 1f));
        assertEquals(0xFF00FF, EffectFx.hsvToRgb(-60f, 1f, 1f));
    }

    @Test
    public void ringsStaggerInsideTheWindowAndSparksLead() {
        float last = EffectFx.ringLocal(0f, EffectFx.RING_COUNT - 1);
        assertTrue("later rings start after the word end", last < 0f);
        assertEquals("last ring ends exactly at the window",
                EffectFx.RING_LIFE,
                EffectFx.ringLocal(EffectFx.RING_WINDOW, EffectFx.RING_COUNT - 1), 1e-5f);
        float prev = -1f;
        for (int i = 0; i <= 20; i++) {
            float t = EffectFx.RING_WINDOW * i / 20f;
            float d = EffectFx.sparkDistance(t, 10f);
            assertTrue("sparks move outward", d > prev || (i > 0 && d == prev && d == 31f));
            prev = d;
            float a = EffectFx.sparkAlpha(t);
            assertTrue("spark alpha in [0,1]", a >= 0f && a <= 1f);
        }
        assertEquals(0f, EffectFx.sparkAlpha(EffectFx.RING_WINDOW), 1e-5f);
        assertEquals(0f, EffectFx.sparkAlpha(0f), 0f);
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
