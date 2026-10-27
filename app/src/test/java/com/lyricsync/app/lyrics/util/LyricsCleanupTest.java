package com.lyricsync.app.lyrics.util;

import static org.junit.Assert.assertEquals;

import com.lyricsync.app.lyrics.model.LyricsData;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class LyricsCleanupTest {

    private static List<String> clean(String title, String... lines) {
        LyricsData l = new LyricsData(LyricsData.Type.LINE);
        long t = 0;
        for (String s : lines) {
            l.lines.add(new LyricsData.LyricsLine(t, t + 1000, s));
            t += 1000;
        }
        LyricsCleanup.clean(l, title);
        List<String> out = new ArrayList<>();
        for (LyricsData.LyricsLine line : l.lines) if (!line.isInterlude) out.add(line.text);
        return out;
    }

    @Test
    public void dropsHeaderCreditsAndAds() {
        assertEquals(java.util.Arrays.asList("故事的小黄花", "从出生那年就飘着"),
                clean("晴天", "晴天 - 周杰伦", "词：周杰伦", "曲：周杰伦", "本歌曲翻唱由某某提供",
                        "故事的小黄花", "从出生那年就飘着"));
    }

    @Test
    public void dropsLooseOpeningCredits() {
        assertEquals(java.util.Arrays.asList("It's gonna take a lot"),
                clean("Africa", "Wriiten by：Someone", "David Paich：", "原唱：TOTO", "Vocal Produced：Someone",
                        "It's gonna take a lot"));
    }

    @Test
    public void headerWithSubtitleInBrackets() {
        assertEquals(java.util.Arrays.asList("缓缓飘落的枫叶像思念"),
                clean("光年之外", "光年之外 (《太空旅客（Passengers）》电影中国区主题曲) - G.E.M.邓紫棋", "缓缓飘落的枫叶像思念"));
    }

    @Test
    public void keepsColonsInsideTheSong() {
        // After the first lyric, a colon is lyrics, not a credit.
        assertEquals(java.util.Arrays.asList("Hello", "Mama said: run"),
                clean("Song", "Hello", "Mama said: run"));
    }
}
