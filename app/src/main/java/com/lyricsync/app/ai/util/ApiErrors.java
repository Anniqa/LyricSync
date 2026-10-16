package com.lyricsync.app.ai.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Pulls a human-readable message out of an API error body.
 *
 * <p>These endpoints answer with several shapes — {@code {"error_description":"…"}} (OAuth),
 * {@code {"error":{"message":"…"}}} (Cloud Code) and {@code {"error":"…"}} — so the reader
 * accepts all of them. Each caller used to keep its own narrower copy and silently returned
 * the fallback for the shapes it did not expect.
 */
public final class ApiErrors {

    private ApiErrors() {}

    /** The message carried by {@code jsonBody}, or {@code fallback} when it carries none. */
    public static String message(String jsonBody, String fallback) {
        if (jsonBody == null || jsonBody.trim().isEmpty()) return fallback;
        try {
            JsonObject json = JsonParser.parseString(jsonBody).getAsJsonObject();
            String described = text(json.get("error_description"));
            if (described != null) return described;

            JsonElement error = json.get("error");
            if (error != null && !error.isJsonNull()) {
                if (error.isJsonObject()) {
                    String nested = text(error.getAsJsonObject().get("message"));
                    if (nested != null) return nested;
                } else {
                    String plain = text(error);
                    if (plain != null) return plain;
                }
            }

            String topLevel = text(json.get("message"));
            return topLevel != null ? topLevel : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /** The element as non-blank text, or null when absent, null, or not a primitive. */
    private static String text(JsonElement el) {
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return null;
        String text = el.getAsString();
        return text != null && !text.trim().isEmpty() ? text : null;
    }
}
