package com.lyricsync.app.lyrics.provider;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.util.AppLog;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SpicyLyricsProvider implements LyricsProvider {
    private static final String TAG = "SpicyLyrics";
    private static final String API_URL = "https://api.spicylyrics.org/query";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String SPICY_VERSION = "5.22.3";
    private static final String SPICY_ORIGIN = "https://xpui.app.spotify.com";
    private static final String SPICY_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Spotify/1.2.63 Chrome/132.0.6834.210 Electron/34.3.1 Safari/537.36";
    private static final String EMBED_URL = "https://open.spotify.com/embed/track/";
    private static final String SPOTIFY_SEARCH_URL = "https://api.spotify.com/v1/search";
    private static final String PREFS_NAME = "spotify_token";
    private static final String PREF_TOKEN = "access_token";
    private static final String PREF_TOKEN_TIME = "token_captured_at";
    private static final long TOKEN_MAX_AGE_MS = 50 * 60 * 1000L;
    private static final long EMBED_TOKEN_MAX_AGE_MS = 55 * 60 * 1000L;
    private static final long MIN_REQUEST_INTERVAL_MS = 2000;
    private static final int MAX_RETRIES = 2;
    private static final long[] RETRY_DELAYS_MS = {1500, 3000};

    private final Context context;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
    private volatile long lastRequestTime = 0;
    private volatile String cachedEmbedToken = null;
    private volatile long embedTokenFetchedAt = 0;

    public SpicyLyricsProvider(Context context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "SpicyLyrics";
    }

    @Override
    public LyricsData fetchLyrics(TrackInfo track) throws Exception {
        String token = getCachedToken();
        String trackId = extractSpotifyId(track);
        if (trackId == null) {
            trackId = resolveSpotifyTrackId(track, token);
        }
        if (trackId == null) {
            throw new IOException("No Spotify track ID available");
        }

        String jsonBody = "{\"queries\":[{\"operation\":\"lyrics\",\"variables\":{\"id\":\""
                + trackId + "\",\"auth\":\"SpicyLyrics-WebAuth\"}}],\"client\":{\"version\":\"" + SPICY_VERSION + "\"}}";

        Request.Builder builder = new Request.Builder()
                .url(API_URL)
                .post(RequestBody.create(jsonBody, JSON))
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Content-Type", "application/json")
                .header("Origin", SPICY_ORIGIN)
                .header("Referer", SPICY_ORIGIN + "/")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "cross-site")
                .header("SpicyLyrics-Version", SPICY_VERSION)
                .header("User-Agent", SPICY_USER_AGENT);

        if (token != null) {
            builder.header("SpicyLyrics-WebAuth", "Bearer " + token);
        }

        Request request = builder.build();

        // Rate limiting: enforce minimum interval between requests
        long now = System.currentTimeMillis();
        long elapsed = now - lastRequestTime;
        if (elapsed < MIN_REQUEST_INTERVAL_MS) {
            Thread.sleep(MIN_REQUEST_INTERVAL_MS - elapsed);
        }

        // Retry with backoff on 429
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            lastRequestTime = System.currentTimeMillis();
            try (Response response = client.newCall(request).execute()) {
                if (response.code() == 429) {
                    if (attempt < MAX_RETRIES) {
                        long delay = RETRY_DELAYS_MS[attempt];
                        AppLog.w(TAG, "Rate limited (429), retrying in " + delay + "ms (attempt " + (attempt + 1) + ")");
                        Thread.sleep(delay);
                        continue;
                    }
                    throw new IOException("HTTP 429 Too Many Requests (after " + MAX_RETRIES + " retries)");
                }
                if (!response.isSuccessful()) {
                    throw new IOException("HTTP " + response.code());
                }

                String body = response.body().string();
                return parseResponse(body);
            }
        }
        throw new IOException("SpicyLyrics request failed");
    }

    private String getCachedToken() {
        // 1. Try LSPosed-captured token (highest quality — real user token)
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String token = prefs.getString(PREF_TOKEN, null);
            long capturedAt = prefs.getLong(PREF_TOKEN_TIME, 0);
            if (token != null && (System.currentTimeMillis() - capturedAt) < TOKEN_MAX_AGE_MS) {
                return token;
            }
        } catch (Exception e) {
            AppLog.w(TAG, "Failed to read cached token: " + e.getMessage());
        }

        // 2. Try Spotify embed token (anonymous, gets Syllable type)
        if (cachedEmbedToken != null && (System.currentTimeMillis() - embedTokenFetchedAt) < EMBED_TOKEN_MAX_AGE_MS) {
            return cachedEmbedToken;
        }
        return fetchEmbedToken();
    }

    private String fetchEmbedToken() {
        try {
            // Use any known Spotify track ID for the embed page
            Request request = new Request.Builder()
                    .url(EMBED_URL + "4fouWK6XVHhzl78KzQ1UjL")
                    .get()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) return null;
                String html = response.body().string();
                // Extract accessToken from JSON in page: "accessToken":"BQD..."
                int idx = html.indexOf("\"accessToken\":\"");
                if (idx < 0) return null;
                int start = idx + 15;
                int end = html.indexOf("\"", start);
                if (end < 0) return null;
                String token = html.substring(start, end);
                if (token.length() < 20) return null;
                cachedEmbedToken = token;
                embedTokenFetchedAt = System.currentTimeMillis();
                AppLog.i(TAG, "Fetched Spotify embed token len=" + token.length());
                return token;
            }
        } catch (Exception e) {
            AppLog.w(TAG, "Failed to fetch embed token: " + e.getMessage());
            return null;
        }
    }

    private LyricsData parseResponse(String body) throws Exception {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray queries = root.getAsJsonArray("queries");

        if (queries == null || queries.isEmpty()) {
            throw new IOException("No lyrics queries result");
        }

        JsonObject result = null;
        for (JsonElement q : queries) {
            JsonObject obj = q.getAsJsonObject();
            if (obj.has("result")) {
                JsonObject res = obj.getAsJsonObject("result");
                if (res.has("data")) {
                    result = res.getAsJsonObject("data");
                }
                break;
            }
        }

        if (result == null) {
            throw new IOException("No lyrics data found");
        }

        // Check for error in response
        if (result.has("error")) {
            String err = safeString(result, "error");
            if (!err.isEmpty() && !err.equals("None")) {
                throw new IOException("API error: " + err);
            }
        }

        LyricsData lyrics = new LyricsData();
        lyrics.provider = getName();

        String type = safeString(result, "Type", "type");

        // Log response structure for debugging
        StringBuilder resultKeys = new StringBuilder();
        for (String k : result.keySet()) resultKeys.append(k).append(",");
        AppLog.d(TAG, "Response keys: [" + resultKeys + "] Type=" + type);

        // Infer type from structure if empty
        if (type.isEmpty()) {
            if (result.has("Content")) {
                JsonArray content = result.getAsJsonArray("Content");
                if (content != null && content.size() > 0) {
                    JsonObject first = content.get(0).getAsJsonObject();
                    if (first.has("Lead")) type = "Syllable";
                    else if (first.has("Text") || first.has("text")) type = "Line";
                    else type = "Line";
                }
            } else if (result.has("Lines")) {
                type = "Static";
            }
            if (type.isEmpty()) type = "Static"; // ultimate fallback
            AppLog.d(TAG, "Type was empty, inferred: " + type);
        }

        switch (type) {
            case "Syllable":
                lyrics.type = LyricsData.Type.SYLLABLE;
                parseSyllableLyrics(result, lyrics);
                break;
            case "Line":
                lyrics.type = LyricsData.Type.LINE;
                parseLineLyrics(result, lyrics);
                break;
            case "Static":
            default:
                lyrics.type = LyricsData.Type.STATIC;
                parseStaticLyrics(result, lyrics);
                break;
        }

        return lyrics;
    }

    private void parseSyllableLyrics(JsonObject data, LyricsData lyrics) {
        JsonArray content = data.getAsJsonArray("Content");
        if (content == null) return;

        for (JsonElement groupEl : content) {
            JsonObject group = groupEl.getAsJsonObject();
            String groupType = safeString(group, "Type", "type");

            // Log all keys for debugging bg vocal detection
            StringBuilder keys = new StringBuilder();
            for (String k : group.keySet()) keys.append(k).append(",");
            AppLog.d(TAG, "Group keys: [" + keys + "] type=" + groupType);

            // Interlude: {"Type":"Interlude","StartTime":...,"EndTime":...}
            if ("Interlude".equals(groupType)) {
                long start = getTimeMs(group, "StartTime");
                long end = getTimeMs(group, "EndTime");
                LyricsData.LyricsLine line = new LyricsData.LyricsLine(start, end, "");
                line.isInterlude = true;
                lyrics.lines.add(line);
                continue;
            }

            // Vocal: {"Type":"Vocal","Lead":{...},"Background":[...]}
            if (!group.has("Lead")) continue;
            JsonObject lead = group.getAsJsonObject("Lead");
            JsonArray leadSyllables = lead.getAsJsonArray("Syllables");
            if (leadSyllables == null || leadSyllables.size() == 0) continue;

            long lineStart = getTimeMs(lead, "StartTime");
            long lineEnd = getTimeMs(lead, "EndTime");

            // Build lead text from syllables
            StringBuilder lineText = new StringBuilder();
            for (JsonElement sylEl : leadSyllables) {
                JsonObject syl = sylEl.getAsJsonObject();
                lineText.append(safeString(syl, "Text", "text"));
            }

            if (lineText.length() == 0) continue;

            LyricsData.LyricsLine line = new LyricsData.LyricsLine(lineStart, lineEnd, lineText.toString().trim());

            // Add lead syllables as words
            for (JsonElement sylEl : leadSyllables) {
                JsonObject syl = sylEl.getAsJsonObject();
                String text = safeString(syl, "Text", "text");
                long start = getTimeMs(syl, "StartTime");
                long end = getTimeMs(syl, "EndTime");
                line.words.add(new LyricsData.Word(start, end, text));
            }

            // Parse background vocals (Spicy EX: checks both "Background" and "background")
            JsonArray bgArray = null;
            if (group.has("Background")) bgArray = group.getAsJsonArray("Background");
            else if (group.has("background")) bgArray = group.getAsJsonArray("background");
            if (bgArray != null && bgArray.size() > 0) {
                    AppLog.d(TAG, "BG vocals found: " + bgArray.size() + " entries for line: " + lineText);
                    line.backgroundVocals = new java.util.ArrayList<>();
                    for (JsonElement bgEl : bgArray) {
                        JsonObject bg = bgEl.getAsJsonObject();
                        JsonArray bgSyllables = bg.getAsJsonArray("Syllables");
                        if (bgSyllables == null || bgSyllables.size() == 0) continue;

                        long bgStart = getTimeMs(bg, "StartTime");
                        long bgEnd = getTimeMs(bg, "EndTime");

                        StringBuilder bgText = new StringBuilder();
                        for (JsonElement sylEl : bgSyllables) {
                            JsonObject syl = sylEl.getAsJsonObject();
                            bgText.append(safeString(syl, "Text", "text"));
                        }

                        if (bgText.length() == 0) continue;

                        LyricsData.LyricsLine bgLine = new LyricsData.LyricsLine(bgStart, bgEnd, bgText.toString().trim());
                        bgLine.isBackground = true;

                        for (JsonElement sylEl : bgSyllables) {
                            JsonObject syl = sylEl.getAsJsonObject();
                            String text = safeString(syl, "Text", "text");
                            long start = getTimeMs(syl, "StartTime");
                            long end = getTimeMs(syl, "EndTime");
                            bgLine.words.add(new LyricsData.Word(start, end, text));
                        }

                        line.backgroundVocals.add(bgLine);
                    }
                }

            lyrics.lines.add(line);
        }

        int bgCount = 0;
        for (LyricsData.LyricsLine l : lyrics.lines) {
            if (l.backgroundVocals != null && !l.backgroundVocals.isEmpty()) bgCount++;
        }
        AppLog.i(TAG, "Parsed " + lyrics.lines.size() + " lines, " + bgCount + " with bg vocals");

        // SpicyLyrics generates the musical-line (instrumental dots) from gaps
        // between lines (>= getLyricsBetweenShow() = 3s), not only from explicit
        // Interlude types. Match that behaviour.
        LyricsData.insertInterludes(lyrics, 3000);
    }

    private void parseLineLyrics(JsonObject data, LyricsData lyrics) {
        JsonArray content = data.getAsJsonArray("Content");
        if (content == null) content = data.getAsJsonArray("Lines");
        if (content == null) return;

        for (JsonElement lineEl : content) {
            JsonObject lineObj = lineEl.getAsJsonObject();
            String lineType = safeString(lineObj, "Type", "type");

            if ("Interlude".equals(lineType)) {
                long start = getTimeMs(lineObj, "StartTime", "startTime");
                long end = getTimeMs(lineObj, "EndTime", "endTime");
                if (start == 0 && lineObj.has("Time")) {
                    JsonObject time = lineObj.getAsJsonObject("Time");
                    start = getTimeMs(time, "Start");
                    end = getTimeMs(time, "End");
                }
                if (start < end) {
                    LyricsData.LyricsLine line = new LyricsData.LyricsLine(start, end, "");
                    line.isInterlude = true;
                    lyrics.lines.add(line);
                }
                continue;
            }

            String text = safeString(lineObj, "Text", "text");
            if (text.isEmpty()) continue;

            // SpicyLyrics Line type: StartTime/EndTime at top level
            long start = getTimeMs(lineObj, "StartTime", "startTime");
            long end = getTimeMs(lineObj, "EndTime", "endTime");
            // Fallback: Time sub-object
            if (start == 0 && lineObj.has("Time")) {
                JsonObject time = lineObj.getAsJsonObject("Time");
                start = getTimeMs(time, "Start");
                end = getTimeMs(time, "End");
            }

            LyricsData.LyricsLine line = new LyricsData.LyricsLine(start, end, text);
            line.words.add(new LyricsData.Word(start, end, text));
            lyrics.lines.add(line);
        }

        LyricsData.insertInterludes(lyrics, 3000);
        AppLog.d(TAG, "Line lyrics: " + lyrics.lines.size() + " lines");
    }

    private void parseStaticLyrics(JsonObject data, LyricsData lyrics) {
        // SpicyLyrics API uses "Lines" for Static type, not "Content"
        JsonArray content = data.getAsJsonArray("Lines");
        if (content == null) content = data.getAsJsonArray("Content");
        if (content == null) return;

        long time = 0;
        for (JsonElement lineEl : content) {
            JsonObject lineObj = lineEl.getAsJsonObject();
            String text = safeString(lineObj, "Text", "text");
            if (!text.isEmpty()) {
                LyricsData.LyricsLine line = new LyricsData.LyricsLine(time, time + 5000, text);
                lyrics.lines.add(line);
                time += 5000;
            }
        }
        AppLog.d(TAG, "Static lyrics: " + lyrics.lines.size() + " lines");
    }

    private long getTimeMs(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key)) {
                try {
                    return (long) (obj.get(key).getAsDouble() * 1000);
                } catch (Exception ignored) {
                }
            }
        }
        return 0;
    }

    private static String safeString(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                try { return obj.get(key).getAsString(); } catch (Exception ignored) {}
            }
        }
        return "";
    }

    private String extractSpotifyId(TrackInfo track) {
        if (track.trackId == null || track.trackId.isEmpty()) return null;
        String id = track.trackId.trim();
        if (id.startsWith("spotify:track:")) {
            id = id.substring("spotify:track:".length());
        } else if (id.contains("open.spotify.com/track/")) {
            int start = id.indexOf("open.spotify.com/track/") + "open.spotify.com/track/".length();
            int end = id.indexOf('?', start);
            id = end >= 0 ? id.substring(start, end) : id.substring(start);
        }
        if (!isSpotifyTrackId(id)) return null;
        return id;
    }

    private String resolveSpotifyTrackId(TrackInfo track, String token) {
        if (token == null || token.isEmpty() || !track.isValid()) return null;
        try {
            String cleanTitle = cleanSearchTitle(track.title);
            String query = "track:\"" + cleanTitle + "\" artist:\"" + track.artist + "\"";
            String url = SPOTIFY_SEARCH_URL
                    + "?type=track&limit=8&market=US&q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8.name());

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    AppLog.w(TAG, "Spotify search failed: HTTP " + response.code());
                    return null;
                }
                String body = response.body().string();
                return pickBestSpotifyCandidate(track, cleanTitle, body);
            }
        } catch (Exception e) {
            AppLog.w(TAG, "Spotify search failed: " + e.getMessage());
            return null;
        }
    }

    private String pickBestSpotifyCandidate(TrackInfo track, String cleanTitle, String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("tracks")) return null;
        JsonObject tracks = root.getAsJsonObject("tracks");
        JsonArray items = tracks.getAsJsonArray("items");
        if (items == null || items.isEmpty()) return null;

        String bestId = null;
        String bestName = null;
        String bestArtists = null;
        String bestArtwork = null;
        double bestScore = 0;

        for (JsonElement itemEl : items) {
            JsonObject item = itemEl.getAsJsonObject();
            String id = safeString(item, "id");
            String name = safeString(item, "name");
            long durationMs = item.has("duration_ms") ? item.get("duration_ms").getAsLong() : 0;
            String artists = joinArtists(item.getAsJsonArray("artists"));
            if (!isSpotifyTrackId(id) || name.isEmpty() || artists.isEmpty()) continue;

            double score = scoreSpotifyCandidate(cleanTitle, track.artist, track.duration, name, artists, durationMs);
            if (score > bestScore) {
                bestScore = score;
                bestId = id;
                bestName = name;
                bestArtists = artists;
                bestArtwork = extractBestAlbumImageUrl(item);
            }
        }

        if (bestId != null && bestScore >= 0.68d) {
            track.trackId = "spotify:track:" + bestId;
            if ((track.albumArtUri == null || track.albumArtUri.isEmpty()) && bestArtwork != null) {
                track.albumArtUri = bestArtwork;
            }
            AppLog.i(TAG, "Resolved Spotify ID from metadata: " + bestName + " - " + bestArtists
                    + " id=" + bestId + " score=" + String.format(Locale.US, "%.3f", bestScore)
                    + " artwork=" + (bestArtwork != null));
            return bestId;
        }

        AppLog.w(TAG, "Spotify search had no confident match, bestScore="
                + String.format(Locale.US, "%.3f", bestScore));
        return null;
    }

    private String extractBestAlbumImageUrl(JsonObject trackItem) {
        if (!trackItem.has("album") || !trackItem.get("album").isJsonObject()) return null;
        JsonObject album = trackItem.getAsJsonObject("album");
        JsonArray images = album.getAsJsonArray("images");
        if (images == null || images.isEmpty()) return null;

        String fallback = null;
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (JsonElement imageEl : images) {
            if (!imageEl.isJsonObject()) continue;
            JsonObject image = imageEl.getAsJsonObject();
            String url = safeString(image, "url");
            if (url.isEmpty()) continue;
            if (fallback == null) fallback = url;
            int width = image.has("width") && !image.get("width").isJsonNull() ? image.get("width").getAsInt() : 300;
            int distance = Math.abs(width - 300);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = url;
            }
        }
        return best != null ? best : fallback;
    }

    private String joinArtists(JsonArray artists) {
        if (artists == null || artists.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonElement artistEl : artists) {
            JsonObject artist = artistEl.getAsJsonObject();
            String name = safeString(artist, "name");
            if (name.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(name);
        }
        return sb.toString();
    }

    private double scoreSpotifyCandidate(String inputTitle, String inputArtist, long inputDuration,
                                         String candidateTitle, String candidateArtists, long candidateDuration) {
        double titleScore = tokenScore(inputTitle, candidateTitle);
        double artistScore = tokenScore(inputArtist, candidateArtists);
        double durationScore = 0.5d;
        if (inputDuration > 0 && candidateDuration > 0) {
            long diff = Math.abs(inputDuration - candidateDuration);
            durationScore = Math.max(0d, 1d - (diff / 12000d));
        }
        return (titleScore * 0.50d)
                + (artistScore * 0.35d)
                + (durationScore * 0.15d)
                - mismatchPenalty(inputTitle, candidateTitle, candidateArtists);
    }

    private double tokenScore(String a, String b) {
        Set<String> left = tokens(a);
        Set<String> right = tokens(b);
        if (left.isEmpty() || right.isEmpty()) return 0d;
        int hits = 0;
        for (String token : left) {
            if (right.contains(token)) hits++;
        }
        double recall = hits / (double) left.size();
        double precision = hits / (double) right.size();
        return (recall * 0.65d) + (precision * 0.35d);
    }

    private Set<String> tokens(String text) {
        Set<String> set = new HashSet<>();
        String normalized = normalize(text);
        if (normalized.isEmpty()) return set;
        for (String token : normalized.split(" ")) {
            if (token.length() > 1) set.add(token);
        }
        return set;
    }

    private double mismatchPenalty(String inputTitle, String candidateTitle, String candidateArtists) {
        String input = normalize(inputTitle);
        String title = normalize(candidateTitle);
        String artists = normalize(candidateArtists);
        double penalty = 0d;
        String[] badTags = {"live", "remix", "sped", "slowed", "karaoke", "instrumental", "cover"};
        for (String tag : badTags) {
            if ((title.contains(tag) || artists.contains(tag)) && !input.contains(tag)) {
                penalty += 0.18d;
            }
        }
        return Math.min(0.45d, penalty);
    }

    private String cleanSearchTitle(String title) {
        if (title == null) return "";
        String cleaned = title
                .replaceAll("(?i)\\s*\\[[^\\]]*]", "")
                .replaceAll("(?i)\\s*\\([^)]*(official|video|audio|lyric|lyrics|visualizer|mv|music video|hd|4k|performance)[^)]*\\)", "")
                .replaceAll("(?i)\\s+-\\s+(official|video|audio|lyric|lyrics|visualizer|mv|music video|hd|4k).*$", "")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.isEmpty() ? title.trim() : cleaned;
    }

    private String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.US)
                .replace('&', ' ')
                .replaceAll("(?i)\\([^)]*\\)|\\[[^\\]]*]", "")
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isSpotifyTrackId(String id) {
        return id != null && id.matches("[A-Za-z0-9]{22}");
    }
}
