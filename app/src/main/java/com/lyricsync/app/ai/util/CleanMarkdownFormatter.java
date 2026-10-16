package com.lyricsync.app.ai.util;

import android.content.Context;
import android.text.Html;
import android.text.Spanned;
import android.widget.TextView;

import io.noties.markwon.Markwon;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TableAwareMovementMethod;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.linkify.LinkifyPlugin;

import java.util.regex.Pattern;

public final class CleanMarkdownFormatter {
    private CleanMarkdownFormatter() {}

    private static volatile Markwon markwonInstance = null;

    /**
     * Initializes the global Markwon renderer singleton using application context.
     */
    public static void init(Context context) {
        if (context != null && markwonInstance == null) {
            getMarkwon(context);
        }
    }

    /**
     * Retrieves or builds the Markwon instance with strikethrough, tables, and linkify plugins.
     */
    public static Markwon getMarkwon(Context context) {
        if (markwonInstance == null && context != null) {
            synchronized (CleanMarkdownFormatter.class) {
                if (markwonInstance == null) {
                    try {
                        Context appCtx = context.getApplicationContext() != null
                                ? context.getApplicationContext()
                                : context;
                        markwonInstance = Markwon.builder(appCtx)
                                .usePlugin(StrikethroughPlugin.create())
                                .usePlugin(TablePlugin.create(appCtx))
                                .usePlugin(LinkifyPlugin.create())
                                .build();
                    } catch (Throwable ignored) {
                        // Safe fallback for non-Android / headless JVM test environments
                    }
                }
            }
        }
        return markwonInstance;
    }

    public static void setMarkwonInstance(Markwon markwon) {
        markwonInstance = markwon;
    }

