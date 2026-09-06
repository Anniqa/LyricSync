package com.lyricsync.app.ai.util;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

public final class CleanMarkdownFormatter {
    private CleanMarkdownFormatter() {}

    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("```[a-zA-Z]*\n?([\\s\\S]*?)```");
    private static final Pattern HEADER_PATTERN = Pattern.compile("(?m)^#{1,6}\\s*(.*?)$");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.*?)\\*\\*");
    private static final Pattern BOLD_UNDERSCORE_PATTERN = Pattern.compile("__(.*?)__");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("(?<!\\*)\\*(?!\\*)(.*?)(?<!\\*)\\*(?!\\*)");
    private static final Pattern BULLET_PATTERN = Pattern.compile("(?m)^[\\*\\-\\+]\\s+(.*?)$");

    public static String stripMarkdown(String text) {
        if (text == null || text.trim().isEmpty()) return "";

        // Remove code fences
        String result = CODE_FENCE_PATTERN.matcher(text).replaceAll("$1");

        // Convert bullet points to unicode bullet •
        result = BULLET_PATTERN.matcher(result).replaceAll("• $1");

        // Remove header marks #
        result = HEADER_PATTERN.matcher(result).replaceAll("$1");

        // Remove bold **
        result = BOLD_PATTERN.matcher(result).replaceAll("$1");
        result = BOLD_UNDERSCORE_PATTERN.matcher(result).replaceAll("$1");

        // Remove italic *
        result = ITALIC_PATTERN.matcher(result).replaceAll("$1");

        // Clean any leftover orphan ** or ##
        result = result.replace("**", "").replace("##", "").replace("###", "").replace("__", "");

        return result.trim();
    }

    public static CharSequence formatClean(String text) {
        return stripMarkdown(text);
    }
}
