package com.lyricsync.app.ai.auth;

import android.app.Activity;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.*;

public class AntigravityAuthManagerTest {

    private FakeSharedPreferences fakePrefs;
    private MockHttpInterceptor mockInterceptor;
    private OkHttpClient mockHttpClient;
    private AntigravityAuthManager authManager;

    @Before
    public void setUp() {
        fakePrefs = new FakeSharedPreferences();
        mockInterceptor = new MockHttpInterceptor();
        mockHttpClient = new OkHttpClient.Builder()
                .addInterceptor(mockInterceptor)
                .build();
        authManager = new AntigravityAuthManager(fakePrefs, mockHttpClient);
        AntigravityAuthManager.setInstance(authManager);
    }

    @Test
    public void testAuthorizeUrlContainsRequiredScopes() {
        String authUrl = AntigravityAuthManager.buildAuthorizeUrl("test_state");
        assertTrue(authUrl.contains("client_id=1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"));
        assertTrue(authUrl.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A51121%2Foauth-callback"));
        assertTrue(authUrl.contains("cloud-platform"));
        assertTrue(authUrl.contains("access_type=offline"));
        assertTrue(authUrl.contains("state=test_state"));
        assertTrue(authUrl.contains("response_type=code"));
        assertTrue(authUrl.contains("prompt=consent"));
    }

    @Test
    public void testInitialStateUnauthenticated() {
        assertFalse(authManager.isAuthenticated());
        assertNull(authManager.getEmail());
        assertNull(authManager.getRefreshToken());
        assertNull(authManager.getAccessToken());
    }

    @Test
    public void testAuthenticatedWhenRefreshTokenExists() {
        fakePrefs.edit().putString(AntigravityAuthManager.PREF_REFRESH_TOKEN, "1//sample-refresh-token").apply();
        fakePrefs.edit().putString(AntigravityAuthManager.PREF_EMAIL, "user@example.com").apply();

        assertTrue(authManager.isAuthenticated());
        assertEquals("1//sample-refresh-token", authManager.getRefreshToken());
        assertEquals("user@example.com", authManager.getEmail());
    }

    @Test
    public void testLogoutClearsCredentials() {
        fakePrefs.edit()
                .putString(AntigravityAuthManager.PREF_ACCESS_TOKEN, "access-token-123")
                .putString(AntigravityAuthManager.PREF_REFRESH_TOKEN, "refresh-token-123")
                .putLong(AntigravityAuthManager.PREF_TOKEN_EXPIRY, System.currentTimeMillis() + 1000000)
                .putString(AntigravityAuthManager.PREF_EMAIL, "user@example.com")
                .apply();

        assertTrue(authManager.isAuthenticated());
        authManager.logout();

        assertFalse(authManager.isAuthenticated());
        assertNull(authManager.getRefreshToken());
        assertNull(authManager.getAccessToken());
        assertNull(authManager.getEmail());
        assertEquals(0L, authManager.getTokenExpiry());
    }

    @Test
    public void testGetValidAccessTokenReturnsCachedWhenFresh() throws InterruptedException {
        String freshToken = "ya29.fresh-token";
        long validExpiry = System.currentTimeMillis() + 600000L; // 10 mins from now
        fakePrefs.edit()
                .putString(AntigravityAuthManager.PREF_ACCESS_TOKEN, freshToken)
                .putString(AntigravityAuthManager.PREF_REFRESH_TOKEN, "refresh-token")
                .putLong(AntigravityAuthManager.PREF_TOKEN_EXPIRY, validExpiry)
                .apply();

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> tokenRef = new AtomicReference<>();
        final AtomicReference<String> errorRef = new AtomicReference<>();

        authManager.getValidAccessToken(new AntigravityAuthManager.TokenCallback() {
            @Override
            public void onTokenReady(String accessToken) {
                tokenRef.set(accessToken);
                latch.countDown();
            }

            @Override
            public void onError(String message) {
                errorRef.set(message);
                latch.countDown();
            }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertNull(errorRef.get());
        assertEquals(freshToken, tokenRef.get());
        // Interceptor should not be called
        assertEquals(0, mockInterceptor.requestCount);
    }

    @Test
    public void testGetValidAccessTokenFailsWhenUnauthenticated() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> errorRef = new AtomicReference<>();

        authManager.getValidAccessToken(new AntigravityAuthManager.TokenCallback() {
            @Override
            public void onTokenReady(String accessToken) {
                latch.countDown();
            }

            @Override
            public void onError(String message) {
                errorRef.set(message);
                latch.countDown();
            }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertNotNull(errorRef.get());
        assertTrue(errorRef.get().toLowerCase().contains("not authenticated") ||
                errorRef.get().toLowerCase().contains("refresh token"));
    }

    @Test
    public void testSilentTokenRefreshWhenTokenExpired() throws InterruptedException {
        // Token expired 10 minutes ago
        fakePrefs.edit()
                .putString(AntigravityAuthManager.PREF_ACCESS_TOKEN, "ya29.old-expired-token")
                .putString(AntigravityAuthManager.PREF_REFRESH_TOKEN, "1//valid-refresh-token")
                .putLong(AntigravityAuthManager.PREF_TOKEN_EXPIRY, System.currentTimeMillis() - 600000L)
                .apply();

        mockInterceptor.tokenResponseJson = "{" +
                "\"access_token\":\"ya29.refreshed-new-access-token\"," +
                "\"expires_in\":3600," +
                "\"token_type\":\"Bearer\"" +
                "}";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> tokenRef = new AtomicReference<>();
        final AtomicReference<String> errorRef = new AtomicReference<>();

        authManager.getValidAccessToken(new AntigravityAuthManager.TokenCallback() {
            @Override
            public void onTokenReady(String accessToken) {
                tokenRef.set(accessToken);
                latch.countDown();
            }

            @Override
            public void onError(String message) {
                errorRef.set(message);
                latch.countDown();
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNull(errorRef.get());
        assertEquals("ya29.refreshed-new-access-token", tokenRef.get());
        assertEquals("ya29.refreshed-new-access-token", authManager.getAccessToken());
        assertTrue(authManager.getTokenExpiry() > System.currentTimeMillis());
        assertTrue(mockInterceptor.requestCount > 0);
    }

    @Test
    public void testExchangeCodeForTokensAndFetchEmail() throws InterruptedException {
        mockInterceptor.tokenResponseJson = "{" +
                "\"access_token\":\"ya29.code-exchanged-access-token\"," +
                "\"refresh_token\":\"1//code-exchanged-refresh-token\"," +
                "\"expires_in\":3600," +
                "\"token_type\":\"Bearer\"" +
                "}";
        mockInterceptor.userInfoResponseJson = "{" +
                "\"id\":\"1001\"," +
                "\"email\":\"musician@lyricsync.io\"," +
                "\"verified_email\":true" +
                "}";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> successEmailRef = new AtomicReference<>();
        final AtomicReference<String> errorRef = new AtomicReference<>();

        authManager.exchangeCodeForTokens("4/0Aean-sample-auth-code", new AntigravityAuthManager.AuthCallback() {
            @Override
            public void onSuccess(String email) {
                successEmailRef.set(email);
                latch.countDown();
            }

            @Override
            public void onError(String message) {
                errorRef.set(message);
                latch.countDown();
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNull(errorRef.get());
        assertEquals("musician@lyricsync.io", successEmailRef.get());
        assertEquals("musician@lyricsync.io", authManager.getEmail());
        assertEquals("1//code-exchanged-refresh-token", authManager.getRefreshToken());
        assertEquals("ya29.code-exchanged-access-token", authManager.getAccessToken());
        assertTrue(authManager.isAuthenticated());
    }

    @Test
    public void testManualTokenLoginWithRefreshTokenDirectly() throws InterruptedException {
        mockInterceptor.tokenResponseJson = "{" +
                "\"access_token\":\"ya29.manual-access-token\"," +
                "\"expires_in\":3600," +
                "\"token_type\":\"Bearer\"" +
                "}";
        mockInterceptor.userInfoResponseJson = "{" +
                "\"id\":\"1002\"," +
                "\"email\":\"manual@lyricsync.io\"" +
                "}";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> emailRef = new AtomicReference<>();

        authManager.manualTokenLogin("1//my-pasted-refresh-token", new AntigravityAuthManager.AuthCallback() {
            @Override
            public void onSuccess(String email) {
                emailRef.set(email);
                latch.countDown();
            }

            @Override
            public void onError(String message) {
                latch.countDown();
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals("manual@lyricsync.io", emailRef.get());
        assertEquals("1//my-pasted-refresh-token", authManager.getRefreshToken());
        assertTrue(authManager.isAuthenticated());
    }

    @Test
    public void testManualTokenLoginEmptyInputFails() {
        final AtomicReference<String> errorRef = new AtomicReference<>();
        authManager.manualTokenLogin("   ", new AntigravityAuthManager.AuthCallback() {
            @Override
            public void onSuccess(String email) {}

            @Override
            public void onError(String message) {
                errorRef.set(message);
            }
        });
        assertNotNull(errorRef.get());
    }

    @Test
    public void testStartBrowserLoginLaunchesAndHandlesCode() throws InterruptedException {
        mockInterceptor.tokenResponseJson = "{" +
                "\"access_token\":\"ya29.browser-access-token\"," +
                "\"refresh_token\":\"1//browser-refresh-token\"," +
                "\"expires_in\":3600" +
                "}";
        mockInterceptor.userInfoResponseJson = "{\"email\":\"browser@lyricsync.io\"}";

        final AtomicReference<String> launchedUrl = new AtomicReference<>();
        final OAuthLoopbackServer testServer = new OAuthLoopbackServer();

        AntigravityAuthManager customAuth = new AntigravityAuthManager(fakePrefs, mockHttpClient) {
            @Override
            protected OAuthLoopbackServer createLoopbackServer() {
                return testServer;
            }

            @Override
            protected void launchBrowserUrl(Activity activity, String url) {
                launchedUrl.set(url);
            }
        };

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> resultEmail = new AtomicReference<>();

        customAuth.startBrowserLogin(null, new AntigravityAuthManager.AuthCallback() {
            @Override
            public void onSuccess(String email) {
                resultEmail.set(email);
                latch.countDown();
            }

            @Override
            public void onError(String message) {
                latch.countDown();
            }
        });

        assertNotNull(launchedUrl.get());
        assertTrue(launchedUrl.get().contains("client_id="));
        assertTrue(testServer.isRunning());

        // Simulate callback from browser by making local HTTP request
        OkHttpClient client = new OkHttpClient();
        Request callbackReq = new Request.Builder()
                .url("http://127.0.0.1:51121/oauth-callback?code=mock_code&state=mock_state")
                .get()
                .build();

        try {
            Response resp = client.newCall(callbackReq).execute();
            resp.close();
        } catch (IOException e) {
            fail("Failed to hit loopback server: " + e.getMessage());
        }

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals("browser@lyricsync.io", resultEmail.get());
        assertEquals("1//browser-refresh-token", customAuth.getRefreshToken());
    }

    @Test
    public void testStartBrowserLoginWithNullActivityReportsError() {
        final AtomicReference<String> errorRef = new AtomicReference<>();
        authManager.startBrowserLogin(null, new AntigravityAuthManager.AuthCallback() {
            @Override
            public void onSuccess(String email) {}

            @Override
            public void onError(String message) {
                errorRef.set(message);
            }
        });

        assertNotNull(errorRef.get());
        assertTrue(errorRef.get().contains("Activity cannot be null"));
    }

    // --- Test Helpers ---

    static class MockHttpInterceptor implements Interceptor {
        volatile int requestCount = 0;
        volatile String tokenResponseJson;
        volatile String userInfoResponseJson;
        volatile int tokenResponseCode = 200;

        @Override
        public Response intercept(Chain chain) throws IOException {
            requestCount++;
            Request request = chain.request();
            String url = request.url().toString();

            if (url.startsWith(AntigravityAuthManager.TOKEN_URL)) {
                String body = tokenResponseJson != null ? tokenResponseJson : "{\"access_token\":\"dummy\",\"expires_in\":3600}";
                return new Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(tokenResponseCode)
                        .message(tokenResponseCode == 200 ? "OK" : "Error")
                        .body(ResponseBody.create(MediaType.parse("application/json; charset=utf-8"), body))
                        .build();
            } else if (url.startsWith("https://www.googleapis.com/oauth2/v1/userinfo")) {
                String body = userInfoResponseJson != null ? userInfoResponseJson : "{\"email\":\"default@test.com\"}";
                return new Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(ResponseBody.create(MediaType.parse("application/json; charset=utf-8"), body))
                        .build();
            }

            return chain.proceed(request);
        }
    }

    static class FakeSharedPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(values);
        }

        @Override
        public String getString(String key, String defValue) {
            Object val = values.get(key);
            return val instanceof String ? (String) val : defValue;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            return null;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object val = values.get(key);
            return val instanceof Integer ? (Integer) val : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object val = values.get(key);
            return val instanceof Long ? (Long) val : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object val = values.get(key);
            return val instanceof Float ? (Float) val : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object val = values.get(key);
            return val instanceof Boolean ? (Boolean) val : defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new FakeEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        class FakeEditor implements Editor {
            private final Map<String, Object> changes = new HashMap<>(values);

            @Override
            public Editor putString(String key, String value) {
                changes.put(key, value);
                return this;
            }

            @Override
            public Editor putStringSet(String key, Set<String> values) {
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                changes.put(key, value);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                changes.put(key, value);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                changes.put(key, value);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                changes.put(key, value);
                return this;
            }

            @Override
            public Editor remove(String key) {
                changes.remove(key);
                return this;
            }

            @Override
            public Editor clear() {
                changes.clear();
                return this;
            }

            @Override
            public boolean commit() {
                values.clear();
                values.putAll(changes);
                return true;
            }

            @Override
            public void apply() {
                commit();
            }
        }
    }
}
