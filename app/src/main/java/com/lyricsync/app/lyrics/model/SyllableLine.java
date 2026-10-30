package com.lyricsync.app.lyrics.model;

import com.lyricsync.app.ai.transliteration.ScriptDetector;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds one lyric line from timed syllables, the shape Apple-style sources share
 * (TTML spans, LyricsPlus "syllabus"). A space after a syllable ends the word; adjacent
 * Latin syllables ("e"+"nough") stay one word via {@link LyricsData.Word#isPartOfWord}.
 * Background syllables go to the line's background vocals. Pure JVM.
 */
public final class SyllableLine {
    private final List<Syl> lead = new ArrayList<>(), bg = new ArrayList<>();

    private static final class Syl {
        final long begin, end;
        final String text;
        boolean spaceAfter;

        Syl(long begin, long end, String text, boolean spaceAfter) {
            this.begin = begin;
            this.end = end;
            this.text = text;
            this.spaceAfter = spaceAfter;
        }
    }

    /** A timed syllable; its own trailing whitespace ("I ") ends the word. Blank text is ignored. */
    public void syllable(long begin, long end, String raw, boolean background) {
        if (raw == null || raw.trim().isEmpty() || begin < 0) return;
        List<Syl> list = background ? bg : lead;
        if (Character.isWhitespace(raw.charAt(0)) && !list.isEmpty()) list.get(list.size() - 1).spaceAfter = true;
        list.add(new Syl(begin, Math.max(end, begin), raw.trim(), Character.isWhitespace(raw.charAt(raw.length() - 1))));
    }

    /** Untimed whitespace between syllables (TTML writes {@code </span> <span>}). */
    public void space(boolean background) {
        List<Syl> list = background ? bg : lead;
        if (!list.isEmpty()) list.get(list.size() - 1).spaceAfter = true;
    }

    public boolean isEmpty() {
        return lead.isEmpty();
    }

    /** The line, or null when it has no text; {@code fallbackText} is used when no syllable was timed. */
    public LyricsData.LyricsLine build(long begin, long end, String fallbackText) {
        String text = lead.isEmpty() ? (fallbackText == null ? "" : fallbackText.trim()) : join(lead);
        if (text.isEmpty()) return null;
        if (begin < 0 && !lead.isEmpty()) begin = lead.get(0).begin;
        if (end < begin && !lead.isEmpty()) end = lead.get(lead.size() - 1).end;
        LyricsData.LyricsLine l = new LyricsData.LyricsLine(Math.max(begin, 0), Math.max(end, begin), text);
        l.words.addAll(words(lead));
        if (!bg.isEmpty()) {
            LyricsData.LyricsLine b = new LyricsData.LyricsLine(bg.get(0).begin, bg.get(bg.size() - 1).end, join(bg));
            b.words.addAll(words(bg));
            l.backgroundVocals = new ArrayList<>();
            l.backgroundVocals.add(b);
        }
        return l;
    }

    private static List<LyricsData.Word> words(List<Syl> syls) {
        List<LyricsData.Word> out = new ArrayList<>();
        for (int i = 0; i < syls.size(); i++) {
            Syl s = syls.get(i);
            LyricsData.Word w = new LyricsData.Word(s.begin, s.end, s.text);
            // CJK has no spaces: each syllable stands alone, like Kugou/Spicy words.
            w.isPartOfWord = !s.spaceAfter && i + 1 < syls.size() && !ScriptDetector.isNonLatin(s.text);
            out.add(w);
        }
        return out;
    }

    private static String join(List<Syl> syls) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < syls.size(); i++) {
            sb.append(syls.get(i).text);
            if (syls.get(i).spaceAfter && i + 1 < syls.size()) sb.append(' ');
        }
        return sb.toString();
    }
}
