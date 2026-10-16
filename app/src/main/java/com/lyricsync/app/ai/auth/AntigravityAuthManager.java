package com.lyricsync.app.ai.auth;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.ai.util.ApiErrors;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Manages Google OAuth 2.0 authentication, silent access token refresh,
 * credential persistence in SharedPreferences, and browser login flow for Antigravity AI.
 */
public class AntigravityAuthManager {

    public static final String CLIENT_ID = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com";
    public static final String CLIENT_SECRET = "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";
    public static final String REDIRECT_URI = "http://127.0.0.1:51121/oauth-callback";
    public static final String SCOPES = "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile openid";
    public static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    public static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    public static final String USERINFO_URL = "https://www.googleapis.com/oauth2/v1/userinfo?alt=json";

    public static final String PREF_NAME = "lyricsync";
    public static final String PREF_REFRESH_TOKEN = "pref_ai_refresh_token";
    public static final String PREF_ACCESS_TOKEN = "pref_ai_access_token";
    public static final String PREF_TOKEN_EXPIRY = "pref_ai_token_expiry";
    public static final String PREF_EMAIL = "pref_ai_email";

    public interface AuthCallback {
        void onSuccess(String email);
        void onError(String message);
    }

    public interface TokenCallback {
        void onTokenReady(String accessToken);
        void onError(String message);
    }

    private static AntigravityAuthManager instance;

    private final SharedPreferences prefs;
    private final OkHttpClient httpClient;
    protected OAuthLoopbackServer loopbackServer;

    private volatile String cachedAccessToken;
    private volatile String cachedRefreshToken;
    private volatile long cachedTokenExpiry;
    private volatile String cachedEmail;

    private final Object refreshLock = new Object();
    private boolean isRefreshing = false;
    private final List<TokenCallback> pendingTokenCallbacks = new ArrayList<>();

