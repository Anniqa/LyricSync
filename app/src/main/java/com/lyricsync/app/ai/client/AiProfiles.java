package com.lyricsync.app.ai.client;

import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Saved OpenAI-compatible providers (DeepSeek, River, …) and which one is active.
 *
 * <p>Profiles are stored as JSON; the <em>active</em> one is mirrored into the four
 * {@code pref_ai_custom_*} keys that {@link AiProvider#read} and the overlay already use,
 * so switching or saving applies immediately with no other code involved. No active
 * profile means Google Antigravity answers.
 */
public final class AiProfiles {
    private AiProfiles() {}

    public static final String PREF_PROFILES = "pref_ai_profiles";
    public static final String PREF_ACTIVE = AiProvider.PREF_ACTIVE;
    /** Reasoning effort choices offered in the UI; "" = let the model decide. */
    public static final String[] EFFORTS = {"", "low", "medium", "high"};

    /** Ready-made OpenAI-compatible starting points; {@code keyUrl} is where the user gets an API key. */
    public static final class Preset {
        public final String name, baseUrl, keyUrl;

        Preset(String name, String baseUrl, String keyUrl) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.keyUrl = keyUrl;
        }
    }

    public static final Preset[] PRESETS = {
            new Preset("DeepSeek", "https://api.deepseek.com/v1", "https://platform.deepseek.com/api_keys"),
            new Preset("River", "https://api.river.ai/v1", null),
            new Preset("OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys"),
            new Preset("OpenAI", "https://api.openai.com/v1", "https://platform.openai.com/api-keys"),
    };

    public static final class Profile {
        public String id = "";
        public String name = "";
        public String baseUrl = "";
        public String apiKey = "";
        public String model = "";
        /** "", "low", "medium" or "high"; sent as {@code reasoning_effort} when set. */
        public String effort = "";

        public Profile() {}

        public Profile(String id, String name, String baseUrl, String apiKey, String model, String effort) {
            this(id, name, baseUrl, apiKey, model);
            this.effort = effort == null ? "" : effort.trim();
        }

        public Profile(String id, String name, String baseUrl, String apiKey, String model) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name.trim();
            this.baseUrl = AiProvider.normalizeBaseUrl(baseUrl);
            this.apiKey = apiKey == null ? "" : apiKey.trim();
            this.model = model == null ? "" : model.trim();
            if (this.name.isEmpty()) this.name = defaultName(this.baseUrl);
        }

        /** What stops this profile from being saved; the key may be empty (local servers). */
        public Problem problem() {
            if (baseUrl.isEmpty()) return Problem.URL;
            if (model.isEmpty()) return Problem.MODEL;
            return Problem.NONE;
        }
    }

    public enum Problem { NONE, URL, MODEL }

    // ── Reading ──────────────────────────────────────────────────────────

    public static List<Profile> list(SharedPreferences p) {
        try {
            List<Profile> out = new Gson().fromJson(p.getString(PREF_PROFILES, "[]"),
                    new TypeToken<List<Profile>>() {}.getType());
            return out == null ? new ArrayList<>() : out;
        } catch (RuntimeException corrupt) {
            return new ArrayList<>();
        }
    }

    /** The active provider: a profile id, {@link AiProvider#ACTIVE_CODEX}, or "" for Google. */
    public static String activeId(SharedPreferences p) {
        String id = p.getString(PREF_ACTIVE, "");
        if (!p.getBoolean(AiProvider.PREF_CUSTOM_ENABLED, false)) return "";
        return AiProvider.ACTIVE_CODEX.equals(id) || find(list(p), id) != null ? id : "";
    }

    public static Profile find(List<Profile> all, String id) {
        if (id != null) for (Profile x : all) if (id.equals(x.id)) return x;
        return null;
    }

    // ── Changing ─────────────────────────────────────────────────────────

    /** Adds or replaces (by id; a blank id gets a new one). An active profile applies at once. */
    public static Profile save(SharedPreferences p, Profile profile) {
        if (profile.id.isEmpty()) profile.id = "p" + Long.toString(System.currentTimeMillis(), 36);
        List<Profile> all = list(p);
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(profile.id)) {
                all.set(i, profile);
                replaced = true;
            }
        }
        if (!replaced) all.add(profile);
        SharedPreferences.Editor e = p.edit().putString(PREF_PROFILES, new Gson().toJson(all));
        if (profile.id.equals(activeId(p))) mirror(e, profile);
        e.apply();
        return profile;
    }

    /** Makes {@code id} the provider; {@link AiProvider#ACTIVE_CODEX} selects ChatGPT, a null or unknown id Google. */
    public static void activate(SharedPreferences p, String id) {
        SharedPreferences.Editor e = p.edit();
        if (AiProvider.ACTIVE_CODEX.equals(id)) {
            e.putBoolean(AiProvider.PREF_CUSTOM_ENABLED, true).putString(PREF_ACTIVE, id).apply();
            return;
        }
        Profile profile = find(list(p), id);
        if (profile == null) {
            e.putBoolean(AiProvider.PREF_CUSTOM_ENABLED, false).putString(PREF_ACTIVE, "");
        } else {
            mirror(e, profile);
            e.putBoolean(AiProvider.PREF_CUSTOM_ENABLED, true).putString(PREF_ACTIVE, profile.id);
        }
        e.apply();
    }

    /** Removes a profile; deleting the active one falls back to Google. */
    public static void delete(SharedPreferences p, String id) {
        boolean wasActive = id != null && id.equals(activeId(p));
        List<Profile> all = list(p);
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).id.equals(id)) all.remove(i);
        p.edit().putString(PREF_PROFILES, new Gson().toJson(all)).apply();
        if (wasActive) activate(p, null);
    }

    /**
     * One-time import of the single provider an earlier version kept in the
     * {@code pref_ai_custom_*} keys, so nobody has to type it again.
     */
    public static void migrate(SharedPreferences p) {
        if (p.contains(PREF_PROFILES) || p.getString(AiProvider.PREF_BASE_URL, "").trim().isEmpty()) return;
        Profile old = new Profile("", "", p.getString(AiProvider.PREF_BASE_URL, ""),
                p.getString(AiProvider.PREF_API_KEY, ""), p.getString(AiProvider.PREF_MODEL, ""));
        boolean wasOn = p.getBoolean(AiProvider.PREF_CUSTOM_ENABLED, false);
        p.edit().putString(PREF_ACTIVE, "").apply();
        save(p, old);
        if (wasOn) activate(p, old.id);
    }

    private static void mirror(SharedPreferences.Editor e, Profile profile) {
        e.putString(AiProvider.PREF_BASE_URL, profile.baseUrl)
                .putString(AiProvider.PREF_API_KEY, profile.apiKey)
                .putString(AiProvider.PREF_MODEL, profile.model)
                .putString(AiProvider.PREF_EFFORT, profile.effort == null ? "" : profile.effort);
    }

    // ── Naming ───────────────────────────────────────────────────────────

    public static Preset presetFor(String baseUrl) {
        String url = AiProvider.normalizeBaseUrl(baseUrl);
        for (Preset pr : PRESETS) if (pr.baseUrl.equals(url)) return pr;
        return null;
    }

    /** "River" for a known URL, else the host without "api."/"www.": "https://llm.example.com/v1" → "llm.example.com". */
    public static String defaultName(String baseUrl) {
        Preset pr = presetFor(baseUrl);
        if (pr != null) return pr.name;
        okhttp3.HttpUrl url = okhttp3.HttpUrl.parse(AiProvider.normalizeBaseUrl(baseUrl));
        if (url == null) return "Custom";
        String host = url.host().toLowerCase(Locale.ROOT);
        return host.startsWith("api.") || host.startsWith("www.") ? host.substring(host.indexOf('.') + 1) : host;
    }

    /** "api.deepseek.com" for a base URL, for the one-line summary under a provider. */
    public static String host(String baseUrl) {
        okhttp3.HttpUrl url = okhttp3.HttpUrl.parse(AiProvider.normalizeBaseUrl(baseUrl));
        return url == null ? "" : url.host();
    }
}
