package com.lyricsync.app.ai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.ai.util.ApiErrors;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Lists the model ids of an OpenAI-compatible API ({@code GET <base>/models}). */
public final class OpenAiModels {
    private OpenAiModels() {}

    public interface Result {
        /** Called on an OkHttp thread. */
        void onModels(List<String> ids);

        void onError(String message);
    }

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    /** Starts the scan; cancel the returned call to drop a stale one. Null when the URL is invalid. */
    public static Call fetch(String baseUrl, String apiKey, Result cb) {
        String base = AiProvider.normalizeBaseUrl(baseUrl);
        if (base.isEmpty()) {
            cb.onError("invalid URL");
            return null;
        }
        Request.Builder b = new Request.Builder().url(base + "/models").get();
        if (apiKey != null && !apiKey.trim().isEmpty()) b.addHeader("Authorization", "Bearer " + apiKey.trim());
        Call call = HTTP.newCall(b.build());
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call c, IOException e) {
                if (!c.isCanceled()) cb.onError(e.getMessage() != null ? e.getMessage() : "network error");
            }

            @Override
            public void onResponse(Call c, Response response) {
                try (ResponseBody body = response.body()) {
                    String text = body != null ? body.string() : "";
                    if (!response.isSuccessful()) {
                        cb.onError(ApiErrors.message(text, "HTTP " + response.code()));
                        return;
                    }
                    List<String> ids = parse(text);
                    if (ids.isEmpty()) cb.onError("no models in the response");
                    else cb.onModels(ids);
                } catch (Exception e) {
                    cb.onError(e.getMessage() != null ? e.getMessage() : "bad response");
                }
            }
        });
        return call;
    }

    /** Ids from {@code {"data":[{"id":…}]}} (OpenAI), {@code {"models":[…]}} or a bare array; sorted, unique. */
    static List<String> parse(String json) {
        List<String> ids = new ArrayList<>();
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (Exception e) {
            return ids;
        }
        JsonArray list = root.isJsonArray() ? root.getAsJsonArray() : null;
        if (list == null && root.isJsonObject()) {
            JsonObject o = root.getAsJsonObject();
            for (String key : new String[]{"data", "models"}) {
                if (o.has(key) && o.get(key).isJsonArray()) {
                    list = o.getAsJsonArray(key);
                    break;
                }
            }
        }
        if (list == null) return ids;
        for (JsonElement e : list) {
            String id = null;
            if (e.isJsonPrimitive()) id = e.getAsString();
            else if (e.isJsonObject()) {
                JsonObject m = e.getAsJsonObject();
                JsonElement v = m.has("id") ? m.get("id") : m.get("name");
                if (v != null && v.isJsonPrimitive()) id = v.getAsString();
            }
            if (id != null && !id.trim().isEmpty() && !ids.contains(id.trim())) ids.add(id.trim());
        }
        Collections.sort(ids, String.CASE_INSENSITIVE_ORDER);
        return ids;
    }
}
