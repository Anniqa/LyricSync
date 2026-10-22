package com.lyricsync.app.ai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.lyricsync.app.lyrics.model.SyllableLyricsParser;
import com.lyricsync.app.ai.auth.AntigravityAuthManager;
import com.lyricsync.app.ai.model.AiChatMessage;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor.SourceItem;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor.ToolCallback;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor.ToolResult;

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

public class AntigravityApiClientToolLoopTest {

    private FakeAuthManager fakeAuthManager;
    private MultiTurnMockInterceptor mockInterceptor;
    private OkHttpClient mockHttpClient;
    private FakeToolExecutor fakeToolExecutor;
    private AntigravityApiClient apiClient;

    @Before
    public void setUp() {
        fakeAuthManager = new FakeAuthManager();
        fakeAuthManager.mockToken = "ya29.test-token";
        mockInterceptor = new MultiTurnMockInterceptor();
        mockHttpClient = new OkHttpClient.Builder()
                .addInterceptor(mockInterceptor)
                .build();
        fakeToolExecutor = new FakeToolExecutor();
        apiClient = new AntigravityApiClient(fakeAuthManager, mockHttpClient, fakeToolExecutor);
    }

    // -------------------------------------------------------------
    // Test 1: buildToolDeclarations structure (Section 3.1)
    // -------------------------------------------------------------
    @Test
    public void testBuildToolDeclarationsStructure() {
        JsonArray declarations = AntigravityApiClient.buildToolDeclarations();
        assertNotNull("Tool declarations array should not be null", declarations);
        assertEquals("Must declare exactly 12 tools with web search", 12, declarations.size());

        JsonArray declarationsNoWeb = AntigravityApiClient.buildToolDeclarations(false);
        assertEquals("Must declare 10 lyric tools without web search", 10, declarationsNoWeb.size());

        // 1. web_search
        JsonObject searchTool = declarations.get(0).getAsJsonObject();
        assertEquals("web_search", searchTool.get("name").getAsString());
        assertTrue("Description must be meaningful", searchTool.get("description").getAsString().contains("Google"));

        JsonObject searchParams = searchTool.getAsJsonObject("parameters");
        assertEquals("OBJECT", searchParams.get("type").getAsString());
        JsonObject searchProps = searchParams.getAsJsonObject("properties");
        assertTrue(searchProps.has("query"));
        assertEquals("STRING", searchProps.getAsJsonObject("query").get("type").getAsString());
        assertTrue(searchProps.has("num_search_results"));
        assertEquals("INTEGER", searchProps.getAsJsonObject("num_search_results").get("type").getAsString());

        JsonArray searchRequired = searchParams.getAsJsonArray("required");
        assertEquals(1, searchRequired.size());
        assertEquals("query", searchRequired.get(0).getAsString());

        // 2. web_fetch
        JsonObject fetchTool = declarations.get(1).getAsJsonObject();
        assertEquals("web_fetch", fetchTool.get("name").getAsString());
        assertTrue("Description must be meaningful", fetchTool.get("description").getAsString().contains("URL")
                || fetchTool.get("description").getAsString().contains("artikel"));

        JsonObject fetchParams = fetchTool.getAsJsonObject("parameters");
        assertEquals("OBJECT", fetchParams.get("type").getAsString());
        JsonObject fetchProps = fetchParams.getAsJsonObject("properties");
        assertTrue(fetchProps.has("url"));
        assertEquals("STRING", fetchProps.getAsJsonObject("url").get("type").getAsString());

        JsonArray fetchRequired = fetchParams.getAsJsonArray("required");
        assertEquals(1, fetchRequired.size());
        assertEquals("url", fetchRequired.get(0).getAsString());
        // Check the 3 full control tools
        boolean foundControlPlayback = false;
        boolean foundAdjustAppearance = false;
        boolean foundManageLines = false;
        for (JsonElement el : declarations) {
            JsonObject obj = el.getAsJsonObject();
            String name = obj.get("name").getAsString();
            if ("control_playback".equals(name)) {
                foundControlPlayback = true;
                JsonObject p = obj.getAsJsonObject("parameters");
                assertTrue(p.getAsJsonObject("properties").has("action"));
            } else if ("adjust_overlay_appearance".equals(name)) {
                foundAdjustAppearance = true;
                JsonObject p = obj.getAsJsonObject("parameters");
                assertTrue(p.getAsJsonObject("properties").has("font_scale"));
            } else if ("manage_lyric_lines".equals(name)) {
                foundManageLines = true;
                JsonObject p = obj.getAsJsonObject("parameters");
                assertTrue(p.getAsJsonObject("properties").has("action"));
            }
        }
        assertTrue("control_playback must be declared", foundControlPlayback);
        // 12. get_current_playing_track
        JsonObject currentTrackTool = declarations.get(11).getAsJsonObject();
        assertEquals("get_current_playing_track", currentTrackTool.get("name").getAsString());
    }

