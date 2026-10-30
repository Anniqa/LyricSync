package com.lyricsync.app.lyrics.model;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** New word-timed formats, shaped like the real Better Lyrics / QQ responses, and format detection. */
public class LyricsFormatTest {

    /** Apple TTML as Better Lyrics serves it: syllables split inside a word, x-bg, no pretty-printing. */
    private static final String TTML = "<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" "
            + "xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" itunes:timing=\"Word\" xml:lang=\"en\"><head><metadata>"
            + "<transliterations><transliteration><text for=\"L1\"><span begin=\"1\" end=\"2\">ignored</span></text></transliteration></transliterations>"
            + "</metadata></head><body dur=\"3:21.570\"><div begin=\"27.395\" end=\"2:23.809\">"
            + "<p begin=\"30.189\" end=\"32.529\" itunes:key=\"L2\" ttm:agent=\"v1\"><span begin=\"30.189\" end=\"30.396\">I've</span> "
            + "<span begin=\"31.498\" end=\"31.839\">long</span> <span begin=\"31.839\" end=\"31.996\">e</span><span begin=\"31.996\" end=\"32.529\">nough</span></p>"
            + "<p begin=\"2:21.228\" end=\"2:23.809\"><span begin=\"2:21.228\" end=\"2:21.445\">you</span> <span begin=\"2:21.445\" end=\"2:22.261\">know</span> "
            + "<span ttm:role=\"x-bg\"><span begin=\"2:21.720\" end=\"2:22.154\">(Back</span> <span begin=\"2:22.889\" end=\"2:23.809\">know)</span></span></p>"
            + "</div></body></tt>";

    @Test
    public void ttmlKeepsSyllablesWordsAndBackgroundVocals() {
        LyricsData l = TtmlParser.parse(TTML);
        assertEquals(LyricsData.Type.SYLLABLE, l.type);
        List<LyricsData.LyricsLine> sung = sung(l);
        assertEquals(2, sung.size());
        LyricsData.LyricsLine a = sung.get(0);
        assertEquals("I've long enough", a.text);
        assertEquals(30189, a.startTime);
        assertEquals(4, a.words.size());
        assertTrue("e+nough is one word", a.words.get(2).isPartOfWord);
        assertFalse(a.words.get(1).isPartOfWord);
        LyricsData.LyricsLine b = sung.get(1);
        assertEquals("you know", b.text);
        assertEquals(141228, b.startTime);
        assertNotNull(b.backgroundVocals);
        assertEquals("(Back know)", b.backgroundVocals.get(0).text);
        assertEquals(141720, b.backgroundVocals.get(0).words.get(0).startTime);
    }

    @Test
    public void ttmlCjkSyllablesStandAloneAndLineTimingDegrades() {
        LyricsData ja = TtmlParser.parse("<tt itunes:timing=\"Word\"><body><div><p begin=\"1.241\" end=\"2.651\">"
                + "<span begin=\"1.241\" end=\"1.635\">夢</span><span begin=\"1.635\" end=\"2.152\">なら</span></p></div></body></tt>");
        assertEquals("夢なら", sung(ja).get(0).text);
        assertFalse(sung(ja).get(0).words.get(0).isPartOfWord);

        LyricsData line = TtmlParser.parse("<tt itunes:timing=\"Line\"><body><div><p begin=\"00:00:05.000\" end=\"00:00:07.500\">"
                + "Whole line here</p></div></body></tt>");
        assertEquals(LyricsData.Type.LINE, line.type);
        assertEquals(5000, sung(line).get(0).startTime);
        assertEquals(3, sung(line).get(0).words.size());
    }

    @Test
    public void qrcInEnvelopeSkipsTimedSpacesAndUnescapes() {
        String qrc = "<?xml version=\"1.0\" encoding=\"utf-8\"?><QrcInfos><LyricInfo LyricCount=\"1\">"
                + "<Lyric_1 LyricType=\"1\" LyricContent=\"[ti:Song]\n[offset:0]\n"
                + "[292333,1910]Let (292333,136)it (292469,97)&quot;go&quot;(292566,202)\n"
                + "[300000,1000]This(300000,500) (300500,10)Is(300510,490)\n\"/></LyricInfo></QrcInfos>";
        LyricsData l = SyllableLyricsParser.parseQrc(qrc);
        assertEquals(LyricsData.Type.SYLLABLE, l.type);
        assertEquals("Let it \"go\"", sung(l).get(0).text);
        assertEquals(3, sung(l).get(0).words.size());
        assertEquals(292469, sung(l).get(0).words.get(1).startTime);
        assertEquals("This Is", sung(l).get(1).text);
        assertEquals(2, sung(l).get(1).words.size());
    }

    @Test
    public void enhancedLrcTimesEachWord() {
        LyricsData l = LyricsFormat.parse("[ar:x]\n[00:12.00]<00:12.00>Hello <00:12.50>wor<00:12.80>ld <00:13.40>\n"
                + "[00:15.00]plain line\n");
        assertEquals(LyricsData.Type.SYLLABLE, l.type);
        LyricsData.LyricsLine a = sung(l).get(0);
        assertEquals("Hello world", a.text);
        assertEquals(3, a.words.size());
        assertEquals(12500, a.words.get(1).startTime);
        assertEquals(12800, a.words.get(1).endTime);
        assertTrue(a.words.get(1).isPartOfWord);
        assertEquals(2, sung(l).get(1).words.size());
    }

    @Test
    public void detectsEveryFormat() {
        assertEquals(LyricsFormat.Kind.TTML, LyricsFormat.detect(TTML));
        assertEquals(LyricsFormat.Kind.QRC, LyricsFormat.detect("[0,1]a(0,1)"));
        assertEquals(LyricsFormat.Kind.KRC, LyricsFormat.detect("[0,1]<0,1,0>a"));
        assertEquals(LyricsFormat.Kind.YRC, LyricsFormat.detect("[0,1](0,1,0)a"));
        assertEquals(LyricsFormat.Kind.ENHANCED_LRC, LyricsFormat.detect("[00:01.00]<00:01.00>a"));
        assertEquals(LyricsFormat.Kind.LRC, LyricsFormat.detect("[00:01.00]a"));
        assertEquals(LyricsFormat.Kind.PLAIN, LyricsFormat.detect("just\nwords"));
        assertEquals(2, LyricsFormat.parse("just\n\nwords").lines.size());
    }

    private static List<LyricsData.LyricsLine> sung(LyricsData l) {
        List<LyricsData.LyricsLine> out = new java.util.ArrayList<>();
        for (LyricsData.LyricsLine x : l.lines) if (!x.isInterlude) out.add(x);
        return out;
    }
}
