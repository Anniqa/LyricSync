package com.lyricsync.app.lyrics.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Shared OkHttp client and tiny JSON helpers for the lyrics providers. */
final class Http {

    static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0 Safari/537.36";

    /** One pool for every provider; short timeouts so a slow source never stalls the cascade. */
    static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private Http() {}

    /** GET {@code url} and parse the body as JSON; 404 returns null, other failures throw. */
    static JsonElement getJson(OkHttpClient client, String url, String... headers) throws IOException {
        Request.Builder b = new Request.Builder().url(url).get();
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        try (Response r = client.newCall(b.build()).execute()) {
            if (r.code() == 404) return null;
            if (!r.isSuccessful() || r.body() == null) throw new IOException("HTTP " + r.code());
            try {
                return JsonParser.parseString(r.body().string());
            } catch (RuntimeException e) {
                throw new IOException("Invalid JSON from " + r.request().url().host());
            }
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        try {
            return o.get(key).getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    static long num(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return 0;
        try {
            return o.get(key).getAsLong();
        } catch (RuntimeException e) {
            try {
                return Math.round(o.get(key).getAsDouble());
            } catch (RuntimeException e2) {
                return 0;
            }
        }
    }

    static JsonObject obj(JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }
}
