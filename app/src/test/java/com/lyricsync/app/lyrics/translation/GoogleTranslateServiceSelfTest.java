package com.lyricsync.app.lyrics.translation;

import com.lyricsync.app.lyrics.model.LyricsData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GoogleTranslateServiceSelfTest {

    public static void main(String[] args) {
        // Test query text building with interlude and empty lines filtering
        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 2000, "Line 1"));
        LyricsData.LyricsLine interlude = new LyricsData.LyricsLine(2000, 4000, "");
        interlude.isInterlude = true;
        lines.add(interlude);
        lines.add(new LyricsData.LyricsLine(4000, 6000, "   "));
        lines.add(new LyricsData.LyricsLine(6000, 8000, "Line 2\nwith newline"));

        List<LyricsData.LyricsLine> translatable = GoogleTranslateService.filterTranslatableLines(lines);
        check(translatable.size() == 2, "filtered translatable count");
        check("Line 1".equals(translatable.get(0).text), "line 1 text");
        check("Line 2\nwith newline".equals(translatable.get(1).text), "line 2 text");

        String query = GoogleTranslateService.buildQueryText(translatable);
        check("Line 1\nLine 2 with newline\n".equals(query), "query text normalized newlines");

        // Test language resolution
        check("en".equals(GoogleTranslateService.resolveTargetLang("en", Locale.FRENCH)), "explicit en");
        check("fr".equals(GoogleTranslateService.resolveTargetLang("auto", Locale.FRENCH)), "auto resolved to system");
        check("id".equals(GoogleTranslateService.resolveTargetLang(null, new Locale("id"))), "null resolved to system");

        System.out.println("GoogleTranslateServiceSelfTest OK");
    }

    private static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
    }
}
