package com.lyricsync.app.lyrics.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.util.AppLog;
import com.lyricsync.app.lyrics.util.JapaneseRomanizer;

import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Resolves a song title + artist into a Spotify track ID.
 *
 * Primary route: Spotify's own catalogue. An anonymous web-player token is bootstrapped
 * from the public embed page and used against the `searchDesktop` pathfinder operation;
 * results are ranked against the local metadata (title / artist / duration) so a live,
 * remix or karaoke pressing does not win over the studio recording.
 *
 * Fallback: public ISRC catalogues (Deezer search -> MusicBrainz ISRC cross-reference)
 * for when the undocumented Spotify contract changes or is rate limited.
 *
 * Resolved IDs are cached in memory (title|artist -> id, misses included).
 */
public class SpotifyTrackResolver {
    private static final String TAG = "SpotifyTrackResolver";
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .followRedirects(false)
            .build();

    // ── Spotify pathfinder (anonymous) ─────────────────────────────────────
    private static final String PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v1/query";
    private static final String SEARCH_OPERATION = "searchDesktop";
    /** Spotify persisted-query hash; "PersistedQueryNotFound" means it must be refreshed. */
    private static final String SEARCH_SHA256 =
            "eff59fa0a3d026b88b56fddbcf4bdfa16a186b8175a5c1a358c072e053c2e5b0";
    private static final String BOOTSTRAP_URL =
            "https://open.spotify.com/embed/track/4uLU6hMCjMI75M1A2tKUQC";
    private static final long TOKEN_TTL_MS = 30 * 60 * 1000L;
    private static final Object TOKEN_LOCK = new Object();
    private static volatile String anonToken = null;
    private static volatile long anonTokenAt = 0;

    private static final long DURATION_WINDOW_MS = 15_000L;
    private static final long MAX_DURATION_DELTA_MS = 8_000L;
    private static final double MIN_TITLE_SIM = 0.72;
    private static final double MIN_ARTIST_SIM = 0.34;
    private static final int MIN_SCORE = 68;

    private static final Set<String> VERSION_TERMS = new HashSet<>(java.util.Arrays.asList(
            "acoustic", "cover", "demo", "english", "inst", "instrumental", "japanese",
            "extended", "karaoke", "live", "mix", "remix", "remaster", "remastered",
            "slowed", "sped", "version", "edit", "tour"));

