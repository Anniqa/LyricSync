package com.lyricsync.app.renderer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AnimConfigSelfTest {

    private static AnimStyleDef motion(String key) {
        return AnimStyleDef.Registry.motionByKey(key);
    }

    private static AnimStyleDef effect(String key) {
        return AnimStyleDef.Registry.effectByKey(key);
    }

    @Test
    public void noneEffectIsPurePassthroughOfMotion() {
        AnimStyleDef spring = motion("spring");
        AnimStyleDef none = AnimStyleDef.Registry.none();

        AnimConfig s = AnimConfig.combine(spring, none);
        assertTrue("none: scale from motion", s.scaleSpline == spring.scaleSpline);
        assertTrue("none: glowSpline from motion", s.glowSpline == spring.glowSpline);
        assertTrue("none: glowEnabled from motion", s.glowEnabled == spring.glowEnabled);
        assertFalse("none: no effect flags", s.echo || s.shimmer || s.flicker);
        assertTrue("spring flags intact", s.springsEnabled && s.letterEmphasis && !s.wave && !s.classic);
        assertEquals("spring", s.motionKey);
        assertEquals("none", s.effectKey);
    }

    @Test
    public void effectFlagsAndGlowCombining() {
        AnimStyleDef spring = motion("spring");
        AnimStyleDef calm = motion("calm");
        AnimStyleDef wave = motion("wave");
        AnimStyleDef typewriter = motion("typewriter");
        AnimStyleDef glow = effect("glow");

        AnimConfig we = AnimConfig.combine(wave, effect("echo"));
        assertTrue("wave kept", we.wave);
        assertTrue("echo added", we.echo);
        assertFalse("wave has no springs even with echo", we.springsEnabled);
        assertTrue("echo glowSpline wins", we.glowSpline == effect("echo").glowSpline);
        assertTrue("glowEnabled OR rule", we.glowEnabled);

        // Shimmer never kills the motion's glow spline.
        AnimConfig ss = AnimConfig.combine(spring, effect("shimmer"));
        assertTrue("shimmer keeps motion glowSpline", ss.glowSpline == spring.glowSpline);
        assertTrue("shimmer flag only", ss.shimmer && !ss.echo);

        // Calm (no own glow) + shimmer: glowEnabled on, spline falls back to calm's zero spline.
        AnimConfig cs = AnimConfig.combine(calm, effect("shimmer"));
        assertTrue("calm+shimmer glowEnabled", cs.glowEnabled);
        assertTrue("calm zero glow spline", cs.glowSpline.at(0.5) == 0.0);

        // Flicker: boost from effect, held-high glow spline from effect.
        AnimConfig cf = AnimConfig.combine(calm, effect("flicker"));
        assertTrue("flicker boost", Math.abs(cf.glowBoost - 1.6f) < 1e-6);
        assertTrue("flicker held-high glow", cf.glowSpline.at(0.5) > 0.9);

        // Typewriter letters survive combination.
        AnimConfig tg = AnimConfig.combine(typewriter, glow);
        assertTrue("typewriter letters", tg.letterEmphasis && tg.binaryLetters && tg.letterMinDuration == 0);
        assertTrue("glow effect on typewriter", tg.glowEnabled && Math.abs(tg.glowBoost - 1.8f) < 1e-6);

        // Classic flag flows from motion.
        assertTrue("classic flag", AnimConfig.combine(motion("classic"), effect("echo")).classic);
    }

    @Test
    public void mechanismFlagsStayOnTheirSide() {
        AnimStyleDef none = AnimStyleDef.Registry.none();
        AnimStyleDef spin = motion("spin");
        AnimStyleDef squash = motion("squash");

        AnimConfig so = AnimConfig.combine(spin, effect("outline"));
        assertTrue("spin from motion", so.spin && !so.squash);
        assertTrue("outline from effect", so.outline && !so.trail && !so.flash && !so.sparkle);
        assertFalse("motion never sets effect flags", AnimConfig.combine(spin, none).outline);

        AnimConfig csp = AnimConfig.combine(motion("calm"), effect("sparkle"));
        assertFalse("effect never sets motion flags", csp.spin || csp.squash);
        assertTrue("sparkle flag flows", csp.sparkle);

        AnimConfig st = AnimConfig.combine(squash, effect("trail"));
        assertTrue("squash x trail", st.squash && st.trail);
    }

    @Test
    public void fireBatchAndGlowSplineInheritance() {
        AnimStyleDef spring = motion("spring");
        AnimStyleDef calm = motion("calm");
        AnimStyleDef spin = motion("spin");

        AnimConfig bf = AnimConfig.combine(spin, effect("fireworks"));
        assertTrue("fireworks from effect", bf.fireworks && !bf.welding && !bf.burning);
        assertFalse("motion never sets fireworks", AnimConfig.combine(spring, AnimStyleDef.Registry.none()).fireworks);
        assertTrue("welding flag flows", AnimConfig.combine(calm, effect("welding")).welding);
        assertTrue("burning flag flows", AnimConfig.combine(calm, effect("burning")).burning);
        assertTrue("burning keeps motion glowSpline",
                AnimConfig.combine(spring, effect("burning")).glowSpline == spring.glowSpline);

        // New effects inherit the motion's glow spline, like shimmer.
        AnimConfig sf = AnimConfig.combine(spring, effect("flash"));
        assertTrue("flash keeps motion glowSpline", sf.glowSpline == spring.glowSpline);
        assertTrue("flash enables glow (non-none effect)", sf.glowEnabled);
    }
}
