package com.lyricsync.app.lyrics.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class JapaneseRomanizerTest {

    @Test
    public void romanisesHiragana() {
        assertEquals("suzume", JapaneseRomanizer.romanize("\u3059\u305a\u3081"));
        assertEquals("gakkou", JapaneseRomanizer.romanize("\u304c\u3063\u3053\u3046"));
        assertEquals("kyou", JapaneseRomanizer.romanize("\u304d\u3087\u3046"));
    }

    @Test
    public void romanisesKatakana() {
        assertEquals("suzume", JapaneseRomanizer.romanize("\u30b9\u30ba\u30e1"));
        assertEquals("toukyou", JapaneseRomanizer.romanize("\u30c8\u30a6\u30ad\u30e7\u30a6"));
    }

    @Test
    public void leavesLatinAndKanjiUntouched() {
        assertEquals("RADWIMPS", JapaneseRomanizer.romanize("RADWIMPS"));
        // 君の名は: kanji kept, kana romanised -> "君no名ha"
        assertEquals("\u541bno\u540dha", JapaneseRomanizer.romanize("\u541b\u306e\u540d\u306f"));
        assertEquals("kimi", JapaneseRomanizer.romanize("kimi"));
    }

    @Test
    public void detectsKana() {
        assertTrue(JapaneseRomanizer.containsKana("\u3059\u305a\u3081"));
        assertTrue(JapaneseRomanizer.containsKana("\u30b9\u30ba\u30e1"));
        assertFalse(JapaneseRomanizer.containsKana("Suzume"));
        assertFalse(JapaneseRomanizer.containsKana(""));
    }
}
