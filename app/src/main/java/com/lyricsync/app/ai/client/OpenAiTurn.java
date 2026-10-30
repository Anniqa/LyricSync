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
import java.util.Map;
import java.util.TreeMap;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * A turn against any OpenAI-compatible {@code /chat/completions} API (DeepSeek, River,
 * OpenRouter, Ollama…): streamed text, streamed tool calls, then tool results as
 * {@code role:"tool"} messages. The same lyric tools as Gemini, in JSON-Schema form.
 */
final class OpenAiTurn extends ToolTurn {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final AiProvider.Settings provider;
    private final JsonArray messages;
    private final JsonArray tools;
    private final StringBuilder roundText = new StringBuilder();
    private final StringBuilder roundReasoning = new StringBuilder();

    OpenAiTurn(AntigravityApiClient owner, AiProvider.Settings provider, List<AiChatMessage> history,
               boolean webTools, StreamListener listener) {
        super(owner, listener);
        this.provider = provider;
        this.messages = toMessages(history);
        boolean search = webTools && AiProvider.searchEngine(provider, owner.toolExecutor.googleSignedIn()) != null;
        this.tools = toolDeclarations(webTools, search);
    }

    @Override
    void stream() {
        JsonObject body = new JsonObject();
        body.addProperty("model", provider.model);
        body.addProperty("stream", true);
        body.add("messages", messages);
        if (tools.size() > 0) body.add("tools", tools);
        // DeepSeek, OpenAI, OpenRouter and River accept it; servers that do not know it ignore it.
        if (!provider.effort.isEmpty()) body.addProperty("reasoning_effort", provider.effort);
        Request.Builder b = new Request.Builder()
                .url(provider.baseUrl + "/chat/completions")
                .addHeader("Accept", "text/event-stream")
                .post(RequestBody.create(JSON, body.toString()));
        if (!provider.apiKey.isEmpty()) b.addHeader("Authorization", "Bearer " + provider.apiKey);
        roundText.setLength(0);
        roundReasoning.setLength(0);
        send(b.build(), this::onStream);
    }

    private void onStream(Response response) {
        Map<Integer, String[]> partial = new TreeMap<>(); // index → {id, name, arguments}
        try (ResponseBody body = response.body()) {
            if (body == null) throw new IOException("Empty response from " + host());
            if (!response.isSuccessful()) {
                throw new IOException(host() + ": " + ApiErrors.message(body.string(),
                        "HTTP " + response.code() + " " + response.message()));
            }
            BufferedSource source = body.source();
            String line;
            while (!isCancelled() && (line = source.readUtf8Line()) != null) {
                JsonObject chunk = chunk(line);
                if (chunk == null) continue;
                if (chunk.has("error")) throw new IOException(host() + ": " + ApiErrors.message(chunk.toString(), "stream error"));
                readUsage(chunk);
                JsonArray choices = chunk.has("choices") && chunk.get("choices").isJsonArray()
                        ? chunk.getAsJsonArray("choices") : new JsonArray();
                for (JsonElement c : choices) {
                    JsonObject delta = c.isJsonObject() ? obj(c.getAsJsonObject(), "delta") : null;
                    if (delta == null) continue;
                    String reasoning = str(delta, "reasoning_content");
                    if (reasoning != null) roundReasoning.append(reasoning);
                    String text = str(delta, "content");
                    if (text != null && !text.isEmpty()) {
                        roundText.append(text);
                        delta(text);
                    }
                    JsonElement tc = delta.get("tool_calls");
                    if (tc != null && tc.isJsonArray()) collectToolCalls(tc.getAsJsonArray(), partial);
                }
            }
        } catch (Exception e) {
            fail(e);
            return;
        }
        onReply(finishToolCalls(partial));
    }

    /** The assistant's tool calls, then one {@code tool} message per result, in call order. */
    @Override
    void addToolTurn(List<FunctionCall> calls, List<ToolResult> results) {
        JsonObject assistant = new JsonObject();
        assistant.addProperty("role", "assistant");
        assistant.addProperty("content", roundText.toString());
        // Thinking models (DeepSeek) want their reasoning back within the same tool loop.
        if (roundReasoning.length() > 0) assistant.addProperty("reasoning_content", roundReasoning.toString());
        JsonArray tc = new JsonArray();
        for (FunctionCall fc : calls) {
            JsonObject fn = new JsonObject();
            fn.addProperty("name", fc.getName());
            fn.addProperty("arguments", fc.getArgs().toString());
            JsonObject call = new JsonObject();
            call.addProperty("id", fc.getId());
            call.addProperty("type", "function");
            call.add("function", fn);
            tc.add(call);
        }
        assistant.add("tool_calls", tc);
        messages.add(assistant);
        for (int i = 0; i < calls.size(); i++) {
            ToolResult r = results.get(i);
            JsonObject m = new JsonObject();
            m.addProperty("role", "tool");
            m.addProperty("tool_call_id", calls.get(i).getId());
            m.addProperty("content", r == null ? "Error: no result" : r.content);
            messages.add(m);
        }
    }

