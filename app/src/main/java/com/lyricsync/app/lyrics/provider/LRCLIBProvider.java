package com.lyricsync.app.lyrics.provider;

import android.net.Uri;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class LRCLIBProvider implements LyricsProvider {
    private static final String TAG = "LRCLIB";
    private static final String API_URL = "https://lrclib.net/api/get";

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    @Override
    public String getName() {
        return "LRCLIB";
    }

    @Override
    public LyricsData fetchLyrics(TrackInfo track) throws Exception {
        String url = buildUrl(track);

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "LyricSync/1.0")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                if (response.code() == 404) {
                    return searchByQuery(track);
                }
                throw new IOException("HTTP " + response.code());
            }

            String body = response.body().string();
            return parseResponse(body);
        }
    }

    private String buildUrl(TrackInfo track) {
        StringBuilder sb = new StringBuilder(API_URL);
        sb.append("?track_name=").append(Uri.encode(track.title));
        sb.append("&artist_name=").append(Uri.encode(track.artist));
        if (track.album != null) {
            sb.append("&album_name=").append(Uri.encode(track.album));
        }
        if (track.duration > 0) {
            sb.append("&duration=").append(track.duration / 1000);
        }
        return sb.toString();
    }

    private LyricsData searchByQuery(TrackInfo track) throws IOException {
        String url = "https://lrclib.net/api/search?track_name="
                + Uri.encode(track.title) + "&artist_name=" + Uri.encode(track.artist);

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "LyricSync/1.0")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }

            String body = response.body().string();
            JsonArray results = JsonParser.parseString(body).getAsJsonArray();

            if (results == null || results.isEmpty()) {
                throw new IOException("No results found");
            }

            return parseResponse(results.get(0).getAsJsonObject().toString());
        }
    }

    private LyricsData parseResponse(String body) throws IOException {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();

        LyricsData lyrics = new LyricsData();
        lyrics.provider = getName();

        String syncedLyrics = root.has("syncedLyrics") && !root.get("syncedLyrics").isJsonNull()
                ? root.get("syncedLyrics").getAsString() : null;

        String plainLyrics = root.has("plainLyrics") && !root.get("plainLyrics").isJsonNull()
                ? root.get("plainLyrics").getAsString() : null;

        if (syncedLyrics != null && !syncedLyrics.isEmpty()) {
            lyrics.type = LyricsData.Type.LINE;
            parseSyncedLyrics(syncedLyrics, lyrics);
        } else if (plainLyrics != null && !plainLyrics.isEmpty()) {
            lyrics.type = LyricsData.Type.STATIC;
            parsePlainLyrics(plainLyrics, lyrics);
        } else {
            throw new IOException("No lyrics content");
        }

        return lyrics;
    }

    private void parseSyncedLyrics(String raw, LyricsData lyrics) {
        for (String line : raw.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;

            int bracketEnd = line.indexOf(']');
            if (bracketEnd < 0 || line.charAt(0) != '[') continue;

            String timePart = line.substring(1, bracketEnd);
            String text = line.substring(bracketEnd + 1).trim();

            if (text.isEmpty()) continue;

            long start = parseTime(timePart);
            if (start < 0) continue;

            LyricsData.LyricsLine lyricsLine = new LyricsData.LyricsLine(start, start + 5000, text);
            lyricsLine.words.add(new LyricsData.Word(start, start + 5000, text));
            lyrics.lines.add(lyricsLine);
        }

        for (int i = 0; i < lyrics.lines.size() - 1; i++) {
            lyrics.lines.get(i).endTime = lyrics.lines.get(i + 1).startTime;
        }
        if (!lyrics.isEmpty()) {
            lyrics.lines.get(lyrics.lines.size() - 1).endTime =
                    lyrics.lines.get(lyrics.lines.size() - 1).startTime + 5000;
        }
    }

    private void parsePlainLyrics(String raw, LyricsData lyrics) {
        long time = 0;
        for (String line : raw.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;

            LyricsData.LyricsLine lyricsLine = new LyricsData.LyricsLine(time, time + 5000, line);
            lyrics.lines.add(lyricsLine);
            time += 5000;
        }
    }

    private long parseTime(String time) {
        try {
            String[] parts = time.split(":");
            if (parts.length != 2) return -1;

            int min = Integer.parseInt(parts[0]);
            String[] secParts = parts[1].split("\\.");
            int sec = Integer.parseInt(secParts[0]);
            int ms = secParts.length > 1 ? Integer.parseInt(secParts[1]) : 0;

            if (String.valueOf(ms).length() == 2) ms *= 10;

            return min * 60000L + sec * 1000L + ms;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
