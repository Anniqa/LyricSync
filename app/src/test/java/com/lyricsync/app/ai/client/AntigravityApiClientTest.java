package com.lyricsync.app.ai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.ai.auth.AntigravityAuthManager;
import com.lyricsync.app.ai.model.AiChatMessage;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

public class AntigravityApiClientTest {

    private FakeAuthManager fakeAuthManager;
    private MockStreamInterceptor mockInterceptor;
    private OkHttpClient mockHttpClient;
    private AntigravityApiClient apiClient;

    @Before
    public void setUp() {
        fakeAuthManager = new FakeAuthManager();
        mockInterceptor = new MockStreamInterceptor();
        mockHttpClient = new OkHttpClient.Builder()
                .addInterceptor(mockInterceptor)
                .build();
        apiClient = new AntigravityApiClient(fakeAuthManager, mockHttpClient);
    }

    @Test
    public void testBuildRequestBodyBasic() {
        List<AiChatMessage> messages = Collections.singletonList(
                AiChatMessage.user("Hello world")
        );

        String json = AntigravityApiClient.buildRequestBodyWithContents("gemini-3-flash", AntigravityApiClient.convertMessagesToContents(messages), false, null);
        assertNotNull(json);

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("aicode-consumers", root.get("project").getAsString());
        assertEquals("gemini-3-flash", root.get("model").getAsString());
        assertEquals("antigravity", root.get("userAgent").getAsString());
        assertEquals("agent", root.get("requestType").getAsString());
        assertTrue(root.get("requestId").getAsString().startsWith("agent-"));

        JsonObject req = root.getAsJsonObject("request");
        assertNotNull(req);

        JsonArray contents = req.getAsJsonArray("contents");
        assertEquals(1, contents.size());
        JsonObject msg0 = contents.get(0).getAsJsonObject();
        assertEquals("user", msg0.get("role").getAsString());
        JsonArray parts = msg0.getAsJsonArray("parts");
        assertEquals(1, parts.size());
        assertEquals("Hello world", parts.get(0).getAsJsonObject().get("text").getAsString());

        // Lyric tools are always injected; web_search/web_fetch only when enabled.
        assertTrue("Request should always contain tools", req.has("tools"));
        JsonArray tools = req.getAsJsonArray("tools");
        assertEquals(1, tools.size());
        JsonArray decls = tools.get(0).getAsJsonObject().getAsJsonArray("functionDeclarations");
        assertNotNull(decls);
        for (JsonElement decl : decls) {
            String name = decl.getAsJsonObject().get("name").getAsString();
            assertFalse("web_search/web_fetch must be absent when enableWebSearch is false",
                    "web_search".equals(name) || "web_fetch".equals(name));
        }

        // No maxOutputTokens artificial clamping
        assertFalse("Payload must NOT clamp maxOutputTokens", json.contains("maxOutputTokens"));
        assertFalse("Payload must NOT clamp maxTokens", json.contains("maxTokens"));

        // No thinkingConfig when effortLevel is null
        assertFalse("Payload should not contain thinkingConfig when effortLevel is null", json.contains("thinkingConfig"));
    }

    @Test
    public void testBuildRequestBodyWithWebSearch() {
        List<AiChatMessage> messages = Collections.singletonList(
                AiChatMessage.user("Explain lyrics of Bohemian Rhapsody")
        );

        String json = AntigravityApiClient.buildRequestBodyWithContents("gemini-2.5-flash", AntigravityApiClient.convertMessagesToContents(messages), true, null);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject req = root.getAsJsonObject("request");

        assertTrue("Request should contain tools array", req.has("tools"));
        JsonArray tools = req.getAsJsonArray("tools");
        assertEquals(1, tools.size());
        JsonObject tool0 = tools.get(0).getAsJsonObject();
        assertTrue("Tool should contain functionDeclarations", tool0.has("functionDeclarations"));
        assertFalse("Tool must NOT contain googleSearch built-in tool alongside functionDeclarations to avoid Gemini API conflict",
                tool0.has("googleSearch"));
    }

