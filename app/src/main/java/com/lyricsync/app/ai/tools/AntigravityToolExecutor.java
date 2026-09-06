package com.lyricsync.app.ai.tools;

import com.google.gson.JsonObject;
import com.lyricsync.app.ai.client.AntigravityApiClient.Cancellable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

/**
 * Autonomous AI Agent Tool Executor for LyricSync.
 * Executes web searches and fetches web pages with strict anti-SSRF protections,
 * clean HTML text extraction, and token context safety limits.
 */
public class AntigravityToolExecutor {

    public static final int MAX_CONTENT_LENGTH = 8000;
    public static final long MAX_RESPONSE_BYTES = 1024 * 1024L; // 1 MB buffer limit
    public static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36";

    // --- Pre-compiled Regex Patterns ---

    private static final Pattern HOST_ALLOWED_PATTERN = Pattern.compile("^[a-zA-Z0-9.:%_-]+$");
    private static final Pattern IP_LITERAL_PATTERN = Pattern.compile("^\\d+(\\.\\d+){1,3}$");
    private static final Pattern DOMAIN_SYNTAX_PATTERN =
            Pattern.compile("^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)+$");

    private static final Pattern TITLE_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("(?is)<script[^>]*>.*?</script>");
    private static final Pattern STYLE_PATTERN = Pattern.compile("(?is)<style[^>]*>.*?</style>");
    private static final Pattern NOSCRIPT_PATTERN = Pattern.compile("(?is)<noscript[^>]*>.*?</noscript>");
    private static final Pattern COMMENT_PATTERN = Pattern.compile("(?is)<!--.*?-->");
    private static final Pattern BR_PATTERN = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern BLOCK_TAG_PATTERN =
            Pattern.compile("(?i)</?(?:p|div|h[1-6]|li|tr|blockquote|section|article|header|footer|nav|aside)[^>]*>");
    private static final Pattern ALL_TAGS_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern HORIZONTAL_WHITESPACE_PATTERN = Pattern.compile("[ \\t\\x0B\\f\\r]+");

    private static final Pattern DECIMAL_ENTITY_PATTERN = Pattern.compile("&#(\\d+);");
    private static final Pattern HEX_ENTITY_PATTERN = Pattern.compile("&#[xX]([0-9a-fA-F]+);");

    // --- Data Models ---

    public static class ToolResult {
        public boolean isError;
        public String content;
        public List<SourceItem> sources;

        public ToolResult() {
            this.sources = new ArrayList<>();
        }

        public ToolResult(boolean isError, String content, List<SourceItem> sources) {
            this.isError = isError;
            this.content = content != null ? content : "";
            this.sources = sources != null ? sources : new ArrayList<SourceItem>();
        }
    }

    public static class SourceItem {
        public String title;
        public String url;
        public String citedText;

        public SourceItem() {}

        public SourceItem(String title, String url) {
            this(title, url, null);
        }

        public SourceItem(String title, String url, String citedText) {
            this.title = title != null ? title : "";
            this.url = url != null ? url : "";
            this.citedText = citedText;
        }
    }

    public interface ToolCallback {
        void onResult(ToolResult result);
    }

    // --- Cancellable Handle ---

    public static class ToolCancellable implements Cancellable {
        private volatile boolean cancelled = false;
        private volatile Call activeCall;

        public void setCall(Call call) {
            this.activeCall = call;
            if (cancelled && call != null) {
                call.cancel();
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            Call call = activeCall;
            if (call != null) {
                call.cancel();
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled || (activeCall != null && activeCall.isCanceled());
        }
    }

    // --- Instance State & Constructors ---

    private final OkHttpClient httpClient;

    public AntigravityToolExecutor() {
        this(createDefaultHttpClient());
    }

    public AntigravityToolExecutor(OkHttpClient httpClient) {
        this.httpClient = httpClient != null ? httpClient : createDefaultHttpClient();
    }

    private static OkHttpClient createDefaultHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .addNetworkInterceptor(new SsrfNetworkInterceptor())
                .build();
    }

    // --- SSRF Protection ---

