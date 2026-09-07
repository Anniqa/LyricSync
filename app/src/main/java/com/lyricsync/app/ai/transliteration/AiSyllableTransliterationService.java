package com.lyricsync.app.ai.transliteration;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.ai.model.AiChatMessage;
import com.lyricsync.app.lyrics.cache.LyricsCacheManager;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AiSyllableTransliterationService {
    private static final String TAG = "AiTranslitService";
    private static final int BATCH_SIZE = 12;
    private static final String DEFAULT_MODEL = "gemini-2.5-flash";

    private final Context context;
    private final AntigravityApiClient apiClient;
    private final LyricsCacheManager cacheManager;
    private final Handler mainHandler;
    private static final Gson GSON = new Gson();

    public interface Callback {
        void onSuccess(LyricsData enrichedData);
        void onError(String message);
        default void onProgress(int processedLines, int totalLines) {}
    }

    public static class LineTransliterationResult {
        public int index;
        public String romanji;
        public String translation;
        public List<String> wordsRomanji = new ArrayList<>();
    }

    public AiSyllableTransliterationService(Context context, AntigravityApiClient apiClient) {
        this(context, apiClient, context != null ? new LyricsCacheManager(context) : null, createSafeMainHandler());
    }

    public AiSyllableTransliterationService(Context context, AntigravityApiClient apiClient, LyricsCacheManager cacheManager) {
        this(context, apiClient, cacheManager, createSafeMainHandler());
    }

    public AiSyllableTransliterationService(Context context, AntigravityApiClient apiClient, LyricsCacheManager cacheManager, Handler mainHandler) {
        this.context = context != null ? context.getApplicationContext() : null;
        this.apiClient = apiClient;
        this.cacheManager = cacheManager;
        this.mainHandler = mainHandler;
    }

    private static Handler createSafeMainHandler() {
        try {
            Looper mainLooper = Looper.getMainLooper();
            if (mainLooper != null) {
                return new Handler(mainLooper);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static void alignSyllables(LyricsData.LyricsLine line, List<String> aiWordsRomanji, String fallbackRomanjiLine) {
        if (line == null) return;
        if (fallbackRomanjiLine != null && !fallbackRomanjiLine.trim().isEmpty()) {
            line.romanji = ScriptDetector.stripNonLatinCharacters(fallbackRomanjiLine).trim();
        }

        if (line.words == null || line.words.isEmpty()) {
            return;
        }

        int expectedCount = line.words.size();

        List<String> tokens = new ArrayList<>();
        if (aiWordsRomanji != null && !aiWordsRomanji.isEmpty()) {
            for (String tok : aiWordsRomanji) {
                tokens.add(ScriptDetector.stripNonLatinCharacters(tok));
            }
        } else if (line.romanji != null && !line.romanji.isEmpty()) {
            String[] split = line.romanji.split("\\s+");
            Collections.addAll(tokens, split);
        }

        for (int i = 0; i < expectedCount; i++) {
            if (i < tokens.size()) {
                line.words.get(i).romanji = tokens.get(i);
            } else {
                line.words.get(i).romanji = "";
            }
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

    public static void computeWordBoundaries(LyricsData.LyricsLine line) {
        if (line == null || line.words == null || line.words.isEmpty()) return;
        int n = line.words.size();
        if (line.romanji != null && !line.romanji.isEmpty()) {
            String full = line.romanji.trim();
            int cursor = 0;
            boolean matched = true;
            for (int i = 0; i < n; i++) {
                LyricsData.Word w = line.words.get(i);
                String tok = (w.romanji != null) ? w.romanji.trim() : "";
                if (tok.isEmpty()) {
                    w.isPartOfWord = false;
                    continue;
                }
                while (cursor < full.length() && Character.isWhitespace(full.charAt(cursor))) {
                    cursor++;
                }
                if (cursor + tok.length() <= full.length()
                        && full.substring(cursor, cursor + tok.length()).equalsIgnoreCase(tok)) {
                    cursor += tok.length();
                    if (cursor < full.length() && !Character.isWhitespace(full.charAt(cursor)) && i < n - 1) {
                        w.isPartOfWord = true;
                    } else {
                        w.isPartOfWord = false;
                    }
                } else {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                line.words.get(n - 1).isPartOfWord = false;
                return;
            }
        }

        for (int i = 0; i < n; i++) {
            LyricsData.Word w = line.words.get(i);
            if (i == n - 1) {
                w.isPartOfWord = false;
            } else if (w.text != null && w.text.endsWith(" ")) {
                w.isPartOfWord = false;
            } else if (w.romanji != null && w.romanji.endsWith(" ")) {
                w.isPartOfWord = false;
            } else {
                w.isPartOfWord = false;
            }
        }
    }

    public static List<LineTransliterationResult> parseJsonResponse(String jsonText) {
        List<LineTransliterationResult> list = new ArrayList<>();
        if (jsonText == null || jsonText.trim().isEmpty()) return list;

        try {
            String clean = jsonText.trim();
            java.util.regex.Pattern fencePattern = java.util.regex.Pattern.compile(
                    "```(?:json)?\\s*([\\s\\S]*?)\\s*```", java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher fenceMatcher = fencePattern.matcher(clean);
            if (fenceMatcher.find()) {
                clean = fenceMatcher.group(1).trim();
            }

            int firstObj = clean.indexOf('{');
            int firstArr = clean.indexOf('[');
            if (firstArr >= 0 && (firstObj < 0 || firstArr < firstObj)) {
                int lastArr = clean.lastIndexOf(']');
                if (lastArr > firstArr) clean = clean.substring(firstArr, lastArr + 1);
            } else if (firstObj >= 0) {
                int lastObj = clean.lastIndexOf('}');
                if (lastObj > firstObj) clean = clean.substring(firstObj, lastObj + 1);
            }

            clean = clean.replaceAll(",\\s*([}\\]])", "$1");

            JsonElement parsed = JsonParser.parseString(clean);
            JsonArray arr = null;
            if (parsed.isJsonObject()) {
                JsonObject root = parsed.getAsJsonObject();
                if (root.has("lines") && root.get("lines").isJsonArray()) {
                    arr = root.getAsJsonArray("lines");
                }
            } else if (parsed.isJsonArray()) {
                arr = parsed.getAsJsonArray();
            }

            if (arr != null) {
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject obj = el.getAsJsonObject();
                    LineTransliterationResult item = new LineTransliterationResult();
                    item.index = (obj.has("index") && !obj.get("index").isJsonNull()) ? obj.get("index").getAsInt() : -1;
                    item.romanji = (obj.has("romanji") && !obj.get("romanji").isJsonNull())
                            ? ScriptDetector.stripNonLatinCharacters(obj.get("romanji").getAsString()) : "";
                    item.translation = (obj.has("translation") && !obj.get("translation").isJsonNull())
                            ? obj.get("translation").getAsString() : "";

                    if (obj.has("wordsRomanji") && !obj.get("wordsRomanji").isJsonNull() && obj.get("wordsRomanji").isJsonArray()) {
                        JsonArray wr = obj.getAsJsonArray("wordsRomanji");
                        for (JsonElement elWord : wr) {
                            String rawToken = elWord != null && !elWord.isJsonNull() ? elWord.getAsString() : "";
                            item.wordsRomanji.add(ScriptDetector.stripNonLatinCharacters(rawToken));
                        }
                    }
                    list.add(item);
                }
            }
        } catch (Exception e) {
            try {
                Log.e(TAG, "Error parsing AI transliteration JSON", e);
            } catch (Throwable ignored) {}
        }
        return list;
    }

    public static String buildPromptPayload(String title, String artist, List<LyricsData.LyricsLine> batchLines, String targetLang) {
        return buildPromptPayload(title, artist, batchLines, targetLang, true);
    }

    public static String buildPromptPayload(String title, String artist, List<LyricsData.LyricsLine> batchLines, String targetLang, boolean includeTranslation) {
        String safeTargetLang = (targetLang != null && !targetLang.trim().isEmpty()) ? targetLang.trim() : "id";
        JsonObject promptObj = new JsonObject();
        promptObj.addProperty("title", title != null ? title : "");
        promptObj.addProperty("artist", artist != null ? artist : "");
        promptObj.addProperty("targetLanguage", safeTargetLang);
        promptObj.addProperty("includeTranslation", includeTranslation);

        JsonArray linesArr = new JsonArray();
        if (batchLines != null) {
            for (int i = 0; i < batchLines.size(); i++) {
                LyricsData.LyricsLine line = batchLines.get(i);
                JsonObject lineObj = new JsonObject();
                lineObj.addProperty("index", i);
                lineObj.addProperty("text", line != null && line.text != null ? line.text : "");

                JsonArray syllArr = new JsonArray();
                if (line != null && line.words != null) {
                    for (LyricsData.Word w : line.words) {
                        syllArr.add(w != null && w.text != null ? w.text : "");
                    }
                }
                lineObj.addProperty("syllableCount", syllArr.size());
                lineObj.add("syllables", syllArr);
                linesArr.add(lineObj);
            }
        }
        promptObj.add("lines", linesArr);

        String translationRule = includeTranslation
                ? "2. For each line, translate the meaning accurately into targetLanguage (" + safeTargetLang + ").\n"
                : "2. The user requested transliteration ONLY. Leave \"translation\" as empty string \"\" for all lines.\n";

        return "You are an expert multilingual song lyrics transliteration and translation engine.\n" +
                "Given song lines with syllable segmentation, output a strict JSON object with a \"lines\" array.\n" +
                "Rules:\n" +
                "1. Transliterate non-Latin scripts (Japanese Kanji/Kana to Hepburn Romaji, Chinese Hanzi to Pinyin, Korean to Revised Romanization, Russian to Latin). Output 'romanji' and 'wordsRomanji' in STRICT LATIN CHARACTERS ONLY. Under no circumstances should original Japanese Kanji/Kana or Chinese characters appear in transliteration fields.\n" +
                translationRule +
                "3. CRITICAL: For \"wordsRomanji\", provide an array of strings of EXACTLY THE SAME LENGTH as input \"syllables\".\n" +
                "   - Each element in \"wordsRomanji\" must correspond 1:1 to the syllable or character at the exact same index in \"syllables\".\n" +
                "   - For Japanese Kanji compounds or Chinese characters: do NOT group or merge them into whole words! Decompose reading into each character. For example, if syllables is [\"前\", \"前\", \"前\", \"世\"], wordsRomanji MUST be [\"zen\", \"zen\", \"zen\", \"se\"], NOT [\"zenzenzense\"]. If syllables is [\"世\", \"界\"], wordsRomanji MUST be [\"se\", \"kai\"]. If input syllable is already Latin, keep it as is.\n" +
                "Output ONLY valid JSON with schema:\n" +
                "{\n" +
                "  \"lines\": [\n" +
                "    {\n" +
                "      \"index\": 0,\n" +
                "      \"romanji\": \"full line transliteration\",\n" +
                "      \"translation\": \"full line translation\",\n" +
                "      \"wordsRomanji\": [\"syllable1\", \"syllable2\"]\n" +
                "    }\n" +
                "  ]\n" +
                "}\n\n" +
                "Payload:\n" +
                promptObj.toString();
    }

    public AntigravityApiClient.Cancellable transliterateSyllablesAsync(
            final LyricsData data,
            final String trackTitle,
            final String artist,
            final String targetLang,
            final Callback callback) {
        return transliterateSyllablesAsync(data, trackTitle, artist, targetLang, true, callback);
    }

    public AntigravityApiClient.Cancellable transliterateSyllablesAsync(
            final LyricsData data,
            final String trackTitle,
            final String artist,
            final String targetLang,
            final boolean includeTranslation,
            final Callback callback) {
        final BatchCancellable compositeCancellable = new BatchCancellable();

        if (data == null || data.lines == null || data.lines.isEmpty()) {
            dispatchError("Lyrics data is empty", callback);
            return compositeCancellable;
        }

        if (apiClient == null) {
            dispatchError("AntigravityApiClient is null", callback);
            return compositeCancellable;
        }

        final List<LyricsData.LyricsLine> targetLines = new ArrayList<>();
        for (LyricsData.LyricsLine line : data.lines) {
            if (line != null && !line.isInterlude && line.text != null && !line.text.trim().isEmpty()) {
                targetLines.add(line);
            }
        }

        if (targetLines.isEmpty()) {
            data.hasTransliteration = true;
            data.sourceScript = ScriptDetector.detectScript(collectAllLinesText(data)).name();
            dispatchSuccess(data, callback);
            return compositeCancellable;
        }

        final int totalLines = targetLines.size();
        final List<List<LyricsData.LyricsLine>> batches = new ArrayList<>();
        for (int i = 0; i < totalLines; i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, totalLines);
            batches.add(new ArrayList<>(targetLines.subList(i, end)));
        }

        processBatch(0, batches, 0, totalLines, data, trackTitle, artist, targetLang, includeTranslation, callback, compositeCancellable);
        return compositeCancellable;
    }

    private void processBatch(
            final int batchIndex,
            final List<List<LyricsData.LyricsLine>> batches,
            final int processedSoFar,
            final int totalLines,
            final LyricsData data,
            final String trackTitle,
            final String artist,
            final String targetLang,
            final boolean includeTranslation,
            final Callback callback,
            final BatchCancellable cancellable) {
        if (cancellable.isCancelled()) {
            return;
        }

        if (batchIndex >= batches.size()) {
            data.sourceScript = ScriptDetector.detectScript(collectAllLinesText(data)).name();
            data.hasTransliteration = true;

            if (cacheManager != null) {
                try {
                    TrackInfo trackInfo = new TrackInfo();
                    trackInfo.title = trackTitle != null ? trackTitle : "";
                    trackInfo.artist = artist != null ? artist : "";
                    cacheManager.cache(trackInfo, data);
                } catch (Exception e) {
                    try {
                        Log.e(TAG, "Failed to cache transliterated lyrics", e);
                    } catch (Throwable ignored) {}
                }
            }

            dispatchSuccess(data, callback);
            return;
        }

        final List<LyricsData.LyricsLine> currentBatch = batches.get(batchIndex);
        String prompt = buildPromptPayload(trackTitle, artist, currentBatch, targetLang, includeTranslation);
        List<AiChatMessage> messages = Collections.singletonList(AiChatMessage.user(prompt));

        AntigravityApiClient.Cancellable call = apiClient.generateContent(
                DEFAULT_MODEL,
                messages,
                false,
                "low",
                new AntigravityApiClient.CompletionCallback() {
                    @Override
                    public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                        if (cancellable.isCancelled()) {
                            return;
                        }

                        List<LineTransliterationResult> results = parseJsonResponse(fullText);
                        for (int i = 0; i < currentBatch.size(); i++) {
                            LyricsData.LyricsLine line = currentBatch.get(i);
                            LineTransliterationResult match = findResultForIndex(results, i);
                            if (match != null) {
                                if (includeTranslation && match.translation != null && !match.translation.trim().isEmpty()) {
                                    line.translation = match.translation.trim();
                                }
                                alignSyllables(line, match.wordsRomanji, match.romanji);
                            }
                        }

                        int updatedProcessed = processedSoFar + currentBatch.size();
                        dispatchProgress(updatedProcessed, totalLines, callback);

                        processBatch(batchIndex + 1, batches, updatedProcessed, totalLines,
                                data, trackTitle, artist, targetLang, includeTranslation, callback, cancellable);
                    }

                    @Override
                    public void onError(Exception error) {
                        if (cancellable.isCancelled()) {
                            return;
                        }
                        String msg = error != null && error.getMessage() != null ? error.getMessage() : "Unknown error";
                        dispatchError("AI Transliteration failed: " + msg, callback);
                    }
                });

        cancellable.setCurrentCall(call);
    }

    private static String collectAllLinesText(LyricsData data) {
        if (data == null || data.lines == null) return "";
        StringBuilder sb = new StringBuilder();
        for (LyricsData.LyricsLine line : data.lines) {
            if (line != null && line.text != null) {
                sb.append(line.text).append(" ");
            }
        }
        return sb.toString();
    }

    private static LineTransliterationResult findResultForIndex(List<LineTransliterationResult> results, int index) {
        if (results == null || results.isEmpty()) return null;
        for (LineTransliterationResult r : results) {
            if (r.index == index) {
                return r;
            }
        }
        boolean hasValidIndices = false;
        for (LineTransliterationResult r : results) {
            if (r.index >= 0) {
                hasValidIndices = true;
                break;
            }
        }
        if (!hasValidIndices && index >= 0 && index < results.size()) {
            return results.get(index);
        }
        return null;
    }

    private void dispatchSuccess(final LyricsData data, final Callback callback) {
        if (callback == null) return;
        if (mainHandler != null) {
            mainHandler.post(() -> callback.onSuccess(data));
        } else {
            callback.onSuccess(data);
        }
    }

    private void dispatchError(final String message, final Callback callback) {
        if (callback == null) return;
        if (mainHandler != null) {
            mainHandler.post(() -> callback.onError(message));
        } else {
            callback.onError(message);
        }
    }

    private void dispatchProgress(final int processed, final int total, final Callback callback) {
        if (callback == null) return;
        if (mainHandler != null) {
            mainHandler.post(() -> callback.onProgress(processed, total));
        } else {
            callback.onProgress(processed, total);
        }
    }

    private static class BatchCancellable implements AntigravityApiClient.Cancellable {
        private volatile boolean cancelled = false;
        private AntigravityApiClient.Cancellable currentCall;

        public synchronized void setCurrentCall(AntigravityApiClient.Cancellable call) {
            this.currentCall = call;
            if (cancelled && call != null) {
                call.cancel();
            }
        }

        @Override
        public synchronized void cancel() {
            cancelled = true;
            if (currentCall != null) {
                currentCall.cancel();
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}
