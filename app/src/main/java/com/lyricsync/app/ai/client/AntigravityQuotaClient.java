package com.lyricsync.app.ai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.ai.auth.AntigravityAuthManager;
import com.lyricsync.app.ai.model.AntigravityModel;
import com.lyricsync.app.ai.model.QuotaSummary;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Handles dynamic tier model discovery and live quota usage retrieval
 * via Google Antigravity Cloudcode PA management endpoints.
 */
public class AntigravityQuotaClient {

    public static final String ENDPOINT_MODELS = "https://daily-cloudcode-pa.googleapis.com/v1internal:fetchAvailableModels";
    public static final String ENDPOINT_QUOTA = "https://daily-cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary";
    public static final String USER_AGENT = "antigravity/ide/2.11.0 darwin/arm64";
    public static final String DEFAULT_PROJECT = "aicode-consumers";

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    public interface ModelDiscoveryCallback {
        void onModelsLoaded(List<AntigravityModel> models);
        void onError(String error);
    }

    public interface QuotaCallback {
        void onQuotaLoaded(QuotaSummary summary);
        void onError(String error);
    }

    private final AntigravityAuthManager authManager;
    private final OkHttpClient httpClient;

    public AntigravityQuotaClient(AntigravityAuthManager authManager) {
        this(authManager, new OkHttpClient());
    }

    public AntigravityQuotaClient(AntigravityAuthManager authManager, OkHttpClient httpClient) {
        this.authManager = authManager;
        this.httpClient = httpClient != null ? httpClient : new OkHttpClient();
    }

