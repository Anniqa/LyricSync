package com.lyricsync.app.renderer;

import com.lyricsync.app.lyrics.model.LyricsData;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class SyllableHighlighterLayerTest {

    private SyllableHighlighter highlighter;

    @Before
    public void setUp() {
        highlighter = new SyllableHighlighter(null, null, null, 24.0f);
    }

    @Test
    public void testTransliterationToggleDefaults() {
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 3000, "前前");
        line.romanji = "zen zen";
        line.words.add(new LyricsData.Word(0, 1500, "前", "zen"));
        line.words.add(new LyricsData.Word(1500, 3000, "前", "zen"));

        assertEquals("zen", line.words.get(0).romanji);
        assertEquals("zen", line.words.get(1).romanji);
        assertEquals("zen zen", line.romanji);

        // Verify default transliteration is enabled
        assertTrue("Transliteration should be enabled by default", highlighter.isTransliterationEnabled());

        // Toggle disabled
        highlighter.setTransliterationEnabled(false);
        assertFalse("Transliteration should be disabled after toggle", highlighter.isTransliterationEnabled());

        // Toggle enabled
        highlighter.setTransliterationEnabled(true);
        assertTrue("Transliteration should be re-enabled", highlighter.isTransliterationEnabled());
    }

    @Test
    public void testHasAnyWordRomanji() {
        // Null line
        assertFalse(highlighter.hasAnyWordRomanji(null));

        // Line with null words list
        LyricsData.LyricsLine nullWordsLine = new LyricsData.LyricsLine(0, 1000, "test");
        nullWordsLine.words = null;
        assertFalse(highlighter.hasAnyWordRomanji(nullWordsLine));

        // Line with empty words
        LyricsData.LyricsLine emptyLine = new LyricsData.LyricsLine(0, 1000, "test");
        assertFalse(highlighter.hasAnyWordRomanji(emptyLine));

        // Line with words having null romaji
        LyricsData.LyricsLine lineNoRomaji = new LyricsData.LyricsLine(0, 2000, "hello world");
        lineNoRomaji.words.add(new LyricsData.Word(0, 1000, "hello"));
        lineNoRomaji.words.add(new LyricsData.Word(1000, 2000, "world"));
        assertFalse(highlighter.hasAnyWordRomanji(lineNoRomaji));

        // Line with whitespace-only romaji
        LyricsData.LyricsLine lineWhitespaceRomaji = new LyricsData.LyricsLine(0, 2000, "hello world");
        lineWhitespaceRomaji.words.add(new LyricsData.Word(0, 1000, "hello", "   "));
        lineWhitespaceRomaji.words.add(new LyricsData.Word(1000, 2000, "world", ""));
        assertFalse(highlighter.hasAnyWordRomanji(lineWhitespaceRomaji));

        // Line with valid romaji on one word
        LyricsData.LyricsLine linePartialRomaji = new LyricsData.LyricsLine(0, 2000, "前 前");
        linePartialRomaji.words.add(new LyricsData.Word(0, 1000, "前", "zen"));
        linePartialRomaji.words.add(new LyricsData.Word(1000, 2000, "前", null));
        assertTrue(highlighter.hasAnyWordRomanji(linePartialRomaji));

        // Line with all romaji
        LyricsData.LyricsLine lineFullRomaji = new LyricsData.LyricsLine(0, 2000, "前 前");
        lineFullRomaji.words.add(new LyricsData.Word(0, 1000, "前", "zen"));
        lineFullRomaji.words.add(new LyricsData.Word(1000, 2000, "前", "zen"));
        assertTrue(highlighter.hasAnyWordRomanji(lineFullRomaji));
    }

    @Test
    public void testLineViewFields() {
        SyllableHighlighter.LineView lv = new SyllableHighlighter.LineView();
        assertNull("romajiWordFlowLayout should be null initially", lv.romajiWordFlowLayout);
        assertNull("romajiWordViews should be null initially", lv.romajiWordViews);

        // Verify fields can be assigned
        List<GradientWordView> dummyList = new ArrayList<>();
        lv.romajiWordViews = dummyList;
        assertSame(dummyList, lv.romajiWordViews);
    }

    @Test
    public void testUpdateLineTransliterationSafeguards() {
        // Safe when lineViews is empty
        highlighter.updateLineTransliteration(-1, Collections.singletonList("test"), "test");
        highlighter.updateLineTransliteration(0, Collections.singletonList("test"), "test");
        highlighter.updateLineTransliteration(5, Collections.singletonList("test"), "test");

        // When a LineView is present in lineViews
        SyllableHighlighter.LineView lv = new SyllableHighlighter.LineView();
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 2000, "前 前");
        line.words.add(new LyricsData.Word(0, 1000, "前"));
        line.words.add(new LyricsData.Word(1000, 2000, "前"));
        lv.line = line;
        highlighter.getLineViews().add(lv);

        highlighter.updateLineTransliteration(0, Arrays.asList("zen", "zen"), "zen zen");
        assertEquals("zen zen", lv.line.romanji);
        assertEquals("zen", lv.line.words.get(0).romanji);
        assertEquals("zen", lv.line.words.get(1).romanji);
    }

    @Test
    public void testUpdateLineTransliterationPartialAndNulls() {
        SyllableHighlighter.LineView lv = new SyllableHighlighter.LineView();
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 3000, "前 前 前");
        line.words.add(new LyricsData.Word(0, 1000, "前"));
        line.words.add(new LyricsData.Word(1000, 2000, "前"));
        line.words.add(new LyricsData.Word(2000, 3000, "前"));
        lv.line = line;
        highlighter.getLineViews().add(lv);

        // Update with fewer items than words
        highlighter.updateLineTransliteration(0, Arrays.asList("zen1", "zen2"), "zen line");
        assertEquals("zen line", lv.line.romanji);
        assertEquals("zen1", lv.line.words.get(0).romanji);
        assertEquals("zen2", lv.line.words.get(1).romanji);
        assertNull(lv.line.words.get(2).romanji);

        // Update with null words list
        highlighter.updateLineTransliteration(0, null, "zen line 2");
        assertEquals("zen line 2", lv.line.romanji);
        assertEquals("zen1", lv.line.words.get(0).romanji);
    }

    @Test
    public void testUpdateProgressSafeCall() {
        // Calling updateProgress should run without error on empty or populated lineViews
        highlighter.updateProgress(1500L);

        SyllableHighlighter.LineView lv = new SyllableHighlighter.LineView();
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 3000, "test");
        line.words.add(new LyricsData.Word(0, 1500, "te"));
        line.words.add(new LyricsData.Word(1500, 3000, "st"));
        lv.line = line;
        highlighter.getLineViews().add(lv);

        highlighter.updateProgress(1000L);
        highlighter.updateProgress(2500L);
    }

    @Test
    public void testNoNonLatinInRomajiLayer() {
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 3000, "前 前");
        line.words.add(new LyricsData.Word(0, 1500, "前"));
        line.words.add(new LyricsData.Word(1500, 3000, "前"));

        // When words don't have romaji yet, hasAnyWordRomanji is false
        assertFalse(highlighter.hasAnyWordRomanji(line));

        // When 1 word has romaji and 1 doesn't
        line.words.get(0).romanji = "zen";
        assertTrue(highlighter.hasAnyWordRomanji(line));

        // SyllableHighlighter must never put Kanji "前" into romanji field
        assertNull(line.words.get(1).romanji);
    }
}
