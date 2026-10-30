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

/** The OpenAI-compatible path runs the same lyric tools as Gemini, end to end. */
public class OpenAiTurnTest {

    @Test
    public void toolsBecomeJsonSchemaFunctions() {
        JsonArray tools = OpenAiTurn.toolDeclarations(true, false);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tools.size(); i++) {
            JsonObject t = tools.get(i).getAsJsonObject();
            assertEquals("function", t.get("type").getAsString());
            names.add(t.getAsJsonObject("function").get("name").getAsString());
        }
        assertFalse("no search engine → no web_search", names.contains("web_search"));
        assertTrue(names.contains("web_fetch") && names.contains("edit_lyrics"));
        JsonObject edit = tools.get(names.indexOf("edit_lyrics")).getAsJsonObject().getAsJsonObject("function");
        JsonObject ops = edit.getAsJsonObject("parameters").getAsJsonObject("properties").getAsJsonObject("ops");
        assertEquals("array", ops.get("type").getAsString());
        assertEquals("object", ops.getAsJsonObject("items").get("type").getAsString());
        assertEquals("integer", ops.getAsJsonObject("items").getAsJsonObject("properties")
                .getAsJsonObject("line").get("type").getAsString());
    }

    /** A streamed call with fragmented arguments runs the tool, then the model answers. */
    @Test
    public void streamedToolCallRunsAndReplays() throws InterruptedException {
        AntigravityApiClientToolLoopTest.MultiTurnMockInterceptor server = new AntigravityApiClientToolLoopTest.MultiTurnMockInterceptor();
        server.enqueueSseResponse(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"pause it\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"control_playback\",\"arguments\":\"{\\\"act\"}}]}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"ion\\\":\\\"pause\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"
                        + "data: [DONE]\n\n");
        server.enqueueSseResponse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Paused, \"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"hmph.\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}\n\n"
                        + "data: [DONE]\n\n");
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(server).build();
        AntigravityApiClient client = new AntigravityApiClient(null, http, new AntigravityApiClientToolLoopTest.FakeToolExecutor());
        List<String> calls = new ArrayList<>();
        client.setLyricActionDelegate(new AntigravityApiClientToolLoopTest.RecordingDelegate(calls));
        client.setProviderSettings(() -> new AiProvider.Settings(true, "https://api.example.com/v1", "sk-test", "deepseek-chat", "", false));
        assertTrue(client.isAuthenticated());

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> text = new AtomicReference<>();
        AtomicReference<Integer> tokens = new AtomicReference<>();
        client.generateContentStream("gemini-ignored", Arrays.asList(AiChatMessage.user("pause")), false, "high",
                new AntigravityApiClient.StreamListener() {
                    @Override
                    public void onComplete(String full, List<String> sources, int prompt, int candidate) {
                        text.set(full);
                        tokens.set(prompt);
                        done.countDown();
                    }

                    @Override
                    public void onError(Exception e) {
                        text.set("ERROR " + e.getMessage());
                        done.countDown();
                    }
                });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("Paused, hmph.", text.get());
        assertEquals(Integer.valueOf(12), tokens.get());
        assertEquals(Arrays.asList("playback:pause:-1"), calls);

        assertEquals("https://api.example.com/v1/chat/completions", server.recordedRequests.get(0).url().toString());
        assertEquals("Bearer sk-test", server.recordedRequests.get(0).header("Authorization"));
        JsonObject second = JsonParser.parseString(server.recordedRequestBodies.get(1)).getAsJsonObject();
        assertEquals("deepseek-chat", second.get("model").getAsString());
        JsonArray msgs = second.getAsJsonArray("messages");
        JsonObject assistant = msgs.get(1).getAsJsonObject();
        assertEquals("pause it", assistant.get("reasoning_content").getAsString());
        JsonObject call = assistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject();
        assertEquals("call_1", call.get("id").getAsString());
        assertEquals("{\"action\":\"pause\"}", call.getAsJsonObject("function").get("arguments").getAsString());
        JsonObject tool = msgs.get(2).getAsJsonObject();
        assertEquals("tool", tool.get("role").getAsString());
        assertEquals("call_1", tool.get("tool_call_id").getAsString());
    }
}
