package com.lyricsync.app.renderer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM checks for the motion signature math: every move is bounded and
 * returns EXACTLY to rest, so no signature can leave a word displaced, tilted
 * or scaled once it has finished.
 */
public class MotionFxTest {

    private static final float EPS = 1e-5f;

    @Test
    public void everyMotionHasItsOwnSignatureOrMechanic() {
        // Unique-ness: a motion either owns a signature or a dedicated mechanic flag.
        java.util.Set<AnimStyleDef.Signature> seen = new java.util.HashSet<>();
        for (AnimStyleDef m : AnimStyleDef.Registry.motionStyles()) {
            boolean mechanic = m.wave || m.swing || m.spin || m.squash;
            boolean calm = "calm".equals(m.key);
            assertTrue(m.key + " has a signature or mechanic",
                    calm || mechanic || m.signature != AnimStyleDef.Signature.NONE);
            if (m.signature != AnimStyleDef.Signature.NONE) {
                assertTrue("signature reused: " + m.signature, seen.add(m.signature));
            }
        }
        for (AnimStyleDef e : AnimStyleDef.Registry.effectStyles()) {
            assertEquals("effects carry no motion signature",
                    AnimStyleDef.Signature.NONE, e.signature);
        }
        assertTrue("glow is neon", AnimStyleDef.Registry.effectByKey("glow").neon);
        assertEquals(AnimStyleDef.Signature.BALL,
                AnimConfig.combine(AnimStyleDef.Registry.motionByKey("classic"),
                        AnimStyleDef.Registry.none()).signature);
    }

    @Test
    public void heartbeatIsBoundedAndLoops() {
        float peak = 0f;
        for (int i = 0; i <= 2000; i++) {
            float t = i / 1000f;
            float b = MotionFx.heartbeat(t);
            assertTrue("beat in range at " + t, b >= 0f && b <= 0.08f);
            peak = Math.max(peak, b);
            assertEquals("loops every period", b,
                    MotionFx.heartbeat(t + MotionFx.HEART_PERIOD), 2e-3f);
        }
        assertTrue("beat is visible", peak > 0.06f);
        assertEquals(0f, MotionFx.heartbeat(0f), 0f);
        assertEquals(0f, MotionFx.heartbeat(-1f), 0f);
    }

    @Test
    public void jellySettlesExactlyAndStartsSquashed() {
        float[] o = new float[2];
        MotionFx.jelly(0.001f, o);
        assertTrue("starts wide", o[0] > 1.05f);
        assertTrue("starts short", o[1] < 0.9f);
        for (int i = 0; i <= 200; i++) {
            MotionFx.jelly(i / 100f, o);
            assertTrue("sx bounded", o[0] > 0.85f && o[0] < 1.15f);
            assertTrue("sy bounded", o[1] > 0.8f && o[1] < 1.2f);
        }
        MotionFx.jelly(MotionFx.JELLY_LIFE, o);
        assertEquals(1f, o[0], 0f);
        assertEquals(1f, o[1], 0f);
        MotionFx.jelly(MotionFx.JELLY_LIFE - 0.001f, o);
        assertEquals("no step at the end", 1f, o[1], 0.01f);
        MotionFx.jelly(-0.2f, o);
        assertEquals(1f, o[0], 0f);
    }

    @Test
    public void impactSquashOnlyBelowRest() {
        float[] o = new float[2];
        MotionFx.impactSquash(-0.3f, o);
        assertEquals(1f, o[0], 0f);
        assertEquals(1f, o[1], 0f);
        MotionFx.impactSquash(0.03f, o);
        assertTrue("flattens", o[1] < 1f && o[0] > 1f);
        MotionFx.impactSquash(5f, o);
        assertTrue("capped", o[1] >= 0.8f);
        assertEquals(1f, MotionFx.fallStretch(0.1f, 3f), 0f);
        assertTrue(MotionFx.fallStretch(-0.2f, 3f) > 1f);
        assertTrue(MotionFx.fallStretch(-0.2f, 1000f) <= 1.08f + EPS);
    }

    @Test
    public void flipIsOneTurnAndRestsFaceOn() {
        assertEquals(0f, MotionFx.flipDegrees(0f), 0f);
        assertEquals(0f, MotionFx.flipDegrees(MotionFx.FLIP_LIFE), 0f);
        assertEquals(0f, MotionFx.flipDegrees(3f), 0f);
        float prev = 0f;
        for (int i = 1; i < 100; i++) {
            float d = MotionFx.flipDegrees(MotionFx.FLIP_LIFE * i / 100f);
            assertTrue("monotonic", d >= prev);
            assertTrue("one turn", d < 360f);
            prev = d;
        }
        assertTrue("near a full turn at the end", prev > 359f);
    }

