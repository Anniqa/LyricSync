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
 * multi-turn autonomous agent tool execution (Google Search & Web Fetch),
 * dynamic reasoning effort control, and token usage tracking via
 * Google Antigravity Cloudcode PA endpoints.
 *
 * Adheres strictly to uncapped model native token limits without artificial clamps.
 */
public class AntigravityApiClient {

    public static final String ENDPOINT_STREAM = "https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse";
    public static final String USER_AGENT = "antigravity/ide/2.11.0 darwin/arm64";
    public static final String DEFAULT_PROJECT = "aicode-consumers";
    public static final String DEFAULT_REQUEST_TYPE = "agent";
    public static final String DEFAULT_USER_AGENT_FIELD = "antigravity";
    public static final int MAX_TOOL_ITERATIONS = 3;

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    /**
     * Handle representing an active AI API call that can be cancelled.
     */
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

    /**
     * Represents a model function call parsed from candidate content parts.
     */
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

    /**
     * Holds parsed fields from an individual SSE event data frame.
     */
    public static class SseChunkResult {
        public String textDelta;
        public String thoughtSignature;
        public final List<String> webSearchQueries = new ArrayList<>();
        public FunctionCall functionCall;
        public final List<FunctionCall> functionCalls = new ArrayList<>();
        public int promptTokens = 0;
        public int candidateTokens = 0;
        public int totalTokens = 0;
        public String finishReason;
        public String errorMessage;
    }

    private final AntigravityAuthManager authManager;
    private final OkHttpClient httpClient;
    private AntigravityToolExecutor toolExecutor;
    private final Set<Call> activeCalls = Collections.newSetFromMap(new ConcurrentHashMap<Call, Boolean>());
    private final Set<ApiCallHandle> activeHandles = Collections.newSetFromMap(new ConcurrentHashMap<ApiCallHandle, Boolean>());

    public AntigravityApiClient(AntigravityAuthManager authManager) {
        this(authManager, createDefaultHttpClient(), null);
    }

    public AntigravityApiClient(AntigravityAuthManager authManager, OkHttpClient httpClient) {
        this(authManager, httpClient, null);
    }

    public AntigravityApiClient(AntigravityAuthManager authManager, OkHttpClient httpClient, AntigravityToolExecutor toolExecutor) {
        this.authManager = authManager;
        this.httpClient = httpClient != null ? httpClient : createDefaultHttpClient();
        this.toolExecutor = toolExecutor != null ? toolExecutor : new AntigravityToolExecutor(authManager, this.httpClient);
    }

    public void setToolExecutor(AntigravityToolExecutor toolExecutor) {
        this.toolExecutor = toolExecutor;
    }

    public AntigravityToolExecutor getToolExecutor() {
        return toolExecutor;
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
        for (ApiCallHandle handle : activeHandles) {
            try {
                handle.cancel();
            } catch (Exception ignored) {}
        }
        activeHandles.clear();
    }

