package com.lyricsync.app.lyrics.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.Test;

public class LyricsDataTest {

    @Test
    public void rankOrdersTypes() {
        assertTrue(LyricsData.rankOf(LyricsData.Type.SYLLABLE) > LyricsData.rankOf(LyricsData.Type.LINE));
        assertTrue(LyricsData.rankOf(LyricsData.Type.LINE) > LyricsData.rankOf(LyricsData.Type.STATIC));
        assertTrue(LyricsData.rankOf(LyricsData.Type.STATIC) > LyricsData.rankOf(null));
    }

    @Test
    public void cacheRoundTripKeepsTranslations() {
        LyricsData data = new LyricsData(LyricsData.Type.LINE);
        data.translationLang = "id";
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 5000, "Hello world");
        line.translation = "Halo dunia";
        data.lines.add(line);

        Gson gson = new Gson();
        LyricsData restored = gson.fromJson(gson.toJson(data), LyricsData.class);
        assertEquals(LyricsData.Type.LINE, restored.type);
        assertEquals("id", restored.translationLang);
        assertEquals("Halo dunia", restored.lines.get(0).translation);
    }

    @Test
    public void interludesFillLongGapsOnly() {
        LyricsData data = new LyricsData(LyricsData.Type.LINE);
        data.lines.add(new LyricsData.LyricsLine(0, 1000, "a"));
        data.lines.add(new LyricsData.LyricsLine(1500, 2000, "b"));   // 500 ms gap
        data.lines.add(new LyricsData.LyricsLine(10000, 11000, "c")); // 8 s gap
        LyricsData.insertInterludes(data, 3000);
        int interludes = 0;
        for (LyricsData.LyricsLine l : data.lines) if (l.isInterlude) interludes++;
        assertEquals(1, interludes); // only the 8 s gap
        assertEquals("c", data.lines.get(data.lines.size() - 1).text);
    }
}