    /**
     * Strictly verifies whether a URL points to a safe, public HTTP or HTTPS endpoint.
     * Blocks private, loopback, link-local, multicast, cloud metadata addresses,
     * non-HTTP schemes, and internal hostnames.
     */
    public static boolean isSafePublicUrl(String urlString) {
        if (urlString == null || urlString.trim().isEmpty()) {
            return false;
        }

        URI uri;
        try {
            uri = new URI(urlString.trim());
        } catch (Exception e) {
            return false;
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        String lowerScheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(lowerScheme) && !"https".equals(lowerScheme)) {
            return false;
        }

        String host = uri.getHost();
        if (host == null || host.trim().isEmpty()) {
            return false;
        }
        host = host.trim().toLowerCase(Locale.ROOT);

        String cleanHost = host;
        if (cleanHost.startsWith("[") && cleanHost.endsWith("]")) {
            cleanHost = cleanHost.substring(1, cleanHost.length() - 1);
        }

        // Local & reserved domain labels
        if ("localhost".equals(cleanHost) || cleanHost.endsWith(".localhost")) {
            return false;
        }
        if (cleanHost.endsWith(".local") || cleanHost.endsWith(".internal")
                || cleanHost.endsWith(".lan") || cleanHost.endsWith(".home")
                || cleanHost.endsWith(".corp") || cleanHost.endsWith(".arpa")
                || cleanHost.endsWith(".invalid") || cleanHost.endsWith(".test")) {
            return false;
        }

        // Non-IPv6 host must have at least one dot
        if (!cleanHost.contains(":") && !cleanHost.contains(".")) {
            return false;
        }

        // Check for disallowed characters in host
        if (!HOST_ALLOWED_PATTERN.matcher(cleanHost).matches()) {
            return false;
        }

        try {
            InetAddress[] addresses = InetAddress.getAllByName(cleanHost);
            for (InetAddress addr : addresses) {
                if (isPrivateOrBlockedAddress(addr)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            // In offline environments or mock test domains, validate host domain syntax
            if (isIpLiteral(cleanHost)) {
                return false;
            }
            return isValidDomainSyntax(cleanHost);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isPrivateOrBlockedAddress(InetAddress addr) {
        if (addr == null) {
            return true;
        }
        if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = addr.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;

            // 0.0.0.0/8 (Current network)
            if (b0 == 0) return true;
            // 10.0.0.0/8 (Private network)
            if (b0 == 10) return true;
            // 127.0.0.0/8 (Loopback)
            if (b0 == 127) return true;
            // 100.64.0.0/10 (Shared Address Space / CGNAT)
            if (b0 == 100 && (b1 >= 64 && b1 <= 127)) return true;
            // 169.254.0.0/16 (Link-local / AWS & GCP metadata 169.254.169.254)
            if (b0 == 169 && b1 == 254) return true;
            // 172.16.0.0/12 (Private network 172.16.0.0 - 172.31.255.255)
            if (b0 == 172 && (b1 >= 16 && b1 <= 31)) return true;
            // 192.0.0.0/24 & 192.0.2.0/24 (IETF / TEST-NET-1)
            if (b0 == 192 && b1 == 0) return true;
            // 192.168.0.0/16 (Private network)
            if (b0 == 192 && b1 == 168) return true;
            // 198.18.0.0/15 (Benchmarking)
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true;
            // 198.51.100.0/24 (TEST-NET-2)
            if (b0 == 198 && b1 == 51 && (bytes[2] & 0xFF) == 100) return true;
            // 203.0.113.0/24 (TEST-NET-3)
            if (b0 == 203 && b1 == 0 && (bytes[2] & 0xFF) == 113) return true;
            // 224.0.0.0/4 (Multicast 224-239)
            if (b0 >= 224 && b0 <= 239) return true;
            // 240.0.0.0/4 (Reserved 240-255)
            if (b0 >= 240) return true;
        } else if (bytes.length == 16) {
            int b0 = bytes[0] & 0xFF;

            // Unique Local Address (fc00::/7)
            if ((b0 & 0xFE) == 0xFC) return true;
            // Link-local unicast (fe80::/10)
            if (b0 == 0xFE && (bytes[1] & 0xC0) == 0x80) return true;

            // Check IPv4-mapped IPv6 address (::ffff:x.x.x.x)
            boolean isIpv4Mapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) {
                    isIpv4Mapped = false;
                    break;
                }
            }
            if (isIpv4Mapped && (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF) {
                int mb0 = bytes[12] & 0xFF;
                int mb1 = bytes[13] & 0xFF;
                if (mb0 == 0 || mb0 == 10 || mb0 == 127) return true;
                if (mb0 == 100 && (mb1 >= 64 && mb1 <= 127)) return true;
                if (mb0 == 169 && mb1 == 254) return true;
                if (mb0 == 172 && (mb1 >= 16 && mb1 <= 31)) return true;
                if (mb0 == 192 && mb1 == 168) return true;
                if (mb0 >= 224) return true;
            }
        }
        return false;
    }

    private static boolean isIpLiteral(String host) {
        return IP_LITERAL_PATTERN.matcher(host).matches() || host.contains(":");
    }

    private static boolean isValidDomainSyntax(String domain) {
        return DOMAIN_SYNTAX_PATTERN.matcher(domain).matches();
    }

    /**
     * OkHttp Network Interceptor actively inspecting outbound socket connections
     * to protect against DNS rebinding and redirection to internal network endpoints.
     */
    public static class SsrfNetworkInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            String url = request.url().toString();
            if (!isSafePublicUrl(url)) {
                throw new IOException("SSRF protection blocked URL: " + url);
            }
            if (chain.connection() != null && chain.connection().socket() != null) {
                InetAddress socketAddr = chain.connection().socket().getInetAddress();
                if (socketAddr != null && isPrivateOrBlockedAddress(socketAddr)) {
                    throw new IOException("SSRF protection blocked connection to private IP: "
                            + socketAddr.getHostAddress());
                }
            }
            return chain.proceed(request);
        }
    }