    @Test
    public void testBuildRequestBodyWithReasoningEffort() {
        List<AiChatMessage> messages = Collections.singletonList(
                AiChatMessage.user("Analyze deep symbolism")
        );

        String json = AntigravityApiClient.buildRequestBodyWithContents("gemini-3-flash", AntigravityApiClient.convertMessagesToContents(messages), false, "high");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject req = root.getAsJsonObject("request");

        assertTrue("Request should have generationConfig", req.has("generationConfig"));
        JsonObject genConfig = req.getAsJsonObject("generationConfig");
        assertTrue("generationConfig should contain thinkingConfig", genConfig.has("thinkingConfig"));

        JsonObject thinking = genConfig.getAsJsonObject("thinkingConfig");
        assertFalse("includeThoughts should be false", thinking.get("includeThoughts").getAsBoolean());
        assertEquals("high", thinking.get("thinkingLevel").getAsString());

        // Still uncapped tokens
        assertFalse("Payload must not clamp maxOutputTokens even when thinkingConfig is set", json.contains("maxOutputTokens"));
    }

    @Test
    public void testBuildRequestBodyReasoningEffortCaseInsensitive() {
        List<AiChatMessage> messages = Collections.singletonList(
                AiChatMessage.user("Think deeply")
        );

        String json = AntigravityApiClient.buildRequestBodyWithContents("gemini-3-flash", AntigravityApiClient.convertMessagesToContents(messages), false, "MEDIUM");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject thinking = root.getAsJsonObject("request")
                .getAsJsonObject("generationConfig")
                .getAsJsonObject("thinkingConfig");
        assertEquals("medium", thinking.get("thinkingLevel").getAsString());
    }

