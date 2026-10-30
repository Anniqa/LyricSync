package com.lyricsync.app.ai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.ai.model.AiChatMessage;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** ChatGPT (Codex) wire format: the request shape and one tool round-trip, as the live backend answered. */
public class CodexTurnTest {

    @Test
    public void requestCarriesInstructionsHistoryToolsAndEffort() {
        List<AiChatMessage> history = Arrays.asList(new AiChatMessage("system", "Be Lyra."),
                AiChatMessage.user("hi"), AiChatMessage.model("Hmph. Hi."));
        JsonObject b = CodexTurn.body("gpt-6-luna", "high", CodexTurn.instructions(history),
                CodexTurn.toInput(history), CodexTurn.toolDeclarations(false, false));
        assertEquals("Be Lyra.", b.get("instructions").getAsString());
        assertFalse(b.get("store").getAsBoolean());
        assertEquals("high", b.getAsJsonObject("reasoning").get("effort").getAsString());
        assertEquals("reasoning.encrypted_content", b.getAsJsonArray("include").get(0).getAsString());
        JsonArray input = b.getAsJsonArray("input");
        assertEquals("system turns move to instructions", 2, input.size());
        assertEquals("output_text", input.get(1).getAsJsonObject().getAsJsonArray("content")
                .get(0).getAsJsonObject().get("type").getAsString());
        JsonObject tool = b.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertTrue("Responses tools are flat", tool.has("name") && tool.has("parameters") && !tool.has("function"));
        assertEquals("medium is the default effort", "medium",
                CodexTurn.body("m", "", "x", new JsonArray(), new JsonArray()).getAsJsonObject("reasoning").get("effort").getAsString());
    }

    @Test
    public void toolCallRunsAndOutputIsSentBack() throws InterruptedException {
        AntigravityApiClientToolLoopTest.MultiTurnMockInterceptor server = new AntigravityApiClientToolLoopTest.MultiTurnMockInterceptor();
        server.enqueueSseResponse(
                "event: response.output_item.done\ndata: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\","
                        + "\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"control_playback\",\"arguments\":\"{\\\"action\\\":\\\"pause\\\"}\",\"status\":\"completed\"}}\n\n"
                        + "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":144,\"output_tokens\":9}}}\n\n");
        server.enqueueSseResponse(
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Done.\"}\n\n"
                        + "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":170,\"output_tokens\":3}}}\n\n");
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(server).build();
        AntigravityApiClient client = new AntigravityApiClient(null, http, new AntigravityApiClientToolLoopTest.FakeToolExecutor());
        List<String> calls = new ArrayList<>();
        client.setLyricActionDelegate(new AntigravityApiClientToolLoopTest.RecordingDelegate(calls));
        AiProvider.Settings codex = new AiProvider.Settings(true, AiProvider.CODEX_BASE_URL, "", "gpt-6-luna", "", false, true, "low");

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> text = new AtomicReference<>();
        CodexTurn turn = new CodexTurn(client, ready -> ready.onToken("tok", "acct"), codex,
                Arrays.asList(AiChatMessage.user("pause")), false, new AntigravityApiClient.StreamListener() {
            @Override public void onDelta(String d) {}
            @Override public void onToolExecuting(String n, String a) {}
            @Override public void onToolCompleted(String n, int c, boolean e) {}
            @Override public void onComplete(String full, List<String> s, int p, int c) { text.set(full); done.countDown(); }
            @Override public void onError(Exception e) { text.set("ERR " + e.getMessage()); done.countDown(); }
        });
        turn.stream();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("Done.", text.get());
        assertEquals(Arrays.asList("playback:pause:-1"), calls);
        assertEquals("chatgpt.com", server.recordedRequests.get(0).url().host());
        assertEquals("acct", server.recordedRequests.get(0).header("chatgpt-account-id"));
        JsonArray second = JsonParser.parseString(server.recordedRequestBodies.get(1)).getAsJsonObject().getAsJsonArray("input");
        JsonObject output = second.get(second.size() - 1).getAsJsonObject();
        assertEquals("function_call_output", output.get("type").getAsString());
        assertEquals("call_1", output.get("call_id").getAsString());
        assertEquals("function_call", second.get(second.size() - 2).getAsJsonObject().get("type").getAsString());
    }
}
