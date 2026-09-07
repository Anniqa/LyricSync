package com.lyricsync.app.lyrics.model;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;

import static org.junit.Assert.*;

public class SyllableLyricsParserTest {

    private static final int[] KRC_KEY = {
            0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
            0x51, 0x36, 0x31, 0x2d, 0xce, 0xd2, 0x6e, 0x69
    };

    @Test
    public void testParseYrc() {
        String yrc = "[12340,3200](12340,500,0)Hello (12840,600,0)world!(13440,2100,0) Beautiful\n"
                   + "[16000,2000](16000,1000,0)Second (17000,1000,0)line";

        LyricsData data = SyllableLyricsParser.parseYrc(yrc);
        assertEquals(LyricsData.Type.SYLLABLE, data.type);
        // Interludes might be inserted if start time >= 3000ms
        // Find the actual lyric lines
        LyricsData.LyricsLine first = null;
        for (LyricsData.LyricsLine line : data.lines) {
            if (!line.isInterlude) {
                first = line;
                break;
            }
        }
        assertNotNull(first);
        assertEquals(12340, first.startTime);
        assertEquals(15540, first.endTime);
        assertEquals("Hello world! Beautiful", first.text);
        assertEquals(3, first.words.size());
        assertEquals(12340, first.words.get(0).startTime);
        assertEquals(12840, first.words.get(0).endTime);
        assertEquals("Hello ", first.words.get(0).text);
        assertEquals(12840, first.words.get(1).startTime);
        assertEquals(13440, first.words.get(1).endTime);
        assertEquals("world!", first.words.get(1).text);
    }

    @Test
    public void testParseKrc() {
        String krc = "[ar:YOASOBI]\n"
                   + "[ti:アイドル]\n"
                   + "[10000,4000]<0,800,0>Mu<800,1200,0>de<2000,2000,0>ki\n"
                   + "[15000,3000]<0,1000,0>no <1000,2000,0>smile";

        LyricsData data = SyllableLyricsParser.parseKrc(krc);
        assertEquals(LyricsData.Type.SYLLABLE, data.type);

        LyricsData.LyricsLine first = null;
        for (LyricsData.LyricsLine line : data.lines) {
            if (!line.isInterlude) {
                first = line;
                break;
            }
        }
        assertNotNull(first);
        assertEquals(10000, first.startTime);
        assertEquals(14000, first.endTime);
        assertEquals(3, first.words.size());
        // Absolute timing: lineStart (10000) + offset (0) = 10000
        assertEquals(10000, first.words.get(0).startTime);
        assertEquals(10800, first.words.get(0).endTime);
        assertEquals("Mu", first.words.get(0).text);

        // Word 2: 10000 + 800 = 10800, duration 1200 -> 12000
        assertEquals(10800, first.words.get(1).startTime);
        assertEquals(12000, first.words.get(1).endTime);
        assertEquals("de", first.words.get(1).text);

        // Word 3: 10000 + 2000 = 12000, duration 2000 -> 14000
        assertEquals(12000, first.words.get(2).startTime);
        assertEquals(14000, first.words.get(2).endTime);
        assertEquals("ki", first.words.get(2).text);
    }

    @Test
    public void testKrcDecryptRoundTrip() throws Exception {
        String originalText = "[1000,2000]<0,1000,0>Hello <1000,1000,0>world";
        byte[] textBytes = originalText.getBytes(StandardCharsets.UTF_8);

        // Compress via Deflater
        Deflater deflater = new Deflater();
        deflater.setInput(textBytes);
        deflater.finish();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[256];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            baos.write(buf, 0, n);
        }
        deflater.end();
        byte[] compressed = baos.toByteArray();

        // XOR with key and prepend "krc1"
        byte[] rawKrc = new byte[4 + compressed.length];
        rawKrc[0] = 'k';
        rawKrc[1] = 'r';
        rawKrc[2] = 'c';
        rawKrc[3] = '1';

        for (int i = 0; i < compressed.length; i++) {
            rawKrc[4 + i] = (byte) (compressed[i] ^ KRC_KEY[i % KRC_KEY.length]);
        }

