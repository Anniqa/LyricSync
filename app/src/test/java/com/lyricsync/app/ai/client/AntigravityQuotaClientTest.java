package com.lyricsync.app.ai.client;

import com.lyricsync.app.ai.auth.AntigravityAuthManager;
import com.lyricsync.app.ai.model.AntigravityModel;
import com.lyricsync.app.ai.model.QuotaSummary;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
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
import okio.Buffer;

import static org.junit.Assert.*;

public class AntigravityQuotaClientTest {

    private MockQuotaInterceptor mockInterceptor;
    private OkHttpClient mockHttpClient;
    private FakeAuthManager fakeAuthManager;
    private AntigravityQuotaClient quotaClient;

    @Before
    public void setUp() {
        mockInterceptor = new MockQuotaInterceptor();
        mockHttpClient = new OkHttpClient.Builder()
                .addInterceptor(mockInterceptor)
                .build();
        fakeAuthManager = new FakeAuthManager();
        quotaClient = new AntigravityQuotaClient(fakeAuthManager, mockHttpClient);
    }

    @Test
    public void testParseQuotaSummaryJson() {
        String json = "{\"groups\":[{\"displayName\":\"Gemini Models\",\"buckets\":[{\"bucketId\":\"gemini-weekly\",\"displayName\":\"Weekly Limit Remaining\",\"remainingFraction\":0.8917,\"resetTime\":\"2026-09-10T22:06:15Z\"},{\"bucketId\":\"gemini-5h\",\"displayName\":\"Five Hour Limit Remaining\",\"remainingFraction\":0.9990,\"resetTime\":\"2026-09-06T21:17:34Z\"}]}]}";
        QuotaSummary summary = AntigravityQuotaClient.parseQuota(json);
        assertNotNull(summary);
        assertEquals(0.8917f, summary.geminiWeeklyFraction, 0.001f);
        assertEquals(0.9990f, summary.gemini5hFraction, 0.001f);
        assertEquals("2026-09-10T22:06:15Z", summary.geminiWeeklyResetTime);
        assertEquals("2026-09-06T21:17:34Z", summary.gemini5hResetTime);
    }

    @Test
    public void testParseQuotaSummaryWithThirdPartyBuckets() {
        String json = "{\"groups\":[" +
                "{\"displayName\":\"Gemini Models\",\"buckets\":[" +
                "{\"bucketId\":\"gemini-weekly\",\"remainingFraction\":0.75,\"resetTime\":\"2026-09-10T00:00:00Z\"}," +
                "{\"bucketId\":\"gemini-5h\",\"remainingFraction\":0.90,\"resetTime\":\"2026-09-06T23:00:00Z\"}" +
                "]}," +
                "{\"displayName\":\"Third Party Models\",\"buckets\":[" +
                "{\"bucketId\":\"third-party-weekly\",\"remainingFraction\":0.50,\"resetTime\":\"2026-09-12T10:00:00Z\"}," +
                "{\"bucketId\":\"third-party-5h\",\"remainingFraction\":0.65,\"resetTime\":\"2026-09-06T22:30:00Z\"}" +
                "]}" +
                "]}";
        QuotaSummary summary = AntigravityQuotaClient.parseQuota(json);
        assertNotNull(summary);
        assertEquals(0.75f, summary.geminiWeeklyFraction, 0.001f);
        assertEquals(0.90f, summary.gemini5hFraction, 0.001f);
        assertEquals(0.50f, summary.thirdPartyWeeklyFraction, 0.001f);
        assertEquals(0.65f, summary.thirdParty5hFraction, 0.001f);
        assertEquals("2026-09-12T10:00:00Z", summary.thirdPartyWeeklyResetTime);
        assertEquals("2026-09-06T22:30:00Z", summary.thirdParty5hResetTime);
    }