    /**
     * Applies markdown formatting directly to a TextView using Markwon,
     * configuring TableAwareMovementMethod for clickable links and tables.
     */
    public static void applyMarkdown(TextView textView, String markdown, boolean isStreaming) {
        if (textView == null) return;
        if (markdown == null || markdown.trim().isEmpty()) {
            textView.setText("");
            return;
        }
        String safeText = isStreaming ? cleanStreamingTail(markdown) : markdown;
        try {
            Context ctx = textView.getContext();
            Markwon markwon = getMarkwon(ctx);
            if (markwon != null) {
                markwon.setMarkdown(textView, safeText);
                if (!(textView.getMovementMethod() instanceof TableAwareMovementMethod)) {
                    textView.setMovementMethod(TableAwareMovementMethod.create());
                }
                return;
            }
        } catch (Throwable ignored) {}

        // Graceful fallback if Markwon cannot be built in current environment
        textView.setText(formatClean(safeText, false));
    }

    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("```[a-zA-Z0-9_-]*\n?([\\s\\S]*?)```");
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`\n]+)`");
    private static final Pattern H1_PATTERN = Pattern.compile("(?m)^#\\s+(.*?)$");
    private static final Pattern H2_PATTERN = Pattern.compile("(?m)^##\\s+(.*?)$");
    private static final Pattern H3_PATTERN = Pattern.compile("(?m)^#{3,6}\\s+(.*?)$");
    private static final Pattern BLOCKQUOTE_PATTERN = Pattern.compile("(?m)^&gt;\\s*(.*?)$");
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^\\]]+)\\]\\((https?://[^)]+)\\)");
    private static final Pattern STRIKETHROUGH_PATTERN = Pattern.compile("~~(.*?)~~");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.*?)\\*\\*");
    private static final Pattern BOLD_UNDERSCORE_PATTERN = Pattern.compile("__(.*?)__");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("(?<!\\*)\\*(?!\\*)(.*?)(?<!\\*)\\*(?!\\*)");
    private static final Pattern BULLET_PATTERN = Pattern.compile("(?m)^[\\*\\-\\+]\\s+(.*?)$");

    /**
     * Converts markdown to semantic HTML suitable for Android's Html.fromHtml renderer.
     * Produces rich headers, blockquotes, code blocks, links, and text formatting.
     */
    public static String markdownToHtml(String text) {
        if (text == null || text.trim().isEmpty()) return "";

        // Escape HTML special chars first
        String html = text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");

        // Code fences: ```lang\ncode``` -> <pre><code>code</code></pre>
        html = CODE_FENCE_PATTERN.matcher(html).replaceAll("<pre><code>$1</code></pre>");

        // Inline code: `code` -> <tt>code</tt>
        html = INLINE_CODE_PATTERN.matcher(html).replaceAll("<tt>$1</tt>");

        // Blockquotes: > quote -> <blockquote>quote</blockquote>
        html = BLOCKQUOTE_PATTERN.matcher(html).replaceAll("<blockquote>$1</blockquote>");
        html = html.replaceAll("</blockquote>\\s*<blockquote>", "<br/>");

        // Markdown Links: [title](url) -> <a href="url">title</a>
        html = LINK_PATTERN.matcher(html).replaceAll("<a href=\"$2\">$1</a>");

        // Strikethrough: ~~text~~ -> <s>text</s>
        html = STRIKETHROUGH_PATTERN.matcher(html).replaceAll("<s>$1</s>");

        // Headers: # H1 -> <h1>H1</h1>, ## H2 -> <h2>H2</h2>, ### H3 -> <h3>H3</h3>
        html = H3_PATTERN.matcher(html).replaceAll("<h3>$1</h3>");
        html = H2_PATTERN.matcher(html).replaceAll("<h2>$1</h2>");
        html = H1_PATTERN.matcher(html).replaceAll("<h1>$1</h1>");

        // Bold: **text** -> <b>text</b>
        html = BOLD_PATTERN.matcher(html).replaceAll("<b>$1</b>");
        html = BOLD_UNDERSCORE_PATTERN.matcher(html).replaceAll("<b>$1</b>");

        // Italic: *text* -> <i>text</i>
        html = ITALIC_PATTERN.matcher(html).replaceAll("<i>$1</i>");

        // Bullet lists: * item -> &bull; item
        html = BULLET_PATTERN.matcher(html).replaceAll("&bull; $1");

        // Clean orphan markdown markers
        html = html.replace("###", "").replace("##", "").replace("**", "").replace("__", "").replace("~~", "");

        // Convert newlines to HTML breaks
        html = html.replace("\r\n", "\n").replace("\n", "<br/>");

        return html;
    }

    /**
     * Cleans streaming tail to prevent broken markdown syntax from flickering in UI.
     */
    public static String cleanStreamingTail(String text) {
        if (text == null || text.isEmpty()) return "";

        String result = text;

        // If text ends with raw opening code fence like "```java" or "```", trim it
        if (result.matches("(?s).*```[a-zA-Z0-9_-]*$")) {
            result = result.replaceFirst("```[a-zA-Z0-9_-]*$", "");
        } else {
            // Check if there is an unclosed code fence
            int fenceCount = countOccurrences(result, "```");
            if (fenceCount % 2 != 0) {
                result = result + "\n```";
            }
        }

        // Check if trailing ends with an unclosed bold
        if (result.endsWith("**")) {
            result = result.substring(0, result.length() - 2);
        } else {
            int boldCount = countOccurrences(result, "**");
            if (boldCount % 2 != 0) {
                result = result + "**";
            }
        }

        return result;
    }

    private static int countOccurrences(String str, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /**
     * Strips all markdown marks into clean, unformatted plain text.
     */
    public static String stripMarkdown(String text) {
        if (text == null || text.trim().isEmpty()) return "";

        // Remove code fences
        String result = CODE_FENCE_PATTERN.matcher(text).replaceAll("$1");
        result = INLINE_CODE_PATTERN.matcher(result).replaceAll("$1");

        // Convert blockquotes
        result = Pattern.compile("(?m)^>\\s*").matcher(result).replaceAll("");

        // Convert links [anchor](url) -> anchor
        result = LINK_PATTERN.matcher(result).replaceAll("$1");

        // Remove strikethrough
        result = STRIKETHROUGH_PATTERN.matcher(result).replaceAll("$1");

        // Convert bullet points to unicode bullet •
        result = BULLET_PATTERN.matcher(result).replaceAll("• $1");

        // Remove header marks #
        result = Pattern.compile("(?m)^#{1,6}\\s+").matcher(result).replaceAll("");

        // Remove bold **
        result = BOLD_PATTERN.matcher(result).replaceAll("$1");
        result = BOLD_UNDERSCORE_PATTERN.matcher(result).replaceAll("$1");

        // Remove italic *
        result = ITALIC_PATTERN.matcher(result).replaceAll("$1");

        // Clean any leftover orphan markers
        result = result.replace("###", "").replace("##", "").replace("**", "").replace("__", "").replace("~~", "");

        return result.trim();
    }

    /**
     * Formats markdown into a rich CharSequence for Android TextView.
     * Uses Markwon when initialized, falling back to semantic HTML and stripMarkdown.
     */
    public static CharSequence formatClean(String text) {
        return formatClean(null, text, false);
    }

    /**
     * Formats markdown with streaming support.
     * When isStreaming is true, tail guards prevent partial syntax flicker.
     */
    public static CharSequence formatClean(String text, boolean isStreaming) {
        return formatClean(null, text, isStreaming);
    }

    /**
     * Formats markdown with optional Context to initialize/leverage Markwon if available.
     */
    public static CharSequence formatClean(Context context, String text, boolean isStreaming) {
        if (text == null || text.trim().isEmpty()) return "";
        String safeText = isStreaming ? cleanStreamingTail(text) : text;
        if (context != null) {
            try {
                Markwon markwon = getMarkwon(context);
                if (markwon != null) {
                    return markwon.toMarkdown(safeText);
                }
            } catch (Throwable ignored) {}
        }
        if (markwonInstance != null) {
            try {
                return markwonInstance.toMarkdown(safeText);
            } catch (Throwable ignored) {}
        }
        try {
            String html = markdownToHtml(safeText);
            Spanned spanned = Html.fromHtml(html);
            int len = spanned.length();
            while (len > 0 && Character.isWhitespace(spanned.charAt(len - 1))) {
                len--;
            }
            return (len == spanned.length()) ? spanned : spanned.subSequence(0, len);
        } catch (Throwable t) {
            // Safe fallback for JVM unit tests without Android runtime
            return stripMarkdown(safeText);
        }
    }

    public static class SourceChipData {
        public final String displayLabel;
        public final String targetUrl;

        public SourceChipData(String displayLabel, String targetUrl) {
            this.displayLabel = displayLabel;
            this.targetUrl = targetUrl;
        }
    }

    /**
     * Resolves a raw web source entry into a clean, human-readable chip label
     * and valid target URL. Strictly eliminates "vertex" / internal redirect noise.
     */
    public static SourceChipData resolveSourceChip(String rawSource, android.content.Context context) {
        if (rawSource == null || rawSource.trim().isEmpty()) return null;
        String raw = rawSource.trim();
        String label = null;
        String url = raw;

        if (raw.contains("|")) {
            String[] parts = raw.split("\\|", 2);
            label = parts[0].trim();
            url = parts[1].trim();
        }

        // Clean up url
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            if (url.contains(".") && !url.contains(" ")) {
                url = "https://" + url;
            } else {
                try {
                    url = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(url, "UTF-8");
                } catch (Exception e) {
                    url = "https://www.google.com/search?q=" + url;
                }
            }
        }

        // If url is a Vertex redirect URL with ?url= parameter, extract target url
        if (url.contains("vertexaisearch.cloud.google.com/grounding-api-redirect")) {
            int urlIdx = url.indexOf("?url=");
            if (urlIdx == -1) urlIdx = url.indexOf("&url=");
            if (urlIdx != -1) {
                try {
                    String encoded = url.substring(urlIdx + 5);
                    int amp = encoded.indexOf('&');
                    if (amp != -1) encoded = encoded.substring(0, amp);
                    String decoded = java.net.URLDecoder.decode(encoded, "UTF-8");
                    if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                        url = decoded;
                    }
                } catch (Exception ignored) {}
            }
        }

        // Derive clean label
        if (label == null || label.isEmpty() || label.toLowerCase(java.util.Locale.ROOT).contains("vertexaisearch")) {
            try {
                java.net.URI uri = new java.net.URI(url);
                String host = uri.getHost();
                if (host != null) {
                    label = host.startsWith("www.") ? host.substring(4) : host;
                }
            } catch (Exception ignored) {}
        }

        if (label == null || label.isEmpty() || label.toLowerCase(java.util.Locale.ROOT).contains("vertexaisearch")) {
            if (context != null) {
                try {
                    int resId = context.getResources().getIdentifier("ai_source_web", "string", context.getPackageName());
                    label = (resId != 0) ? context.getString(resId) : "Web Source";
                } catch (Throwable t) {
                    label = "Web Source";
                }
            } else {
                label = "Web Source";
            }
        }

        return new SourceChipData(label, url);
    }

    public static class ToolDisplayInfo {
        public final int iconRes;
        public final String runningText;
        public final String doneText;

        public ToolDisplayInfo(int iconRes, String runningText, String doneText) {
            this.iconRes = iconRes;
            this.runningText = runningText != null ? runningText : "";
            this.doneText = doneText != null ? doneText : "";
        }
    }

    public static ToolDisplayInfo formatTool(String toolName, String queryOrUrl, int resultsCount, Context context) {
        int icon = com.lyricsync.app.R.drawable.ic_search;
        String running = "";
        String done = "Selesai";

        if ("web_fetch".equals(toolName)) {
            icon = com.lyricsync.app.R.drawable.ic_search_globe;
            String host = queryOrUrl;
            try {
                android.net.Uri uri = android.net.Uri.parse(queryOrUrl);
                if (uri.getHost() != null) host = uri.getHost();
            } catch (Exception ignored) {}
            running = (context != null ? context.getString(com.lyricsync.app.R.string.ai_tool_fetching) : "Mengambil isi halaman") + (host != null ? " (" + host + ")" : "");
            done = context != null ? context.getString(com.lyricsync.app.R.string.ai_tool_fetch_done) : "Halaman berhasil dibaca";
        } else if ("translate_lyrics".equals(toolName)) {
            icon = com.lyricsync.app.R.drawable.ic_sparkles;
            running = "Menerjemahkan lirik (" + queryOrUrl + ")…";
            done = "Lirik berhasil diterjemahkan!";
        } else if ("transliterate_lyrics".equals(toolName)) {
            icon = com.lyricsync.app.R.drawable.ic_sparkles;
            running = "Menerapkan transliterasi Romaji/Latin…";
            done = "Transliterasi berhasil diterapkan!";
        } else if ("set_sync_offset".equals(toolName)) {
            icon = com.lyricsync.app.R.drawable.ic_settings;
            running = "Menyesuaikan timing lirik (" + queryOrUrl + " ms)…";
            done = "Sinkronisasi berhasil disesuaikan!";
        } else if ("modify_lyric_line".equals(toolName)) {
            icon = com.lyricsync.app.R.drawable.ic_sparkles;
            running = "Memperbarui " + queryOrUrl + " di overlay…";
            done = "Baris lirik berhasil diperbarui!";
        } else if ("batch_edit_lyrics".equals(toolName)) {
            icon = com.lyricsync.app.R.drawable.ic_sparkles;
            running = "Memperbarui " + queryOrUrl + " di overlay…";
            done = "Lirik berhasil diperbarui!";
        } else if ("toggle_display".equals(toolName)) {
            icon = com.lyricsync.app.R.drawable.ic_sparkles;
            running = "Mengubah tampilan: " + queryOrUrl + "…";
            done = "Pengaturan tampilan diperbarui!";
        } else if ("control_playback".equals(toolName)) {
            icon = com.lyricsync.app.R.drawable.ic_play;
            running = "Mengontrol pemutaran musik (" + queryOrUrl + ")…";
            done = "Pemutaran musik diperbarui!";
        } else if ("adjust_overlay_appearance".equals(toolName)) {
            icon = com.lyricsync.app.R.drawable.ic_settings;
            running = "Menyesuaikan tampilan overlay…";
            done = "Tampilan overlay diperbarui!";
        } else if ("manage_lyric_lines".equals(toolName)) {
            icon = com.lyricsync.app.R.drawable.ic_lyrics_lines;
            running = "Mengatur baris lirik (" + queryOrUrl + ")…";
            done = "Baris lirik berhasil diperbarui!";
        } else if ("get_current_playing_track".equals(toolName)) {
            icon = com.lyricsync.app.R.drawable.ic_music_note;
            running = "Memeriksa lagu yang sedang diputar…";
            done = "Info lagu aktif berhasil didapatkan!";
        } else {
            String query = queryOrUrl != null ? queryOrUrl : "";
            if (query.length() > 50) query = query.substring(0, 50) + "…";
            running = (context != null ? context.getString(com.lyricsync.app.R.string.ai_tool_searching) : "Mencari") + " \"" + query + "\"";
            done = context != null ? context.getString(com.lyricsync.app.R.string.ai_tool_search_done, resultsCount) : "Ditemukan " + resultsCount + " sumber";
        }
        return new ToolDisplayInfo(icon, running, done);
    }
}
