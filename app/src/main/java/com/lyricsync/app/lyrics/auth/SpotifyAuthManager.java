package com.lyricsync.app.lyrics.auth;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.util.AppLog;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Manages Spotify user authentication via sp_dc cookie or raw access token
 * to unlock 100% reliable syllable-level synchronized lyrics from SpicyLyrics.
 */
public class SpotifyAuthManager {
    private static final String TAG = "SpotifyAuthManager";
    public static final String PREF_NAME = "lyricsync";
    public static final String PREF_SP_DC = "pref_spotify_sp_dc";
    public static final String PREF_ACCESS_TOKEN = "pref_spotify_access_token";
    public static final String PREF_TOKEN_TIME = "pref_spotify_token_time";
    public static final String PREF_USER_NAME = "pref_spotify_user_name";

    private static final String TOKEN_API_BASE = "https://open.spotify.com/api/token";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36";
    private static final long TOKEN_VALIDITY_MS = 45 * 60 * 1000L; // 45 minutes

    private static final byte[] FALLBACK_SECRET = new byte[]{
            44, 55, 47, 42, 70, 40, 34, 114, 76, 74, 50, 111, 120, 97, 75, 76, 94, 102, 43, 69, 49, 120, 118, 80, 64, 78
    };
    private static final int DEFAULT_SECRET_VERSION = 61;
    private static volatile byte[] cachedSecret = FALLBACK_SECRET;
    private static volatile int cachedSecretVersion = DEFAULT_SECRET_VERSION;
    private static volatile long secretCacheExpiry = 0;

    private static volatile SpotifyAuthManager instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final OkHttpClient httpClient;

    public interface AuthCallback {
        void onSuccess(String accessToken, boolean isAnonymous);
        void onError(String message);
    }

