package com.lyricsync.app.ai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.ai.auth.AntigravityAuthManager;
import com.lyricsync.app.ai.model.AiChatMessage;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor.SourceItem;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor.ToolCallback;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor.ToolResult;
import com.lyricsync.app.ai.tools.ToolArgs;
import com.lyricsync.app.ai.util.ApiErrors;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * Gemini chat over the Antigravity Cloudcode PA endpoint: SSE streaming with up to
 * {@link #MAX_TOOL_ITERATIONS} tool round-trips (web search/fetch and app actions).
 *
 * <p>One request = one {@link Turn}: stream a response; if it asks for function calls, run
 * them in order, append the calls and their results to the conversation, and stream again. Listener
 * callbacks are isolated — a throwing listener never turns success into an error.
 */
public class AntigravityApiClient {

    public static final String ENDPOINT_STREAM = "https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse";
    public static final String USER_AGENT = "antigravity/ide/2.11.0 darwin/arm64";
    public static final String DEFAULT_PROJECT = "aicode-consumers";
    public static final String DEFAULT_REQUEST_TYPE = "agent";
    public static final String DEFAULT_USER_AGENT_FIELD = "antigravity";
    /** Tool round-trips per request: enough to look, edit and verify, bounded against loops. */
    public static final int MAX_TOOL_ITERATIONS = 8;

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    /** Gemini 3 rejects a replayed function call without a signature; this sentinel is accepted. */
    private static final String SKIP_SIGNATURE = "skip_thought_signature_validator";

    /** Handle to an in-flight request. */
    public interface Cancellable {
        void cancel();

        boolean isCancelled();
    }

    public interface StreamListener {
        default void onDelta(String textDelta) {
            onChunk(textDelta);
        }

        default void onChunk(String text) {
        }

        default void onToolExecuting(String toolName, String queryOrUrl) {
        }

        default void onToolCompleted(String toolName, int resultsCount) {
        }

        /** {@code isError}: the tool failed; the model gets the error and carries on. */
        default void onToolCompleted(String toolName, int resultsCount, boolean isError) {
            onToolCompleted(toolName, resultsCount);
        }

        default void onComplete(String fullResponse, List<String> webSources) {
        }

        default void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
            onComplete(fullText, webSources);
        }

        void onError(Exception error);
    }

    public interface CompletionCallback {
        void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens);

        void onError(Exception error);
    }

    /** A function call from the model; {@code rawPart} is replayed verbatim on the next turn. */
    public static class FunctionCall {
        public final String name;
        public final JsonObject args;
        public final String id;
        public final String thoughtSignature;
        public final JsonObject rawPart;

        public FunctionCall(String name, JsonObject args) {
            this(name, args, null, null, null);
        }

        public FunctionCall(String name, JsonObject args, String id, String thoughtSignature) {
            this(name, args, id, thoughtSignature, null);
        }

        public FunctionCall(String name, JsonObject args, String id, String thoughtSignature, JsonObject rawPart) {
            this.name = name != null ? name : "";
            this.args = args != null ? args : new JsonObject();
            this.id = id;
            this.thoughtSignature = thoughtSignature;
            this.rawPart = rawPart;
        }

        public String getName() {
            return name;
        }

        public JsonObject getArgs() {
            return args;
        }

        public String getId() {
            return id;
        }

        public String getThoughtSignature() {
            return thoughtSignature;
        }

        public JsonObject getRawPart() {
            return rawPart;
        }
    }

    /** One parsed SSE frame. */
    public static class SseChunkResult {
        public String textDelta;
        public String thoughtSignature;
        public final List<String> webSearchQueries = new ArrayList<>();
        /** The first call of this frame (convenience); {@link #functionCalls} has them all. */
        public FunctionCall functionCall;
        public final List<FunctionCall> functionCalls = new ArrayList<>();
        public int promptTokens = 0;
        public int candidateTokens = 0;
        public String errorMessage;
    }

    private final AntigravityAuthManager authManager;
    private final OkHttpClient httpClient;
    private final AntigravityToolExecutor toolExecutor;
    private volatile LyricActionDelegate lyricActionDelegate;

    public AntigravityApiClient(AntigravityAuthManager authManager) {
        this(authManager, null, null);
    }

    public AntigravityApiClient(AntigravityAuthManager authManager, OkHttpClient httpClient) {
        this(authManager, httpClient, null);
    }

    public AntigravityApiClient(AntigravityAuthManager authManager, OkHttpClient httpClient,
                                AntigravityToolExecutor toolExecutor) {
        this.authManager = authManager;
        this.httpClient = httpClient != null ? httpClient : new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        this.toolExecutor = toolExecutor != null ? toolExecutor : new AntigravityToolExecutor(authManager, this.httpClient);
    }

    public void setLyricActionDelegate(LyricActionDelegate delegate) {
        this.lyricActionDelegate = delegate;
    }

    public boolean isAuthenticated() {
        return authManager != null && authManager.isAuthenticated();
    }

    // ---------------------------------------------------------------- request building

    public static JsonArray buildToolDeclarations() {
        return buildToolDeclarations(true);
    }

    public static JsonArray buildToolDeclarations(boolean enableWebSearch) {
        return LyricTools.declarations(enableWebSearch);
    }

    /** Chat turns → Gemini "contents" ("assistant" becomes "model", a missing role "user"). */
    static JsonArray convertMessagesToContents(List<AiChatMessage> messages) {
        JsonArray contents = new JsonArray();
        if (messages == null) return contents;
        for (AiChatMessage msg : messages) {
            if (msg == null) continue;
            String role = msg.role == null || msg.role.trim().isEmpty() ? "user" : msg.role.trim().toLowerCase(Locale.ROOT);
            if ("assistant".equals(role)) role = "model";
            JsonObject part = new JsonObject();
            part.addProperty("text", msg.content != null ? msg.content : "");
            contents.add(turn(role, part));
        }
        return contents;
    }

    /** The request envelope. Lyric tools are always offered; web tools only when enabled. */
    public static String buildRequestBodyWithContents(String modelId, JsonArray contents,
                                                      boolean enableTools, String effortLevel) {
        JsonObject request = new JsonObject();
        request.add("contents", contents != null ? contents : new JsonArray());
        JsonObject tool = new JsonObject();
        tool.add("functionDeclarations", buildToolDeclarations(enableTools));
        JsonArray tools = new JsonArray();
        tools.add(tool);
        request.add("tools", tools);
        if (effortLevel != null && !effortLevel.trim().isEmpty() && !"none".equalsIgnoreCase(effortLevel.trim())) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("includeThoughts", false);
            thinking.addProperty("thinkingLevel", effortLevel.trim().toLowerCase(Locale.ROOT));
            JsonObject generation = new JsonObject();
            generation.add("thinkingConfig", thinking);
            request.add("generationConfig", generation);
        }

        JsonObject root = new JsonObject();
        root.addProperty("project", DEFAULT_PROJECT);
        root.addProperty("model", modelId != null ? modelId : "");
        root.addProperty("userAgent", DEFAULT_USER_AGENT_FIELD);
        root.addProperty("requestType", DEFAULT_REQUEST_TYPE);
        root.addProperty("requestId", "agent-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8));
        root.add("request", request);
        return root.toString();
    }

    public static String extractThoughtSignature(JsonObject obj) {
        if (obj == null) return null;
        for (String key : new String[]{"thought_signature", "thoughtSignature", "signature"}) {
            JsonElement el = obj.get(key);
            if (el != null && el.isJsonPrimitive()) return el.getAsString();
        }
        return null;
    }

    // ---------------------------------------------------------------- SSE parsing

    /**
     * Parses one SSE line ("data: {...}") or raw JSON frame. Returns null for heartbeats,
     * comments, "[DONE]", malformed JSON and frames with nothing useful.
     */
    public static SseChunkResult parseSseLine(String line) {
        if (line == null) return null;
        String payload = line.trim();
        if (payload.isEmpty() || payload.startsWith(":")) return null;
        if (payload.startsWith("data:")) payload = payload.substring(5).trim();
        if (payload.isEmpty() || "[DONE]".equalsIgnoreCase(payload)) return null;

        JsonObject root;
        try {
            JsonElement e = JsonParser.parseString(payload);
            if (!e.isJsonObject()) return null;
            root = e.getAsJsonObject();
        } catch (Exception malformed) {
            return null;
        }
        // Cloudcode PA wraps the Gemini response in {"response": {...}}.
        JsonObject resp = obj(root, "response") != null ? obj(root, "response") : root;

        JsonElement error = resp.has("error") ? resp.get("error") : root.get("error");
        if (error != null) {
            SseChunkResult r = new SseChunkResult();
            r.errorMessage = error.isJsonObject() ? ApiErrors.message(error.toString(), "In-band stream error")
                    : error.isJsonPrimitive() ? error.getAsString() : "In-band stream error";
            return r;
        }

        SseChunkResult r = new SseChunkResult();
        boolean useful = false;
        for (JsonElement c : arr(resp, "candidates")) {
            if (!c.isJsonObject()) continue;
            JsonObject cand = c.getAsJsonObject();
            for (JsonElement p : arr(obj(cand, "content"), "parts")) {
                if (!p.isJsonObject()) continue;
                JsonObject part = p.getAsJsonObject();
                String text = str(part, "text");
                if (text != null) {
                    r.textDelta = r.textDelta == null ? text : r.textDelta + text;
                    useful = true;
                }
                String sig = extractThoughtSignature(part);
                if (sig != null) r.thoughtSignature = sig;
                JsonObject fc = obj(part, "functionCall");
                String name = fc == null ? null : str(fc, "name");
                if (name != null && !name.trim().isEmpty()) {
                    String fcSig = extractThoughtSignature(fc);
                    if (fcSig == null) fcSig = r.thoughtSignature;
                    FunctionCall call = new FunctionCall(name.trim(), obj(fc, "args"), str(fc, "id"), fcSig, part);
                    r.functionCalls.add(call);
                    if (r.functionCall == null) r.functionCall = call;
                    useful = true;
                }
            }
            useful |= grounding(obj(cand, "groundingMetadata"), r);
        }
        useful |= grounding(obj(resp, "groundingMetadata") != null ? obj(resp, "groundingMetadata")
                : obj(root, "groundingMetadata"), r);

        JsonObject usage = obj(resp, "usageMetadata") != null ? obj(resp, "usageMetadata") : obj(root, "usageMetadata");
        if (usage != null) {
            Integer prompt = firstInt(usage, "promptTokenCount", "promptTokens");
            Integer cand = firstInt(usage, "candidatesTokenCount", "candidatesTokens", "candidateTokens");
            if (prompt != null) r.promptTokens = prompt;
            if (cand != null) r.candidateTokens = cand;
            useful |= prompt != null || cand != null;
        }
        return useful ? r : null;
    }

    /** Search queries and cited pages ("title|url", title, or url) into {@code r.webSearchQueries}. */
    private static boolean grounding(JsonObject g, SseChunkResult r) {
        if (g == null) return false;
        boolean found = false;
        for (JsonElement q : arr(g, "webSearchQueries")) {
            if (q.isJsonPrimitive()) found |= addOnce(r.webSearchQueries, q.getAsString().trim());
        }
        for (JsonElement c : arr(g, "groundingChunks")) {
            JsonObject web = c.isJsonObject() ? obj(c.getAsJsonObject(), "web") : null;
            if (web == null) continue;
            String title = trimmed(str(web, "title")), uri = trimmed(str(web, "uri"));
            if (title != null && uri != null && !title.equalsIgnoreCase(uri)) {
                found |= addOnce(r.webSearchQueries, title + "|" + uri);
                addOnce(r.webSearchQueries, title);
            } else if (title != null) {
                found |= addOnce(r.webSearchQueries, title);
            } else if (uri != null) {
                found |= addOnce(r.webSearchQueries, uri);
            }
        }
        return found;
    }

    // ---------------------------------------------------------------- public calls

    public Cancellable generateContentStream(String model, List<AiChatMessage> history,
                                            String userPrompt, boolean enableTools, StreamListener listener) {
        List<AiChatMessage> messages = new ArrayList<>();
        if (history != null) messages.addAll(history);
        if (userPrompt != null && !userPrompt.trim().isEmpty()) messages.add(AiChatMessage.user(userPrompt.trim()));
        return generateContentStream(model, messages, enableTools, null, listener);
    }

    /** Streams a reply, running tools as the model asks for them. */
    public Cancellable generateContentStream(final String modelId, final List<AiChatMessage> messages,
                                            final boolean enableWebSearch, final String effortLevel,
                                            final StreamListener listener) {
        final Turn turn = new Turn(modelId, convertMessagesToContents(messages), enableWebSearch, effortLevel, listener);
        if (authManager == null) {
            turn.fail(new IllegalStateException("AntigravityAuthManager is null"));
            return turn;
        }
        try {
            authManager.getValidAccessToken(new AntigravityAuthManager.TokenCallback() {
                @Override
                public void onTokenReady(String accessToken) {
                    turn.token = accessToken;
                    turn.stream();
                }

                @Override
                public void onError(String message) {
                    turn.fail(new IOException("Authentication failed: " + message));
                }
            });
        } catch (Exception e) {
            turn.fail(new IOException("Failed to acquire access token: " + e.getMessage(), e));
        }
        return turn;
    }

    /** Non-streaming convenience (batch translation/transliteration). */
    public Cancellable generateContent(String modelId, List<AiChatMessage> messages,
                                      boolean enableWebSearch, String effortLevel,
                                      final CompletionCallback callback) {
        return generateContentStream(modelId, messages, enableWebSearch, effortLevel, new StreamListener() {
            @Override
            public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                if (callback != null) callback.onComplete(fullText, webSources, promptTokens, candidateTokens);
            }

            @Override
            public void onError(Exception error) {
                if (callback != null) callback.onError(error);
            }
        });
    }

    // ---------------------------------------------------------------- one conversation turn

    /** State of one request across its tool round-trips; also the caller's cancel handle. */
    final class Turn implements Cancellable {
        private final String modelId;
        private final JsonArray contents;
        private final boolean webTools;
        private final String effort;
        private final StreamListener listener;
        private final StringBuilder text = new StringBuilder();
        private final List<String> sources = new ArrayList<>();
        private volatile String token;
        private volatile boolean cancelled;
        private volatile boolean finished;
        private volatile Call call;
        private volatile Cancellable tool;
        private int toolRounds;
        private int promptTokens;
        private int candidateTokens;

        Turn(String modelId, JsonArray contents, boolean webTools, String effort, StreamListener listener) {
            this.modelId = modelId;
            this.contents = contents;
            this.webTools = webTools;
            this.effort = effort;
            this.listener = listener;
        }

        @Override
        public void cancel() {
            cancelled = true;
            Call c = call;
            Cancellable t = tool;
            if (c != null) c.cancel();
            if (t != null) {
                try {
                    t.cancel();
                } catch (Exception ignored) {
                }
            }
        }

        @Override
        public boolean isCancelled() {
            Call c = call;
            Cancellable t = tool;
            return cancelled || (c != null && c.isCanceled()) || (t != null && t.isCancelled());
        }

        void stream() {
            if (isCancelled()) return;
            Request request = new Request.Builder()
                    .url(ENDPOINT_STREAM)
                    .addHeader("Authorization", "Bearer " + token)
                    .addHeader("User-Agent", USER_AGENT)
                    .addHeader("Accept", "text/event-stream")
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(JSON, buildRequestBodyWithContents(modelId, contents, webTools, effort)))
                    .build();
            try {
                Call c = httpClient.newCall(request);
                call = c;
                if (cancelled) c.cancel();
                c.enqueue(new Callback() {
                    @Override
                    public void onFailure(Call c, IOException e) {
                        fail(e);
                    }

                    @Override
                    public void onResponse(Call c, Response response) {
                        onStream(response);
                    }
                });
            } catch (Exception e) {
                fail(new IOException("Failed to initiate stream request: " + e.getMessage(), e));
            }
        }

        private void onStream(Response response) {
            List<FunctionCall> calls = new ArrayList<>();
            String streamSig = null;
            try (ResponseBody body = response.body()) {
                if (body == null) throw new IOException("Empty response body from Antigravity API");
                if (!response.isSuccessful()) {
                    throw new IOException("Antigravity API request failed: " + ApiErrors.message(body.string(),
                            "HTTP " + response.code() + ": " + response.message()));
                }
                BufferedSource source = body.source();
                String line;
                while (!isCancelled() && (line = source.readUtf8Line()) != null) {
                    SseChunkResult chunk = parseSseLine(line);
                    if (chunk == null) continue;
                    if (chunk.errorMessage != null) throw new IOException("Antigravity API stream error: " + chunk.errorMessage);
                    if (chunk.thoughtSignature != null) streamSig = chunk.thoughtSignature;
                    calls.addAll(chunk.functionCalls);
                    if (chunk.textDelta != null && !chunk.textDelta.isEmpty()) {
                        text.append(chunk.textDelta);
                        notify(() -> listener.onDelta(chunk.textDelta));
                    }
                    for (String s : chunk.webSearchQueries) addOnce(sources, s);
                    if (chunk.promptTokens > 0) promptTokens = chunk.promptTokens;
                    if (chunk.candidateTokens > 0) candidateTokens = chunk.candidateTokens;
                }
            } catch (Exception e) {
                fail(e);
                return;
            }
            if (isCancelled()) return;
            if (calls.isEmpty() || toolRounds >= MAX_TOOL_ITERATIONS) {
                complete(); // no tool call, or the loop guard: answer with what we have
                return;
            }
            toolRounds++;
            contents.add(callTurn(calls, streamSig));
            runTools(calls, 0, new JsonArray());
        }

        /** Runs {@code calls} one after another (they may depend on each other), then streams again. */
        private void runTools(List<FunctionCall> calls, int i, JsonArray responses) {
            if (isCancelled()) return;
            if (i >= calls.size()) {
                JsonObject t = new JsonObject();
                t.addProperty("role", "user");
                t.add("parts", responses);
                contents.add(t);
                stream();
                return;
            }
            FunctionCall fc = calls.get(i);
            String name = fc.getName();
            JsonObject args = fc.getArgs();
            notify(() -> listener.onToolExecuting(name, LyricTools.describe(name, args)));
            ToolCallback done = result -> {
                if (isCancelled()) return;
                boolean error = result == null || result.isError;
                int count = result == null ? 0
                        : result.sources != null && !result.sources.isEmpty() ? result.sources.size()
                        : error ? 0 : 1;
                notify(() -> listener.onToolCompleted(name, count, error));
                if (result != null && result.sources != null) {
                    for (SourceItem s : result.sources) addSource(s);
                }
                responses.add(responsePart(fc, result));
                runTools(calls, i + 1, responses);
            };
            try {
                if ("web_search".equals(name)) {
                    tool = toolExecutor.executeWebSearch(ToolArgs.str(args, "query", ""),
                            ToolArgs.intValue(args, "num_search_results", 5), done);
                } else if ("web_fetch".equals(name)) {
                    tool = toolExecutor.executeWebFetch(ToolArgs.str(args, "url", ""), done);
                } else {
                    LyricTools.dispatch(lyricActionDelegate, name, args, done);
                }
                if (cancelled && tool != null) tool.cancel();
            } catch (Exception e) {
                done.onResult(ToolResult.error("Tool execution error: " + e.getMessage()));
            }
        }

        private void addSource(SourceItem s) {
            if (s == null) return;
            String url = s.url != null ? s.url.trim() : "", title = s.title != null ? s.title.trim() : "";
            if (!url.isEmpty()) addOnce(sources, url);
            if (!title.isEmpty() && !url.isEmpty() && !title.equalsIgnoreCase(url)) addOnce(sources, title + "|" + url);
            else if (!title.isEmpty()) addOnce(sources, title);
        }

        private void complete() {
            if (finished || isCancelled()) return;
            finished = true;
            String full = text.toString();
            notify(() -> listener.onComplete(full, sources, promptTokens, candidateTokens));
        }

        void fail(Exception e) {
            if (finished || isCancelled()) return; // a cancelled call ends silently
            finished = true;
            notify(() -> listener.onError(e));
        }

        private void notify(Runnable r) {
            if (listener == null) return;
            try {
                r.run();
            } catch (Throwable ignored) {
                // a broken listener must not abort the stream
            }
        }
    }

    // ---------------------------------------------------------------- tool turns

    /**
     * The model's function calls, replayed in one model turn. Gemini 3 validates the thought
     * signature of the first call only; it goes on the part, never inside functionCall
     * (whose proto only has name/args/id). Parallel calls after it are replayed as sent.
     */
    static JsonObject callTurn(List<FunctionCall> calls, String streamSig) {
        JsonArray parts = new JsonArray();
        for (int i = 0; i < calls.size(); i++) {
            FunctionCall fc = calls.get(i);
            parts.add(i == 0 ? signedPart(fc, streamSig) : rawPart(fc));
        }
        JsonObject t = new JsonObject();
        t.addProperty("role", "model");
        t.add("parts", parts);
        return t;
    }

    private static JsonObject rawPart(FunctionCall fc) {
        if (fc.getRawPart() != null) return fc.getRawPart().deepCopy();
        JsonObject call = new JsonObject();
        call.addProperty("name", fc.getName());
        if (fc.getId() != null && !fc.getId().trim().isEmpty()) call.addProperty("id", fc.getId().trim());
        call.add("args", fc.getArgs());
        JsonObject part = new JsonObject();
        part.add("functionCall", call);
        return part;
    }

    private static JsonObject signedPart(FunctionCall fc, String streamSig) {
        JsonObject part = rawPart(fc);
        JsonObject inner = obj(part, "functionCall");
        String sig = firstNonBlank(fc.getThoughtSignature(), streamSig, extractThoughtSignature(part),
                extractThoughtSignature(inner), SKIP_SIGNATURE).trim();
        if (inner != null) {
            inner.remove("thought_signature");
            inner.remove("thoughtSignature");
            inner.remove("signature");
        }
        part.addProperty("thought_signature", sig);
        part.addProperty("thoughtSignature", sig);
        return part;
    }

    /** One tool's output. The turn carrying it has role "user" (Cloudcode PA rejects "function"/"tool"). */
    static JsonObject responsePart(FunctionCall fc, ToolResult result) {
        String raw = result != null && result.content != null ? result.content : "";
        JsonObject response = new JsonObject();
        try {
            response.add("content", JsonParser.parseString(raw));
        } catch (Exception notJson) {
            response.addProperty("content", raw);
        }
        JsonObject fr = new JsonObject();
        fr.addProperty("name", fc.getName());
        if (fc.getId() != null && !fc.getId().trim().isEmpty()) fr.addProperty("id", fc.getId().trim());
        fr.add("response", response);
        JsonObject part = new JsonObject();
        part.add("functionResponse", fr);
        return part;
    }

    // ---------------------------------------------------------------- JSON helpers

    private static JsonObject turn(String role, JsonObject part) {
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject t = new JsonObject();
        t.addProperty("role", role);
        t.add("parts", parts);
        return t;
    }

    private static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static Integer firstInt(JsonObject o, String... keys) {
        for (String k : keys) {
            JsonElement e = o.get(k);
            if (e != null && e.isJsonPrimitive()) {
                try {
                    return e.getAsInt();
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private static String trimmed(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static String firstNonBlank(String... xs) {
        for (String x : xs) if (x != null && !x.trim().isEmpty()) return x;
        return "";
    }

    private static boolean addOnce(List<String> list, String s) {
        if (s == null || s.isEmpty() || list.contains(s)) return false;
        list.add(s);
        return true;
    }
}