    @Test
    public void explodeStartsScatteredAndLandsHome() {
        float[] o = new float[3];
        MotionFx.explode(42, 3, 0.001f, o);
        assertTrue("starts away from home", Math.hypot(o[0], o[1]) > 0.8);
        for (float u : new float[]{0f, 1f, 1.5f, -0.2f}) {
            MotionFx.explode(42, 3, u, o);
            assertEquals(0f, o[0], 0f);
            assertEquals(0f, o[1], 0f);
            assertEquals(0f, o[2], 0f);
        }
        MotionFx.explode(42, 3, 0.999f, o);
        assertEquals("continuous at landing", 0f, (float) Math.hypot(o[0], o[1]), 0.01f);
        for (int i = 1; i < 100; i++) {
            MotionFx.explode(7, i % 5, i / 100f, o);
            assertTrue("bounded flight", Math.hypot(o[0], o[1]) < 2.2);
            assertTrue("bounded spin", Math.abs(o[2]) <= 71f);
        }
    }

    @Test
    public void rubberPreservesVolumeAndRest() {
        float[] o = new float[2];
        MotionFx.rubber(1f, o);
        assertEquals(1f, o[0], EPS);
        assertEquals(1f, o[1], EPS);
        MotionFx.rubber(1.15f, o);
        assertTrue("overshoot stretches tall", o[1] > o[0]);
        assertEquals("area ~ s^2", 1.15f * 1.15f, o[0] * o[1], 1e-3f);
        MotionFx.rubber(0.8f, o);
        assertTrue("undershoot squashes wide", o[0] > o[1]);
    }

    @Test
    public void scatterTiltBoundedAndZeroAtRest() {
        for (int i = 0; i < 40; i++) {
            assertEquals(0f, MotionFx.scatterTilt(i, 0f), 0f);
            float t = MotionFx.scatterTilt(i, 0.3f);
            assertTrue(Math.abs(t) <= 16f);
        }
        assertNotEquals(MotionFx.scatterTilt(1, 0.3f), MotionFx.scatterTilt(2, 0.3f), 0f);
    }

    @Test
    public void floatBobAndSurfTiltBounded() {
        for (long t = 0; t < 20000; t += 37) {
            for (int w = 0; w < 8; w++) {
                assertTrue(Math.abs(MotionFx.floatBob(t, w)) <= 0.0221f);
            }
            assertTrue(Math.abs(MotionFx.surfTilt(t * 0.0075, 1.0)) <= 6.0001f);
        }
        assertEquals(0f, MotionFx.surfTilt(1.2, 0.0), 0f);
    }

    @Test
    public void ballLandsOnWordEdges() {
        assertEquals(0f, MotionFx.ballHeight(0f), 0f);
        assertEquals(0f, MotionFx.ballHeight(1f), 0f);
        assertEquals(1f, MotionFx.ballHeight(0.5f), EPS);
        assertEquals(1f, MotionFx.ballSquash(0.5f), 0f);
        assertTrue(MotionFx.ballSquash(0.01f) < 0.8f);
        assertTrue(MotionFx.ballSquash(0.99f) < 0.8f);
    }

    @Test
    public void caretSolidWhileTypingThenBlinks() {
        assertEquals(1f, MotionFx.caretAlpha(123, true), 0f);
        boolean on = false;
        boolean off = false;
        for (long t = 0; t < 1060; t += 10) {
            float a = MotionFx.caretAlpha(t, false);
            assertTrue(a >= 0f && a <= 1f);
            on |= a > 0.99f;
            off |= a < 0.01f;
        }
        assertTrue("blinks on and off", on && off);
    }

    @Test
    public void ghostsVanishAtRest() {
        assertEquals(0f, MotionFx.chromaSplit(1f), 0f);
        assertEquals(0f, MotionFx.chromaSplit(0.9f), 0f);
        assertTrue(MotionFx.chromaSplit(1.12f) > 0.05f);
        assertTrue(MotionFx.chromaSplit(3f) <= 0.09f);
        assertEquals(0f, MotionFx.zoomBlur(1f), 0f);
        assertEquals(1f, MotionFx.zoomBlur(1.16f), 1e-4f);
        assertEquals(0f, MotionFx.reflection(0f), 0f);
        assertEquals(0f, MotionFx.reflection(1f), 0f);
        assertEquals(0.30f, MotionFx.reflection(0.5f), EPS);
    }

    @Test
    public void dropletsArcUpThenFall() {
        float[] o = new float[2];
        MotionFx.droplet(9, 0, 0.1f, o);
        assertTrue("rises first", o[1] < 0f);
        assertTrue("left droplet goes left", o[0] < 0f);
        MotionFx.droplet(9, 1, 0.1f, o);
        assertTrue("right droplet goes right", o[0] > 0f);
        MotionFx.droplet(9, 0, 0f, o);
        assertEquals(0f, o[0], 0f);
        assertEquals(0f, o[1], 0f);
    }
}
