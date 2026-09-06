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

    // --- Mock HTTP Interceptor Helper ---

    static class MockHttpInterceptor implements Interceptor {
        volatile int requestCount = 0;
        volatile int responseCode = 200;
        volatile String responseBody = "<html><body>Sample</body></html>";
        volatile String lastUrl;

        @Override
        public Response intercept(Chain chain) throws IOException {
            requestCount++;
            Request request = chain.request();
            lastUrl = request.url().toString();

            if (responseCode >= 400) {
                return new Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(responseCode)
                        .message("Error " + responseCode)
                        .body(ResponseBody.create(MediaType.parse("text/plain"), "Not Found"))
                        .build();
            }

            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(MediaType.parse("text/html; charset=utf-8"), responseBody))
                    .build();
        }
    }
}
