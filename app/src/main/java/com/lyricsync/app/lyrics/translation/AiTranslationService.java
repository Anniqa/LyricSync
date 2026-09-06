package com.lyricsync.app.lyrics.translation;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.ai.auth.AntigravityAuthManager;
import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.ai.model.AiChatMessage;
import com.lyricsync.app.lyrics.model.LyricsData;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dual-Engine Contextual Lyrics Translation service.
 * Translates song lyrics poetically using Google Antigravity Cloudcode PA endpoints
 * with automatic, seamless fallback to GoogleTranslateService on failure, timeout,
 * or unauthenticated state.
 */
public class AiTranslationService {

    public static final String PREF_AI_TRANSLATION_ENABLED = "pref_ai_translation_enabled";
    public static final String DEFAULT_MODEL = "gemini-2.5-flash";
    public static final long DEFAULT_TIMEOUT_MS = 8000;
    public static final String PREF_NAME = "lyricsync";

    private final SharedPreferences prefs;
    private final AntigravityAuthManager authManager;
    private final AntigravityApiClient apiClient;
    private final GoogleTranslateService fallbackService;
    private final ScheduledExecutorService timeoutExecutor;
    private final long timeoutMs;
    private final Handler mainHandler;
    private final String modelId;

    private AntigravityApiClient.Cancellable activeAiCall;
    private ScheduledFuture<?> timeoutFuture;
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);

    public AiTranslationService(Context context,
                                AntigravityApiClient apiClient,
                                GoogleTranslateService fallbackService) {
        this(context,
             context != null ? AntigravityAuthManager.getInstance(context) : null,
             apiClient,
             fallbackService);
    }

    public AiTranslationService(Context context,
                                AntigravityAuthManager authManager,
                                AntigravityApiClient apiClient,
                                GoogleTranslateService fallbackService) {
        this(context != null ? context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) : null,
             authManager,
             apiClient,
             fallbackService,
             Executors.newSingleThreadScheduledExecutor(),
             DEFAULT_TIMEOUT_MS,
             createSafeMainHandler());
    }

    public AiTranslationService(SharedPreferences prefs,
                                AntigravityAuthManager authManager,
                                AntigravityApiClient apiClient,
                                GoogleTranslateService fallbackService) {
        this(prefs,
             authManager,
             apiClient,
             fallbackService,
             Executors.newSingleThreadScheduledExecutor(),
             DEFAULT_TIMEOUT_MS,
             createSafeMainHandler());
    }

    public AiTranslationService(SharedPreferences prefs,
                                AntigravityAuthManager authManager,
                                AntigravityApiClient apiClient,
                                GoogleTranslateService fallbackService,
                                ScheduledExecutorService timeoutExecutor,
                                long timeoutMs,
                                Handler mainHandler) {
        this.prefs = prefs;
        this.authManager = authManager;
        this.apiClient = apiClient;
        this.fallbackService = fallbackService;
        this.timeoutExecutor = timeoutExecutor != null ? timeoutExecutor : Executors.newSingleThreadScheduledExecutor();
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        this.mainHandler = mainHandler;
        this.modelId = DEFAULT_MODEL;
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

    /**
     * Checks if AI translation is enabled in preferences. Defaults to true.
     */
    public boolean isAiTranslationEnabled() {
        if (prefs != null) {
            return prefs.getBoolean(PREF_AI_TRANSLATION_ENABLED, true);
        }
        return true;
    }

    /**
     * Checks if the user is authenticated with Google Antigravity.
     */
    public boolean isUserLoggedIn() {
        return authManager != null && authManager.isAuthenticated();
    }

    /**
     * Constructs a structured translation prompt with a numbered JSON array of translatable lines.
     */
    public static String buildTranslationPrompt(List<LyricsData.LyricsLine> translatableLines, String targetLang) {
        if (translatableLines == null || translatableLines.isEmpty()) {
            return "";
        }
        String lang = (targetLang != null && !targetLang.trim().isEmpty()) ? targetLang.trim() : "en";

        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert musical and poetic translator.\n");
        sb.append("Translate the following song lyrics into target language: ").append(lang).append(".\n\n");
        sb.append("Guidelines:\n");
        sb.append("1. Provide a natural, poetic, and contextually aware translation fitting song lyrics.\n");
        sb.append("2. Preserve the emotional resonance, metaphors, and artistic rhythm rather than literal word-for-word translation.\n");
        sb.append("3. Maintain strict 1-to-1 line alignment using the provided 'id' numbers.\n");
        sb.append("4. Output STRICTLY a JSON array of objects with \"id\" (integer) and \"text\" (translated line string):\n");
        sb.append("   [{\"id\": 0, \"text\": \"...\"}, {\"id\": 1, \"text\": \"...\"}]\n");
        sb.append("5. Return ONLY valid JSON without markdown code fences or conversational text.\n\n");
        sb.append("Input lyrics:\n");

        JsonArray array = new JsonArray();
        for (int i = 0; i < translatableLines.size(); i++) {
            LyricsData.LyricsLine line = translatableLines.get(i);
            JsonObject obj = new JsonObject();
            obj.addProperty("id", i);
            obj.addProperty("text", (line != null && line.text != null) ? line.text : "");
            array.add(obj);
        }
        sb.append(array.toString());

        return sb.toString();
    }

    /**
     * Strips markdown fences (```json ... ``` or ``` ... ```) and extracts outermost JSON array or object.
     */
    public static String cleanJson(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.isEmpty()) return "";

        // 1. Strip markdown code fences case-insensitively
        String lower = text.toLowerCase(Locale.ROOT);
        int startFence = lower.indexOf("```json");
        int fenceLen = 7;
        if (startFence == -1) {
            startFence = lower.indexOf("```");
            fenceLen = 3;
        }
        if (startFence != -1) {
            int contentStart = startFence + fenceLen;
            int endFence = text.indexOf("```", contentStart);
            if (endFence != -1) {
                text = text.substring(contentStart, endFence).trim();
            } else {
                text = text.substring(contentStart).trim();
            }
        }

        // 2. Extract outermost [ ... ] or { ... }
        int firstBracket = text.indexOf('[');
        int lastBracket = text.lastIndexOf(']');
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');

        if (firstBracket != -1 && lastBracket != -1 && lastBracket > firstBracket) {
            if (firstBrace == -1 || firstBracket < firstBrace) {
                return text.substring(firstBracket, lastBracket + 1).trim();
            }
        }

        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1).trim();
        }

        if (firstBracket != -1 && lastBracket != -1 && lastBracket > firstBracket) {
            return text.substring(firstBracket, lastBracket + 1).trim();
        }

        return text;
    }

    /**
     * Parses the AI response JSON array, maps translated lines to translatableLines,
     * and constructs a GoogleTranslateParser.ParseResult.
     */
    public static GoogleTranslateParser.ParseResult parseAiTranslation(String aiResponse,
                                                                       List<LyricsData.LyricsLine> translatableLines,
                                                                       String targetLang) {
        GoogleTranslateParser.ParseResult result = new GoogleTranslateParser.ParseResult();
        if (aiResponse == null || aiResponse.trim().isEmpty() || translatableLines == null || translatableLines.isEmpty()) {
            return result;
        }

        String cleaned = cleanJson(aiResponse);
        if (cleaned.isEmpty()) {
            return result;
        }

        try {
            JsonElement root = JsonParser.parseString(cleaned);
            JsonArray array = null;

            if (root.isJsonArray()) {
                array = root.getAsJsonArray();
            } else if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                if (obj.has("detectedSourceLang") && !obj.get("detectedSourceLang").isJsonNull()) {
                    result.detectedSourceLang = obj.get("detectedSourceLang").getAsString();
                } else if (obj.has("sourceLang") && !obj.get("sourceLang").isJsonNull()) {
                    result.detectedSourceLang = obj.get("sourceLang").getAsString();
                }

                if (obj.has("lines") && obj.get("lines").isJsonArray()) {
                    array = obj.getAsJsonArray("lines");
                } else if (obj.has("translations") && obj.get("translations").isJsonArray()) {
                    array = obj.getAsJsonArray("translations");
                } else if (obj.has("lyrics") && obj.get("lyrics").isJsonArray()) {
                    array = obj.getAsJsonArray("lyrics");
                } else {
                    for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                        if (entry.getValue().isJsonArray()) {
                            array = entry.getValue().getAsJsonArray();
                            break;
                        }
                    }
                }
            }

            if (array == null) {
                return result;
            }

            if (targetLang != null && !targetLang.isEmpty()
                    && !targetLang.equalsIgnoreCase("auto")
                    && targetLang.equalsIgnoreCase(result.detectedSourceLang)) {
                result.isSameLanguage = true;
                return result;
            }

            int count = 0;
            for (int i = 0; i < array.size(); i++) {
                JsonElement elem = array.get(i);
                if (!elem.isJsonObject()) continue;
                JsonObject item = elem.getAsJsonObject();

                int id = -1;
                if (item.has("id") && !item.get("id").isJsonNull()) {
                    try {
                        id = item.get("id").getAsInt();
                    } catch (Exception e) {
                        try {
                            id = Integer.parseInt(item.get("id").getAsString().trim());
                        } catch (Exception ignored) {}
                    }
                } else {
                    id = i;
                }

                if (id < 0 || id >= translatableLines.size()) {
                    continue;
                }

                String text = null;
                if (item.has("text") && !item.get("text").isJsonNull()) {
                    text = item.get("text").getAsString();
                } else if (item.has("translation") && !item.get("translation").isJsonNull()) {
                    text = item.get("translation").getAsString();
                } else if (item.has("translatedText") && !item.get("translatedText").isJsonNull()) {
                    text = item.get("translatedText").getAsString();
                } else if (item.has("translated_text") && !item.get("translated_text").isJsonNull()) {
                    text = item.get("translated_text").getAsString();
                }

                if (text != null) {
                    String trimmed = text.trim();
                    translatableLines.get(id).translation = trimmed.isEmpty() ? null : trimmed;
                    count++;
                }
            }
            result.translatedLineCount = count;
        } catch (Exception ignored) {}

        return result;
    }

    /**
     * Cancels any active AI translation call, timeout task, and fallback service request.
     */
    public synchronized void cancel() {
        isCancelled.set(true);
        cancelTimeout();
        cancelAiCall();
        if (fallbackService != null) {
            fallbackService.cancel();
        }
    }

    private synchronized void cancelTimeout() {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
    }

    private synchronized void cancelAiCall() {
        if (activeAiCall != null) {
            activeAiCall.cancel();
            activeAiCall = null;
        }
    }

    /**
     * Translates lyrics using AI if available, falling back smoothly to GoogleTranslateService.
     */
    public synchronized void translateLyrics(final List<LyricsData.LyricsLine> allLines,
                                            final String targetLang,
                                            final GoogleTranslateService.TranslationCallback callback) {
        cancel();
        isCancelled.set(false);

        final List<LyricsData.LyricsLine> translatable = GoogleTranslateService.filterTranslatableLines(allLines);
        if (translatable.isEmpty()) {
            dispatchSuccess(new GoogleTranslateParser.ParseResult(), targetLang, callback);
            return;
        }

        final String finalTargetLang = GoogleTranslateService.resolveTargetLang(targetLang, Locale.getDefault());

        // Global constraint: fallback if AI translation disabled, not logged in, or apiClient null
        if (!isAiTranslationEnabled() || !isUserLoggedIn() || apiClient == null) {
            fallbackToGoogle(allLines, targetLang, callback);
            return;
        }

        String prompt = buildTranslationPrompt(translatable, finalTargetLang);
        List<AiChatMessage> messages = Collections.singletonList(new AiChatMessage("user", prompt));

        final AtomicBoolean handled = new AtomicBoolean(false);

        // Schedule timeout fallback
        timeoutFuture = timeoutExecutor.schedule(() -> {
            if (isCancelled.get()) return;
            if (handled.compareAndSet(false, true)) {
                cancelAiCall();
                fallbackToGoogle(allLines, targetLang, callback);
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        try {
            activeAiCall = apiClient.generateContent(modelId, messages, false, "low",
                    new AntigravityApiClient.CompletionCallback() {
                @Override
                public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                    if (isCancelled.get()) return;
                    if (handled.compareAndSet(false, true)) {
                        cancelTimeout();
                        try {
                            GoogleTranslateParser.ParseResult result =
                                    parseAiTranslation(fullText, translatable, finalTargetLang);
                            if (result.translatedLineCount > 0 || result.isSameLanguage) {
                                dispatchSuccess(result, finalTargetLang, callback);
                            } else {
                                fallbackToGoogle(allLines, targetLang, callback);
                            }
                        } catch (Exception e) {
                            fallbackToGoogle(allLines, targetLang, callback);
                        }
                    }
                }

                @Override
                public void onError(Exception error) {
                    if (isCancelled.get()) return;
                    if (handled.compareAndSet(false, true)) {
                        cancelTimeout();
                        fallbackToGoogle(allLines, targetLang, callback);
                    }
                }
            });
        } catch (Exception e) {
            if (handled.compareAndSet(false, true)) {
                cancelTimeout();
                fallbackToGoogle(allLines, targetLang, callback);
            }
        }
    }

    private void fallbackToGoogle(List<LyricsData.LyricsLine> allLines,
                                  String targetLang,
                                  GoogleTranslateService.TranslationCallback callback) {
        if (fallbackService != null) {
            fallbackService.translateLines(allLines, targetLang, callback);
        } else if (callback != null) {
            dispatchError(new IOException("AiTranslation failed and fallback service is null"), callback);
        }
    }

    private void dispatchSuccess(GoogleTranslateParser.ParseResult result,
                                String targetLang,
                                GoogleTranslateService.TranslationCallback callback) {
        if (callback == null) return;
        if (mainHandler == null) {
            callback.onSuccess(result, targetLang);
        } else {
            mainHandler.post(() -> callback.onSuccess(result, targetLang));
        }
    }

    private void dispatchError(Exception error, GoogleTranslateService.TranslationCallback callback) {
        if (callback == null) return;
        if (mainHandler == null) {
            callback.onError(error);
        } else {
            mainHandler.post(() -> callback.onError(error));
        }
    }
}
