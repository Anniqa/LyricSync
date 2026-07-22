package com.lyricsync.app.lyrics.model;

import java.util.ArrayList;
import java.util.List;

public class LyricsData {
    public enum Type { SYLLABLE, LINE, STATIC }

    public Type type;
    public List<LyricsLine> lines = new ArrayList<>();
    public String provider;

    public static class LyricsLine {
        public long startTime;
        public long endTime;
        public String text;
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

    public static class Word {
        public long startTime;
        public long endTime;
        public String text;

        public Word() {}

        public Word(long startTime, long endTime, String text) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.text = text;
        }
    }

    public boolean isEmpty() {
        return lines == null || lines.isEmpty();
    }
}
