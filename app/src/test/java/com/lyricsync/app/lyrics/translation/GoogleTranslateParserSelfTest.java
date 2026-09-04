package com.lyricsync.app.lyrics.translation;

import com.lyricsync.app.lyrics.model.LyricsData;
import java.util.ArrayList;
import java.util.List;

public class GoogleTranslateParserSelfTest {

    public static void main(String[] args) {
        testSentenceSplitMapping();
        testCJKMultiLineMergedChunk();
        testInterludeSkipping();
        testSameLanguageDetection();
        System.out.println("GoogleTranslateParserSelfTest OK");
    }

    private static void testSentenceSplitMapping() {
        // Line 0 had punctuation and was split into 2 chunks by Google
        String mockJson = "[[[\"Halo! \",\"Hello! \",null,null,3],[\"Apa kabarmu?\\n\",\"How are you?\\n\",null,null,3],"
                + "[\"Saya baik-baik saja.\\n\",\"I am fine.\\n\",null,null,3],"
                + "[\"Terima kasih.\",\"Thank you.\",null,null,3]],null,\"en\"]";

        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 3000, "Hello! How are you?"));
        lines.add(new LyricsData.LyricsLine(3000, 6000, "I am fine."));
        lines.add(new LyricsData.LyricsLine(6000, 9000, "Thank you."));

        GoogleTranslateParser.ParseResult res = GoogleTranslateParser.parse(mockJson, lines, "id");
        check("en".equals(res.detectedSourceLang), "detected en");
        check(!res.isSameLanguage, "not same language");
        check("Halo! Apa kabarmu?".equals(lines.get(0).translation), "line 0 reconstructed properly across chunks");
        check("Saya baik-baik saja.".equals(lines.get(1).translation), "line 1 translation");
        check("Terima kasih.".equals(lines.get(2).translation), "line 2 translation");
    }

    private static void testCJKMultiLineMergedChunk() {
        // Korean lyrics returned as 1 single segment containing newlines
        String mockJson = "[[[\"aku merindukanmu\\nSekarang aku ingin melihatmu\\nBahkan fotomu\\naku merindukanmu\",\"...\",null,null,3]],null,\"ko\"]";

        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 2000, "line 1"));
        lines.add(new LyricsData.LyricsLine(2000, 4000, "line 2"));
        lines.add(new LyricsData.LyricsLine(4000, 6000, "line 3"));
        lines.add(new LyricsData.LyricsLine(6000, 8000, "line 4"));

        GoogleTranslateParser.ParseResult res = GoogleTranslateParser.parse(mockJson, lines, "id");
        check("ko".equals(res.detectedSourceLang), "detected ko");
        check(lines.size() == 4, "4 lines");
        check("aku merindukanmu".equals(lines.get(0).translation), "korean line 0");
        check("Sekarang aku ingin melihatmu".equals(lines.get(1).translation), "korean line 1");
        check("Bahkan fotomu".equals(lines.get(2).translation), "korean line 2");
        check("aku merindukanmu".equals(lines.get(3).translation), "korean line 3");
    }

    private static void testInterludeSkipping() {
        String mockJson = "[[[\"Baris satu\\n\",\"Line one\\n\",null,null,3],[\"Baris dua\",\"Line two\",null,null,3]],null,\"en\"]";

        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 2000, "Line one"));
        LyricsData.LyricsLine interlude = new LyricsData.LyricsLine(2000, 5000, "");
        interlude.isInterlude = true;
        lines.add(interlude);
        lines.add(new LyricsData.LyricsLine(5000, 8000, "Line two"));

        // Parser only receives translatable lines
        List<LyricsData.LyricsLine> translatable = new ArrayList<>();
        for (LyricsData.LyricsLine l : lines) {
            if (!l.isInterlude) translatable.add(l);
        }

        GoogleTranslateParser.parse(mockJson, translatable, "id");
        check("Baris satu".equals(lines.get(0).translation), "translatable line 0");
        check(lines.get(1).translation == null, "interlude remains null");
        check("Baris dua".equals(lines.get(2).translation), "translatable line 2");
    }

    private static void testSameLanguageDetection() {
        String mockJson = "[[[\"Halo dunia\",\"Halo dunia\",null,null,3]],null,\"id\"]";
        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 2000, "Halo dunia"));

        GoogleTranslateParser.ParseResult res = GoogleTranslateParser.parse(mockJson, lines, "id");
        check("id".equals(res.detectedSourceLang), "detected id");
        check(res.isSameLanguage, "flagged as same language");
        check(lines.get(0).translation == null, "translation skipped when same language");
    }

    private static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
    }
}
