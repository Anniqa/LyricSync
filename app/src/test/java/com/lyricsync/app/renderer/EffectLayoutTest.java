package com.lyricsync.app.renderer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins the motion x effect overlay geometry: the sung-side clip edge per render path
 * (see {@link EffectLayout}) and the Shimmer band that has to stay inside it.
 */
public class EffectLayoutTest {

    private static final float EPS = 1e-4f;

    /** Sweep motions fill continuously, so the edge is the sweep clamped to the word. */
    @Test
    public void sweepEdgeIsTheClampedSweep() {
        assertEquals(0f, EffectLayout.fillEdge(0.30f, -0.20f, false, false, 0f), EPS);
        assertEquals(0f, EffectLayout.fillEdge(0.30f, -0.50f, false, false, 0f), EPS);
        assertEquals(0.40f, EffectLayout.fillEdge(0.50f, 0.40f, false, false, 0f), EPS);
        assertEquals(1f, EffectLayout.fillEdge(1.00f, 1.00f, false, false, 0f), EPS);
    }

    /**
     * TYPEWRITER strikes letters one at a time, so the visible fill ends at the struck
     * run — well behind the raw sweep mid-word.
     */
    @Test
    public void binaryStrikeEdgeTracksTheStruckRunNotTheSweep() {
        float p = 0.60f;
        assertEquals(0.25f, EffectLayout.fillEdge(0.70f, p, false, true, 0.25f), EPS);
        // The sweep must not leak through when the binary strike is the active fill.
        assertTrue(EffectLayout.fillEdge(0.70f, p, false, true, 0.25f) < p);
        assertEquals(1f, EffectLayout.fillEdge(0.70f, p, false, true, 1.20f), EPS);
        assertEquals(0f, EffectLayout.fillEdge(0.70f, p, false, true, 0f), EPS);
    }

    /** CLASSIC flips the whole word the instant it starts, then never moves again. */
    @Test
    public void classicEdgeFlipsTheWholeWord() {
        // Unstarted words are fully dim, even when the sweep value would be ahead of them.
        assertEquals(0f, EffectLayout.fillEdge(0f, 0.10f, true, false, 0f), EPS);
        assertEquals(0f, EffectLayout.fillEdge(0f, -0.10f, true, false, 0f), EPS);
        assertEquals(1f, EffectLayout.fillEdge(0.02f, -0.18f, true, false, 0f), EPS);
        assertEquals(1f, EffectLayout.fillEdge(0.90f, 0.88f, true, false, 0f), EPS);
    }

    /**
     * Regression: the Shimmer band was swept in its own 0.05..0.98 progress space while
     * the renderer clipped it to the fill edge (-0.20 + 1.20 * progress), so the band sat
     * ahead of the clip and was cut away for most of the word. The band must ride the edge
     * it is clipped to, and its whole width must fit in the clip as soon as the edge is
     * wider than the band.
     */
    @Test
    public void shimmerBandRidesTheEdgeItIsClippedTo() {
        int fullyVisible = 0;
        for (int i = 0; i <= 100; i++) {
            float progress = i / 100f;
            float p = -0.20f + 1.20f * progress;
            float edge = EffectLayout.fillEdge(progress, p, false, false, 0f);
            float center = EffectLayout.shimmerCenter(edge);
            assertTrue("band must start inside the sung region at " + progress, center <= edge + EPS);
            if (edge >= EffectLayout.SHIMMER_HALF_WIDTH) {
                assertEquals("band's trailing edge must reach the fill edge at " + progress,
                        edge, center + EffectLayout.SHIMMER_HALF_WIDTH, EPS);
                fullyVisible++;
            }
        }
        // The sweep edge is wider than the band from progress 0.217 on, so the band is
        // drawable in full for the rest of the word (79 of 101 samples).
        assertTrue("an all-word shimmer must be drawable in full", fullyVisible >= 78);
    }

    /** Any edge the renderer can produce keeps the band inside the word. */
    @Test
    public void bandStaysInsideTheWordForEveryCombination() {
        for (AnimStyleDef motion : AnimStyleDef.Registry.motionStyles()) {
            for (AnimStyleDef effect : AnimStyleDef.Registry.effectStyles()) {
                AnimConfig config = AnimConfig.combine(motion, effect);
                for (int i = 0; i <= 20; i++) {
                    float progress = i / 20f;
                    float p = -0.20f + 1.20f * progress;
                    float edge = EffectLayout.fillEdge(progress, p, config.classic,
                            config.binaryLetters, 0.3f);
                    String what = motion.key + " x " + effect.key + " @" + progress;
                    assertTrue("edge below word start: " + what, edge >= 0f);
                    assertTrue("edge past word end: " + what, edge <= 1f);
                    float center = EffectLayout.shimmerCenter(edge);
                    assertTrue("band leads the fill edge: " + what, center <= edge + EPS);
                    assertTrue("band leaves the word: " + what, center <= 1f);
                }
            }
        }
    }
}