    @Test
    public void testBuildRequestBodyContainsFunctionDeclarations() {
        List<AiChatMessage> messages = Collections.singletonList(AiChatMessage.user("Tell me about Fix You"));
        String json = AntigravityApiClient.buildRequestBody("gemini-2.5-flash", messages, true, null);

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject req = root.getAsJsonObject("request");
        assertTrue(req.has("tools"));

        JsonArray tools = req.getAsJsonArray("tools");
        assertTrue(tools.size() >= 1);
        JsonObject tool0 = tools.get(0).getAsJsonObject();
        assertTrue("tool object must have functionDeclarations", tool0.has("functionDeclarations"));
        assertFalse("tool object must NOT have built-in googleSearch", tool0.has("googleSearch"));

        JsonArray decls = tool0.getAsJsonArray("functionDeclarations");
        assertEquals(12, decls.size());
        assertEquals("web_search", decls.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("web_fetch", decls.get(1).getAsJsonObject().get("name").getAsString());
    }

    // -------------------------------------------------------------
    // Test 2: parseSseLine with functionCall
    // -------------------------------------------------------------
    @Test
    public void testParseSseLineFunctionCallWebSearch() {
        String sseLine = "data: {\"response\": {\"candidates\": [{\"content\": {\"role\": \"model\",\"parts\": [{\"functionCall\": {\"name\": \"web_search\",\"args\": {\"query\": \"Bohemian Rhapsody story\",\"num_search_results\": 3}}}]}}]}}";

        AntigravityApiClient.SseChunkResult chunk = AntigravityApiClient.parseSseLine(sseLine);
        assertNotNull(chunk);
        assertNotNull(chunk.functionCall);
        assertEquals("web_search", chunk.functionCall.getName());
        assertNotNull(chunk.functionCall.getArgs());
        assertEquals("Bohemian Rhapsody story", chunk.functionCall.getArgs().get("query").getAsString());
        assertEquals(3, chunk.functionCall.getArgs().get("num_search_results").getAsInt());
        assertNull(chunk.textDelta);
    }

    @Test
    public void testParseSseLineFunctionCallWebFetch() {
        String sseLine = "data: {\"candidates\": [{\"content\": {\"parts\": [{\"functionCall\": {\"name\": \"web_fetch\",\"args\": {\"url\": \"https://en.wikipedia.org/wiki/Fix_You\"}}}]}}]}";

        AntigravityApiClient.SseChunkResult chunk = AntigravityApiClient.parseSseLine(sseLine);
        assertNotNull(chunk);
        assertNotNull(chunk.functionCall);
        assertEquals("web_fetch", chunk.functionCall.getName());
        assertEquals("https://en.wikipedia.org/wiki/Fix_You", chunk.functionCall.getArgs().get("url").getAsString());
    }

    @Test
    public void testParseSseLineBothTextAndFunctionCall() {
        String sseLine = "data: {\"response\": {\"candidates\": [{\"content\": {\"role\": \"model\",\"parts\": [{\"text\": \"Let me look that up for you.\"},{\"functionCall\": {\"name\": \"web_search\",\"args\": {\"query\": \"Coldplay Fix You\"}}}]}}]}}";

        AntigravityApiClient.SseChunkResult chunk = AntigravityApiClient.parseSseLine(sseLine);
        assertNotNull(chunk);
        assertEquals("Let me look that up for you.", chunk.textDelta);
        assertNotNull(chunk.functionCall);
        assertEquals("web_search", chunk.functionCall.getName());
    }

    // -------------------------------------------------------------
    // Test 3: Multi-turn flow: functionCall -> Tool Execution -> functionResponse -> Text
    // -------------------------------------------------------------
    @Test
    public void testMultiTurnFlowWebSearchSuccess() throws InterruptedException {
        // Turn 1: model returns functionCall with thoughtSignature and id
        String turn1Response = "data: {\"response\": {\"candidates\": [{\"content\": {\"role\": \"model\",\"parts\": [{\"thoughtSignature\": \"sig-coldplay-123\",\"functionCall\": {\"name\": \"web_search\",\"id\": \"call_coldplay_456\",\"args\": {\"query\": \"Coldplay Fix You origin\"}}}]}}]}}\n\n";

        // Turn 2: model receives functionResponse and returns final completion
        String turn2Response = "data: {\"response\": {\"candidates\": [{\"content\": {\"role\": \"model\",\"parts\": [{\"text\": \"Fix You was written by Chris Martin for Gwyneth Paltrow.\"}]}}],\"usageMetadata\": {\"promptTokenCount\": 50,\"candidatesTokenCount\": 18}}}\n\n";

        mockInterceptor.enqueueSseResponse(turn1Response);
        mockInterceptor.enqueueSseResponse(turn2Response);

        fakeToolExecutor.searchResultToReturn = new ToolResult(
                false,
                "{\"status\":\"success\",\"answer\":\"Fix you was written for Gwyneth Paltrow\"}",
                Collections.singletonList(new SourceItem("Coldplay Wikipedia", "https://en.wikipedia.org/wiki/Fix_You"))
        );

        final CountDownLatch latch = new CountDownLatch(1);
        final List<String> toolEvents = new ArrayList<>();
        final List<String> chunks = new ArrayList<>();
        final AtomicReference<String> fullResponseRef = new AtomicReference<>();
        final AtomicReference<List<String>> sourcesRef = new AtomicReference<>();
        final AtomicReference<Exception> errorRef = new AtomicReference<>();

        List<AiChatMessage> history = new ArrayList<>();
        apiClient.generateContentStream("gemini-2.5-flash", history, "What inspired Fix You?", true, new AntigravityApiClient.StreamListener() {
            @Override
            public void onChunk(String text) {
                chunks.add(text);
            }

            @Override
            public void onToolExecuting(String toolName, String queryOrUrl) {
                toolEvents.add("executing:" + toolName + ":" + queryOrUrl);
            }

            @Override
            public void onToolCompleted(String toolName, int resultsCount) {
                toolEvents.add("completed:" + toolName + ":" + resultsCount);
            }

            @Override
            public void onComplete(String fullResponse, List<String> webSources) {
                fullResponseRef.set(fullResponse);
                sourcesRef.set(webSources);
                latch.countDown();
            }

            @Override
            public void onError(Exception e) {
                errorRef.set(e);
                latch.countDown();
            }
        });

        assertTrue("Multi-turn loop should complete within 5 seconds", latch.await(5, TimeUnit.SECONDS));
        assertNull("Should not have error: " + errorRef.get(), errorRef.get());

        // Verify tool events
        assertEquals(2, toolEvents.size());
        assertEquals("executing:web_search:Coldplay Fix You origin", toolEvents.get(0));
        assertEquals("completed:web_search:1", toolEvents.get(1));

        // Verify executor was called
        assertEquals(1, fakeToolExecutor.searchCallsCount);
        assertEquals("Coldplay Fix You origin", fakeToolExecutor.lastSearchQuery);

        // Verify HTTP turns
        assertEquals(2, mockInterceptor.recordedRequests.size());

        // Verify Turn 2 request payload contains functionCall and functionResponse turns
        String turn2RequestBody = mockInterceptor.recordedRequestBodies.get(1);
        JsonObject turn2Json = JsonParser.parseString(turn2RequestBody).getAsJsonObject();
        JsonArray contents = turn2Json.getAsJsonObject("request").getAsJsonArray("contents");

        assertTrue("Turn 2 must have at least 3 contents: user prompt, model functionCall, function response", contents.size() >= 3);

        // Check model functionCall turn
        JsonObject modelTurn = contents.get(1).getAsJsonObject();
        assertEquals("model", modelTurn.get("role").getAsString());
        JsonObject fcPart = modelTurn.getAsJsonArray("parts").get(0).getAsJsonObject();
        assertTrue(fcPart.has("functionCall"));
        assertEquals("web_search", fcPart.getAsJsonObject("functionCall").get("name").getAsString());
        assertEquals("call_coldplay_456", fcPart.getAsJsonObject("functionCall").get("id").getAsString());
        assertTrue(fcPart.has("thoughtSignature"));
        assertEquals("sig-coldplay-123", fcPart.get("thoughtSignature").getAsString());

        // Check functionResponse turn (Cloudcode PA requires role: "user")
        JsonObject fnTurn = contents.get(2).getAsJsonObject();
        assertEquals("user", fnTurn.get("role").getAsString());
        JsonObject frPart = fnTurn.getAsJsonArray("parts").get(0).getAsJsonObject();
        assertTrue(frPart.has("functionResponse"));
        JsonObject fr = frPart.getAsJsonObject("functionResponse");
        assertEquals("web_search", fr.get("name").getAsString());
        assertEquals("call_coldplay_456", fr.get("id").getAsString());
        assertTrue("function response must contain response object", fr.has("response"));

        // Verify completion outputs
        assertEquals("Fix You was written by Chris Martin for Gwyneth Paltrow.", fullResponseRef.get());
        assertNotNull(sourcesRef.get());
        assertTrue(sourcesRef.get().contains("https://en.wikipedia.org/wiki/Fix_You"));
    }

    // -------------------------------------------------------------
    // Test 4: Multi-turn flow with web_fetch
    // -------------------------------------------------------------
    @Test
    public void testMultiTurnFlowWebFetchSuccess() throws InterruptedException {
        // Turn 1: model returns functionCall for web_fetch
        String turn1Response = "data: {\"response\": {\"candidates\": [{\"content\": {\"role\": \"model\",\"parts\": [{\"functionCall\": {\"name\": \"web_fetch\",\"args\": {\"url\": \"https://rollingstone.com/article/123\"}}}]}}]}}\n\n";

        // Turn 2: model returns answer
        String turn2Response = "data: {\"response\": {\"candidates\": [{\"content\": {\"role\": \"model\",\"parts\": [{\"text\": \"According to the interview, the song was completed in 2005.\"}]}}]}}\n\n";

        mockInterceptor.enqueueSseResponse(turn1Response);
        mockInterceptor.enqueueSseResponse(turn2Response);

        fakeToolExecutor.fetchResultToReturn = new ToolResult(
                false,
                "{\"status\":\"success\",\"url\":\"https://rollingstone.com/article/123\",\"title\":\"Rolling Stone Interview\",\"content\":\"The song was completed in 2005.\"}",
                Collections.singletonList(new SourceItem("Rolling Stone Interview", "https://rollingstone.com/article/123"))
        );

        final CountDownLatch latch = new CountDownLatch(1);
        final List<String> toolEvents = new ArrayList<>();
        final AtomicReference<String> fullResponseRef = new AtomicReference<>();
        final AtomicReference<List<String>> sourcesRef = new AtomicReference<>();

        apiClient.generateContentStream("gemini-2.5-flash", null, "Read interview", true, new AntigravityApiClient.StreamListener() {
            @Override
            public void onToolExecuting(String toolName, String queryOrUrl) {
                toolEvents.add("exec:" + toolName + ":" + queryOrUrl);
            }

            @Override
            public void onToolCompleted(String toolName, int resultsCount) {
                toolEvents.add("done:" + toolName + ":" + resultsCount);
            }

            @Override
            public void onComplete(String fullResponse, List<String> webSources) {
                fullResponseRef.set(fullResponse);
                sourcesRef.set(webSources);
                latch.countDown();
            }

            @Override
            public void onError(Exception e) {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, fakeToolExecutor.fetchCallsCount);
        assertEquals("https://rollingstone.com/article/123", fakeToolExecutor.lastFetchUrl);
        assertEquals("exec:web_fetch:https://rollingstone.com/article/123", toolEvents.get(0));
        assertEquals("done:web_fetch:1", toolEvents.get(1));
        assertEquals("According to the interview, the song was completed in 2005.", fullResponseRef.get());
        assertTrue(sourcesRef.get().contains("https://rollingstone.com/article/123"));
    }

    // -------------------------------------------------------------
    // Test 5: Hard loop guard (max 3 tool iterations)
    // -------------------------------------------------------------
    @Test
    public void testMaxThreeToolIterationsGuard() throws InterruptedException {
        // Enqueue 5 turns of functionCall to simulate runaway model loop
        String loopChunk = "data: {\"response\": {\"candidates\": [{\"content\": {\"role\": \"model\",\"parts\": [{\"functionCall\": {\"name\": \"web_search\",\"args\": {\"query\": \"loop test\"}}}]}}]}}\n\n";
        mockInterceptor.enqueueSseResponse(loopChunk); // iteration 1
        mockInterceptor.enqueueSseResponse(loopChunk); // iteration 2
        mockInterceptor.enqueueSseResponse(loopChunk); // iteration 3
        mockInterceptor.enqueueSseResponse(loopChunk); // iteration 4 (should NOT execute tool)

        fakeToolExecutor.searchResultToReturn = new ToolResult(false, "{\"status\":\"ok\"}", Collections.<SourceItem>emptyList());

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean completeCalled = new AtomicBoolean(false);

        apiClient.generateContentStream("gemini-2.5-flash", null, "Infinite loop query", true, new AntigravityApiClient.StreamListener() {
            @Override
            public void onComplete(String fullResponse, List<String> webSources) {
                completeCalled.set(true);
                latch.countDown();
            }

            @Override
            public void onError(Exception e) {
                latch.countDown();
            }
        });

        assertTrue("Loop guard must terminate within 5 seconds", latch.await(5, TimeUnit.SECONDS));
        assertTrue("onComplete must be called when max iterations reached", completeCalled.get());
        assertEquals("Tool must be executed exactly 3 times max", 3, fakeToolExecutor.searchCallsCount);
    }

    // -------------------------------------------------------------
    // Test 6: Thread-safe cancellation during tool execution
    // -------------------------------------------------------------
    @Test
    public void testCancellationDuringToolExecution() throws InterruptedException {
        // Turn 1: model returns functionCall
        String turn1Response = "data: {\"response\": {\"candidates\": [{\"content\": {\"role\": \"model\",\"parts\": [{\"functionCall\": {\"name\": \"web_search\",\"args\": {\"query\": \"cancel test\"}}}]}}]}}\n\n";
        mockInterceptor.enqueueSseResponse(turn1Response);

        // Tool executor will pause and wait for cancellation
        fakeToolExecutor.holdToolExecution = true;

        final AtomicBoolean completeTriggered = new AtomicBoolean(false);
        final AtomicBoolean errorTriggered = new AtomicBoolean(false);
        final AtomicReference<AntigravityApiClient.Cancellable> handleRef = new AtomicReference<>();

        AntigravityApiClient.Cancellable handle = apiClient.generateContentStream(
                "gemini-2.5-flash", null, "Test cancel", true, new AntigravityApiClient.StreamListener() {
            @Override
            public void onComplete(String fullResponse, List<String> webSources) {
                completeTriggered.set(true);
            }

            @Override
            public void onError(Exception e) {
                errorTriggered.set(true);
            }
        });
        handleRef.set(handle);

        // Wait for tool execution to be initiated
        long start = System.currentTimeMillis();
        while (fakeToolExecutor.searchCallsCount == 0 && System.currentTimeMillis() - start < 3000) {
            Thread.sleep(20);
        }
        assertEquals(1, fakeToolExecutor.searchCallsCount);

        // Now cancel handle during tool execution
        assertFalse(handle.isCancelled());
        handle.cancel();
        assertTrue(handle.isCancelled());
        assertTrue("In-flight tool cancellable must be cancelled", fakeToolExecutor.lastToolCancellable.isCancelled());

        // Resume tool callback if any
        fakeToolExecutor.releaseToolExecution();

        Thread.sleep(100);
        assertFalse("onComplete must NOT be called after cancellation", completeTriggered.get());
        assertFalse("onError must NOT be called after cancellation", errorTriggered.get());
    }

    // -------------------------------------------------------------
    // Test 7: Tool execution error gracefully forwarded to Gemini
    // -------------------------------------------------------------
    @Test
    public void testToolExecutionErrorGracefullyContinues() throws InterruptedException {
        String turn1Response = "data: {\"response\": {\"candidates\": [{\"content\": {\"role\": \"model\",\"parts\": [{\"functionCall\": {\"name\": \"web_search\",\"args\": {\"query\": \"error test\"}}}]}}]}}\n\n";
        String turn2Response = "data: {\"response\": {\"candidates\": [{\"content\": {\"role\": \"model\",\"parts\": [{\"text\": \"Based on my offline knowledge...\"}]}}]}}\n\n";

        mockInterceptor.enqueueSseResponse(turn1Response);
        mockInterceptor.enqueueSseResponse(turn2Response);

        fakeToolExecutor.searchResultToReturn = new ToolResult(true, "Network timeout connecting to search", null);

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> fullResponseRef = new AtomicReference<>();

        apiClient.generateContentStream("gemini-2.5-flash", null, "Query with tool error", true, new AntigravityApiClient.StreamListener() {
            @Override
            public void onComplete(String fullResponse, List<String> webSources) {
                fullResponseRef.set(fullResponse);
                latch.countDown();
            }

            @Override
            public void onError(Exception e) {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("Based on my offline knowledge...", fullResponseRef.get());
        assertEquals(2, mockInterceptor.recordedRequests.size());
    }

    // -------------------------------------------------------------
    // Test 8: Backwards compatibility (without tools, 4-arg onComplete)
    // -------------------------------------------------------------
    @Test
    public void testBackwardsCompatibilityWithoutTools() throws InterruptedException {
        String sseResponse = "data: {\"response\": {\"candidates\": [{\"content\": {\"role\": \"model\",\"parts\": [{\"text\": \"Direct response without tools\"}]}}],\"usageMetadata\": {\"promptTokenCount\": 10,\"candidatesTokenCount\": 5}}}\n\n";
        mockInterceptor.enqueueSseResponse(sseResponse);

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> fullTextRef = new AtomicReference<>();
        final AtomicInteger promptTokensRef = new AtomicInteger();
        final AtomicInteger candidateTokensRef = new AtomicInteger();

        List<AiChatMessage> messages = Collections.singletonList(AiChatMessage.user("No tools prompt"));
        apiClient.generateContentStream("gemini-2.5-flash", messages, false, null, new AntigravityApiClient.StreamListener() {
            @Override
            public void onDelta(String textDelta) {}

            @Override
            public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                fullTextRef.set(fullText);
                promptTokensRef.set(promptTokens);
                candidateTokensRef.set(candidateTokens);
                latch.countDown();
            }

            @Override
            public void onError(Exception error) {
                latch.countDown();
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals("Direct response without tools", fullTextRef.get());
        assertEquals(10, promptTokensRef.get());
        assertEquals(5, candidateTokensRef.get());
        assertEquals(0, fakeToolExecutor.searchCallsCount);
    }

    // =============================================================
    // Helper Classes
    // =============================================================

    static class FakeAuthManager extends AntigravityAuthManager {
        volatile String mockToken;
        FakeAuthManager() {
            super((android.content.SharedPreferences) null, null);
        }
        @Override
        public void getValidAccessToken(TokenCallback callback) {
            if (callback != null) {
                if (mockToken != null) callback.onTokenReady(mockToken);
                else callback.onError("Auth error");
            }
        }
    }

    static class MultiTurnMockInterceptor implements Interceptor {
        final List<String> enqueuedSseResponses = new ArrayList<>();
        final List<Request> recordedRequests = new ArrayList<>();
        final List<String> recordedRequestBodies = new ArrayList<>();
        int requestIndex = 0;

        void enqueueSseResponse(String sse) {
            enqueuedSseResponses.add(sse);
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            recordedRequests.add(request);

            if (request.body() != null) {
                Buffer buffer = new Buffer();
                request.body().writeTo(buffer);
                recordedRequestBodies.add(buffer.readUtf8());
            } else {
                recordedRequestBodies.add("");
            }

            String sse = "data: {}\n\n";
            if (requestIndex < enqueuedSseResponses.size()) {
                sse = enqueuedSseResponses.get(requestIndex);
                requestIndex++;
            }

            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(MediaType.parse("text/event-stream; charset=utf-8"), sse))
                    .build();
        }
    }

    // -------------------------------------------------------------
    // Test: Simulation of "tampilkan romanji dan translate nya Jepang buang"
    // -------------------------------------------------------------
    @Test
    public void testSimulationTransliterateLyricsAndTranslation() throws InterruptedException {
        final AtomicReference<String> executedMode = new AtomicReference<>();
        final AtomicBoolean executedShowOriginal = new AtomicBoolean(true);
        final AtomicBoolean delegateInvoked = new AtomicBoolean(false);

        apiClient.setLyricActionDelegate(new AntigravityApiClient.LyricActionDelegate() {
            @Override
            public void translateLyrics(String targetLang, ActionCallback callback) {
                callback.onSuccess("Translated to " + targetLang);
            }

            @Override
            public void transliterateLyrics(String mode, ActionCallback callback) {
                transliterateLyrics(mode, true, callback);
            }

            @Override
            public void transliterateLyrics(String mode, boolean showOriginalScript, ActionCallback callback) {
                executedMode.set(mode);
                executedShowOriginal.set(showOriginalScript);
                delegateInvoked.set(true);
                callback.onSuccess("Transliterasi (" + mode + ", aksara=" + showOriginalScript + ") berhasil diterapkan.");
            }

            @Override
            public void setSyncOffset(int offsetMs, ActionCallback callback) {
                callback.onSuccess("Sync offset: " + offsetMs);
            }

            @Override
            public void modifyLine(int lineIndex, String text, String translation, String romanji, ActionCallback callback) {
                callback.onSuccess("Modified line " + lineIndex);
            }

            @Override
            public void toggleDisplay(String feature, boolean enabled, ActionCallback callback) {
                callback.onSuccess("Toggled " + feature);
            }
        });

        // Turn 1: Model calls transliterate_lyrics with thought_signature and show_original_script=false
        String turn1Response = "data: {\"response\": {\"candidates\": [{\"content\": {\"role\": \"model\",\"parts\": [{\"thought_signature\": \"sig_crypto_gemini_3\",\"functionCall\": {\"name\": \"transliterate_lyrics\",\"id\": \"call_translit_1\",\"args\": {\"mode\": \"romaji_with_translation\",\"show_original_script\": false}}}]}}]}}\n\n";
        // Turn 2: Model returns explanation
        String turn2Response = "data: {\"response\": {\"candidates\": [{\"content\": {\"role\": \"model\",\"parts\": [{\"text\": \"Aksara Jepang telah disembunyikan dan lirik ditampilkan dalam Romaji beserta terjemahannya di layar overlay.\"}]}}],\"usageMetadata\": {\"promptTokenCount\": 45,\"candidatesTokenCount\": 25}}}\n\n";

        mockInterceptor.enqueueSseResponse(turn1Response);
        mockInterceptor.enqueueSseResponse(turn2Response);

        final CountDownLatch latch = new CountDownLatch(1);
        final List<String> toolEvents = new ArrayList<>();
        final AtomicReference<String> completedText = new AtomicReference<>();
        final AtomicReference<Exception> errorRef = new AtomicReference<>();

        // Test with webSearch=false, ensuring lyric tools still work!
        apiClient.generateContentStream("gemini-3.8-flash-high", new ArrayList<>(),
                "tampilkan romanji dan translate nya Jepang buang", false, new AntigravityApiClient.StreamListener() {
                    @Override
                    public void onToolExecuting(String toolName, String queryOrUrl) {
                        toolEvents.add("executing:" + toolName);
                    }

                    @Override
                    public void onToolCompleted(String toolName, int resultsCount) {
                        toolEvents.add("completed:" + toolName);
                    }

                    @Override
                    public void onComplete(String fullResponse, List<String> webSources) {
                        completedText.set(fullResponse);
                        latch.countDown();
                    }

                    @Override
                    public void onError(Exception e) {
                        errorRef.set(e);
                        latch.countDown();
                    }
                });

        assertTrue("Simulation should complete within 5 seconds", latch.await(5, TimeUnit.SECONDS));
        assertNull("Should not encounter error: " + errorRef.get(), errorRef.get());

        // Verify tool execution
        assertTrue("LyricActionDelegate must be invoked", delegateInvoked.get());
        assertEquals("romaji_with_translation", executedMode.get());
        assertFalse("showOriginalScript must be false for 'Jepang buang'", executedShowOriginal.get());

        // Verify event lifecycle
        assertEquals(2, toolEvents.size());
        assertEquals("executing:transliterate_lyrics", toolEvents.get(0));
        assertEquals("completed:transliterate_lyrics", toolEvents.get(1));

        // Verify completion text
        assertNotNull(completedText.get());
        assertTrue(completedText.get().contains("Romaji"));
    }
    @Test
    public void testSimulationControlPlaybackAppearanceAndManageLines() throws InterruptedException {
        final AtomicReference<String> lastPlaybackAction = new AtomicReference<>();
        final AtomicInteger lastPlaybackLine = new AtomicInteger(-1);
        final AtomicReference<Float> lastFontScale = new AtomicReference<>();
        final AtomicReference<String> lastAnimStyle = new AtomicReference<>();
        final AtomicReference<String> lastManageAction = new AtomicReference<>();
        final AtomicInteger lastManageLine = new AtomicInteger(-1);

        apiClient.setLyricActionDelegate(new AntigravityApiClient.LyricActionDelegate() {
            @Override public void translateLyrics(String targetLang, ActionCallback callback) { callback.onSuccess("OK"); }
            @Override public void transliterateLyrics(String mode, ActionCallback callback) { callback.onSuccess("OK"); }
            @Override public void setSyncOffset(int offsetMs, ActionCallback callback) { callback.onSuccess("OK"); }
            @Override public void modifyLine(int lineIndex, String text, String translation, String romanji, ActionCallback callback) { callback.onSuccess("OK"); }
            @Override public void toggleDisplay(String feature, boolean enabled, ActionCallback callback) { callback.onSuccess("OK"); }

            @Override
            public void controlPlayback(String action, int lineIndex, long positionMs, ActionCallback callback) {
                lastPlaybackAction.set(action);
                lastPlaybackLine.set(lineIndex);
                callback.onSuccess("Seek to line " + (lineIndex + 1) + " success");
            }

            @Override
            public void adjustAppearance(Float fontScale, String animStyle, Integer widthPercent, Integer heightPercent, ActionCallback callback) {
                lastFontScale.set(fontScale);
                lastAnimStyle.set(animStyle);
                callback.onSuccess("Appearance updated");
            }

            @Override
            public void manageLines(AntigravityApiClient.LyricActionDelegate.LineOp op, ActionCallback callback) {
                lastManageAction.set(op.action);
                lastManageLine.set(op.lineIndex);
                callback.onSuccess("Line managed");
            }
        });

        // Turn 1: Model calls control_playback
        String turn1Response = "data: {\"response\": {\"candidates\": [{\"content\": {\"role\": \"model\",\"parts\": [{\"thought_signature\": \"sig_ctrl_1\",\"functionCall\": {\"name\": \"control_playback\",\"id\": \"call_ctrl_1\",\"args\": {\"action\": \"seek_to_line\",\"line_index\": 4}}}]}}]}}\n\n";
        // Turn 2: Model calls adjust_overlay_appearance
        String turn2Response = "data: {\"response\": {\"candidates\": [{\"content\": {\"role\": \"model\",\"parts\": [{\"thought_signature\": \"sig_adj_1\",\"functionCall\": {\"name\": \"adjust_overlay_appearance\",\"id\": \"call_adj_1\",\"args\": {\"font_scale\": 1.3,\"anim_style\": \"glow\"}}}]}}]}}\n\n";
        // Turn 3: Model calls manage_lyric_lines
        String turn3Response = "data: {\"response\": {\"candidates\": [{\"content\": {\"role\": \"model\",\"parts\": [{\"thought_signature\": \"sig_man_1\",\"functionCall\": {\"name\": \"manage_lyric_lines\",\"id\": \"call_man_1\",\"args\": {\"action\": \"delete\",\"line_index\": 2}}}]}}]}}\n\n";
        // Turn 4: Final user text
        String turn4Response = "data: {\"response\": {\"candidates\": [{\"content\": {\"role\": \"model\",\"parts\": [{\"text\": \"Semua perintah kontrol berhasil dieksekusi.\"}]}}],\"usageMetadata\": {\"promptTokenCount\": 50,\"candidatesTokenCount\": 25}}}\n\n";

        mockInterceptor.enqueueSseResponse(turn1Response);
        mockInterceptor.enqueueSseResponse(turn2Response);
        mockInterceptor.enqueueSseResponse(turn3Response);
        mockInterceptor.enqueueSseResponse(turn4Response);

        final CountDownLatch latch = new CountDownLatch(1);
        final List<String> toolEvents = new ArrayList<>();
        final AtomicReference<String> completedText = new AtomicReference<>();

        apiClient.generateContentStream("gemini-3.8-flash-high", new ArrayList<>(),
                "lompat ke baris 5, besarin font dan hapus baris 3", false, new AntigravityApiClient.StreamListener() {
                    @Override
                    public void onToolExecuting(String toolName, String queryOrUrl) {
                        toolEvents.add("executing:" + toolName + ":" + queryOrUrl);
                    }

                    @Override
                    public void onToolCompleted(String toolName, int resultsCount) {
                        toolEvents.add("completed:" + toolName);
                    }

                    @Override
                    public void onComplete(String fullResponse, List<String> webSources) {
                        completedText.set(fullResponse);
                        latch.countDown();
                    }

                    @Override
                    public void onError(Exception error) {
                        latch.countDown();
                    }
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("seek_to_line", lastPlaybackAction.get());
        assertEquals(4, lastPlaybackLine.get());
        assertEquals(Float.valueOf(1.3f), lastFontScale.get());
        assertEquals("glow", lastAnimStyle.get());
        assertEquals("delete", lastManageAction.get());
        assertEquals(2, lastManageLine.get());
        assertEquals(6, toolEvents.size());
        assertEquals("Semua perintah kontrol berhasil dieksekusi.", completedText.get());
    }

    static class FakeToolExecutor extends AntigravityToolExecutor {
        volatile int searchCallsCount = 0;
        volatile int fetchCallsCount = 0;
        volatile String lastSearchQuery;
        volatile String lastFetchUrl;
        volatile ToolResult searchResultToReturn = new ToolResult();
        volatile ToolResult fetchResultToReturn = new ToolResult();

        volatile boolean holdToolExecution = false;
        volatile ToolCallback pendingCallback;
        volatile ToolResult pendingResult;
        volatile ToolCancellable lastToolCancellable;

        @Override
        public AntigravityApiClient.Cancellable executeWebSearch(String query, int maxResults, final ToolCallback callback) {
            searchCallsCount++;
            lastSearchQuery = query;
            final ToolCancellable cancellable = new ToolCancellable();
            lastToolCancellable = cancellable;

            if (holdToolExecution) {
                pendingCallback = callback;
                pendingResult = searchResultToReturn;
            } else {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        if (!cancellable.isCancelled() && callback != null) {
                            callback.onResult(searchResultToReturn);
                        }
                    }
                }).start();
            }
            return cancellable;
        }

        @Override
        public AntigravityApiClient.Cancellable executeWebFetch(String urlString, final ToolCallback callback) {
            fetchCallsCount++;
            lastFetchUrl = urlString;
            final ToolCancellable cancellable = new ToolCancellable();
            lastToolCancellable = cancellable;

            if (holdToolExecution) {
                pendingCallback = callback;
                pendingResult = fetchResultToReturn;
            } else {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        if (!cancellable.isCancelled() && callback != null) {
                            callback.onResult(fetchResultToReturn);
                        }
                    }
                }).start();
            }
            return cancellable;
        }

        void releaseToolExecution() {
            holdToolExecution = false;
            if (pendingCallback != null) {
                final ToolCallback cb = pendingCallback;
                final ToolResult res = pendingResult;
                pendingCallback = null;
                pendingResult = null;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        if (lastToolCancellable != null && !lastToolCancellable.isCancelled()) {
                            cb.onResult(res);
                        }
                    }
                }).start();
            }
        }
    }
}
