package com.lyricsync.app.lyrics.provider;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.lyrics.auth.SpotifyAuthManager;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.util.AppLog;

import java.io.IOException;
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
            trackId = SpotifyTrackResolver.resolve(track.title, track.artist);
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
        String syncType = lyricsObj.has("syncType") && !lyricsObj.get("syncType").isJsonNull()
                ? lyricsObj.get("syncType").getAsString() : "LINE_SYNCED";

        JsonArray linesArr = lyricsObj.has("lines") && lyricsObj.get("lines").isJsonArray()
                ? lyricsObj.getAsJsonArray("lines") : null;

        if (linesArr == null || linesArr.isEmpty()) {
            throw new IOException("Empty lyrics lines");
        }

        LyricsData data = new LyricsData();
        data.provider = getName();

        boolean hasSyllables = false;

        for (int i = 0; i < linesArr.size(); i++) {
            JsonObject lineObj = linesArr.get(i).getAsJsonObject();
            long startMs = parseLongSafe(lineObj, "startTimeMs");
            long endMs = parseLongSafe(lineObj, "endTimeMs");
            String words = lineObj.has("words") && !lineObj.get("words").isJsonNull()
                    ? lineObj.get("words").getAsString() : "";

            if (endMs <= startMs) {
                // Estimate endMs using next line start or default duration
                if (i < linesArr.size() - 1) {
                    long nextStart = parseLongSafe(linesArr.get(i + 1).getAsJsonObject(), "startTimeMs");
                    endMs = nextStart > startMs ? nextStart : startMs + 3500;
                } else {
                    endMs = startMs + 3500;
                }
            }

            LyricsData.LyricsLine line = new LyricsData.LyricsLine(startMs, endMs, words.trim());

            if (lineObj.has("syllables") && lineObj.get("syllables").isJsonArray()) {
                JsonArray sylArr = lineObj.getAsJsonArray("syllables");
                if (sylArr != null && sylArr.size() > 0) {
                    hasSyllables = true;
                    for (int s = 0; s < sylArr.size(); s++) {
                        JsonObject sylObj = sylArr.get(s).getAsJsonObject();
                        long sStart = parseLongSafe(sylObj, "startTimeMs");
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

        data.type = hasSyllables ? LyricsData.Type.SYLLABLE : LyricsData.Type.LINE;
        AppLog.i(TAG, "Parsed color-lyrics lines=" + data.lines.size() + " type=" + data.type);
        return data;
    }

    private long parseLongSafe(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return 0;
        try {
            return obj.get(key).getAsLong();
        } catch (Exception e) {
            try {
                return Long.parseLong(obj.get(key).getAsString());
            } catch (Exception ignored) {
                return 0;
            }
        }
    }
}