    /**
     * Constructs tool schema declarations for autonomous web search and web fetch.
     */
    public static JsonArray buildToolDeclarations() {
        JsonArray declarations = new JsonArray();

        // 1. web_search
        JsonObject searchTool = new JsonObject();
        searchTool.addProperty("name", "web_search");
        searchTool.addProperty("description", "Mencari informasi fakta terkini di Google/web terkait lagu, artis, lirik, rilisan, latar belakang cerita, dan wawancara.");

        JsonObject searchParameters = new JsonObject();
        searchParameters.addProperty("type", "OBJECT");

        JsonObject searchProperties = new JsonObject();
        JsonObject queryProp = new JsonObject();
        queryProp.addProperty("type", "STRING");
        queryProp.addProperty("description", "Kata kunci pencarian yang spesifik dan efektif di Google.");
        searchProperties.add("query", queryProp);

        JsonObject numProp = new JsonObject();
        numProp.addProperty("type", "INTEGER");
        numProp.addProperty("description", "Jumlah maksimal sumber pencarian yang diinginkan (default: 5).");
        searchProperties.add("num_search_results", numProp);

        searchParameters.add("properties", searchProperties);
        JsonArray searchRequired = new JsonArray();
        searchRequired.add("query");
        searchParameters.add("required", searchRequired);

        searchTool.add("parameters", searchParameters);
        declarations.add(searchTool);

        // 2. web_fetch
        JsonObject fetchTool = new JsonObject();
        fetchTool.addProperty("name", "web_fetch");
        fetchTool.addProperty("description", "Mengambil dan membaca isi konten artikel/halaman web dari URL untuk membaca wawancara, artikel berita, atau ulasan lengkap.");

        JsonObject fetchParameters = new JsonObject();
        fetchParameters.addProperty("type", "OBJECT");

        JsonObject fetchProperties = new JsonObject();
        JsonObject urlProp = new JsonObject();
        urlProp.addProperty("type", "STRING");
        urlProp.addProperty("description", "URL halaman web publik (http:// atau https://) yang akan dibaca.");
        fetchProperties.add("url", urlProp);

        fetchParameters.add("properties", fetchProperties);
        JsonArray fetchRequired = new JsonArray();
        fetchRequired.add("url");
        fetchParameters.add("required", fetchRequired);

        fetchTool.add("parameters", fetchParameters);
        declarations.add(fetchTool);

        return declarations;
    }

    /**
     * Converts a list of AiChatMessage turns into a Gemini-compliant contents JsonArray.
     */
    public static JsonArray convertMessagesToContents(List<AiChatMessage> messages) {
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
        return contents;
    }

    /**
     * Constructs the JSON request body matching the Antigravity Cloudcode PA protocol.
     * Leaves token limits to native model capacity without artificial maxOutputTokens clamping.
     *
     * @param modelId Model identifier (e.g. "gemini-3-flash", "claude-sonnet-4-6")
     * @param messages Conversation turns
     * @param enableWebSearch When true, injects agent functionDeclarations (web_search, web_fetch)
     * @param effortLevel Optional reasoning effort level ("low", "medium", "high")
     * @return Formatted JSON payload string
     */
    public static String buildRequestBody(String modelId, List<AiChatMessage> messages,
                                         boolean enableWebSearch, String effortLevel) {
        return buildRequestBodyWithContents(modelId, convertMessagesToContents(messages), enableWebSearch, effortLevel);
    }

