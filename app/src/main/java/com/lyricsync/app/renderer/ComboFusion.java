package com.lyricsync.app.renderer;

import java.util.Locale;

/**
 * The "fusion" view of a motion × effect pair for the Combo Lab: an element for each
 * side, a fused name, a rarity tier and a power score. Pure JVM, deterministic (the
 * same pair always fuses into the same result) and derived from the style data, so a
 * new style is classified automatically.
 */
public final class ComboFusion {
    private ComboFusion() {}

    /** Elements, like a creature type. Colours are opaque ARGB for the UI. */
    public enum Element {
        FIRE("Fire", 0xFFFF7043), WATER("Water", 0xFF4FC3F7), ELECTRIC("Electric", 0xFFFFD54F),
        COSMIC("Cosmic", 0xFFB388FF), NATURE("Nature", 0xFF81C784), SPIRIT("Spirit", 0xFFF48FB1),
        NEON("Neon", 0xFF18FFFF), STEEL("Steel", 0xFFB0BEC5), NORMAL("Normal", 0xFFE0E0E0);

        public final String label;
        public final int color;

        Element(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    public enum Rarity {
        COMMON(1, "Common", 0xFFB0BEC5), RARE(2, "Rare", 0xFF4FC3F7), EPIC(3, "Epic", 0xFFB388FF),
        LEGENDARY(4, "Legendary", 0xFFFFD54F), MYTHIC(5, "Mythic", 0xFFFF4FC3);

        public final int stars;
        public final String label;
        public final int color;

        Rarity(int stars, String label, int color) {
            this.stars = stars;
            this.label = label;
            this.color = color;
        }
    }

    public static final class Result {
        public final String name;
        public final Element motionElement, effectElement;
        public final Rarity rarity;
        /** 0..100, for the power bar. */
        public final int power;
        /** Matching elements or a known synergy: the fusion gets a bonus. */
        public final boolean synergy;

        Result(String name, Element m, Element e, Rarity rarity, int power, boolean synergy) {
            this.name = name;
            this.motionElement = m;
            this.effectElement = e;
            this.rarity = rarity;
            this.power = power;
            this.synergy = synergy;
        }
    }

    public static Element element(AnimStyleDef d) {
        if (d == null || d == AnimStyleDef.Registry.none()) return Element.NORMAL;
        if (d.category == AnimStyleDef.Category.EFFECT) {
            if (d.burning || d.welding || d.fireworks) return Element.FIRE;
            if (d.aurora || d.meteor) return Element.COSMIC;
            if (d.hologram || d.neon || d.flicker) return Element.NEON;
            if (d.sakura) return Element.NATURE;
            if (d.ringPulse || d.flash) return Element.ELECTRIC;
            if (d.sparkle || d.shimmer) return Element.SPIRIT;
            if (d.echo || d.trail) return Element.WATER;
            if (d.outline) return Element.STEEL;
            return Element.NORMAL;
        }
        switch (d.key) {
            case "bubble": case "wave": case "drop": case "rise": return Element.WATER;
            case "pulse": case "heartbeat": case "pop": return Element.ELECTRIC;
            case "spin": case "zoom": case "scatter": return Element.COSMIC;
            case "swing": case "calm": return Element.NATURE;
            case "elastic": case "spring": case "squash": case "bounce": return Element.SPIRIT;
            case "typewriter": case "classic": return Element.STEEL;
            default: return Element.NORMAL;
        }
    }

    /** Overlays the effect layers; combos count every layer. */
    static int layers(AnimStyleDef e) {
        int n = 0;
        for (boolean f : new boolean[]{e.echo, e.shimmer, e.flicker, e.outline, e.trail, e.flash, e.sparkle,
                e.fireworks, e.welding, e.burning, e.aurora, e.meteor, e.ringPulse, e.neon, e.hologram, e.sakura}) {
            if (f) n++;
        }
        return n;
    }

    /** How much the motion does on its own: letter work, signature, mechanics. */
    static int motionWeight(AnimStyleDef m) {
        int w = 1;
        if (m.letterEmphasis) w++;
        if (m.signature != AnimStyleDef.Signature.NONE) w++;
        if (m.wave || m.spin || m.squash || m.scatter || m.swing) w++;
        return w;
    }

    private static boolean synergy(Element a, Element b) {
        if (a == b && a != Element.NORMAL) return true;
        return pair(a, b, Element.WATER, Element.ELECTRIC) || pair(a, b, Element.FIRE, Element.ELECTRIC)
                || pair(a, b, Element.COSMIC, Element.SPIRIT) || pair(a, b, Element.NATURE, Element.SPIRIT)
                || pair(a, b, Element.NEON, Element.ELECTRIC) || pair(a, b, Element.STEEL, Element.FIRE);
    }

    private static boolean pair(Element a, Element b, Element x, Element y) {
        return a == x && b == y || a == y && b == x;
    }

    public static Result fuse(AnimStyleDef motion, AnimStyleDef effect) {
        Element me = element(motion), ee = element(effect);
        boolean syn = synergy(me, ee);
        int layers = effect == AnimStyleDef.Registry.none() ? 0 : layers(effect);
        int power = 18 + motionWeight(motion) * 9 + layers * 16 + (syn ? 14 : 0) + spread(motion.key, effect.key);
        power = Math.max(5, Math.min(100, power));
        Rarity rarity = power >= 92 ? Rarity.MYTHIC : power >= 78 ? Rarity.LEGENDARY : power >= 62 ? Rarity.EPIC
                : power >= 44 ? Rarity.RARE : Rarity.COMMON;
        return new Result(name(motion, effect), me, ee, rarity, power, syn);
    }

    /** Small stable per-pair spread so equal-weight pairs still differ (0..7). */
    private static int spread(String a, String b) {
        int h = (a + "×" + b).hashCode();
        return Math.floorMod(h, 8);
    }

    /** "Spring" × "Burning" → "Spring Burn"; with no effect the motion's own name. */
    public static String name(AnimStyleDef motion, AnimStyleDef effect) {
        if (effect == null || effect == AnimStyleDef.Registry.none()) return motion.displayName;
        String first = motion.displayName.split(" ")[0];
        String last = effect.displayName.split(" ")[effect.displayName.split(" ").length - 1];
        return first + " " + suffix(last);
    }

    private static String suffix(String w) {
        String l = w.toLowerCase(Locale.ROOT);
        if (l.endsWith("ing") && l.length() > 5) return w.substring(0, w.length() - 3);
        return w;
    }

    /** Pair key for the discovered set. */
    public static String key(AnimStyleDef motion, AnimStyleDef effect) {
        return motion.key + "+" + effect.key;
    }

    public static int totalCombos() {
        return AnimStyleDef.Registry.motionStyles().size() * AnimStyleDef.Registry.effectStyles().size();
    }
}
