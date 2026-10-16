package com.lyricsync.app.ai.service;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.ai.util.JsonResponse;
import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.ai.model.AiChatMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for analyzing the emotional mood and deeper artistic meaning of songs
 * using Google Antigravity Cloudcode PA endpoints with persistent caching and
 * autonomous agent tool calling (Google Search & Web Fetch).
 */
public class SongMeaningService {

    public static final String DEFAULT_MODEL = "gemini-3.8-flash-high";
    public static final String PREF_CACHE_PREFIX = "pref_song_meaning_";
    public static final String PREF_NAME = "lyricsync";

    /**
     * Data class holding structured song meaning analysis and grounding web sources.
     */
    public static class SongMeaning {
        public String mood;
        public String summary;
        public List<String> webSources;

        public SongMeaning() {
            this.webSources = new ArrayList<>();
        }

        public SongMeaning(String mood, String summary, List<String> webSources) {
            this.mood = mood;
            this.summary = summary;
            this.webSources = webSources != null ? new ArrayList<>(webSources) : new ArrayList<String>();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SongMeaning that = (SongMeaning) o;
            return Objects.equals(mood, that.mood) &&
                    Objects.equals(summary, that.summary) &&
                    Objects.equals(webSources, that.webSources);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mood, summary, webSources);
        }

