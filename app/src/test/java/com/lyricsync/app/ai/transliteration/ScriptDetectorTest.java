package com.lyricsync.app.ai.transliteration;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScriptDetectorTest {

    @Test
    public void testDetectLatin() {
        assertEquals(ScriptDetector.ScriptType.LATIN, ScriptDetector.detectScript("Hello World 123! This is English lyrics."));
        assertEquals(ScriptDetector.ScriptType.LATIN, ScriptDetector.detectScript("Ini adalah lirik bahasa Indonesia."));
        assertFalse(ScriptDetector.isNonLatin("Simple Latin Text"));
    }

    @Test
    public void testDetectJapanese() {
        // Hiragana & Katakana & Kanji
        assertEquals(ScriptDetector.ScriptType.JAPANESE, ScriptDetector.detectScript("前前前世から僕は"));
        assertEquals(ScriptDetector.ScriptType.JAPANESE, ScriptDetector.detectScript("ありがとう"));
        assertEquals(ScriptDetector.ScriptType.JAPANESE, ScriptDetector.detectScript("スパークル"));
        assertTrue(ScriptDetector.isNonLatin("スパークル"));
    }

    @Test
    public void testDetectChinese() {
        // Hanzi without Kana
        assertEquals(ScriptDetector.ScriptType.CHINESE, ScriptDetector.detectScript("我爱你 中国"));
        assertTrue(ScriptDetector.isNonLatin("青花瓷"));
    }

    @Test
    public void testDetectKorean() {
        // Hangul
        assertEquals(ScriptDetector.ScriptType.KOREAN, ScriptDetector.detectScript("사랑해 영원히"));
        assertTrue(ScriptDetector.isNonLatin("강남스타일"));
    }

    @Test
    public void testDetectCyrillic() {
        // Russian / Cyrillic
        assertEquals(ScriptDetector.ScriptType.CYRILLIC, ScriptDetector.detectScript("Группа крови на рукаве"));
        assertTrue(ScriptDetector.isNonLatin("Привет мир"));
    }

    @Test
    public void testEmptyOrNull() {
        assertEquals(ScriptDetector.ScriptType.LATIN, ScriptDetector.detectScript(null));
        assertEquals(ScriptDetector.ScriptType.LATIN, ScriptDetector.detectScript("   "));
        assertFalse(ScriptDetector.isNonLatin(null));
    }
}