    @Test
    public void testParseQuotaSummary3pAlias() {
        String json = "{\"groups\":[{\"buckets\":[" +
                "{\"bucketId\":\"3p-weekly\",\"remainingFraction\":0.42,\"resetTime\":\"2026-09-11T12:00:00Z\"}," +
                "{\"bucketId\":\"3p-5h\",\"remainingFraction\":0.88,\"resetTime\":\"2026-09-06T20:00:00Z\"}" +
                "]}]}";
        QuotaSummary summary = AntigravityQuotaClient.parseQuota(json);
        assertNotNull(summary);
        assertEquals(0.42f, summary.thirdPartyWeeklyFraction, 0.001f);
        assertEquals(0.88f, summary.thirdParty5hFraction, 0.001f);
    }

    @Test
    public void testParseQuotaSummaryMalformedJson() {
        QuotaSummary summaryNull = AntigravityQuotaClient.parseQuota(null);
        assertNotNull(summaryNull);
        assertEquals(0f, summaryNull.geminiWeeklyFraction, 0.001f);

        QuotaSummary summaryEmpty = AntigravityQuotaClient.parseQuota("");
        assertNotNull(summaryEmpty);

        QuotaSummary summaryInvalid = AntigravityQuotaClient.parseQuota("{invalid-json");
        assertNotNull(summaryInvalid);
    }

    @Test
    public void testParseQuotaResilienceWithMalformedBucket() {
        String json = "{\"groups\":[{\"buckets\":[" +
                "\"malformed_bucket_string\"," +
                "{\"bucketId\":\"gemini-weekly\",\"remainingFraction\":0.77,\"resetTime\":\"2026-09-10T12:00:00Z\"}" +
                "]}]}";
        QuotaSummary summary = AntigravityQuotaClient.parseQuota(json);
        assertNotNull(summary);
        assertEquals(0.77f, summary.geminiWeeklyFraction, 0.001f);
        assertEquals("2026-09-10T12:00:00Z", summary.geminiWeeklyResetTime);
    }

    @Test
    public void testParseAvailableModelsJson() {
        String json = "{\"models\":{" +
                "\"gemini-2.5-flash\":{\"displayName\":\"Gemini 2.5 Flash\",\"maxOutputTokens\":65536,\"supportsThinking\":true}," +
                "\"chat_20706\":{\"isInternal\":true}," +
                "\"chat_23310\":{\"displayName\":\"Internal Test Model\"}," +
                "\"claude-sonnet-4-6\":{\"displayName\":\"Claude Sonnet 4.6\",\"maxOutputTokens\":32768,\"supportsThinking\":false}" +
                "}}";
        List<AntigravityModel> models = AntigravityQuotaClient.parseModels(json);
        assertNotNull(models);
        assertEquals(2, models.size());

        assertEquals("gemini-2.5-flash", models.get(0).id);
        assertEquals("Gemini 2.5 Flash", models.get(0).displayName);
        assertEquals(65536, models.get(0).maxOutputTokens);
        assertTrue(models.get(0).supportsThinking);
        assertFalse(models.get(0).isInternal);

        assertEquals("claude-sonnet-4-6", models.get(1).id);
        assertEquals("Claude Sonnet 4.6", models.get(1).displayName);
        assertEquals(32768, models.get(1).maxOutputTokens);
        assertFalse(models.get(1).supportsThinking);
    }

    @Test
    public void testParseAvailableModelsFallbackTokens() {
        String json = "{\"models\":{\"gemini-1.5-pro\":{\"displayName\":\"Gemini 1.5 Pro\",\"maxTokens\":8192}}}";
        List<AntigravityModel> models = AntigravityQuotaClient.parseModels(json);
        assertEquals(1, models.size());
        assertEquals("gemini-1.5-pro", models.get(0).id);
        assertEquals(8192, models.get(0).maxOutputTokens);
    }