        @Override
        public String toString() {
            return "SongMeaning{" +
                    "mood='" + mood + '\'' +
                    ", summary='" + summary + '\'' +
                    ", webSources=" + webSources +
                    '}';
        }
    }

    public interface MeaningCallback {
        void onResult(SongMeaning meaning, boolean fromCache);
        void onError(Exception e);
    }

    private final SharedPreferences prefs;
    private final AntigravityApiClient apiClient;
    private final String modelId;
    private final Map<String, SongMeaning> memoryCache = new ConcurrentHashMap<>();

    public SongMeaningService(Context context, AntigravityApiClient apiClient) {
        this(context != null ? context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) : null,
                apiClient, DEFAULT_MODEL);
    }

    public SongMeaningService(SharedPreferences prefs, AntigravityApiClient apiClient) {
        this(prefs, apiClient, DEFAULT_MODEL);
    }

    public SongMeaningService(SharedPreferences prefs, AntigravityApiClient apiClient, String modelId) {
        this.prefs = prefs;
        this.apiClient = apiClient;
        this.modelId = (modelId != null && !modelId.trim().isEmpty()) ? modelId.trim() : DEFAULT_MODEL;
    }

    /**
     * Generates a unique, normalized cache key for the given artist and title.
     * Computes MD5 of lowercase trimmed artist + "_" + title.
     */
    public static String getCacheKey(String artist, String title) {
        String a = artist != null ? artist.trim().toLowerCase(Locale.ROOT) : "";
        String t = title != null ? title.trim().toLowerCase(Locale.ROOT) : "";
        String input = a + "_" + t;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm unavailable", e);
        }
    }

    /**
     * Strips markdown code blocks (e.g. ```json ... ``` or ``` ... ```) case-insensitively,
     * and extracts the outermost JSON object substring if surrounded by commentary.
     */
    public static String cleanJsonFence(String rawResponse) {
        return JsonResponse.extract(rawResponse);
    }

    /**
     * Builds structured prompt instructing Gemini to analyze song meaning, grounding in real Google Search facts
     * when enabled, and return JSON in Indonesian.
     */
    public static String buildMeaningPrompt(String title, String artist, String lyrics, boolean enableWebSearch) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analisis makna lagu, emosi dominan, dan interpretasi artistik berikut:\n");
        sb.append("Tuliskan analisis dalam Bahasa Indonesia.\n");
        sb.append("Judul: ").append(title != null ? title.trim() : "").append("\n");
        sb.append("Artis: ").append(artist != null ? artist.trim() : "").append("\n");
        if (enableWebSearch) {
            sb.append("\n[PENTING] Lakukan penelusuran Google Search secara aktif untuk menemukan fakta riil, latar belakang resmi, wawancara pencipta lagu/musisi, dan konteks album. Jelaskan dengan akurat dan faktual, bukan sekadar tebakan atau asumsi.\n");
        }
        sb.append("\nLirik Lagu:\n").append(lyrics != null ? lyrics.trim() : "").append("\n\n");
        sb.append("Berikan output HANYA dalam format JSON valid tanpa format markdown codeblock:\n");
        sb.append("{\n");
        sb.append("  \"mood\": \"Sebutkan 1-3 kata emosi dominan (contoh: Melancholic, Nostalgic, Hopeful)\",\n");
        sb.append("  \"summary\": \"Penjelasan ringkas makna lagu, cerita di baliknya, dan pesan moralnya dalam 2-4 kalimat berbahasa Indonesia yang mendalam\"\n");
        sb.append("}");
        return sb.toString();
    }

    public static String buildMeaningPrompt(String title, String artist, String fullLyrics) {
        return buildMeaningPrompt(title, artist, fullLyrics, false);
    }

    /**
     * Retrieves the meaning of a song, checking local caches first before querying the AI API.
     * When enableWebSearch is true, leverages the autonomous agent tooling harness for grounded factual analysis.
     * Returns a Cancellable handle to abort the network request if needed.
     */
    public AntigravityApiClient.Cancellable getSongMeaning(final String title, final String artist,
                                                          final String fullLyrics, final boolean enableWebSearch,
                                                          final MeaningCallback callback) {
        final String cacheKey = getCacheKey(artist, title);

        // 1. Check in-memory cache
        SongMeaning cached = memoryCache.get(cacheKey);

        // 2. Check SharedPreferences if not present in memory
        if (cached == null && prefs != null) {
            String jsonStr = prefs.getString(PREF_CACHE_PREFIX + cacheKey, null);
            if (jsonStr != null && !jsonStr.trim().isEmpty()) {
                try {
                    JsonObject obj = JsonParser.parseString(jsonStr).getAsJsonObject();
                    String mood = obj.has("mood") && !obj.get("mood").isJsonNull() ? obj.get("mood").getAsString() : "";
                    String summary = obj.has("summary") && !obj.get("summary").isJsonNull() ? obj.get("summary").getAsString() : "";
                    List<String> sources = new ArrayList<>();
                    if (obj.has("webSources") && obj.get("webSources").isJsonArray()) {
                        for (JsonElement el : obj.getAsJsonArray("webSources")) {
                            if (el != null && el.isJsonPrimitive()) {
                                sources.add(el.getAsString());
                            }
                        }
                    }
                    cached = new SongMeaning(mood, summary, sources);
                    memoryCache.put(cacheKey, cached);
                } catch (Exception ignored) {}
            }
        }

        // Cache hit
        if (cached != null) {
            if (callback != null) {
                try {
                    callback.onResult(cached, true);
                } catch (Throwable ignored) {}
            }
            return new NoOpCancellable();
        }

        // Cache miss: query API
        if (apiClient == null) {
            if (callback != null) {
                try {
                    callback.onError(new IllegalStateException("AntigravityApiClient is null"));
                } catch (Throwable ignored) {}
            }
            return new NoOpCancellable();
        }

        String prompt = buildMeaningPrompt(title, artist, fullLyrics, enableWebSearch);
        List<AiChatMessage> messages = Collections.singletonList(AiChatMessage.user(prompt));

        return apiClient.generateContent(modelId, messages, enableWebSearch, null, new AntigravityApiClient.CompletionCallback() {
            @Override
            public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                SongMeaning parsedMeaning = null;
                Exception parseException = null;

                try {
                    String cleaned = cleanJsonFence(fullText);
                    JsonObject json = JsonParser.parseString(cleaned).getAsJsonObject();
                    String mood = json.has("mood") && !json.get("mood").isJsonNull() ? json.get("mood").getAsString() : "";
                    String summary = json.has("summary") && !json.get("summary").isJsonNull() ? json.get("summary").getAsString() : "";
                    List<String> finalSources = new ArrayList<>();
                    if (webSources != null) {
                        for (String src : webSources) {
                            if (src != null && !src.trim().isEmpty() && !finalSources.contains(src.trim())) {
                                finalSources.add(src.trim());
                            }
                        }
                    }
                    if (json.has("sources") && json.get("sources").isJsonArray()) {
                        for (JsonElement el : json.getAsJsonArray("sources")) {
                            if (el != null && el.isJsonPrimitive()) {
                                String src = el.getAsString().trim();
                                if (!src.isEmpty() && !finalSources.contains(src)) {
                                    finalSources.add(src);
                                }
                            }
                        }
                    } else if (json.has("webSources") && json.get("webSources").isJsonArray()) {
                        for (JsonElement el : json.getAsJsonArray("webSources")) {
                            if (el != null && el.isJsonPrimitive()) {
                                String src = el.getAsString().trim();
                                if (!src.isEmpty() && !finalSources.contains(src)) {
                                    finalSources.add(src);
                                }
                            }
                        }
                    }
                    parsedMeaning = new SongMeaning(mood, summary, finalSources);

                    // Populate memory cache
                    memoryCache.put(cacheKey, parsedMeaning);

                    // Persist to SharedPreferences
                    if (prefs != null) {
                        JsonObject cacheObj = new JsonObject();
                        cacheObj.addProperty("mood", parsedMeaning.mood);
                        cacheObj.addProperty("summary", parsedMeaning.summary);
                        if (parsedMeaning.webSources != null) {
                            JsonArray arr = new JsonArray();
                            for (String src : parsedMeaning.webSources) {
                                arr.add(src);
                            }
                            cacheObj.add("webSources", arr);
                        }
                        prefs.edit().putString(PREF_CACHE_PREFIX + cacheKey, cacheObj.toString()).apply();
                    }
                } catch (Exception e) {
                    parseException = e;
                }

                // Callback dispatch is OUTSIDE parsing try-catch to avoid double-callback
                if (parseException != null) {
                    if (callback != null) {
                        try {
                            callback.onError(parseException);
                        } catch (Throwable ignored) {}
                    }
                } else if (parsedMeaning != null) {
                    if (callback != null) {
                        try {
                            callback.onResult(parsedMeaning, false);
                        } catch (Throwable ignored) {
                            // Caller runtime exceptions in onResult must NOT cascade into onError
                        }
                    }
                }
            }

            @Override
            public void onError(Exception error) {
                if (callback != null) {
                    try {
                        callback.onError(error);
                    } catch (Throwable ignored) {}
                }
            }
        });
    }

    public void putMemoryCache(String artist, String title, SongMeaning meaning) {
        if (meaning != null) {
            memoryCache.put(getCacheKey(artist, title), meaning);
        }
    }

    public SongMeaning getMemoryCache(String cacheKey) {
        return memoryCache.get(cacheKey);
    }

    /**
     * Clears all cached song meanings from both in-memory cache and SharedPreferences.
     */
    public void clearCache() {
        memoryCache.clear();
        if (prefs != null) {
            SharedPreferences.Editor editor = prefs.edit();
            Map<String, ?> all = prefs.getAll();
            if (all != null) {
                for (String key : all.keySet()) {
                    if (key.startsWith(PREF_CACHE_PREFIX)) {
                        editor.remove(key);
                    }
                }
            }
            editor.apply();
        }
    }

    private static class NoOpCancellable implements AntigravityApiClient.Cancellable {
        @Override
        public void cancel() {}

        @Override
        public boolean isCancelled() {
            return false;
        }
    }
}
