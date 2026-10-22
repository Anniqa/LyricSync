package com.lyricsync.app.lyrics;

import com.google.gson.Gson;
import com.lyricsync.app.lyrics.model.LyricsData;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Translation fields survive a Gson round trip (translationLang + per-line translations). */
public class LyricsDataTranslationSelfTest {

    @Test
    public void gsonRoundTripKeepsTranslations() {
        LyricsData data = new LyricsData();
        data.type = LyricsData.Type.LINE;
        data.translationLang = "id";

        LyricsData.LyricsLine line1 = new LyricsData.LyricsLine(0, 5000, "Hello world");
        line1.translation = "Halo dunia";
        data.lines.add(line1);

        LyricsData.LyricsLine line2 = new LyricsData.LyricsLine(5000, 10000, "Goodbye world");
        line2.translation = "Selamat tinggal dunia";
        data.lines.add(line2);

        assertEquals("id", data.translationLang);
        assertEquals("Halo dunia", data.lines.get(0).translation);
        assertEquals("Selamat tinggal dunia", data.lines.get(1).translation);

        Gson gson = new Gson();
        LyricsData restored = gson.fromJson(gson.toJson(data), LyricsData.class);

        assertEquals("id", restored.translationLang);
        assertEquals(2, restored.lines.size());
        assertEquals("Halo dunia", restored.lines.get(0).translation);
        assertEquals("Selamat tinggal dunia", restored.lines.get(1).translation);
    }
}
