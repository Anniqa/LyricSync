package com.lyricsync.app.lyrics.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.util.AppLog;

import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Resolves song title and artist into an official Spotify track ID using public
 * ISRC catalogs (Deezer Search -> MusicBrainz ISRC cross-reference).
 * Completely free, requires no API keys, and never gets 401/429 rate-limited.
 */
public class SpotifyTrackResolver {
    private static final String TAG = "SpotifyTrackResolver";
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();

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

    private static java.util.Set<String> tokens(String s) {
        if (s == null) return java.util.Collections.emptySet();
        java.util.Set<String> set = new java.util.HashSet<>();
        String[] parts = s.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+");
        for (String p : parts) {
            if (!p.isEmpty()) {
                set.add(p);
            }
        }
        return set;
    }

    private static double tokenOverlap(java.util.Set<String> a, java.util.Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int common = 0;
        for (String s : a) {
            if (b.contains(s)) common++;
        }
        return (double) common / Math.max(a.size(), b.size());
    }

    private static boolean isTitleMatch(String queryTitle, String candTitle, String candTitleShort) {
        if (queryTitle == null || queryTitle.trim().isEmpty()) return false;
        String qClean = cleanTitle(queryTitle).toLowerCase(java.util.Locale.ROOT);
        String cClean = cleanTitle(candTitle).toLowerCase(java.util.Locale.ROOT);
        String cShort = cleanTitle(candTitleShort).toLowerCase(java.util.Locale.ROOT);

        if (qClean.equals(cClean) || qClean.equals(cShort)) {
            return true;
        }

        java.util.Set<String> qTokens = tokens(qClean);
        java.util.Set<String> cTokens = tokens(cClean);
        java.util.Set<String> sTokens = tokens(cShort);

        double overlapClean = tokenOverlap(qTokens, cTokens);
        double overlapShort = tokenOverlap(qTokens, sTokens);
        double maxOverlap = Math.max(overlapClean, overlapShort);

        if (maxOverlap >= 0.35) {
            return true;
        }

        int minLen = Math.min(qClean.length(), cClean.length());
        int maxLen = Math.max(qClean.length(), cClean.length());
        if (minLen >= 4 && (double) minLen / maxLen >= 0.6 && (cClean.contains(qClean) || qClean.contains(cClean))) {
            return true;
        }

        return false;
    }

    public static String resolve(String title, String artist) {
        if (title == null || title.trim().isEmpty()) return null;
        String cTitle = cleanTitle(title);
        String cArtist = cleanArtist(artist);
        try {
            String query = (cTitle + " " + cArtist).trim();
            String deezerUrl = "https://api.deezer.com/search?q=" + URLEncoder.encode(query, "UTF-8");
            Request dReq = new Request.Builder()
                    .url(deezerUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .get()
                    .build();

            String isrc = null;
            try (Response resp = client.newCall(dReq).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                JsonObject root = JsonParser.parseString(resp.body().string()).getAsJsonObject();
                if (!root.has("data") || !root.get("data").isJsonArray()) return null;
                JsonArray items = root.getAsJsonArray("data");
                for (JsonElement el : items) {
                    if (!el.isJsonObject()) continue;
                    JsonObject item = el.getAsJsonObject();
                    String candTitle = item.has("title") ? item.get("title").getAsString() : "";
                    String candTitleShort = item.has("title_short") ? item.get("title_short").getAsString() : candTitle;
                    if (!isTitleMatch(cTitle, candTitle, candTitleShort)) {
                        continue;
                    }
                    if (item.has("isrc") && !item.get("isrc").isJsonNull()) {
                        String s = item.get("isrc").getAsString();
                        if (s != null && !s.trim().isEmpty()) {
                            isrc = s.trim();
                            break;
                        }
                    }
                }
            }

            if (isrc == null || isrc.isEmpty()) return null;

            String mbUrl = "https://musicbrainz.org/ws/2/isrc/" + isrc + "?fmt=json&inc=url-rels";
            Request mbReq = new Request.Builder()
                    .url(mbUrl)
                    .header("User-Agent", "LyricSync/16.4.3 (https://github.com/Anniqa/LyricSync)")
                    .header("Accept", "application/json")
                    .get()
                    .build();

            try (Response resp = client.newCall(mbReq).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                JsonObject root = JsonParser.parseString(resp.body().string()).getAsJsonObject();
                if (!root.has("recordings") || !root.get("recordings").isJsonArray()) return null;
                JsonArray recs = root.getAsJsonArray("recordings");
                for (JsonElement rEl : recs) {
                    if (!rEl.isJsonObject()) continue;
                    JsonObject rec = rEl.getAsJsonObject();
                    if (!rec.has("relations") || !rec.get("relations").isJsonArray()) continue;
                    JsonArray rels = rec.getAsJsonArray("relations");
                    for (JsonElement relEl : rels) {
                        if (!relEl.isJsonObject()) continue;
                        JsonObject rel = relEl.getAsJsonObject();
                        if (rel.has("url") && rel.get("url").isJsonObject()) {
                            JsonObject urlObj = rel.getAsJsonObject("url");
                            if (urlObj.has("resource") && !urlObj.get("resource").isJsonNull()) {
                                String res = urlObj.get("resource").getAsString();
                                int idx = res.indexOf("open.spotify.com/track/");
                                if (idx >= 0) {
                                    String part = res.substring(idx + "open.spotify.com/track/".length());
                                    int qIdx = part.indexOf('?');
                                    if (qIdx >= 0) part = part.substring(0, qIdx);
                                    if (part.length() == 22) {
                                        AppLog.i(TAG, "Resolved Spotify track ID: " + part + " via ISRC " + isrc + " for " + title);
                                        return part;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            AppLog.w(TAG, "Track resolution failed: " + e.getMessage());
        }
        return null;
    }
}
