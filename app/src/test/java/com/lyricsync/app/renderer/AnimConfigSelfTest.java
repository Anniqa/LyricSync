package com.lyricsync.app.renderer;

public class AnimConfigSelfTest {
    public static void main(String[] args) {
        AnimStyleDef spring = AnimStyleDef.Registry.motionByKey("spring");
        AnimStyleDef calm = AnimStyleDef.Registry.motionByKey("calm");
        AnimStyleDef wave = AnimStyleDef.Registry.motionByKey("wave");
        AnimStyleDef typewriter = AnimStyleDef.Registry.motionByKey("typewriter");
        AnimStyleDef none = AnimStyleDef.Registry.none();
        AnimStyleDef glow = AnimStyleDef.Registry.effectByKey("glow");
        AnimStyleDef echo = AnimStyleDef.Registry.effectByKey("echo");
        AnimStyleDef shimmer = AnimStyleDef.Registry.effectByKey("shimmer");
        AnimStyleDef flicker = AnimStyleDef.Registry.effectByKey("flicker");

        // none effect = pure passthrough of the motion style.
        AnimConfig s = AnimConfig.combine(spring, none);
        check(s.scaleSpline == spring.scaleSpline, "none: scale from motion");
        check(s.glowSpline == spring.glowSpline, "none: glowSpline from motion");
        check(s.glowEnabled == spring.glowEnabled, "none: glowEnabled from motion");
        check(!s.echo && !s.shimmer && !s.flicker, "none: no effect flags");
        check(s.springsEnabled && s.letterEmphasis && !s.wave && !s.classic, "spring flags intact");
        check("spring".equals(s.motionKey) && "none".equals(s.effectKey), "keys recorded");

        // Effect flags come from the effect; motion fields from the motion.
        AnimConfig we = AnimConfig.combine(wave, echo);
        check(we.wave, "wave kept");
        check(we.echo, "echo added");
        check(!we.springsEnabled, "wave has no springs even with echo");
        check(we.glowSpline == echo.glowSpline, "echo glowSpline wins");
        check(we.glowEnabled, "glowEnabled OR rule");

        // Shimmer never kills the motion's glow spline.
        AnimConfig ss = AnimConfig.combine(spring, shimmer);
        check(ss.glowSpline == spring.glowSpline, "shimmer keeps motion glowSpline");
        check(ss.shimmer && !ss.echo, "shimmer flag only");

        // Calm (no own glow) + shimmer: glowEnabled on, spline falls back to calm's zero spline.
        AnimConfig cs = AnimConfig.combine(calm, shimmer);
        check(cs.glowEnabled, "calm+shimmer glowEnabled");
        check(cs.glowSpline.at(0.5) == 0.0, "calm zero glow spline");

        // Flicker: boost from effect, held-high glow spline from effect.
        AnimConfig cf = AnimConfig.combine(calm, flicker);
        check(Math.abs(cf.glowBoost - 1.6f) < 1e-6, "flicker boost");
        check(cf.glowSpline.at(0.5) > 0.9, "flicker held-high glow");

        // Typewriter letters survive combination.
        AnimConfig tg = AnimConfig.combine(typewriter, glow);
        check(tg.letterEmphasis && tg.binaryLetters && tg.letterMinDuration == 0, "typewriter letters");
        check(tg.glowEnabled && Math.abs(tg.glowBoost - 1.8f) < 1e-6, "glow effect on typewriter");

        // Classic flag flows from motion.
        check(AnimConfig.combine(AnimStyleDef.Registry.motionByKey("classic"), echo).classic,
                "classic flag");

        // "Besar" batch: motion-only flags never come from effects and vice versa.
        AnimStyleDef spin = AnimStyleDef.Registry.motionByKey("spin");
        AnimStyleDef squash = AnimStyleDef.Registry.motionByKey("squash");
        AnimStyleDef outline = AnimStyleDef.Registry.effectByKey("outline");
        AnimStyleDef trail = AnimStyleDef.Registry.effectByKey("trail");
        AnimStyleDef flash = AnimStyleDef.Registry.effectByKey("flash");
        AnimStyleDef sparkle = AnimStyleDef.Registry.effectByKey("sparkle");

        AnimConfig so = AnimConfig.combine(spin, outline);
        check(so.spin && !so.squash, "spin from motion");
        check(so.outline && !so.trail && !so.flash && !so.sparkle, "outline from effect");
        check(!AnimConfig.combine(spin, none).outline, "motion never sets effect flags");
        AnimConfig csp = AnimConfig.combine(calm, sparkle);
        check(!csp.spin && !csp.squash, "effect never sets motion flags");
        check(csp.sparkle, "sparkle flag flows");
        AnimConfig st = AnimConfig.combine(squash, trail);
        check(st.squash && st.trail, "squash x trail");

        // Fire batch: the three fire effects flow from effect only.
        AnimStyleDef fireworks = AnimStyleDef.Registry.effectByKey("fireworks");
        AnimStyleDef welding = AnimStyleDef.Registry.effectByKey("welding");
        AnimStyleDef burning = AnimStyleDef.Registry.effectByKey("burning");
        AnimConfig bf = AnimConfig.combine(spin, fireworks);
        check(bf.fireworks && !bf.welding && !bf.burning, "fireworks from effect");
        check(!AnimConfig.combine(spin, none).fireworks, "motion never sets fireworks");
        check(AnimConfig.combine(calm, welding).welding, "welding flag flows");
        check(AnimConfig.combine(calm, burning).burning, "burning flag flows");
        check(AnimConfig.combine(spring, burning).glowSpline == spring.glowSpline,
                "burning keeps motion glowSpline");

        // New effects inherit the motion's glow spline, like shimmer.
        AnimConfig sf = AnimConfig.combine(spring, flash);
        check(sf.glowSpline == spring.glowSpline, "flash keeps motion glowSpline");
        check(sf.glowEnabled, "flash enables glow (non-none effect)");

        System.out.println("AnimConfigSelfTest OK");
    }

    private static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
    }
}