    /**
     * Parses the JSON payload from retrieveUserQuotaSummary into a QuotaSummary domain object.
     */
    public static QuotaSummary parseQuota(String json) {
        QuotaSummary summary = new QuotaSummary();
        if (json == null || json.trim().isEmpty()) {
            return summary;
        }

        try {
            JsonElement rootElem = JsonParser.parseString(json);
            if (!rootElem.isJsonObject()) {
                return summary;
            }
            JsonObject root = rootElem.getAsJsonObject();
            if (root.has("groups") && root.get("groups").isJsonArray()) {
                JsonArray groups = root.getAsJsonArray("groups");
                for (JsonElement groupElem : groups) {
                    if (!groupElem.isJsonObject()) continue;
                    JsonObject group = groupElem.getAsJsonObject();
                    if (group.has("buckets") && group.get("buckets").isJsonArray()) {
                        JsonArray buckets = group.getAsJsonArray("buckets");
                        for (JsonElement bucketElem : buckets) {
                            if (!bucketElem.isJsonObject()) continue;
                            JsonObject bucket = bucketElem.getAsJsonObject();
                            String bucketId = bucket.has("bucketId") && !bucket.get("bucketId").isJsonNull()
                                    ? bucket.get("bucketId").getAsString() : "";
                            float fraction = bucket.has("remainingFraction") && !bucket.get("remainingFraction").isJsonNull()
                                    ? bucket.get("remainingFraction").getAsFloat() : 0.0f;
                            String resetTime = bucket.has("resetTime") && !bucket.get("resetTime").isJsonNull()
                                    ? bucket.get("resetTime").getAsString() : null;

                            if ("gemini-weekly".equalsIgnoreCase(bucketId)) {
                                summary.geminiWeeklyFraction = fraction;
                                summary.geminiWeeklyResetTime = resetTime;
                            } else if ("gemini-5h".equalsIgnoreCase(bucketId)) {
                                summary.gemini5hFraction = fraction;
                                summary.gemini5hResetTime = resetTime;
                            } else if ("third-party-weekly".equalsIgnoreCase(bucketId) || "3p-weekly".equalsIgnoreCase(bucketId)) {
                                summary.thirdPartyWeeklyFraction = fraction;
                                summary.thirdPartyWeeklyResetTime = resetTime;
                            } else if ("third-party-5h".equalsIgnoreCase(bucketId) || "3p-5h".equalsIgnoreCase(bucketId)) {
                                summary.thirdParty5hFraction = fraction;
                                summary.thirdParty5hResetTime = resetTime;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return summary;
    }

    /**
     * Parses the JSON payload from fetchAvailableModels, filtering out internal models.
     */
    public static List<AntigravityModel> parseModels(String json) {
        List<AntigravityModel> models = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return models;
        }

        try {
            JsonElement rootElem = JsonParser.parseString(json);
            if (!rootElem.isJsonObject()) {
                return models;
            }
            JsonObject root = rootElem.getAsJsonObject();
            if (root.has("models") && root.get("models").isJsonObject()) {
                JsonObject modelsObj = root.getAsJsonObject("models");
                for (Map.Entry<String, JsonElement> entry : modelsObj.entrySet()) {
                    String modelId = entry.getKey();
                    if (!entry.getValue().isJsonObject()) continue;
                    JsonObject mObj = entry.getValue().getAsJsonObject();

                    boolean isInternal = mObj.has("isInternal") && !mObj.get("isInternal").isJsonNull()
                            && mObj.get("isInternal").getAsBoolean();
                    if (isInternal) {
                        continue;
                    }
                    if ("chat_20706".equalsIgnoreCase(modelId) || "chat_23310".equalsIgnoreCase(modelId)) {
                        continue;
                    }

                    String displayName = mObj.has("displayName") && !mObj.get("displayName").isJsonNull()
                            ? mObj.get("displayName").getAsString() : modelId;

                    int maxOutputTokens = 0;
                    if (mObj.has("maxOutputTokens") && !mObj.get("maxOutputTokens").isJsonNull()) {
                        maxOutputTokens = mObj.get("maxOutputTokens").getAsInt();
                    } else if (mObj.has("maxTokens") && !mObj.get("maxTokens").isJsonNull()) {
                        maxOutputTokens = mObj.get("maxTokens").getAsInt();
                    }

                    boolean supportsThinking = mObj.has("supportsThinking") && !mObj.get("supportsThinking").isJsonNull()
                            && mObj.get("supportsThinking").getAsBoolean();

                    AntigravityModel model = new AntigravityModel(modelId, displayName, maxOutputTokens, supportsThinking, false);
                    models.add(model);
                }
            }
        } catch (Exception ignored) {}

        return models;
    }

    /**
     * Asynchronously discovers available tier models from Google Antigravity.
     */
    public void fetchModels(final ModelDiscoveryCallback callback) {
        if (authManager == null) {
            if (callback != null) {
                callback.onError("AuthManager is null");
            }
            return;
        }

        authManager.getValidAccessToken(new AntigravityAuthManager.TokenCallback() {
            @Override
            public void onTokenReady(String accessToken) {
                executeFetchModels(accessToken, callback);
            }

            @Override
            public void onError(String message) {
                if (callback != null) {
                    callback.onError(message);
                }
            }
        });
    }

    private void executeFetchModels(String accessToken, final ModelDiscoveryCallback callback) {
        RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, "{}");
        Request request = new Request.Builder()
                .url(ENDPOINT_MODELS)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try {
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    if (callback != null) {
                        callback.onError("Network error fetching models: " + e.getMessage());
                    }
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (ResponseBody responseBody = response.body()) {
                        if (responseBody == null) {
                            if (callback != null) {
                                callback.onError("Empty response fetching models");
                            }
                            return;
                        }
                        String responseString = responseBody.string();
                        if (!response.isSuccessful()) {
                            String error = parseErrorMessage(responseString, "HTTP " + response.code());
                            if (callback != null) {
                                callback.onError("Failed to fetch models: " + error);
                            }
                            return;
                        }

                        List<AntigravityModel> models = parseModels(responseString);
                        if (callback != null) {
                            callback.onModelsLoaded(models);
                        }
                    } catch (Exception e) {
                        if (callback != null) {
                            callback.onError("Error parsing models response: " + e.getMessage());
                        }
                    }
                }
            });
        } catch (Exception e) {
            if (callback != null) {
                callback.onError("Failed to initiate models request: " + e.getMessage());
            }
        }
    }

    /**
     * Asynchronously retrieves current user quota usage and reset windows.
     */
    public void fetchQuota(final QuotaCallback callback) {
        if (authManager == null) {
            if (callback != null) {
                callback.onError("AuthManager is null");
            }
            return;
        }

        authManager.getValidAccessToken(new AntigravityAuthManager.TokenCallback() {
            @Override
            public void onTokenReady(String accessToken) {
                executeFetchQuota(accessToken, callback);
            }

            @Override
            public void onError(String message) {
                if (callback != null) {
                    callback.onError(message);
                }
            }
        });
    }

    private void executeFetchQuota(String accessToken, final QuotaCallback callback) {
        String payload = "{\"project\":\"" + DEFAULT_PROJECT + "\"}";
        RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, payload);
        Request request = new Request.Builder()
                .url(ENDPOINT_QUOTA)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try {
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    if (callback != null) {
                        callback.onError("Network error fetching quota: " + e.getMessage());
                    }
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (ResponseBody responseBody = response.body()) {
                        if (responseBody == null) {
                            if (callback != null) {
                                callback.onError("Empty response fetching quota");
                            }
                            return;
                        }
                        String responseString = responseBody.string();
                        if (!response.isSuccessful()) {
                            String error = parseErrorMessage(responseString, "HTTP " + response.code());
                            if (callback != null) {
                                callback.onError("Failed to fetch quota: " + error);
                            }
                            return;
                        }

                        QuotaSummary summary = parseQuota(responseString);
                        if (callback != null) {
                            callback.onQuotaLoaded(summary);
                        }
                    } catch (Exception e) {
                        if (callback != null) {
                            callback.onError("Error parsing quota response: " + e.getMessage());
                        }
                    }
                }
            });
        } catch (Exception e) {
            if (callback != null) {
                callback.onError("Failed to initiate quota request: " + e.getMessage());
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
}