    public static SpotifyAuthManager getInstance(Context context) {
        if (instance == null) {
            synchronized (SpotifyAuthManager.class) {
                if (instance == null) {
                    instance = new SpotifyAuthManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private SpotifyAuthManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    public boolean isAuthenticated() {
        String spDc = prefs.getString(PREF_SP_DC, null);
        if (spDc != null && !spDc.trim().isEmpty()) return true;
        String token = prefs.getString(PREF_ACCESS_TOKEN, null);
        return token != null && !token.trim().isEmpty();
    }

    public String getStoredSpDc() {
        return prefs.getString(PREF_SP_DC, null);
    }

    public String getStoredToken() {
        return prefs.getString(PREF_ACCESS_TOKEN, null);
    }

    public String getValidAccessTokenSync() {
        String token = prefs.getString(PREF_ACCESS_TOKEN, null);
        long time = prefs.getLong(PREF_TOKEN_TIME, 0);
        String spDc = prefs.getString(PREF_SP_DC, null);

        if (spDc != null && !spDc.trim().isEmpty()) {
            if (token != null && (System.currentTimeMillis() - time) < TOKEN_VALIDITY_MS) {
                return token;
            }
            // Refresh token synchronously
            String refreshed = exchangeSpDcSync(spDc.trim());
            if (refreshed != null && !refreshed.isEmpty()) {
                return refreshed;
            }
        }

        if (token != null && (System.currentTimeMillis() - time) < TOKEN_VALIDITY_MS) {
            return token;
        }
        return null;
    }

    public static String generateTotp(long timeSeconds) {
        try {
            byte[] secretBytes = cachedSecret;
            StringBuilder sb = new StringBuilder();
            for (int t = 0; t < secretBytes.length; t++) {
                int val = (secretBytes[t] & 0xFF) ^ ((t % 33) + 9);
                sb.append(val);
            }
            byte[] keyBytes = sb.toString().getBytes(StandardCharsets.UTF_8);

            long counter = timeSeconds / 30;
            byte[] counterBytes = ByteBuffer.allocate(8).putLong(counter).array();

            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA1"));
            byte[] hash = mac.doFinal(counterBytes);

            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);

            int otp = binary % 1000000;
            return String.format(Locale.US, "%06d", otp);
        } catch (Exception e) {
            AppLog.w(TAG, "Failed to generate TOTP: " + e.getMessage());
            return "000000";
        }
    }

    private String buildTokenUrl() {
        long nowSec = System.currentTimeMillis() / 1000L;
        String totp = generateTotp(nowSec);
        return TOKEN_API_BASE + "?reason=init&productType=web-player&totp="
                + totp + "&totpVer=" + cachedSecretVersion + "&totpServer=" + totp;
    }

    public String exchangeSpDcSync(String spDc) {
        if (spDc == null || spDc.trim().isEmpty()) return null;
        try {
            String url = buildTokenUrl();
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", "sp_dc=" + spDc.trim())
                    .header("Accept", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    AppLog.w(TAG, "sp_dc exchange failed HTTP " + response.code());
                    return null;
                }
                String body = response.body().string();
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                if (root.has("accessToken") && !root.get("accessToken").isJsonNull()) {
                    String accessToken = root.get("accessToken").getAsString();
                    boolean isAnon = root.has("isAnonymous") && root.get("isAnonymous").getAsBoolean();
                    prefs.edit()
                            .putString(PREF_ACCESS_TOKEN, accessToken)
                            .putLong(PREF_TOKEN_TIME, System.currentTimeMillis())
                            .apply();
                    AppLog.i(TAG, "Exchanged sp_dc for accessToken len=" + accessToken.length() + " isAnon=" + isAnon);
                    return accessToken;
                }
            }
        } catch (Exception e) {
            AppLog.w(TAG, "Failed to exchange sp_dc: " + e.getMessage());
        }
        return null;
    }

    public void loginWithSpDc(final String spDc, final AuthCallback callback) {
        if (spDc == null || spDc.trim().isEmpty()) {
            if (callback != null) callback.onError("Cookie sp_dc tidak boleh kosong");
            return;
        }

        final String cleanSpDc = spDc.trim();
        String url = buildTokenUrl();
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Cookie", "sp_dc=" + cleanSpDc)
                .header("Accept", "application/json")
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                AppLog.w(TAG, "Network error during sp_dc login: " + e.getMessage());
                if (callback != null) {
                    callback.onError("Koneksi gagal: " + e.getMessage());
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful() || response.body() == null) {
                    if (callback != null) {
                        callback.onError("Validasi gagal (HTTP " + response.code() + "). Pastikan cookie sp_dc masih aktif.");
                    }
                    return;
                }

                try {
                    String body = response.body().string();
                    JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                    if (root.has("accessToken") && !root.get("accessToken").isJsonNull()) {
                        String accessToken = root.get("accessToken").getAsString();
                        boolean isAnon = root.has("isAnonymous") && root.get("isAnonymous").getAsBoolean();

                        prefs.edit()
                                .putString(PREF_SP_DC, cleanSpDc)
                                .putString(PREF_ACCESS_TOKEN, accessToken)
                                .putLong(PREF_TOKEN_TIME, System.currentTimeMillis())
                                .apply();

                        if (callback != null) {
                            callback.onSuccess(accessToken, isAnon);
                        }
                    } else {
                        if (callback != null) {
                            callback.onError("Respon Spotify tidak memuat token akses.");
                        }
                    }
                } catch (Exception e) {
                    if (callback != null) {
                        callback.onError("Gagal memproses data token: " + e.getMessage());
                    }
                }
            }
        });
    }

    public void loginWithRawToken(String token) {
        if (token == null || token.trim().isEmpty()) return;
        prefs.edit()
                .putString(PREF_ACCESS_TOKEN, token.trim())
                .putLong(PREF_TOKEN_TIME, System.currentTimeMillis())
                .remove(PREF_SP_DC)
                .apply();
    }

    public void logout() {
        prefs.edit()
                .remove(PREF_SP_DC)
                .remove(PREF_ACCESS_TOKEN)
                .remove(PREF_TOKEN_TIME)
                .remove(PREF_USER_NAME)
                .apply();
        AppLog.i(TAG, "Spotify credentials cleared");
    }
}
