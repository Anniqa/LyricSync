package com.lyricsync.app.lyrics.translation;

import com.lyricsync.app.lyrics.model.LyricsData;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GoogleTranslateParserSelfTest {

    @Test
    public void sentenceSplitMappingReconstructsLines() {
        // Line 0 had punctuation and was split into 2 chunks by Google
        String mockJson = "[[[\"Halo! \",\"Hello! \",null,null,3],[\"Apa kabarmu?\\n\",\"How are you?\\n\",null,null,3],"
                + "[\"Saya baik-baik saja.\\n\",\"I am fine.\\n\",null,null,3],"
                + "[\"Terima kasih.\",\"Thank you.\",null,null,3]],null,\"en\"]";

        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 3000, "Hello! How are you?"));
        lines.add(new LyricsData.LyricsLine(3000, 6000, "I am fine."));
        lines.add(new LyricsData.LyricsLine(6000, 9000, "Thank you."));

        GoogleTranslateParser.ParseResult res = GoogleTranslateParser.parse(mockJson, lines, "id");
        assertEquals("en", res.detectedSourceLang);
        assertFalse("not same language", res.isSameLanguage);
        assertEquals("line 0 reconstructed properly across chunks", "Halo! Apa kabarmu?", lines.get(0).translation);
        assertEquals("line 1 translation", "Saya baik-baik saja.", lines.get(1).translation);
        assertEquals("line 2 translation", "Terima kasih.", lines.get(2).translation);
    }

    @Test
    public void cjkMultiLineMergedChunkMapsPerLine() {
        // Korean lyrics returned as 1 single segment containing newlines
        String mockJson = "[[[\"aku merindukanmu\\nSekarang aku ingin melihatmu\\nBahkan fotomu\\naku merindukanmu\",\"...\",null,null,3]],null,\"ko\"]";

        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 2000, "line 1"));
        lines.add(new LyricsData.LyricsLine(2000, 4000, "line 2"));
        lines.add(new LyricsData.LyricsLine(4000, 6000, "line 3"));
        lines.add(new LyricsData.LyricsLine(6000, 8000, "line 4"));

        GoogleTranslateParser.ParseResult res = GoogleTranslateParser.parse(mockJson, lines, "id");
        assertEquals("ko", res.detectedSourceLang);
        assertEquals(4, lines.size());
        assertEquals("aku merindukanmu", lines.get(0).translation);
        assertEquals("Sekarang aku ingin melihatmu", lines.get(1).translation);
        assertEquals("Bahkan fotomu", lines.get(2).translation);
        assertEquals("aku merindukanmu", lines.get(3).translation);
    }

    @Test
    public void interludeLinesAreSkipped() {
        String mockJson = "[[[\"Baris satu\\n\",\"Line one\\n\",null,null,3],[\"Baris dua\",\"Line two\",null,null,3]],null,\"en\"]";

        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 2000, "Line one"));
        LyricsData.LyricsLine interlude = new LyricsData.LyricsLine(2000, 5000, "");
        interlude.isInterlude = true;
        lines.add(interlude);
        lines.add(new LyricsData.LyricsLine(5000, 8000, "Line two"));

        // The parser only receives translatable lines
        List<LyricsData.LyricsLine> translatable = new ArrayList<>();
        for (LyricsData.LyricsLine l : lines) {
            if (!l.isInterlude) translatable.add(l);
        }

        GoogleTranslateParser.parse(mockJson, translatable, "id");
        assertEquals("Baris satu", lines.get(0).translation);
        assertNull("interlude remains null", lines.get(1).translation);
        assertEquals("Baris dua", lines.get(2).translation);
    }

    @Test
    public void sameLanguageSkipsTranslation() {
        String mockJson = "[[[\"Halo dunia\",\"Halo dunia\",null,null,3]],null,\"id\"]";
        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 2000, "Halo dunia"));

        GoogleTranslateParser.ParseResult res = GoogleTranslateParser.parse(mockJson, lines, "id");
        assertEquals("id", res.detectedSourceLang);
        assertTrue("flagged as same language", res.isSameLanguage);
        assertNull("translation skipped when same language", lines.get(0).translation);
    }
}
