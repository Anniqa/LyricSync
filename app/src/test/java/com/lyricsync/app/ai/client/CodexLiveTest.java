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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Live ChatGPT (Codex) round-trip with a local login. LYRICSYNC_LIVE=1, CODEX_ACCESS_TOKEN,
 * CODEX_ACCOUNT_ID (from ~/.codex/auth.json), optional CODEX_MODEL. Tokens never printed.
 */
public class CodexLiveTest {

    @Test
    public void pausesThroughTheToolLoop() throws InterruptedException {
        String token = System.getenv("CODEX_ACCESS_TOKEN"), account = System.getenv("CODEX_ACCOUNT_ID");
        Assume.assumeTrue("1".equals(System.getenv("LYRICSYNC_LIVE")) && token != null && account != null);
        String model = System.getenv("CODEX_MODEL") != null ? System.getenv("CODEX_MODEL") : "gpt-6-luna";
        AntigravityApiClient client = new AntigravityApiClient(null, null, new AntigravityApiClientToolLoopTest.FakeToolExecutor());
        List<String> calls = new ArrayList<>();
        client.setLyricActionDelegate(new AntigravityApiClientToolLoopTest.RecordingDelegate(calls));
        AiProvider.Settings s = new AiProvider.Settings(true, AiProvider.CODEX_BASE_URL, "", model, "", false, true, "low");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> out = new AtomicReference<>();
        new CodexTurn(client, r -> r.onToken(token, account), s,
                Arrays.asList(new AiChatMessage("system", "You control a music app. Be brief."), AiChatMessage.user("Pause the music, then say done.")),
                false, new AntigravityApiClient.StreamListener() {
                    @Override public void onComplete(String full, List<String> src, int p, int c) {
                        out.set(full + " [tokens " + p + "/" + c + "]");
                        done.countDown();
                    }

                    @Override public void onError(Exception e) {
                        out.set("ERR " + e.getMessage());
                        done.countDown();
                    }
                }).stream();
        assertTrue(done.await(90, TimeUnit.SECONDS));
        System.out.println("CODEX " + model + ": " + out.get() + " calls=" + calls);
        assertEquals(1, calls.size());
        assertTrue(calls.get(0), calls.get(0).startsWith("playback:pause:"));
        assertTrue(out.get(), !out.get().startsWith("ERR"));
    }
}
