package com.lyricsync.app.ai.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.ai.client.AntigravityApiClient;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
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

public class AntigravityToolExecutorTest {

    private MockHttpInterceptor mockInterceptor;
    private OkHttpClient mockHttpClient;
    private AntigravityToolExecutor executor;

    @Before
    public void setUp() {
        mockInterceptor = new MockHttpInterceptor();
        mockHttpClient = new OkHttpClient.Builder()
                .addInterceptor(mockInterceptor)
                .build();
        executor = new AntigravityToolExecutor(mockHttpClient);
    }

    // --- SSRF Protection Tests ---

    @Test
    public void testIsSafePublicUrlRejectsLocalhost() {
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://localhost"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://localhost:8080"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("https://localhost/admin"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://127.0.0.1"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://127.0.0.1:3000"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://127.0.1.1"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://127.255.255.254"));
    }

    @Test
    public void testIsSafePublicUrlRejectsPrivateIpv4() {
        // 10.0.0.0/8
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://10.0.0.1"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("https://10.255.255.254/secret"));

        // 172.16.0.0/12
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://172.16.0.1"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://172.31.255.254"));

        // 192.168.0.0/16
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://192.168.0.1"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://192.168.1.1"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://192.168.254.254"));

        // 0.0.0.0
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://0.0.0.0"));
    }

    @Test
    public void testIsSafePublicUrlRejectsLinkLocalAndMetadata() {
        // AWS / GCP / Azure metadata service (169.254.169.254)
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://169.254.169.254/latest/meta-data/"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://169.254.1.1"));
    }

