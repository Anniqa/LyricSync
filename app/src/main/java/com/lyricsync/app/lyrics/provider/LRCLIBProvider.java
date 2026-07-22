package com.lyricsync.app.lyrics.provider;

import android.net.Uri;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class LRCLIBProvider implements LyricsProvider {
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

            if (response.body() == null) throw new IOException("Empty response body");
            String body = response.body().string();
            return parseResponse(body);
        }
    }

    private String buildUrl(TrackInfo track) {
        String cleanTitle = cleanMetadata(track.title);
        String cleanArtist = cleanMetadata(track.artist);
        StringBuilder sb = new StringBuilder(API_URL);
        sb.append("?track_name=").append(Uri.encode(cleanTitle));
        sb.append("&artist_name=").append(Uri.encode(cleanArtist));
        if (track.album != null) {
            sb.append("&album_name=").append(Uri.encode(track.album));
        }
        if (track.duration > 0) {
            sb.append("&duration=").append(track.duration / 1000);
        }
        return sb.toString();
    }

    private String cleanMetadata(String text) {
        if (text == null) return "";
        return text
                .replaceAll("(?i)\\s*\\[[^\\]]*]", "")
                .replaceAll("(?i)\\s*\\([^)]*(official|video|audio|lyric|lyrics|visualizer|mv|music video|hd|4k|performance|topic)[^)]*\\)", "")
                .replaceAll("(?i)\\s*-\\s*(official|video|audio|lyric|lyrics|mv|music video|hd|4k|topic).*$", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private LyricsData searchByQuery(TrackInfo track) throws IOException {
        String cleanTitle = cleanMetadata(track.title);
        String cleanArtist = cleanMetadata(track.artist);
        String url = "https://lrclib.net/api/search?track_name="
                + Uri.encode(cleanTitle) + "&artist_name=" + Uri.encode(cleanArtist);

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "LyricSync/1.0")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }

            if (response.body() == null) throw new IOException("Empty response body");
            String body = response.body().string();
            JsonArray results = JsonParser.parseString(body).getAsJsonArray();

            if (results == null || results.isEmpty()) {
                throw new IOException("No results found");
            }

            JsonObject best = results.get(0).getAsJsonObject();
            double bestScore = -1;
            for (JsonElement el : results) {
                JsonObject obj = el.getAsJsonObject();
                String rName = safeStr(obj, "trackName", "name");
                String rArtist = safeStr(obj, "artistName");
                long rDuration = obj.has("duration") && !obj.get("duration").isJsonNull()
                        ? (long) obj.get("duration").getAsDouble() : 0;
                double score = scoreResult(cleanTitle, cleanArtist, track.duration,
                        rName, rArtist, rDuration);
                if (score > bestScore) {
                    bestScore = score;
                    best = obj;
                }
            }

            return parseResponse(best.toString());
        }
    }

    private double scoreResult(String inTitle, String inArtist, long inDuration,
                                String candTitle, String candArtist, long candDuration) {
        double ts = tokenOverlap(inTitle, candTitle);
        double as = tokenOverlap(inArtist, candArtist);
        double ds = 0.5d;
        if (inDuration > 0 && candDuration > 0) {
            long diff = Math.abs(inDuration - candDuration);
            ds = Math.max(0d, 1d - (diff / 12000d));
        }
        return (ts * 0.50d) + (as * 0.35d) + (ds * 0.15d);
    }

    private double tokenOverlap(String a, String b) {
        if (a == null || b == null) return 0d;
        Set<String> la = tokens(a);
        Set<String> ra = tokens(b);
        if (la.isEmpty() || ra.isEmpty()) return 0d;
        int hits = 0;
        for (String t : la) { if (ra.contains(t)) hits++; }
        double recall = hits / (double) la.size();
        double precision = hits / (double) ra.size();
        return (recall * 0.65d) + (precision * 0.35d);
    }

    private Set<String> tokens(String text) {
        Set<String> set = new HashSet<>();
        String norm = text.toLowerCase(java.util.Locale.US)
                .replaceAll("[^a-z0-9]+", " ").trim();
        if (norm.isEmpty()) return set;
        for (String t : norm.split(" ")) { if (t.length() > 1) set.add(t); }
        return set;
    }

    private static String safeStr(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                try { return obj.get(key).getAsString(); } catch (Exception ignored) {}
            }
        }
        return "";
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
            lyrics.lines.add(lyricsLine);
        }

        for (int i = 0; i < lyrics.lines.size() - 1; i++) {
            lyrics.lines.get(i).endTime = lyrics.lines.get(i + 1).startTime;
        }
        if (!lyrics.isEmpty()) {
            lyrics.lines.get(lyrics.lines.size() - 1).endTime =
                    lyrics.lines.get(lyrics.lines.size() - 1).startTime + 5000;
        }

        for (LyricsData.LyricsLine ll : lyrics.lines) {
            splitLineIntoWords(ll);
        }

        LyricsData.insertInterludes(lyrics, 3000);
    }

    private void splitLineIntoWords(LyricsData.LyricsLine line) {
        if (line.text == null || line.text.isEmpty()) return;

        String[] words = line.text.split(" ");
        if (words.length <= 1) {
            if (words.length == 1 && !words[0].isEmpty()) {
                line.words.add(new LyricsData.Word(line.startTime, line.endTime, words[0]));
            }
            return;
        }

        long duration = line.endTime - line.startTime;
        long wordDuration = duration / words.length;

        for (int i = 0; i < words.length; i++) {
            long wordStart = line.startTime + (i * wordDuration);
            long wordEnd = (i == words.length - 1) ? line.endTime : wordStart + wordDuration;
            String sep = (i < words.length - 1) ? " " : "";
            line.words.add(new LyricsData.Word(wordStart, wordEnd, words[i] + sep));
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

            if (secParts.length > 1) {
                if (secParts[1].length() == 1) ms *= 100;
                else if (secParts[1].length() == 2) ms *= 10;
                else if (secParts[1].length() > 3) ms = (int) Math.round(ms / Math.pow(10, secParts[1].length() - 3));
            }

            return min * 60000L + sec * 1000L + ms;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
