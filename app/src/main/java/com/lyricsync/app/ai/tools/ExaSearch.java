package com.lyricsync.app.ai.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor.SourceItem;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor.ToolCallback;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor.ToolCancellable;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor.ToolResult;
import com.lyricsync.app.ai.util.ApiErrors;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** {@code web_search} through Exa (https://exa.ai): results with highlights, same result shape as Gemini grounding. */
final class ExaSearch {
    private ExaSearch() {}

    static final String ENDPOINT = "https://api.exa.ai/search";
    private static final int MAX_HIGHLIGHT_CHARS = 1200;

    static void search(OkHttpClient http, String apiKey, String query, int maxResults,
                       ToolCancellable handle, ToolCallback callback) {
        JsonObject contents = new JsonObject();
        contents.addProperty("highlights", true);
        JsonObject body = new JsonObject();
        body.addProperty("query", query.trim());
        body.addProperty("type", "auto");
        body.addProperty("numResults", Math.max(1, Math.min(maxResults > 0 ? maxResults : 5, 10)));
        body.add("contents", contents);
        Request request = new Request.Builder()
                .url(ENDPOINT)
                .addHeader("x-api-key", apiKey)
                .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), body.toString()))
                .build();
        Call call = http.newCall(request);
        handle.setCall(call);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call c, IOException e) {
                if (!handle.isCancelled()) callback.onResult(AntigravityToolExecutor.createGracefulErrorResult(
                        "Exa search failed: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call c, Response response) {
                if (handle.isCancelled()) return;
                try (ResponseBody b = response.body()) {
                    String text = b != null ? b.string() : "";
                    callback.onResult(response.isSuccessful() ? parse(query, text)
                            : AntigravityToolExecutor.createGracefulErrorResult("Exa search failed: "
                            + ApiErrors.message(text, "HTTP " + response.code())));
                } catch (Exception e) {
                    callback.onResult(AntigravityToolExecutor.createGracefulErrorResult("Exa search failed: " + e.getMessage()));
                }
            }
        });
    }

    /** Exa results → {query, results:[{title,url,published,highlights}], sources}; the model writes the answer. */
    static ToolResult parse(String query, String json) {
        JsonArray results;
        try {
            JsonElement root = JsonParser.parseString(json);
            JsonElement r = root.isJsonObject() ? root.getAsJsonObject().get("results") : null;
            results = r != null && r.isJsonArray() ? r.getAsJsonArray() : new JsonArray();
        } catch (Exception e) {
            return AntigravityToolExecutor.createGracefulErrorResult("Exa returned an unreadable response.");
        }
        List<SourceItem> sources = new ArrayList<>();
        JsonArray out = new JsonArray();
        for (JsonElement el : results) {
            if (!el.isJsonObject()) continue;
            JsonObject r = el.getAsJsonObject();
            String url = str(r, "url"), title = str(r, "title");
            if (url.isEmpty()) continue;
            StringBuilder hl = new StringBuilder();
            JsonElement h = r.get("highlights");
            if (h != null && h.isJsonArray()) {
                for (JsonElement x : h.getAsJsonArray()) {
                    if (!x.isJsonPrimitive() || hl.length() >= MAX_HIGHLIGHT_CHARS) continue;
                    if (hl.length() > 0) hl.append(" … ");
                    hl.append(x.getAsString().trim());
                }
            }
            if (hl.length() == 0) hl.append(str(r, "text"));
            String snippet = hl.length() > MAX_HIGHLIGHT_CHARS ? hl.substring(0, MAX_HIGHLIGHT_CHARS) + "…" : hl.toString();
            JsonObject o = new JsonObject();
            o.addProperty("title", title);
            o.addProperty("url", url);
            String published = str(r, "publishedDate");
            if (!published.isEmpty()) o.addProperty("published", published.length() > 10 ? published.substring(0, 10) : published);
            o.addProperty("highlights", snippet);
            out.add(o);
            sources.add(new SourceItem(title.isEmpty() ? url : title, url, snippet));
        }
        if (sources.isEmpty()) {
            return AntigravityToolExecutor.createGracefulErrorResult("Exa found nothing for: " + query);
        }
        JsonObject output = new JsonObject();
        output.addProperty("engine", "exa");
        output.addProperty("query", query);
        output.add("results", out);
        return new ToolResult(false, output.toString(), sources);
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString().trim() : "";
    }
}
