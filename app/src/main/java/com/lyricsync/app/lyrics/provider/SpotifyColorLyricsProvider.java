package com.lyricsync.app.lyrics.provider;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.lyrics.auth.SpotifyAuthManager;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.util.AppLog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Directly queries Spotify's internal color-lyrics API using the authenticated
 * user's Spotify access token. Zero dependency on third-party servers.
 */
public class SpotifyColorLyricsProvider implements LyricsProvider {
    private static final String TAG = "SpotifyColorLyrics";
    private static final String COLOR_LYRICS_URL = "https://spclient.wg.spotify.com/color-lyrics/v2/track/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36";
    /** Synthetic line length used only for payloads that carry no timing at all. */
    private static final long STATIC_LINE_MS = 3500L;

    private final Context context;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    public SpotifyColorLyricsProvider(Context context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "Spotify";
    }

    @Override
    public LyricsData fetchLyrics(TrackInfo track) throws Exception {
        SpotifyAuthManager authMgr = SpotifyAuthManager.getInstance(context);
        if (!authMgr.isAuthenticated()) {
            throw new IOException("Spotify account not authenticated");
        }

        String userToken = authMgr.getValidAccessTokenSync();
        if (userToken == null || userToken.trim().isEmpty()) {
            throw new IOException("Failed to obtain valid Spotify access token");
        }

        String trackId = extractTrackId(track);
        if (trackId == null && track != null && track.isValid()) {
            trackId = SpotifyTrackResolver.resolve(track.title, track.artist, track.duration);
            if (trackId != null) {
                track.trackId = "spotify:track:" + trackId;
            }
        }
        if (trackId == null) {
            throw new IOException("No Spotify track ID available");
        }

        String url = COLOR_LYRICS_URL + trackId + "?format=json&vocalRemoval=false";
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer " + userToken)
                .header("App-Platform", "WebPlayer")
                .header("User-Agent", USER_AGENT)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                if (response.code() == 401) {
                    authMgr.invalidateToken();
                }
                throw new IOException("HTTP " + response.code());
            }

