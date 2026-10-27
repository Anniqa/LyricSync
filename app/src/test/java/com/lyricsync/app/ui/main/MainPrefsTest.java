package com.lyricsync.app.ui.main;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The main screen's pref contract: keys, defaults and clamps must match the shipped values. */
public class MainPrefsTest {

    @Test
    public void keysAreTheShippedStrings() {
        assertEquals("lyricsync", MainPrefs.FILE);
        assertEquals("font_scale", MainPrefs.FONT_SCALE);
        assertEquals("overlay_width_percent", MainPrefs.WIDTH_PERCENT);
        assertEquals("overlay_height_percent", MainPrefs.HEIGHT_PERCENT);
        assertEquals("sync_offset_ms", MainPrefs.SYNC_OFFSET_MS);
        assertEquals("pref_ai_model", MainPrefs.AI_MODEL);
        assertEquals("pref_ai_search_model", MainPrefs.AI_SEARCH_MODEL);
        assertEquals("pref_ai_total_tokens", MainPrefs.AI_TOTAL_TOKENS);
        assertEquals("dev_expanded", MainPrefs.DEV_EXPANDED);
    }

    @Test
    public void defaultsAndRangesMatchTheOverlay() {
        assertEquals(1.0f, MainPrefs.FONT_SCALE_DEFAULT, 0f);
        assertEquals(88, MainPrefs.WIDTH_DEFAULT);
        assertEquals(36, MainPrefs.HEIGHT_DEFAULT);
        assertEquals(1500, MainPrefs.SYNC_LIMIT_MS);
        // The overlay re-reads these prefs with its own config; the ranges must agree.
        com.lyricsync.app.overlay.OverlayConfig c = com.lyricsync.app.overlay.OverlayConfig.DEFAULT;
        assertEquals(c.widthPercentMin, MainPrefs.WIDTH_MIN);
        assertEquals(c.widthPercentMax, MainPrefs.WIDTH_MAX);
        assertEquals(c.widthPercentDefault, MainPrefs.WIDTH_DEFAULT);
        assertEquals(c.heightPercentMin, MainPrefs.HEIGHT_MIN);
        assertEquals(c.heightPercentMax, MainPrefs.HEIGHT_MAX);
        assertEquals(c.heightPercentDefault, MainPrefs.HEIGHT_DEFAULT);
        assertEquals(c.syncOffsetLimitMs, MainPrefs.SYNC_LIMIT_MS);
        assertEquals(Math.round(c.fontScaleMin * 100), MainPrefs.FONT_PERCENT_MIN);
        assertEquals(Math.round(c.fontScaleMax * 100), MainPrefs.FONT_PERCENT_MAX);
    }

    @Test
    public void storedValuesAreClampedLikeBefore() {
        assertEquals(100, MainPrefs.fontPercent(1.0f));
        assertEquals(50, MainPrefs.fontPercent(0.1f));
        assertEquals(200, MainPrefs.fontPercent(9f));
        assertEquals(55, MainPrefs.widthPercent(10));
        assertEquals(100, MainPrefs.widthPercent(140));
        assertEquals(20, MainPrefs.heightPercent(0));
        assertEquals(70, MainPrefs.heightPercent(99));
        assertEquals(1500, MainPrefs.syncOffset(99_999L));
        assertEquals(-1500, MainPrefs.syncOffset(Long.MIN_VALUE));
        assertEquals(-250, MainPrefs.syncOffset(-250L));
    }

    @Test
    public void sliderValuesAreAlwaysLegal() {
        // Material Slider throws for out-of-range or off-step values.
        assertEquals(50f, MainPrefs.sliderValue(12f, 50f, 200f), 0f);
        assertEquals(200f, MainPrefs.sliderValue(999f, 50f, 200f), 0f);
        assertEquals(123f, MainPrefs.sliderValue(122.6f, 50f, 200f), 0f);
        assertEquals(-1500f, MainPrefs.sliderValue(-3000f, -1500f, 1500f), 0f);
        for (float v = -2000f; v <= 2000f; v += 37.3f) {
            float s = MainPrefs.sliderValue(v, -1500f, 1500f);
            assertTrue(s >= -1500f && s <= 1500f);
            assertEquals(s, Math.round(s), 0f);
        }
    }

    @Test
    public void offsetReadOut() {
        assertEquals("+120", MainPrefs.signedOffset(120));
        assertEquals("-40", MainPrefs.signedOffset(-40));
        assertEquals("0", MainPrefs.signedOffset(0));
        assertEquals(1, MainPrefs.offsetDirection(5));
        assertEquals(-1, MainPrefs.offsetDirection(-5));
        assertEquals(0, MainPrefs.offsetDirection(0));
    }

    @Test
    public void targetLanguageLookup() {
        assertEquals(9, MainPrefs.TARGET_LANG_CODES.length);
        assertEquals(0, MainPrefs.targetLangIndex("auto"));
        assertEquals(6, MainPrefs.targetLangIndex("zh-cn"));
        assertEquals(0, MainPrefs.targetLangIndex("xx"));
        assertEquals(0, MainPrefs.targetLangIndex(null));
    }

    @Test
    public void spicyKeyValidation() {
        assertTrue(MainPrefs.isValidSpicyKey("sl_pk_abc-DEF_123"));
        assertTrue(MainPrefs.isValidSpicyKey("sl_sk_x"));
        assertFalse(MainPrefs.isValidSpicyKey("sl_xx_abc"));
        assertFalse(MainPrefs.isValidSpicyKey("sl_pk_"));
        assertFalse(MainPrefs.isValidSpicyKey("sl_pk_a b"));
        assertFalse(MainPrefs.isValidSpicyKey(null));
    }

    @Test
    public void avatarLetter() {
        assertEquals("A", MainPrefs.avatarLetter("alice@example.com"));
        assertEquals("G", MainPrefs.avatarLetter(""));
        assertEquals("G", MainPrefs.avatarLetter(null));
        assertEquals("Z", MainPrefs.avatarLetter("  zed"));
    }
}