    public static synchronized AntigravityAuthManager getInstance(Context context) {
        if (instance == null) {
            Context appContext = context != null ? context.getApplicationContext() : null;
            SharedPreferences sp = appContext != null ? appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) : null;
            instance = new AntigravityAuthManager(sp, new OkHttpClient());
        }
        return instance;
    }

    public static synchronized void setInstance(AntigravityAuthManager customInstance) {
        instance = customInstance;
    }

    public AntigravityAuthManager(Context context) {
        this(context != null ? context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) : null, new OkHttpClient());
    }

    public AntigravityAuthManager(SharedPreferences prefs) {
        this(prefs, new OkHttpClient());
    }

    public AntigravityAuthManager(SharedPreferences prefs, OkHttpClient httpClient) {
        this.prefs = prefs;
        this.httpClient = httpClient != null ? httpClient : new OkHttpClient();
        if (this.prefs != null) {
            this.cachedAccessToken = this.prefs.getString(PREF_ACCESS_TOKEN, null);
            this.cachedRefreshToken = this.prefs.getString(PREF_REFRESH_TOKEN, null);
            this.cachedTokenExpiry = this.prefs.getLong(PREF_TOKEN_EXPIRY, 0L);
            this.cachedEmail = this.prefs.getString(PREF_EMAIL, null);
        }
    }

    /**
     * Builds Google OAuth authorization URL with required Antigravity parameters.
     */
    public static String buildAuthorizeUrl(String state) {
        try {
            return AUTH_URL + "?" +
                    "client_id=" + URLEncoder.encode(CLIENT_ID, "UTF-8") +
                    "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8") +
                    "&response_type=code" +
                    "&scope=" + URLEncoder.encode(SCOPES, "UTF-8") +
                    "&access_type=offline" +
                    "&prompt=consent" +
                    "&state=" + (state != null ? URLEncoder.encode(state, "UTF-8") : "");
        } catch (UnsupportedEncodingException e) {
            return AUTH_URL + "?" +
                    "client_id=" + CLIENT_ID +
                    "&redirect_uri=" + REDIRECT_URI +
                    "&response_type=code" +
                    "&access_type=offline" +
                    "&prompt=consent" +
                    "&state=" + (state != null ? state : "");
        }
    }

    /**
     * Returns true if a refresh token is saved and available.
     */
    public boolean isAuthenticated() {
        String rt = getRefreshToken();
        return rt != null && !rt.trim().isEmpty();
    }

    public String getRefreshToken() {
        if (cachedRefreshToken != null && !cachedRefreshToken.trim().isEmpty()) {
            return cachedRefreshToken;
        }
        if (prefs != null) {
            cachedRefreshToken = prefs.getString(PREF_REFRESH_TOKEN, null);
        }
        return cachedRefreshToken;
    }

    public String getAccessToken() {
        if (cachedAccessToken != null && !cachedAccessToken.trim().isEmpty()) {
            return cachedAccessToken;
        }
        if (prefs != null) {
            cachedAccessToken = prefs.getString(PREF_ACCESS_TOKEN, null);
        }
        return cachedAccessToken;
    }

    public long getTokenExpiry() {
        if (cachedTokenExpiry > 0) {
            return cachedTokenExpiry;
        }
        if (prefs != null) {
            cachedTokenExpiry = prefs.getLong(PREF_TOKEN_EXPIRY, 0L);
        }
        return cachedTokenExpiry;
    }

    public String getEmail() {
        if (cachedEmail != null && !cachedEmail.trim().isEmpty()) {
            return cachedEmail;
        }
        if (prefs != null) {
            cachedEmail = prefs.getString(PREF_EMAIL, null);
        }
        return cachedEmail;
    }

    public synchronized void saveTokens(String accessToken, String refreshToken, long expiryMs) {
        this.cachedAccessToken = accessToken;
        if (refreshToken != null && !refreshToken.trim().isEmpty()) {
            this.cachedRefreshToken = refreshToken;
        }
        this.cachedTokenExpiry = expiryMs;

        if (prefs != null) {
            SharedPreferences.Editor editor = prefs.edit();
            if (accessToken != null) {
                editor.putString(PREF_ACCESS_TOKEN, accessToken);
            }
            if (refreshToken != null && !refreshToken.trim().isEmpty()) {
                editor.putString(PREF_REFRESH_TOKEN, refreshToken);
            }
            if (expiryMs > 0) {
                editor.putLong(PREF_TOKEN_EXPIRY, expiryMs);
            }
            editor.apply();
        }
    }

    public synchronized void saveEmail(String email) {
        this.cachedEmail = email;
        if (prefs != null && email != null) {
            prefs.edit().putString(PREF_EMAIL, email).apply();
        }
    }

    /**
     * Clears all stored auth credentials and tokens.
     */
    public void logout() {
        List<TokenCallback> callbacksToCancel;
        synchronized (refreshLock) {
            this.cachedAccessToken = null;
            this.cachedRefreshToken = null;
            this.cachedTokenExpiry = 0L;
            this.cachedEmail = null;

            if (prefs != null) {
                prefs.edit()
                        .remove(PREF_ACCESS_TOKEN)
                        .remove(PREF_REFRESH_TOKEN)
                        .remove(PREF_TOKEN_EXPIRY)
                        .remove(PREF_EMAIL)
                        .apply();
            }

            // Guard the server field with the same monitor as startBrowserLogin(): using
            // refreshLock alone let a login and a logout race, stranding a bound loopback port.
            synchronized (AntigravityAuthManager.this) {
                if (loopbackServer != null) {
                    try {
                        loopbackServer.stop();
                    } catch (Exception ignored) {}
                    loopbackServer = null;
                }
            }

            callbacksToCancel = new ArrayList<>(pendingTokenCallbacks);
            pendingTokenCallbacks.clear();
            isRefreshing = false;
        }

        for (TokenCallback cb : callbacksToCancel) {
            try {
                cb.onError("Logged out while token operation was in progress");
            } catch (Exception ignored) {}
        }
    }

    /**
     * Retrieves a valid access token. If currently cached token is fresh (> 5 mins remaining),
     * returns it immediately. Otherwise silently refreshes the token using the saved refresh token.
     */
    public void getValidAccessToken(final TokenCallback callback) {
        if (callback == null) return;

        String currentToken = getAccessToken();
        long expiry = getTokenExpiry();
        long now = System.currentTimeMillis();

        if (currentToken != null && !currentToken.trim().isEmpty() && (expiry - now > 300000L)) {
            callback.onTokenReady(currentToken);
            return;
        }

        final String refreshToken = getRefreshToken();
        if (refreshToken == null || refreshToken.trim().isEmpty()) {
            callback.onError("Not authenticated: no refresh token available");
            return;
        }

        synchronized (refreshLock) {
            currentToken = getAccessToken();
            expiry = getTokenExpiry();
            now = System.currentTimeMillis();
            if (currentToken != null && !currentToken.trim().isEmpty() && (expiry - now > 300000L)) {
                callback.onTokenReady(currentToken);
                return;
            }

            pendingTokenCallbacks.add(callback);
            if (isRefreshing) {
                return;
            }
            isRefreshing = true;
        }

        executeRefreshTokenRequest(refreshToken);
    }

    private void executeRefreshTokenRequest(final String refreshToken) {
        RequestBody formBody = new FormBody.Builder()
                .add("refresh_token", refreshToken)
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .add("grant_type", "refresh_token")
                .build();

        Request request = new Request.Builder()
                .url(TOKEN_URL)
                .post(formBody)
                .build();

        try {
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    notifyTokenError("Failed to refresh access token: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (ResponseBody responseBody = response.body()) {
                        if (responseBody == null) {
                            notifyTokenError("Empty response while refreshing access token");
                            return;
                        }
                        String body = responseBody.string();
                        if (!response.isSuccessful()) {
                            String error = ApiErrors.message(body, "HTTP " + response.code());
                            notifyTokenError("Token refresh failed: " + error);
                            return;
                        }

                        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                        String newAccessToken = json.has("access_token") ? json.get("access_token").getAsString() : null;
                        long expiresIn = json.has("expires_in") ? json.get("expires_in").getAsLong() : 3600L;
                        long expiryMs = System.currentTimeMillis() + (expiresIn * 1000L);
                        String newRefreshToken = json.has("refresh_token") ? json.get("refresh_token").getAsString() : refreshToken;

                        if (newAccessToken == null) {
                            notifyTokenError("No access token present in refresh response");
                            return;
                        }

                        // Guard against resuscitation if logout() occurred while refresh was in-flight
                        synchronized (refreshLock) {
                            String currentRt = getRefreshToken();
                            if (currentRt == null || currentRt.trim().isEmpty() || !isRefreshing) {
                                notifyTokenError("Authentication was revoked during token refresh");
                                return;
                            }
                            saveTokens(newAccessToken, newRefreshToken, expiryMs);
                            notifyTokenSuccess(newAccessToken);
                        }
                    } catch (Exception e) {
                        notifyTokenError("Error parsing token refresh response: " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            notifyTokenError("Failed to initiate token refresh: " + e.getMessage());
        }
    }

    private void notifyTokenSuccess(String newAccessToken) {
        List<TokenCallback> callbacks;
        synchronized (refreshLock) {
            isRefreshing = false;
            callbacks = new ArrayList<>(pendingTokenCallbacks);
            pendingTokenCallbacks.clear();
        }
        for (TokenCallback cb : callbacks) {
            try {
                cb.onTokenReady(newAccessToken);
            } catch (Exception ignored) {}
        }
    }

    private void notifyTokenError(String errorMessage) {
        List<TokenCallback> callbacks;
        synchronized (refreshLock) {
            isRefreshing = false;
            callbacks = new ArrayList<>(pendingTokenCallbacks);
            pendingTokenCallbacks.clear();
        }
        for (TokenCallback cb : callbacks) {
            try {
                cb.onError(errorMessage);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Exchanges an authorization code for access and refresh tokens.
     */
    public void exchangeCodeForTokens(String code, final AuthCallback callback) {
        if (code == null || code.trim().isEmpty()) {
            if (callback != null) callback.onError("Authorization code cannot be empty");
            return;
        }
        loginWithForm(new FormBody.Builder()
                .add("code", code.trim())
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .add("redirect_uri", REDIRECT_URI)
                .add("grant_type", "authorization_code")
                .build(), "Token exchange", null, callback);
    }

    /**
     * POSTs an OAuth grant to the token endpoint, then stores the tokens it returns and
     * resolves the account email. The two grant flows differ only in the form they send and
     * in which refresh token to keep when the response does not carry a new one.
     */
    private void loginWithForm(final FormBody form, final String failurePrefix,
                               final String fallbackRefreshToken, final AuthCallback callback) {
        Request request = new Request.Builder()
                .url(TOKEN_URL)
                .post(form)
                .build();

        try {
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    if (callback != null) {
                        callback.onError(failurePrefix + " gagal (jaringan): " + e.getMessage());
                    }
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (ResponseBody responseBody = response.body()) {
                        if (responseBody == null) {
                            if (callback != null) callback.onError(failurePrefix + " gagal: respons kosong");
                            return;
                        }
                        String body = responseBody.string();
                        if (!response.isSuccessful()) {
                            if (callback != null) {
                                callback.onError(failurePrefix + " gagal: "
                                        + ApiErrors.message(body, "HTTP " + response.code()));
                            }
                            return;
                        }

                        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                        String accessToken = json.has("access_token") ? json.get("access_token").getAsString() : null;
                        if (accessToken == null) {
                            if (callback != null) callback.onError(failurePrefix + " gagal: respons tanpa access token");
                            return;
                        }
                        long expiresIn = json.has("expires_in") ? json.get("expires_in").getAsLong() : 3600L;
                        String refreshToken = json.has("refresh_token")
                                ? json.get("refresh_token").getAsString() : fallbackRefreshToken;
                        saveTokens(accessToken, refreshToken, System.currentTimeMillis() + (expiresIn * 1000L));
                        fetchUserEmail(accessToken, callback);
                    } catch (Exception e) {
                        if (callback != null) {
                            callback.onError(failurePrefix + " gagal memproses respons: " + e.getMessage());
                        }
                    }
                }
            });
        } catch (Exception e) {
            if (callback != null) callback.onError(failurePrefix + " gagal dimulai: " + e.getMessage());
        }
    }


    /**
     * Fetches authenticated user's email address from Google OAuth userinfo endpoint.
     */
    public void fetchUserEmail(final String accessToken, final AuthCallback callback) {
        if (accessToken == null) {
            if (callback != null) callback.onError("Access token cannot be null");
            return;
        }

        Request request = new Request.Builder()
                .url(USERINFO_URL)
                .addHeader("Authorization", "Bearer " + accessToken)
                .get()
                .build();

        try {
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    // If fetching email fails, authentication is still preserved
                    String existingEmail = getEmail();
                    if (callback != null) {
                        callback.onSuccess(existingEmail != null ? existingEmail : "authenticated");
                    }
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String email = null;
                    try (ResponseBody responseBody = response.body()) {
                        if (response.isSuccessful() && responseBody != null) {
                            JsonObject json = JsonParser.parseString(responseBody.string()).getAsJsonObject();
                            if (json.has("email")) {
                                email = json.get("email").getAsString();
                                saveEmail(email);
                            }
                        }
                    } catch (Exception ignored) {}

                    if (email == null) {
                        email = getEmail();
                    }
                    if (callback != null) {
                        callback.onSuccess(email != null ? email : "authenticated");
                    }
                }
            });
        } catch (Exception e) {
            String existingEmail = getEmail();
            if (callback != null) {
                callback.onSuccess(existingEmail != null ? existingEmail : "authenticated");
            }
        }
    }

    /**
     * Initiates native browser login flow with loopback listener on port 51121.
     */
    public synchronized void startBrowserLogin(Activity activity, final AuthCallback callback) {
        final String state = UUID.randomUUID().toString();
        final String authUrl = buildAuthorizeUrl(state);

        if (loopbackServer != null) {
            try {
                loopbackServer.stop();
            } catch (Exception ignored) {}
            loopbackServer = null;
        }

        loopbackServer = createLoopbackServer();
        loopbackServer.start(new OAuthLoopbackServer.Callback() {
            @Override
            public void onCodeReceived(String authCode, String returnedState) {
                if (returnedState == null || !state.equals(returnedState)) {
                    if (callback != null) {
                        callback.onError("CSRF state mismatch: expected " + state + " but received " + returnedState);
                    }
                    return;
                }
                exchangeCodeForTokens(authCode, callback);
            }

            @Override
            public void onError(Exception e) {
                if (callback != null) {
                    callback.onError("OAuth loopback error: " + e.getMessage());
                }
            }
        });

        try {
            launchBrowserUrl(activity, authUrl);
        } catch (Exception e) {
            if (loopbackServer != null) {
                try {
                    loopbackServer.stop();
                } catch (Exception ignored) {}
                loopbackServer = null;
            }
            if (callback != null) {
                callback.onError("Failed to launch browser: " + e.getMessage());
            }
        }
    }

    protected OAuthLoopbackServer createLoopbackServer() {
        return new OAuthLoopbackServer();
    }

    protected void launchBrowserUrl(Activity activity, String url) {
        if (activity == null) {
            throw new IllegalArgumentException("Activity cannot be null");
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }

    /**
     * Handles manual input of an authorization code or refresh token.
     */
    public void manualTokenLogin(String refreshTokenOrCode, final AuthCallback callback) {
        if (refreshTokenOrCode == null || refreshTokenOrCode.trim().isEmpty()) {
            if (callback != null) {
                callback.onError("Token atau kode otorisasi tidak boleh kosong");
            }
            return;
        }

        final String input = refreshTokenOrCode.trim();

        if (input.startsWith("1//")) {
            // Direct refresh token
            loginWithRefreshToken(input, callback);
        } else {
            // Try authorization code first
            exchangeCodeForTokens(input, new AuthCallback() {
                @Override
                public void onSuccess(String email) {
                    if (callback != null) {
                        callback.onSuccess(email);
                    }
                }

                @Override
                public void onError(String message) {
                    // If code exchange failed, attempt as refresh token fallback
                    loginWithRefreshToken(input, callback);
                }
            });
        }
    }

    private void loginWithRefreshToken(final String refreshToken, final AuthCallback callback) {
        loginWithForm(new FormBody.Builder()
                .add("refresh_token", refreshToken)
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .add("grant_type", "refresh_token")
                .build(), "Login", refreshToken, callback);
    }
}
