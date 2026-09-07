package com.lyricsync.app.lyrics.model;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * Universal Syllable Lyric Parser supporting:
 * - YRC (NetEase Cloud Music)
 * - KRC (Kugou Music, with XOR/zlib decryption)
 * - TTML / XML (Apple Music / Better Lyrics / Lyricify)
 * - ELRC (Enhanced LRC)
 * - Standard LRC (Line-synced fallback)
 */
public class SyllableLyricsParser {

    private static final int[] KRC_KEY = {
            0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
            0x51, 0x36, 0x31, 0x2d, 0xce, 0xd2, 0x6e, 0x69
    };

    private static final Pattern YRC_LINE_HEADER = Pattern.compile("^\\[(\\d+),(\\d+)\\]");
    private static final Pattern YRC_SYLLABLE = Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^()]*)");

    private static final Pattern KRC_LINE_HEADER = Pattern.compile("^\\[(\\d+),(\\d+)\\]");
    private static final Pattern KRC_SYLLABLE = Pattern.compile("<(\\d+),(\\d+),\\d+>([^<>]*)");

    private static final Pattern ELRC_LINE_HEADER = Pattern.compile("^\\[(\\d{2}):(\\d{2})\\.?(\\d{0,3})\\]");
    private static final Pattern ELRC_WORD = Pattern.compile("<(\\d{2}):(\\d{2})\\.?(\\d{0,3})>([^<]*)");

    private static final Pattern LRC_LINE = Pattern.compile("^\\[(\\d{2}):(\\d{2})\\.?(\\d{0,3})\\](.*)");

    private static final Pattern TTML_P_TAG = Pattern.compile(
            "<p\\b([^>]*)>(.*?)</p>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TTML_SPAN_TAG = Pattern.compile(
            "<span\\b([^>]*)>(.*?)</span>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    private static final Pattern ATTR_BEGIN = Pattern.compile("begin=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR_END = Pattern.compile("end=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /**
     * Decrypts a raw Kugou KRC byte array.
     * Skips 4-byte 'krc1' magic, XORs with 16-byte key, and decompresses via zlib Inflater.
     */
    public static byte[] decryptKrc(byte[] rawKrc) throws Exception {
        if (rawKrc == null || rawKrc.length < 4) {
            throw new IllegalArgumentException("Invalid KRC byte array: length < 4");
        }

        int offset = 0;
        if (rawKrc[0] == 'k' && rawKrc[1] == 'r' && rawKrc[2] == 'c' && rawKrc[3] == '1') {
            offset = 4;
        }

        byte[] xored = new byte[rawKrc.length - offset];
        for (int i = 0; i < xored.length; i++) {
            xored[i] = (byte) (rawKrc[i + offset] ^ KRC_KEY[i % KRC_KEY.length]);
        }

        try (java.util.zip.InflaterInputStream in = new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(xored));
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }

    public static String decryptKrcToString(byte[] rawKrc) throws Exception {
        return new String(decryptKrc(rawKrc), StandardCharsets.UTF_8);
    }

    /**
     * Universal entry point: parses raw lyric text with an optional format hint.
     * If format is null or "auto", inspects text to detect format automatically.
     */
    public static LyricsData parse(String raw, String formatHint) {
        if (raw == null || raw.trim().isEmpty()) {
            return new LyricsData(LyricsData.Type.LINE);
        }

        String fmt = formatHint != null ? formatHint.trim().toLowerCase() : "auto";
        switch (fmt) {
            case "yrc":
                return parseYrc(raw);
            case "krc":
                return parseKrc(raw);
            case "ttml":
            case "xml":
                return parseTtml(raw);
            case "elrc":
                return parseElrc(raw);
            case "lrc":
                return parseLrc(raw);
            case "auto":
            default:
                return parseAuto(raw);
        }
    }

    public static LyricsData parseAuto(String raw) {
        if (raw == null || raw.isEmpty()) return new LyricsData(LyricsData.Type.LINE);
        String trimmed = raw.trim();
        if (trimmed.startsWith("<") || trimmed.contains("<tt") || trimmed.contains("<p ") || trimmed.contains("<p>")) {
            return parseTtml(raw);
        }
        if (KRC_SYLLABLE.matcher(raw).find()) return parseKrc(raw);
        if (YRC_SYLLABLE.matcher(raw).find()) return parseYrc(raw);
        if (ELRC_WORD.matcher(raw).find()) return parseElrc(raw);
        return parseLrc(raw);
    }

    /**
     * Parses NetEase YRC format into Type.SYLLABLE.
     * Line header: [lineStart,lineDuration]
     * Words: (wordStart,wordDuration,0)wordText
     */
    public static LyricsData parseYrc(String raw) {
        LyricsData lyrics = new LyricsData(LyricsData.Type.SYLLABLE);
        if (raw == null || raw.isEmpty()) return lyrics;

        for (String fullLine : raw.split("\n")) {
            String line = fullLine.trim();
            if (line.isEmpty() || line.startsWith("{")) continue;

            Matcher headerMatcher = YRC_LINE_HEADER.matcher(line);
            if (!headerMatcher.find()) continue;

            long lineStart = Long.parseLong(headerMatcher.group(1));
            long lineDuration = Long.parseLong(headerMatcher.group(2));
            long lineEnd = lineStart + lineDuration;

            String contentPart = line.substring(headerMatcher.end());
            Matcher wordMatcher = YRC_SYLLABLE.matcher(contentPart);

            StringBuilder lineText = new StringBuilder();
            LyricsData.LyricsLine lyricsLine = new LyricsData.LyricsLine(lineStart, lineEnd, "");

            while (wordMatcher.find()) {
                long start = Long.parseLong(wordMatcher.group(1));
                long duration = Long.parseLong(wordMatcher.group(2));
                String text = wordMatcher.group(3);

                if (text == null || text.isEmpty()) continue;

                lineText.append(text);
                lyricsLine.words.add(new LyricsData.Word(start, start + duration, text));
            }

            if (lineText.length() > 0) {
                lyricsLine.text = lineText.toString().trim();
                lyrics.lines.add(lyricsLine);
            }
        }

        if (lyrics.lines.isEmpty()) {
            lyrics.type = LyricsData.Type.LINE;
        } else {
            LyricsData.insertInterludes(lyrics, 3000);
        }
        return lyrics;
    }

    /**
     * Parses Kugou KRC format into Type.SYLLABLE.
     * Line header: [lineStart,lineDuration]
     * Words: <wordOffset,wordDuration,0>wordText
     * Absolute timestamps: startTime = lineStart + wordOffset, endTime = startTime + wordDuration.
     */
    public static LyricsData parseKrc(String raw) {
        LyricsData lyrics = new LyricsData(LyricsData.Type.SYLLABLE);
        if (raw == null || raw.isEmpty()) return lyrics;

        for (String fullLine : raw.split("\n")) {
            String line = fullLine.trim();
            if (line.isEmpty() || line.startsWith("[ar:") || line.startsWith("[ti:") || line.startsWith("[by:")
                    || line.startsWith("[hash:") || line.startsWith("[al:") || line.startsWith("[sign:")
                    || line.startsWith("[qq:") || line.startsWith("[total:") || line.startsWith("[offset:")
                    || line.startsWith("[id:")) {
                continue;
            }

            Matcher headerMatcher = KRC_LINE_HEADER.matcher(line);
            if (!headerMatcher.find()) continue;

            long lineStart = Long.parseLong(headerMatcher.group(1));
            long lineDuration = Long.parseLong(headerMatcher.group(2));
            long lineEnd = lineStart + lineDuration;

            String contentPart = line.substring(headerMatcher.end());
            Matcher wordMatcher = KRC_SYLLABLE.matcher(contentPart);

            StringBuilder lineText = new StringBuilder();
            LyricsData.LyricsLine lyricsLine = new LyricsData.LyricsLine(lineStart, lineEnd, "");

            while (wordMatcher.find()) {
                long offset = Long.parseLong(wordMatcher.group(1));
                long duration = Long.parseLong(wordMatcher.group(2));
                String text = wordMatcher.group(3);

                if (text == null) continue;

                long start = lineStart + offset;
                long end = start + duration;

                lineText.append(text);
                lyricsLine.words.add(new LyricsData.Word(start, end, text));
            }

            if (lineText.length() > 0) {
                lyricsLine.text = lineText.toString().trim();
                lyrics.lines.add(lyricsLine);
            }
        }

        if (lyrics.lines.isEmpty()) {
            lyrics.type = LyricsData.Type.LINE;
        } else {
            LyricsData.insertInterludes(lyrics, 3000);
        }
        return lyrics;
    }

    /**
     * Parses TTML/XML syllable or line lyrics.
     * Line: <p begin="..." end="...">
     * Syllable: <span begin="..." end="...">word</span>
     */
    public static LyricsData parseTtml(String raw) {
        LyricsData lyrics = new LyricsData(LyricsData.Type.SYLLABLE);
        if (raw == null || raw.isEmpty()) return lyrics;

        Matcher pMatcher = TTML_P_TAG.matcher(raw);
        while (pMatcher.find()) {
            String pAttrs = pMatcher.group(1);
            String pBody = pMatcher.group(2);

            long lineStart = extractTime(pAttrs, ATTR_BEGIN, 0);
            long lineEnd = extractTime(pAttrs, ATTR_END, lineStart + 5000);

            LyricsData.LyricsLine lyricsLine = new LyricsData.LyricsLine(lineStart, lineEnd, "");
            StringBuilder lineText = new StringBuilder();

            Matcher spanMatcher = TTML_SPAN_TAG.matcher(pBody);
            boolean hasSpans = false;

            while (spanMatcher.find()) {
                hasSpans = true;
                String spanAttrs = spanMatcher.group(1);
                String spanContent = spanMatcher.group(2);
                String text = stripHtmlTags(spanContent);

                if (text.isEmpty()) continue;

                long spanStart = extractTime(spanAttrs, ATTR_BEGIN, lineStart);
                long spanEnd = extractTime(spanAttrs, ATTR_END, spanStart + 500);

                lineText.append(text);
                lyricsLine.words.add(new LyricsData.Word(spanStart, spanEnd, text));
            }

            if (!hasSpans) {
                // Line-level without spans
                String cleanText = stripHtmlTags(pBody).trim();
                if (!cleanText.isEmpty()) {
                    lyricsLine.text = cleanText;
                    splitLineIntoWords(lyricsLine);
                    lyrics.lines.add(lyricsLine);
                }
            } else if (lineText.length() > 0) {
                lyricsLine.text = lineText.toString().trim();
                // Update lineEnd if last word ends later
                if (!lyricsLine.words.isEmpty()) {
                    long lastWordEnd = lyricsLine.words.get(lyricsLine.words.size() - 1).endTime;
                    if (lastWordEnd > lyricsLine.endTime) {
                        lyricsLine.endTime = lastWordEnd;
                    }
                }
                lyrics.lines.add(lyricsLine);
            }
        }

        if (lyrics.lines.isEmpty()) {
            lyrics.type = LyricsData.Type.LINE;
        } else {
            // Check if any line has words
            boolean anySyllables = false;
            for (LyricsData.LyricsLine l : lyrics.lines) {
                if (l.words != null && !l.words.isEmpty()) {
                    anySyllables = true;
                    break;
                }
            }
            lyrics.type = anySyllables ? LyricsData.Type.SYLLABLE : LyricsData.Type.LINE;
            LyricsData.insertInterludes(lyrics, 3000);
        }
        return lyrics;
    }

    /**
     * Parses Enhanced LRC (ELRC) format.
     * Line: [mm:ss.xx]<mm:ss.xx>word1 <mm:ss.xx>word2
     */
    public static LyricsData parseElrc(String raw) {
        LyricsData lyrics = new LyricsData(LyricsData.Type.SYLLABLE);
        if (raw == null || raw.isEmpty()) return lyrics;

        for (String fullLine : raw.split("\n")) {
            String line = fullLine.trim();
            if (line.isEmpty()) continue;

            Matcher headerMatcher = ELRC_LINE_HEADER.matcher(line);
            if (!headerMatcher.find()) continue;

            long lineStart = parseLrcTimestamp(headerMatcher.group(1), headerMatcher.group(2), headerMatcher.group(3));
            String contentPart = line.substring(headerMatcher.end());

            Matcher wordMatcher = ELRC_WORD.matcher(contentPart);
            List<LyricsData.Word> rawWords = new ArrayList<>();
            StringBuilder lineText = new StringBuilder();

            while (wordMatcher.find()) {
                long wordStart = parseLrcTimestamp(wordMatcher.group(1), wordMatcher.group(2), wordMatcher.group(3));
                String text = wordMatcher.group(4);
                if (text == null) text = "";

                lineText.append(text);
                rawWords.add(new LyricsData.Word(wordStart, wordStart + 500, text));
            }

            if (!rawWords.isEmpty()) {
                // Set word end times to next word start time
                for (int i = 0; i < rawWords.size() - 1; i++) {
                    rawWords.get(i).endTime = rawWords.get(i + 1).startTime;
                }
                long lineEnd = rawWords.get(rawWords.size() - 1).endTime;
                LyricsData.LyricsLine lyricsLine = new LyricsData.LyricsLine(lineStart, lineEnd, lineText.toString().trim());
                lyricsLine.words = rawWords;
                lyrics.lines.add(lyricsLine);
            }
        }

        if (lyrics.lines.isEmpty()) {
            return parseLrc(raw);
        }

        // Adjust line end times
        for (int i = 0; i < lyrics.lines.size() - 1; i++) {
            lyrics.lines.get(i).endTime = lyrics.lines.get(i + 1).startTime;
            if (!lyrics.lines.get(i).words.isEmpty()) {
                LyricsData.Word lastWord = lyrics.lines.get(i).words.get(lyrics.lines.get(i).words.size() - 1);
                lastWord.endTime = lyrics.lines.get(i).endTime;
            }
        }
        if (!lyrics.lines.isEmpty()) {
            LyricsData.LyricsLine last = lyrics.lines.get(lyrics.lines.size() - 1);
            if (!last.words.isEmpty()) {
                last.endTime = last.words.get(last.words.size() - 1).startTime + 3000;
                last.words.get(last.words.size() - 1).endTime = last.endTime;
            }
        }

        LyricsData.insertInterludes(lyrics, 3000);
        return lyrics;
    }

    /**
     * Parses Standard LRC format into Type.LINE with distributed word timing.
     */
    public static LyricsData parseLrc(String raw) {
        LyricsData lyrics = new LyricsData(LyricsData.Type.LINE);
        if (raw == null || raw.isEmpty()) return lyrics;

        for (String fullLine : raw.split("\n")) {
            String line = fullLine.trim();
            if (line.isEmpty() || line.startsWith("[ar:") || line.startsWith("[ti:") || line.startsWith("[al:")
                    || line.startsWith("[by:") || line.startsWith("[offset:") || line.startsWith("[length:")) {
                continue;
            }
            Matcher matcher = LRC_LINE.matcher(line);
            if (!matcher.find()) continue;

            long start = parseLrcTimestamp(matcher.group(1), matcher.group(2), matcher.group(3));
            String text = matcher.group(4).trim();
            if (text.isEmpty()) continue;

            LyricsData.LyricsLine lyricsLine = new LyricsData.LyricsLine(start, start + 5000, text);
            lyrics.lines.add(lyricsLine);
        }

        // Derive line end times from next line start
        for (int i = 0; i < lyrics.lines.size() - 1; i++) {
            lyrics.lines.get(i).endTime = lyrics.lines.get(i + 1).startTime;
        }
        if (!lyrics.isEmpty()) {
            lyrics.lines.get(lyrics.lines.size() - 1).endTime =
                    lyrics.lines.get(lyrics.lines.size() - 1).startTime + 5000;
        }

        for (LyricsData.LyricsLine ll : lyrics.lines) {
            splitLineIntoWords(ll);
        }

        LyricsData.insertInterludes(lyrics, 3000);
        return lyrics;
    }

    public static void splitLineIntoWords(LyricsData.LyricsLine line) {
        if (line == null || line.text == null || line.text.isEmpty()) return;

        String[] tokens = line.text.split("\\s+");
        if (tokens.length == 0) return;

        long duration = Math.max(line.endTime - line.startTime, 500);
        int totalChars = 0;
        for (String token : tokens) {
            totalChars += token.length();
        }
        if (totalChars == 0) return;

        long currentStart = line.startTime;
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            long tokenDuration = (duration * token.length()) / totalChars;
            if (i == tokens.length - 1) {
                tokenDuration = Math.max(line.endTime - currentStart, 100);
            }
            line.words.add(new LyricsData.Word(currentStart, currentStart + tokenDuration, token));
            currentStart += tokenDuration;
        }
    }

    public static long parseLrcTimestamp(String minStr, String secStr, String fracStr) {
        long min = Long.parseLong(minStr);
        long sec = Long.parseLong(secStr);
        long ms = 0;
        if (fracStr != null && !fracStr.isEmpty()) {
            if (fracStr.length() == 1) {
                ms = Long.parseLong(fracStr) * 100;
            } else if (fracStr.length() == 2) {
                ms = Long.parseLong(fracStr) * 10;
            } else if (fracStr.length() >= 3) {
                ms = Long.parseLong(fracStr.substring(0, 3));
            }
        }
        return min * 60000L + sec * 1000L + ms;
    }

    public static long parseTtmlTimestamp(String time) {
        if (time == null || time.isEmpty()) return 0;
        time = time.trim().toLowerCase();
        if (time.endsWith("ms")) {
            try {
                return (long) Double.parseDouble(time.substring(0, time.length() - 2));
            } catch (NumberFormatException ignored) {}
        }
        if (time.endsWith("s")) {
            try {
                return (long) (Double.parseDouble(time.substring(0, time.length() - 1)) * 1000);
            } catch (NumberFormatException ignored) {}
        }
        if (time.contains(":")) {
            String[] parts = time.split(":");
            try {
                if (parts.length == 2) {
                    long min = Long.parseLong(parts[0]);
                    double sec = Double.parseDouble(parts[1]);
                    return (long) (min * 60000 + sec * 1000);
                } else if (parts.length == 3) {
                    long hr = Long.parseLong(parts[0]);
                    long min = Long.parseLong(parts[1]);
                    double sec = Double.parseDouble(parts[2]);
                    return (long) (hr * 3600000 + min * 60000 + sec * 1000);
                }
            } catch (NumberFormatException ignored) {}
        }
        try {
            return (long) Double.parseDouble(time);
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    private static long extractTime(String attrs, Pattern pattern, long defaultVal) {
        if (attrs == null) return defaultVal;
        Matcher m = pattern.matcher(attrs);
        if (m.find()) {
            return parseTtmlTimestamp(m.group(1));
        }
        return defaultVal;
    }

    private static String stripHtmlTags(String text) {
        if (text == null) return "";
        return text.replaceAll("<[^>]*>", "");
    }
}
