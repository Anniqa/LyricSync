package com.lyricsync.app.ai.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lyricsync.app.ai.transliteration.AiSyllableTransliterationService;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.SyllableLyricsParser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The AI's read/write access to the lyrics: a JSON view of the lines, atomic multi-op
 * edits, and undo. Pure JVM, so the whole edit language is unit-tested.
 *
 * <p>Ops run in order on a copy; the lyrics change only when every op is valid. Each
 * line exposes everything the overlay draws: {@code text}, {@code romaji} (the Latin
 * line), {@code translation}, and {@code word_romaji} (one Latin label per word, drawn
 * over that word). Any Latin text may go into the romaji fields, e.g. a word-by-word
 * gloss.
 */
public final class LyricsEditor {

    public static final int MAX_UNDO = 20;
    public static final int MAX_READ_LINES = 60;
    private static final long DEFAULT_LINE_MS = 3000;
    private static final Gson GSON = new Gson();

    /** What an edit changed, so the overlay can switch the matching layers on. */
    public static final class Result {
        public final String summary;
        public final boolean wroteTranslation;
        public final boolean wroteRomaji;

        Result(String summary, boolean wroteTranslation, boolean wroteRomaji) {
            this.summary = summary;
            this.wroteTranslation = wroteTranslation;
            this.wroteRomaji = wroteRomaji;
        }
    }

    private final Deque<String> undo = new ArrayDeque<>();
    private LyricsData owner;
    private String original;

    // ---------------------------------------------------------------- edit / undo

    /** Applies {@code ops} to {@code data}; throws {@link IllegalArgumentException} (nothing changed) on a bad op. */
    public Result apply(LyricsData data, JsonArray ops) {
        if (data == null || data.lines == null) throw new IllegalArgumentException("No lyrics are loaded.");
        if (ops == null || ops.size() == 0) throw new IllegalArgumentException("ops is empty.");
        attach(data);
        String before = GSON.toJson(data);
        LyricsData work = GSON.fromJson(before, LyricsData.class);
        List<String> log = new ArrayList<>();
        boolean[] wrote = new boolean[2]; // translation, romaji
        for (int i = 0; i < ops.size(); i++) {
            JsonElement el = ops.get(i);
            try {
                if (el == null || !el.isJsonObject()) throw new IllegalArgumentException("not an object");
                log.add(applyOne(work, el.getAsJsonObject(), wrote));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("ops[" + i + "]: " + e.getMessage() + " No change was made.");
            }
        }
        undo.push(before);
        while (undo.size() > MAX_UNDO) undo.removeLast();
        copyInto(work, data);
        return new Result("Applied " + ops.size() + " op(s): " + String.join("; ", log) + ".", wrote[0], wrote[1]);
    }

    /** Reverts the last edit, or all edits since these lyrics loaded; throws {@link IllegalStateException} when there is none. */
    public String undo(LyricsData data, boolean all) {
        attach(data);
        if (undo.isEmpty()) throw new IllegalStateException("Nothing to undo.");
        String snapshot = all ? original : undo.pop();
        if (all) undo.clear();
        copyInto(GSON.fromJson(snapshot, LyricsData.class), data);
        return all ? "All AI edits were undone." : "The last edit was undone (" + undo.size() + " more step(s) available).";
    }

    public int undoSteps(LyricsData data) {
        return data == owner ? undo.size() : 0;
    }

    /** History belongs to one lyrics object; a new song (a new object) starts it over. */
    private void attach(LyricsData data) {
        if (data != owner) {
            owner = data;
            undo.clear();
            original = GSON.toJson(data);
        } else if (undo.isEmpty()) {
            original = GSON.toJson(data);
        }
    }

    private static void copyInto(LyricsData from, LyricsData to) {
        to.type = from.type;
        to.lines = from.lines != null ? from.lines : new ArrayList<>();
        to.provider = from.provider;
        to.translationLang = from.translationLang;
        to.hasTransliteration = from.hasTransliteration;
        to.sourceScript = from.sourceScript;
    }

    // ---------------------------------------------------------------- ops

    private static String applyOne(LyricsData d, JsonObject op, boolean[] wrote) {
        String type = ToolArgs.str(op, "op", "").trim().toLowerCase(Locale.ROOT);
        switch (type) {
            case "set": return set(d, op, wrote);
            case "insert": return insert(d, op, wrote);
            case "delete": return delete(d, op);
            case "shift": return shift(d, op);
            case "replace": return replace(d, op, wrote);
            default: throw new IllegalArgumentException("unknown op '" + type + "' (use set, insert, delete, shift or replace).");
        }
    }