    /**
     * Constructs the JSON request body using an existing contents JsonArray.
     */
    public static String buildRequestBodyWithContents(String modelId, JsonArray contents,
                                                      boolean enableTools, String effortLevel) {
        JsonObject root = new JsonObject();
        root.addProperty("project", DEFAULT_PROJECT);
        root.addProperty("model", modelId != null ? modelId : "");
        root.addProperty("userAgent", DEFAULT_USER_AGENT_FIELD);
        root.addProperty("requestType", DEFAULT_REQUEST_TYPE);
        root.addProperty("requestId", "agent-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8));

        JsonObject request = new JsonObject();
        request.add("contents", contents != null ? contents : new JsonArray());

        // Inject tools if enabled (agent function declarations: web_search, web_fetch)
        if (enableTools) {
            JsonArray tools = new JsonArray();
            JsonObject tool = new JsonObject();
            tool.add("functionDeclarations", buildToolDeclarations());
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
     * Extracts text delta, functionCall, grounding queries, in-band error frames,
     * and token metrics from usageMetadata.
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

            // 1. Extract candidates text, functionCall, and candidate-level metadata
            if (resp.has("candidates") && resp.get("candidates").isJsonArray()) {
                JsonArray candidates = resp.getAsJsonArray("candidates");
                for (JsonElement candElem : candidates) {
                    if (!candElem.isJsonObject()) continue;
                    JsonObject cand = candElem.getAsJsonObject();

                    // Content parts: text and functionCall
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

                                if (part.has("thoughtSignature") && !part.get("thoughtSignature").isJsonNull() && part.get("thoughtSignature").isJsonPrimitive()) {
                                    result.thoughtSignature = part.get("thoughtSignature").getAsString();
                                }

                                if (part.has("functionCall") && part.get("functionCall").isJsonObject()) {
                                    JsonObject fcObj = part.getAsJsonObject("functionCall");
                                    String name = fcObj.has("name") && !fcObj.get("name").isJsonNull() && fcObj.get("name").isJsonPrimitive()
                                            ? fcObj.get("name").getAsString()
                                            : null;
                                    JsonObject args = fcObj.has("args") && fcObj.get("args").isJsonObject()
                                            ? fcObj.getAsJsonObject("args")
                                            : new JsonObject();
                                    String callId = fcObj.has("id") && !fcObj.get("id").isJsonNull() && fcObj.get("id").isJsonPrimitive()
                                            ? fcObj.get("id").getAsString()
                                            : null;
                                    String thoughtSig = part.has("thoughtSignature") && !part.get("thoughtSignature").isJsonNull() && part.get("thoughtSignature").isJsonPrimitive()
                                            ? part.get("thoughtSignature").getAsString()
                                            : result.thoughtSignature;
                                    if (name != null && !name.trim().isEmpty()) {
                                        FunctionCall fc = new FunctionCall(name.trim(), args, callId, thoughtSig, part);
                                        result.functionCalls.add(fc);
                                        if (result.functionCall == null) {
                                            result.functionCall = fc;
                                        }
                                        hasMeaningfulData = true;
                                    }
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
     * Overloaded streaming method accepting history, user prompt, and enableTools.
     */
    public Cancellable generateContentStream(String model, List<AiChatMessage> history,
                                            String userPrompt, boolean enableTools,
                                            StreamListener listener) {
        List<AiChatMessage> messages = new ArrayList<>();
        if (history != null) {
            messages.addAll(history);
        }
        if (userPrompt != null && !userPrompt.trim().isEmpty()) {
            messages.add(AiChatMessage.user(userPrompt.trim()));
        }
        return generateContentStream(model, messages, enableTools, null, listener);
    }

    /**
     * Streams AI completions line-by-line via Server-Sent Events (SSE) with autonomous multi-turn tool calling.
     * Returns a Cancellable handle to abort generation.
     */
    public Cancellable generateContentStream(final String modelId, final List<AiChatMessage> messages,
                                            final boolean enableWebSearch, final String effortLevel,
                                            final StreamListener listener) {
        final ApiCallHandle handle = new ApiCallHandle();
        activeHandles.add(handle);

        if (authManager == null) {
            activeHandles.remove(handle);
            dispatchOnError(listener, new IllegalStateException("AntigravityAuthManager is null"));
            return handle;
        }

        try {
            authManager.getValidAccessToken(new AntigravityAuthManager.TokenCallback() {
                @Override
                public void onTokenReady(String accessToken) {
                    if (handle.isCancelled()) {
                        activeHandles.remove(handle);
                        return;
                    }
                    JsonArray contents = convertMessagesToContents(messages);
                    executeStreamLoop(modelId, contents, enableWebSearch, effortLevel, accessToken,
                            listener, handle, 0, new StringBuilder(), new ArrayList<String>(), 0, 0);
                }

                @Override
                public void onError(String message) {
                    activeHandles.remove(handle);
                    if (handle.isCancelled()) {
                        return;
                    }
                    dispatchOnError(listener, new IOException("Authentication failed: " + message));
                }
            });
        } catch (Exception e) {
            activeHandles.remove(handle);
            if (!handle.isCancelled()) {
                dispatchOnError(listener, new IOException("Failed to acquire access token: " + e.getMessage(), e));
            }
        }

        return handle;
    }

    private void executeStreamLoop(final String modelId, final JsonArray contents,
                                   final boolean enableTools, final String effortLevel,
                                   final String accessToken, final StreamListener listener,
                                   final ApiCallHandle handle, final int toolIteration,
                                   final StringBuilder accumulatedText, final List<String> accumulatedSources,
                                   final int lastPromptTokens, final int lastCandidateTokens) {
        if (handle.isCancelled()) {
            activeHandles.remove(handle);
            return;
        }

        String payloadJson = buildRequestBodyWithContents(modelId, contents, enableTools, effortLevel);
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
                        activeHandles.remove(handle);
                        return;
                    }
                    activeHandles.remove(handle);
                    dispatchOnError(listener, e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        if (call.isCanceled() || handle.isCancelled()) {
                            activeHandles.remove(handle);
                            return;
                        }

                        FunctionCall detectedFunctionCall = null;
                        String streamThoughtSig = null;
                        int promptTokens = lastPromptTokens;
                        int candidateTokens = lastCandidateTokens;

                        try (ResponseBody responseBody = response.body()) {
                            if (responseBody == null) {
                                if (call.isCanceled() || handle.isCancelled()) return;
                                activeHandles.remove(handle);
                                dispatchOnError(listener, new IOException("Empty response body from Antigravity API"));
                                return;
                            }

                            if (!response.isSuccessful()) {
                                if (call.isCanceled() || handle.isCancelled()) return;
                                String errString = responseBody.string();
                                String parsedMsg = parseErrorMessage(errString, "HTTP " + response.code() + ": " + response.message());
                                activeHandles.remove(handle);
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
                                        activeHandles.remove(handle);
                                        dispatchOnError(listener, new IOException("Antigravity API stream error: " + chunk.errorMessage));
                                        return;
                                    }

                                    if (chunk.thoughtSignature != null) {
                                        streamThoughtSig = chunk.thoughtSignature;
                                    }

                                    if (chunk.functionCall != null) {
                                        detectedFunctionCall = chunk.functionCall;
                                    }

                                    if (chunk.textDelta != null && !chunk.textDelta.isEmpty()) {
                                        accumulatedText.append(chunk.textDelta);
                                        dispatchOnDelta(listener, chunk.textDelta);
                                    }
                                    for (String src : chunk.webSearchQueries) {
                                        if (!accumulatedSources.contains(src)) {
                                            accumulatedSources.add(src);
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
                            activeHandles.remove(handle);
                            dispatchOnError(listener, e);
                            return;
                        }

                        if (call.isCanceled() || handle.isCancelled()) {
                            activeHandles.remove(handle);
                            return;
                        }

                        // Attach stream-level thoughtSignature if not attached directly to functionCall
                        if (detectedFunctionCall != null && detectedFunctionCall.getThoughtSignature() == null && streamThoughtSig != null) {
                            detectedFunctionCall = new FunctionCall(
                                    detectedFunctionCall.name,
                                    detectedFunctionCall.args,
                                    detectedFunctionCall.id,
                                    streamThoughtSig,
                                    detectedFunctionCall.rawPart
                            );
                        }

                        // Check if model invoked a tool functionCall
                        if (enableTools && detectedFunctionCall != null) {
                            if (toolIteration >= MAX_TOOL_ITERATIONS) {
                                // Hard loop guard: reached max iterations, complete immediately
                                activeHandles.remove(handle);
                                dispatchOnComplete(listener, accumulatedText.toString(), accumulatedSources, promptTokens, candidateTokens);
                                return;
                            }

                            handleToolExecution(modelId, contents, enableTools, effortLevel, accessToken,
                                    listener, handle, toolIteration, accumulatedText, accumulatedSources,
                                    promptTokens, candidateTokens, detectedFunctionCall);
                            return;
                        }

                        // Normal completion without pending functionCall
                        activeHandles.remove(handle);
                        dispatchOnComplete(listener, accumulatedText.toString(), accumulatedSources, promptTokens, candidateTokens);
                    } finally {
                        activeCalls.remove(call);
                    }
                }
            });
        } catch (Exception e) {
            activeHandles.remove(handle);
            if (!handle.isCancelled()) {
                dispatchOnError(listener, new IOException("Failed to initiate stream request: " + e.getMessage(), e));
            }
        }
    }

    private void handleToolExecution(final String modelId, final JsonArray contents,
                                     final boolean enableTools, final String effortLevel,
                                     final String accessToken, final StreamListener listener,
                                     final ApiCallHandle handle, final int toolIteration,
                                     final StringBuilder accumulatedText, final List<String> accumulatedSources,
                                     final int promptTokens, final int candidateTokens,
                                     final FunctionCall functionCall) {
        if (handle.isCancelled()) {
            activeHandles.remove(handle);
            return;
        }

        final String toolName = functionCall.getName();
        final JsonObject toolArgs = functionCall.getArgs();

        String queryOrUrl = "";
        if ("web_search".equals(toolName)) {
            queryOrUrl = toolArgs.has("query") && !toolArgs.get("query").isJsonNull()
                    ? toolArgs.get("query").getAsString()
                    : toolArgs.toString();
        } else if ("web_fetch".equals(toolName)) {
            queryOrUrl = toolArgs.has("url") && !toolArgs.get("url").isJsonNull()
                    ? toolArgs.get("url").getAsString()
                    : toolArgs.toString();
        } else {
            queryOrUrl = toolArgs.toString();
        }

        dispatchOnToolExecuting(listener, toolName, queryOrUrl);

        AntigravityToolExecutor executor = this.toolExecutor != null
                ? this.toolExecutor
                : new AntigravityToolExecutor(authManager, httpClient);

        ToolCallback callback = new ToolCallback() {
            @Override
            public void onResult(ToolResult result) {
                if (handle.isCancelled()) {
                    activeHandles.remove(handle);
                    return;
                }

                int resultsCount = 0;
                if (result != null) {
                    if (result.sources != null && !result.sources.isEmpty()) {
                        resultsCount = result.sources.size();
                    } else if (!result.isError) {
                        resultsCount = 1;
                    }
                }

                dispatchOnToolCompleted(listener, toolName, resultsCount);

                // Collect sources from result
                if (result != null && result.sources != null) {
                    for (SourceItem item : result.sources) {
                        if (item != null) {
                            String src = (item.url != null && !item.url.trim().isEmpty())
                                    ? item.url.trim()
                                    : (item.title != null ? item.title.trim() : "");
                            if (!src.isEmpty() && !accumulatedSources.contains(src)) {
                                accumulatedSources.add(src);
                            }
                        }
                    }
                }

                // Append model functionCall turn to contents
                JsonObject modelTurn = new JsonObject();
                modelTurn.addProperty("role", "model");
                JsonArray modelParts = new JsonArray();
                if (functionCall.getRawPart() != null) {
                    modelParts.add(functionCall.getRawPart());
                } else {
                    JsonObject fcPart = new JsonObject();
                    if (functionCall.getThoughtSignature() != null && !functionCall.getThoughtSignature().trim().isEmpty()) {
                        fcPart.addProperty("thoughtSignature", functionCall.getThoughtSignature().trim());
                    }
                    JsonObject fcObj = new JsonObject();
                    fcObj.addProperty("name", toolName);
                    if (functionCall.getId() != null && !functionCall.getId().trim().isEmpty()) {
                        fcObj.addProperty("id", functionCall.getId().trim());
                    }
                    fcObj.add("args", toolArgs != null ? toolArgs : new JsonObject());
                    fcPart.add("functionCall", fcObj);
                    modelParts.add(fcPart);
                }
                modelTurn.add("parts", modelParts);
                contents.add(modelTurn);

                // Append functionResponse turn to contents
                // CRITICAL FOR CLOUDCODE PA / GEMINI: role MUST be "user", NOT "function" or "tool"
                JsonObject fnTurn = new JsonObject();
                fnTurn.addProperty("role", "user");
                JsonArray fnParts = new JsonArray();
                JsonObject fnPart = new JsonObject();
                JsonObject frObj = new JsonObject();
                frObj.addProperty("name", toolName);
                if (functionCall.getId() != null && !functionCall.getId().trim().isEmpty()) {
                    frObj.addProperty("id", functionCall.getId().trim());
                }

                JsonObject respWrapper = new JsonObject();
                String rawContent = (result != null && result.content != null) ? result.content : "";
                try {
                    JsonElement parsed = JsonParser.parseString(rawContent);
                    respWrapper.add("content", parsed);
                } catch (Exception e) {
                    respWrapper.addProperty("content", rawContent);
                }
                frObj.add("response", respWrapper);
                fnPart.add("functionResponse", frObj);
                fnParts.add(fnPart);
                fnTurn.add("parts", fnParts);
                contents.add(fnTurn);

                // Launch next stream turn with updated contents
                executeStreamLoop(modelId, contents, enableTools, effortLevel, accessToken,
                        listener, handle, toolIteration + 1, accumulatedText, accumulatedSources,
                        promptTokens, candidateTokens);
            }
        };

        Cancellable toolCancellable = null;
        try {
            if ("web_search".equals(toolName)) {
                String query = toolArgs.has("query") && !toolArgs.get("query").isJsonNull()
                        ? toolArgs.get("query").getAsString()
                        : "";
                int maxResults = toolArgs.has("num_search_results") && !toolArgs.get("num_search_results").isJsonNull()
                        ? toolArgs.get("num_search_results").getAsInt()
                        : 5;
                toolCancellable = executor.executeWebSearch(query, maxResults, callback);
            } else if ("web_fetch".equals(toolName)) {
                String url = toolArgs.has("url") && !toolArgs.get("url").isJsonNull()
                        ? toolArgs.get("url").getAsString()
                        : "";
                toolCancellable = executor.executeWebFetch(url, callback);
            } else {
                callback.onResult(new ToolResult(true, "Unknown tool: " + toolName, Collections.<SourceItem>emptyList()));
                return;
            }
        } catch (Exception e) {
            callback.onResult(new ToolResult(true, "Tool execution error: " + e.getMessage(), Collections.<SourceItem>emptyList()));
            return;
        }

        handle.setToolCancellable(toolCancellable);
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
            }
            try {
                listener.onChunk(delta);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void dispatchOnToolExecuting(StreamListener listener, String toolName, String queryOrUrl) {
        if (listener != null) {
            try {
                listener.onToolExecuting(toolName, queryOrUrl);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void dispatchOnToolCompleted(StreamListener listener, String toolName, int resultsCount) {
        if (listener != null) {
            try {
                listener.onToolCompleted(toolName, resultsCount);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void dispatchOnComplete(StreamListener listener, String fullText,
                                           List<String> webSources, int promptTokens, int candidateTokens) {
        if (listener != null) {
            try {
                listener.onComplete(fullText, webSources, promptTokens, candidateTokens);
            } catch (Throwable ignored) {
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
     * Thread-safe Cancellable handle wrapping active OkHttp Call and tool execution Cancellable.
     */
    static class ApiCallHandle implements Cancellable {
        private volatile boolean cancelled = false;
        private volatile Call activeCall;
        private volatile Cancellable activeToolCancellable;

        void setCall(Call call) {
            this.activeCall = call;
            if (cancelled && call != null) {
                call.cancel();
            }
        }

        void setToolCancellable(Cancellable toolCancellable) {
            this.activeToolCancellable = toolCancellable;
            if (cancelled && toolCancellable != null) {
                toolCancellable.cancel();
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            Call call = activeCall;
            if (call != null) {
                try {
                    call.cancel();
                } catch (Exception ignored) {}
            }
            Cancellable tc = activeToolCancellable;
            if (tc != null) {
                try {
                    tc.cancel();
                } catch (Exception ignored) {}
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled
                    || (activeCall != null && activeCall.isCanceled())
                    || (activeToolCancellable != null && activeToolCancellable.isCancelled());
        }
    }
}
