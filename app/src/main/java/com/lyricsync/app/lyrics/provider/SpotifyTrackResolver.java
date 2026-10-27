package com.lyricsync.app.lyrics.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lyricsync.app.lyrics.util.TrackMatcher;
import com.lyricsync.app.lyrics.util.TrackQuery;
import com.lyricsync.app.util.AppLog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Title + artist → Spotify track id, so SpicyLyrics can be asked for word-by-word lyrics.
 *
 * Uses Spotify's own search (anonymous web-player token from the public embed page,
 * `searchDesktop` persisted query). Candidates are ranked with {@link TrackMatcher}, so a
 * live/remix/karaoke pressing never beats the studio recording. Results — misses
 * included — are cached in a small LRU, so a track resolves once per session.
 */
public final class SpotifyTrackResolver {
    private static final String TAG = "SpotifyTrackResolver";

    private static final String SEARCH_URL = "https://api-partner.spotify.com/pathfinder/v1/query"
            + "?operationName=searchDesktop&variables=%s&extensions=%s";
    /** Persisted-query hash; a "PersistedQueryNotFound" error means Spotify rotated it. */
    private static final String SEARCH_SHA256 =
            "eff59fa0a3d026b88b56fddbcf4bdfa16a186b8175a5c1a358c072e053c2e5b0";
    private static final String TOKEN_URL = "https://open.spotify.com/embed/track/4uLU6hMCjMI75M1A2tKUQC";
    private static final long TOKEN_TTL_MS = 30 * 60 * 1000L;
    /** Stricter than the lyrics providers: a wrong id means wrong lyrics from the best source. */
    private static final double MIN_SCORE = 0.70;
    private static final long MAX_DURATION_DELTA_MS = 8_000L;

    private static final Object TOKEN_LOCK = new Object();
    private static volatile String token;
    private static volatile long tokenAt;

    private static final Map<String, String> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 256;
                }
            });

    private SpotifyTrackResolver() {}

    /** The Spotify id of {@code q}, or null when it is not (confidently) on Spotify. */
    public static String resolve(TrackQuery q) {
        if (q == null || q.isEmpty()) return null;
        String key = (q.title + "|" + q.artist).toLowerCase(java.util.Locale.ROOT);
        String hit = CACHE.get(key);
        if (hit != null) return hit.isEmpty() ? null : hit;

        String id = null;
        boolean answered = false;
        for (String t : q.titles()) {
            JsonObject root = search((t + " " + q.artist).trim());
            if (root == null) continue;            // failed: try the next spelling, never cache
            answered = true;
            id = pickBest(root, q);
            if (id != null) break;
        }
        if (answered) CACHE.put(key, id == null ? "" : id);
        return id;
    }

    /** Package-private: ranks a captured search response offline. */
    static String pickBest(JsonObject root, TrackQuery q) {
        JsonArray items = items(root);
        if (items == null) return null;
        String bestId = null;
        double best = MIN_SCORE;
        for (JsonElement el : items) {
            JsonObject item = Http.obj(el) == null ? null : Http.obj(Http.obj(el).get("item"));
            JsonObject data = item == null ? null : Http.obj(item.get("data"));
            if (data == null) continue;
            String id = Http.str(data, "id");
            if (id.length() != 22) continue;
            long dur = Http.num(Http.obj(data.get("duration")), "totalMilliseconds");
            boolean durationOff = q.durationMs > 0 && dur > 0 && Math.abs(q.durationMs - dur) > MAX_DURATION_DELTA_MS;
            // A music video runs longer than the song (intro, outro); only then may the length differ.
            if (durationOff && !(q.fromVideo && dur < q.durationMs)) continue;
            double s = q.score(Http.str(data, "name"), artists(data), dur);
            if (s > best) { best = s; bestId = id; } // ties keep Spotify's own (popularity) order
        }
        if (bestId != null) AppLog.i(TAG, "Resolved " + q + " -> " + bestId + String.format(" (%.2f)", best));
        return bestId;
    }

    private static JsonArray items(JsonObject root) {
        try {
            return root.getAsJsonObject("data").getAsJsonObject("searchV2")
                    .getAsJsonObject("tracksV2").getAsJsonArray("items");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String artists(JsonObject data) {
        StringBuilder sb = new StringBuilder();
        try {
            for (JsonElement a : data.getAsJsonObject("artists").getAsJsonArray("items")) {
                String n = Http.str(a.getAsJsonObject().getAsJsonObject("profile"), "name");
                if (!n.isEmpty()) sb.append(sb.length() > 0 ? ", " : "").append(n);
            }
        } catch (RuntimeException ignored) {
        }
        return sb.toString();
    }

    /** One search; retries once (fresh token on 401). Null on failure. */
    private static JsonObject search(String term) {
        String vars = "{\"searchTerm\":" + new com.google.gson.JsonPrimitive(term)
                + ",\"offset\":0,\"limit\":10,\"numberOfTopResults\":5,\"includeAudiobooks\":false,"
                + "\"includePreReleases\":false,\"includeAlbumPreReleases\":false,\"includeAuthors\":false,"
                + "\"includeEpisodeContentRatingsV2\":false}";
        String ext = "{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"" + SEARCH_SHA256 + "\"}}";
        String url = String.format(SEARCH_URL, Http.enc(vars), Http.enc(ext));
        boolean refresh = false;
        for (int attempt = 0; attempt < 2; attempt++) {
            String t = token(refresh);
            if (t == null) return null;
            try {
                JsonObject root = Http.obj(Http.getJson(Http.CLIENT, url, "Authorization", "Bearer " + t,
                        "app-platform", "WebPlayer", "Accept", "application/json", "User-Agent", Http.BROWSER_UA));
                if (root != null && root.has("errors")) {
                    AppLog.w(TAG, "Spotify search error (persisted query rotated?)");
                    return null;
                }
                return root;
            } catch (java.io.IOException e) {
                // 401: token expired, refresh it; timeouts/5xx are usually transient: retry once.
                AppLog.w(TAG, "Spotify search failed: " + e.getMessage());
                refresh = String.valueOf(e.getMessage()).contains("401");
            }
        }
        return null;
    }

    private static String token(boolean refresh) {
        synchronized (TOKEN_LOCK) {
            if (!refresh && token != null && System.currentTimeMillis() - tokenAt < TOKEN_TTL_MS) return token;
            token = null;
            try (okhttp3.Response r = Http.CLIENT.newCall(new okhttp3.Request.Builder()
                    .url(TOKEN_URL).header("User-Agent", Http.BROWSER_UA).build()).execute()) {
                String html = r.isSuccessful() && r.body() != null ? r.body().string() : "";
                int i = html.indexOf("\"accessToken\":\"");
                if (i < 0) return null;
                int start = i + 15, end = html.indexOf('"', start);
                if (end - start < 20) return null;
                token = html.substring(start, end);
                tokenAt = System.currentTimeMillis();
                return token;
            } catch (Exception e) {
                AppLog.w(TAG, "Token bootstrap failed: " + e.getMessage());
                return null;
            }
        }
    }
}