    @Test
    public void testIsSafePublicUrlRejectsNonHttpSchemes() {
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("file:///etc/passwd"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("ftp://example.com/data"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("content://media/external/images"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("javascript:alert(1)"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("data:text/html,<h1>test</h1>"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("gopher://example.com/"));
    }

    @Test
    public void testIsSafePublicUrlRejectsInvalidAndLocalHostnames() {
        assertFalse(AntigravityToolExecutor.isSafePublicUrl(null));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl(""));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("   "));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("not-a-url"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://router"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://internal.corp"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://metadata.google.internal"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://myhost.local"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://service.lan"));
    }

    @Test
    public void testIsSafePublicUrlRejectsLoopbackIpv6() {
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://[::1]"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://[0:0:0:0:0:0:0:1]"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://[fe80::1]"));
        assertFalse(AntigravityToolExecutor.isSafePublicUrl("http://[fc00::1]"));
    }

    @Test
    public void testIsSafePublicUrlAllowsSafePublicUrls() {
        assertTrue(AntigravityToolExecutor.isSafePublicUrl("https://en.wikipedia.org/wiki/Bohemian_Rhapsody"));
        assertTrue(AntigravityToolExecutor.isSafePublicUrl("http://example.com"));
        assertTrue(AntigravityToolExecutor.isSafePublicUrl("https://api.github.com/repos"));
        assertTrue(AntigravityToolExecutor.isSafePublicUrl("https://lyricsync.app/guide"));
    }

    // --- HTML Text Extraction Tests ---

    @Test
    public void testExtractReadableTextStripsScriptsAndStyles() {
        String html = "<html><head><style>body { color: red; }</style>"
                + "<script>alert('malicious');</script></head>"
                + "<body><h1>Song Title</h1><p>Meaning of the song goes here.</p>"
                + "<script type=\"text/javascript\">console.log('extra');</script></body></html>";

        String text = AntigravityToolExecutor.extractReadableText(html);
        assertNotNull(text);
        assertFalse(text.contains("alert"));
        assertFalse(text.contains("body { color: red; }"));
        assertFalse(text.contains("console.log"));
        assertTrue(text.contains("Song Title"));
        assertTrue(text.contains("Meaning of the song goes here."));
    }

    @Test
    public void testExtractReadableTextCollapsesWhitespace() {
        String html = "<p>   Line 1    with    spaces.   </p>\n\n\n<p>Line 2   </p>";
        String text = AntigravityToolExecutor.extractReadableText(html);
        assertNotNull(text);
        assertFalse(text.contains("    "));
        assertTrue(text.contains("Line 1 with spaces."));
        assertTrue(text.contains("Line 2"));
    }

    @Test
    public void testExtractReadableTextUnescapesHtmlEntities() {
        String html = "<p>&quot;Hello&quot; &amp; &lt;World&gt; &#39;test&#39; &nbsp; done</p>";
        String text = AntigravityToolExecutor.extractReadableText(html);
        assertEquals("\"Hello\" & <World> 'test' done", text.trim());
    }

    @Test
    public void testExtractReadableTextUnescapesSmpEmojiEntities() {
        // &#127925; = U+1F3B5 (musical note), &#x1F3B6; = U+1F3B6 (multiple musical notes)
        String html = "<p>Melody &#127925; and Harmony &#x1F3B6; in concert</p>";
        String text = AntigravityToolExecutor.extractReadableText(html);
        assertEquals("Melody \uD83C\uDFB5 and Harmony \uD83C\uDFB6 in concert", text.trim());
    }

    @Test
    public void testExtractReadableTextTruncatesAt8000Chars() {
        StringBuilder largeContent = new StringBuilder("<html><body><p>");
        for (int i = 0; i < 1200; i++) {
            largeContent.append("word").append(i).append(" ");
        }
        largeContent.append("</p></body></html>");

        String text = AntigravityToolExecutor.extractReadableText(largeContent.toString());
        assertTrue(text.length() <= 8000);
        assertEquals(8000, text.length());
    }

    @Test
    public void testExtractTitle() {
        String html = "<html><head><title>Bohemian Rhapsody &amp; Queen - Song Facts</title></head><body>Hello</body></html>";
        String title = AntigravityToolExecutor.extractTitle(html);
        assertEquals("Bohemian Rhapsody & Queen - Song Facts", title);
    }

    // --- Web Fetch Execution Tests ---

    @Test
    public void testExecuteWebFetchBlocksSsrfImmediately() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<AntigravityToolExecutor.ToolResult> resultRef = new AtomicReference<>();

        AntigravityApiClient.Cancellable handle = executor.executeWebFetch("http://127.0.0.1:8080/private", new AntigravityToolExecutor.ToolCallback() {
            @Override
            public void onResult(AntigravityToolExecutor.ToolResult result) {
                resultRef.set(result);
                latch.countDown();
            }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertNotNull(handle);
        assertEquals(0, mockInterceptor.requestCount); // Zero network calls made

        AntigravityToolExecutor.ToolResult result = resultRef.get();
        assertNotNull(result);
        assertTrue("Result must be error", result.isError);
        assertNotNull(result.content);
        assertTrue(result.content.contains("error") || result.content.contains("SSRF"));
    }

    @Test
    public void testExecuteWebFetchBlocksFileScheme() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<AntigravityToolExecutor.ToolResult> resultRef = new AtomicReference<>();

        executor.executeWebFetch("file:///etc/passwd", new AntigravityToolExecutor.ToolCallback() {
            @Override
            public void onResult(AntigravityToolExecutor.ToolResult result) {
                resultRef.set(result);
                latch.countDown();
            }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(0, mockInterceptor.requestCount);
        AntigravityToolExecutor.ToolResult result = resultRef.get();
        assertNotNull(result);
        assertTrue(result.isError);
    }

    @Test
    public void testExecuteWebFetchSuccess() throws InterruptedException {
        mockInterceptor.responseBody = "<html><head><title>Queen - Bohemian Rhapsody</title></head>"
                + "<body><p>Bohemian Rhapsody was released in 1975 by British rock band Queen.</p></body></html>";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<AntigravityToolExecutor.ToolResult> resultRef = new AtomicReference<>();

        executor.executeWebFetch("https://example.com/queen-song-story", new AntigravityToolExecutor.ToolCallback() {
            @Override
            public void onResult(AntigravityToolExecutor.ToolResult result) {
                resultRef.set(result);
                latch.countDown();
            }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, mockInterceptor.requestCount);
        assertEquals("https://example.com/queen-song-story", mockInterceptor.lastUrl);

        AntigravityToolExecutor.ToolResult result = resultRef.get();
        assertNotNull(result);
        assertFalse("Must not be error", result.isError);
        assertNotNull(result.content);

        JsonObject json = JsonParser.parseString(result.content).getAsJsonObject();
        assertEquals("success", json.get("status").getAsString());
        assertEquals("https://example.com/queen-song-story", json.get("url").getAsString());
        assertEquals("Queen - Bohemian Rhapsody", json.get("title").getAsString());
        assertTrue(json.get("content").getAsString().contains("Bohemian Rhapsody was released in 1975"));

        assertNotNull(result.sources);
        assertEquals(1, result.sources.size());
        AntigravityToolExecutor.SourceItem source = result.sources.get(0);
        assertEquals("Queen - Bohemian Rhapsody", source.title);
        assertEquals("https://example.com/queen-song-story", source.url);
    }

    @Test
    public void testExecuteWebFetchTruncatesAt8000Chars() throws InterruptedException {
        StringBuilder sb = new StringBuilder("<html><head><title>Large Page</title></head><body><p>");
        for (int i = 0; i < 2000; i++) {
            sb.append("longparagraphcontent").append(i).append(" ");
        }
        sb.append("</p></body></html>");

        mockInterceptor.responseBody = sb.toString();

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<AntigravityToolExecutor.ToolResult> resultRef = new AtomicReference<>();

        executor.executeWebFetch("https://example.com/large-article", new AntigravityToolExecutor.ToolCallback() {
            @Override
            public void onResult(AntigravityToolExecutor.ToolResult result) {
                resultRef.set(result);
                latch.countDown();
            }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        AntigravityToolExecutor.ToolResult result = resultRef.get();
        assertNotNull(result);
        assertFalse(result.isError);

        JsonObject json = JsonParser.parseString(result.content).getAsJsonObject();
        String content = json.get("content").getAsString();
        assertTrue("Content length must be <= 8000", content.length() <= 8000);
        assertEquals(8000, content.length());
    }

    @Test
    public void testExecuteWebFetchHttpError() throws InterruptedException {
        mockInterceptor.responseCode = 404;

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<AntigravityToolExecutor.ToolResult> resultRef = new AtomicReference<>();

        executor.executeWebFetch("https://example.com/not-found", new AntigravityToolExecutor.ToolCallback() {
            @Override
            public void onResult(AntigravityToolExecutor.ToolResult result) {
                resultRef.set(result);
                latch.countDown();
            }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        AntigravityToolExecutor.ToolResult result = resultRef.get();
        assertNotNull(result);
        assertTrue(result.isError);
        assertTrue(result.content.contains("404"));
    }

    @Test
    public void testExecuteWebFetchRejectsBinaryContentType() throws InterruptedException {
        mockInterceptor.contentType = "image/png";
        mockInterceptor.responseBody = "\u0089PNG\r\n\u001a\n";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<AntigravityToolExecutor.ToolResult> resultRef = new AtomicReference<>();

        executor.executeWebFetch("https://example.com/cover.png", new AntigravityToolExecutor.ToolCallback() {
            @Override
            public void onResult(AntigravityToolExecutor.ToolResult result) {
                resultRef.set(result);
                latch.countDown();
            }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        AntigravityToolExecutor.ToolResult result = resultRef.get();
        assertNotNull(result);
        assertTrue(result.isError);
        assertTrue(result.content.contains("Unsupported content type"));
    }

    @Test
    public void testExecuteWebFetchCancellation() {
        mockInterceptor.responseBody = "<html><body>Delayed</body></html>";

        AntigravityApiClient.Cancellable handle = executor.executeWebFetch("https://example.com/cancel-test", new AntigravityToolExecutor.ToolCallback() {
            @Override
            public void onResult(AntigravityToolExecutor.ToolResult result) {}
        });

        assertNotNull(handle);
        assertFalse(handle.isCancelled());
        handle.cancel();
        assertTrue(handle.isCancelled());
    }

    // --- Google Search Grounding Tests ---

    @Test
    public void testBuildSearchPayloadStructure() {
        String payload = AntigravityToolExecutor.buildSearchPayload("custom-project-123", "Queen Bohemian Rhapsody meaning", 5);
        assertNotNull(payload);

        JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
        assertEquals("custom-project-123", root.get("project").getAsString());
        assertEquals("gemini-2.5-flash", root.get("model").getAsString());
        assertEquals("agent", root.get("requestType").getAsString());
        assertEquals("antigravity", root.get("userAgent").getAsString());
        assertTrue(root.has("requestId"));
        assertTrue(root.get("requestId").getAsString().startsWith("agent-"));

        assertTrue(root.has("request"));
        JsonObject request = root.getAsJsonObject("request");

        // Contents
        assertTrue(request.has("contents"));
        com.google.gson.JsonArray contents = request.getAsJsonArray("contents");
        assertEquals(1, contents.size());
        JsonObject turn = contents.get(0).getAsJsonObject();
        assertEquals("user", turn.get("role").getAsString());
        com.google.gson.JsonArray parts = turn.getAsJsonArray("parts");
        assertEquals(1, parts.size());
        assertEquals("Queen Bohemian Rhapsody meaning", parts.get(0).getAsJsonObject().get("text").getAsString());

        // Tools
        assertTrue(request.has("tools"));
        com.google.gson.JsonArray tools = request.getAsJsonArray("tools");
        assertEquals(1, tools.size());
        JsonObject tool0 = tools.get(0).getAsJsonObject();
        assertTrue("Tool must contain googleSearch", tool0.has("googleSearch"));
        assertTrue(tool0.get("googleSearch").isJsonObject());
    }

    @Test
    public void testBuildSearchPayloadDefaultProjectId() {
        String payload = AntigravityToolExecutor.buildSearchPayload(null, "Queen song facts", 3);
        JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
        assertEquals("aicode-consumers", root.get("project").getAsString());
        assertEquals("gemini-2.5-flash", root.get("model").getAsString());
    }

    @Test
    public void testParseSearchGroundingResponseExtractsTextSourcesCitationsAndQueries() {
        String sseChunk = "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Bohemian Rhapsody was written by Freddie Mercury and released in 1975.\"}]}, \"groundingMetadata\": {\"webSearchQueries\": [\"queen bohemian rhapsody history\", \"freddie mercury songwriting 1975\"], \"groundingChunks\": [{\"web\": {\"title\": \"Bohemian Rhapsody - Wikipedia\", \"uri\": \"https://en.wikipedia.org/wiki/Bohemian_Rhapsody\"}}, {\"web\": {\"title\": \"Songfacts: Bohemian Rhapsody\", \"uri\": \"https://www.songfacts.com/facts/queen/bohemian-rhapsody\"}}], \"groundingSupports\": [{\"groundingChunkIndices\": [0], \"segment\": {\"text\": \"Bohemian Rhapsody was written by Freddie Mercury and released in 1975.\"}}]}}]}\n\n";

        AntigravityToolExecutor.ToolResult result = AntigravityToolExecutor.parseSearchGroundingResponse(sseChunk);
        assertNotNull(result);
        assertFalse("Result must not be an error", result.isError);
        assertNotNull(result.content);

        // Verify Java sources list
        assertEquals(2, result.sources.size());
        assertEquals("Bohemian Rhapsody - Wikipedia", result.sources.get(0).title);
        assertEquals("https://en.wikipedia.org/wiki/Bohemian_Rhapsody", result.sources.get(0).url);
        assertEquals("Songfacts: Bohemian Rhapsody", result.sources.get(1).title);
        assertEquals("https://www.songfacts.com/facts/queen/bohemian-rhapsody", result.sources.get(1).url);

        // Verify JSON content structure
        JsonObject json = JsonParser.parseString(result.content).getAsJsonObject();
        assertEquals("success", json.get("status").getAsString());
        assertTrue(json.get("answer").getAsString().contains("Freddie Mercury and released in 1975"));

        // Verify queries
        com.google.gson.JsonArray queries = json.getAsJsonArray("searchQueries");
        assertNotNull(queries);
        assertEquals(2, queries.size());
        assertEquals("queen bohemian rhapsody history", queries.get(0).getAsString());
        assertEquals("freddie mercury songwriting 1975", queries.get(1).getAsString());

        // Verify citations
        com.google.gson.JsonArray citations = json.getAsJsonArray("citations");
        assertNotNull(citations);
        assertEquals(1, citations.size());
        JsonObject citation = citations.get(0).getAsJsonObject();
        assertEquals("Bohemian Rhapsody - Wikipedia", citation.get("title").getAsString());
        assertEquals("https://en.wikipedia.org/wiki/Bohemian_Rhapsody", citation.get("url").getAsString());
        assertEquals("Bohemian Rhapsody was written by Freddie Mercury and released in 1975.", citation.get("citedText").getAsString());
    }

    @Test
    public void testParseSearchGroundingResponseResolvesVertexRedirectUrls() {
        String sseChunk = "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Song info\"}]}, \"groundingMetadata\": {\"groundingChunks\": [{\"web\": {\"title\": \"Redirected Source\", \"uri\": \"https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc123xyz?url=https%3A%2F%2Fwww.rollingstone.com%2Fmusic%2Fqueen\"}}]}}]}\n\n";

        AntigravityToolExecutor.ToolResult result = AntigravityToolExecutor.parseSearchGroundingResponse(sseChunk);
        assertNotNull(result);
        assertFalse(result.isError);
        assertEquals(1, result.sources.size());
        assertEquals("https://www.rollingstone.com/music/queen", result.sources.get(0).url);
        assertEquals("Redirected Source", result.sources.get(0).title);
    }

    @Test
    public void testParseSearchGroundingResponseErrorFrame() {
        String sseErrorChunk = "data: {\"error\": {\"code\": 429, \"message\": \"Quota exceeded for project\"}}\n\n";

        AntigravityToolExecutor.ToolResult result = AntigravityToolExecutor.parseSearchGroundingResponse(sseErrorChunk);
        assertNotNull(result);
        assertTrue("Result must be error", result.isError);
        assertTrue("Must contain fallback message", result.content.contains("Pencarian web tidak tersedia saat ini. Lanjutkan dengan pengetahuan internal."));
        assertEquals(0, result.sources.size());
    }

    @Test
    public void testExecuteWebSearchSuccess() throws InterruptedException {
        mockInterceptor.contentType = "text/event-stream";
        mockInterceptor.responseBody = "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Freddie Mercury wrote Bohemian Rhapsody in 1975.\"}]}, \"groundingMetadata\": {\"webSearchQueries\": [\"queen bohemian rhapsody\"], \"groundingChunks\": [{\"web\": {\"title\": \"Queen Official\", \"uri\": \"https://queenonline.com/history\"}}], \"groundingSupports\": [{\"groundingChunkIndices\": [0], \"segment\": {\"text\": \"Freddie Mercury wrote Bohemian Rhapsody in 1975.\"}}]}}]}\n\n";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<AntigravityToolExecutor.ToolResult> resultRef = new AtomicReference<>();

        AntigravityApiClient.Cancellable handle = executor.executeWebSearch("Queen Bohemian Rhapsody", 5, new AntigravityToolExecutor.ToolCallback() {
            @Override
            public void onResult(AntigravityToolExecutor.ToolResult result) {
                resultRef.set(result);
                latch.countDown();
            }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertNotNull(handle);
        assertEquals(1, mockInterceptor.requestCount);
        assertEquals("https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse", mockInterceptor.lastUrl);

        AntigravityToolExecutor.ToolResult result = resultRef.get();
        assertNotNull(result);
        assertFalse("Must not be error", result.isError);
        assertEquals(1, result.sources.size());
        assertEquals("Queen Official", result.sources.get(0).title);
        assertEquals("https://queenonline.com/history", result.sources.get(0).url);

        JsonObject json = JsonParser.parseString(result.content).getAsJsonObject();
        assertEquals("success", json.get("status").getAsString());
        assertTrue(json.get("answer").getAsString().contains("Freddie Mercury wrote Bohemian Rhapsody"));
    }

    @Test
    public void testExecuteWebSearchFallbackOn503() throws InterruptedException {
        mockInterceptor.contentType = "text/event-stream";
        mockInterceptor.urlResponseCodes.put(
                "https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse", 503);
        mockInterceptor.urlResponseCodes.put(
                "https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse", 200);
        mockInterceptor.urlResponseBodies.put(
                "https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse",
                "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Recovered from prod endpoint.\"}]}}]}\n\n");

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<AntigravityToolExecutor.ToolResult> resultRef = new AtomicReference<>();

        executor.executeWebSearch("Fallback test", 5, new AntigravityToolExecutor.ToolCallback() {
            @Override
            public void onResult(AntigravityToolExecutor.ToolResult result) {
                resultRef.set(result);
                latch.countDown();
            }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(2, mockInterceptor.requestCount);
        assertEquals("https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse", mockInterceptor.lastUrl);

        AntigravityToolExecutor.ToolResult result = resultRef.get();
        assertNotNull(result);
        assertFalse("Fallback to prod must succeed", result.isError);
        assertTrue(result.content.contains("Recovered from prod endpoint."));
    }

    @Test
    public void testExecuteWebSearchFallbackOn404() throws InterruptedException {
        mockInterceptor.contentType = "text/event-stream";
        mockInterceptor.urlResponseCodes.put(
                "https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse", 404);
        mockInterceptor.urlResponseCodes.put(
                "https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse", 200);
        mockInterceptor.urlResponseBodies.put(
                "https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse",
                "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Recovered from 404 fallback.\"}]}}]}\n\n");

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<AntigravityToolExecutor.ToolResult> resultRef = new AtomicReference<>();

        executor.executeWebSearch("404 Fallback test", 5, new AntigravityToolExecutor.ToolCallback() {
            @Override
            public void onResult(AntigravityToolExecutor.ToolResult result) {
                resultRef.set(result);
                latch.countDown();
            }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(2, mockInterceptor.requestCount);
        assertEquals("https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse", mockInterceptor.lastUrl);

        AntigravityToolExecutor.ToolResult result = resultRef.get();
        assertNotNull(result);
        assertFalse(result.isError);
        assertTrue(result.content.contains("Recovered from 404 fallback."));
    }

    @Test
    public void testExecuteWebSearchGracefulErrorOn429() throws InterruptedException {
        mockInterceptor.responseCode = 429;

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<AntigravityToolExecutor.ToolResult> resultRef = new AtomicReference<>();

        executor.executeWebSearch("Rate limited query", 5, new AntigravityToolExecutor.ToolCallback() {
            @Override
            public void onResult(AntigravityToolExecutor.ToolResult result) {
                resultRef.set(result);
                latch.countDown();
            }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        AntigravityToolExecutor.ToolResult result = resultRef.get();
        assertNotNull(result);
        assertTrue("Must be marked error", result.isError);
        assertTrue("Must contain graceful Indonesian message",
                result.content.contains("Pencarian web tidak tersedia saat ini. Lanjutkan dengan pengetahuan internal."));
        assertEquals(0, result.sources.size());
    }

    @Test
    public void testExecuteWebSearchGracefulErrorOnNetworkFailure() throws InterruptedException {
        mockInterceptor.throwNetworkError = true;

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<AntigravityToolExecutor.ToolResult> resultRef = new AtomicReference<>();

        executor.executeWebSearch("Network broken query", 5, new AntigravityToolExecutor.ToolCallback() {
            @Override
            public void onResult(AntigravityToolExecutor.ToolResult result) {
                resultRef.set(result);
                latch.countDown();
            }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        AntigravityToolExecutor.ToolResult result = resultRef.get();
        assertNotNull(result);
        assertTrue("Must be marked error", result.isError);
        assertTrue("Must contain graceful Indonesian message",
                result.content.contains("Pencarian web tidak tersedia saat ini. Lanjutkan dengan pengetahuan internal."));
        assertEquals(0, result.sources.size());
    }

    @Test
    public void testExecuteWebSearchCancellation() {
        mockInterceptor.contentType = "text/event-stream";
        mockInterceptor.responseBody = "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Delayed\"}]}}]}\n\n";

        AntigravityApiClient.Cancellable handle = executor.executeWebSearch("Cancel query", 5, new AntigravityToolExecutor.ToolCallback() {
            @Override
            public void onResult(AntigravityToolExecutor.ToolResult result) {}
        });

        assertNotNull(handle);
        assertFalse(handle.isCancelled());
        handle.cancel();
        assertTrue(handle.isCancelled());
    }

    // --- Mock HTTP Interceptor Helper ---

    static class MockHttpInterceptor implements Interceptor {
        volatile int requestCount = 0;
        volatile int responseCode = 200;
        volatile String contentType = "text/html; charset=utf-8";
        volatile String responseBody = "<html><body>Sample</body></html>";
        volatile String lastUrl;
        volatile boolean throwNetworkError = false;
        final java.util.Map<String, Integer> urlResponseCodes = new java.util.HashMap<>();
        final java.util.Map<String, String> urlResponseBodies = new java.util.HashMap<>();

        @Override
        public Response intercept(Chain chain) throws IOException {
            requestCount++;
            Request request = chain.request();
            lastUrl = request.url().toString();

            if (throwNetworkError) {
                throw new IOException("Simulated network failure");
            }

            int code = responseCode;
            if (urlResponseCodes.containsKey(lastUrl)) {
                code = urlResponseCodes.get(lastUrl);
            }

            String bodyStr = responseBody;
            if (urlResponseBodies.containsKey(lastUrl)) {
                bodyStr = urlResponseBodies.get(lastUrl);
            }

            if (code >= 400) {
                return new Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("Error " + code)
                        .body(ResponseBody.create(MediaType.parse("application/json; charset=utf-8"),
                                "{\"error\": \"HTTP " + code + "\"}"))
                        .build();
            }

            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(MediaType.parse(contentType), bodyStr))
                    .build();
        }
    }
}
