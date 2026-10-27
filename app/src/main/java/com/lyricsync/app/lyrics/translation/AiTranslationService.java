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
import com.lyricsync.app.ai.util.JsonResponse;
import com.lyricsync.app.lyrics.model.LyricsData;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Translates lyrics with the AI model and falls back to Google Translate when AI is off,
 * the user is signed out, the call fails or times out, or the answer covers too few lines.
 * Only the latest request may deliver a result.
 */
public class AiTranslationService {

    public static final String PREF_AI_TRANSLATION_ENABLED = "pref_ai_translation_enabled";
    public static final String DEFAULT_MODEL = "gemini-3.8-flash-high";
    /** A whole song in one call; a thinking model needs more than a few seconds. */
    static final long DEFAULT_TIMEOUT_MS = 30_000;
    /** Below this share of translated lines the AI answer is dropped for Google's. */
    static final float MIN_COVERAGE = 0.8f;

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ai-translation-timeout");
        t.setDaemon(true);
        return t;
    });

    private final SharedPreferences prefs;
    private final AntigravityAuthManager auth;
    private final AntigravityApiClient api;
    private final GoogleTranslateService google;
    private final long timeoutMs;
    private final Handler mainHandler;

    private int generation;
    private AntigravityApiClient.Cancellable activeCall;
    private ScheduledFuture<?> timeout;

    public AiTranslationService(Context context, AntigravityAuthManager auth,
                                AntigravityApiClient api, GoogleTranslateService google) {
        this(context.getSharedPreferences("lyricsync", Context.MODE_PRIVATE), auth, api, google,
                DEFAULT_TIMEOUT_MS, new Handler(Looper.getMainLooper()));
    }

    /** {@code mainHandler} null runs callbacks inline (tests). */
    AiTranslationService(SharedPreferences prefs, AntigravityAuthManager auth, AntigravityApiClient api,
                         GoogleTranslateService google, long timeoutMs, Handler mainHandler) {
        this.prefs = prefs;
        this.auth = auth;
        this.api = api;
        this.google = google;
        this.timeoutMs = timeoutMs;
        this.mainHandler = mainHandler;
    }

    /** Translates {@code allLines} in place (interludes and blank lines are skipped). */
    public synchronized void translateLyrics(List<LyricsData.LyricsLine> allLines, String targetLang,
                                             GoogleTranslateService.TranslationCallback callback) {
        cancel();
        final int gen = generation;
        final List<LyricsData.LyricsLine> lines = GoogleTranslateService.filterTranslatableLines(allLines);
        final String lang = GoogleTranslateService.resolveTargetLang(targetLang, Locale.getDefault());
        if (lines.isEmpty()) {
            deliver(gen, () -> callback.onSuccess(new GoogleTranslateParser.ParseResult(), lang));
            return;
        }
        boolean aiOn = prefs == null || prefs.getBoolean(PREF_AI_TRANSLATION_ENABLED, true);
        if (!aiOn || api == null || auth == null || !auth.isAuthenticated()) {
            google.translateLines(allLines, targetLang, callback);
            return;
        }

        List<AiChatMessage> prompt = Collections.singletonList(AiChatMessage.user(buildPrompt(lines, lang)));
        timeout = TIMER.schedule(() -> fallback(gen, allLines, targetLang, callback), timeoutMs, TimeUnit.MILLISECONDS);
        activeCall = api.generateContent(DEFAULT_MODEL, prompt, false, "low", new AntigravityApiClient.CompletionCallback() {
            @Override
            public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                GoogleTranslateParser.ParseResult result = apply(fullText, lines, lang);
                if (result == null) {
                    fallback(gen, allLines, targetLang, callback);
                } else {
                    finish(gen, () -> callback.onSuccess(result, lang));
                }
            }

            @Override
            public void onError(Exception error) {
                fallback(gen, allLines, targetLang, callback);
            }
        });
    }

    public synchronized void cancel() {
        generation++;
        if (timeout != null) timeout.cancel(false);
        if (activeCall != null) activeCall.cancel();
        timeout = null;
        activeCall = null;
        if (google != null) google.cancel();
    }

    /** The current request hands over to Google; a superseded one does nothing. */
    private void fallback(int gen, List<LyricsData.LyricsLine> allLines, String targetLang,
                          GoogleTranslateService.TranslationCallback callback) {
        synchronized (this) {
            if (gen != generation) return;
            generation++;
            if (timeout != null) timeout.cancel(false);
            if (activeCall != null) activeCall.cancel();
            timeout = null;
            activeCall = null;
        }
        google.translateLines(allLines, targetLang, callback);
    }

    private void finish(int gen, Runnable action) {
        synchronized (this) {
            if (gen != generation) return;
            generation++;
            if (timeout != null) timeout.cancel(false);
            timeout = null;
            activeCall = null;
        }
        post(action);
    }

    private void deliver(int gen, Runnable action) {
        synchronized (this) {
            if (gen != generation) return;
        }
        post(action);
    }

    private void post(Runnable action) {
        if (mainHandler == null) action.run();
        else mainHandler.post(action);
    }

    static String buildPrompt(List<LyricsData.LyricsLine> lines, String targetLang) {
        String language = languageName(targetLang);
        JsonArray input = new JsonArray();
        for (int i = 0; i < lines.size(); i++) {
            JsonObject o = new JsonObject();
            o.addProperty("id", i);
            o.addProperty("text", lines.get(i).text.trim());
            input.add(o);
        }
        return "You are a professional song-lyrics translator. Translate every line below into "
                + language + " (" + targetLang + ").\n\n"
                + "Quality rules:\n"
                + "- Read all lines first and translate with the whole song's context, so tone, speaker and tense stay consistent.\n"
                + "- Translate meaning and feeling, not word for word: natural, fluent " + language
                + " that a native speaker would actually write as subtitles. Keep imagery and metaphors; use an idiomatic equivalent when a literal one sounds wrong.\n"
                + "- Keep each line about as short as the original so it fits on screen as a one-line subtitle.\n"
                + "- Repeated lines get the identical translation every time.\n"
                + "- Keep names, places and brand names as they are. Vocalisations (oh, yeah, la la) stay unchanged.\n"
                + "- A line already written in " + language + " is returned unchanged.\n"
                + "- No notes, explanations, romanisation or quotation marks inside the text.\n\n"
                + "Answer with JSON only, no code fence, exactly one entry per input id:\n"
                + "{\"sourceLanguage\":\"<ISO 639-1 code of the lyrics>\",\"lines\":[{\"id\":0,\"text\":\"...\"}]}\n\n"
                + "Lines:\n" + input;
    }

    /**
     * Writes the model's translations into {@code lines}. Returns null — and leaves the lines
     * untouched — when the answer is unusable or covers less than {@link #MIN_COVERAGE}.
     */
    static GoogleTranslateParser.ParseResult apply(String answer, List<LyricsData.LyricsLine> lines, String targetLang) {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(JsonResponse.extract(answer));
            if (parsed.isJsonArray()) {
                root = new JsonObject();
                root.add("lines", parsed);
            } else {
                root = parsed.getAsJsonObject();
            }
        } catch (RuntimeException e) {
            return null;
        }

        GoogleTranslateParser.ParseResult result = new GoogleTranslateParser.ParseResult();
        String source = string(root, "sourceLanguage");
        if (source != null) result.detectedSourceLang = source.toLowerCase(Locale.ROOT);
        if (targetLang.equalsIgnoreCase(result.detectedSourceLang)) {
            result.isSameLanguage = true;
            return result;
        }

        JsonArray items = root.has("lines") && root.get("lines").isJsonArray() ? root.getAsJsonArray("lines") : null;
        if (items == null) return null;
        String[] texts = new String[lines.size()];
        int count = 0;
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isJsonObject()) continue;
            JsonObject item = items.get(i).getAsJsonObject();
            int id = item.has("id") ? asInt(item.get("id")) : i;
            String text = string(item, "text");
            if (id < 0 || id >= texts.length || text == null || text.trim().isEmpty() || texts[id] != null) continue;
            texts[id] = text.trim();
            count++;
        }
        if (count < Math.ceil(lines.size() * MIN_COVERAGE)) return null;
        for (int i = 0; i < texts.length; i++) {
            if (texts[i] != null) lines.get(i).translation = texts[i];
        }
        result.translatedLineCount = count;
        return result;
    }

    /** "id" → "Indonesian"; unknown codes stay as they are. */
    public static String languageName(String code) {
        String name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH);
        return name == null || name.isEmpty() || name.equalsIgnoreCase(code) ? code : name;
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
}
