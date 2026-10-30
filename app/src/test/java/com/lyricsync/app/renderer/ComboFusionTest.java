package com.lyricsync.app.renderer;

import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Combo Lab fusion: stable results, every pair valid, every rarity reachable, combos rank high. */
public class ComboFusionTest {

    @Test
    public void everyPairFusesStablyAndAllRaritiesAppear() {
        EnumSet<ComboFusion.Rarity> seen = EnumSet.noneOf(ComboFusion.Rarity.class);
        for (AnimStyleDef m : AnimStyleDef.Registry.motionStyles()) {
            for (AnimStyleDef e : AnimStyleDef.Registry.effectStyles()) {
                ComboFusion.Result a = ComboFusion.fuse(m, e), b = ComboFusion.fuse(m, e);
                assertEquals("deterministic", a.power, b.power);
                assertEquals(a.name, b.name);
                assertTrue(a.power >= 5 && a.power <= 100);
                assertTrue("name not empty: " + m.key + "+" + e.key, !a.name.trim().isEmpty());
                seen.add(a.rarity);
            }
        }
        assertEquals("every rarity tier is reachable", EnumSet.allOf(ComboFusion.Rarity.class), seen);
        assertEquals(AnimStyleDef.Registry.motionStyles().size() * AnimStyleDef.Registry.effectStyles().size(),
                ComboFusion.totalCombos());
    }

    @Test
    public void layeredEffectsOutrankPlainOnesAndNamesFuse() {
        AnimStyleDef spring = AnimStyleDef.Registry.motionByKey("spring");
        AnimStyleDef none = AnimStyleDef.Registry.none();
        AnimStyleDef cosmic = AnimStyleDef.Registry.effectByKey("cosmic");
        AnimStyleDef burning = AnimStyleDef.Registry.effectByKey("burning");
        assertTrue(ComboFusion.fuse(spring, cosmic).power > ComboFusion.fuse(spring, burning).power);
        assertTrue(ComboFusion.fuse(spring, burning).power > ComboFusion.fuse(spring, none).power);
        assertEquals("no effect keeps the motion name", spring.displayName, ComboFusion.name(spring, none));
        assertEquals("Spring Burn", ComboFusion.name(spring, burning));
        assertEquals(ComboFusion.Element.FIRE, ComboFusion.element(burning));
        assertEquals(ComboFusion.Element.WATER, ComboFusion.element(AnimStyleDef.Registry.motionByKey("wave")));
        assertEquals("spring+burning", ComboFusion.key(spring, burning));
    }
}
