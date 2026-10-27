package com.lyricsync.app.lyrics.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;

/**
 * Parsers for the lyric formats the providers return. Pure JVM.
 * <ul>
 *   <li>YRC (Netease): {@code [lineStart,dur](wordStart,dur,0)word…} — absolute word times</li>
 *   <li>KRC (Kugou, XOR+zlib encrypted): {@code [lineStart,dur]<offset,dur,0>word…} — offsets</li>
 *   <li>LRC: {@code [mm:ss.xx]line}, repeated tags allowed — line-synced</li>
 * </ul>
 * A word-timed format with no usable word lines falls back to LINE.
 */
public final class SyllableLyricsParser {

    private static final int[] KRC_KEY = {
            0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
            0x51, 0x36, 0x31, 0x2d, 0xce, 0xd2, 0x6e, 0x69};

    private static final Pattern LINE_HEADER = Pattern.compile("^\\[(\\d+),(\\d+)]");
    private static final Pattern YRC_WORD = Pattern.compile("\\((\\d+),(\\d+),-?\\d+\\)([^()]*)");
    private static final Pattern KRC_WORD = Pattern.compile("<(\\d+),(\\d+),-?\\d+>([^<>]*)");
    private static final Pattern LRC_TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final long INTERLUDE_GAP_MS = 3000;

    private SyllableLyricsParser() {}

    /** Kugou KRC: optional "krc1" magic, XOR with the fixed key, then zlib. */
    public static byte[] decryptKrc(byte[] raw) throws Exception {
        if (raw == null || raw.length < 4) throw new IllegalArgumentException("KRC payload too short");
        int off = raw[0] == 'k' && raw[1] == 'r' && raw[2] == 'c' && raw[3] == '1' ? 4 : 0;
        byte[] x = new byte[raw.length - off];
        for (int i = 0; i < x.length; i++) x[i] = (byte) (raw[i + off] ^ KRC_KEY[i % KRC_KEY.length]);
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(x));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) != -1; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    public static LyricsData parseYrc(String raw) {
        return parseWordTimed(raw, YRC_WORD, false);
    }

    public static LyricsData parseKrc(String raw) {
        return parseWordTimed(raw, KRC_WORD, true);
    }

    /** YRC and KRC share one shape; KRC word times are offsets from the line start. */
    private static LyricsData parseWordTimed(String raw, Pattern word, boolean relative) {
        LyricsData lyrics = new LyricsData(LyricsData.Type.SYLLABLE);
        if (raw == null) return asLine(lyrics);
        for (String rawLine : raw.split("\\r?\\n")) {
            String line = rawLine.trim();
            Matcher h = LINE_HEADER.matcher(line);
            if (!h.find()) continue; // metadata ([ti:], [ar:], {json} …)
            long lineStart = Long.parseLong(h.group(1));
            LyricsData.LyricsLine ll = new LyricsData.LyricsLine(lineStart, lineStart + Long.parseLong(h.group(2)), "");
            StringBuilder text = new StringBuilder();
            Matcher w = word.matcher(line.substring(h.end()));
            while (w.find()) {
                String t = w.group(3);
                if (t.isEmpty()) continue;
                long start = Long.parseLong(w.group(1)) + (relative ? lineStart : 0);
                ll.words.add(new LyricsData.Word(start, start + Long.parseLong(w.group(2)), t));
                text.append(t);
            }
            ll.text = text.toString().trim();
            if (!ll.text.isEmpty()) lyrics.lines.add(ll);
        }
        if (lyrics.lines.isEmpty()) return asLine(lyrics);
        LyricsData.insertInterludes(lyrics, INTERLUDE_GAP_MS);
        return lyrics;
    }

    /**
     * Standard LRC → Type.LINE with words timed by length. Handles repeated time tags
     * ("[00:12.00][01:30.00]chorus"), 1–3 digit fractions, "mm:ss:xx", metadata tags and
     * out-of-order lines.
     */
    public static LyricsData parseLrc(String raw) {
        LyricsData lyrics = new LyricsData(LyricsData.Type.LINE);
        if (raw == null) return lyrics;
        for (String rawLine : raw.split("\\r?\\n")) {
            String line = rawLine.trim();
            Matcher tag = LRC_TIME.matcher(line);
            List<Long> starts = new ArrayList<>();
            int textStart = 0;
            while (tag.find() && tag.start() == textStart) {
                starts.add(timestamp(tag.group(1), tag.group(2), tag.group(3)));
                textStart = tag.end();
            }
            String text = line.substring(textStart).trim();
            if (starts.isEmpty() || text.isEmpty()) continue;
            for (long s : starts) lyrics.lines.add(new LyricsData.LyricsLine(s, s + 5000, text));
        }
        lyrics.lines.sort((a, b) -> Long.compare(a.startTime, b.startTime));
        for (int i = 0; i + 1 < lyrics.lines.size(); i++) {
            lyrics.lines.get(i).endTime = lyrics.lines.get(i + 1).startTime;
        }
        for (LyricsData.LyricsLine ll : lyrics.lines) splitLineIntoWords(ll);
        LyricsData.insertInterludes(lyrics, INTERLUDE_GAP_MS);
        return lyrics;
    }

    /** Spreads a line's duration over its words in proportion to their length. */
    public static void splitLineIntoWords(LyricsData.LyricsLine line) {
        if (line == null || line.text == null || line.text.trim().isEmpty()) return;
        String[] tokens = line.text.trim().split("\\s+");
        int chars = 0;
        for (String t : tokens) chars += t.length();
        long duration = Math.max(line.endTime - line.startTime, 500);
        long t0 = line.startTime;
        for (int i = 0; i < tokens.length; i++) {
            long d = i == tokens.length - 1 ? Math.max(line.endTime - t0, 100) : duration * tokens[i].length() / chars;
            line.words.add(new LyricsData.Word(t0, t0 + d, tokens[i]));
            t0 += d;
        }
    }

    /** "mm", "ss", fraction of 1–3 digits (".5" = 500 ms, ".05" = 50 ms, ".005" = 5 ms). */
    static long timestamp(String min, String sec, String frac) {
        long ms = 0;
        if (frac != null && !frac.isEmpty()) {
            ms = Long.parseLong(frac);
            if (frac.length() == 1) ms *= 100;
            else if (frac.length() == 2) ms *= 10;
        }
        return Long.parseLong(min) * 60_000L + Long.parseLong(sec) * 1000L + ms;
    }

    private static LyricsData asLine(LyricsData empty) {
        empty.type = LyricsData.Type.LINE;
        return empty;
    }
}
