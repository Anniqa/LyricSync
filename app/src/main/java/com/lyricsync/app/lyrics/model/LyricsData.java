package com.lyricsync.app.lyrics.model;

import java.util.ArrayList;
import java.util.List;

public class LyricsData {
    public enum Type { SYLLABLE, LINE, STATIC }

    public Type type;
    public List<LyricsLine> lines = new ArrayList<>();
    public String provider;
    public String translationLang;
    public boolean hasTransliteration;
    public String sourceScript;

    public LyricsData() {}

    public LyricsData(Type type) {
        this.type = type;
    }

    public static class LyricsLine {
        public long startTime;
        public long endTime;
        public String text;
        public String translation;
        public String romanji;
        public List<Word> words = new ArrayList<>();
        public boolean isInterlude;
        public List<LyricsLine> backgroundVocals;

        public LyricsLine() {}

        public LyricsLine(long startTime, long endTime, String text) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.text = text;
        }
    }

    public static void insertInterludes(LyricsData data, long gapThresholdMs) {
        if (data == null || data.lines == null || data.lines.size() < 2) return;

        // Check start: if first line starts late enough
        if (data.lines.get(0).startTime >= gapThresholdMs) {
            LyricsLine interlude = new LyricsLine(0, data.lines.get(0).startTime, "");
            interlude.isInterlude = true;
            data.lines.add(0, interlude);
        }

        // Scan gaps between consecutive lines (iterate backwards to keep indices stable)
        for (int i = data.lines.size() - 1; i > 0; i--) {
            LyricsLine prev = data.lines.get(i - 1);
            LyricsLine curr = data.lines.get(i);
            if (prev.isInterlude || curr.isInterlude) continue;

            long gap = curr.startTime - prev.endTime;
            if (gap >= gapThresholdMs) {
                LyricsLine interlude = new LyricsLine(prev.endTime, curr.startTime, "");
                interlude.isInterlude = true;
                data.lines.add(i, interlude);
            }
        }
    }
    public static void synthesizeWordTimings(LyricsData data) {
        if (data == null || data.lines == null || data.lines.isEmpty()) return;

        boolean hasTrueWordTiming = false;
        for (LyricsLine line : data.lines) {
            if (line.words != null && !line.words.isEmpty()) {
                for (Word w : line.words) {
                    if (w.endTime > w.startTime) {
                        hasTrueWordTiming = true;
                        break;
                    }
                }
            }
            if (hasTrueWordTiming) break;
        }
        if (hasTrueWordTiming && data.type == Type.SYLLABLE) return;

        for (LyricsLine line : data.lines) {
            if (line.isInterlude || line.text == null || line.text.trim().isEmpty()) continue;
            if (line.words != null && !line.words.isEmpty()) continue;

            String text = line.text.trim();
            List<String> tokens = new ArrayList<>();
            if (text.contains(" ")) {
                for (String t : text.split("\\s+")) {
                    if (!t.isEmpty()) tokens.add(t);
                }
            } else {
                // CJK or continuous script without spaces: chunk into 1-2 character units
                for (int i = 0; i < text.length(); i++) {
                    tokens.add(String.valueOf(text.charAt(i)));
                }
            }
            if (tokens.isEmpty()) continue;

            long lineDur = Math.max(800, line.endTime - line.startTime);
            int totalWeight = 0;
            for (String tok : tokens) totalWeight += Math.max(1, tok.length());

            long curStart = line.startTime;
            for (int i = 0; i < tokens.size(); i++) {
                String tok = tokens.get(i);
                long dur = (long) ((double) Math.max(1, tok.length()) / totalWeight * lineDur);
                long wEnd = (i == tokens.size() - 1) ? line.endTime : curStart + dur;
                Word w = new Word(curStart, wEnd, tok);
                w.isPartOfWord = false;
                line.words.add(w);
                curStart = wEnd;
            }
        }
        data.type = Type.SYLLABLE;
    }

    public static class Word {
        public long startTime;
        public long endTime;
        public String text;
        public String romanji;
        public boolean isPartOfWord;

        public Word() {}

        public Word(long startTime, long endTime, String text) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.text = text;
        }

        public Word(long startTime, long endTime, String text, String romanji) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.text = text;
            this.romanji = romanji;
        }
    }

    public boolean isEmpty() {
        return lines == null || lines.isEmpty();
    }
}
