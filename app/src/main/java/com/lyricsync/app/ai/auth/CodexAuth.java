package com.lyricsync.app.ai.auth;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.ai.util.ApiErrors;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * ChatGPT (OpenAI Codex) sign-in: the same public OAuth client and PKCE browser flow
 * the Codex CLI uses (reference: oh-my-pi {@code registry/oauth/openai-codex.ts}).
 *
 * <p>OpenAI only accepts {@code http://localhost:1455/auth/callback} as redirect, so the
 * loopback listens on exactly that port. Tokens live in the app's private prefs and are
 * never logged; the account id comes from the access token's JWT claims.
 */
public final class CodexAuth {

    static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    static final String AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize";
    static final String TOKEN_URL = "https://auth.openai.com/oauth/token";
    static final int PORT = 1455;
    static final String PATH = "/auth/callback";
    static final String REDIRECT_URI = "http://localhost:" + PORT + PATH;
    static final String SCOPE = "openid profile email offline_access";
    static final String ORIGINATOR = "codex_cli_rs";
    private static final String CLAIMS = "https://api.openai.com/auth";
    private static final String PROFILE = "https://api.openai.com/profile";
    /** Refresh this long before expiry so a request never starts with a dying token. */
    private static final long SKEW_MS = 5 * 60_000;

    public static final String PREF_ACCESS = "pref_codex_access_token";
    public static final String PREF_REFRESH = "pref_codex_refresh_token";
    public static final String PREF_EXPIRES = "pref_codex_expires_at";
    public static final String PREF_ACCOUNT = "pref_codex_account_id";
    public static final String PREF_EMAIL = "pref_codex_email";
    public static final String PREF_PLAN = "pref_codex_plan";

    public interface LoginCallback {
        void onSuccess(String email);

        void onError(String message);
    }

    public interface TokenCallback {
        void onToken(String accessToken, String accountId);

        void onError(String message);
    }

    private static volatile CodexAuth instance;

    private final SharedPreferences prefs;
    private final OkHttpClient http;
    private final List<TokenCallback> waiting = new ArrayList<>();
    private boolean refreshing;
    private OAuthLoopbackServer server;
    /** The browser login in progress, for a callback URL pasted by hand. */
    private String pendingVerifier, pendingState;

    public static CodexAuth get(android.content.Context context) {
        if (instance == null) {
            synchronized (CodexAuth.class) {
                if (instance == null) {
                    instance = new CodexAuth(context.getApplicationContext()
                            .getSharedPreferences(AntigravityAuthManager.PREF_NAME, android.content.Context.MODE_PRIVATE), null);
                }
            }
        }
        return instance;
    }

    /** For tests: explicit prefs and HTTP client. */
    public CodexAuth(SharedPreferences prefs, OkHttpClient http) {
        this.prefs = prefs;
        this.http = http != null ? http : new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build();
    }

    // ── State ────────────────────────────────────────────────────────────

    public boolean isSignedIn() {
        return !prefs.getString(PREF_REFRESH, "").isEmpty() && !prefs.getString(PREF_ACCOUNT, "").isEmpty();
    }

    public String email() {
        return prefs.getString(PREF_EMAIL, "");
    }

    /** "free", "plus", "pro"… as the token reports it, or "". */
    public String plan() {
        return prefs.getString(PREF_PLAN, "");
    }

    public void logout() {
        stopServer();
        prefs.edit().remove(PREF_ACCESS).remove(PREF_REFRESH).remove(PREF_EXPIRES)
                .remove(PREF_ACCOUNT).remove(PREF_EMAIL).remove(PREF_PLAN).apply();
    }

    // ── Browser login ────────────────────────────────────────────────────

    /** Opens the ChatGPT sign-in page and waits on localhost:1455 for the redirect. */
    public synchronized void startLogin(Activity activity, LoginCallback cb) {
        String verifier = randomUrlSafe(32);
        String state = randomUrlSafe(16);
        String url = authorizeUrl(state, challenge(verifier));
        pendingVerifier = verifier;
        pendingState = state;
        stopServer();
        server = new OAuthLoopbackServer(PORT, PATH, "ChatGPT (Codex)", "akun ChatGPT");
        server.start(new OAuthLoopbackServer.Callback() {
            @Override
            public void onCodeReceived(String code, String returned) {
                stopServer();
                if (!state.equals(returned)) {
                    cb.onError("Sign-in was interrupted (state mismatch). Try again.");
                    return;
                }
                exchange(new FormBody.Builder().add("grant_type", "authorization_code").add("client_id", CLIENT_ID)
                        .add("code", code).add("code_verifier", verifier).add("redirect_uri", REDIRECT_URI).build(), cb);
            }

            @Override
            public void onError(Exception e) {
                stopServer();
                String m = e.getMessage() != null ? e.getMessage() : e.toString();
                cb.onError(m.contains("in use") || m.contains("EADDRINUSE") || m.contains("bind")
                        ? "Port 1455 is busy (another Codex login running?). Close it and try again." : m);
            }
        });
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Exception e) {
            stopServer();
            cb.onError("Cannot open the browser: " + e.getMessage());
        }
    }

    static String authorizeUrl(String state, String challenge) {
        return HttpUrl.get(AUTHORIZE_URL).newBuilder()
                .addQueryParameter("response_type", "code")
                .addQueryParameter("client_id", CLIENT_ID)
                .addQueryParameter("redirect_uri", REDIRECT_URI)
                .addQueryParameter("scope", SCOPE)
                .addQueryParameter("code_challenge", challenge)
                .addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("state", state)
                .addQueryParameter("id_token_add_organizations", "true")
                .addQueryParameter("codex_cli_simplified_flow", "true")
                .addQueryParameter("originator", ORIGINATOR)
                .build().toString();
    }

    /**
     * Manual sign-in when the browser could not reach the app: the callback URL the
     * browser landed on ({@code http://localhost:1455/auth/callback?code=…}) after
     * {@link #startLogin}, a refresh token, or a Codex {@code auth.json}.
     */
    public void loginManually(String input, LoginCallback cb) {
        String code = callbackCode(input);
        if (code != null) {
            String verifier, state;
            synchronized (this) {
                verifier = pendingVerifier;
                state = pendingState;
            }
            String returned = callbackParam(input, "state");
            if (verifier == null) {
                cb.onError("Start \"Sign in with ChatGPT\" first, then paste the address it ends on.");
            } else if (returned != null && !returned.equals(state)) {
                cb.onError("That address belongs to an older sign-in. Sign in again.");
            } else {
                stopServer();
                exchange(new FormBody.Builder().add("grant_type", "authorization_code").add("client_id", CLIENT_ID)
                        .add("code", code).add("code_verifier", verifier).add("redirect_uri", REDIRECT_URI).build(), cb);
            }
            return;
        }
        String refresh = refreshTokenFrom(input);
        if (refresh.isEmpty()) {
            cb.onError("Paste the callback address, a refresh token or ~/.codex/auth.json.");
            return;
        }
        exchange(refreshBody(refresh), cb);
    }

    /** The {@code code} of a pasted callback URL, or null when the text is not one. */
    static String callbackCode(String input) {
        String s = input == null ? "" : input.trim();
        return s.contains("code=") && (s.contains("://") || s.startsWith("/") || s.startsWith("?")) ? callbackParam(s, "code") : null;
    }

    private static String callbackParam(String input, String name) {
        String s = input.trim();
        int q = s.indexOf('?');
        HttpUrl u = HttpUrl.parse("http://localhost/" + (q >= 0 ? s.substring(q) : ""));
        String v = u != null ? u.queryParameter(name) : null;
        return v == null || v.isEmpty() ? null : v;
    }

    /** Accepts a bare refresh token or a Codex auth.json; "" when neither. */
    static String refreshTokenFrom(String input) {
        String s = input == null ? "" : input.trim();
        if (s.startsWith("{")) {
            try {
                JsonObject o = JsonParser.parseString(s).getAsJsonObject();
                JsonObject t = o.has("tokens") && o.get("tokens").isJsonObject() ? o.getAsJsonObject("tokens") : o;
                return str(t, "refresh_token");
            } catch (RuntimeException e) {
                return "";
            }
        }
        return s.contains(" ") || s.length() < 20 ? "" : s;
    }

    // ── Tokens ───────────────────────────────────────────────────────────

    /** A valid access token and its account id, refreshing first when it is (nearly) expired. */
    public void token(TokenCallback cb) {
        String access = prefs.getString(PREF_ACCESS, "");
        String account = prefs.getString(PREF_ACCOUNT, "");
        if (!access.isEmpty() && !account.isEmpty() && System.currentTimeMillis() < prefs.getLong(PREF_EXPIRES, 0) - SKEW_MS) {
            cb.onToken(access, account);
            return;
        }
        String refresh = prefs.getString(PREF_REFRESH, "");
        if (refresh.isEmpty()) {
            cb.onError("Sign in with ChatGPT first.");
            return;
        }
        synchronized (waiting) {
            waiting.add(cb);
            if (refreshing) return;
            refreshing = true;
        }
        exchange(refreshBody(refresh), new LoginCallback() {
            @Override
            public void onSuccess(String email) {
                for (TokenCallback w : drain()) w.onToken(prefs.getString(PREF_ACCESS, ""), prefs.getString(PREF_ACCOUNT, ""));
            }

            @Override
            public void onError(String message) {
                for (TokenCallback w : drain()) w.onError(message);
            }
        });
    }

    private List<TokenCallback> drain() {
        synchronized (waiting) {
            refreshing = false;
            List<TokenCallback> out = new ArrayList<>(waiting);
            waiting.clear();
            return out;
        }
    }

    private static FormBody refreshBody(String refresh) {
        return new FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh)
                .add("client_id", CLIENT_ID).build();
    }

    /** POST to the token endpoint and store the result; refresh responses may omit a new refresh token. */
    private void exchange(FormBody body, LoginCallback cb) {
        Request req = new Request.Builder().url(TOKEN_URL).post(body).build();
        http.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                cb.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (ResponseBody rb = response.body()) {
                    String text = rb != null ? rb.string() : "";
                    if (!response.isSuccessful()) {
                        cb.onError("Sign-in failed (" + response.code() + "): " + ApiErrors.message(text, response.message()));
                        return;
                    }
                    String error = store(JsonParser.parseString(text).getAsJsonObject());
                    if (error != null) cb.onError(error);
                    else cb.onSuccess(email());
                } catch (Exception e) {
                    cb.onError("Unexpected sign-in response: " + e.getMessage());
                }
            }
        });
    }

    /** Saves a token response; returns an error message, or null on success. */
    String store(JsonObject t) {
        String access = str(t, "access_token");
        if (access.isEmpty()) return "The response had no access token.";
        String refresh = str(t, "refresh_token");
        if (refresh.isEmpty()) refresh = prefs.getString(PREF_REFRESH, "");
        long expiresIn = t.has("expires_in") ? t.get("expires_in").getAsLong() : 3600;
        JsonObject claims = jwt(access), auth = obj(claims, CLAIMS), idAuth = obj(jwt(str(t, "id_token")), CLAIMS);
        String account = str(auth, "chatgpt_account_id");
        if (account.isEmpty()) account = prefs.getString(PREF_ACCOUNT, "");
        if (account.isEmpty()) return "This account has no ChatGPT workspace.";
        String email = str(obj(claims, PROFILE), "email").toLowerCase(Locale.ROOT);
        String plan = str(auth, "chatgpt_plan_type");
        if (plan.isEmpty()) plan = str(idAuth, "chatgpt_plan_type");
        SharedPreferences.Editor e = prefs.edit().putString(PREF_ACCESS, access).putString(PREF_REFRESH, refresh)
                .putLong(PREF_EXPIRES, System.currentTimeMillis() + expiresIn * 1000).putString(PREF_ACCOUNT, account);
        if (!email.isEmpty()) e.putString(PREF_EMAIL, email);
        if (!plan.isEmpty()) e.putString(PREF_PLAN, plan.toLowerCase(Locale.ROOT));
        e.apply();
        return null;
    }

    // ── Models ───────────────────────────────────────────────────────────

    /** Pinned Codex client version; the backend hides newer models from older versions. */
    static final String CLIENT_VERSION = "0.159.0";

    public static final class Model {
        public final String id, name;
        public final List<String> efforts;

        Model(String id, String name, List<String> efforts) {
            this.id = id;
            this.name = name;
            this.efforts = efforts;
        }
    }

    public interface ModelsCallback {
        void onModels(List<Model> models);

        void onError(String message);
    }

    /** The models this ChatGPT account may use: {@code GET …/codex/models}, listed and API-supported only. */
    public void fetchModels(ModelsCallback cb) {
        token(new TokenCallback() {
            @Override
            public void onToken(String access, String account) {
                Request req = new Request.Builder()
                        .url("https://chatgpt.com/backend-api/codex/models?client_version=" + CLIENT_VERSION)
                        .addHeader("Authorization", "Bearer " + access)
                        .addHeader("chatgpt-account-id", account)
                        .addHeader("originator", ORIGINATOR)
                        .build();
                http.newCall(req).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        cb.onError("Network error: " + e.getMessage());
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        try (ResponseBody rb = response.body()) {
                            String text = rb != null ? rb.string() : "";
                            if (!response.isSuccessful()) {
                                cb.onError("HTTP " + response.code() + ": " + ApiErrors.message(text, response.message()));
                                return;
                            }
                            List<Model> models = parseModels(text);
                            if (models.isEmpty()) cb.onError("No models for this account.");
                            else cb.onModels(models);
                        } catch (Exception e) {
                            cb.onError(e.getMessage());
                        }
                    }
                });
            }

            @Override
            public void onError(String message) {
                cb.onError(message);
            }
        });
    }

    static List<Model> parseModels(String json) {
        List<Model> out = new ArrayList<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("models") || !root.get("models").isJsonArray()) return out;
        for (com.google.gson.JsonElement el : root.getAsJsonArray("models")) {
            if (!el.isJsonObject()) continue;
            JsonObject m = el.getAsJsonObject();
            String id = str(m, "slug");
            boolean listed = !"hide".equals(str(m, "visibility"));
            boolean api = !m.has("supported_in_api") || m.get("supported_in_api").getAsBoolean();
            if (id.isEmpty() || !listed || !api) continue;
            List<String> efforts = new ArrayList<>();
            if (m.has("supported_reasoning_levels") && m.get("supported_reasoning_levels").isJsonArray()) {
                for (com.google.gson.JsonElement r : m.getAsJsonArray("supported_reasoning_levels")) {
                    if (r.isJsonObject()) {
                        String e = str(r.getAsJsonObject(), "effort");
                        if (!e.isEmpty()) efforts.add(e);
                    }
                }
            }
            String name = str(m, "display_name");
            out.add(new Model(id, name.isEmpty() ? id : name, efforts));
        }
        return out;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private synchronized void stopServer() {
        if (server != null) server.stop();
        server = null;
    }

    static String challenge(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return b64url(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomUrlSafe(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return b64url(b);
    }

    private static String b64url(byte[] b) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** The JWT payload, or an empty object. */
    static JsonObject jwt(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return new JsonObject();
            byte[] json = java.util.Base64.getUrlDecoder().decode(parts[1]);
            return JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static JsonObject obj(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString().trim() : "";
    }
}
