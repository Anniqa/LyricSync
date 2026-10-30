package com.lyricsync.app.ai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.ai.client.AntigravityApiClient.FunctionCall;
import com.lyricsync.app.ai.client.AntigravityApiClient.StreamListener;
import com.lyricsync.app.ai.model.AiChatMessage;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor.ToolResult;
import com.lyricsync.app.ai.util.ApiErrors;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * One reply from ChatGPT through the Codex backend ({@code chatgpt.com/backend-api/codex/responses}),
 * signed in with OAuth. Wire format: OpenAI Responses API with {@code store:false}, so the
 * whole conversation — including earlier tool calls, their outputs and encrypted reasoning
 * — is sent every round. Shapes verified against the live endpoint; reference: oh-my-pi
 * {@code providers/openai-codex/request-transformer.ts}.
 */
final class CodexTurn extends ToolTurn {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    interface Tokens {
        void get(TokenReady ready);
    }

    interface TokenReady {
        void onToken(String accessToken, String accountId);

        void onError(String message);
    }

    private final Tokens tokens;
    private final AiProvider.Settings provider;
    private final String instructions;
    private final JsonArray input;
    private final JsonArray tools;
    /** Output items of the current round that must be replayed (reasoning, function calls). */
    private final List<JsonObject> roundItems = new ArrayList<>();

    CodexTurn(AntigravityApiClient owner, Tokens tokens, AiProvider.Settings provider, List<AiChatMessage> history,
              boolean webTools, StreamListener listener) {
        super(owner, listener);
        this.tokens = tokens;
        this.provider = provider;
        this.instructions = instructions(history);
        this.input = toInput(history);
        boolean search = webTools && AiProvider.searchEngine(provider, owner.toolExecutor.googleSignedIn()) != null;
        this.tools = toolDeclarations(webTools, search);
    }

    @Override
    void stream() {
        tokens.get(new TokenReady() {
            @Override
            public void onToken(String accessToken, String accountId) {
                send(request(accessToken, accountId), CodexTurn.this::onStream);
            }

            @Override
            public void onError(String message) {
                fail(new IOException("ChatGPT: " + message));
            }
        });
    }

    Request request(String accessToken, String accountId) {
        JsonObject body = body(provider.model, provider.effort, instructions, input, tools);
        roundItems.clear();
        return new Request.Builder()
                .url(AiProvider.CODEX_BASE_URL + "/responses")
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("chatgpt-account-id", accountId)
                .addHeader("originator", "codex_cli_rs")
                .addHeader("OpenAI-Beta", "responses=experimental")
                .addHeader("Accept", "text/event-stream")
                .post(RequestBody.create(JSON, body.toString()))
                .build();
    }

    private void onStream(Response response) {
        List<FunctionCall> calls = new ArrayList<>();
        try (ResponseBody body = response.body()) {
            if (body == null) throw new IOException("ChatGPT: empty response");
            if (!response.isSuccessful()) {
                String text = body.string();
                throw new IOException("ChatGPT: " + errorMessage(response.code(), text));
            }
            BufferedSource source = body.source();
            String line;
            while (!isCancelled() && (line = source.readUtf8Line()) != null) {
                JsonObject e = event(line);
                if (e == null) continue;
                String type = str(e, "type");
                if ("response.output_text.delta".equals(type)) {
                    delta(str(e, "delta"));
                } else if ("response.output_item.done".equals(type)) {
                    JsonObject item = obj(e, "item");
                    String kind = str(item, "type");
                    if ("function_call".equals(kind)) {
                        roundItems.add(replayCall(item));
                        calls.add(new FunctionCall(str(item, "name"), args(str(item, "arguments")), str(item, "call_id"), null));
                    } else if ("reasoning".equals(kind) && item.has("encrypted_content")) {
                        roundItems.add(item);
                    }
                } else if ("response.completed".equals(type)) {
                    readUsage(obj(obj(e, "response"), "usage"));
                } else if ("response.failed".equals(type) || "error".equals(type)) {
                    JsonObject err = obj(obj(e, "response"), "error");
                    if (err.size() == 0) err = obj(e, "error");
                    throw new IOException("ChatGPT: " + (str(err, "message").isEmpty() ? "request failed" : str(err, "message")));
                }
            }
        } catch (Exception e) {
            fail(e);
            return;
        }
        onReply(calls);
    }

    /** Replays this round's reasoning and calls, then one {@code function_call_output} per call. */
    @Override
    void addToolTurn(List<FunctionCall> calls, List<ToolResult> results) {
        for (JsonObject item : roundItems) input.add(item);
        for (int i = 0; i < calls.size(); i++) {
            ToolResult r = results.get(i);
            JsonObject out = new JsonObject();
            out.addProperty("type", "function_call_output");
            out.addProperty("call_id", calls.get(i).getId());
            out.addProperty("output", r == null ? "Error: no result" : r.content);
            input.add(out);
        }
    }

