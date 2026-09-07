package com.lyricsync.app.lyrics.provider;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.util.AppLog;
import com.lyricsync.app.ai.transliteration.ScriptDetector;
import com.lyricsync.app.lyrics.auth.SpotifyAuthManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private static final String SPICY_VERSION = "6.3.12";
    private static final String SPICY_ORIGIN = "https://xpui.app.spotify.com";
    private static final String SPICY_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 Spotify/1.2.52.442";
    private static final String EMBED_URL = "https://open.spotify.com/embed/track/";
    private static final long EMBED_TOKEN_MAX_AGE_MS = 8 * 60 * 1000L;
    private static final long MIN_REQUEST_INTERVAL_MS = 1500;
    private static final int MAX_RETRIES = 2;
    private static final long[] RETRY_DELAYS_MS = {1200, 2500};

    private final Context context;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
    private volatile long lastRequestTime = 0;
    private volatile String cachedEmbedToken = null;
    private volatile long embedTokenFetchedAt = 0;

    public void invalidateEmbedToken() {
        cachedEmbedToken = null;
        embedTokenFetchedAt = 0;
        try {
            SpotifyAuthManager.getInstance(context).invalidateToken();
        } catch (Exception ignored) {}
    }

    public SpicyLyricsProvider(Context context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "SpicyLyrics";
    }

    @Override
    public LyricsData fetchLyrics(TrackInfo track) throws Exception {
        String trackId = extractSpotifyId(track);
        if (trackId == null) {
            trackId = resolveSpotifyTrackId(track);
        }
        if (trackId == null) {
            throw new IOException("No Spotify track ID available");
        }

        String jsonBody = "{\"queries\":[{\"operation\":\"lyrics\",\"variables\":{\"id\":\""
                + trackId + "\",\"auth\":\"SpicyLyrics-WebAuth\"}}],\"client\":{\"version\":\"" + SPICY_VERSION + "\"}}";

        // Rate limiting: enforce minimum interval between requests
        long now = System.currentTimeMillis();
        long elapsed = now - lastRequestTime;
        if (elapsed < MIN_REQUEST_INTERVAL_MS) {
            Thread.sleep(MIN_REQUEST_INTERVAL_MS - elapsed);
        }

        // Retry with backoff on 429, server errors, or token failure
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            lastRequestTime = System.currentTimeMillis();
            if (attempt > 0) {
                invalidateEmbedToken();
            }
            String token = getCachedToken(trackId);

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
                    .header("X-mode", "2")
                    .header("User-Agent", SPICY_USER_AGENT);

            if (token != null) {
                builder.header("SpicyLyrics-WebAuth", "Bearer " + token);
            }
            Request request = builder.build();

            try (Response response = client.newCall(request).execute()) {
                if (response.code() == 429) {
                    invalidateEmbedToken();
                    if (attempt < MAX_RETRIES) {
                        long delay = RETRY_DELAYS_MS[attempt];
                        AppLog.w(TAG, "Rate limited (429), retrying in " + delay + "ms (attempt " + (attempt + 1) + ")");
                        Thread.sleep(delay);
                        continue;
                    }
                    throw new IOException("HTTP 429 Too Many Requests (after " + MAX_RETRIES + " retries)");
                }
                if (!response.isSuccessful()) {
                    invalidateEmbedToken();
                    if (attempt < MAX_RETRIES && response.code() >= 500) {
                        long delay = RETRY_DELAYS_MS[attempt];
                        AppLog.w(TAG, "Server error (" + response.code() + "), retrying in " + delay + "ms");
                        Thread.sleep(delay);
                        continue;
                    }
                    throw new IOException("HTTP " + response.code());
                }

                if (response.body() == null) throw new IOException("Empty response body");
                String body = response.body().string();
                try {
                    return parseResponse(body);
                } catch (IOException e) {
                    invalidateEmbedToken();
                    if (attempt < MAX_RETRIES) {
                        long delay = RETRY_DELAYS_MS[attempt];
                        AppLog.w(TAG, "API error: " + e.getMessage() + ", retrying with fresh token in " + delay + "ms");
                        Thread.sleep(delay);
                        continue;
                    }
                    throw e;
                }
            }
        }
        throw new IOException("SpicyLyrics request failed");
    }

    private String getCachedToken(String trackId) {
        try {
            SpotifyAuthManager authMgr = SpotifyAuthManager.getInstance(context);
            String userToken = authMgr.getValidAccessTokenSync();
            if (userToken != null && !userToken.trim().isEmpty()) {
                return userToken.trim();
            }
        } catch (Exception ignored) {}

        if (cachedEmbedToken != null && (System.currentTimeMillis() - embedTokenFetchedAt) < EMBED_TOKEN_MAX_AGE_MS) {
            return cachedEmbedToken;
        }
        return fetchEmbedToken(trackId);
    }

    private String fetchEmbedToken(String trackId) {
        String target = (trackId != null && !trackId.isEmpty()) ? trackId : "4fouWK6XVHhzl78KzQ1UjL";
        String token = tryFetchEmbedPage(target);
        if (token != null) return token;
        if (!target.equals("4fouWK6XVHhzl78KzQ1UjL")) {
            return tryFetchEmbedPage("4fouWK6XVHhzl78KzQ1UjL");
        }
        return null;
    }

    private String tryFetchEmbedPage(String trackId) {
        try {
            Request request = new Request.Builder()
                    .url(EMBED_URL + trackId)
                    .get()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                String html = response.body().string();
                int idx = html.indexOf("\"accessToken\":\"");
                if (idx < 0) return null;
                int start = idx + 15;
                int end = html.indexOf("\"", start);
                if (end < 0) return null;
                String token = html.substring(start, end);
                if (token.length() < 20) return null;
                cachedEmbedToken = token;
                embedTokenFetchedAt = System.currentTimeMillis();
                AppLog.i(TAG, "Fetched Spotify embed token len=" + token.length() + " for track " + trackId);
                return token;
            }
        } catch (Exception e) {
            AppLog.w(TAG, "Failed to fetch embed token for " + trackId + ": " + e.getMessage());
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
                    JsonElement dataElem = res.get("data");
                    if (dataElem.isJsonObject()) {
                        result = dataElem.getAsJsonObject();
                    } else if (dataElem.isJsonArray()) {
                        try {
                            JsonElement unpacked = SpicyLyricsObjPack.unpack(dataElem.getAsJsonArray());
                            if (unpacked != null && unpacked.isJsonObject()) {
                                result = unpacked.getAsJsonObject();
                            }
                        } catch (Exception e) {
                            AppLog.e(TAG, "Failed to unpack SpicyLyrics SLObjPack data", e);
                        }
                    }
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
                if (start < 0 || end < 0) continue;
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
            if (lineStart < 0 || lineEnd < 0) continue;

            // SpicyLyrics syllables are sub-word timing units and carry NO spaces.
            // The IsPartOfWord flag marks a syllable that continues into the NEXT
            // syllable, so we must merge syllables back into real words or the line
            // renders as one run-on word (e.g. "Ibeentrynacall").
            StringBuilder lineText = new StringBuilder();
            List<LyricsData.Word> leadWords = buildWordsFromSyllables(leadSyllables, lineText);

            if (leadWords.isEmpty()) continue;

            LyricsData.LyricsLine line = new LyricsData.LyricsLine(lineStart, lineEnd, lineText.toString().trim());
            line.words.addAll(leadWords);

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
                        if (bgStart < 0 || bgEnd < 0) continue;

                        StringBuilder bgText = new StringBuilder();
                        List<LyricsData.Word> bgWords = buildWordsFromSyllables(bgSyllables, bgText);
                        if (bgWords.isEmpty()) continue;

                        LyricsData.LyricsLine bgLine = new LyricsData.LyricsLine(bgStart, bgEnd, bgText.toString().trim());
                        bgLine.words.addAll(bgWords);

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

    /**
     * Merge SpicyLyrics syllables into real words. A syllable whose IsPartOfWord flag
     * is true continues the word that the NEXT syllable begins, so consecutive
     * syllables are grouped into one Word until a syllable that is not part of a word
     * starts a new one. The merged, space-joined text is appended to {@code lineText}.
     */
    private List<LyricsData.Word> buildWordsFromSyllables(JsonArray syllables, StringBuilder lineText) {
        List<LyricsData.Word> words = new ArrayList<>();
        if (syllables == null) return words;

        // Check if syllables contain non-Latin scripts (Japanese Kanji/Kana, Chinese, Korean)
        boolean hasNonLatin = false;
        for (int i = 0; i < syllables.size(); i++) {
            JsonObject syl = syllables.get(i).getAsJsonObject();
            String text = safeString(syl, "Text", "text");
            if (ScriptDetector.isNonLatin(text)) {
                hasNonLatin = true;
                break;
            }
        }

        if (hasNonLatin) {
            // In CJK languages without word spaces, keep each syllable/mora distinct with its own
            // timing from SpicyLyrics. Merging all syllables into a single Word destroys per-syllable
            // timing and creates an unbreakable run-on line that overflows the overlay.
            for (int i = 0; i < syllables.size(); i++) {
                JsonObject syl = syllables.get(i).getAsJsonObject();
                String text = safeString(syl, "Text", "text");
                if (text.isEmpty()) continue;
                long start = getTimeMs(syl, "StartTime");
                long end = getTimeMs(syl, "EndTime");
                if (start < 0 || end < 0) continue;
                boolean partOfWord = syl.has("IsPartOfWord") && !syl.get("IsPartOfWord").isJsonNull()
                        && syl.get("IsPartOfWord").getAsBoolean();

                LyricsData.Word w = new LyricsData.Word(start, end, text);
                w.isPartOfWord = partOfWord;
                words.add(w);
                lineText.append(text);
            }
            return words;
        }
        StringBuilder cur = new StringBuilder();
        long curStart = 0;
        long curEnd = 0;
        boolean prevPartOfWord = false;

        for (int i = 0; i < syllables.size(); i++) {
            JsonObject syl = syllables.get(i).getAsJsonObject();
            String text = safeString(syl, "Text", "text");
            if (text.isEmpty()) continue;
            long start = getTimeMs(syl, "StartTime");
            long end = getTimeMs(syl, "EndTime");
            if (start < 0 || end < 0) continue;
            boolean partOfWord = syl.has("IsPartOfWord") && !syl.get("IsPartOfWord").isJsonNull()
                    && syl.get("IsPartOfWord").getAsBoolean();

            if (cur.length() == 0) {
                curStart = start;
                curEnd = end;
                cur.append(text);
            } else if (prevPartOfWord) {
                // previous syllable was part of a word -> this one continues it
                curEnd = end;
                cur.append(text);
            } else {
                // previous syllable ended a word -> flush, start a new one
                words.add(new LyricsData.Word(curStart, curEnd, cur.toString().trim()));
                if (lineText.length() > 0) lineText.append(' ');
                lineText.append(cur.toString().trim());
                cur.setLength(0);
                curStart = start;
                curEnd = end;
                cur.append(text);
            }
            prevPartOfWord = partOfWord;
        }

        if (cur.length() > 0) {
            words.add(new LyricsData.Word(curStart, curEnd, cur.toString().trim()));
            if (lineText.length() > 0) lineText.append(' ');
            lineText.append(cur.toString().trim());
        }
        return words;
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
                if (start < 0 && lineObj.has("Time")) {
                    JsonObject time = lineObj.getAsJsonObject("Time");
                    start = getTimeMs(time, "Start");
                    end = getTimeMs(time, "End");
                }
                if (start >= 0 && end >= 0 && start < end) {
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
            if (start < 0 && lineObj.has("Time")) {
                JsonObject time = lineObj.getAsJsonObject("Time");
                start = getTimeMs(time, "Start");
                end = getTimeMs(time, "End");
            }
            if (start < 0 || end < 0) continue;

            LyricsData.LyricsLine line = new LyricsData.LyricsLine(start, end, text);
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
        return -1;
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

    private String resolveSpotifyTrackId(TrackInfo track) {
        if (!track.isValid()) return null;

        String id = extractSpotifyId(track);
        if (id != null) return id;

        String title = track.title;
        String artist = track.artist;
        if (title != null && title.contains(" - ")) {
            String[] parts = title.split(" - ", 2);
            if (artist == null || artist.trim().isEmpty() || artist.toLowerCase(Locale.US).contains("topic")) {
                artist = parts[0];
                title = parts[1];
            }
        }

        String isrcResolvedId = SpotifyTrackResolver.resolve(title, artist);
        if (isrcResolvedId != null) {
            track.trackId = "spotify:track:" + isrcResolvedId;
            AppLog.i(TAG, "Resolved trackId via SpotifyTrackResolver: " + isrcResolvedId + " for " + title);
            return isrcResolvedId;
        }
        return null;
    }
    private boolean isSpotifyTrackId(String id) {
        return id != null && id.matches("[A-Za-z0-9]{22}");
    }
}