    @Test
    public void testParseAvailableModelsMalformedJson() {
        List<AntigravityModel> modelsNull = AntigravityQuotaClient.parseModels(null);
        assertNotNull(modelsNull);
        assertTrue(modelsNull.isEmpty());

        List<AntigravityModel> modelsEmpty = AntigravityQuotaClient.parseModels("");
        assertNotNull(modelsEmpty);
        assertTrue(modelsEmpty.isEmpty());

        List<AntigravityModel> modelsInvalid = AntigravityQuotaClient.parseModels("not json");
        assertNotNull(modelsInvalid);
        assertTrue(modelsInvalid.isEmpty());
    }

    @Test
    public void testParseModelsResilienceWithMalformedItem() {
        String json = "{\"models\":{" +
                "\"broken-model\": \"not an object\"," +
                "\"gemini-2.5-flash\":{\"displayName\":\"Gemini 2.5 Flash\",\"maxOutputTokens\":65536}" +
                "}}";
        List<AntigravityModel> models = AntigravityQuotaClient.parseModels(json);
        assertNotNull(models);
        assertEquals(1, models.size());
        assertEquals("gemini-2.5-flash", models.get(0).id);
    }

    @Test
    public void testFetchModelsSuccess() throws InterruptedException {
        fakeAuthManager.mockToken = "ya29.valid-mock-token";
        mockInterceptor.modelsResponseJson = "{\"models\":{\"gemini-2.5-flash\":{\"displayName\":\"Gemini 2.5 Flash\",\"maxOutputTokens\":65536}}}";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<List<AntigravityModel>> resultRef = new AtomicReference<>();
        final AtomicReference<String> errorRef = new AtomicReference<>();

        quotaClient.fetchModels(new AntigravityQuotaClient.ModelDiscoveryCallback() {
            @Override
            public void onModelsLoaded(List<AntigravityModel> models) {
                resultRef.set(models);
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNull(errorRef.get());
        assertNotNull(resultRef.get());
        assertEquals(1, resultRef.get().size());
        assertEquals("gemini-2.5-flash", resultRef.get().get(0).id);

        // Verify HTTP headers & body
        assertEquals(AntigravityQuotaClient.ENDPOINT_MODELS, mockInterceptor.lastRequestUrl);
        assertEquals("Bearer ya29.valid-mock-token", mockInterceptor.lastAuthorizationHeader);
        assertEquals(AntigravityQuotaClient.USER_AGENT, mockInterceptor.lastUserAgentHeader);
        assertEquals("{}", mockInterceptor.lastRequestBody);
    }

    @Test
    public void testFetchModelsCallbackExceptionDoesNotTriggerOnError() throws InterruptedException {
        fakeAuthManager.mockToken = "ya29.valid-mock-token";
        mockInterceptor.modelsResponseJson = "{\"models\":{\"gemini-2.5-flash\":{\"displayName\":\"Gemini 2.5 Flash\"}}}";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean errorTriggered = new AtomicBoolean(false);

        quotaClient.fetchModels(new AntigravityQuotaClient.ModelDiscoveryCallback() {
            @Override
            public void onModelsLoaded(List<AntigravityModel> models) {
                latch.countDown();
                throw new RuntimeException("Simulated caller exception in onModelsLoaded");
            }

            @Override
            public void onError(String error) {
                errorTriggered.set(true);
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        // Wait briefly to ensure onError wasn't called asynchronously
        Thread.sleep(50);
        assertFalse("onError must NOT be called if onModelsLoaded threw an exception", errorTriggered.get());
    }

    @Test
    public void testFetchModelsAuthFailure() throws InterruptedException {
        fakeAuthManager.mockToken = null;
        fakeAuthManager.mockError = "Not authenticated: no refresh token available";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> errorRef = new AtomicReference<>();

        quotaClient.fetchModels(new AntigravityQuotaClient.ModelDiscoveryCallback() {
            @Override
            public void onModelsLoaded(List<AntigravityModel> models) {
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNotNull(errorRef.get());
        assertTrue(errorRef.get().contains("Not authenticated"));
        assertEquals(0, mockInterceptor.requestCount);
    }

    @Test
    public void testFetchModelsSynchronousTokenExceptionHandled() throws InterruptedException {
        fakeAuthManager.throwSynchronously = true;

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> errorRef = new AtomicReference<>();

        quotaClient.fetchModels(new AntigravityQuotaClient.ModelDiscoveryCallback() {
            @Override
            public void onModelsLoaded(List<AntigravityModel> models) {
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNotNull(errorRef.get());
        assertTrue(errorRef.get().contains("Failed to acquire access token"));
    }

    @Test
    public void testFetchModelsHttpError() throws InterruptedException {
        fakeAuthManager.mockToken = "ya29.valid-mock-token";
        mockInterceptor.responseCode = 403;
        mockInterceptor.modelsResponseJson = "{\"error\":{\"code\":403,\"message\":\"Quota exceeded or permission denied\"}}";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> errorRef = new AtomicReference<>();

        quotaClient.fetchModels(new AntigravityQuotaClient.ModelDiscoveryCallback() {
            @Override
            public void onModelsLoaded(List<AntigravityModel> models) {
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNotNull(errorRef.get());
        assertTrue(errorRef.get().contains("Quota exceeded or permission denied"));
    }

    @Test
    public void testFetchQuotaSuccess() throws InterruptedException {
        fakeAuthManager.mockToken = "ya29.valid-mock-token";
        mockInterceptor.quotaResponseJson = "{\"groups\":[{\"displayName\":\"Gemini Models\",\"buckets\":[{\"bucketId\":\"gemini-weekly\",\"remainingFraction\":0.8917,\"resetTime\":\"2026-09-10T22:06:15Z\"}]}]}";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<QuotaSummary> resultRef = new AtomicReference<>();
        final AtomicReference<String> errorRef = new AtomicReference<>();

        quotaClient.fetchQuota(new AntigravityQuotaClient.QuotaCallback() {
            @Override
            public void onQuotaLoaded(QuotaSummary summary) {
                resultRef.set(summary);
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNull(errorRef.get());
        assertNotNull(resultRef.get());
        assertEquals(0.8917f, resultRef.get().geminiWeeklyFraction, 0.001f);
        assertEquals("2026-09-10T22:06:15Z", resultRef.get().geminiWeeklyResetTime);

        // Verify HTTP headers & body
        assertEquals(AntigravityQuotaClient.ENDPOINT_QUOTA, mockInterceptor.lastRequestUrl);
        assertEquals("Bearer ya29.valid-mock-token", mockInterceptor.lastAuthorizationHeader);
        assertEquals(AntigravityQuotaClient.USER_AGENT, mockInterceptor.lastUserAgentHeader);
        assertTrue(mockInterceptor.lastRequestBody.contains("\"project\":\"aicode-consumers\"") ||
                mockInterceptor.lastRequestBody.contains("\"project\": \"aicode-consumers\""));
    }

    @Test
    public void testFetchQuotaCallbackExceptionDoesNotTriggerOnError() throws InterruptedException {
        fakeAuthManager.mockToken = "ya29.valid-mock-token";
        mockInterceptor.quotaResponseJson = "{\"groups\":[{\"displayName\":\"Gemini Models\",\"buckets\":[{\"bucketId\":\"gemini-5h\",\"remainingFraction\":0.99}]}]}";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean errorTriggered = new AtomicBoolean(false);

        quotaClient.fetchQuota(new AntigravityQuotaClient.QuotaCallback() {
            @Override
            public void onQuotaLoaded(QuotaSummary summary) {
                latch.countDown();
                throw new RuntimeException("Simulated caller exception in onQuotaLoaded");
            }

            @Override
            public void onError(String error) {
                errorTriggered.set(true);
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertFalse("onError must NOT be called if onQuotaLoaded threw an exception", errorTriggered.get());
    }

    @Test
    public void testFetchQuotaAuthFailure() throws InterruptedException {
        fakeAuthManager.mockToken = null;
        fakeAuthManager.mockError = "Authentication expired";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> errorRef = new AtomicReference<>();

        quotaClient.fetchQuota(new AntigravityQuotaClient.QuotaCallback() {
            @Override
            public void onQuotaLoaded(QuotaSummary summary) {
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNotNull(errorRef.get());
        assertTrue(errorRef.get().contains("Authentication expired"));
        assertEquals(0, mockInterceptor.requestCount);
    }

    @Test
    public void testFetchQuotaSynchronousTokenExceptionHandled() throws InterruptedException {
        fakeAuthManager.throwSynchronously = true;

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> errorRef = new AtomicReference<>();

        quotaClient.fetchQuota(new AntigravityQuotaClient.QuotaCallback() {
            @Override
            public void onQuotaLoaded(QuotaSummary summary) {
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNotNull(errorRef.get());
        assertTrue(errorRef.get().contains("Failed to acquire access token"));
    }

    @Test
    public void testFetchQuotaHttpError() throws InterruptedException {
        fakeAuthManager.mockToken = "ya29.valid-mock-token";
        mockInterceptor.responseCode = 500;
        mockInterceptor.quotaResponseJson = "{\"error\":\"Internal server error\"}";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> errorRef = new AtomicReference<>();

        quotaClient.fetchQuota(new AntigravityQuotaClient.QuotaCallback() {
            @Override
            public void onQuotaLoaded(QuotaSummary summary) {
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNotNull(errorRef.get());
        assertTrue(errorRef.get().contains("Internal server error"));
    }

    @Test
    public void testNullCallbacksDoNotThrow() {
        fakeAuthManager.mockToken = "ya29.valid-mock-token";
        mockInterceptor.modelsResponseJson = "{\"models\":{}}";
        mockInterceptor.quotaResponseJson = "{\"groups\":[]}";

        try {
            quotaClient.fetchModels(null);
            quotaClient.fetchQuota(null);
        } catch (Exception e) {
            fail("Should not throw when callback is null: " + e.getMessage());
        }
    }

    // --- Helpers ---

    static class FakeAuthManager extends AntigravityAuthManager {
        volatile String mockToken;
        volatile String mockError;
        volatile boolean throwSynchronously = false;

        FakeAuthManager() {
            super((android.content.SharedPreferences) null, null);
        }

        @Override
        public void getValidAccessToken(TokenCallback callback) {
            if (throwSynchronously) {
                throw new IllegalStateException("Simulated synchronous token exception");
            }
            if (callback == null) return;
            if (mockToken != null) {
                callback.onTokenReady(mockToken);
            } else {
                callback.onError(mockError != null ? mockError : "Not authenticated");
            }
        }
    }

    static class MockQuotaInterceptor implements Interceptor {
        volatile int requestCount = 0;
        volatile int responseCode = 200;
        volatile String modelsResponseJson;
        volatile String quotaResponseJson;

        volatile String lastRequestUrl;
        volatile String lastAuthorizationHeader;
        volatile String lastUserAgentHeader;
        volatile String lastRequestBody;

        @Override
        public Response intercept(Chain chain) throws IOException {
            requestCount++;
            Request request = chain.request();
            lastRequestUrl = request.url().toString();
            lastAuthorizationHeader = request.header("Authorization");
            lastUserAgentHeader = request.header("User-Agent");

            if (request.body() != null) {
                Buffer buffer = new Buffer();
                request.body().writeTo(buffer);
                lastRequestBody = buffer.readUtf8();
            } else {
                lastRequestBody = null;
            }

            String responseBodyStr = "{}";
            if (lastRequestUrl.contains("fetchAvailableModels")) {
                responseBodyStr = modelsResponseJson != null ? modelsResponseJson : "{\"models\":{}}";
            } else if (lastRequestUrl.contains("retrieveUserQuotaSummary")) {
                responseBodyStr = quotaResponseJson != null ? quotaResponseJson : "{\"groups\":[]}";
            }

            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(responseCode)
                    .message(responseCode == 200 ? "OK" : "Error")
                    .body(ResponseBody.create(MediaType.parse("application/json; charset=utf-8"), responseBodyStr))
                    .build();
        }
    }
}
