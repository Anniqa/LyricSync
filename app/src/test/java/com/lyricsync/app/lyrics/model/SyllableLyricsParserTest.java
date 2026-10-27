package com.lyricsync.app.lyrics.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.DeflaterOutputStream;

public class SyllableLyricsParserTest {

    private static LyricsData.LyricsLine firstLyric(LyricsData l) {
        for (LyricsData.LyricsLine line : l.lines) if (!line.isInterlude) return line;
        throw new AssertionError("no lyric line");
    }

    @Test
    public void yrc_wordsUseAbsoluteTimes() {
        LyricsData l = SyllableLyricsParser.parseYrc(
                "{\"t\":0,\"c\":[{\"tx\":\"credits\"}]}\n[1000,900](1000,400,0)Hel(1400,500,0)lo");
        assertEquals(LyricsData.Type.SYLLABLE, l.type);
        LyricsData.LyricsLine line = firstLyric(l);
        assertEquals("Hello", line.text);
        assertEquals(1400, line.words.get(1).startTime);
    }

    @Test
    public void krc_wordsAreOffsetsFromLineStart() {
        LyricsData l = SyllableLyricsParser.parseKrc("[ti:Song]\n[5000,1000]<0,300,0>Hi <300,700,0>there");
        LyricsData.LyricsLine line = firstLyric(l);
        assertEquals("Hi there", line.text);
        assertEquals(5300, line.words.get(1).startTime);
        assertEquals(6000, line.words.get(1).endTime);
    }

    @Test
    public void wordTimedWithoutWords_fallsBackToLine() {
        assertEquals(LyricsData.Type.LINE, SyllableLyricsParser.parseKrc("[ti:x]\n[ar:y]").type);
    }

    @Test
    public void lrc_repeatedTagsAndUnsortedLines() {
        LyricsData l = SyllableLyricsParser.parseLrc(
                "[ar:Someone]\n[00:20.00]second\n[00:10.5][00:30.05]chorus\n[00:40]");
        assertEquals(LyricsData.Type.LINE, l.type);
        StringBuilder order = new StringBuilder();
        for (LyricsData.LyricsLine line : l.lines) if (!line.isInterlude) order.append(line.text).append('@').append(line.startTime).append(' ');
        assertEquals("chorus@10500 second@20000 chorus@30050 ", order.toString());
        LyricsData.LyricsLine first = firstLyric(l);
        assertEquals(20000, first.endTime);
        assertEquals(1, first.words.size());
    }

    @Test
    public void lrc_wordTimingCoversTheLine() {
        LyricsData l = SyllableLyricsParser.parseLrc("[00:01.00]one two three\n[00:04.00]next");
        LyricsData.LyricsLine line = firstLyric(l);
        assertEquals(3, line.words.size());
        assertEquals(1000, line.words.get(0).startTime);
        assertEquals(4000, line.words.get(2).endTime);
    }

    @Test
    public void krc_decryptRoundTrip() throws Exception {
        int[] key = {0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47, 0x51, 0x36, 0x31, 0x2d, 0xce, 0xd2, 0x6e, 0x69};
        String plain = "[0,500]<0,500,0>歌";
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        try (DeflaterOutputStream out = new DeflaterOutputStream(z)) {
            out.write(plain.getBytes(StandardCharsets.UTF_8));
        }
        byte[] packed = z.toByteArray();
        byte[] raw = new byte[packed.length + 4];
        raw[0] = 'k'; raw[1] = 'r'; raw[2] = 'c'; raw[3] = '1';
        for (int i = 0; i < packed.length; i++) raw[i + 4] = (byte) (packed[i] ^ key[i % key.length]);
        assertEquals(plain, new String(SyllableLyricsParser.decryptKrc(raw), StandardCharsets.UTF_8));
        assertTrue(SyllableLyricsParser.parseKrc(plain).isSynced());
    }
}