    @Test
    public void testBuildRequestBodyMultiTurnRoles() {
        List<AiChatMessage> messages = Arrays.asList(
                AiChatMessage.user("Hi"),
                AiChatMessage.model("Hello! How can I help?"),
                new AiChatMessage("assistant", "Additional context"),
                new AiChatMessage(null, "Another question")
        );

        String json = AntigravityApiClient.buildRequestBodyWithContents("gemini-3-flash", AntigravityApiClient.convertMessagesToContents(messages), false, null);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray contents = root.getAsJsonObject("request").getAsJsonArray("contents");

        assertEquals(4, contents.size());
        assertEquals("user", contents.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("model", contents.get(1).getAsJsonObject().get("role").getAsString());
        // 'assistant' should normalize to 'model' for Gemini protocol
        assertEquals("model", contents.get(2).getAsJsonObject().get("role").getAsString());
        // null role should default to 'user'
        assertEquals("user", contents.get(3).getAsJsonObject().get("role").getAsString());
    }

    @Test
    public void testParseSseLineCloudcodeResponseChunk() {
        String sseLine = "data: {\"response\": {\"candidates\": [{\"content\": {\"role\": \"model\",\"parts\": [{\"text\": \"Hello \"}]}}],\"usageMetadata\": {\"promptTokenCount\": 12,\"candidatesTokenCount\": 2,\"totalTokenCount\": 14}}}";

        AntigravityApiClient.SseChunkResult chunk = AntigravityApiClient.parseSseLine(sseLine);
        assertNotNull(chunk);
        assertEquals("Hello ", chunk.textDelta);
        assertEquals(12, chunk.promptTokens);
        assertEquals(2, chunk.candidateTokens);
    }

    @Test
    public void testParseSseLineDirectGeminiChunk() {
        String sseLine = "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"world!\"}]}}],\"usageMetadata\": {\"promptTokens\": 10,\"candidatesTokens\": 5}}";

        AntigravityApiClient.SseChunkResult chunk = AntigravityApiClient.parseSseLine(sseLine);
        assertNotNull(chunk);
        assertEquals("world!", chunk.textDelta);
        assertEquals(10, chunk.promptTokens);
        assertEquals(5, chunk.candidateTokens);
    }

    @Test
    public void testParseSseLineGroundingMetadata() {
        String sseLine = "data: {\"response\": {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Found info.\"}]},\"groundingMetadata\": {\"webSearchQueries\": [\"Coldplay fix you story\", \"Chris Martin 2005\"]}}],\"usageMetadata\": {\"promptTokenCount\": 20,\"candidatesTokenCount\": 10}}}";

        AntigravityApiClient.SseChunkResult chunk = AntigravityApiClient.parseSseLine(sseLine);
        assertNotNull(chunk);
        assertEquals("Found info.", chunk.textDelta);
        assertEquals(2, chunk.webSearchQueries.size());
        assertTrue(chunk.webSearchQueries.contains("Coldplay fix you story"));
        assertTrue(chunk.webSearchQueries.contains("Chris Martin 2005"));
    }

    @Test
    public void testParseSseLineEmptyOrMalformed() {
        assertNull(AntigravityApiClient.parseSseLine(null));
        assertNull(AntigravityApiClient.parseSseLine(""));
        assertNull(AntigravityApiClient.parseSseLine("   "));
        assertNull(AntigravityApiClient.parseSseLine(": keepalive ping"));
        assertNull(AntigravityApiClient.parseSseLine("data: [DONE]"));
        assertNull(AntigravityApiClient.parseSseLine("data: not-json"));
        assertNull(AntigravityApiClient.parseSseLine("data: {}")); // empty json
    }

    @Test
    public void testGenerateContentStreamSuccess() throws InterruptedException {
        fakeAuthManager.mockToken = "ya29.test-access-token";
        mockInterceptor.responseSseStream =
                "data: {\"response\": {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"The song \"}]}}],\"usageMetadata\": {\"promptTokenCount\": 15,\"candidatesTokenCount\": 2}}}\n\n" +
                "data: {\"response\": {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"is about hope.\"}]}}],\"groundingMetadata\": {\"webSearchQueries\": [\"Fix You meaning\"]},\"usageMetadata\": {\"promptTokenCount\": 15,\"candidatesTokenCount\": 6}}}\n\n";

        final CountDownLatch latch = new CountDownLatch(1);
        final List<String> deltas = new ArrayList<>();
        final AtomicReference<String> fullTextRef = new AtomicReference<>();
        final AtomicReference<List<String>> sourcesRef = new AtomicReference<>();
        final AtomicInteger promptTokensRef = new AtomicInteger();
        final AtomicInteger candidateTokensRef = new AtomicInteger();
        final AtomicReference<Exception> errorRef = new AtomicReference<>();

        List<AiChatMessage> messages = Collections.singletonList(AiChatMessage.user("Meaning of Fix You"));
        apiClient.generateContentStream("gemini-3-flash", messages, true, "low", new AntigravityApiClient.StreamListener() {
            @Override
            public void onDelta(String textDelta) {
                deltas.add(textDelta);
            }

            @Override
            public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                fullTextRef.set(fullText);
                sourcesRef.set(webSources);
                promptTokensRef.set(promptTokens);
                candidateTokensRef.set(candidateTokens);
                latch.countDown();
            }

            @Override
            public void onError(Exception error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNull(errorRef.get());
        assertEquals(2, deltas.size());
        assertEquals("The song ", deltas.get(0));
        assertEquals("is about hope.", deltas.get(1));
        assertEquals("The song is about hope.", fullTextRef.get());
        assertNotNull(sourcesRef.get());
        assertTrue(sourcesRef.get().contains("Fix You meaning"));
        assertEquals(15, promptTokensRef.get());
        assertEquals(6, candidateTokensRef.get());

        // Verify request headers
        assertEquals(AntigravityApiClient.ENDPOINT_STREAM, mockInterceptor.lastRequestUrl);
        assertEquals("Bearer ya29.test-access-token", mockInterceptor.lastAuthorizationHeader);
        assertEquals(AntigravityApiClient.USER_AGENT, mockInterceptor.lastUserAgentHeader);
        assertEquals("text/event-stream", mockInterceptor.lastAcceptHeader);
    }

    @Test
    public void testGenerateContentStreamAuthError() throws InterruptedException {
        fakeAuthManager.mockToken = null;
        fakeAuthManager.mockError = "OAuth expired";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Exception> errorRef = new AtomicReference<>();

        List<AiChatMessage> messages = Collections.singletonList(AiChatMessage.user("Test"));
        apiClient.generateContentStream("gemini-3-flash", messages, false, null, new AntigravityApiClient.StreamListener() {
            @Override
            public void onDelta(String textDelta) {}

            @Override
            public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                latch.countDown();
            }

            @Override
            public void onError(Exception error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNotNull(errorRef.get());
        assertTrue(errorRef.get().getMessage().contains("OAuth expired"));
        assertEquals(0, mockInterceptor.requestCount);
    }

    @Test
    public void testGenerateContentStreamHttpError() throws InterruptedException {
        fakeAuthManager.mockToken = "ya29.valid-token";
        mockInterceptor.responseCode = 503;
        mockInterceptor.responseErrorBody = "{\"error\": {\"code\": 503, \"message\": \"Model capacity exhausted\", \"status\": \"UNAVAILABLE\"}}";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Exception> errorRef = new AtomicReference<>();

        List<AiChatMessage> messages = Collections.singletonList(AiChatMessage.user("Test"));
        apiClient.generateContentStream("gemini-2.5-flash", messages, false, null, new AntigravityApiClient.StreamListener() {
            @Override
            public void onDelta(String textDelta) {}

            @Override
            public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                latch.countDown();
            }

            @Override
            public void onError(Exception error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNotNull(errorRef.get());
        assertTrue(errorRef.get().getMessage().contains("Model capacity exhausted") ||
                errorRef.get().getMessage().contains("503"));
    }

    @Test
    public void testStreamListenerCallbackExceptionDoesNotTriggerOnError() throws InterruptedException {
        fakeAuthManager.mockToken = "ya29.valid-token";
        mockInterceptor.responseSseStream =
                "data: {\"response\": {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Response\"}]}}]}}\n\n";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean onErrorTriggered = new AtomicBoolean(false);

        List<AiChatMessage> messages = Collections.singletonList(AiChatMessage.user("Test"));
        apiClient.generateContentStream("gemini-3-flash", messages, false, null, new AntigravityApiClient.StreamListener() {
            @Override
            public void onDelta(String textDelta) {}

            @Override
            public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                latch.countDown();
                throw new RuntimeException("Simulated exception in consumer onComplete callback");
            }

            @Override
            public void onError(Exception error) {
                onErrorTriggered.set(true);
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertFalse("onError must NOT be invoked if caller onComplete threw an exception", onErrorTriggered.get());
    }

    @Test
    public void testGenerateContentBatchSuccess() throws InterruptedException {
        fakeAuthManager.mockToken = "ya29.valid-token";
        mockInterceptor.responseSseStream =
                "data: {\"response\": {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Translated line 1\\nTranslated line 2\"}]}}],\"usageMetadata\": {\"promptTokenCount\": 25,\"candidatesTokenCount\": 12}}}\n\n";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> fullTextRef = new AtomicReference<>();
        final AtomicInteger candidateTokensRef = new AtomicInteger();

        List<AiChatMessage> messages = Collections.singletonList(AiChatMessage.user("Translate"));
        apiClient.generateContent("gemini-3-flash", messages, false, null, new AntigravityApiClient.CompletionCallback() {
            @Override
            public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                fullTextRef.set(fullText);
                candidateTokensRef.set(candidateTokens);
                latch.countDown();
            }

            @Override
            public void onError(Exception error) {
                latch.countDown();
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals("Translated line 1\nTranslated line 2", fullTextRef.get());
        assertEquals(12, candidateTokensRef.get());
    }

    @Test
    public void testAiChatMessageFactoriesAndEquality() {
        AiChatMessage msg1 = AiChatMessage.user("Hello");
        assertEquals("user", msg1.role);
        assertEquals("Hello", msg1.content);

        AiChatMessage msg2 = AiChatMessage.model("Hi there");
        assertEquals("model", msg2.role);
        assertEquals("Hi there", msg2.content);

        AiChatMessage msg1Duplicate = new AiChatMessage("user", "Hello");
        assertEquals(msg1, msg1Duplicate);
        assertEquals(msg1.hashCode(), msg1Duplicate.hashCode());
        assertTrue(msg1.toString().contains("Hello"));

        AiChatMessage emptyMsg = new AiChatMessage();
        assertNull(emptyMsg.role);
        assertNull(emptyMsg.content);
    }

    @Test
    public void testGenerateContentStreamSynchronousTokenException() throws InterruptedException {
        fakeAuthManager.throwSynchronously = true;

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Exception> errorRef = new AtomicReference<>();

        List<AiChatMessage> messages = Collections.singletonList(AiChatMessage.user("Test"));
        apiClient.generateContentStream("gemini-3-flash", messages, false, null, new AntigravityApiClient.StreamListener() {
            @Override
            public void onDelta(String textDelta) {}

            @Override
            public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                latch.countDown();
            }

            @Override
            public void onError(Exception error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNotNull(errorRef.get());
        assertTrue(errorRef.get().getMessage().contains("Synchronous auth error"));
    }

    @Test
    public void testGenerateContentStreamNetworkFailure() throws InterruptedException {
        fakeAuthManager.mockToken = "ya29.valid-token";
        mockInterceptor.simulateNetworkFailure = true;

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Exception> errorRef = new AtomicReference<>();

        List<AiChatMessage> messages = Collections.singletonList(AiChatMessage.user("Test"));
        apiClient.generateContentStream("gemini-3-flash", messages, false, null, new AntigravityApiClient.StreamListener() {
            @Override
            public void onDelta(String textDelta) {}

            @Override
            public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                latch.countDown();
            }

            @Override
            public void onError(Exception error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNotNull(errorRef.get());
        assertTrue(errorRef.get().getMessage().contains("Simulated network failure"));
    }

    @Test
    public void testNullListenerAndCallbackDoNotThrow() {
        fakeAuthManager.mockToken = "ya29.valid-token";
        mockInterceptor.responseSseStream =
                "data: {\"response\": {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Ok\"}]}}]}}\n\n";

        List<AiChatMessage> messages = Collections.singletonList(AiChatMessage.user("Test"));
        try {
            apiClient.generateContentStream("gemini-3-flash", messages, false, null, null);
            apiClient.generateContent("gemini-3-flash", messages, false, null, null);
        } catch (Exception e) {
            fail("Calling with null listener/callback must not throw: " + e.getMessage());
        }
    }

    @Test
    public void testParseSseLineGroundingChunksWebTitle() {
        String sseLine = "data: {\"response\": {\"groundingMetadata\": {\"groundingChunks\": [{\"web\": {\"title\": \"wikipedia.org\", \"uri\": \"https://vertexaisearch...\"}}]}}}";

        AntigravityApiClient.SseChunkResult chunk = AntigravityApiClient.parseSseLine(sseLine);
        assertNotNull(chunk);
        assertTrue(chunk.webSearchQueries.contains("wikipedia.org"));
    }

    @Test
    public void testGenerateContentBatchAuthError() throws InterruptedException {
        fakeAuthManager.mockToken = null;
        fakeAuthManager.mockError = "Refresh token expired";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Exception> errorRef = new AtomicReference<>();

        List<AiChatMessage> messages = Collections.singletonList(AiChatMessage.user("Translate"));
        apiClient.generateContent("gemini-3-flash", messages, false, null, new AntigravityApiClient.CompletionCallback() {
            @Override
            public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                latch.countDown();
            }

            @Override
            public void onError(Exception error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNotNull(errorRef.get());
        assertTrue(errorRef.get().getMessage().contains("Refresh token expired"));
    }

    @Test
    public void testParseSseLineInBandError() {
        String sseLine = "data: {\"error\": {\"code\": 429, \"message\": \"Resource has been exhausted (e.g. check quota).\", \"status\": \"RESOURCE_EXHAUSTED\"}}";

        AntigravityApiClient.SseChunkResult chunk = AntigravityApiClient.parseSseLine(sseLine);
        assertNotNull(chunk);
        assertNotNull(chunk.errorMessage);
        assertTrue(chunk.errorMessage.contains("Resource has been exhausted"));
    }

    @Test
    public void testParseSseLineInBandErrorResponseWrapper() {
        String sseLine = "data: {\"response\": {\"error\": {\"code\": 503, \"message\": \"Backend service unavailable\"}}}";

        AntigravityApiClient.SseChunkResult chunk = AntigravityApiClient.parseSseLine(sseLine);
        assertNotNull(chunk);
        assertNotNull(chunk.errorMessage);
        assertTrue(chunk.errorMessage.contains("Backend service unavailable"));
    }

    @Test
    public void testParseSseLineGroundingChunksUriFallbackWhenTitleMissing() {
        String sseLine = "data: {\"response\": {\"groundingMetadata\": {\"groundingChunks\": [{\"web\": {\"uri\": \"https://example.com/source\"}}]}}}";

        AntigravityApiClient.SseChunkResult chunk = AntigravityApiClient.parseSseLine(sseLine);
        assertNotNull(chunk);
        assertTrue(chunk.webSearchQueries.contains("https://example.com/source"));
    }

    @Test
    public void testGenerateContentStreamInBandErrorDispatchesOnError() throws InterruptedException {
        fakeAuthManager.mockToken = "ya29.valid-token";
        mockInterceptor.responseSseStream =
                "data: {\"response\": {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Starting...\"}]}}]}}\n\n" +
                "data: {\"error\": {\"code\": 429, \"message\": \"Rate limit reached\"}}\n\n";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean completeTriggered = new AtomicBoolean(false);
        final AtomicReference<Exception> errorRef = new AtomicReference<>();

        List<AiChatMessage> messages = Collections.singletonList(AiChatMessage.user("Test"));
        apiClient.generateContentStream("gemini-3-flash", messages, false, null, new AntigravityApiClient.StreamListener() {
            @Override
            public void onDelta(String textDelta) {}

            @Override
            public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                completeTriggered.set(true);
                latch.countDown();
            }

            @Override
            public void onError(Exception error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNotNull(errorRef.get());
        assertTrue(errorRef.get().getMessage().contains("Rate limit reached"));
        assertFalse("onComplete must NOT be called when an in-band error occurs", completeTriggered.get());
    }

    @Test
    public void testCancellableHandleCancelBeforeToken() throws InterruptedException {
        fakeAuthManager.mockToken = "ya29.valid-token";
        fakeAuthManager.holdToken = true;

        final AtomicBoolean deltaTriggered = new AtomicBoolean(false);
        final AtomicBoolean completeTriggered = new AtomicBoolean(false);
        final AtomicBoolean errorTriggered = new AtomicBoolean(false);

        List<AiChatMessage> messages = Collections.singletonList(AiChatMessage.user("Test"));
        AntigravityApiClient.Cancellable handle = apiClient.generateContentStream("gemini-3-flash", messages, false, null, new AntigravityApiClient.StreamListener() {
            @Override
            public void onDelta(String textDelta) {
                deltaTriggered.set(true);
            }

            @Override
            public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                completeTriggered.set(true);
            }

            @Override
            public void onError(Exception error) {
                errorTriggered.set(true);
            }
        });

        assertFalse(handle.isCancelled());
        handle.cancel();
        assertTrue(handle.isCancelled());
        fakeAuthManager.releaseToken(); // the token arrives after the cancel

        Thread.sleep(100);
        assertEquals("a cancelled request is never sent", 0, mockInterceptor.requestCount);
        assertFalse("onComplete must NOT be called when cancelled", completeTriggered.get());
        assertFalse("onError must NOT be called when cancelled", errorTriggered.get());
    }

    @Test
    public void testCancellableHandleCancelDuringStreaming() throws InterruptedException {
        fakeAuthManager.mockToken = "ya29.valid-token";
        mockInterceptor.responseSseStream =
                "data: {\"response\": {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Chunk 1\"}]}}]}}\n\n" +
                "data: {\"response\": {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Chunk 2\"}]}}]}}\n\n";

        final CountDownLatch deltaLatch = new CountDownLatch(1);
        final AtomicBoolean completeTriggered = new AtomicBoolean(false);
        final AtomicBoolean errorTriggered = new AtomicBoolean(false);
        final AtomicReference<AntigravityApiClient.Cancellable> handleRef = new AtomicReference<>();

        List<AiChatMessage> messages = Collections.singletonList(AiChatMessage.user("Test"));
        AntigravityApiClient.Cancellable handle = apiClient.generateContentStream("gemini-3-flash", messages, false, null, new AntigravityApiClient.StreamListener() {
            @Override
            public void onDelta(String textDelta) {
                deltaLatch.countDown();
                if (handleRef.get() != null) {
                    handleRef.get().cancel();
                }
            }

            @Override
            public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                completeTriggered.set(true);
            }

            @Override
            public void onError(Exception error) {
                errorTriggered.set(true);
            }
        });
        handleRef.set(handle);

        assertTrue(deltaLatch.await(3, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertTrue(handle.isCancelled());
        assertFalse("onComplete must NOT be called after cancellation", completeTriggered.get());
        assertFalse("onError must NOT be called after cancellation", errorTriggered.get());
    }

    // --- Test Helpers ---

    static class FakeAuthManager extends AntigravityAuthManager {
        volatile String mockToken;
        volatile String mockError;
        volatile boolean throwSynchronously = false;
        /** When set, the token is held until {@link #releaseToken()} (to cancel "before the token"). */
        volatile boolean holdToken = false;
        volatile TokenCallback heldCallback;

        FakeAuthManager() {
            super((android.content.SharedPreferences) null, null);
        }

        @Override
        public void getValidAccessToken(TokenCallback callback) {
            if (throwSynchronously) {
                throw new IllegalStateException("Synchronous auth error");
            }
            if (callback == null) return;
            if (holdToken) {
                heldCallback = callback;
                return;
            }
            if (mockToken != null) {
                callback.onTokenReady(mockToken);
            } else {
                callback.onError(mockError != null ? mockError : "Not authenticated");
            }
        }

        void releaseToken() {
            holdToken = false;
            if (heldCallback != null) getValidAccessToken(heldCallback);
        }
    }

    static class MockStreamInterceptor implements Interceptor {
        volatile int requestCount = 0;
        volatile int responseCode = 200;
        volatile boolean simulateNetworkFailure = false;
        volatile String responseSseStream;
        volatile String responseErrorBody;

        volatile String lastRequestUrl;
        volatile String lastAuthorizationHeader;
        volatile String lastUserAgentHeader;
        volatile String lastAcceptHeader;
        volatile String lastRequestBody;

        @Override
        public Response intercept(Chain chain) throws IOException {
            requestCount++;
            Request request = chain.request();
            lastRequestUrl = request.url().toString();
            lastAuthorizationHeader = request.header("Authorization");
            lastUserAgentHeader = request.header("User-Agent");
            lastAcceptHeader = request.header("Accept");

            if (simulateNetworkFailure) {
                throw new IOException("Simulated network failure");
            }

            if (request.body() != null) {
                Buffer buffer = new Buffer();
                request.body().writeTo(buffer);
                lastRequestBody = buffer.readUtf8();
            } else {
                lastRequestBody = null;
            }

            if (responseCode >= 400) {
                String errorBody = responseErrorBody != null ? responseErrorBody : "{\"error\": \"HTTP " + responseCode + "\"}";
                return new Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(responseCode)
                        .message("Error " + responseCode)
                        .body(ResponseBody.create(MediaType.parse("application/json; charset=utf-8"), errorBody))
                        .build();
            }

            String sse = responseSseStream != null ? responseSseStream : "data: {}\n\n";
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(MediaType.parse("text/event-stream; charset=utf-8"), sse))
                    .build();
        }
    }
}
