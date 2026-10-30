package com.lyricsync.app.ai.client;

import com.lyricsync.app.ai.model.AiChatMessage;

import org.junit.Assume;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Live: LYRICSYNC_LIVE=1 AI_BASE_URL=… AI_API_KEY=… [AI_MODEL=…]. Scans /models, then runs a tool call. */
public class OpenAiLiveTest {

    @Test
    public void scanAndToolCall() throws Exception {
        String base = System.getenv("AI_BASE_URL"), key = System.getenv("AI_API_KEY");
        Assume.assumeTrue("1".equals(System.getenv("LYRICSYNC_LIVE")) && base != null && key != null);

        CountDownLatch scanned = new CountDownLatch(1);
        AtomicReference<List<String>> models = new AtomicReference<>();
        OpenAiModels.fetch(base, key, new OpenAiModels.Result() {
            @Override public void onModels(List<String> ids) { models.set(ids); scanned.countDown(); }
            @Override public void onError(String m) { System.out.println("scan error: " + m); scanned.countDown(); }
        });
        assertTrue(scanned.await(30, TimeUnit.SECONDS));
        assertFalse("models scanned", models.get() == null || models.get().isEmpty());
        System.out.println("models: " + models.get());
        String model = System.getenv("AI_MODEL") != null ? System.getenv("AI_MODEL") : models.get().get(0);

        AntigravityApiClient client = new AntigravityApiClient(null, null, new AntigravityApiClientToolLoopTest.FakeToolExecutor());
        List<String> calls = new ArrayList<>();
        client.setLyricActionDelegate(new AntigravityApiClientToolLoopTest.RecordingDelegate(calls));
        client.setProviderSettings(() -> new AiProvider.Settings(true, base, key, model, "", false));
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> out = new AtomicReference<>();
        client.generateContentStream(null, Arrays.asList(
                AiChatMessage.user("You control a music lyrics app through tools. Pause the music, then reply in one short sentence.")),
                false, null, new AntigravityApiClient.StreamListener() {
                    @Override public void onComplete(String full, List<String> s, int p, int c) { out.set(full); done.countDown(); }
                    @Override public void onError(Exception e) { out.set("ERROR " + e.getMessage()); done.countDown(); }
                });
        assertTrue(done.await(90, TimeUnit.SECONDS));
        System.out.println("model=" + model + " tools=" + calls + " reply=" + out.get());
        assertFalse(out.get(), out.get().startsWith("ERROR"));
        assertTrue("tool called: " + calls, calls.toString().contains("playback:pause"));
    }

    /** Custom model + real web_search through Exa (no Google account). */
    @Test
    public void webSearchThroughExa() throws Exception {
        String base = System.getenv("AI_BASE_URL"), key = System.getenv("AI_API_KEY"), exa = System.getenv("EXA_API_KEY");
        Assume.assumeTrue("1".equals(System.getenv("LYRICSYNC_LIVE")) && base != null && key != null && exa != null);
        String model = System.getenv("AI_MODEL");
        AiProvider.Settings settings = new AiProvider.Settings(true, base, key, model, exa, false);
        com.lyricsync.app.ai.tools.AntigravityToolExecutor tools =
                new com.lyricsync.app.ai.tools.AntigravityToolExecutor((com.lyricsync.app.ai.auth.AntigravityAuthManager) null);
        AntigravityApiClient client = new AntigravityApiClient(null, null, tools);
        client.setProviderSettings(() -> settings);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> out = new AtomicReference<>();
        List<String> sources = new ArrayList<>();
        client.generateContentStream(null, Arrays.asList(AiChatMessage.user(
                "Use web_search once: what album is My Chemical Romance's 'This Is How I Disappear' on? One sentence.")),
                true, null, new AntigravityApiClient.StreamListener() {
                    @Override public void onToolExecuting(String n, String q) { System.out.println("tool " + n + ": " + q); }
                    @Override public void onComplete(String full, List<String> s, int p, int c) { out.set(full); sources.addAll(s); done.countDown(); }
                    @Override public void onError(Exception e) { out.set("ERROR " + e.getMessage()); done.countDown(); }
                });
        assertTrue(done.await(120, TimeUnit.SECONDS));
        System.out.println("reply=" + out.get() + "\nsources=" + sources.size());
        assertFalse(out.get(), out.get().startsWith("ERROR"));
        assertFalse("sources from Exa", sources.isEmpty());
    }
}
