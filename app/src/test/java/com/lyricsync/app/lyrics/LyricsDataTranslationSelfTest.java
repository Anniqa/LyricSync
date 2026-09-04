package com.lyricsync.app.lyrics;

import com.google.gson.Gson;
import com.lyricsync.app.lyrics.model.LyricsData;

public class LyricsDataTranslationSelfTest {

    public static void main(String[] args) {
        LyricsData data = new LyricsData();
        data.type = LyricsData.Type.LINE;
        data.translationLang = "id";

        LyricsData.LyricsLine line1 = new LyricsData.LyricsLine(0, 5000, "Hello world");
        line1.translation = "Halo dunia";
        data.lines.add(line1);

        LyricsData.LyricsLine line2 = new LyricsData.LyricsLine(5000, 10000, "Goodbye world");
        line2.translation = "Selamat tinggal dunia";
        data.lines.add(line2);

        check("id".equals(data.translationLang), "translationLang stored on LyricsData");
        check("Halo dunia".equals(data.lines.get(0).translation), "translation stored on line 0");
        check("Selamat tinggal dunia".equals(data.lines.get(1).translation), "translation stored on line 1");

        // Gson round-trip test
        Gson gson = new Gson();
        String json = gson.toJson(data);
        LyricsData restored = gson.fromJson(json, LyricsData.class);

        check("id".equals(restored.translationLang), "restored translationLang matches");
        check(restored.lines.size() == 2, "restored lines count");
        check("Halo dunia".equals(restored.lines.get(0).translation), "restored line 0 translation");
        check("Selamat tinggal dunia".equals(restored.lines.get(1).translation), "restored line 1 translation");

        System.out.println("LyricsDataTranslationSelfTest OK");
    }

    private static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
    }
}
