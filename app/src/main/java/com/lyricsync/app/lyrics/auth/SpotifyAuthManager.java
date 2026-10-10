package com.lyricsync.app.lyrics.auth;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.util.AppLog;

import java.util.concurrent.TimeUnit;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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

    private static volatile SpotifyAuthManager instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final OkHttpClient httpClient;

    /** Held across a token refresh so concurrent callers share one sp_dc exchange. */
    private final Object refreshLock = new Object();
    /** Guards credential state: the token/time prefs pair and tokenGeneration. */
    private final Object stateLock = new Object();
    /**
     * Bumped whenever persisted credentials are explicitly replaced or cleared
     * (invalidateToken/logout/login). An exchange only persists the token it fetched
     * if the generation it started with is still current, so a refresh that raced
     * invalidation cannot resurrect the invalidated token.
     */
    private long tokenGeneration = 0L;

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

    public static String cleanCookie(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 2) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.startsWith("Cookie:")) {
            s = s.substring(7).trim();
        }
        if (s.startsWith("sp_dc=")) {
            s = s.substring(6).trim();
        }
        int semi = s.indexOf(';');
        if (semi > 0) {
            s = s.substring(0, semi).trim();
        }
        return s;
    }

    /**
     * The cleaned sp_dc the user saved, or null when no Spotify session is connected.
     * There is no built-in fallback: a shared cookie is always rejected by Spotify (HTTP 401),
     * so callers fall through to the anonymous web-player token instead.
     */
    public String getEffectiveSpDc() {
        String spDc = prefs.getString(PREF_SP_DC, null);
        if (spDc == null || spDc.trim().isEmpty()) {
            return null;
        }
        return cleanCookie(spDc);
    }

    /**
     * True only when the user has actually connected a Spotify session
     * (a saved sp_dc cookie or a pasted access token).
     */
    public boolean isAuthenticated() {
        String spDc = getStoredSpDc();
        if (spDc != null && !cleanCookie(spDc).isEmpty()) {
            return true;
        }
        String token = getStoredToken();
        return token != null && !token.trim().isEmpty();
    }

    public void invalidateToken() {
        synchronized (stateLock) {
            tokenGeneration++;
            prefs.edit().remove(PREF_ACCESS_TOKEN).remove(PREF_TOKEN_TIME).apply();
        }
        AppLog.i(TAG, "Invalidated Spotify access token, will refresh with sp_dc on next request");
    }

    public String getStoredSpDc() {
        return prefs.getString(PREF_SP_DC, null);
    }

    public String getStoredToken() {
        return prefs.getString(PREF_ACCESS_TOKEN, null);
    }

    public String getValidAccessTokenSync() {
        synchronized (refreshLock) {
            // Fast path: reuse a fresh token. The token/time pair is read atomically
            // under stateLock so we never observe a mismatched snapshot.
            String token;
            synchronized (stateLock) {
                token = prefs.getString(PREF_ACCESS_TOKEN, null);
                long time = prefs.getLong(PREF_TOKEN_TIME, 0);
                boolean fresh = token != null && !token.isEmpty()
                        && (System.currentTimeMillis() - time) < TOKEN_VALIDITY_MS;
                if (fresh) {
                    return token;
                }
            }

            String spDc = getEffectiveSpDc();
            if (spDc != null && !spDc.isEmpty()) {
                // Refresh token synchronously from the user session. refreshLock makes
                // concurrent callers wait here and then reuse the token just persisted,
                // so only one exchange happens per stale token.
                long epoch = currentGeneration();
                String refreshed = exchangeSpDcSync(spDc, epoch);
                if (refreshed != null && !refreshed.isEmpty()) {
                    if (epoch == currentGeneration()) {
                        return refreshed;
                    }
                    // invalidateToken() (or another credential change) ran while this
                    // exchange was in flight: do not resurface the invalidated token.
                    return null;
                }
            }

            return token; // last resort: whatever we had, even if stale
        }
    }

    private long currentGeneration() {
        synchronized (stateLock) {
            return tokenGeneration;
        }
    }

    private void persistTokenIfCurrent(String accessToken, long epoch) {
        synchronized (stateLock) {
            if (epoch != tokenGeneration) {
                return;
            }
            prefs.edit()
                    .putString(PREF_ACCESS_TOKEN, accessToken)
                    .putLong(PREF_TOKEN_TIME, System.currentTimeMillis())
                    .apply();
        }
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

    public String exchangeSpDcSync(String spDc) {
        if (spDc == null || spDc.trim().isEmpty()) return null;
        return exchangeSpDcSync(cleanCookie(spDc), currentGeneration());
    }

    private String exchangeSpDcSync(String cleanSpDc, long epoch) {
        long nowSec = System.currentTimeMillis() / 1000L;
        long[] offsets = new long[]{ 0, -30, 30 };
        for (long offset : offsets) {
            String token = tryExchangeAtTime(cleanSpDc, nowSec + offset);
            if (token != null && !token.isEmpty()) {
                persistTokenIfCurrent(token, epoch);
                return token;
            }
        }
        return null;
    }

    private String tryExchangeAtTime(String cleanSpDc, long timeSec) {
        try {
            String totp = generateTotp(timeSec);
            String url = TOKEN_API_BASE + "?reason=init&productType=web-player&totp="
                    + totp + "&totpVer=" + cachedSecretVersion + "&totpServer=" + totp;

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", "sp_dc=" + cleanSpDc)
                    .header("Accept", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    AppLog.w(TAG, "sp_dc exchange attempt HTTP " + response.code());
                    return null;
                }
                String body = response.body().string();
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                if (root.has("accessToken") && !root.get("accessToken").isJsonNull()) {
                    String accessToken = root.get("accessToken").getAsString();
                    boolean isAnon = root.has("isAnonymous") && root.get("isAnonymous").getAsBoolean();
                    AppLog.i(TAG, "Exchanged sp_dc for accessToken len=" + accessToken.length() + " isAnon=" + isAnon);
                    return accessToken;
                }
            }
        } catch (Exception e) {
            AppLog.w(TAG, "Failed exchange attempt: " + e.getMessage());
        }
        return null;
    }

    public void loginWithSpDc(final String spDc, final AuthCallback callback) {
        if (spDc == null || spDc.trim().isEmpty()) {
            if (callback != null) callback.onError("Cookie sp_dc tidak boleh kosong");
            return;
        }

        final String cleanSpDc = cleanCookie(spDc);
        new Thread(() -> {
            String token = exchangeSpDcSync(cleanSpDc);
            if (token != null && !token.isEmpty()) {
                synchronized (stateLock) {
                    tokenGeneration++;
                    prefs.edit()
                            .putString(PREF_SP_DC, cleanSpDc)
                            .putString(PREF_ACCESS_TOKEN, token)
                            .putLong(PREF_TOKEN_TIME, System.currentTimeMillis())
                            .apply();
                }
                AppLog.i(TAG, "Successfully logged in with sp_dc, token len=" + token.length());
                if (callback != null) callback.onSuccess(token, false);
            } else {
                if (callback != null) {
                    callback.onError("Validasi gagal. Pastikan cookie sp_dc masih aktif.");
                }
            }
        }).start();
    }

    public void loginWithRawToken(String token) {
        if (token == null || token.trim().isEmpty()) return;
        synchronized (stateLock) {
            tokenGeneration++;
            prefs.edit()
                    .putString(PREF_ACCESS_TOKEN, token.trim())
                    .putLong(PREF_TOKEN_TIME, System.currentTimeMillis())
                    .remove(PREF_SP_DC)
                    .apply();
        }
    }

    public void logout() {
        synchronized (stateLock) {
            tokenGeneration++;
            prefs.edit()
                    .remove(PREF_SP_DC)
                    .remove(PREF_ACCESS_TOKEN)
                    .remove(PREF_TOKEN_TIME)
                    .remove(PREF_USER_NAME)
                    .apply();
        }
        AppLog.i(TAG, "Spotify credentials cleared");
    }
}