        // Test decrypt
        String decrypted = SyllableLyricsParser.decryptKrcToString(rawKrc);
        assertEquals(originalText, decrypted);
    }

    @Test
    public void testParseTtml() {
        String ttml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<tt xmlns=\"http://www.w3.org/ns/ttml\">\n"
                    + "  <body>\n"
                    + "    <div>\n"
                    + "      <p begin=\"00:10.500\" end=\"00:14.000\">\n"
                    + "        <span begin=\"00:10.500\" end=\"00:11.200\">Never </span>\n"
                    + "        <span begin=\"00:11.200\" end=\"00:12.000\">gonna </span>\n"
                    + "        <span begin=\"00:12.000\" end=\"00:13.500\">give you up</span>\n"
                    + "      </p>\n"
                    + "    </div>\n"
                    + "  </body>\n"
                    + "</tt>";

        LyricsData data = SyllableLyricsParser.parseTtml(ttml);
        assertEquals(LyricsData.Type.SYLLABLE, data.type);

        LyricsData.LyricsLine first = null;
        for (LyricsData.LyricsLine l : data.lines) {
            if (!l.isInterlude) {
                first = l;
                break;
            }
        }
        assertNotNull(first);
        assertEquals(10500, first.startTime);
        assertEquals(14000, first.endTime);
        assertEquals("Never gonna give you up", first.text);
        assertEquals(3, first.words.size());
        assertEquals(10500, first.words.get(0).startTime);
        assertEquals(11200, first.words.get(0).endTime);
    }

    @Test
    public void testParseElrc() {
        String elrc = "[00:05.00]<00:05.00>First <00:05.80>word <00:06.50>end\n"
                    + "[00:08.00]<00:08.00>Next <00:09.00>line";

        LyricsData data = SyllableLyricsParser.parseElrc(elrc);
        assertEquals(LyricsData.Type.SYLLABLE, data.type);

        LyricsData.LyricsLine first = null;
        for (LyricsData.LyricsLine l : data.lines) {
            if (!l.isInterlude) {
                first = l;
                break;
            }
        }
        assertNotNull(first);
        assertEquals(5000, first.startTime);
        assertEquals(3, first.words.size());
        assertEquals(5000, first.words.get(0).startTime);
        assertEquals(5800, first.words.get(0).endTime);
        assertEquals("First ", first.words.get(0).text);
    }

    @Test
    public void testParseLrc() {
        String lrc = "[00:05.00]First line text\n"
                   + "[00:10.00]Second line text";

        LyricsData data = SyllableLyricsParser.parseLrc(lrc);
        assertEquals(LyricsData.Type.LINE, data.type);

        LyricsData.LyricsLine first = null;
        for (LyricsData.LyricsLine l : data.lines) {
            if (!l.isInterlude) {
                first = l;
                break;
            }
        }
        assertNotNull(first);
        assertEquals(5000, first.startTime);
        assertEquals(10000, first.endTime);
        assertEquals("First line text", first.text);
        // Words should be split for distributed timing
        assertFalse(first.words.isEmpty());
    }

    @Test
    public void testAutoDetection() {
        String yrc = "[1000,2000](1000,1000,0)YRC";
        String krc = "[1000,2000]<0,1000,0>KRC";
        String ttml = "<p begin=\"00:01.000\"><span>TTML</span></p>";
        String elrc = "[00:01.00]<00:01.00>ELRC";
        String lrc = "[00:01.00]LRC";

        assertEquals(LyricsData.Type.SYLLABLE, SyllableLyricsParser.parse(yrc, "auto").type);
        assertEquals(LyricsData.Type.SYLLABLE, SyllableLyricsParser.parse(krc, "auto").type);
        assertEquals(LyricsData.Type.SYLLABLE, SyllableLyricsParser.parse(ttml, "auto").type);
        assertEquals(LyricsData.Type.SYLLABLE, SyllableLyricsParser.parse(elrc, "auto").type);
        assertEquals(LyricsData.Type.LINE, SyllableLyricsParser.parse(lrc, "auto").type);
    }
}
