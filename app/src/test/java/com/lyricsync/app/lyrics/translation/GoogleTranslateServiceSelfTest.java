package com.lyricsync.app.lyrics.translation;

import com.lyricsync.app.lyrics.model.LyricsData;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;

public class GoogleTranslateServiceSelfTest {

    @Test
    public void filtersAndBuildsQueryText() {
        // Interlude and blank lines are filtered out of the translatable set.
        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 2000, "Line 1"));
        LyricsData.LyricsLine interlude = new LyricsData.LyricsLine(2000, 4000, "");
        interlude.isInterlude = true;
        lines.add(interlude);
        lines.add(new LyricsData.LyricsLine(4000, 6000, "   "));
        lines.add(new LyricsData.LyricsLine(6000, 8000, "Line 2\nwith newline"));

        List<LyricsData.LyricsLine> translatable = GoogleTranslateService.filterTranslatableLines(lines);
        assertEquals(2, translatable.size());
        assertEquals("Line 1", translatable.get(0).text);
        assertEquals("Line 2\nwith newline", translatable.get(1).text);

        // Newlines inside a line are normalized to spaces in the batch query.
        String query = GoogleTranslateService.buildQueryText(translatable);
        assertEquals("Line 1\nLine 2 with newline\n", query);
    }

    @Test
    public void resolvesTargetLangIncludingLegacyCodes() {
        assertEquals("en", GoogleTranslateService.resolveTargetLang("en", Locale.FRENCH));
        assertEquals("fr", GoogleTranslateService.resolveTargetLang("auto", Locale.FRENCH));
        assertEquals("id", GoogleTranslateService.resolveTargetLang(null, new Locale("id")));
        assertEquals("id", GoogleTranslateService.resolveTargetLang("in", Locale.ENGLISH)); // legacy in → id
        assertEquals("id", GoogleTranslateService.resolveTargetLang("auto", new Locale("in")));
        assertEquals("he", GoogleTranslateService.resolveTargetLang("iw", Locale.ENGLISH)); // legacy iw → he
        assertEquals("yi", GoogleTranslateService.resolveTargetLang("ji", Locale.ENGLISH)); // legacy ji → yi
    }
}
