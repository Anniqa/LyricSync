package com.lyricsync.app.ai.transliteration;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.ai.model.AiChatMessage;
import com.lyricsync.app.ai.util.JsonResponse;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.util.JapaneseRomanizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds a Latin reading (Hepburn romaji, pinyin, Revised Romanization ...) to every syllable
 * of a lyric, in batches of {@link #BATCH_SIZE} lines. The model is asked for one reading
 * per syllable; when its split differs, {@link #alignTokens} maps its words back onto the
 * syllables. Translation is optional and only used by the single-line editor; whole songs
 * are translated by {@code AiTranslationService}, which sees the full context.
 */
public class AiSyllableTransliterationService {
    static final int BATCH_SIZE = 20;
    private static final String MODEL = "gemini-3.8-flash-high";

    private final AntigravityApiClient apiClient;
    private final Handler mainHandler;

    public interface Callback {
        void onSuccess(LyricsData enrichedData);
        void onError(String message);
    }

    public AiSyllableTransliterationService(Context context, AntigravityApiClient apiClient) {
        this(apiClient, new Handler(Looper.getMainLooper()));
    }

    /** {@code mainHandler} null runs everything inline (tests). */
    AiSyllableTransliterationService(AntigravityApiClient apiClient, Handler mainHandler) {
        this.apiClient = apiClient;
        this.mainHandler = mainHandler;
    }

    /**
     * Fills {@code word.romanji} and {@code line.romanji} of {@code data} in place, and
     * {@code line.translation} when {@code translateTo} is set. Results are applied on the
     * main thread, where the renderer reads them; a failed batch is retried once.
     */
    public AntigravityApiClient.Cancellable transliterateSyllablesAsync(LyricsData data, String title, String artist,
                                                                     String translateTo, Callback callback) {
        Job job = new Job(data, title, artist, translateTo, callback);
        if (data == null || data.lines == null || data.lines.isEmpty() || apiClient == null) {
            post(() -> callback.onError("Lirik tidak tersedia untuk transliterasi"));
            return job;
        }
        for (LyricsData.LyricsLine line : data.lines) {
            if (line != null && !line.isInterlude && line.text != null && !line.text.trim().isEmpty()) {
                job.lines.add(line);
            }
        }
        job.next();
        return job;
    }

    private final class Job implements AntigravityApiClient.Cancellable {
        final LyricsData data;
        final String title;
        final String artist;
        final String translateTo;
        final Callback callback;
        final List<LyricsData.LyricsLine> lines = new ArrayList<>();
        int start;
        boolean retried;
        volatile boolean cancelled;
        private AntigravityApiClient.Cancellable call;

        Job(LyricsData data, String title, String artist, String translateTo, Callback callback) {
            this.data = data;
            this.title = title;
            this.artist = artist;
            this.translateTo = translateTo;
            this.callback = callback;
        }

        void next() {
            if (cancelled) return;
            if (start >= lines.size()) {
                data.hasTransliteration = true;
                data.sourceScript = ScriptDetector.detectScript(allText(data)).name();
                post(() -> { if (!cancelled) callback.onSuccess(data); });
                return;
            }
            final List<LyricsData.LyricsLine> batch = lines.subList(start, Math.min(start + BATCH_SIZE, lines.size()));
            String prompt = buildPrompt(title, artist, batch, translateTo);
            AntigravityApiClient.Cancellable c = apiClient.generateContent(MODEL,
                    Collections.singletonList(AiChatMessage.user(prompt)), false, "low",
                    new AntigravityApiClient.CompletionCallback() {
                        @Override
                        public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                            post(() -> {
                                if (cancelled) return;
                                if (apply(fullText, batch, translateTo != null) == 0) {
                                    retryOrFail("AI tidak mengembalikan transliterasi yang valid");
                                    return;
                                }
                                start += batch.size();
                                retried = false;
                                next();
                            });
                        }

                        @Override
                        public void onError(Exception error) {
                            String msg = error != null && error.getMessage() != null ? error.getMessage() : "unknown error";
                            post(() -> { if (!cancelled) retryOrFail("Transliterasi AI gagal: " + msg); });
                        }
                    });
            synchronized (this) {
                call = c;
                if (cancelled && c != null) c.cancel();
            }
        }

        void retryOrFail(String message) {
            if (!retried) {
                retried = true;
                next();
            } else {
                callback.onError(message);
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            synchronized (this) {
                if (call != null) call.cancel();
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    private void post(Runnable action) {
        if (mainHandler == null) action.run();
        else mainHandler.post(action);
    }

    static String buildPrompt(String title, String artist, List<LyricsData.LyricsLine> batch, String translateTo) {
        JsonArray input = new JsonArray();
        for (int i = 0; i < batch.size(); i++) {
            LyricsData.LyricsLine line = batch.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("index", i);
            o.addProperty("text", line.text.trim());
            JsonArray syllables = new JsonArray();
            for (LyricsData.Word w : line.words) syllables.add(w != null && w.text != null ? w.text : "");
            o.add("syllables", syllables);
            input.add(o);
        }
        String song = (title == null || title.isEmpty() ? "unknown" : title)
                + (artist == null || artist.isEmpty() ? "" : " by " + artist);
        String translation = translateTo == null ? ""
                : "- \"translation\": a natural, fluent " + translateTo
                  + " translation of the line's meaning, written like a professional subtitle.\n";
        return "You romanize song lyrics for a karaoke display. Song: " + song + ".\n\n"
                + "For each input line return:\n"
                + "- \"romanized\": the whole line in Latin letters, words separated by spaces as a native reader would.\n"
                + "- \"syllables\": one reading per entry of the input \"syllables\" array, same order, EXACTLY the same length. "
                + "Never merge or split entries; a kanji compound spread over several entries is divided by its reading "
                + "(前前前世 as [前,前,前,世] -> [\"zen\",\"zen\",\"zen\",\"se\"]). Entries already in Latin, digits and "
                + "punctuation are copied unchanged; an entry with no sound gets \"\".\n"
                + translation + "\n"
                + "Readings:\n"
                + "- Japanese: modified Hepburn, lowercase ASCII without macrons; long vowels as written (ou, uu, ee); "
                + "particles は=wa, を=o, へ=e; っ doubles the next consonant; use the reading that fits the song's context.\n"
                + "- Chinese: Hanyu Pinyin with tone marks, one syllable per character.\n"
                + "- Korean: Revised Romanization, one block per syllable, applying sound changes across blocks.\n"
                + "- Other scripts: their standard romanization.\n"
                + "Output Latin characters only in romanized and syllables.\n\n"
                + "Answer with JSON only, no code fence:\n"
                + "{\"lines\":[{\"index\":0,\"romanized\":\"...\",\"syllables\":[\"...\"]"
                + (translateTo == null ? "" : ",\"translation\":\"...\"") + "}]}\n\n"
                + "Input:\n" + input;
    }

    /** Writes the model's answer into {@code batch}; returns how many lines got a reading. */
    static int apply(String answer, List<LyricsData.LyricsLine> batch, boolean withTranslation) {
        JsonArray items;
        try {
            JsonElement root = JsonParser.parseString(JsonResponse.extract(answer));
            items = root.isJsonArray() ? root.getAsJsonArray() : root.getAsJsonObject().getAsJsonArray("lines");
        } catch (RuntimeException e) {
            return 0;
        }
        if (items == null) return 0;
        int applied = 0;
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isJsonObject()) continue;
            JsonObject item = items.get(i).getAsJsonObject();
            int index = item.has("index") ? asInt(item.get("index")) : i;
            if (index < 0 || index >= batch.size()) continue;
            List<String> syllables = new ArrayList<>();
            JsonElement arr = item.get("syllables");
            if (arr != null && arr.isJsonArray()) {
                for (JsonElement e : arr.getAsJsonArray()) syllables.add(e.isJsonPrimitive() ? e.getAsString() : "");
            }
            String romanized = string(item, "romanized");
            if (syllables.isEmpty() && (romanized == null || romanized.trim().isEmpty())) continue;
            LyricsData.LyricsLine line = batch.get(index);
            alignSyllables(line, syllables, romanized);
            String translation = string(item, "translation");
            if (withTranslation && translation != null && !translation.trim().isEmpty()) {
                line.translation = translation.trim();
            }
            applied++;
        }
        return applied;
    }

    private static String allText(LyricsData data) {
        StringBuilder sb = new StringBuilder();
        for (LyricsData.LyricsLine line : data.lines) {
            if (line != null && line.text != null) sb.append(line.text).append(' ');
        }
        return sb.toString();
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static int asInt(JsonElement e) {
        try {
            return e.getAsInt();
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    private static final Pattern MORA_PATTERN = Pattern.compile(
        "(?i)" +
        "(?:(?:t?ch|s?sh|ts|[kscnhmrgzdbptfvwj]y)[aiueoāīūēō](?:n(?![aiueoāīūēōy]))?)|" +
        "(?:[bcdfghjklmnprstvwxyz][aiueoāīūēō](?:n(?![aiueoāīūēōy]))?)|" +
        "(?:[aiueoāīūēō](?:n(?![aiueoāīūēōy]))?)|" +
        "(?:n(?![aiueoāīūēōy]))|" +
        "(?:[bcdfghjklmnprstvwxyz](?=[bcdfghjklmnprstvwxyz]))|" +
        "(?:[a-zA-Z0-9]+)"
    );

    static List<String> splitRomajiIntoMoras(String text) {
        List<String> moras = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return moras;
        Matcher m = MORA_PATTERN.matcher(text.trim());
        while (m.find()) {
            String mora = m.group().trim();
            if (!mora.isEmpty()) {
                moras.add(mora);
            }
        }
        return moras;
    }

    private static class Anchor {
        final int tokenIdx;
        final int sylStart;
        final int sylEnd;
        Anchor(int tokenIdx, int sylStart, int sylEnd) {
            this.tokenIdx = tokenIdx;
            this.sylStart = sylStart;
            this.sylEnd = sylEnd;
        }
    }

    /**
     * True when {@code token} reads syllables {@code start..end}: the same Latin text, or the
     * kana's Hepburn reading (particles は/へ also read "wa"/"e"). Kanji never match here.
     */
    private static boolean matchKanaOrText(String token, List<LyricsData.Word> syllables, int start, int end) {
        String tok = token.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
        if (tok.isEmpty()) return false;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            LyricsData.Word w = syllables.get(i);
            if (w == null || w.text == null) return false;
            sb.append(w.text.trim());
        }
        String text = sb.toString();
        if (tok.equalsIgnoreCase(text.replaceAll("[^a-zA-Z0-9]", ""))) return true;
        if (!JapaneseRomanizer.containsKana(text)) return false;
        String reading = JapaneseRomanizer.romanize(text).toLowerCase(Locale.ROOT);
        if (!reading.matches("[a-z]+")) return false;
        if (tok.equals(reading)) return true;
        return start == end && (("ha".equals(reading) && "wa".equals(tok)) || ("he".equals(reading) && "e".equals(tok)));
    }

    /** Aligns {@code tokens} into {@code count} slots and writes them into {@code out} at {@code start}. */
    private static void fillSlots(String[] out, int start, int count, List<String> tokens) {
        List<String> sub = alignTokens(tokens, count, null);
        for (int k = 0; k < count; k++) {
            out[start + k] = (k < sub.size()) ? sub.get(k) : "";
        }
    }

    static List<String> alignTokens(List<String> rawTokens, int expectedCount, List<LyricsData.Word> syllables) {
        if (expectedCount <= 0) return Collections.emptyList();
        if (rawTokens == null || rawTokens.isEmpty()) {
            List<String> res = new ArrayList<>();
            for (int i = 0; i < expectedCount; i++) res.add("");
            return res;
        }

        // 1. If rawTokens already matches expectedCount exactly: 1:1
        if (rawTokens.size() == expectedCount) {
            return new ArrayList<>(rawTokens);
        }

        // 2. If more tokens than expectedCount: group proportionally into expectedCount buckets
        if (rawTokens.size() > expectedCount) {
            List<String> result = new ArrayList<>();
            int total = rawTokens.size();
            for (int i = 0; i < expectedCount; i++) {
                int start = (i * total) / expectedCount;
                int end = ((i + 1) * total) / expectedCount;
                StringBuilder sb = new StringBuilder();
                for (int k = start; k < end; k++) {
                    sb.append(rawTokens.get(k));
                }
                result.add(sb.toString());
            }
            return result;
        }

        // 3. Anchor matching when syllables list is provided
        if (syllables != null && syllables.size() == expectedCount) {
            List<Anchor> anchors = new ArrayList<>();
            int currentSyl = 0;
            int prevToken = -1;
            int n = rawTokens.size();
            for (int t = 0; t < n; t++) {
                String tok = rawTokens.get(t);
                if (tok == null || tok.trim().isEmpty()) continue;
                int foundStart = -1;
                int foundEnd = -1;
                for (int sStart = currentSyl; sStart < expectedCount; sStart++) {
                    if (sStart - currentSyl < (t - prevToken - 1)) {
                        continue;
                    }
                    for (int sEnd = sStart; sEnd < Math.min(expectedCount, sStart + 5); sEnd++) {
                        int remainingSyl = expectedCount - 1 - sEnd;
                        int remainingTok = n - 1 - t;
                        if (remainingSyl < remainingTok) {
                            break;
                        }
                        if (matchKanaOrText(tok, syllables, sStart, sEnd)) {
                            foundStart = sStart;
                            foundEnd = sEnd;
                            break;
                        }
                    }
                    if (foundStart != -1) break;
                }
                if (foundStart != -1) {
                    anchors.add(new Anchor(t, foundStart, foundEnd));
                    currentSyl = foundEnd + 1;
                    prevToken = t;
                }
            }

            if (!anchors.isEmpty()) {
                String[] out = new String[expectedCount];
                int lastSylEnd = -1;
                int lastTokIdx = -1;
                for (Anchor a : anchors) {
                    // Fill gap before anchor
                    int gapSylStart = lastSylEnd + 1;
                    int numSyl = a.sylStart - gapSylStart;
                    if (numSyl > 0) {
                        int gapTokStart = lastTokIdx + 1;
                        List<String> gapToks = (gapTokStart < a.tokenIdx)
                                ? rawTokens.subList(gapTokStart, a.tokenIdx)
                                : Collections.emptyList();
                        fillSlots(out, gapSylStart, numSyl, gapToks);
                    }
                    // Fill anchor itself
                    int span = a.sylEnd - a.sylStart + 1;
                    String tok = rawTokens.get(a.tokenIdx);
                    if (span == 1) {
                        out[a.sylStart] = tok;
                    } else {
                        fillSlots(out, a.sylStart, span,
                                tok.isEmpty() ? Collections.emptyList() : Collections.singletonList(tok));
                    }
                    lastSylEnd = a.sylEnd;
                    lastTokIdx = a.tokenIdx;
                }
                // Fill trailing gap after last anchor
                int trailSylStart = lastSylEnd + 1;
                int numTrailSyl = expectedCount - trailSylStart;
                if (numTrailSyl > 0) {
                    int trailTokStart = lastTokIdx + 1;
                    List<String> gapToks = (trailTokStart < n)
                            ? rawTokens.subList(trailTokStart, n)
                            : Collections.emptyList();
                    fillSlots(out, trailSylStart, numTrailSyl, gapToks);
                }
                List<String> finalRes = new ArrayList<>(expectedCount);
                for (int i = 0; i < expectedCount; i++) {
                    finalRes.add(out[i] != null ? out[i] : "");
                }
                return finalRes;
            }
        }

        // 4. Fallback: allocate slots based on mora decomposition
        int n = rawTokens.size();
        List<List<String>> morasList = new ArrayList<>(n);
        int totalMoras = 0;
        for (String tok : rawTokens) {
            List<String> m = (tok != null && !tok.isEmpty()) ? splitRomajiIntoMoras(tok) : Collections.emptyList();
            morasList.add(m);
            totalMoras += Math.max(1, m.size());
        }

        List<Integer> slots = new ArrayList<>(n);
        for (List<String> m : morasList) {
            slots.add(Math.max(1, m.size()));
        }

        if (totalMoras > expectedCount) {
            int excess = totalMoras - expectedCount;
            // First reduction: 2-mora words (e.g. 2-mora readings) kept as 1 slot
            for (int i = 0; i < n && excess > 0; i++) {
                if (slots.get(i) == 2) {
                    slots.set(i, 1);
                    excess--;
                }
            }
            // Second reduction: largest multi-mora tokens
            while (excess > 0) {
                int maxIdx = -1;
                int maxVal = 1;
                for (int i = 0; i < n; i++) {
                    if (slots.get(i) > maxVal) {
                        maxVal = slots.get(i);
                        maxIdx = i;
                    }
                }
                if (maxIdx >= 0) {
                    slots.set(maxIdx, maxVal - 1);
                    excess--;
                } else {
                    break;
                }
            }
        } else if (totalMoras < expectedCount) {
            int needed = expectedCount - totalMoras;
            while (needed > 0) {
                int maxIdx = -1;
                int maxLen = -1;
                for (int i = 0; i < n; i++) {
                    int len = rawTokens.get(i) != null ? rawTokens.get(i).length() : 0;
                    if (len > maxLen) {
                        maxLen = len;
                        maxIdx = i;
                    }
                }
                if (maxIdx >= 0) {
                    slots.set(maxIdx, slots.get(maxIdx) + 1);
                    needed--;
                } else {
                    slots.set(0, slots.get(0) + needed);
                    break;
                }
            }
        }

        // Emit tokens into the allocated slots
        List<String> result = new ArrayList<>(expectedCount);
        for (int i = 0; i < n; i++) {
            String tok = rawTokens.get(i);
            if (tok == null) tok = "";
            int targetSlots = slots.get(i);
            List<String> moras = morasList.get(i);

            if (targetSlots <= 1) {
                result.add(tok);
            } else if (moras.size() == targetSlots) {
                result.addAll(moras);
            } else if (moras.size() > targetSlots) {
                int mTotal = moras.size();
                for (int s = 0; s < targetSlots; s++) {
                    int start = (s * mTotal) / targetSlots;
                    int end = ((s + 1) * mTotal) / targetSlots;
                    StringBuilder sb = new StringBuilder();
                    for (int k = start; k < end; k++) {
                        sb.append(moras.get(k));
                    }
                    result.add(sb.toString());
                }
            } else {
                // moras.size() < targetSlots: split text proportionally
                int tLen = tok.length();
                for (int s = 0; s < targetSlots; s++) {
                    int start = (s * tLen) / targetSlots;
                    int end = ((s + 1) * tLen) / targetSlots;
                    result.add(tok.substring(start, end));
                }
            }
        }

        // Safety fallback to ensure exact expectedCount size
        while (result.size() < expectedCount) {
            result.add("");
        }
        if (result.size() > expectedCount) {
            return new ArrayList<>(result.subList(0, expectedCount));
        }

        return result;
    }

    /**
     * Writes a Latin reading onto {@code line}: the line text, and per-word labels aligned to
     * its words (given per word, or split from the line text when absent).
     */
    public static void alignSyllables(LyricsData.LyricsLine line, List<String> aiWordsRomanji, String fallbackRomanjiLine) {
        if (line == null) return;
        if (fallbackRomanjiLine != null && !fallbackRomanjiLine.trim().isEmpty()) {
            line.romanji = ScriptDetector.stripNonLatinCharacters(fallbackRomanjiLine).trim();
        }

        if (line.words == null || line.words.isEmpty()) {
            return;
        }

        int expectedCount = line.words.size();

        List<String> rawTokens = new ArrayList<>();
        if (aiWordsRomanji != null && !aiWordsRomanji.isEmpty()) {
            for (String tok : aiWordsRomanji) {
                rawTokens.add(tok != null ? ScriptDetector.stripNonLatinCharacters(tok).trim() : "");
            }
        }
        boolean hasAny = false;
        for (String t : rawTokens) {
            if (!t.isEmpty()) {
                hasAny = true;
                break;
            }
        }
        if (!hasAny && line.romanji != null && !line.romanji.isEmpty()) {
            rawTokens.clear();
            String[] split = line.romanji.split("\\s+");
            for (String s : split) {
                String clean = ScriptDetector.stripNonLatinCharacters(s).trim();
                if (!clean.isEmpty()) {
                    rawTokens.add(clean);
                }
            }
        }

        List<String> aligned = alignTokens(rawTokens, expectedCount, line.words);

        for (int i = 0; i < expectedCount; i++) {
            line.words.get(i).romanji = (i < aligned.size()) ? aligned.get(i) : "";
        }

        if (line.romanji == null || line.romanji.trim().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (LyricsData.Word w : line.words) {
                if (w.romanji != null && !w.romanji.trim().isEmpty()) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(w.romanji.trim());
                }
            }
            line.romanji = sb.toString();
        }

        computeWordBoundaries(line);
    }

    private static boolean isWordSeparator(char c) {
        return Character.isWhitespace(c) || c == '-' || c == '\'' || c == '\"' || c == '/'
                || c == ',' || c == '.' || c == '!' || c == '?' || c == '~' || c == ';' || c == ':'
                || c == '—' || c == '–' || c == '…';
    }

    private static boolean isSmallKanaOrChouon(char c) {
        return c == 'っ' || c == 'ッ' || c == 'ゃ' || c == 'ャ'
                || c == 'ゅ' || c == 'ュ' || c == 'ょ' || c == 'ョ'
                || c == 'ぁ' || c == 'ァ' || c == 'ぃ' || c == 'ィ'
                || c == 'ぅ' || c == 'ゥ' || c == 'ぇ' || c == 'ェ'
                || c == 'ぉ' || c == 'ォ' || c == 'ー';
    }

    public static void computeWordBoundaries(LyricsData.LyricsLine line) {
        if (line == null || line.words == null || line.words.isEmpty()) return;
        int n = line.words.size();
        if (line.romanji != null && !line.romanji.isEmpty()) {
            String full = line.romanji.trim();
            int cursor = 0;
            boolean hadAnyMatch = false;
            for (int i = 0; i < n; i++) {
                LyricsData.Word w = line.words.get(i);
                String tok = (w.romanji != null) ? w.romanji.trim() : "";
                if (tok.isEmpty()) {
                    w.isPartOfWord = false;
                    continue;
                }
                while (cursor < full.length() && isWordSeparator(full.charAt(cursor))) {
                    cursor++;
                }

                int matchPos = -1;
                if (cursor + tok.length() <= full.length()
                        && full.substring(cursor, cursor + tok.length()).equalsIgnoreCase(tok)) {
                    matchPos = cursor;
                } else {
                    // Lookahead window up to 20 chars to recover from minor omissions, particles, or punctuation
                    int searchLimit = Math.min(full.length(), cursor + 20);
                    for (int s = cursor; s <= searchLimit - tok.length(); s++) {
                        if (isWordSeparator(full.charAt(s))) continue;
                        if (full.regionMatches(true, s, tok, 0, tok.length())) {
                            matchPos = s;
                            break;
                        }
                    }
                }

                if (matchPos >= 0) {
                    hadAnyMatch = true;
                    cursor = matchPos + tok.length();
                    boolean continuesWord = false;
                    if (cursor < full.length() && i < n - 1) {
                        char nextChar = full.charAt(cursor);
                        if (Character.isLetterOrDigit(nextChar)) {
                            continuesWord = true;
                        }
                    }
                    w.isPartOfWord = continuesWord;
                } else {
                    // Mismatched token: keep as independent boundary, do NOT merge blindly
                    w.isPartOfWord = false;
                }
            }
            if (hadAnyMatch) {
                line.words.get(n - 1).isPartOfWord = false;
                return;
            }
        }

        // Fallback: check text-level script continuity or whitespace
        for (int i = 0; i < n; i++) {
            LyricsData.Word w = line.words.get(i);
            if (i == n - 1) {
                w.isPartOfWord = false;
                continue;
            }
            LyricsData.Word next = line.words.get(i + 1);
            String t = w.text != null ? w.text.trim() : "";
            String nt = next.text != null ? next.text.trim() : "";

            if (w.text != null && (w.text.endsWith(" ") || w.text.endsWith("\u3000"))) {
                w.isPartOfWord = false;
            } else if (w.romanji != null && w.romanji.endsWith(" ")) {
                w.isPartOfWord = false;
            } else if (!t.isEmpty() && !nt.isEmpty()) {
                char nextFirst = nt.charAt(0);
                if (isSmallKanaOrChouon(nextFirst)) {
                    // Small kana / chouon always continues the preceding mora
                    w.isPartOfWord = true;
                } else {
                    w.isPartOfWord = false;
                }
            } else {
                w.isPartOfWord = false;
            }
        }
    }
}