    private static String set(LyricsData d, JsonObject op, boolean[] wrote) {
        int i = index(op, "line", d.lines.size());
        LyricsData.LyricsLine line = d.lines.get(i);
        String text = ToolArgs.str(op, "text", null);
        String translation = ToolArgs.str(op, "translation", null);
        String romaji = ToolArgs.str(op, "romaji", null);
        List<String> wordRomaji = strings(op, "word_romaji");
        Long start = ToolArgs.intOrNull(op, "start_ms") != null ? ToolArgs.longValue(op, "start_ms", 0) : null;
        Long end = ToolArgs.intOrNull(op, "end_ms") != null ? ToolArgs.longValue(op, "end_ms", 0) : null;
        if (text == null && translation == null && romaji == null && wordRomaji == null && start == null && end == null) {
            throw new IllegalArgumentException("set needs at least one of text, translation, romaji, word_romaji, start_ms, end_ms.");
        }
        if (line.isInterlude && (text != null || wordRomaji != null)) {
            throw new IllegalArgumentException("line " + i + " is an instrumental break; it has no text.");
        }
        List<String> changed = new ArrayList<>();
        if (start != null || end != null) {
            long s = start != null ? start : line.startTime, e = end != null ? end : line.endTime;
            if (s < 0 || e <= s) throw new IllegalArgumentException("need 0 <= start_ms < end_ms.");
            retime(line, s, e);
            changed.add("timing");
        }
        if (text != null) {
            if (text.trim().isEmpty()) throw new IllegalArgumentException("text is empty; use a delete op to remove a line.");
            setText(line, text.trim());
            changed.add("text");
        }
        if (translation != null) {
            line.translation = blankToNull(translation);
            wrote[0] |= line.translation != null;
            changed.add("translation");
        }
        if (wordRomaji != null || romaji != null) {
            setRomaji(line, i, romaji, wordRomaji);
            wrote[1] |= line.romanji != null;
            changed.add(wordRomaji != null ? "word_romaji" : "romaji");
        }
        return "set line " + i + " (" + String.join(", ", changed) + ")";
    }

    private static String insert(LyricsData d, JsonObject op, boolean[] wrote) {
        int at = index(op, "line", d.lines.size() + 1);
        String text = ToolArgs.str(op, "text", "").trim();
        if (text.isEmpty()) throw new IllegalArgumentException("insert needs text.");
        long prevEnd = at > 0 ? d.lines.get(at - 1).endTime : 0;
        long start = ToolArgs.longValue(op, "start_ms", prevEnd);
        long nextStart = at < d.lines.size() ? d.lines.get(at).startTime : start + DEFAULT_LINE_MS;
        long end = ToolArgs.longValue(op, "end_ms", nextStart > start ? nextStart : start + DEFAULT_LINE_MS);
        if (start < 0 || end <= start) throw new IllegalArgumentException("need 0 <= start_ms < end_ms.");
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(start, end, text);
        SyllableLyricsParser.splitLineIntoWords(line);
        line.translation = blankToNull(ToolArgs.str(op, "translation", null));
        String romaji = ToolArgs.str(op, "romaji", null);
        if (romaji != null) setRomaji(line, at, romaji, null);
        d.lines.add(at, line);
        wrote[0] |= line.translation != null;
        wrote[1] |= line.romanji != null;
        return "inserted line " + at;
    }

    private static String delete(LyricsData d, JsonObject op) {
        int from = index(op, "line", d.lines.size());
        int to = op.has("to_line") ? index(op, "to_line", d.lines.size()) : from;
        if (to < from) throw new IllegalArgumentException("to_line is before line.");
        if (to - from + 1 >= d.lines.size()) throw new IllegalArgumentException("refusing to delete every line.");
        d.lines.subList(from, to + 1).clear();
        return from == to ? "deleted line " + from : "deleted lines " + from + "-" + to;
    }

