package com.lyricsync.app.ai.client;

import android.content.SharedPreferences;

import java.util.Locale;

import okhttp3.HttpUrl;

/**
 * Which backend answers the AI: Google Antigravity (default) or any OpenAI-compatible
 * API (DeepSeek, River, OpenRouter, a local server…), ChatGPT sign-in (Codex), and which engine answers
 * {@code web_search}. Settings live in the shared "lyricsync" prefs; keys are never logged.
 */
public final class AiProvider {
    private AiProvider() {}

    public static final String PREF_CUSTOM_ENABLED = "pref_ai_custom_enabled";
    public static final String PREF_BASE_URL = "pref_ai_custom_base_url";
    public static final String PREF_API_KEY = "pref_ai_custom_api_key";
    public static final String PREF_MODEL = "pref_ai_custom_model";
    public static final String PREF_EFFORT = "pref_ai_custom_effort";
    public static final String PREF_EXA_KEY = "pref_exa_api_key";
    /** Which provider answers: "" Google, {@link #ACTIVE_CODEX}, or a saved profile id. */
    public static final String PREF_ACTIVE = "pref_ai_active_profile";
    public static final String ACTIVE_CODEX = "codex";
    public static final String PREF_CODEX_MODEL = "pref_codex_model";
    public static final String PREF_CODEX_EFFORT = "pref_codex_effort";
    public static final String CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex";
    public static final String PREF_EXA_FIRST = "pref_web_search_exa_first";

    public static final String ENGINE_GEMINI = "gemini";
    public static final String ENGINE_EXA = "exa";

    /** Snapshot of the provider settings for one request. */
    public static final class Settings {
        public final boolean customEnabled;
        public final String baseUrl;
        public final String apiKey;
        public final String model;
        public final String exaKey;
        public final boolean exaFirst;
        /** ChatGPT sign-in (Codex Responses API) instead of an OpenAI-compatible key. */
        public final boolean codex;
        /** Reasoning effort to request ("low", "medium", "high", …); "" = the model's default. */
        public final String effort;

        public Settings(boolean customEnabled, String baseUrl, String apiKey, String model,
                        String exaKey, boolean exaFirst) {
            this(customEnabled, baseUrl, apiKey, model, exaKey, exaFirst, false, "");
        }

        public Settings(boolean customEnabled, String baseUrl, String apiKey, String model,
                        String exaKey, boolean exaFirst, boolean codex, String effort) {
            this.codex = codex;
            this.effort = trim(effort).toLowerCase(Locale.ROOT);
            this.customEnabled = customEnabled;
            this.baseUrl = normalizeBaseUrl(baseUrl);
            this.apiKey = trim(apiKey);
            this.model = trim(model);
            this.exaKey = trim(exaKey);
            this.exaFirst = exaFirst;
        }

        /** The custom provider is on and has what a request needs (the key may be empty for local servers). */
        public boolean customReady() {
            return customEnabled && !baseUrl.isEmpty() && !model.isEmpty();
        }
    }

    public static Settings read(SharedPreferences p) {
        if (p == null) return new Settings(false, "", "", "", "", false);
        boolean on = p.getBoolean(PREF_CUSTOM_ENABLED, false);
        String exa = p.getString(PREF_EXA_KEY, "");
        boolean exaFirst = p.getBoolean(PREF_EXA_FIRST, false);
        if (on && ACTIVE_CODEX.equals(p.getString(PREF_ACTIVE, ""))) {
            return new Settings(true, CODEX_BASE_URL, "", p.getString(PREF_CODEX_MODEL, ""), exa, exaFirst,
                    true, p.getString(PREF_CODEX_EFFORT, ""));
        }
        return new Settings(on, p.getString(PREF_BASE_URL, ""), p.getString(PREF_API_KEY, ""),
                p.getString(PREF_MODEL, ""), exa, exaFirst, false, p.getString(PREF_EFFORT, ""));
    }

    /**
     * Accepts what users paste: a bare host, a trailing slash, or a full endpoint URL.
     * "api.deepseek.com" → "https://api.deepseek.com/v1"; ".../v1/chat/completions" → ".../v1".
     * Returns "" when it is not an http(s) URL.
     */
    public static String normalizeBaseUrl(String raw) {
        String s = trim(raw);
        if (s.isEmpty()) return "";
        if (!s.matches("(?i)^https?://.*")) s = "https://" + s;
        s = stripSlashes(s);
        String lower = s.toLowerCase(Locale.ROOT);
        for (String suffix : new String[]{"/chat/completions", "/completions", "/models"}) {
            if (lower.endsWith(suffix)) {
                s = stripSlashes(s.substring(0, s.length() - suffix.length()));
                break;
            }
        }
        HttpUrl url = HttpUrl.parse(s);
        if (url == null) return "";
        return "/".equals(url.encodedPath()) ? s + "/v1" : s;
    }

    /**
     * The engine for {@code web_search}: Gemini grounding needs a Google sign-in, Exa needs
     * a key. The preferred one is used when it can run, else the other; null when neither can.
     */
    public static String searchEngine(Settings s, boolean googleSignedIn) {
        boolean exa = s != null && !s.exaKey.isEmpty();
        boolean preferExa = s != null && s.exaFirst;
        if (preferExa && exa) return ENGINE_EXA;
        if (googleSignedIn) return ENGINE_GEMINI;
        return exa ? ENGINE_EXA : null;
    }

    /** "deepseek-ai/DeepSeek-V4.1-Flash" → "DeepSeek-V4.1-Flash", for small badges. */
    public static String shortModel(String model) {
        String m = trim(model);
        int slash = m.lastIndexOf('/');
        return slash >= 0 && slash < m.length() - 1 ? m.substring(slash + 1) : m;
    }

    private static String stripSlashes(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
