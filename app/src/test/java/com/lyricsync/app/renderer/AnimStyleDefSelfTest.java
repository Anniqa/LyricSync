package com.lyricsync.app.renderer;

public class AnimStyleDefSelfTest {
    public static void main(String[] args) {
        check(AnimStyleDef.Registry.motionStyles().size() == 12, "12 motion styles");
        check(AnimStyleDef.Registry.effectStyles().size() == 5, "5 effect styles");
        check("none".equals(AnimStyleDef.Registry.effectStyles().get(0).key), "first effect is none");
        check(AnimStyleDef.Registry.none().category == AnimStyleDef.Category.EFFECT, "none is EFFECT");

        // Every legacy LyricAnimStyle key must resolve (migration depends on it).
        String[] legacyKeys = {"spring","bubble","calm","glow","wave","pulse","rise",
                "typewriter","drop","zoom","echo","shimmer","flicker","swing","classic","scatter"};
        for (String k : legacyKeys) {
            boolean found = false;
            for (AnimStyleDef d : AnimStyleDef.Registry.motionStyles()) found |= k.equals(d.key);
            for (AnimStyleDef d : AnimStyleDef.Registry.effectStyles()) found |= k.equals(d.key);
            check(found, "legacy key resolves: " + k);
        }

        check(AnimStyleDef.Registry.motionByKey("nope").key.equals("spring"), "motion default spring");
        check(AnimStyleDef.Registry.motionByKey(null).key.equals("spring"), "motion null default");
        check(AnimStyleDef.Registry.effectByKey("nope").key.equals("none"), "effect default none");
        check(AnimStyleDef.Registry.effectByKey(null).key.equals("none"), "effect null default");

        // Category membership per spec.
        String[] motions = {"spring","bubble","wave","pulse","rise","typewriter",
                "drop","zoom","swing","scatter","calm","classic"};
        for (String k : motions) {
            check(AnimStyleDef.Registry.motionByKey(k).category == AnimStyleDef.Category.MOTION,
                    k + " is MOTION");
        }
        String[] effects = {"glow","echo","shimmer","flicker"};
        for (String k : effects) {
            check(AnimStyleDef.Registry.effectByKey(k).category == AnimStyleDef.Category.EFFECT,
                    k + " is EFFECT");
        }

        // Nullable glowSpline: shimmer + none inherit; glow/echo/flicker define their own.
        check(AnimStyleDef.Registry.effectByKey("shimmer").glowSpline == null, "shimmer glowSpline null");
        check(AnimStyleDef.Registry.none().glowSpline == null, "none glowSpline null");
        check(AnimStyleDef.Registry.effectByKey("glow").glowSpline != null, "glow glowSpline set");
        check(AnimStyleDef.Registry.effectByKey("echo").glowSpline != null, "echo glowSpline set");
        check(AnimStyleDef.Registry.effectByKey("flicker").glowSpline != null, "flicker glowSpline set");

        // Spot-check legacy values survived the move.
        AnimStyleDef spring = AnimStyleDef.Registry.motionByKey("spring");
        check(Math.abs(spring.scaleSpline.at(0.65) - 1.10) < 0.02, "spring scale peak ~1.10");
        AnimStyleDef drop = AnimStyleDef.Registry.motionByKey("drop");
        check(Math.abs(drop.yOffsetSpline.at(0.0) - (-0.30)) < 1e-6, "drop starts at -0.30");
        check(AnimStyleDef.Registry.motionByKey("typewriter").letterMinDuration == 0, "typewriter minDur 0");
        check(AnimStyleDef.Registry.motionByKey("spring").letterMinDuration == 1000, "spring minDur 1000");
        check(Math.abs(AnimStyleDef.Registry.effectByKey("flicker").glowBoost - 1.6f) < 1e-6, "flicker boost");
        check(Math.abs(AnimStyleDef.Registry.effectByKey("glow").glowBoost - 1.8f) < 1e-6, "glow boost");

        System.out.println("AnimStyleDefSelfTest OK");
    }

    private static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
    }
}
