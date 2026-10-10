package com.lyricsync.app.lyrics.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class NeteaseLyricsProvider implements LyricsProvider {
    private static final String SEARCH_URL = "https://music.163.com/api/search/get";
    private static final String LYRIC_URL = "https://music.163.com/api/song/lyric";

    private final OkHttpClient client;

    public NeteaseLyricsProvider() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    public NeteaseLyricsProvider(OkHttpClient client) {
        this.client = client != null ? client : new OkHttpClient();
    }

    @Override
    public String getName() {
        return "Netease";
    }

    @Override
    public LyricsData fetchLyrics(TrackInfo track) throws Exception {
        if (track == null || track.title == null || track.title.trim().isEmpty()) return null;

        com.lyricsync.app.lyrics.util.TrackMetadataSanitizer.SanitizedMetadata sanitized =
                com.lyricsync.app.lyrics.util.TrackMetadataSanitizer.sanitize(track);
        String cleanTitle = !sanitized.cleanTitle.isEmpty() ? sanitized.cleanTitle : track.title;
        String cleanArtist = !sanitized.cleanArtist.isEmpty() ? sanitized.cleanArtist : track.artist;

        TrackInfo query = new TrackInfo();
        query.title = cleanTitle;
        query.artist = cleanArtist;
        query.duration = track.duration;

        List<Long> songIds = searchSongIds(cleanMetadata(cleanTitle) + " " + cleanMetadata(cleanArtist),
                cleanTitle, cleanArtist, track.duration);

        // Try aliases if no song IDs found
        if (songIds.isEmpty() && !sanitized.titleAliases.isEmpty()) {
            for (String alias : sanitized.titleAliases) {
                List<Long> aliasIds = searchSongIds(cleanMetadata(alias) + " " + cleanMetadata(cleanArtist),
                        alias, cleanArtist, track.duration);
                if (!aliasIds.isEmpty()) {
                    songIds = aliasIds;
                    break;
                }
            }
        }

        // Additional query permutation: try cleanTitle alone if combined query returned no songs
        if (songIds.isEmpty() && !cleanTitle.trim().isEmpty()) {
            List<Long> titleOnlyIds = searchSongIds(cleanMetadata(cleanTitle), cleanTitle, "", track.duration);
            if (!titleOnlyIds.isEmpty()) {
                songIds = titleOnlyIds;
            }
        }

        LyricsData bestLineLyrics = null;
        for (long id : songIds) {
            try {
                LyricsData lyrics = fetchLyricById(id);
                if (lyrics != null && !lyrics.isEmpty()) {
                    if (lyrics.type == LyricsData.Type.SYLLABLE) {
                        return lyrics; // Found preferred syllable lyrics!
                    }
                    if (bestLineLyrics == null && lyrics.isSynced()) {
                        bestLineLyrics = lyrics; // Keep first line-synced as fallback
                    }
                }
            } catch (Exception ignored) {}
        }

        if (bestLineLyrics != null) {
            return bestLineLyrics;
        }

        return null;
    }

    public LyricsData fetchLyricsByQuery(String queryString, long durationMs) throws Exception {
        if (queryString == null || queryString.trim().isEmpty()) return null;
        List<Long> songIds = searchSongIds(queryString.trim(), queryString.trim(), "", durationMs);
        LyricsData bestLineLyrics = null;
        for (long id : songIds) {
            try {
                LyricsData lyrics = fetchLyricById(id);
                if (lyrics != null && !lyrics.isEmpty()) {
                    if (lyrics.type == LyricsData.Type.SYLLABLE) {
                        return lyrics; // Return syllable lyrics immediately!
                    }
                    if (bestLineLyrics == null && lyrics.isSynced()) {
                        bestLineLyrics = lyrics;
                    }
                }
            } catch (Exception ignored) {}
        }
        return bestLineLyrics;
    }

    private static String urlEncode(String val) {
        if (val == null) return "";
        try {
            return java.net.URLEncoder.encode(val, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return val;
        }
    }

    private List<Long> searchSongIds(String searchQuery, String title, String artist, long durationMs) throws IOException {
        String url = SEARCH_URL + "?s=" + urlEncode(searchQuery) + "&type=1&limit=10&offset=0";

        Request request = new Request.Builder()
                .url(url)
                .header("Referer", "https://music.163.com")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build();

        List<Long> resultIds = new java.util.ArrayList<>();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }

            if (response.body() == null) throw new IOException("Empty response body");
            String body = response.body().string();
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject result = root.getAsJsonObject("result");

            if (result == null || !result.has("songs")) return resultIds;

            JsonArray songs = result.getAsJsonArray("songs");
            if (songs == null || songs.isEmpty()) return resultIds;

            List<ScoredSong> scored = new java.util.ArrayList<>();
            for (JsonElement el : songs) {
                JsonObject song = el.getAsJsonObject();
                String name = safeStr(song, "name");
                String artists = joinNeteaseArtists(song);
                long duration = safeLong(song, "duration");
                long id = safeLong(song, "id");
                if (id <= 0) continue;
                double score = scoreCandidate(title, artist, durationMs, name, artists, duration);
                if (score >= 0.45d) {
                    scored.add(new ScoredSong(id, score));
                }
            }

            java.util.Collections.sort(scored);
            for (ScoredSong s : scored) {
                resultIds.add(s.id);
            }
            return resultIds;
        }
    }

    private static class ScoredSong implements Comparable<ScoredSong> {
        final long id;
        final double score;

        ScoredSong(long id, double score) {
            this.id = id;
            this.score = score;
        }

        @Override
        public int compareTo(ScoredSong o) {
            return Double.compare(o.score, this.score); // descending
        }
    }

    private static long safeLong(JsonObject obj, String key) {
        try {
            if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return 0;
            return obj.get(key).getAsLong();
        } catch (Exception e) {
            return 0;
        }
    }

    private String joinNeteaseArtists(JsonObject song) {
        if (!song.has("artists")) return "";
        JsonArray arr = song.getAsJsonArray("artists");
        StringBuilder sb = new StringBuilder();
        for (JsonElement el : arr) {
            JsonObject a = el.getAsJsonObject();
            String name = safeStr(a, "name");
            if (name.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(name);
        }
        return sb.toString();
    }

    private double scoreCandidate(String inTitle, String inArtist, long inDuration,
                                   String candName, String candArtists, long candDuration) {
        if (inTitle == null || candName == null) return 0d;

        double ts = tokenOverlap(inTitle, candName);
        String normInTitle = inTitle.trim().toLowerCase(java.util.Locale.ROOT);
        String normCandName = candName.trim().toLowerCase(java.util.Locale.ROOT);
        if (normInTitle.equals(normCandName)) {
            ts = 1.0d;
        } else if (normInTitle.contains(normCandName) || normCandName.contains(normInTitle)) {
            ts = Math.max(ts, 0.75d);
        }

        // Gatekeeper 1: Title must have minimum token overlap. Never accept an unrelated song.
        if (ts < 0.25d) {
            return 0.0d;
        }

        double as = 0.5d;
        boolean hasInArtist = inArtist != null && !inArtist.trim().isEmpty();
        boolean hasCandArtist = candArtists != null && !candArtists.trim().isEmpty();
        if (hasInArtist && hasCandArtist) {
            as = tokenOverlap(inArtist, candArtists);
            String normInArtist = inArtist.trim().toLowerCase(java.util.Locale.ROOT);
            String normCandArtist = candArtists.trim().toLowerCase(java.util.Locale.ROOT);
            if (normInArtist.equals(normCandArtist)) {
                as = 1.0d;
            } else if (normInArtist.contains(normCandArtist) || normCandArtist.contains(normInArtist)) {
                as = Math.max(as, 0.80d);
            }
            // Gatekeeper 2: Artist must have minimum overlap when both are provided
            if (as < 0.25d) {
                return 0.0d;
            }
        }

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
        if (text == null) return set;
        String norm = text.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        if (norm.isEmpty()) return set;
        String[] words = norm.split("\\s+");
        for (String w : words) {
            if (w.length() > 1 || words.length <= 2) {
                set.add(w);
            }
            // For CJK characters (Japanese, Chinese, Korean), add individual characters and 2-grams
            for (int i = 0; i < w.length(); i++) {
                char c = w.charAt(i);
                if ((c >= 0x4e00 && c <= 0x9fff) || (c >= 0x3040 && c <= 0x30ff) || (c >= 0xac00 && c <= 0xd7af)) {
                    set.add(String.valueOf(c));
                    if (i + 1 < w.length()) {
                        set.add(w.substring(i, i + 2));
                    }
                }
            }
        }
        // Compact alphanumeric token for short acronyms/stylized names like n/a, die4u
        String compact = text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
        if (!compact.isEmpty()) {
            set.add(compact);
        }
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

    private static String cleanMetadata(String text) {
        if (text == null) return "";
        return text
                .replaceAll("(?i)\\s*\\[[^\\]]*(official|video|audio|lyric|lyrics|mv|hd|4k|remaster|remastered|deluxe|visualizer|live|performance|topic)[^\\]]*\\]", "")
                .replaceAll("(?i)\\s*\\([^)]*(official|video|audio|lyric|lyrics|visualizer|mv|music video|hd|4k|performance|topic)[^)]*\\)", "")
                .replaceAll("(?i)\\s*-\\s*(official|video|audio|lyric|lyrics|mv|music video|hd|4k|topic).*$", "")
                .replaceAll("(?i)\\s*\\b(full album|album|ep)\\b\\s*$", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private LyricsData fetchLyricById(long songId) throws IOException {
        String url = LYRIC_URL + "?id=" + songId + "&lv=1&tv=1&rv=1&yv=1";

        Request request = new Request.Builder()
                .url(url)
                .header("Referer", "https://music.163.com")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }

            if (response.body() == null) throw new IOException("Empty response body");
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
            String yrc = extractLyric(root.getAsJsonObject("yrc"));
            if (yrc != null) {
                lyrics.type = LyricsData.Type.SYLLABLE;
                parseYRC(yrc, lyrics);
                if (!lyrics.isEmpty()) {
                    LyricsData.insertInterludes(lyrics, 3000);
                    return lyrics;
                }
            }
        }

        // Try LRC (line-synced)
        if (root.has("lrc") && !root.get("lrc").isJsonNull()) {
            String lrc = extractLyric(root.getAsJsonObject("lrc"));
            if (lrc != null) {
                lyrics.type = LyricsData.Type.LINE;
                parseLRC(lrc, lyrics);
                if (!lyrics.isEmpty()) return lyrics;
            }
        }

        // Fallback: static from tlyric
        if (root.has("tlyric") && !root.get("tlyric").isJsonNull()) {
            String tlyric = extractLyric(root.getAsJsonObject("tlyric"));
            if (tlyric != null) {
                lyrics.type = LyricsData.Type.STATIC;
                parseLRC(tlyric, lyrics);
            }
        }

        return lyrics;
    }

    /** Safely reads the "lyric" string from a yrc/lrc/tlyric container object. */
    private static String extractLyric(JsonObject container) {
        if (container == null || !container.has("lyric") || container.get("lyric").isJsonNull()) {
            return null;
        }
        return container.get("lyric").getAsString();
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

        // Insert interlude dots for gaps >= 3 seconds
        LyricsData.insertInterludes(lyrics, 3000);
    }

    private void splitLineIntoWords(LyricsData.LyricsLine line) {
        if (line.text == null || line.text.isEmpty()) return;

        String[] words = line.text.split("\\s+");
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