            if (response.body() == null) throw new IOException("Empty response body");
            String jsonStr = response.body().string();
            return parseColorLyricsJson(jsonStr);
        }
    }

    String extractTrackId(TrackInfo track) {
        if (track == null) return null;
        if (track.trackId != null && !track.trackId.isEmpty()) {
            String id = track.trackId;
            if (id.startsWith("spotify:track:")) {
                id = id.substring("spotify:track:".length());
            }
            int qIdx = id.indexOf('?');
            if (qIdx > 0) id = id.substring(0, qIdx);
            if (!id.isEmpty()) return id.trim();
        }
        return null;
    }

    LyricsData parseColorLyricsJson(String jsonStr) throws Exception {
        JsonObject root = JsonParser.parseString(jsonStr).getAsJsonObject();
        if (!root.has("lyrics") || !root.get("lyrics").isJsonObject()) {
            throw new IOException("Invalid color-lyrics response structure");
        }

        JsonObject lyricsObj = root.getAsJsonObject("lyrics");

        JsonArray linesArr = lyricsObj.has("lines") && lyricsObj.get("lines").isJsonArray()
                ? lyricsObj.getAsJsonArray("lines") : null;

        if (linesArr == null || linesArr.isEmpty()) {
            throw new IOException("Empty lyrics lines");
        }

        LyricsData data = new LyricsData();
        data.provider = getName();

        String syncType = lyricsObj.has("syncType") && !lyricsObj.get("syncType").isJsonNull()
                ? lyricsObj.get("syncType").getAsString() : "";

        int lineCount = linesArr.size();

        // Pass 1: read raw line start times with a sentinel so a missing timestamp is
        // distinguishable from a genuine 0 ms, and detect syllables / real timing.
        long[] rawStarts = new long[lineCount];
        boolean hasSyllables = false;
        boolean hasRealStart = false;
        for (int i = 0; i < lineCount; i++) {
            JsonObject lineObj = linesArr.get(i).getAsJsonObject();
            JsonArray sylArr = lineObj.has("syllables") && lineObj.get("syllables").isJsonArray()
                    ? lineObj.getAsJsonArray("syllables") : null;
            if (sylArr != null && sylArr.size() > 0) hasSyllables = true;
            long start = parseLongSafe(lineObj, "startTimeMs");
            if (start < 0 && sylArr != null && sylArr.size() > 0) {
                // A syllable-timed line may omit its own start; anchor it on the first syllable.
                start = parseLongSafe(sylArr.get(0).getAsJsonObject(), "startTimeMs");
            }
            rawStarts[i] = start;
            if (start >= 0) hasRealStart = true;
        }

        // An explicitly UNSYNCED payload, or one with no readable line timestamp, is honest
        // static lyrics: a synthetic grid keeps the order without pretending to be timed.
        boolean staticGrid = isUnsyncedSyncType(syncType) || !hasRealStart;
        long[] startTimes = new long[lineCount];
        if (staticGrid) {
            for (int i = 0; i < lineCount; i++) startTimes[i] = i * STATIC_LINE_MS;
        } else {
            resolveStartTimes(rawStarts, startTimes);
        }

        for (int i = 0; i < lineCount; i++) {
            JsonObject lineObj = linesArr.get(i).getAsJsonObject();
            long startMs = startTimes[i];
            String words = lineObj.has("words") && !lineObj.get("words").isJsonNull()
                    ? lineObj.get("words").getAsString() : "";

            long endMs;
            if (staticGrid) {
                endMs = startMs + STATIC_LINE_MS;
            } else {
                endMs = parseLongSafe(lineObj, "endTimeMs");
                if (endMs <= startMs) {
                    // Estimate endMs using next line start or default duration
                    if (i < lineCount - 1) {
                        long nextStart = startTimes[i + 1];
                        endMs = nextStart > startMs ? nextStart : startMs + STATIC_LINE_MS;
                    } else {
                        endMs = startMs + STATIC_LINE_MS;
                    }
                }
            }

            LyricsData.LyricsLine line = new LyricsData.LyricsLine(startMs, endMs, words.trim());

            if (lineObj.has("syllables") && lineObj.get("syllables").isJsonArray()) {
                JsonArray sylArr = lineObj.getAsJsonArray("syllables");
                if (sylArr != null && sylArr.size() > 0) {
                    for (int s = 0; s < sylArr.size(); s++) {
                        JsonObject sylObj = sylArr.get(s).getAsJsonObject();
                        long sStart = Math.max(0, parseLongSafe(sylObj, "startTimeMs"));
                        long sEnd = parseLongSafe(sylObj, "endTimeMs");
                        String sText = sylObj.has("text") && !sylObj.get("text").isJsonNull()
                                ? sylObj.get("text").getAsString() : "";
                        if (sEnd <= sStart) {
                            if (s < sylArr.size() - 1) {
                                long nextSStart = parseLongSafe(sylArr.get(s + 1).getAsJsonObject(), "startTimeMs");
                                sEnd = nextSStart > sStart ? nextSStart : sStart + 300;
                            } else {
                                sEnd = endMs;
                            }
                        }
                        line.words.add(new LyricsData.Word(sStart, sEnd, sText));
                    }
                }
            }

            data.lines.add(line);
        }

        data.type = hasSyllables ? LyricsData.Type.SYLLABLE
                : staticGrid ? LyricsData.Type.STATIC : LyricsData.Type.LINE;
        AppLog.i(TAG, "Parsed color-lyrics lines=" + data.lines.size() + " type=" + data.type);
        return data;
    }

    /** True when the payload's own syncType declares it unsynced. */
    private static boolean isUnsyncedSyncType(String syncType) {
        return syncType != null && syncType.toUpperCase(Locale.ROOT).contains("UNSYNCED");
    }

    /**
     * Fill a missing line start from its timed neighbours instead of pinning it to 0 ms. A run of
     * untimed lines is spread evenly across the gap its neighbours leave; a trailing run advances
     * from the last timed line by the document's median line length. A genuine 0 is a real time
     * and is kept as 0.
     */
    private static void resolveStartTimes(long[] rawStarts, long[] startTimes) {
        long median = medianGap(rawStarts);
        int total = rawStarts.length;
        int i = 0;
        while (i < total) {
            if (rawStarts[i] >= 0) {
                startTimes[i] = rawStarts[i];
                i++;
                continue;
            }
            int runStart = i;
            while (i < total && rawStarts[i] < 0) i++;
            int runLength = i - runStart;
            boolean hasBefore = runStart > 0;
            long before = hasBefore ? startTimes[runStart - 1] : 0L;
            boolean bounded = i < total && rawStarts[i] > before;
            long span = bounded ? rawStarts[i] - before : 0L;
            for (int k = 0; k < runLength; k++) {
                long at = bounded
                        ? before + span * (k + 1) / (runLength + 1)
                        : before + median * (k + 1);
                startTimes[runStart + k] = Math.max(0, at);
            }
        }
    }

    /** Median positive gap between consecutive timed lines, or the static line length. */
    private static long medianGap(long[] rawStarts) {
        List<Long> gaps = new ArrayList<>();
        boolean hasPrevious = false;
        long previous = 0L;
        for (long start : rawStarts) {
            if (start < 0) continue;
            if (hasPrevious && start > previous) gaps.add(start - previous);
            previous = start;
            hasPrevious = true;
        }
        if (gaps.isEmpty()) return STATIC_LINE_MS;
        Collections.sort(gaps);
        return gaps.get(gaps.size() / 2);
    }

    /**
     * Returns the timestamp, or -1 when the key is absent/unreadable, so a genuine 0 ms is never
     * mistaken for a missing value.
     */
    private long parseLongSafe(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return -1;
        try {
            return obj.get(key).getAsLong();
        } catch (Exception e) {
            try {
                return Long.parseLong(obj.get(key).getAsString());
            } catch (Exception ignored) {
                return -1;
            }
        }
    }
}