    /** title|artist -> id ("" = known miss). Bounded, access-ordered LRU. */
    private static final Map<String, String> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 256;
                }
            });

    // ── title / artist cleaning (ported from the original resolver) ─────────
    public static String cleanTitle(String title) {
        if (title == null) return "";
        String cleaned = title
                .replaceAll("(?i)\\s*\\[[^\\]]*(official|video|audio|lyric|lyrics|visualizer|mv|music video|hd|4k|performance|remaster|remastered)[^\\]]*\\]", "")
                .replaceAll("(?i)\\s*\\([^)]*(official|video|audio|lyric|lyrics|visualizer|mv|music video|hd|4k|performance|remaster|remastered)[^)]*\\)", "")
                .replaceAll("(?i)\\s+-\\s+(official|video|audio|lyric|lyrics|visualizer|mv|music video|hd|4k).*$", "")
                .replaceAll("(?i)\\s*[\\(\\[]?\\s*(feat\\.?|ft\\.?|featuring)\\s+[^\\)\\]]*[\\)\\]]?", "")
                .replaceAll("(?i)\\s*\\b(full album|album|ep)\\b\\s*$", "")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.isEmpty() ? title.trim() : cleaned;
    }

    public static String cleanArtist(String artist) {
        if (artist == null) return "";
        String cleaned = artist
                .replaceAll("(?i)\\s*-\\s*topic\\s*$", "")
                .replaceAll("(?i)\\s*vevo\\s*$", "")
                .replaceAll("(?i)\\s*official\\s*$", "")
                .replaceAll("(?i)\\s*(feat\\.?|ft\\.?|featuring)\\s+.*$", "")
                .replaceAll("\\s+", " ")
                .trim();
        String[] split = cleaned.split("\\s*(,|&| x | X |/|;)\\s*");
        if (split.length > 0 && !split[0].trim().isEmpty()) {
            cleaned = split[0].trim();
        }
        return cleaned.isEmpty() ? artist.trim() : cleaned;
    }

    // ── public entry points ────────────────────────────────────────────────
    public static String resolve(String title, String artist) {
        return resolve(title, artist, 0L);
    }

    public static String resolve(String title, String artist, long durationMs) {
        if (title == null || title.trim().isEmpty()) return null;
        String cTitle = cleanTitle(title);
        String cArtist = cleanArtist(artist);
        String key = (cTitle + "|" + cArtist).toLowerCase(Locale.ROOT);
        String hit = CACHE.get(key);
        if (hit != null) return hit.isEmpty() ? null : hit;

        String id = resolveViaSpotifySearch(cTitle, cArtist, durationMs);
        if (id == null) {
            id = resolveViaIsrc(cTitle, cArtist);
        }
        CACHE.put(key, id == null ? "" : id);
        return id;
    }

    // ── primary: Spotify search ────────────────────────────────────────────
    private static String resolveViaSpotifySearch(String title, String artist, long durationMs) {
        // A player may show a Japanese title ("すずめ") where the catalogue stores the
        // romanised form ("Suzume"), so search both spellings when kana is present.
        java.util.LinkedHashSet<String> titles = new java.util.LinkedHashSet<>();
        String base = title == null ? "" : title.trim();
        if (!base.isEmpty()) titles.add(base);
        String romaji = JapaneseRomanizer.romanize(base);
        if (!romaji.trim().isEmpty() && !romaji.equalsIgnoreCase(base)) titles.add(romaji.trim());
        if (titles.isEmpty()) return null;

        for (String t : titles) {
            String query = (t + " " + (artist == null ? "" : artist)).trim();
            if (query.isEmpty()) continue;
            for (int attempt = 0; attempt < 2; attempt++) {
                String token = getAnonToken(attempt > 0);
                if (token == null) return null;
                JsonObject root = search(token, query);
                if (root == null) continue; // 401 / transient -> retry once with a fresh token
                String id = pickBest(root, title, artist, durationMs);
                if (id != null) return id;
                break; // search succeeded but nothing matched this spelling; try the next one
            }
        }
        return null;
    }

    private static JsonObject search(String token, String query) {
        try {
            String url = PATHFINDER_URL + "?operationName=" + SEARCH_OPERATION
                    + "&variables=" + URLEncoder.encode(buildVariables(query), "UTF-8")
                    + "&extensions=" + URLEncoder.encode(buildExtensions(), "UTF-8");
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .header("Authorization", "Bearer " + token)
                    .header("app-platform", "WebPlayer")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (response.code() == 401) {
                    anonToken = null; // force a refresh on the next attempt
                    return null;
                }
                if (!response.isSuccessful() || response.body() == null) return null;
                String body = response.body().string();
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                if (root.has("errors") && root.get("errors").isJsonArray()) {
                    AppLog.w(TAG, "Spotify search returned errors (persisted query rotated?)");
                    return null;
                }
                return root;
            }
        } catch (Exception e) {
            AppLog.w(TAG, "Spotify search failed: " + e.getMessage());
            return null;
        }
    }

    private static String buildVariables(String query) {
        return "{\"searchTerm\":\"" + query.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",\"offset\":0,\"limit\":20,\"numberOfTopResults\":5,"
                + "\"includeAudiobooks\":false,\"includePreReleases\":true,"
                + "\"includeAlbumPreReleases\":false,\"includeAuthors\":false,"
                + "\"includeEpisodeContentRatingsV2\":false}";
    }

    private static String buildExtensions() {
        return "{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"" + SEARCH_SHA256 + "\"}}";
    }

    /** Package-private so a unit test can rank a captured search response offline. */
    static String pickBest(JsonObject root, String queryTitle, String queryArtist, long durationMs) {
        JsonArray items = null;
        try {
            items = root.getAsJsonObject("data").getAsJsonObject("searchV2")
                    .getAsJsonObject("tracksV2").getAsJsonArray("items");
        } catch (Exception ignored) {}
        if (items == null) return null;

        boolean artistKnown = queryArtist != null && !queryArtist.trim().isEmpty();
        String qNormArtist = artistKnown ? artistNorm(queryArtist) : "";
        Set<String> qVersionTerms = versionTerms(queryTitle);

        int bestScore = -1;
        String bestId = null;
        long bestDelta = Long.MAX_VALUE;

        for (JsonElement el : items) {
            if (!el.isJsonObject()) continue;
            JsonObject itemObj = el.getAsJsonObject().getAsJsonObject("item");
            if (itemObj == null) continue;
            JsonObject data = itemObj.getAsJsonObject("data");
            if (data == null) continue;
            String id = optString(data, "id");
            String name = optString(data, "name");
            if (id == null || id.length() != 22 || name == null) continue;

            double titleSim = titleSimilarity(queryTitle, name);
            double artistSim = artistSimilarity(qNormArtist, data);
            long candDuration = 0L;
            try { candDuration = data.getAsJsonObject("duration").get("totalMilliseconds").getAsLong(); } catch (Exception ignored) {}
            long delta = (durationMs > 0 && candDuration > 0) ? Math.abs(durationMs - candDuration) : 0L;
            double durationScore = (durationMs > 0) ? Math.max(0.0, 1.0 - (double) delta / DURATION_WINDOW_MS) : 1.0;

            if (titleSim < MIN_TITLE_SIM) continue;
            if (artistKnown && artistSim < MIN_ARTIST_SIM) continue;
            if (durationMs > 0 && candDuration > 0 && delta > MAX_DURATION_DELTA_MS) continue;

            boolean versionConflict = !qVersionTerms.equals(versionTerms(name));
            int score = (int) Math.round(100.0 * (titleSim * 0.55 + artistSim * 0.30 + durationScore * 0.15)
                    - (versionConflict ? 18 : 0));

            if (score > bestScore || (score == bestScore && delta < bestDelta)) {
                bestScore = score;
                bestId = id;
                bestDelta = delta;
            }
        }

        if (bestId != null && bestScore >= MIN_SCORE) {
            AppLog.i(TAG, "Resolved Spotify track ID via search: " + bestId + " (score " + bestScore + ")");
            return bestId;
        }
        return null;
    }

    private static double artistSimilarity(String qNormArtist, JsonObject data) {
        if (qNormArtist == null || qNormArtist.isEmpty()) return 0.0;
        double best = 0.0;
        try {
            JsonArray artists = data.getAsJsonObject("artists").getAsJsonArray("items");
            for (JsonElement a : artists) {
                JsonObject profile = a.getAsJsonObject().getAsJsonObject("profile");
                if (profile == null) continue;
                String an = optString(profile, "name");
                if (an != null) best = Math.max(best, similarity(qNormArtist, artistNorm(an)));
            }
        } catch (Exception ignored) {}
        return best;
    }

    /** Normalised form of a title: cleaned, kana romanised, accents folded, lowercased. */
    private static String titleNorm(String raw) {
        return normalize(JapaneseRomanizer.romanize(cleanTitle(raw)));
    }

    /** Normalised form of an artist name: cleaned, kana romanised, accents folded. */
    private static String artistNorm(String raw) {
        return normalize(JapaneseRomanizer.romanize(cleanArtist(raw)));
    }

    private static double titleSimilarity(String qRaw, String cRaw) {
        List<String> q = titleParts(qRaw);
        List<String> c = titleParts(cRaw);
        double best = 0.0;
        for (String a : q) {
            for (String b : c) {
                best = Math.max(best, similarity(a, b));
            }
        }
        return best;
    }

    /** "ROSÉ - APT." -> ["rose", "apt"], "すずめ (feat. Toaka)" -> ["suzume"]. */
    private static List<String> titleParts(String raw) {
        List<String> parts = new ArrayList<>();
        for (String p : cleanTitle(raw).split("\\s+-\\s+")) {
            String t = normalize(JapaneseRomanizer.romanize(p));
            if (!t.isEmpty()) parts.add(t);
        }
        if (parts.isEmpty()) {
            String t = titleNorm(raw);
            if (!t.isEmpty()) parts.add(t);
        }
        return parts;
    }

    private static Set<String> versionTerms(String title) {
        Set<String> found = new HashSet<>();
        for (String t : tokens(titleNorm(title))) {
            if (VERSION_TERMS.contains(t)) found.add(t);
        }
        return found;
    }

    // ── anonymous token bootstrap ──────────────────────────────────────────
    private static String getAnonToken(boolean forceRefresh) {
        if (!forceRefresh && anonToken != null && (System.currentTimeMillis() - anonTokenAt) < TOKEN_TTL_MS) {
            return anonToken;
        }
        synchronized (TOKEN_LOCK) {
            if (!forceRefresh && anonToken != null && (System.currentTimeMillis() - anonTokenAt) < TOKEN_TTL_MS) {
                return anonToken;
            }
            try {
                Request request = new Request.Builder()
                        .url(BOOTSTRAP_URL)
                        .get()
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build();
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) return null;
                    String html = response.body().string();
                    int idx = html.indexOf("\"accessToken\":\"");
                    if (idx < 0) return null;
                    int start = idx + "\"accessToken\":\"".length();
                    int end = html.indexOf('"', start);
                    if (end < 0) return null;
                    String token = html.substring(start, end);
                    if (token.length() < 20) return null;
                    anonToken = token;
                    anonTokenAt = System.currentTimeMillis();
                    AppLog.i(TAG, "Bootstrapped Spotify search token len=" + token.length());
                    return token;
                }
            } catch (Exception e) {
                AppLog.w(TAG, "Token bootstrap failed: " + e.getMessage());
                return null;
            }
        }
    }

    // ── fallback: Deezer -> ISRC -> MusicBrainz ────────────────────────────
    private static String resolveViaIsrc(String cTitle, String cArtist) {
        // Try the title as shown and its romanised form: Deezer indexes "Suzume",
        // not "すずめ", for the same recording.
        java.util.LinkedHashSet<String> variants = new java.util.LinkedHashSet<>();
        variants.add(cTitle);
        String romaji = JapaneseRomanizer.romanize(cTitle).trim();
        if (!romaji.isEmpty() && !romaji.equalsIgnoreCase(cTitle)) variants.add(romaji);
        for (String t : variants) {
            String id = resolveViaIsrcQuery(t, cArtist);
            if (id != null) return id;
        }
        return null;
    }

    private static String resolveViaIsrcQuery(String cTitle, String cArtist) {
        try {
            String query = (cTitle + " " + cArtist).trim();
            String deezerUrl = "https://api.deezer.com/search?q=" + URLEncoder.encode(query, "UTF-8");
            Request dReq = new Request.Builder().url(deezerUrl).header("User-Agent", "Mozilla/5.0").get().build();

            String isrc = null;
            try (Response resp = client.newCall(dReq).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                JsonObject root = JsonParser.parseString(resp.body().string()).getAsJsonObject();
                if (!root.has("data") || !root.get("data").isJsonArray()) return null;
                for (JsonElement el : root.getAsJsonArray("data")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject item = el.getAsJsonObject();
                    String candTitle = item.has("title") ? item.get("title").getAsString() : "";
                    String candTitleShort = item.has("title_short") ? item.get("title_short").getAsString() : candTitle;
                    if (!isTitleMatch(cTitle, candTitle, candTitleShort)) continue;
                    if (item.has("isrc") && !item.get("isrc").isJsonNull()) {
                        String s = item.get("isrc").getAsString();
                        if (s != null && !s.trim().isEmpty()) { isrc = s.trim(); break; }
                    }
                }
            }
            if (isrc == null || isrc.isEmpty()) return null;

            String mbUrl = "https://musicbrainz.org/ws/2/isrc/" + isrc + "?fmt=json&inc=url-rels";
            Request mbReq = new Request.Builder()
                    .url(mbUrl)
                    .header("User-Agent", "LyricSync/16.4.29 (https://github.com/Anniqa/LyricSync)")
                    .header("Accept", "application/json")
                    .get()
                    .build();
            try (Response resp = client.newCall(mbReq).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                JsonObject root = JsonParser.parseString(resp.body().string()).getAsJsonObject();
                if (!root.has("recordings") || !root.get("recordings").isJsonArray()) return null;
                for (JsonElement rEl : root.getAsJsonArray("recordings")) {
                    if (!rEl.isJsonObject()) continue;
                    JsonObject rec = rEl.getAsJsonObject();
                    if (!rec.has("relations") || !rec.get("relations").isJsonArray()) continue;
                    for (JsonElement relEl : rec.getAsJsonArray("relations")) {
                        if (!relEl.isJsonObject()) continue;
                        JsonObject urlObj = relEl.getAsJsonObject().getAsJsonObject("url");
                        if (urlObj == null) continue;
                        String res = urlObj.has("resource") && !urlObj.get("resource").isJsonNull()
                                ? urlObj.get("resource").getAsString() : null;
                        if (res == null) continue;
                        String prefix = "open.spotify.com/track/";
                        int i = res.indexOf(prefix);
                        if (i >= 0) {
                            String part = res.substring(i + prefix.length());
                            int q = part.indexOf('?');
                            if (q >= 0) part = part.substring(0, q);
                            if (part.length() == 22) {
                                AppLog.i(TAG, "Resolved Spotify track ID via ISRC " + isrc + ": " + part);
                                return part;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            AppLog.w(TAG, "ISRC resolution failed: " + e.getMessage());
        }
        return null;
    }

    // ── matching helpers ───────────────────────────────────────────────────
    private static boolean isTitleMatch(String queryTitle, String candTitle, String candTitleShort) {
        if (queryTitle == null || queryTitle.trim().isEmpty()) return false;
        String qClean = cleanTitle(queryTitle).toLowerCase(Locale.ROOT);
        String cClean = cleanTitle(candTitle).toLowerCase(Locale.ROOT);
        String cShort = cleanTitle(candTitleShort).toLowerCase(Locale.ROOT);
        if (qClean.equals(cClean) || qClean.equals(cShort)) return true;

        Set<String> qTokens = tokens(qClean);
        double maxOverlap = Math.max(tokenOverlap(qTokens, tokens(cClean)), tokenOverlap(qTokens, tokens(cShort)));
        if (maxOverlap >= 0.35) return true;

        int minLen = Math.min(qClean.length(), cClean.length());
        int maxLen = Math.max(qClean.length(), cClean.length());
        if (minLen >= 4 && (double) minLen / maxLen >= 0.6 && (cClean.contains(qClean) || qClean.contains(cClean))) {
            return true;
        }
        return false;
    }

    static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace("&", " and ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    /** Sørensen-Dice over character bigrams: compact, deterministic, cheap. */
    private static double similarity(String left, String right) {
        if (left.equals(right)) return 1.0;
        if (left.isEmpty() || right.isEmpty()) return 0.0;
        if (left.length() == 1 || right.length() == 1) return left.equals(right) ? 1.0 : 0.0;

        List<String> lb = bigrams(left);
        List<String> rb = new ArrayList<>(bigrams(right));
        int inter = 0;
        for (String bg : lb) {
            int idx = rb.indexOf(bg);
            if (idx >= 0) { inter++; rb.remove(idx); }
        }
        return 2.0 * inter / (lb.size() + bigrams(right).size());
    }

    private static List<String> bigrams(String s) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i + 1 < s.length(); i++) out.add(s.substring(i, i + 2));
        return out;
    }

    private static Set<String> tokens(String s) {
        if (s == null) return Collections.emptySet();
        Set<String> set = new HashSet<>();
        for (String p : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!p.isEmpty()) set.add(p);
        }
        return set;
    }

    private static double tokenOverlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int common = 0;
        for (String s : a) if (b.contains(s)) common++;
        return (double) common / Math.max(a.size(), b.size());
    }

    private static String optString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        try { return obj.get(key).getAsString(); } catch (Exception e) { return null; }
    }
}
