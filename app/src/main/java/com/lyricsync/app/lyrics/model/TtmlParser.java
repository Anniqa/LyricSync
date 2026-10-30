package com.lyricsync.app.lyrics.model;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.SAXParserFactory;

/**
 * Apple Music style TTML ({@code <p>} line, {@code <span begin end>} syllable) → LyricsData.
 * Pure JVM: SAX from javax.xml, built into both Android and the JDK.
 *
 * <ul>
 *   <li>{@code itunes:timing="Word"} → SYLLABLE; {@code "Line"}, or lines with no timed
 *       spans, → LINE with words timed by length.</li>
 *   <li>Whitespace between spans separates words; adjacent spans ("e"+"nough") are one
 *       word, marked {@link LyricsData.Word#isPartOfWord} so the renderer keeps them together.</li>
 *   <li>{@code <span ttm:role="x-bg">} → the line's background vocals.</li>
 *   <li>Times: "SS.mmm", "M:SS.mmm", "H:MM:SS.mmm" and an optional "s" suffix.</li>
 * </ul>
 */
public final class TtmlParser {

    private TtmlParser() {}

    public static LyricsData parse(String ttml) {
        LyricsData out = new LyricsData(LyricsData.Type.SYLLABLE);
        if (ttml == null || ttml.trim().isEmpty()) return asLine(out);
        Reader reader = new Reader(out);
        try {
            SAXParserFactory f = SAXParserFactory.newInstance();
            f.setNamespaceAware(false); // attributes keep their prefixes: "itunes:timing", "ttm:role"
            f.newSAXParser().parse(new InputSource(new StringReader(ttml)), reader);
        } catch (Exception malformed) {
            // keep whatever lines parsed before the error
        }
        if (out.lines.isEmpty()) return asLine(out);
        boolean anyWords = false;
        for (LyricsData.LyricsLine l : out.lines) anyWords |= !l.words.isEmpty();
        if (!reader.wordTimed || !anyWords) {
            for (LyricsData.LyricsLine l : out.lines) {
                l.words.clear();
                SyllableLyricsParser.splitLineIntoWords(l);
            }
            out.type = LyricsData.Type.LINE;
        }
        LyricsData.insertInterludes(out, 3000);
        return out;
    }

    /** SAX events → lines. Text in {@code <head>} (translations, transliterations) is skipped. */
    private static final class Reader extends DefaultHandler {
        final LyricsData out;
        boolean wordTimed = true;
        int headDepth;
        Line line;

        Reader(LyricsData out) {
            this.out = out;
        }

        @Override
        public void startElement(String uri, String local, String name, Attributes a) {
            if ("tt".equals(name)) {
                wordTimed = !"line".equalsIgnoreCase(a.getValue("itunes:timing"));
            } else if ("head".equals(name)) {
                headDepth++;
            } else if (headDepth == 0 && "p".equals(name)) {
                line = new Line(time(a.getValue("begin")), time(a.getValue("end")));
            } else if (line != null && "span".equals(name)) {
                line.open(a.getValue("ttm:role"), a.getValue("begin"), a.getValue("end"));
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (line != null) line.text(new String(ch, start, length));
        }

        @Override
        public void endElement(String uri, String local, String name) {
            if ("head".equals(name)) {
                headDepth--;
            } else if (line != null && "span".equals(name)) {
                line.close();
            } else if (line != null && "p".equals(name)) {
                LyricsData.LyricsLine l = line.build();
                if (l != null) out.lines.add(l);
                line = null;
            }
        }
    }

    /** One {@code <p>} being read; span nesting tracks timed syllables and x-bg wrappers. */
    private static final class Line {
        final long begin, end;
        final SyllableLine syllables = new SyllableLine();
        final StringBuilder plain = new StringBuilder(); // untimed text (Line-timed files)
        final StringBuilder current = new StringBuilder();
        final List<Integer> stack = new ArrayList<>();    // per open span: 0 plain, 1 bg wrapper, 2 timed
        int bgDepth;
        long sBegin = -1, sEnd = -1;

        Line(long begin, long end) {
            this.begin = begin;
            this.end = end;
        }

        void open(String role, String b, String e) {
            boolean isBg = "x-bg".equals(role);
            if (isBg) bgDepth++;
            if (b != null && !b.isEmpty() && e != null && !e.isEmpty()) {
                sBegin = time(b);
                sEnd = time(e);
                current.setLength(0);
                stack.add(2);
            } else {
                stack.add(isBg ? 1 : 0);
            }
        }

        void text(String t) {
            if (t.isEmpty()) return;
            if (sBegin >= 0) current.append(t);
            else if (t.trim().isEmpty()) syllables.space(bgDepth > 0);
            else plain.append(t);
        }

        void close() {
            if (stack.isEmpty()) return;
            int kind = stack.remove(stack.size() - 1);
            if (kind == 2) {
                syllables.syllable(sBegin, sEnd, current.toString(), bgDepth > 0);
                sBegin = -1;
            } else if (kind == 1) {
                bgDepth--;
            }
        }

        LyricsData.LyricsLine build() {
            return syllables.build(begin, end, plain.toString());
        }
    }

    /** "12.5", "1:16.656", "00:01:30.500", "12.5s" → ms; -1 when unreadable. */
    static long time(String t) {
        if (t == null) return -1;
        String s = t.trim();
        if (s.endsWith("s") && !s.endsWith("ms")) s = s.substring(0, s.length() - 1);
        try {
            String[] p = s.split(":");
            double sec = Double.parseDouble(p[p.length - 1]);
            if (p.length >= 2) sec += Integer.parseInt(p[p.length - 2]) * 60;
            if (p.length >= 3) sec += Integer.parseInt(p[p.length - 3]) * 3600;
            return Math.round(sec * 1000);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static LyricsData asLine(LyricsData empty) {
        empty.type = LyricsData.Type.LINE;
        return empty;
    }
}
