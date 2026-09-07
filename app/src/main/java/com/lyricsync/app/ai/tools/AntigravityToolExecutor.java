package com.lyricsync.app.ai.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.ai.auth.AntigravityAuthManager;
import com.lyricsync.app.ai.client.AntigravityApiClient.Cancellable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
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

    public static final String ENDPOINT_DAILY =
            "https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse";
    public static final String ENDPOINT_PROD =
            "https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse";
    public static final String DEFAULT_PROJECT = "aicode-consumers";
    public static final String SEARCH_MODEL = "gemini-3.8-flash-high";
    public static final String UNIFIED_SEARCH_MODEL = "same_as_chat";
    private static volatile String activeSearchModel = UNIFIED_SEARCH_MODEL;
    private static volatile java.util.function.Supplier<String> chatModelSupplier = null;

    public static void setChatModelSupplier(java.util.function.Supplier<String> supplier) {
        chatModelSupplier = supplier;
    }

    public static void setSearchModel(String model) {
        if (model != null && !model.trim().isEmpty()) {
            activeSearchModel = model.trim();
        } else {
            activeSearchModel = UNIFIED_SEARCH_MODEL;
        }
    }

    public static String getSearchModel() {
        if (UNIFIED_SEARCH_MODEL.equalsIgnoreCase(activeSearchModel) || "auto".equalsIgnoreCase(activeSearchModel)) {
            if (chatModelSupplier != null) {
                try {
                    String supplied = chatModelSupplier.get();
                    if (supplied != null && !supplied.trim().isEmpty()) {
                        return supplied.trim();
                    }
                } catch (Throwable ignored) {}
            }
            return SEARCH_MODEL;
        }
        return activeSearchModel != null ? activeSearchModel : SEARCH_MODEL;
    }

    public static String FALLBACK_SEARCH_ERROR_MESSAGE =
            "Pencarian web tidak tersedia saat ini. Lanjutkan dengan pengetahuan internal.";

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

    private final AntigravityAuthManager authManager;
    private final OkHttpClient httpClient;
    private volatile String projectId = DEFAULT_PROJECT;

    public AntigravityToolExecutor() {
        this(null, createDefaultHttpClient());
    }

    public AntigravityToolExecutor(OkHttpClient httpClient) {
        this(null, httpClient);
    }

    public AntigravityToolExecutor(AntigravityAuthManager authManager) {
        this(authManager, createDefaultHttpClient());
    }

    public AntigravityToolExecutor(AntigravityAuthManager authManager, OkHttpClient httpClient) {
        this.authManager = authManager;
        this.httpClient = httpClient != null ? httpClient : createDefaultHttpClient();
    }

    public void setProjectId(String projectId) {
        if (projectId != null && !projectId.trim().isEmpty()) {
            this.projectId = projectId.trim();
        }
    }

    public String getProjectId() {
        return projectId;
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

    // --- Google Search Grounding Execution ---

    /**
     * Resolves Google Vertex redirect URLs to original target URLs.
     */
    public static String resolveGroundingUrl(String uri) {
        if (uri == null || uri.trim().isEmpty()) {
            return "";
        }
        String trimmed = uri.trim();
        if (trimmed.contains("vertexaisearch.cloud.google.com/grounding-api-redirect")) {
            try {
                int urlIdx = trimmed.indexOf("?url=");
                if (urlIdx == -1) {
                    urlIdx = trimmed.indexOf("&url=");
                }
                if (urlIdx == -1 && trimmed.contains("url=")) {
                    urlIdx = trimmed.indexOf("url=") - 1;
                }
                if (urlIdx != -1) {
                    String encodedUrl = trimmed.substring(urlIdx + 5);
                    int amp = encodedUrl.indexOf('&');
                    if (amp != -1) {
                        encodedUrl = encodedUrl.substring(0, amp);
                    }
                    String decoded = URLDecoder.decode(encodedUrl, "UTF-8");
                    if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                        return decoded;
                    }
                }
            } catch (Exception ignored) {}
        }
        return trimmed;
    }

    /**
     * Creates a standardized, graceful error ToolResult so the AI model continues without crashing.
     */
    public static ToolResult createGracefulErrorResult(String message) {
        String msg = (message != null && !message.trim().isEmpty()) ? message : FALLBACK_SEARCH_ERROR_MESSAGE;
        JsonObject err = new JsonObject();
        err.addProperty("status", "error");
        err.addProperty("message", msg);
        err.addProperty("content", msg);
        return new ToolResult(true, err.toString(), Collections.<SourceItem>emptyList());
    }

    /**
     * Builds the JSON request payload matching the Google Antigravity Cloudcode PA protocol
     * with Google Search Grounding enabled via gemini-2.5-flash.
     */
    public static String buildSearchPayload(String projectId, String query, int maxResults) {
        JsonObject root = new JsonObject();
        root.addProperty("project", (projectId != null && !projectId.trim().isEmpty()) ? projectId.trim() : DEFAULT_PROJECT);
        root.addProperty("model", getSearchModel());
        root.addProperty("userAgent", "antigravity");
        root.addProperty("requestType", "agent");
        root.addProperty("requestId", "agent-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8));

        JsonObject request = new JsonObject();

        JsonArray contents = new JsonArray();
        JsonObject turn = new JsonObject();
        turn.addProperty("role", "user");

        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("text", query != null ? query : "");
        parts.add(part);

        turn.add("parts", parts);
        contents.add(turn);
        request.add("contents", contents);

        JsonArray tools = new JsonArray();
        JsonObject tool = new JsonObject();
        tool.add("googleSearch", new JsonObject());
        tools.add(tool);
        request.add("tools", tools);

        root.add("request", request);
        return root.toString();
    }

    /**
     * Parses Google Search Grounding SSE stream text or raw JSON chunks.
     * Extracts answer text, groundingChunks (sources), groundingSupports (citations),
     * and webSearchQueries. Resilient against malformed or error frames.
     */
    public static ToolResult parseSearchGroundingResponse(String sseStreamText) {
        return parseSearchGroundingResponse(sseStreamText, -1);
    }

    public static ToolResult parseSearchGroundingResponse(String sseStreamText, int maxResults) {
        if (sseStreamText == null || sseStreamText.trim().isEmpty()) {
            return createGracefulErrorResult(FALLBACK_SEARCH_ERROR_MESSAGE);
        }

        StringBuilder fullAnswer = new StringBuilder();
        List<SourceItem> rawChunks = new ArrayList<>();
        List<SourceItem> sources = new ArrayList<>();
        List<SourceItem> citations = new ArrayList<>();
        List<String> searchQueries = new ArrayList<>();

        String[] events = sseStreamText.split("(?:\r?\n){2,}");
        for (String event : events) {
            String trimmedEvent = event.trim();
            if (trimmedEvent.isEmpty()) continue;

            StringBuilder dataPayload = new StringBuilder();
            String[] lines = trimmedEvent.split("\r?\n");
            for (String line : lines) {
                String trimmedLine = line.trim();
                if (trimmedLine.startsWith(":")) continue; // Comment/heartbeat
                if (trimmedLine.startsWith("data:")) {
                    String sub = trimmedLine.substring(5).trim();
                    if (dataPayload.length() > 0) dataPayload.append("\n");
                    dataPayload.append(sub);
                } else if (trimmedLine.startsWith("{")) {
                    if (dataPayload.length() > 0) dataPayload.append("\n");
                    dataPayload.append(trimmedLine);
                }
            }

            String jsonStr = dataPayload.toString().trim();
            if (jsonStr.isEmpty() || "[DONE]".equalsIgnoreCase(jsonStr)) {
                continue;
            }

            try {
                JsonElement elem = JsonParser.parseString(jsonStr);
                if (!elem.isJsonObject()) continue;
                JsonObject root = elem.getAsJsonObject();

                JsonObject resp = root.has("response") && root.get("response").isJsonObject()
                        ? root.getAsJsonObject("response")
                        : root;

                if (resp.has("error") || root.has("error")) {
                    return createGracefulErrorResult(FALLBACK_SEARCH_ERROR_MESSAGE);
                }

                if (resp.has("candidates") && resp.get("candidates").isJsonArray()) {
                    JsonArray candidates = resp.getAsJsonArray("candidates");
                    for (JsonElement candElem : candidates) {
                        if (!candElem.isJsonObject()) continue;
                        JsonObject cand = candElem.getAsJsonObject();

                        if (cand.has("content") && cand.get("content").isJsonObject()) {
                            JsonObject content = cand.getAsJsonObject("content");
                            if (content.has("parts") && content.get("parts").isJsonArray()) {
                                JsonArray parts = content.getAsJsonArray("parts");
                                for (JsonElement pElem : parts) {
                                    if (!pElem.isJsonObject()) continue;
                                    JsonObject part = pElem.getAsJsonObject();
                                    if (part.has("text") && !part.get("text").isJsonNull() && part.get("text").isJsonPrimitive()) {
                                        fullAnswer.append(part.get("text").getAsString());
                                    }
                                }
                            }
                        }

                        if (cand.has("groundingMetadata") && cand.get("groundingMetadata").isJsonObject()) {
                            extractGroundingFromMetadata(cand.getAsJsonObject("groundingMetadata"),
                                    rawChunks, sources, citations, searchQueries);
                        }
                    }
                }

                if (resp.has("groundingMetadata") && resp.get("groundingMetadata").isJsonObject()) {
                    extractGroundingFromMetadata(resp.getAsJsonObject("groundingMetadata"),
                            rawChunks, sources, citations, searchQueries);
                } else if (root.has("groundingMetadata") && root.get("groundingMetadata").isJsonObject()) {
                    extractGroundingFromMetadata(root.getAsJsonObject("groundingMetadata"),
                            rawChunks, sources, citations, searchQueries);
                }

            } catch (Exception ignored) {}
        }

        if (sources.isEmpty() && !citations.isEmpty()) {
            for (SourceItem c : citations) {
                boolean found = false;
                for (SourceItem s : sources) {
                    if (s.url.equals(c.url)) {
                        found = true;
                        break;
                    }
                }
                if (!found && !c.url.isEmpty()) {
                    sources.add(new SourceItem(c.title, c.url));
                }
            }
        }

        if (maxResults > 0 && sources.size() > maxResults) {
            sources = new ArrayList<>(sources.subList(0, maxResults));
        }

        JsonObject output = new JsonObject();
        output.addProperty("status", "success");
        output.addProperty("answer", fullAnswer.toString().trim());

        JsonArray sourcesJson = new JsonArray();
        for (SourceItem s : sources) {
            JsonObject sObj = new JsonObject();
            sObj.addProperty("title", s.title);
            sObj.addProperty("url", s.url);
            sourcesJson.add(sObj);
        }
        output.add("sources", sourcesJson);

        JsonArray citationsJson = new JsonArray();
        for (SourceItem c : citations) {
            JsonObject cObj = new JsonObject();
            cObj.addProperty("title", c.title);
            cObj.addProperty("url", c.url);
            cObj.addProperty("citedText", c.citedText != null ? c.citedText : "");
            citationsJson.add(cObj);
        }
        output.add("citations", citationsJson);

        JsonArray queriesJson = new JsonArray();
        for (String q : searchQueries) {
            queriesJson.add(q);
        }
        output.add("searchQueries", queriesJson);

        return new ToolResult(false, output.toString(), sources);
    }

    private static void extractGroundingFromMetadata(JsonObject gm,
                                                    List<SourceItem> rawChunks,
                                                    List<SourceItem> sources,
                                                    List<SourceItem> citations,
                                                    List<String> searchQueries) {
        if (gm == null) return;

        // 1. webSearchQueries
        if (gm.has("webSearchQueries") && gm.get("webSearchQueries").isJsonArray()) {
            JsonArray qArr = gm.getAsJsonArray("webSearchQueries");
            for (JsonElement qElem : qArr) {
                if (qElem != null && !qElem.isJsonNull() && qElem.isJsonPrimitive()) {
                    String q = qElem.getAsString().trim();
                    if (!q.isEmpty() && !searchQueries.contains(q)) {
                        searchQueries.add(q);
                    }
                }
            }
        }

        // 2. groundingChunks
        if (gm.has("groundingChunks") && gm.get("groundingChunks").isJsonArray()) {
            JsonArray cArr = gm.getAsJsonArray("groundingChunks");
            for (JsonElement cElem : cArr) {
                if (!cElem.isJsonObject()) continue;
                JsonObject cObj = cElem.getAsJsonObject();
                String title = "";
                String uri = "";
                if (cObj.has("web") && cObj.get("web").isJsonObject()) {
                    JsonObject web = cObj.getAsJsonObject("web");
                    if (web.has("title") && !web.get("title").isJsonNull()) {
                        title = web.get("title").getAsString().trim();
                    }
                    if (web.has("uri") && !web.get("uri").isJsonNull()) {
                        uri = web.get("uri").getAsString().trim();
                    } else if (web.has("url") && !web.get("url").isJsonNull()) {
                        uri = web.get("url").getAsString().trim();
                    }
                } else {
                    if (cObj.has("title") && !cObj.get("title").isJsonNull()) {
                        title = cObj.get("title").getAsString().trim();
                    }
                    if (cObj.has("uri") && !cObj.get("uri").isJsonNull()) {
                        uri = cObj.get("uri").getAsString().trim();
                    } else if (cObj.has("url") && !cObj.get("url").isJsonNull()) {
                        uri = cObj.get("url").getAsString().trim();
                    }
                }

                String resolvedUrl = resolveGroundingUrl(uri);
                if (title.isEmpty()) {
                    title = resolvedUrl;
                }
                SourceItem item = new SourceItem(title, resolvedUrl);
                rawChunks.add(item);

                boolean alreadyInSources = false;
                for (SourceItem s : sources) {
                    if (s.url.equals(resolvedUrl)) {
                        alreadyInSources = true;
                        break;
                    }
                }
                if (!alreadyInSources && !resolvedUrl.isEmpty()) {
                    sources.add(item);
                }
            }
        }

        // 3. groundingSupports
        if (gm.has("groundingSupports") && gm.get("groundingSupports").isJsonArray()) {
            JsonArray sArr = gm.getAsJsonArray("groundingSupports");
            for (JsonElement sElem : sArr) {
                if (!sElem.isJsonObject()) continue;
                JsonObject sObj = sElem.getAsJsonObject();

                String citedText = "";
                if (sObj.has("segment") && sObj.get("segment").isJsonObject()) {
                    JsonObject seg = sObj.getAsJsonObject("segment");
                    if (seg.has("text") && !seg.get("text").isJsonNull()) {
                        citedText = seg.get("text").getAsString();
                    }
                } else if (sObj.has("citedText") && !sObj.get("citedText").isJsonNull()) {
                    citedText = sObj.get("citedText").getAsString();
                } else if (sObj.has("text") && !sObj.get("text").isJsonNull()) {
                    citedText = sObj.get("text").getAsString();
                }

                if (sObj.has("groundingChunkIndices") && sObj.get("groundingChunkIndices").isJsonArray()) {
                    JsonArray idxArr = sObj.getAsJsonArray("groundingChunkIndices");
                    for (JsonElement idxElem : idxArr) {
                        if (idxElem.isJsonPrimitive()) {
                            int idx = idxElem.getAsInt();
                            if (idx >= 0 && idx < rawChunks.size()) {
                                SourceItem chunk = rawChunks.get(idx);
                                citations.add(new SourceItem(chunk.title, chunk.url, citedText));
                            }
                        }
                    }
                } else if (sObj.has("title") || sObj.has("url") || sObj.has("uri")) {
                    String title = sObj.has("title") ? sObj.get("title").getAsString() : "";
                    String uri = sObj.has("url") ? sObj.get("url").getAsString() : (sObj.has("uri") ? sObj.get("uri").getAsString() : "");
                    String resolvedUrl = resolveGroundingUrl(uri);
                    if (title.isEmpty()) title = resolvedUrl;
                    citations.add(new SourceItem(title, resolvedUrl, citedText));
                }
            }
        }
    }

    /**
     * Executes Google Search Grounding with gemini-2.5-flash and { "tools": [{ "googleSearch": {} }] }.
     * Primary endpoint: daily-cloudcode-pa.googleapis.com
     * Fallback endpoint: cloudcode-pa.googleapis.com (on 503 / 404)
     * Resilient error handling on 429, 503, or network failure.
     */
    public Cancellable executeWebSearch(final String query, final int maxResults, final ToolCallback callback) {
        final ToolCancellable handle = new ToolCancellable();

        if (query == null || query.trim().isEmpty()) {
            if (callback != null) {
                callback.onResult(createGracefulErrorResult("Query pencarian tidak boleh kosong"));
            }
            return handle;
        }

        if (handle.isCancelled()) {
            return handle;
        }

        if (authManager != null) {
            authManager.getValidAccessToken(new AntigravityAuthManager.TokenCallback() {
                @Override
                public void onTokenReady(String accessToken) {
                    if (handle.isCancelled()) return;
                    performSearchRequest(ENDPOINT_DAILY, accessToken, query, maxResults, handle, callback, false);
                }

                @Override
                public void onError(String message) {
                    if (handle.isCancelled()) return;
                    if (callback != null) {
                        callback.onResult(createGracefulErrorResult(FALLBACK_SEARCH_ERROR_MESSAGE));
                    }
                }
            });
        } else {
            // For unit testing with mock HTTP client or unauthenticated execution
            performSearchRequest(ENDPOINT_DAILY, "test-token", query, maxResults, handle, callback, false);
        }

        return handle;
    }

    private void performSearchRequest(final String endpoint, final String accessToken,
                                      final String query, final int maxResults,
                                      final ToolCancellable handle, final ToolCallback callback,
                                      final boolean isRetry) {
        if (handle.isCancelled()) return;

        String payload = buildSearchPayload(projectId, query, maxResults);
        Request request;
        try {
            Request.Builder reqBuilder = new Request.Builder()
                    .url(endpoint)
                    .addHeader("User-Agent", "antigravity/ide/2.11.0 darwin/arm64")
                    .addHeader("Accept", "text/event-stream")
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), payload));

            if (accessToken != null && !accessToken.trim().isEmpty()) {
                reqBuilder.addHeader("Authorization", "Bearer " + accessToken.trim());
            }

            request = reqBuilder.build();
        } catch (Exception e) {
            if (!handle.isCancelled() && callback != null) {
                callback.onResult(createGracefulErrorResult(FALLBACK_SEARCH_ERROR_MESSAGE));
            }
            return;
        }

        final Call call = httpClient.newCall(request);
        handle.setCall(call);

        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (handle.isCancelled()) return;
                if (!isRetry && ENDPOINT_DAILY.equals(endpoint)) {
                    performSearchRequest(ENDPOINT_PROD, accessToken, query, maxResults, handle, callback, true);
                    return;
                }
                if (callback != null) {
                    callback.onResult(createGracefulErrorResult(FALLBACK_SEARCH_ERROR_MESSAGE));
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (handle.isCancelled()) {
                    response.close();
                    return;
                }

                int code = response.code();
                if ((code == 503 || code == 404) && !isRetry && ENDPOINT_DAILY.equals(endpoint)) {
                    response.close();
                    performSearchRequest(ENDPOINT_PROD, accessToken, query, maxResults, handle, callback, true);
                    return;
                }

                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful() || responseBody == null) {
                        if (callback != null) {
                            callback.onResult(createGracefulErrorResult(FALLBACK_SEARCH_ERROR_MESSAGE));
                        }
                        return;
                    }

                    String sseText = responseBody.string();
                    ToolResult result = parseSearchGroundingResponse(sseText, maxResults);
                    if (result.isError) {
                        result = createGracefulErrorResult(FALLBACK_SEARCH_ERROR_MESSAGE);
                    } else {
                        // Resolve Vertex redirect URLs to canonical URLs
                        if (result.sources != null) {
                            for (SourceItem s : result.sources) {
                                if (s.url != null && s.url.contains("vertexaisearch.cloud.google.com")) {
                                    try {
                                        Request headReq = new Request.Builder()
                                                .url(s.url)
                                                .head()
                                                .build();
                                        try (Response headResp = httpClient.newCall(headReq).execute()) {
                                            if (headResp.code() >= 300 && headResp.code() < 400) {
                                                String loc = headResp.header("Location");
                                                if (loc != null && (loc.startsWith("http://") || loc.startsWith("https://"))) {
                                                    s.url = loc.trim();
                                                }
                                            }
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        }

                        if (maxResults > 0 && result.sources.size() > maxResults) {
                            result.sources = new ArrayList<>(result.sources.subList(0, maxResults));
                            try {
                                JsonObject json = JsonParser.parseString(result.content).getAsJsonObject();
                                JsonArray newSourcesArr = new JsonArray();
                                for (SourceItem s : result.sources) {
                                    JsonObject sObj = new JsonObject();
                                    sObj.addProperty("title", s.title);
                                    sObj.addProperty("url", s.url);
                                    newSourcesArr.add(sObj);
                                }
                                json.add("sources", newSourcesArr);
                                result.content = json.toString();
                            } catch (Exception ignored) {}
                        }
                    }

                    if (callback != null) {
                        callback.onResult(result);
                    }
                } catch (Exception e) {
                    if (!handle.isCancelled() && callback != null) {
                        callback.onResult(createGracefulErrorResult(FALLBACK_SEARCH_ERROR_MESSAGE));
                    }
                }
            }
        });
    }
}
