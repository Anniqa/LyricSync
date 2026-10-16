package com.lyricsync.app.ai.util;

import java.util.regex.Pattern;

/**
 * Turns a model reply into something {@code JsonParser} can read.
 *
 * <p>Models wrap JSON in {@code ```json} fences, pad it with prose, and occasionally leave a
 * trailing comma. Three callers each kept their own hand-rolled version of this and each
 * handled a different subset; this is the union, in one place.
 */
public final class JsonResponse {

    private static final Pattern TRAILING_COMMA = Pattern.compile(",\\s*([}\\]])");

    private JsonResponse() {}

    /** The JSON payload in {@code raw}, or "" when there is nothing to parse. */
    public static String extract(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.isEmpty()) return "";
        return TRAILING_COMMA.matcher(outermost(stripFence(text))).replaceAll("$1");
    }

    /**
     * The text between the first ```json (or plain ```) fence and the next one; {@code text}
     * itself when it carries no fence. Comparison is case-insensitive without case-folding the
     * string, which would shift the offsets for characters whose lower-case form is longer.
     */
    private static String stripFence(String text) {
        int start = indexOfIgnoreCase(text, "```json", 0);
        int tagLength = 7;
        if (start == -1) {
            start = indexOfIgnoreCase(text, "```", 0);
            tagLength = 3;
        }
        if (start == -1) return text;

        int contentStart = start + tagLength;
        int end = text.indexOf("```", contentStart);
        return (end != -1 ? text.substring(contentStart, end) : text.substring(contentStart)).trim();
    }

    /** The outermost {@code [...]} or {@code {...},} whichever opens first. */
    private static String outermost(String text) {
        int brace = text.indexOf('{');
        int bracket = text.indexOf('[');
        boolean arrayFirst = bracket != -1 && (brace == -1 || bracket < brace);

        int from = arrayFirst ? bracket : brace;
        if (from != -1) {
            int to = text.lastIndexOf(arrayFirst ? ']' : '}');
            if (to > from) return text.substring(from, to + 1).trim();
        }
        if (arrayFirst) return text;

        // No usable object: fall back to an array, which is what the caller may have meant.
        if (bracket != -1) {
            int to = text.lastIndexOf(']');
            if (to > bracket) return text.substring(bracket, to + 1).trim();
        }
        return text;
    }

    private static int indexOfIgnoreCase(String text, String needle, int from) {
        int last = text.length() - needle.length();
        for (int i = Math.max(0, from); i <= last; i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }
}
