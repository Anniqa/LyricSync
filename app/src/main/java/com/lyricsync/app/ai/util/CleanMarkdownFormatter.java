package com.lyricsync.app.ai.util;

import android.text.Html;
import android.text.Spanned;

import java.util.regex.Pattern;

public final class CleanMarkdownFormatter {
    private CleanMarkdownFormatter() {}

    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("```[a-zA-Z]*\n?([\\s\\S]*?)```");
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`]+)`");
    private static final Pattern HEADER_PATTERN = Pattern.compile("(?m)^#{1,6}\\s*(.*?)$");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.*?)\\*\\*");
    private static final Pattern BOLD_UNDERSCORE_PATTERN = Pattern.compile("__(.*?)__");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("(?<!\\*)\\*(?!\\*)(.*?)(?<!\\*)\\*(?!\\*)");
    private static final Pattern BULLET_PATTERN = Pattern.compile("(?m)^[\\*\\-\\+]\\s+(.*?)$");

    /**
     * Converts markdown to basic HTML suitable for Android's Html.fromHtml renderer.
     */
    public static String markdownToHtml(String text) {
        if (text == null || text.trim().isEmpty()) return "";

        // Escape HTML special chars first
        String html = text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");

        // Code fences: ```code``` -> <tt>code</tt>
        html = CODE_FENCE_PATTERN.matcher(html).replaceAll("<tt>$1</tt>");

        // Inline code: `code` -> <tt>code</tt>
        html = INLINE_CODE_PATTERN.matcher(html).replaceAll("<tt>$1</tt>");

        // Headers: ### Header -> <b>Header</b>
        html = HEADER_PATTERN.matcher(html).replaceAll("<b>$1</b>");

        // Bold: **text** -> <b>text</b>
        html = BOLD_PATTERN.matcher(html).replaceAll("<b>$1</b>");
        html = BOLD_UNDERSCORE_PATTERN.matcher(html).replaceAll("<b>$1</b>");

        // Italic: *text* -> <i>text</i>
        html = ITALIC_PATTERN.matcher(html).replaceAll("<i>$1</i>");

        // Bullet lists: * item -> &bull; item
        html = BULLET_PATTERN.matcher(html).replaceAll("&bull; $1");

        // Clean orphan markdown markers
        html = html.replace("###", "").replace("##", "").replace("**", "").replace("__", "");

        // Convert newlines to HTML breaks
        html = html.replace("\r\n", "\n").replace("\n", "<br/>");

        return html;
    }

    /**
     * Strips all markdown marks into clean, unformatted plain text.
     */
    public static String stripMarkdown(String text) {
        if (text == null || text.trim().isEmpty()) return "";

        // Remove code fences
        String result = CODE_FENCE_PATTERN.matcher(text).replaceAll("$1");
        result = INLINE_CODE_PATTERN.matcher(result).replaceAll("$1");

        // Convert bullet points to unicode bullet •
        result = BULLET_PATTERN.matcher(result).replaceAll("• $1");

        // Remove header marks #
        result = HEADER_PATTERN.matcher(result).replaceAll("$1");

        // Remove bold **
        result = BOLD_PATTERN.matcher(result).replaceAll("$1");
        result = BOLD_UNDERSCORE_PATTERN.matcher(result).replaceAll("$1");

        // Remove italic *
        result = ITALIC_PATTERN.matcher(result).replaceAll("$1");

        // Clean any leftover orphan markers
        result = result.replace("###", "").replace("##", "").replace("**", "").replace("__", "");

        return result.trim();
    }

    /**
     * Formats markdown into a rich CharSequence for Android TextView.
     * Gracefully falls back to stripMarkdown in JVM testing environments.
     */
    public static CharSequence formatClean(String text) {
        if (text == null || text.trim().isEmpty()) return "";
        try {
            String html = markdownToHtml(text);
            Spanned spanned = Html.fromHtml(html);
            // Trim trailing newlines produced by Html.fromHtml
            int len = spanned.length();
            while (len > 0 && Character.isWhitespace(spanned.charAt(len - 1))) {
                len--;
            }
            return (len == spanned.length()) ? spanned : spanned.subSequence(0, len);
        } catch (Throwable t) {
            // Safe fallback for JVM unit tests without Android runtime
            return stripMarkdown(text);
        }
    }
}