    private void readUsage(JsonObject usage) {
        if (usage.has("input_tokens")) promptTokens = usage.get("input_tokens").getAsInt();
        if (usage.has("output_tokens")) candidateTokens = usage.get("output_tokens").getAsInt();
    }

    // ---------------------------------------------------------------- wire format (tested)

    static JsonObject body(String model, String effort, String instructions, JsonArray input, JsonArray tools) {
        JsonObject b = new JsonObject();
        b.addProperty("model", model);
        b.addProperty("store", false);
        b.addProperty("stream", true);
        b.addProperty("instructions", instructions);
        b.add("input", input);
        if (tools.size() > 0) {
            b.add("tools", tools);
            b.addProperty("tool_choice", "auto");
            b.addProperty("parallel_tool_calls", false); // the lyric tools may depend on each other
        }
        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("effort", effort.isEmpty() ? "medium" : effort);
        b.add("reasoning", reasoning);
        JsonArray include = new JsonArray();
        include.add("reasoning.encrypted_content"); // store:false → reasoning must travel with the input
        b.add("include", include);
        return b;
    }

    /** The backend requires top-level instructions; the app's system context goes there. */
    static String instructions(List<AiChatMessage> history) {
        if (history != null) {
            for (AiChatMessage m : history) {
                if (m != null && m.role != null && "system".equalsIgnoreCase(m.role.trim()) && m.content != null) return m.content;
            }
        }
        return "You are Lyra, the assistant inside the LyricSync lyrics app.";
    }

    /** Chat history → Responses input items; "model" turns are the assistant's. System turns become instructions. */
    static JsonArray toInput(List<AiChatMessage> history) {
        JsonArray out = new JsonArray();
        if (history == null) return out;
        for (AiChatMessage m : history) {
            if (m == null || m.content == null) continue;
            String role = m.role == null ? "user" : m.role.trim().toLowerCase(Locale.ROOT);
            if ("system".equals(role)) continue;
            boolean assistant = "model".equals(role) || "assistant".equals(role);
            JsonObject part = new JsonObject();
            part.addProperty("type", assistant ? "output_text" : "input_text");
            part.addProperty("text", m.content);
            JsonArray content = new JsonArray();
            content.add(part);
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "message");
            msg.addProperty("role", assistant ? "assistant" : "user");
            msg.add("content", content);
            out.add(msg);
        }
        return out;
    }

    /** The lyric tools in Responses form: {type, name, description, parameters} without the "function" wrapper. */
    static JsonArray toolDeclarations(boolean webTools, boolean webSearch) {
        JsonArray out = new JsonArray();
        for (JsonElement el : OpenAiTurn.toolDeclarations(webTools, webSearch)) {
            JsonObject fn = el.getAsJsonObject().getAsJsonObject("function");
            JsonObject t = new JsonObject();
            t.addProperty("type", "function");
            for (String k : new String[]{"name", "description", "parameters"}) if (fn.has(k)) t.add(k, fn.get(k));
            out.add(t);
        }
        return out;
    }

    /** A finished function_call item reduced to the fields the backend accepts back. */
    private static JsonObject replayCall(JsonObject item) {
        JsonObject c = new JsonObject();
        c.addProperty("type", "function_call");
        c.addProperty("call_id", str(item, "call_id"));
        c.addProperty("name", str(item, "name"));
        c.addProperty("arguments", str(item, "arguments"));
        return c;
    }

    /** "429 usage limit…" style message from {"detail":…} or {"error":{"message":…}} bodies. */
    static String errorMessage(int code, String body) {
        String m = ApiErrors.message(body, "");
        if (m.isEmpty()) {
            try {
                JsonObject o = JsonParser.parseString(body).getAsJsonObject();
                m = str(o, "detail");
            } catch (RuntimeException ignored) {
            }
        }
        if (code == 401) return "the sign-in expired. Sign in with ChatGPT again.";
        if (code == 429) return m.isEmpty() ? "usage limit reached. Try again later." : m;
        return m.isEmpty() ? "HTTP " + code : m;
    }

    private static JsonObject args(String json) {
        try {
            JsonElement e = JsonParser.parseString(json == null || json.trim().isEmpty() ? "{}" : json);
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException malformed) {
            return new JsonObject(); // the tool reports what is missing
        }
    }

    private static JsonObject event(String line) {
        String p = line == null ? "" : line.trim();
        if (!p.startsWith("data:")) return null;
        p = p.substring(5).trim();
        if (!p.startsWith("{")) return null;
        try {
            return JsonParser.parseString(p).getAsJsonObject();
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static JsonObject obj(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
