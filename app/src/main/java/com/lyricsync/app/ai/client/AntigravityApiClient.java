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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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

    public interface LyricActionDelegate {
        class LineEdit {
            public final int lineIndex;
            public final String text;
            public final String translation;
            public final String romanji;
            public LineEdit(int lineIndex, String text, String translation, String romanji) {
                this.lineIndex = lineIndex;
                this.text = text;
                this.translation = translation;
                this.romanji = romanji;
            }
        }
        void translateLyrics(String targetLang, ActionCallback callback);
        void transliterateLyrics(String mode, ActionCallback callback);
        default void transliterateLyrics(String mode, boolean showOriginalScript, ActionCallback callback) {
            transliterateLyrics(mode, callback);
        }
        void setSyncOffset(int offsetMs, ActionCallback callback);
        void modifyLine(int lineIndex, String text, String translation, String romanji, ActionCallback callback);
        default void batchEditLyrics(List<LineEdit> edits, ActionCallback callback) {
            if (edits == null || edits.isEmpty()) {
                callback.onError("Daftar editan kosong");
                return;
            }
            for (LineEdit e : edits) {
                modifyLine(e.lineIndex, e.text, e.translation, e.romanji, null);
            }
            callback.onSuccess(edits.size() + " baris berhasil diperbarui.");
        }
        void toggleDisplay(String feature, boolean enabled, ActionCallback callback);
        default void controlPlayback(String action, int lineIndex, long positionMs, ActionCallback callback) {
            if (callback != null) callback.onError("controlPlayback belum didukung");
        }
        default void adjustAppearance(Float fontScale, String animStyle, Integer widthPercent, Integer heightPercent, ActionCallback callback) {
            if (callback != null) callback.onError("adjustAppearance belum didukung");
        }
        default void manageLines(String action, int lineIndex, long startMs, long endMs, String text, String translation, String romanji, long shiftMs, ActionCallback callback) {
            if (callback != null) callback.onError("manageLines belum didukung");
        }
        default void getCurrentPlayingTrack(TrackInfoCallback callback) {
            if (callback != null) callback.onError("getCurrentPlayingTrack belum didukung");
        }
        interface TrackInfoCallback {
            void onSuccess(String title, String artist, String lyrics, boolean isPlaying, long positionMs);
            void onError(String error);
        }
        interface ActionCallback {
            void onSuccess(String message);
            void onError(String error);
        }
    }

    private volatile LyricActionDelegate lyricActionDelegate;

    public void setLyricActionDelegate(LyricActionDelegate delegate) {
        this.lyricActionDelegate = delegate;
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
        public int promptTokens = 0;
        public int candidateTokens = 0;
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

    public boolean isAuthenticated() {
        return authManager != null && authManager.isAuthenticated();
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
    private static JsonObject makeTool(String name, String desc, String[] req, Object... props) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", desc);
        JsonObject params = new JsonObject();
        params.addProperty("type", "OBJECT");
        JsonObject properties = new JsonObject();
        for (int i = 0; i < props.length; i += 3) {
            JsonObject p = new JsonObject();
            p.addProperty("type", (String) props[i + 1]);
            p.addProperty("description", (String) props[i + 2]);
            properties.add((String) props[i], p);
        }
        params.add("properties", properties);
        if (req != null && req.length > 0) {
            JsonArray r = new JsonArray();
            for (String item : req) r.add(item);
            params.add("required", r);
        }
        tool.add("parameters", params);
        return tool;
    }

    public static JsonArray buildToolDeclarations() {
        return buildToolDeclarations(true);
    }

    public static JsonArray buildToolDeclarations(boolean enableWebSearch) {
        JsonArray declarations = new JsonArray();
        if (enableWebSearch) {
            declarations.add(makeTool("web_search", "Mencari informasi fakta terkini di Google/web terkait lagu, artis, lirik, rilisan, latar belakang cerita, dan wawancara.",
                    new String[]{"query"},
                    "query", "STRING", "Kata kunci pencarian yang spesifik dan efektif di Google.",
                    "num_search_results", "INTEGER", "Jumlah maksimal sumber pencarian yang diinginkan (default: 5)."));
            declarations.add(makeTool("web_fetch", "Mengambil dan membaca isi konten artikel/halaman web dari URL untuk membaca wawancara, artikel berita, atau ulasan lengkap.",
                    new String[]{"url"},
                    "url", "STRING", "URL halaman web publik (http:// atau https://) yang akan dibaca."));
        }
        declarations.add(makeTool("translate_lyrics", "Menerjemahkan lirik lagu saat ini ke bahasa target secara langsung dan menampilkannya di overlay pemutar lirik.",
                new String[]{"target_language"},
                "target_language", "STRING", "Kode bahasa tujuan ('id', 'en', 'ja', 'ko', 'es', dll)."));
        declarations.add(makeTool("transliterate_lyrics", "Mentransliterasikan lirik beraksara non-Latin ke huruf Latin/Romaji dan langsung menerapkannya pada overlay lirik.",
                null,
                "mode", "STRING", "Mode: 'romaji_with_translation' (dengan arti), 'romaji_only' (romaji saja), atau 'pure_romaji' (hanya romaji).",
                "show_original_script", "BOOLEAN", "true untuk tetap menampilkan huruf asli di atas romaji, false untuk membuang/menyembunyikan huruf asli (default: true)."));
        declarations.add(makeTool("set_sync_offset", "Menyesuaikan offset sinkronisasi audio dan lirik secara instan dalam milidetik.",
                new String[]{"offset_ms"},
                "offset_ms", "INTEGER", "Nilai offset dalam milidetik (positif untuk tunda, negatif untuk percepat)."));
        declarations.add(makeTool("modify_lyric_line", "Mengubah atau memperbaiki teks, arti terjemahan, atau pelafalan romaji baris lirik tertentu secara langsung.",
                new String[]{"line_index"},
                "line_index", "INTEGER", "Indeks baris lirik (mulai dari 0).",
                "text", "STRING", "Teks lirik baru (opsional).",
                "translation", "STRING", "Terjemahan baru untuk baris tersebut (opsional).",
                "romanji", "STRING", "Bacaan romaji baru untuk baris tersebut (opsional)."));
        declarations.add(makeTool("toggle_display", "Menampilkan atau menyembunyikan terjemahan lirik, romaji, atau aksara asli pada tampilan overlay.",
                new String[]{"feature", "enabled"},
                "feature", "STRING", "Fitur: 'translation', 'romaji', atau 'original_script'.",
                "enabled", "BOOLEAN", "true untuk menampilkan, false untuk menyembunyikan."));
        declarations.add(com.google.gson.JsonParser.parseString("{"
                + "\"name\":\"batch_edit_lyrics\","
                + "\"description\":\"Membuat terjemahan, mengedit teks, atau pelafalan romaji untuk banyak baris lirik sekaligus.\","
                + "\"parameters\":{\"type\":\"OBJECT\",\"properties\":{\"lines\":{\"type\":\"ARRAY\",\"description\":\"Daftar baris lirik yang diedit/diterjemahkan.\",\"items\":{\"type\":\"OBJECT\",\"properties\":{\"line_index\":{\"type\":\"INTEGER\",\"description\":\"Indeks baris lirik (mulai dari 0).\"},\"translation\":{\"type\":\"STRING\",\"description\":\"Terjemahan baru untuk baris tersebut.\"},\"text\":{\"type\":\"STRING\",\"description\":\"Teks lirik baru (opsional).\"},\"romanji\":{\"type\":\"STRING\",\"description\":\"Bacaan romaji baru (opsional).\"}},\"required\":[\"line_index\"]}}},\"required\":[\"lines\"]}}"
        ).getAsJsonObject());
        declarations.add(makeTool("control_playback",
                "Mengontrol pemutaran musik di pemutar pengguna (lompat ke baris lirik tertentu/seek to line, lompat ke milidetik tertentu, jeda/pause, putar/play, lagu berikutnya/sebelumnya).",
                new String[]{"action"},
                "action", "STRING", "Aksi: 'seek_to_line', 'seek_to_position', 'play', 'pause', 'skip_next', atau 'skip_previous'.",
                "line_index", "INTEGER", "Indeks baris lirik sasaran untuk 'seek_to_line' (mulai dari 0).",
                "position_ms", "INTEGER", "Posisi audio dalam milidetik untuk 'seek_to_position'."));
        declarations.add(makeTool("adjust_overlay_appearance",
                "Menyesuaikan tampilan visual overlay pemutar lirik (skala ukuran font teks, gaya animasi suku kata, lebar jendela, dan tinggi jendela overlay).",
                null,
                "font_scale", "NUMBER", "Skala ukuran font (misal: 0.8 lebih kecil, 1.0 normal, 1.3 lebih besar, 1.6 sangat besar).",
                "anim_style", "STRING", "Gaya efek animasi teks ('glow', 'scale', 'float', 'pop', 'fade').",
                "width_percent", "INTEGER", "Persentase lebar overlay (45 sampai 100).",
                "height_percent", "INTEGER", "Persentase tinggi overlay (20 sampai 70)."));
        declarations.add(makeTool("manage_lyric_lines",
                "Menambah, menghapus, atau memindahkan timing baris lirik tertentu di overlay secara langsung.",
                new String[]{"action", "line_index"},
                "action", "STRING", "Aksi: 'delete' (hapus baris), 'insert' (sisipkan baris baru), atau 'shift_timing' (geser waktu baris).",
                "line_index", "INTEGER", "Indeks baris sasaran (mulai dari 0).",
                "text", "STRING", "Teks baris lirik baru (untuk aksi 'insert').",
                "start_time_ms", "INTEGER", "Waktu mulai baris dalam milidetik (untuk aksi 'insert').",
                "end_time_ms", "INTEGER", "Waktu akhir baris dalam milidetik (untuk aksi 'insert').",
                "translation", "STRING", "Terjemahan baris baru (opsional untuk aksi 'insert').",
                "romanji", "STRING", "Bacaan romaji baris baru (opsional untuk aksi 'insert').",
                "shift_ms", "INTEGER", "Pergeseran waktu dalam milidetik (+/-) untuk aksi 'shift_timing'."));
        declarations.add(makeTool("get_current_playing_track",
                "Mendapatkan informasi lagu yang sedang diputar di pemutar musik pengguna saat ini (judul, artis, lirik aktif, status pemutaran). Panggil alat ini untuk memastikan lagu yang sedang aktif jika pengguna bertanya tentang lagu saat ini.",
                null));
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
                    String trimmedRole = msg.role.trim().toLowerCase(Locale.ROOT);
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

        // Always inject tools: lyric tools are always available, web search added if enabled
        JsonArray tools = new JsonArray();
        JsonObject tool = new JsonObject();
        tool.add("functionDeclarations", buildToolDeclarations(enableTools));
        tools.add(tool);
        request.add("tools", tools);

        // Inject reasoning effort config if specified (uncapped maxOutputTokens)
        if (effortLevel != null && !effortLevel.trim().isEmpty() && !"none".equalsIgnoreCase(effortLevel.trim())) {
            JsonObject generationConfig = new JsonObject();
            JsonObject thinkingConfig = new JsonObject();
            thinkingConfig.addProperty("includeThoughts", false);
            thinkingConfig.addProperty("thinkingLevel", effortLevel.trim().toLowerCase(Locale.ROOT));
            generationConfig.add("thinkingConfig", thinkingConfig);
            request.add("generationConfig", generationConfig);
        }

        root.add("request", request);
        return root.toString();
    }

    public static String extractThoughtSignature(JsonObject obj) {
        if (obj == null) return null;
        for (String key : new String[]{"thought_signature", "thoughtSignature", "signature"}) {
            if (obj.has(key)) {
                JsonElement el = obj.get(key);
                if (el != null && !el.isJsonNull() && el.isJsonPrimitive()) {
                    return el.getAsString();
                }
            }
        }
        return null;
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
                    errResult.errorMessage = ApiErrors.message(errorElem.toString(), "In-band stream error");
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

                                String sig = extractThoughtSignature(part);
                                if (sig != null) {
                                    result.thoughtSignature = sig;
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
                                    String thoughtSig = extractThoughtSignature(fcObj);
                                    if (thoughtSig == null) thoughtSig = extractThoughtSignature(part);
                                    if (thoughtSig == null) thoughtSig = result.thoughtSignature;
                                    if (name != null && !name.trim().isEmpty()) {
                                        FunctionCall fc = new FunctionCall(name.trim(), args, callId, thoughtSig, part);
                                        if (result.functionCall == null) {
                                            result.functionCall = fc;
                                        }
                                        hasMeaningfulData = true;
                                    }
                                }
                            }
                        }
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
                    String title = null;
                    String uri = null;
                    if (web.has("title") && !web.get("title").isJsonNull() && web.get("title").isJsonPrimitive()) {
                        String t = web.get("title").getAsString();
                        if (t != null && !t.trim().isEmpty()) {
                            title = t.trim();
                        }
                    }
                    if (web.has("uri") && !web.get("uri").isJsonNull() && web.get("uri").isJsonPrimitive()) {
                        String u = web.get("uri").getAsString();
                        if (u != null && !u.trim().isEmpty()) {
                            uri = u.trim();
                        }
                    }

                    if (title != null && uri != null && !title.equalsIgnoreCase(uri)) {
                        String combined = title + "|" + uri;
                        if (!result.webSearchQueries.contains(combined)) {
                            result.webSearchQueries.add(combined);
                            found = true;
                        }
                        if (!result.webSearchQueries.contains(title)) {
                            result.webSearchQueries.add(title);
                        }
                    } else if (title != null) {
                        if (!result.webSearchQueries.contains(title)) {
                            result.webSearchQueries.add(title);
                            found = true;
                        }
                    } else if (uri != null) {
                        if (!result.webSearchQueries.contains(uri)) {
                            result.webSearchQueries.add(uri);
                            found = true;
                        }
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
                        if (cancelledExit(call, handle)) return;

                        FunctionCall detectedFunctionCall = null;
                        String streamThoughtSig = null;
                        int promptTokens = lastPromptTokens;
                        int candidateTokens = lastCandidateTokens;

                        try (ResponseBody responseBody = response.body()) {
                            if (responseBody == null) {
                                if (call.isCanceled() || handle.isCancelled()) return;
                                failAndCleanup(call, handle, listener, new IOException("Empty response body from Antigravity API"));
                                return;
                            }

                            if (!response.isSuccessful()) {
                                if (call.isCanceled() || handle.isCancelled()) return;
                                String errString = responseBody.string();
                                String parsedMsg = ApiErrors.message(errString, "HTTP " + response.code() + ": " + response.message());
                                failAndCleanup(call, handle, listener, new IOException("Antigravity API request failed: " + parsedMsg));
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
                                        failAndCleanup(call, handle, listener, new IOException("Antigravity API stream error: " + chunk.errorMessage));
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
                            failAndCleanup(call, handle, listener, e);
                            return;
                        }

                        if (cancelledExit(call, handle)) return;

                        // Attach stream-level thoughtSignature if not attached directly to functionCall
                        if (detectedFunctionCall != null) {
                            detectedFunctionCall = withStreamSignature(detectedFunctionCall, streamThoughtSig);
                        }

                        // Check if model invoked a tool functionCall
                        if (detectedFunctionCall != null) {
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
                        if (!handle.isCancelled() && !call.isCanceled()) {
                            dispatchOnComplete(listener, accumulatedText.toString(), accumulatedSources, promptTokens, candidateTokens);
                        }
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

        String queryOrUrl = describeToolCall(toolName, toolArgs);

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
                            String url = (item.url != null) ? item.url.trim() : "";
                            String title = (item.title != null) ? item.title.trim() : "";
                            if (!url.isEmpty() && !accumulatedSources.contains(url)) {
                                accumulatedSources.add(url);
                            }
                            if (!title.isEmpty() && !url.isEmpty() && !title.equalsIgnoreCase(url)) {
                                String combined = title + "|" + url;
                                if (!accumulatedSources.contains(combined)) {
                                    accumulatedSources.add(combined);
                                }
                            } else if (!title.isEmpty() && !accumulatedSources.contains(title)) {
                                accumulatedSources.add(title);
                            }
                        }
                    }
                }

                // Append model functionCall turn to contents
                JsonObject modelTurn = new JsonObject();
                modelTurn.addProperty("role", "model");
                JsonArray modelParts = new JsonArray();
                JsonObject fcPart;
                if (functionCall.getRawPart() != null) {
                    fcPart = functionCall.getRawPart().deepCopy();
                } else {
                    fcPart = new JsonObject();
                    JsonObject fcObj = new JsonObject();
                    fcObj.addProperty("name", toolName);
                    if (functionCall.getId() != null && !functionCall.getId().trim().isEmpty()) {
                        fcObj.addProperty("id", functionCall.getId().trim());
                    }
                    fcObj.add("args", toolArgs != null ? toolArgs : new JsonObject());
                    fcPart.add("functionCall", fcObj);
                }

                // CRITICAL FOR GEMINI 3+: thought_signature MUST be present on the functionCall part!
                String sig = functionCall.getThoughtSignature();
                if (sig == null || sig.trim().isEmpty()) {
                    sig = extractThoughtSignature(fcPart);
                }
                if (sig == null || sig.trim().isEmpty()) {
                    if (fcPart.has("functionCall") && fcPart.get("functionCall").isJsonObject()) {
                        sig = extractThoughtSignature(fcPart.getAsJsonObject("functionCall"));
                    }
                }
                if (sig == null || sig.trim().isEmpty()) {
                    sig = "skip_thought_signature_validator";
                }

                // Clean up functionCall object: protobuf FunctionCall ONLY has: name, args, id.
                // It MUST NOT contain thought_signature or thoughtSignature!
                if (fcPart.has("functionCall") && fcPart.get("functionCall").isJsonObject()) {
                    JsonObject innerFc = fcPart.getAsJsonObject("functionCall");
                    innerFc.remove("thought_signature");
                    innerFc.remove("thoughtSignature");
                    innerFc.remove("signature");
                }

                // thought_signature belongs on Part (fcPart), NOT inside functionCall!
                fcPart.addProperty("thought_signature", sig.trim());
                fcPart.addProperty("thoughtSignature", sig.trim());
                modelParts.add(fcPart);
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
                toolCancellable = executor.executeWebSearch(
                        ToolArgs.str(toolArgs, "query", ""),
                        ToolArgs.intValue(toolArgs, "num_search_results", 5),
                        callback);
            } else if ("web_fetch".equals(toolName)) {
                toolCancellable = executor.executeWebFetch(ToolArgs.str(toolArgs, "url", ""), callback);
            } else if (lyricActionDelegate == null) {
                callback.onResult(ToolResult.error("LyricActionDelegate tidak tersedia"));
            } else if ("translate_lyrics".equals(toolName)) {
                lyricActionDelegate.translateLyrics(
                        ToolArgs.str(toolArgs, "target_language", "id"),
                        wrapActionCallback(callback, "Gagal menerjemahkan"));
            } else if ("transliterate_lyrics".equals(toolName)) {
                String mode = ToolArgs.str(toolArgs, "mode", "romaji");
                // An explicit flag wins; otherwise "pure_romaji" is the mode that drops the original.
                boolean showOriginal = has(toolArgs, "show_original_script")
                        ? ToolArgs.bool(toolArgs, "show_original_script", true)
                        : !"pure_romaji".equalsIgnoreCase(mode);
                lyricActionDelegate.transliterateLyrics(mode, showOriginal,
                        wrapActionCallback(callback, "Gagal mentransliterasikan lirik"));
            } else if ("set_sync_offset".equals(toolName)) {
                lyricActionDelegate.setSyncOffset(
                        ToolArgs.intValue(toolArgs, "offset_ms", 0),
                        wrapActionCallback(callback, "Gagal mengatur offset"));
            } else if ("modify_lyric_line".equals(toolName)) {
                lyricActionDelegate.modifyLine(
                        ToolArgs.intValue(toolArgs, "line_index", -1),
                        ToolArgs.str(toolArgs, "text", null),
                        ToolArgs.str(toolArgs, "translation", null),
                        ToolArgs.str(toolArgs, "romanji", null),
                        wrapActionCallback(callback, "Gagal mengubah baris"));
            } else if ("batch_edit_lyrics".equals(toolName)) {
                lyricActionDelegate.batchEditLyrics(lineEdits(toolArgs),
                        wrapActionCallback(callback, "Gagal mengedit banyak baris"));
            } else if ("toggle_display".equals(toolName)) {
                lyricActionDelegate.toggleDisplay(
                        ToolArgs.str(toolArgs, "feature", ""),
                        ToolArgs.bool(toolArgs, "enabled", false),
                        wrapActionCallback(callback, "Gagal mengubah tampilan"));
            } else if ("control_playback".equals(toolName)) {
                lyricActionDelegate.controlPlayback(
                        ToolArgs.str(toolArgs, "action", ""),
                        ToolArgs.intValue(toolArgs, "line_index", -1),
                        ToolArgs.longValue(toolArgs, "position_ms", 0L),
                        wrapActionCallback(callback, "Gagal mengontrol pemutaran"));
            } else if ("adjust_overlay_appearance".equals(toolName)) {
                lyricActionDelegate.adjustAppearance(
                        ToolArgs.floatOrNull(toolArgs, "font_scale"),
                        ToolArgs.str(toolArgs, "anim_style", null),
                        ToolArgs.intOrNull(toolArgs, "width_percent"),
                        ToolArgs.intOrNull(toolArgs, "height_percent"),
                        wrapActionCallback(callback, "Gagal mengubah tampilan"));
            } else if ("manage_lyric_lines".equals(toolName)) {
                lyricActionDelegate.manageLines(
                        ToolArgs.str(toolArgs, "action", ""),
                        ToolArgs.intValue(toolArgs, "line_index", -1),
                        ToolArgs.longValue(toolArgs, "start_time_ms", 0L),
                        ToolArgs.longValue(toolArgs, "end_time_ms", 0L),
                        ToolArgs.str(toolArgs, "text", null),
                        ToolArgs.str(toolArgs, "translation", null),
                        ToolArgs.str(toolArgs, "romanji", null),
                        ToolArgs.longValue(toolArgs, "shift_ms", 0L),
                        wrapActionCallback(callback, "Gagal mengelola baris lirik"));
            } else if ("get_current_playing_track".equals(toolName)) {
                lyricActionDelegate.getCurrentPlayingTrack(new LyricActionDelegate.TrackInfoCallback() {
                    @Override
                    public void onSuccess(String title, String artist, String lyrics, boolean isPlaying, long positionMs) {
                        JsonObject obj = new JsonObject();
                        obj.addProperty("title", title != null ? title : "");
                        obj.addProperty("artist", artist != null ? artist : "");
                        obj.addProperty("is_playing", isPlaying);
                        obj.addProperty("position_ms", positionMs);
                        if (lyrics != null && !lyrics.isEmpty()) {
                            obj.addProperty("lyrics", lyrics.length() > 3000 ? lyrics.substring(0, 3000) + "..." : lyrics);
                        }
                        callback.onResult(ToolResult.message(obj.toString()));
                    }
                    @Override
                    public void onError(String error) {
                        callback.onResult(ToolResult.error("Gagal mendapatkan info lagu: " + error));
                    }
                });
            } else {
                callback.onResult(ToolResult.error("Unknown tool: " + toolName));
                return;
            }
        } catch (Exception e) {
            callback.onResult(ToolResult.error("Tool execution error: " + e.getMessage()));
            return;
        }

        handle.setToolCancellable(toolCancellable);
    }

    /**
     * A short, human-readable summary of what a tool call is doing, shown as the inline
     * status line while the tool runs. Arguments are read leniently so a mistyped one
     * degrades the label instead of breaking the call.
     */
    private static String describeToolCall(String toolName, JsonObject args) {
        String unexpected = args != null ? args.toString() : "";
        if (toolName == null) return unexpected;
        switch (toolName) {
            case "web_search":
                return ToolArgs.str(args, "query", unexpected);
            case "web_fetch":
                return ToolArgs.str(args, "url", unexpected);
            case "translate_lyrics":
                return ToolArgs.str(args, "target_language", "id");
            case "transliterate_lyrics":
                return ToolArgs.str(args, "mode", "romaji");
            case "set_sync_offset":
                return ToolArgs.str(args, "offset_ms", "0");
            case "modify_lyric_line":
                return has(args, "line_index")
                        ? "Baris " + (ToolArgs.intValue(args, "line_index", 0) + 1)
                        : "Baris lirik";
            case "batch_edit_lyrics":
                return lineCount(args) + " baris lirik";
            case "toggle_display":
                return ToolArgs.str(args, "feature", "")
                        + " (" + (ToolArgs.bool(args, "enabled", false) ? "on" : "off") + ")";
            case "control_playback": {
                String action = ToolArgs.str(args, "action", "playback");
                if ("seek_to_line".equals(action) && has(args, "line_index")) {
                    return "Lompat ke baris " + (ToolArgs.intValue(args, "line_index", 0) + 1);
                }
                return action;
            }
            case "adjust_overlay_appearance":
                return "Tampilan overlay";
            case "manage_lyric_lines":
                return ToolArgs.str(args, "action", "baris")
                        + " baris " + (ToolArgs.intValue(args, "line_index", -1) + 1);
            case "get_current_playing_track":
                return "Status lagu aktif";
            default:
                return unexpected;
        }
    }

    /** The "lines" array of a batch edit, as delegate edits. Malformed entries are skipped. */
    private static List<LyricActionDelegate.LineEdit> lineEdits(JsonObject args) {
        List<LyricActionDelegate.LineEdit> edits = new ArrayList<>();
        if (args == null || !args.has("lines") || !args.get("lines").isJsonArray()) return edits;
        for (JsonElement el : args.getAsJsonArray("lines")) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject line = el.getAsJsonObject();
            int index = ToolArgs.intValue(line, "line_index", -1);
            if (index < 0) continue;
            edits.add(new LyricActionDelegate.LineEdit(
                    index,
                    ToolArgs.str(line, "text", null),
                    ToolArgs.str(line, "translation", null),
                    ToolArgs.str(line, "romanji", null)));
        }
        return edits;
    }

    private static int lineCount(JsonObject args) {
        return (args != null && args.has("lines") && args.get("lines").isJsonArray())
                ? args.getAsJsonArray("lines").size()
                : 0;
    }

    /** Presence of a non-null argument, for callers that must tell "absent" from a default. */
    private static boolean has(JsonObject args, String key) {
        return args != null && args.has(key) && !args.get(key).isJsonNull();
    }

    private static LyricActionDelegate.ActionCallback wrapActionCallback(final ToolCallback callback, final String failPrefix) {
        return new LyricActionDelegate.ActionCallback() {
            @Override
            public void onSuccess(String message) {
                callback.onResult(ToolResult.message(message));
            }

            @Override
            public void onError(String error) {
                callback.onResult(ToolResult.error(failPrefix + ": " + error));
            }
        };
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
                // onDelta() delegates to onChunk() by default, so calling only onDelta()
                // delivers each delta exactly once whether the listener overrides either method.
                listener.onDelta(delta);
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

    /**
     * Cancelled or superseded: drop the handle silently and tell the caller to unwind.
     * Mirrors the historical outer cancel paths, which removed the handle without
     * dispatching an error.
     */
    private boolean cancelledExit(Call call, ApiCallHandle handle) {
        if (call.isCanceled() || handle.isCancelled()) {
            activeHandles.remove(handle);
            return true;
        }
        return false;
    }

    /** Error path: drop the handle, then surface the failure to the listener. */
    private void failAndCleanup(Call call, ApiCallHandle handle,
                                   AntigravityApiClient.StreamListener listener, Exception e) {
        activeHandles.remove(handle);
        dispatchOnError(listener, e);
    }

    /** Attach stream-level thoughtSignature only when the functionCall itself carries none. */
    private static FunctionCall withStreamSignature(FunctionCall fc, String streamThoughtSig) {
        String fcSig = fc.getThoughtSignature();
        if (fcSig == null || fcSig.trim().isEmpty()) {
            if (streamThoughtSig != null && !streamThoughtSig.trim().isEmpty()) {
                fcSig = streamThoughtSig;
            }
        }
        if (fcSig != null) {
            return new FunctionCall(fc.name, fc.args, fc.id, fcSig, fc.rawPart);
        }
        return fc;
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
