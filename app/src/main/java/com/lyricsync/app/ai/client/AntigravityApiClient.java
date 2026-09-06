package com.lyricsync.app.ai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.ai.auth.AntigravityAuthManager;
import com.lyricsync.app.ai.model.AiChatMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * Antigravity AI Completion Client providing Server-Sent Events (SSE) streaming,
 * Google Search Grounding tool integration, dynamic reasoning effort control,
 * and token usage tracking via Google Antigravity Cloudcode PA endpoints.
 *
 * Adheres strictly to uncapped model native token limits without artificial clamps.
 */
public class AntigravityApiClient {

    public static final String ENDPOINT_STREAM = "https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse";
    public static final String USER_AGENT = "antigravity/ide/2.11.0 darwin/arm64";
    public static final String DEFAULT_PROJECT = "aicode-consumers";
    public static final String DEFAULT_REQUEST_TYPE = "agent";
    public static final String DEFAULT_USER_AGENT_FIELD = "antigravity";

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    /**
     * Handle representing an active AI API call that can be cancelled.
     */
    public interface Cancellable {
        void cancel();
        boolean isCancelled();
    }

    public interface StreamListener {
        void onDelta(String textDelta);
        void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens);
        void onError(Exception error);
    }

    public interface CompletionCallback {
        void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens);
        void onError(Exception error);
    }

    /**
     * Holds parsed fields from an individual SSE event data frame.
     */
    public static class SseChunkResult {
        public String textDelta;
        public final List<String> webSearchQueries = new ArrayList<>();
        public int promptTokens = 0;
        public int candidateTokens = 0;
        public int totalTokens = 0;
        public String finishReason;
        public String errorMessage;
    }

    private final AntigravityAuthManager authManager;
    private final OkHttpClient httpClient;
    private final Set<Call> activeCalls = Collections.newSetFromMap(new ConcurrentHashMap<Call, Boolean>());

    public AntigravityApiClient(AntigravityAuthManager authManager) {
        this(authManager, createDefaultHttpClient());
    }

    public AntigravityApiClient(AntigravityAuthManager authManager, OkHttpClient httpClient) {
        this.authManager = authManager;
        this.httpClient = httpClient != null ? httpClient : createDefaultHttpClient();
    }

    private static OkHttpClient createDefaultHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Cancels all active in-flight calls managed by this client.
     */
    public void cancelAll() {
        for (Call call : activeCalls) {
            try {
                call.cancel();
            } catch (Exception ignored) {}
        }
        activeCalls.clear();
    }

    /**
     * Constructs the JSON request body matching the Antigravity Cloudcode PA protocol.
     * Leaves token limits to native model capacity without artificial maxOutputTokens clamping.
     *
     * @param modelId Model identifier (e.g. "gemini-3-flash", "claude-sonnet-4-6")
     * @param messages Conversation turns
     * @param enableWebSearch When true, injects googleSearch tool
     * @param effortLevel Optional reasoning effort level ("low", "medium", "high")
     * @return Formatted JSON payload string
     */
    public static String buildRequestBody(String modelId, List<AiChatMessage> messages,
                                         boolean enableWebSearch, String effortLevel) {
        JsonObject root = new JsonObject();
        root.addProperty("project", DEFAULT_PROJECT);
        root.addProperty("model", modelId != null ? modelId : "");
        root.addProperty("userAgent", DEFAULT_USER_AGENT_FIELD);
        root.addProperty("requestType", DEFAULT_REQUEST_TYPE);
        root.addProperty("requestId", "agent-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8));

        JsonObject request = new JsonObject();

        // Build contents array
        JsonArray contents = new JsonArray();
        if (messages != null) {
            for (AiChatMessage msg : messages) {
                if (msg == null) continue;
                JsonObject msgObj = new JsonObject();

                // Normalize role: null -> "user", "assistant" -> "model"
                String role = "user";
                if (msg.role != null && !msg.role.trim().isEmpty()) {
                    String trimmedRole = msg.role.trim().toLowerCase();
                    if ("assistant".equals(trimmedRole)) {
                        role = "model";
                    } else {
                        role = trimmedRole;
                    }
                }
                msgObj.addProperty("role", role);

                JsonArray parts = new JsonArray();
                JsonObject part = new JsonObject();
                part.addProperty("text", msg.content != null ? msg.content : "");
                parts.add(part);

                msgObj.add("parts", parts);
                contents.add(msgObj);
            }
        }
        request.add("contents", contents);

        // Inject Google Search grounding tool if enabled
        if (enableWebSearch) {
            JsonArray tools = new JsonArray();
            JsonObject tool = new JsonObject();
            tool.add("googleSearch", new JsonObject());
            tools.add(tool);
            request.add("tools", tools);
        }

        // Inject reasoning effort config if specified (uncapped maxOutputTokens)
        if (effortLevel != null && !effortLevel.trim().isEmpty() && !"none".equalsIgnoreCase(effortLevel.trim())) {
            JsonObject generationConfig = new JsonObject();
            JsonObject thinkingConfig = new JsonObject();
            thinkingConfig.addProperty("includeThoughts", false);
            thinkingConfig.addProperty("thinkingLevel", effortLevel.trim().toLowerCase());
            generationConfig.add("thinkingConfig", thinkingConfig);
            request.add("generationConfig", generationConfig);
        }

        root.add("request", request);
        return root.toString();
    }

    /**
     * Parses an individual SSE data line or raw JSON chunk into an SseChunkResult.
     * Extracts text delta from candidates[0].content.parts[0].text,
     * grounding search queries from groundingMetadata.webSearchQueries,
     * in-band error frames, and token metrics from usageMetadata.
     *
     * Resilient against malformed, comment, or non-data lines.
     *
     * @param line Raw SSE line (e.g. "data: {...}") or raw JSON string
     * @return SseChunkResult or null if line contains no relevant data or is malformed
     */
    public static SseChunkResult parseSseLine(String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith(":")) {
            return null; // Heartbeat or comment
        }

        String jsonPayload = trimmed;
        if (trimmed.startsWith("data:")) {
            jsonPayload = trimmed.substring(5).trim();
        }
        if (jsonPayload.isEmpty() || "[DONE]".equalsIgnoreCase(jsonPayload)) {
            return null;
        }

        try {
            JsonElement rootElem = JsonParser.parseString(jsonPayload);
            if (!rootElem.isJsonObject()) {
                return null;
            }

            JsonObject root = rootElem.getAsJsonObject();
            // Handle both Cloudcode PA wrapper ("response": {...}) and direct Gemini response
            JsonObject resp = root.has("response") && root.get("response").isJsonObject()
                    ? root.getAsJsonObject("response")
                    : root;

            // In-band SSE error detection
            if (resp.has("error") || root.has("error")) {
                JsonElement errorElem = resp.has("error") ? resp.get("error") : root.get("error");
                SseChunkResult errResult = new SseChunkResult();
                if (errorElem.isJsonObject()) {
                    errResult.errorMessage = parseErrorMessage(errorElem.toString(), "In-band stream error");
                } else if (errorElem.isJsonPrimitive()) {
                    errResult.errorMessage = errorElem.getAsString();
                } else {
                    errResult.errorMessage = "In-band stream error";
                }
                return errResult;
            }

            SseChunkResult result = new SseChunkResult();
            boolean hasMeaningfulData = false;

            // 1. Extract candidates text and candidate-level metadata
            if (resp.has("candidates") && resp.get("candidates").isJsonArray()) {
                JsonArray candidates = resp.getAsJsonArray("candidates");
                for (JsonElement candElem : candidates) {
                    if (!candElem.isJsonObject()) continue;
                    JsonObject cand = candElem.getAsJsonObject();

                    // Content parts text
                    if (cand.has("content") && cand.get("content").isJsonObject()) {
                        JsonObject content = cand.getAsJsonObject("content");
                        if (content.has("parts") && content.get("parts").isJsonArray()) {
                            JsonArray parts = content.getAsJsonArray("parts");
                            for (JsonElement partElem : parts) {
                                if (!partElem.isJsonObject()) continue;
                                JsonObject part = partElem.getAsJsonObject();
                                if (part.has("text") && !part.get("text").isJsonNull() && part.get("text").isJsonPrimitive()) {
                                    String text = part.get("text").getAsString();
                                    if (result.textDelta == null) {
                                        result.textDelta = text;
                                    } else {
                                        result.textDelta += text;
                                    }
                                    hasMeaningfulData = true;
                                }
                            }
                        }
                    }

                    // Finish reason
                    if (cand.has("finishReason") && !cand.get("finishReason").isJsonNull() && cand.get("finishReason").isJsonPrimitive()) {
                        result.finishReason = cand.get("finishReason").getAsString();
                    }

                    // Candidate groundingMetadata
                    if (cand.has("groundingMetadata") && cand.get("groundingMetadata").isJsonObject()) {
                        if (extractGrounding(cand.getAsJsonObject("groundingMetadata"), result)) {
                            hasMeaningfulData = true;
                        }
                    }
                }
            }

            // 2. Extract top-level / response-level grounding metadata
            if (resp.has("groundingMetadata") && resp.get("groundingMetadata").isJsonObject()) {
                if (extractGrounding(resp.getAsJsonObject("groundingMetadata"), result)) {
                    hasMeaningfulData = true;
                }
            } else if (root.has("groundingMetadata") && root.get("groundingMetadata").isJsonObject()) {
                if (extractGrounding(root.getAsJsonObject("groundingMetadata"), result)) {
                    hasMeaningfulData = true;
                }
            }

            // 3. Extract usage metadata
            JsonObject usage = null;
            if (resp.has("usageMetadata") && resp.get("usageMetadata").isJsonObject()) {
                usage = resp.getAsJsonObject("usageMetadata");
            } else if (root.has("usageMetadata") && root.get("usageMetadata").isJsonObject()) {
                usage = root.getAsJsonObject("usageMetadata");
            }

            if (usage != null) {
                // Prompt tokens
                if (usage.has("promptTokenCount") && !usage.get("promptTokenCount").isJsonNull() && usage.get("promptTokenCount").isJsonPrimitive()) {
                    result.promptTokens = usage.get("promptTokenCount").getAsInt();
                    hasMeaningfulData = true;
                } else if (usage.has("promptTokens") && !usage.get("promptTokens").isJsonNull() && usage.get("promptTokens").isJsonPrimitive()) {
                    result.promptTokens = usage.get("promptTokens").getAsInt();
                    hasMeaningfulData = true;
                }

                // Candidate tokens
                if (usage.has("candidatesTokenCount") && !usage.get("candidatesTokenCount").isJsonNull() && usage.get("candidatesTokenCount").isJsonPrimitive()) {
                    result.candidateTokens = usage.get("candidatesTokenCount").getAsInt();
                    hasMeaningfulData = true;
                } else if (usage.has("candidatesTokens") && !usage.get("candidatesTokens").isJsonNull() && usage.get("candidatesTokens").isJsonPrimitive()) {
                    result.candidateTokens = usage.get("candidatesTokens").getAsInt();
                    hasMeaningfulData = true;
                } else if (usage.has("candidateTokens") && !usage.get("candidateTokens").isJsonNull() && usage.get("candidateTokens").isJsonPrimitive()) {
                    result.candidateTokens = usage.get("candidateTokens").getAsInt();
                    hasMeaningfulData = true;
                }

                // Total tokens
                if (usage.has("totalTokenCount") && !usage.get("totalTokenCount").isJsonNull() && usage.get("totalTokenCount").isJsonPrimitive()) {
                    result.totalTokens = usage.get("totalTokenCount").getAsInt();
                } else if (usage.has("totalTokens") && !usage.get("totalTokens").isJsonNull() && usage.get("totalTokens").isJsonPrimitive()) {
                    result.totalTokens = usage.get("totalTokens").getAsInt();
                }
            }

            return hasMeaningfulData ? result : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean extractGrounding(JsonObject grounding, SseChunkResult result) {
        boolean found = false;
        if (grounding == null) return false;

        // Extract webSearchQueries array
        if (grounding.has("webSearchQueries") && grounding.get("webSearchQueries").isJsonArray()) {
            JsonArray queries = grounding.getAsJsonArray("webSearchQueries");
            for (JsonElement q : queries) {
                if (q != null && !q.isJsonNull() && q.isJsonPrimitive()) {
                    String queryText = q.getAsString();
                    if (queryText != null && !queryText.trim().isEmpty() && !result.webSearchQueries.contains(queryText.trim())) {
                        result.webSearchQueries.add(queryText.trim());
                        found = true;
                    }
                }
            }
        }

        // Also check groundingChunks for web source titles / URIs
        if (grounding.has("groundingChunks") && grounding.get("groundingChunks").isJsonArray()) {
            JsonArray chunks = grounding.getAsJsonArray("groundingChunks");
            for (JsonElement cElem : chunks) {
                if (!cElem.isJsonObject()) continue;
                JsonObject cObj = cElem.getAsJsonObject();
                if (cObj.has("web") && cObj.get("web").isJsonObject()) {
                    JsonObject web = cObj.getAsJsonObject("web");
                    String source = null;
                    if (web.has("title") && !web.get("title").isJsonNull() && web.get("title").isJsonPrimitive()) {
                        String title = web.get("title").getAsString();
                        if (title != null && !title.trim().isEmpty()) {
                            source = title.trim();
                        }
                    }
                    if (source == null && web.has("uri") && !web.get("uri").isJsonNull() && web.get("uri").isJsonPrimitive()) {
                        String uri = web.get("uri").getAsString();
                        if (uri != null && !uri.trim().isEmpty()) {
                            source = uri.trim();
                        }
                    }
                    if (source != null && !result.webSearchQueries.contains(source)) {
                        result.webSearchQueries.add(source);
                        found = true;
                    }
                }
            }
        }

        return found;
    }

    /**
     * Streams AI completions line-by-line via Server-Sent Events (SSE).
     * Returns a Cancellable handle to abort generation.
     */
    public Cancellable generateContentStream(final String modelId, final List<AiChatMessage> messages,
                                            final boolean enableWebSearch, final String effortLevel,
                                            final StreamListener listener) {
        final ApiCallHandle handle = new ApiCallHandle();

        if (authManager == null) {
            dispatchOnError(listener, new IllegalStateException("AntigravityAuthManager is null"));
            return handle;
        }

        try {
            authManager.getValidAccessToken(new AntigravityAuthManager.TokenCallback() {
                @Override
                public void onTokenReady(String accessToken) {
                    if (handle.isCancelled()) {
                        return;
                    }
                    executeStreamRequest(modelId, messages, enableWebSearch, effortLevel, accessToken, listener, handle);
                }

                @Override
                public void onError(String message) {
                    if (handle.isCancelled()) {
                        return;
                    }
                    dispatchOnError(listener, new IOException("Authentication failed: " + message));
                }
            });
        } catch (Exception e) {
            if (!handle.isCancelled()) {
                dispatchOnError(listener, new IOException("Failed to acquire access token: " + e.getMessage(), e));
            }
        }

        return handle;
    }

    private void executeStreamRequest(String modelId, List<AiChatMessage> messages,
                                      boolean enableWebSearch, String effortLevel,
                                      String accessToken, final StreamListener listener,
                                      final ApiCallHandle handle) {
        if (handle.isCancelled()) {
            return;
        }

        String payloadJson = buildRequestBody(modelId, messages, enableWebSearch, effortLevel);
        RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, payloadJson);

        Request request = new Request.Builder()
                .url(ENDPOINT_STREAM)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Accept", "text/event-stream")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try {
            final Call call = httpClient.newCall(request);
            handle.setCall(call);
            activeCalls.add(call);

            call.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    activeCalls.remove(call);
                    if (call.isCanceled() || handle.isCancelled()) {
                        return;
                    }
                    dispatchOnError(listener, e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        if (call.isCanceled() || handle.isCancelled()) {
                            return;
                        }

                        StringBuilder fullText = new StringBuilder();
                        List<String> webSources = new ArrayList<>();
                        int promptTokens = 0;
                        int candidateTokens = 0;

                        try (ResponseBody responseBody = response.body()) {
                            if (responseBody == null) {
                                if (call.isCanceled() || handle.isCancelled()) return;
                                dispatchOnError(listener, new IOException("Empty response body from Antigravity API"));
                                return;
                            }

                            if (!response.isSuccessful()) {
                                if (call.isCanceled() || handle.isCancelled()) return;
                                String errString = responseBody.string();
                                String parsedMsg = parseErrorMessage(errString, "HTTP " + response.code() + ": " + response.message());
                                dispatchOnError(listener, new IOException("Antigravity API request failed: " + parsedMsg));
                                return;
                            }

                            BufferedSource source = responseBody.source();
                            while (!source.exhausted()) {
                                if (call.isCanceled() || handle.isCancelled()) return;
                                String line = source.readUtf8Line();
                                if (line == null) break;

                                SseChunkResult chunk = parseSseLine(line);
                                if (chunk != null) {
                                    // Check for in-band error frame
                                    if (chunk.errorMessage != null) {
                                        if (call.isCanceled() || handle.isCancelled()) return;
                                        dispatchOnError(listener, new IOException("Antigravity API stream error: " + chunk.errorMessage));
                                        return;
                                    }

                                    if (chunk.textDelta != null && !chunk.textDelta.isEmpty()) {
                                        fullText.append(chunk.textDelta);
                                        dispatchOnDelta(listener, chunk.textDelta);
                                    }
                                    for (String src : chunk.webSearchQueries) {
                                        if (!webSources.contains(src)) {
                                            webSources.add(src);
                                        }
                                    }
                                    if (chunk.promptTokens > 0) {
                                        promptTokens = chunk.promptTokens;
                                    }
                                    if (chunk.candidateTokens > 0) {
                                        candidateTokens = chunk.candidateTokens;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            if (call.isCanceled() || handle.isCancelled()) {
                                return;
                            }
                            dispatchOnError(listener, e);
                            return;
                        }

                        if (call.isCanceled() || handle.isCancelled()) {
                            return;
                        }
                        // Invoke onComplete outside stream reading block to prevent consumer exceptions from routing to onError
                        dispatchOnComplete(listener, fullText.toString(), webSources, promptTokens, candidateTokens);
                    } finally {
                        activeCalls.remove(call);
                    }
                }
            });
        } catch (Exception e) {
            if (!handle.isCancelled()) {
                dispatchOnError(listener, new IOException("Failed to initiate stream request: " + e.getMessage(), e));
            }
        }
    }

    /**
     * Executes a non-streaming AI completion (useful for batch operations like translation).
     * Returns a Cancellable handle to abort generation.
     */
    public Cancellable generateContent(String modelId, List<AiChatMessage> messages,
                                      boolean enableWebSearch, String effortLevel,
                                      final CompletionCallback callback) {
        return generateContentStream(modelId, messages, enableWebSearch, effortLevel, new StreamListener() {
            @Override
            public void onDelta(String textDelta) {
                // Ignore chunk deltas for batch completions
            }

            @Override
            public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                if (callback != null) {
                    try {
                        callback.onComplete(fullText, webSources, promptTokens, candidateTokens);
                    } catch (Throwable ignored) {}
                }
            }

            @Override
            public void onError(Exception error) {
                if (callback != null) {
                    try {
                        callback.onError(error);
                    } catch (Throwable ignored) {}
                }
            }
        });
    }

    private static void dispatchOnDelta(StreamListener listener, String delta) {
        if (listener != null) {
            try {
                listener.onDelta(delta);
            } catch (Throwable ignored) {
                // Caller exceptions in onDelta must not cascade into onError
            }
        }
    }

    private static void dispatchOnComplete(StreamListener listener, String fullText,
                                           List<String> webSources, int promptTokens, int candidateTokens) {
        if (listener != null) {
            try {
                listener.onComplete(fullText, webSources, promptTokens, candidateTokens);
            } catch (Throwable ignored) {
                // Caller exceptions in onComplete must not cascade into onError
            }
        }
    }

    private static void dispatchOnError(StreamListener listener, Exception error) {
        if (listener != null) {
            try {
                listener.onError(error);
            } catch (Throwable ignored) {
            }
        }
    }

    private static String parseErrorMessage(String jsonBody, String fallback) {
        try {
            JsonObject json = JsonParser.parseString(jsonBody).getAsJsonObject();
            if (json.has("error")) {
                JsonElement errorElem = json.get("error");
                if (errorElem.isJsonObject()) {
                    JsonObject errorObj = errorElem.getAsJsonObject();
                    if (errorObj.has("message") && !errorObj.get("message").isJsonNull()) {
                        return errorObj.get("message").getAsString();
                    }
                } else if (errorElem.isJsonPrimitive()) {
                    return errorElem.getAsString();
                }
            }
            if (json.has("message") && !json.get("message").isJsonNull()) {
                return json.get("message").getAsString();
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    /**
     * Thread-safe Cancellable handle wrapping active OkHttp Call.
     */
    private static class ApiCallHandle implements Cancellable {
        private volatile boolean cancelled = false;
        private volatile Call activeCall;

        void setCall(Call call) {
            this.activeCall = call;
            if (cancelled && call != null) {
                call.cancel();
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            Call call = activeCall;
            if (call != null) {
                call.cancel();
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled || (activeCall != null && activeCall.isCanceled());
        }
    }
}