    // --- HTML Text Extraction ---

    /**
     * Extracts readable text from raw HTML, stripping scripts, styles, comments,
     * unescaping entities, collapsing whitespace, and truncating to MAX_CONTENT_LENGTH.
     */
    public static String extractReadableText(String html) {
        return extractReadableText(html, MAX_CONTENT_LENGTH);
    }

    public static String extractReadableText(String html, int maxLength) {
        if (html == null || html.trim().isEmpty()) {
            return "";
        }

        // 1. Strip script tags
        String clean = SCRIPT_PATTERN.matcher(html).replaceAll(" ");
        // 2. Strip style tags
        clean = STYLE_PATTERN.matcher(clean).replaceAll(" ");
        // 3. Strip noscript tags
        clean = NOSCRIPT_PATTERN.matcher(clean).replaceAll(" ");
        // 4. Strip HTML comments
        clean = COMMENT_PATTERN.matcher(clean).replaceAll(" ");

        // 5. Replace line breaking tags with newlines
        clean = BR_PATTERN.matcher(clean).replaceAll("\n");
        clean = BLOCK_TAG_PATTERN.matcher(clean).replaceAll("\n");

        // 6. Strip all other tags
        clean = ALL_TAGS_PATTERN.matcher(clean).replaceAll(" ");

        // 7. Unescape HTML entities
        clean = unescapeHtml(clean);

        // 8. Collapse whitespace and newlines
        String[] lines = clean.split("\n");
        StringBuilder sb = new StringBuilder();
        boolean prevEmpty = false;
        for (String line : lines) {
            String trimmed = HORIZONTAL_WHITESPACE_PATTERN.matcher(line).replaceAll(" ").trim();
            if (!trimmed.isEmpty()) {
                if (sb.length() > 0) {
                    if (prevEmpty) {
                        sb.append("\n\n");
                    } else {
                        sb.append("\n");
                    }
                }
                sb.append(trimmed);
                prevEmpty = false;
            } else {
                prevEmpty = true;
            }
        }

        String result = sb.toString().trim();
        if (maxLength > 0 && result.length() > maxLength) {
            result = result.substring(0, maxLength);
        }
        return result;
    }

    /**
     * Extracts page title from HTML <title> tag.
     */
    public static String extractTitle(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        Matcher matcher = TITLE_PATTERN.matcher(html);
        if (matcher.find()) {
            String raw = matcher.group(1);
            raw = ALL_TAGS_PATTERN.matcher(raw).replaceAll(" ");
            raw = unescapeHtml(raw);
            return HORIZONTAL_WHITESPACE_PATTERN.matcher(raw).replaceAll(" ").trim();
        }
        return "";
    }

    private static String unescapeHtml(String input) {
        if (input == null || !input.contains("&")) {
            return input != null ? input : "";
        }
        String text = input
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");

        if (text.contains("&#")) {
            // Decimal entities (including SMP code points > 0xFFFF)
            Matcher decMatcher = DECIMAL_ENTITY_PATTERN.matcher(text);
            StringBuffer sb = new StringBuffer();
            while (decMatcher.find()) {
                try {
                    int code = Integer.parseInt(decMatcher.group(1));
                    if (Character.isValidCodePoint(code)) {
                        decMatcher.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(code))));
                    } else {
                        decMatcher.appendReplacement(sb, "");
                    }
                } catch (Exception e) {
                    decMatcher.appendReplacement(sb, decMatcher.group(0));
                }
            }
            decMatcher.appendTail(sb);
            text = sb.toString();

