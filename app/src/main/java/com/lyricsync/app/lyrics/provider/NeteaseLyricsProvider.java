package com.lyricsync.app.lyrics.provider;

import android.net.Uri;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class NeteaseLyricsProvider implements LyricsProvider {
    private static final String TAG = "NeteaseLyrics";
    private static final String SEARCH_URL = "https://music.163.com/api/search/get";
    private static final String LYRIC_URL = "https://music.163.com/api/song/lyric";

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    @Override
    public String getName() {
        return "Netease";
    }

    @Override
    public LyricsData fetchLyrics(TrackInfo track) throws Exception {
        long songId = searchSong(track);
        if (songId <= 0) {
            throw new IOException("Song not found on Netease");
        }

        return fetchLyricById(songId);
    }

    private long searchSong(TrackInfo track) throws IOException {
        String query = track.title + " " + track.artist;
        String url = SEARCH_URL + "?s=" + Uri.encode(query) + "&type=1&limit=5&offset=0";

        Request request = new Request.Builder()
                .url(url)
                .header("Referer", "https://music.163.com")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }

            String body = response.body().string();
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject result = root.getAsJsonObject("result");

            if (result == null || !result.has("songs")) return -1;

            JsonArray songs = result.getAsJsonArray("songs");
            if (songs.isEmpty()) return -1;

            return songs.get(0).getAsJsonObject().get("id").getAsLong();
        }
    }

    private LyricsData fetchLyricById(long songId) throws IOException {
        String url = LYRIC_URL + "?id=" + songId + "&lv=1&tv=1&rv=1";

        Request request = new Request.Builder()
                .url(url)
                .header("Referer", "https://music.163.com")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }

            String body = response.body().string();
            return parseLyricResponse(body);
        }
    }

    private LyricsData parseLyricResponse(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        LyricsData lyrics = new LyricsData();
        lyrics.provider = getName();

        // Try YRC (syllable-synced) first
        if (root.has("yrc") && !root.get("yrc").isJsonNull()) {
            String yrc = root.getAsJsonObject("yrc").get("lyric").getAsString();
            lyrics.type = LyricsData.Type.SYLLABLE;
            parseYRC(yrc, lyrics);
            if (!lyrics.isEmpty()) {
                LyricsData.insertInterludes(lyrics, 3000);
                return lyrics;
            }
        }

        // Try LRC (line-synced)
        if (root.has("lrc") && !root.get("lrc").isJsonNull()) {
            String lrc = root.getAsJsonObject("lrc").get("lyric").getAsString();
            lyrics.type = LyricsData.Type.LINE;
            parseLRC(lrc, lyrics);
            if (!lyrics.isEmpty()) return lyrics;
        }

        // Fallback: static from tlyric
        if (root.has("tlyric") && !root.get("tlyric").isJsonNull()) {
            String tlyric = root.getAsJsonObject("tlyric").get("lyric").getAsString();
            lyrics.type = LyricsData.Type.STATIC;
            parseLRC(tlyric, lyrics);
        }

        return lyrics;
    }

    private static final Pattern YRC_LINE_HEADER = Pattern.compile("\\[(\\d+),(\\d+)\\]");
    private static final Pattern YRC_SYLLABLE = Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^()]*)");

    private void parseYRC(String raw, LyricsData lyrics) {
        if (raw == null || raw.isEmpty()) return;

        for (String fullLine : raw.split("\n")) {
            String line = fullLine.trim();
            if (line.isEmpty() || line.startsWith("{")) continue;

            Matcher headerMatcher = YRC_LINE_HEADER.matcher(line);
            if (!headerMatcher.find()) continue;

            long lineStart = Long.parseLong(headerMatcher.group(1));
            long lineDuration = Long.parseLong(headerMatcher.group(2));
            long lineEnd = lineStart + lineDuration;

            String contentPart = line.substring(headerMatcher.end());
            Matcher wordMatcher = YRC_SYLLABLE.matcher(contentPart);

            StringBuilder lineText = new StringBuilder();
            LyricsData.LyricsLine lyricsLine = new LyricsData.LyricsLine(lineStart, lineEnd, "");

            while (wordMatcher.find()) {
                long start = Long.parseLong(wordMatcher.group(1));
                long duration = Long.parseLong(wordMatcher.group(2));
                String text = wordMatcher.group(3);

                if (text == null || text.isEmpty()) continue;

                lineText.append(text);
                lyricsLine.words.add(new LyricsData.Word(start, start + duration, text));
            }

            if (lineText.length() > 0) {
                lyricsLine.text = lineText.toString().trim();
                lyrics.lines.add(lyricsLine);
            }
        }
    }

    private static final Pattern LRC_TIME = Pattern.compile("\\[(\\d{2}):(\\d{2})\\.?(\\d{0,3})\\](.*)");

    private void parseLRC(String raw, LyricsData lyrics) {
        if (raw == null || raw.isEmpty()) return;

        for (String fullLine : raw.split("\n")) {
            String line = fullLine.trim();
            if (line.isEmpty()) continue;

            Matcher matcher = LRC_TIME.matcher(line);
            if (!matcher.find()) continue;

            int min = Integer.parseInt(matcher.group(1));
            int sec = Integer.parseInt(matcher.group(2));
            String msStr = matcher.group(3);
            int ms = msStr.isEmpty() ? 0 : Integer.parseInt(msStr);
            if (msStr.length() == 2) ms *= 10;
            if (msStr.length() == 1) ms *= 100;

            long start = min * 60000L + sec * 1000L + ms;
            String text = matcher.group(4).trim();

            if (text.isEmpty()) continue;

            LyricsData.LyricsLine lyricsLine = new LyricsData.LyricsLine(start, start + 5000, text);
            lyrics.lines.add(lyricsLine);
        }

        // Set end times based on next line start, then split into words
        for (int i = 0; i < lyrics.lines.size() - 1; i++) {
            lyrics.lines.get(i).endTime = lyrics.lines.get(i + 1).startTime;
        }
        if (!lyrics.isEmpty()) {
            lyrics.lines.get(lyrics.lines.size() - 1).endTime =
                    lyrics.lines.get(lyrics.lines.size() - 1).startTime + 5000;
        }

        // Split each line into words with distributed timing
        for (LyricsData.LyricsLine ll : lyrics.lines) {
            splitLineIntoWords(ll);
        }

        // Insert interlude dots for gaps >= 4 seconds
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
}
