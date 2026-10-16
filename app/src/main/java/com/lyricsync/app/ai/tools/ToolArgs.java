package com.lyricsync.app.ai.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Lenient readers for function-call arguments.
 *
 * <p>A model does not always type arguments the way the schema asks: an index that is
 * declared INTEGER commonly arrives as {@code "3"}, a flag as {@code "true"}. Reading with
 * these instead of {@code getAsInt()}/{@code getAsString()} means a mistyped argument
 * degrades one value rather than aborting the whole tool call, and an absent argument
 * never throws.
 *
 * <p>Each reader takes a fallback so callers can say what "not given" means for them
 * ({@code -1} for an index, {@code null} for "leave unchanged").
 */
public final class ToolArgs {

    private ToolArgs() {}

    /** The argument as text, or {@code fallback} when it is absent or null. */
    public static String str(JsonObject args, String key, String fallback) {
        JsonElement el = element(args, key);
        if (el == null) return fallback;
        return el.isJsonPrimitive() ? el.getAsString() : el.toString();
    }

    public static int intValue(JsonObject args, String key, int fallback) {
        Double value = number(args, key);
        return value != null ? (int) Math.round(value) : fallback;
    }

    public static long longValue(JsonObject args, String key, long fallback) {
        Double value = number(args, key);
        return value != null ? Math.round(value) : fallback;
    }

    /** Accepts a real boolean, or the strings "true"/"false". Anything else is the fallback. */
    public static boolean bool(JsonObject args, String key, boolean fallback) {
        JsonElement el = element(args, key);
        if (el == null || !el.isJsonPrimitive()) return fallback;
        if (el.getAsJsonPrimitive().isBoolean()) return el.getAsBoolean();
        String text = el.getAsString().trim();
        if ("true".equalsIgnoreCase(text)) return true;
        if ("false".equalsIgnoreCase(text)) return false;
        return fallback;
    }

    /** Null rather than a fallback, so a caller can tell "absent" from a real zero. */
    public static Float floatOrNull(JsonObject args, String key) {
        Double value = number(args, key);
        return value != null ? value.floatValue() : null;
    }

    public static Integer intOrNull(JsonObject args, String key) {
        Double value = number(args, key);
        return value != null ? (int) Math.round(value) : null;
    }

    private static Double number(JsonObject args, String key) {
        JsonElement el = element(args, key);
        if (el == null || !el.isJsonPrimitive()) return null;
        try {
            return el.getAsDouble();
        } catch (NumberFormatException | UnsupportedOperationException | IllegalStateException e) {
            return null;
        }
    }

    private static JsonElement element(JsonObject args, String key) {
        if (args == null || key == null || !args.has(key)) return null;
        JsonElement el = args.get(key);
        return (el == null || el.isJsonNull()) ? null : el;
    }
}