    private static String shift(LyricsData d, JsonObject op) {
        long ms = ToolArgs.longValue(op, "ms", 0);
        if (ms == 0) throw new IllegalArgumentException("shift needs a non-zero ms (+ later, - earlier).");
        int[] range = range(d, op);
        for (int i = range[0]; i <= range[1]; i++) {
            LyricsData.LyricsLine l = d.lines.get(i);
            long s = Math.max(0, l.startTime + ms);
            retime(l, s, s + (l.endTime - l.startTime));
        }
        return "shifted lines " + range[0] + "-" + range[1] + " by " + ms + " ms";
    }

    private static String replace(LyricsData d, JsonObject op, boolean[] wrote) {
        String find = ToolArgs.str(op, "find", "");
        if (find.isEmpty()) throw new IllegalArgumentException("replace needs find.");
        String with = ToolArgs.str(op, "replace", "");
        String field = ToolArgs.str(op, "field", "text").trim().toLowerCase(Locale.ROOT);
        boolean all = "all".equals(field);
        if (!all && !"text".equals(field) && !"translation".equals(field) && !"romaji".equals(field)) {
            throw new IllegalArgumentException("field must be text, translation, romaji or all.");
        }
        Pattern p = Pattern.compile(Pattern.quote(find), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        String rep = Matcher.quoteReplacement(with);
        int[] range = range(d, op);
        int hits = 0;
        for (int i = range[0]; i <= range[1]; i++) {
            LyricsData.LyricsLine l = d.lines.get(i);
            if (l.isInterlude) continue;
            if ((all || "text".equals(field)) && count(p, l.text) > 0) {
                hits += count(p, l.text);
                String text = p.matcher(l.text).replaceAll(rep).trim();
                if (!text.isEmpty()) setText(l, text);
            }
            if ((all || "translation".equals(field)) && count(p, l.translation) > 0) {
                hits += count(p, l.translation);
                l.translation = blankToNull(p.matcher(l.translation).replaceAll(rep));
                wrote[0] |= l.translation != null;
            }
            if ((all || "romaji".equals(field)) && count(p, l.romanji) > 0) {
                hits += count(p, l.romanji);
                l.romanji = blankToNull(p.matcher(l.romanji).replaceAll(rep));
                for (LyricsData.Word w : l.words) {
                    if (w.romanji != null) w.romanji = p.matcher(w.romanji).replaceAll(rep);
                }
                wrote[1] |= l.romanji != null;
            }
        }
        return "replaced '" + find + "' in " + field + ": " + hits + " match(es)";
    }

    // ---------------------------------------------------------------- line helpers

    /** New text invalidates the old word timing and reading: words are re-split, the romaji dropped. */
    private static void setText(LyricsData.LyricsLine line, String text) {
        if (text.equals(line.text)) return;
        line.text = text;
        line.words = new ArrayList<>();
        SyllableLyricsParser.splitLineIntoWords(line);
        line.romanji = null;
    }

    /**
     * The Latin layer of a line. {@code wordRomaji} labels each word 1:1; a line-level
     * {@code romaji} alone is spread over the words; "" clears.
     */
    private static void setRomaji(LyricsData.LyricsLine line, int i, String romaji, List<String> wordRomaji) {
        int words = line.words != null ? line.words.size() : 0;
        if (wordRomaji != null && wordRomaji.size() != words) {
            throw new IllegalArgumentException("line " + i + " has " + words + " word(s); word_romaji needs exactly "
                    + words + " entries (read_lyrics with include_words shows them).");
        }
        for (LyricsData.Word w : line.words) w.romanji = null;
        line.romanji = null;
        String lineRomaji = romaji != null ? romaji.trim() : wordRomaji != null ? joinNonBlank(wordRomaji) : "";
        if (lineRomaji.isEmpty() && (wordRomaji == null || joinNonBlank(wordRomaji).isEmpty())) return;
        AiSyllableTransliterationService.alignSyllables(line, wordRomaji, lineRomaji);
        line.romanji = blankToNull(line.romanji);
    }

    /** Moves a line (and its words and background vocals) to {@code start..end}, scaling inner timing. */
    private static void retime(LyricsData.LyricsLine line, long start, long end) {
        long s0 = line.startTime, len0 = Math.max(1, line.endTime - line.startTime), len = end - start;
        line.startTime = start;
        line.endTime = end;
        List<LyricsData.Word> words = new ArrayList<>(line.words);
        if (line.backgroundVocals != null) {
            for (LyricsData.LyricsLine bg : line.backgroundVocals) {
                bg.startTime = start + (bg.startTime - s0) * len / len0;
                bg.endTime = start + (bg.endTime - s0) * len / len0;
                words.addAll(bg.words);
            }
        }
        for (LyricsData.Word w : words) {
            w.startTime = Math.max(0, start + (w.startTime - s0) * len / len0);
            w.endTime = Math.max(w.startTime, start + (w.endTime - s0) * len / len0);
        }
    }

    // ---------------------------------------------------------------- read

    /** Lines {@code from..to} (inclusive; -1 = to the end, at most {@link #MAX_READ_LINES}) as JSON. */
    public static JsonObject read(LyricsData data, int from, int to, boolean includeWords) {
        JsonObject o = new JsonObject();
        int n = data != null && data.lines != null ? data.lines.size() : 0;
        o.addProperty("total_lines", n);
        JsonArray lines = new JsonArray();
        if (n > 0) {
            int a = Math.max(0, Math.min(from, n - 1));
            int b = to < 0 ? n - 1 : Math.min(to, n - 1);
            b = Math.min(Math.max(a, b), a + MAX_READ_LINES - 1);
            for (int i = a; i <= b; i++) lines.add(line(i, data.lines.get(i), includeWords));
            o.addProperty("from", a);
            o.addProperty("to", b);
            if (b < n - 1) o.addProperty("next_from", b + 1);
        }
        o.add("lines", lines);
        return o;
    }

    private static JsonObject line(int i, LyricsData.LyricsLine l, boolean includeWords) {
        JsonObject o = new JsonObject();
        o.addProperty("line", i);
        o.addProperty("start_ms", l.startTime);
        o.addProperty("end_ms", l.endTime);
        if (l.isInterlude) {
            o.addProperty("interlude", true);
            return o;
        }
        o.addProperty("text", l.text != null ? l.text : "");
        if (l.romanji != null && !l.romanji.trim().isEmpty()) o.addProperty("romaji", l.romanji.trim());
        if (l.translation != null && !l.translation.trim().isEmpty()) o.addProperty("translation", l.translation.trim());
        if (includeWords && l.words != null && !l.words.isEmpty()) {
            JsonArray words = new JsonArray(), romaji = new JsonArray();
            boolean anyRomaji = false;
            for (LyricsData.Word w : l.words) {
                words.add(w.text != null ? w.text : "");
                String r = w.romanji != null ? w.romanji.trim() : "";
                anyRomaji |= !r.isEmpty();
                romaji.add(r);
            }
            o.add("words", words);
            if (anyRomaji) o.add("word_romaji", romaji);
        }
        if (l.backgroundVocals != null && !l.backgroundVocals.isEmpty()) {
            JsonArray bg = new JsonArray();
            for (LyricsData.LyricsLine b : l.backgroundVocals) bg.add(b.text != null ? b.text : "");
            o.add("background_vocals", bg);
        }
        return o;
    }

    // ---------------------------------------------------------------- arg helpers

    private static int index(JsonObject op, String key, int size) {
        Integer i = ToolArgs.intOrNull(op, key);
        if (i == null) throw new IllegalArgumentException(key + " is required.");
        if (i < 0 || i >= size) throw new IllegalArgumentException(key + " " + i + " is out of range 0-" + (size - 1) + ".");
        return i;
    }

    /** line..to_line; both optional (default: every line). */
    private static int[] range(LyricsData d, JsonObject op) {
        int n = d.lines.size();
        if (n == 0) throw new IllegalArgumentException("there are no lines.");
        int from = op.has("line") ? index(op, "line", n) : 0;
        int to = op.has("to_line") ? index(op, "to_line", n) : op.has("line") ? from : n - 1;
        if (to < from) throw new IllegalArgumentException("to_line is before line.");
        return new int[]{from, to};
    }

    private static List<String> strings(JsonObject op, String key) {
        JsonElement el = op.get(key);
        if (el == null || el.isJsonNull()) return null;
        if (!el.isJsonArray()) throw new IllegalArgumentException(key + " must be an array of strings.");
        List<String> out = new ArrayList<>();
        for (JsonElement e : el.getAsJsonArray()) out.add(e == null || e.isJsonNull() ? "" : e.getAsString());
        return out;
    }

    private static int count(Pattern p, String s) {
        if (s == null) return 0;
        Matcher m = p.matcher(s);
        int c = 0;
        while (m.find()) c++;
        return c;
    }

    private static String joinNonBlank(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (s == null || s.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(s.trim());
        }
        return sb.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