            // Hex entities (including SMP code points > 0xFFFF)
            Matcher hexMatcher = HEX_ENTITY_PATTERN.matcher(text);
            StringBuffer sbHex = new StringBuffer();
            while (hexMatcher.find()) {
                try {
                    int code = Integer.parseInt(hexMatcher.group(1), 16);
                    if (Character.isValidCodePoint(code)) {
                        hexMatcher.appendReplacement(sbHex, Matcher.quoteReplacement(new String(Character.toChars(code))));
                    } else {
                        hexMatcher.appendReplacement(sbHex, "");
                    }
                } catch (Exception e) {
                    hexMatcher.appendReplacement(sbHex, hexMatcher.group(0));
                }
            }
            hexMatcher.appendTail(sbHex);
            text = sbHex.toString();
        }
        return text;
    }

    // --- Web Fetch Execution ---

    /**
     * Executes web fetch for an external public web page with anti-SSRF verification,
     * safe 1MB memory buffer bounds, clean text extraction, and truncation to 8,000 characters.
     */
    public Cancellable executeWebFetch(final String urlString, final ToolCallback callback) {
        final ToolCancellable handle = new ToolCancellable();

        if (!isSafePublicUrl(urlString)) {
            JsonObject err = new JsonObject();
            err.addProperty("status", "error");
            err.addProperty("message", "SSRF protection blocked URL: " + urlString);
            if (callback != null) {
                callback.onResult(new ToolResult(true, err.toString(), Collections.<SourceItem>emptyList()));
            }
            return handle;
        }

        if (handle.isCancelled()) {
            return handle;
        }

        Request request;
        try {
            request = new Request.Builder()
                    .url(urlString)
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .get()
                    .build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("status", "error");
            err.addProperty("message", "Invalid URL request: " + e.getMessage());
            if (callback != null) {
                callback.onResult(new ToolResult(true, err.toString(), Collections.<SourceItem>emptyList()));
            }
            return handle;
        }

        final Call call = httpClient.newCall(request);
        handle.setCall(call);

        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (handle.isCancelled()) {
                    return;
                }
                JsonObject err = new JsonObject();
                err.addProperty("status", "error");
                err.addProperty("message", e.getMessage() != null ? e.getMessage() : "Network error");
                if (callback != null) {
                    callback.onResult(new ToolResult(true, err.toString(), Collections.<SourceItem>emptyList()));
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (handle.isCancelled()) {
                    response.close();
                    return;
                }
                try {
                    if (!response.isSuccessful()) {
                        JsonObject err = new JsonObject();
                        err.addProperty("status", "error");
                        err.addProperty("message", "HTTP " + response.code() + " " + response.message());
                        err.addProperty("url", urlString);
                        if (callback != null) {
                            callback.onResult(new ToolResult(true, err.toString(), Collections.<SourceItem>emptyList()));
                        }
                        return;
                    }

                    ResponseBody body = response.body();
                    if (body != null) {
                        MediaType mediaType = body.contentType();
                        if (mediaType != null) {
                            String type = mediaType.type();
                            String subtype = mediaType.subtype();
                            boolean isTextual = "text".equalsIgnoreCase(type)
                                    || "html".equalsIgnoreCase(subtype)
                                    || "json".equalsIgnoreCase(subtype)
                                    || "xml".equalsIgnoreCase(subtype)
                                    || subtype.toLowerCase(Locale.ROOT).contains("html")
                                    || subtype.toLowerCase(Locale.ROOT).contains("xml")
                                    || subtype.toLowerCase(Locale.ROOT).contains("json");
                            if (!isTextual) {
                                JsonObject err = new JsonObject();
                                err.addProperty("status", "error");
                                err.addProperty("message", "Unsupported content type: " + mediaType);
                                err.addProperty("url", urlString);
                                if (callback != null) {
                                    callback.onResult(new ToolResult(true, err.toString(), Collections.<SourceItem>emptyList()));
                                }
                                return;
                            }
                        }
                    }

                    // Safe bounded read up to MAX_RESPONSE_BYTES (1MB) to prevent OOM
                    String rawHtml = "";
                    if (body != null) {
                        BufferedSource source = body.source();
                        Buffer buffer = new Buffer();
                        long totalRead = 0;
                        while (totalRead < MAX_RESPONSE_BYTES) {
                            long toRead = Math.min(8192L, MAX_RESPONSE_BYTES - totalRead);
                            long bytesRead = source.read(buffer, toRead);
                            if (bytesRead <= 0) {
                                break;
                            }
                            totalRead += bytesRead;
                        }
                        rawHtml = buffer.readUtf8();
                    }

                    String title = extractTitle(rawHtml);
                    if (title.isEmpty()) {
                        title = urlString;
                    }
                    String content = extractReadableText(rawHtml, MAX_CONTENT_LENGTH);

                    JsonObject json = new JsonObject();
                    json.addProperty("status", "success");
                    json.addProperty("url", urlString);
                    json.addProperty("title", title);
                    json.addProperty("content", content);

                    List<SourceItem> sources = new ArrayList<>();
                    sources.add(new SourceItem(title, urlString));

                    if (callback != null) {
                        callback.onResult(new ToolResult(false, json.toString(), sources));
                    }
                } catch (Exception e) {
                    if (!handle.isCancelled() && callback != null) {
                        JsonObject err = new JsonObject();
                        err.addProperty("status", "error");
                        err.addProperty("message", "Failed parsing web page: " + e.getMessage());
                        callback.onResult(new ToolResult(true, err.toString(), Collections.<SourceItem>emptyList()));
                    }
                } finally {
                    response.close();
                }
            }
        });

        return handle;
    }
}
