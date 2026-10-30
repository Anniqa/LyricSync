package com.lyricsync.app.lyrics.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises a lyrics file by its content and parses it, for lyrics a user pastes or
 * an AI hands over: TTML, QRC, KRC (decrypted text), YRC, Enhanced LRC, LRC or plain
 * text. Pure JVM.
 */
public final class LyricsFormat {

    public enum Kind { TTML, QRC, KRC, YRC, ENHANCED_LRC, LRC, PLAIN }

    private static final Pattern QRC = Pattern.compile("(?m)^\\[\\d+,\\d+][^\\n]*?\\(\\d+,\\d+\\)");
    private static final Pattern KRC = Pattern.compile("(?m)^\\[\\d+,\\d+][^\\n]*?<\\d+,\\d+,-?\\d+>");
    private static final Pattern YRC = Pattern.compile("(?m)^\\[\\d+,\\d+][^\\n]*?\\(\\d+,\\d+,-?\\d+\\)");
    private static final Pattern LRC = Pattern.compile("(?m)^\\s*\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?]");
    private static final Pattern WORD_TAG = Pattern.compile("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>");
    private static final Pattern LINE_TAG = Pattern.compile("^\\s*\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");

    private LyricsFormat() {}

    public static Kind detect(String raw) {
        String s = raw == null ? "" : raw;
        if (s.contains("<tt") && s.contains("<p")) return Kind.TTML;
        if (s.contains("LyricContent=") || QRC.matcher(s).find()) return Kind.QRC;
        if (KRC.matcher(s).find()) return Kind.KRC;
        if (YRC.matcher(s).find()) return Kind.YRC;
        if (LRC.matcher(s).find()) return WORD_TAG.matcher(s).find() ? Kind.ENHANCED_LRC : Kind.LRC;
        return Kind.PLAIN;
    }

    /** Parses {@code raw} by its detected kind; never null, empty when nothing was readable. */
    public static LyricsData parse(String raw) {
        switch (detect(raw)) {
            case TTML: return TtmlParser.parse(raw);
            case QRC: return SyllableLyricsParser.parseQrc(raw);
            case KRC: return SyllableLyricsParser.parseKrc(raw);
            case YRC: return SyllableLyricsParser.parseYrc(raw);
            case ENHANCED_LRC: return parseEnhancedLrc(raw);
            case LRC: return SyllableLyricsParser.parseLrc(raw);
            default: return parsePlain(raw);
        }
    }

    /**
     * Enhanced LRC: {@code [00:12.00]<00:12.00>Word <00:12.40>by <00:12.70>word <00:13.20>}.
     * Each word runs from its tag to the next tag (or the next line). Lines without word
     * tags are timed by length, so a partly enhanced file still reads.
     */
    static LyricsData parseEnhancedLrc(String raw) {
        LyricsData out = new LyricsData(LyricsData.Type.SYLLABLE);
        String[] rows = raw.split("\\r?\\n");
        for (String row : rows) {
            Matcher lt = LINE_TAG.matcher(row);
            if (!lt.find()) continue;
            long lineStart = SyllableLyricsParser.timestamp(lt.group(1), lt.group(2), lt.group(3));
            String body = row.substring(lt.end());
            SyllableLine syl = new SyllableLine();
            Matcher w = WORD_TAG.matcher(body);
            long t = -1;
            int from = 0;
            StringBuilder plain = new StringBuilder();
            while (w.find()) {
                String text = body.substring(from, w.start());
                long next = SyllableLyricsParser.timestamp(w.group(1), w.group(2), w.group(3));
                if (t >= 0) syl.syllable(t, next, text, false);
                else plain.append(text);
                t = next;
                from = w.end();
            }
            String tail = body.substring(from);
            if (t >= 0 && !tail.trim().isEmpty()) syl.syllable(t, -1, tail, false); // end fixed below
            LyricsData.LyricsLine line = syl.build(lineStart, -1, (plain + tail).replaceAll("\\s+", " "));
            if (line != null) out.lines.add(line);
        }
        if (out.lines.isEmpty()) {
            out.type = LyricsData.Type.LINE;
            return out;
        }
        out.lines.sort((a, b) -> Long.compare(a.startTime, b.startTime));
        boolean anyWords = false;
        for (int i = 0; i < out.lines.size(); i++) {
            LyricsData.LyricsLine l = out.lines.get(i);
            long nextStart = i + 1 < out.lines.size() ? out.lines.get(i + 1).startTime : l.startTime + 5000;
            if (!l.words.isEmpty()) {
                anyWords = true;
                LyricsData.Word last = l.words.get(l.words.size() - 1);
                if (last.endTime <= last.startTime) last.endTime = Math.max(last.startTime + 300, Math.min(nextStart, last.startTime + 3000));
                l.endTime = Math.max(l.endTime, last.endTime);
            } else {
                l.endTime = nextStart;
                SyllableLyricsParser.splitLineIntoWords(l);
            }
        }
        if (!anyWords) out.type = LyricsData.Type.LINE;
        LyricsData.insertInterludes(out, 3000);
        return out;
    }

    /** Plain text → STATIC lines, five seconds apart (the overlay scrolls through them). */
    static LyricsData parsePlain(String raw) {
        LyricsData out = new LyricsData(LyricsData.Type.STATIC);
        long t = 0;
        for (String row : (raw == null ? "" : raw).split("\\r?\\n")) {
            String text = row.trim();
            if (text.isEmpty()) continue;
            out.lines.add(new LyricsData.LyricsLine(t, t + 5000, text));
            t += 5000;
        }
        return out;
    }
}