    // ---------------------------------------------------------------- wire format (tested)

    /** Chat history → OpenAI messages ("model" becomes "assistant"). */
    static JsonArray toMessages(List<AiChatMessage> history) {
        JsonArray out = new JsonArray();
        if (history == null) return out;
        for (AiChatMessage m : history) {
            if (m == null) continue;
            String role = m.role == null ? "user" : m.role.trim().toLowerCase(Locale.ROOT);
            if ("model".equals(role)) role = "assistant";
            else if (!"assistant".equals(role) && !"system".equals(role)) role = "user";
            JsonObject o = new JsonObject();
            o.addProperty("role", role);
            o.addProperty("content", m.content != null ? m.content : "");
            out.add(o);
        }
        return out;
    }

    /** The lyric tools as OpenAI functions; Gemini's upper-case schema types become JSON Schema. */
    static JsonArray toolDeclarations(boolean webTools, boolean webSearch) {
        JsonArray out = new JsonArray();
        for (JsonElement d : LyricTools.declarations(webTools)) {
            JsonObject g = d.getAsJsonObject();
            if (!webSearch && "web_search".equals(g.get("name").getAsString())) continue;
            JsonObject fn = new JsonObject();
            fn.add("name", g.get("name"));
            fn.add("description", g.get("description"));
            fn.add("parameters", jsonSchema(g.get("parameters")));
            JsonObject t = new JsonObject();
            t.addProperty("type", "function");
            t.add("function", fn);
            out.add(t);
        }
        return out;
    }

    private static JsonElement jsonSchema(JsonElement e) {
        if (e == null || !e.isJsonObject()) return e;
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> kv : e.getAsJsonObject().entrySet()) {
            JsonElement v = kv.getValue();
            if ("type".equals(kv.getKey()) && v.isJsonPrimitive()) {
                out.addProperty("type", v.getAsString().toLowerCase(Locale.ROOT));
            } else if ("properties".equals(kv.getKey()) && v.isJsonObject()) {
                JsonObject props = new JsonObject();
                for (Map.Entry<String, JsonElement> p : v.getAsJsonObject().entrySet()) props.add(p.getKey(), jsonSchema(p.getValue()));
                out.add("properties", props);
            } else {
                out.add(kv.getKey(), v.isJsonObject() ? jsonSchema(v) : v);
            }
        }
        return out;
    }

    /** Streamed tool calls arrive in pieces keyed by index: the id and name once, arguments in parts. */
    static void collectToolCalls(JsonArray deltas, Map<Integer, String[]> partial) {
        for (JsonElement el : deltas) {
            if (!el.isJsonObject()) continue;
            JsonObject d = el.getAsJsonObject();
            int index = d.has("index") && d.get("index").isJsonPrimitive() ? d.get("index").getAsInt() : partial.size();
            String[] acc = partial.get(index);
            if (acc == null) partial.put(index, acc = new String[]{null, "", ""});
            String id = str(d, "id");
            if (id != null && !id.isEmpty()) acc[0] = id;
            JsonObject fn = obj(d, "function");
            String name = str(fn, "name");
            if (name != null) acc[1] += name;
            String args = str(fn, "arguments");
            if (args != null) acc[2] += args;
        }
    }

    static List<FunctionCall> finishToolCalls(Map<Integer, String[]> partial) {
        List<FunctionCall> calls = new ArrayList<>();
        for (Map.Entry<Integer, String[]> e : partial.entrySet()) {
            String[] acc = e.getValue();
            if (acc[1].trim().isEmpty()) continue;
            JsonObject args;
            try {
                JsonElement parsed = JsonParser.parseString(acc[2].trim().isEmpty() ? "{}" : acc[2]);
                args = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
            } catch (Exception malformed) {
                args = new JsonObject(); // the tool reports what is missing
            }
            String id = acc[0] != null ? acc[0] : "call_" + e.getKey();
            calls.add(new FunctionCall(acc[1].trim(), args, id, null));
        }
        return calls;
    }

    private void readUsage(JsonObject chunk) {
        JsonObject usage = obj(chunk, "usage");
        if (usage == null) return;
        Integer p = integer(usage, "prompt_tokens"), c = integer(usage, "completion_tokens");
        if (p != null && p > 0) promptTokens = p;
        if (c != null && c > 0) candidateTokens = c;
    }

    private String host() {
        okhttp3.HttpUrl u = okhttp3.HttpUrl.parse(provider.baseUrl);
        return u != null ? u.host() : "AI provider";
    }

    private static JsonObject chunk(String line) {
        String p = line == null ? "" : line.trim();
        if (p.startsWith("data:")) p = p.substring(5).trim();
        if (p.isEmpty() || p.startsWith(":") || "[DONE]".equals(p) || !p.startsWith("{")) return null;
        try {
            return JsonParser.parseString(p).getAsJsonObject();
        } catch (Exception malformed) {
            return null;
        }
    }

    private static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static Integer integer(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            return e != null && e.isJsonPrimitive() ? e.getAsInt() : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
