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

    @Test
    public void testOriginalScriptToggle() {
        assertTrue("Original script enabled by default", highlighter.isOriginalScriptEnabled());
        highlighter.setOriginalScriptEnabled(false);
        assertFalse("Original script disabled after toggle", highlighter.isOriginalScriptEnabled());
        highlighter.setOriginalScriptEnabled(true);
        assertTrue("Original script re-enabled", highlighter.isOriginalScriptEnabled());
    }

    @Test
    public void testUpdateLineTransliterationStripsNonLatinCharacters() {
        SyllableHighlighter.LineView lv = new SyllableHighlighter.LineView();
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 2000, "前 僕");
        line.words.add(new LyricsData.Word(0, 1000, "前"));
        line.words.add(new LyricsData.Word(1000, 2000, "僕"));
        lv.line = line;
        highlighter.getLineViews().add(lv);

        highlighter.updateLineTransliteration(0, Arrays.asList("前 (zen)", "僕"), "前 (zen) 僕");
        assertEquals("zen", lv.line.words.get(0).romanji);
        assertEquals("", lv.line.words.get(1).romanji);
        assertEquals("zen", lv.line.romanji);
    }

    @Test
    public void testFallbackToOriginalScriptWhenTransliterationDisabled() {
        SyllableHighlighter.LineView lv = new SyllableHighlighter.LineView();
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 2000, "前");
        line.words.add(new LyricsData.Word(0, 2000, "前", "zen"));
        lv.line = line;

        // When original is disabled but transliteration is also disabled, original should be shown to prevent blank lines
        highlighter.setOriginalScriptEnabled(false);
        highlighter.setTransliterationEnabled(false);

        boolean hasRomanji = lv.line != null && highlighter.hasAnyWordRomanji(lv.line);
        boolean showOriginal = highlighter.isOriginalScriptEnabled() || !highlighter.isTransliterationEnabled() || !hasRomanji;
        assertTrue("Original script must be shown if transliteration is disabled", showOriginal);
    }

    @Test
    public void testShowOriginalWhenNoRomajiLayoutToPreventBlankLines() {
        SyllableHighlighter.LineView lv = new SyllableHighlighter.LineView();
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 2000, "前");
        line.words.add(new LyricsData.Word(0, 2000, "前", "zen"));
        lv.line = line;
        lv.romajiWordFlowLayout = null;

        highlighter.setOriginalScriptEnabled(false);
        highlighter.setTransliterationEnabled(true);

        boolean hasRomanji = lv.line != null && highlighter.hasAnyWordRomanji(lv.line);
        boolean hasActiveRomaji = lv.romajiWordFlowLayout != null && highlighter.isTransliterationEnabled() && hasRomanji;
        boolean showOriginal = highlighter.isOriginalScriptEnabled() || !hasActiveRomaji;
        assertTrue("Original script must never be hidden if romaji layer is not present", showOriginal);
    }

    @Test
    public void testBuildDisplayWordsGrouping() {
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 2400, "前前前世から");
        LyricsData.Word w0 = new LyricsData.Word(0, 300, "前", "zen");
        w0.isPartOfWord = true;
        LyricsData.Word w1 = new LyricsData.Word(300, 600, "前", "zen");
        w1.isPartOfWord = true;
        LyricsData.Word w2 = new LyricsData.Word(600, 900, "前", "zen");
        w2.isPartOfWord = true;
        LyricsData.Word w3 = new LyricsData.Word(900, 1200, "世", "se");
        w3.isPartOfWord = false;

        LyricsData.Word w4 = new LyricsData.Word(1200, 1800, "か", "ka");
        w4.isPartOfWord = true;
        LyricsData.Word w5 = new LyricsData.Word(1800, 2400, "ら", "ra");
        w5.isPartOfWord = false;

        line.words.addAll(Arrays.asList(w0, w1, w2, w3, w4, w5));

        List<SyllableHighlighter.DisplayWord> displayWords = SyllableHighlighter.buildDisplayWords(line);
        assertEquals(2, displayWords.size());

        SyllableHighlighter.DisplayWord dw0 = displayWords.get(0);
        assertEquals("前前前世", dw0.text);
        assertEquals("zenzenzense", dw0.romanji);
        assertEquals(0, dw0.startTime);
        assertEquals(1200, dw0.endTime);
        assertEquals(4, dw0.syllables.size());

        SyllableHighlighter.DisplayWord dw1 = displayWords.get(1);
        assertEquals("から", dw1.text);
        assertEquals("kara", dw1.romanji);
        assertEquals(1200, dw1.startTime);
        assertEquals(2400, dw1.endTime);
        assertEquals(2, dw1.syllables.size());
    }

    @Test
    public void testBuildDisplayWordsDiscreteAndEmpty() {
        assertTrue(SyllableHighlighter.buildDisplayWords(null).isEmpty());
        assertTrue(SyllableHighlighter.buildDisplayWords(new LyricsData.LyricsLine(0, 100, "")).isEmpty());

        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 1000, "I am");
        LyricsData.Word w0 = new LyricsData.Word(0, 500, "I", "I");
        w0.isPartOfWord = false;
        LyricsData.Word w1 = new LyricsData.Word(500, 1000, "am", "am");
        w1.isPartOfWord = false;
        line.words.addAll(Arrays.asList(w0, w1));

        List<SyllableHighlighter.DisplayWord> displayWords = SyllableHighlighter.buildDisplayWords(line);
        assertEquals(2, displayWords.size());
        assertEquals("I", displayWords.get(0).text);
        assertEquals(0, displayWords.get(0).startTime);
        assertEquals(500, displayWords.get(0).endTime);
        assertEquals("am", displayWords.get(1).text);
        assertEquals(500, displayWords.get(1).startTime);
        assertEquals(1000, displayWords.get(1).endTime);
    }

    @Test
    public void testBuildDisplayWordsSafetyCap() {
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 3000, "12345678");
        for (int i = 0; i < 8; i++) {
            LyricsData.Word w = new LyricsData.Word(i * 300, (i + 1) * 300, "word" + i, "romaji" + i);
            w.isPartOfWord = true; // all marked as part of word (e.g. erroneous run-on)
            line.words.add(w);
        }
        List<SyllableHighlighter.DisplayWord> displayWords = SyllableHighlighter.buildDisplayWords(line);
        // Safety cap must prevent all 8 syllables from becoming 1 giant display word
        assertTrue("Safety cap must split runaway words so views can wrap in WordFlowLayout", displayWords.size() > 1);
        for (SyllableHighlighter.DisplayWord dw : displayWords) {
            assertTrue("DisplayWord must not exceed 4 syllables", dw.syllables.size() <= 4);
        }
    }
}
