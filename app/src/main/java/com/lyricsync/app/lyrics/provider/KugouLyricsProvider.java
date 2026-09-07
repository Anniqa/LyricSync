package com.lyricsync.app.lyrics.provider;

import java.net.URLEncoder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.SyllableLyricsParser;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.lyrics.util.TrackMetadataSanitizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Kugou Music Syllable (KRC) Lyrics Provider.
 * Searches Kugou API, selects best candidate with krctype=2 (syllable format),
 * downloads encrypted KRC, decrypts with XOR/zlib, and produces Type.SYLLABLE lyrics.
 */
public class KugouLyricsProvider implements LyricsProvider {

    private static final String SEARCH_URL = "http://krcs.kugou.com/search";
    private static final String DOWNLOAD_URL = "http://lyrics.kugou.com/download";

    private final OkHttpClient client;

    public KugouLyricsProvider() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    public KugouLyricsProvider(OkHttpClient client) {
        this.client = client != null ? client : new OkHttpClient();
    }

    @Override
    public String getName() {
        return "Kugou";
    }

    @Override
    public LyricsData fetchLyrics(TrackInfo track) throws Exception {
        if (track == null || !track.isValid()) return null;

        TrackMetadataSanitizer.SanitizedMetadata sanitized = TrackMetadataSanitizer.sanitize(track);
        String cleanTitle = sanitized.cleanTitle.isEmpty() ? track.title : sanitized.cleanTitle;
        String cleanArtist = sanitized.cleanArtist.isEmpty() ? track.artist : sanitized.cleanArtist;

        // 1. Search with primary clean title and artist
        Candidate best = searchCandidate(cleanTitle, cleanArtist, track.duration);

        // 2. If not found or low score, try aliases (e.g. English or original script alias)
        if (best == null && !sanitized.titleAliases.isEmpty()) {
            for (String alias : sanitized.titleAliases) {
                best = searchCandidate(alias, cleanArtist, track.duration);
                if (best != null) break;
            }
        }

        if (best == null) {
            return null;
        }

        // 3. Download lyric content
        String rawBase64 = downloadLyricContent(best.id, best.accesskey);
        if (rawBase64 == null || rawBase64.trim().isEmpty()) {
            return null;
        }

        // 4. Decode base64 and decrypt KRC
        byte[] rawKrc;
        try {
            rawKrc = Base64.getDecoder().decode(rawBase64.replaceAll("\\s+", ""));
        } catch (IllegalArgumentException e) {
            return null;
        }

        byte[] decryptedBytes = SyllableLyricsParser.decryptKrc(rawKrc);
        String krcText = new String(decryptedBytes, StandardCharsets.UTF_8);

        // 5. Parse KRC format
        LyricsData lyrics = SyllableLyricsParser.parseKrc(krcText);
        if (lyrics != null && !lyrics.isEmpty()) {
            lyrics.provider = getName();
            return lyrics;
        }

        return null;
    }

    private static class Candidate {
        String id;
        String accesskey;
        String song;
        String singer;
        long duration;
        int krctype;
        double score;
    }
    private Candidate searchCandidate(String title, String artist, long duration) throws IOException {
        String keyword = (title + " " + artist).trim();
        String encodedKeyword = "";
        try {
            encodedKeyword = URLEncoder.encode(keyword, "UTF-8");
        } catch (Exception ignored) {}
        String url = SEARCH_URL + "?ver=1&man=yes&client=mobi"
                + "&keyword=" + encodedKeyword
                + "&duration=" + (duration > 0 ? duration : "")
                + "&hash=";

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }

            String bodyStr = response.body().string();
            JsonObject root = JsonParser.parseString(bodyStr).getAsJsonObject();
            if (!root.has("candidates") || !root.get("candidates").isJsonArray()) {
                return null;
            }

            JsonArray candidates = root.getAsJsonArray("candidates");
            Candidate best = null;
            double highestScore = -1.0;

            for (JsonElement elem : candidates) {
                if (!elem.isJsonObject()) continue;
                JsonObject obj = elem.getAsJsonObject();

                Candidate c = new Candidate();
                c.id = safeStr(obj, "id");
                c.accesskey = safeStr(obj, "accesskey");
                c.song = safeStr(obj, "song");
                c.singer = safeStr(obj, "singer");
                c.duration = safeLong(obj, "duration");
                c.krctype = (int) safeLong(obj, "krctype");

                if (c.id.isEmpty() || c.accesskey.isEmpty()) continue;

                double score = scoreCandidate(title, artist, duration, c.song, c.singer, c.duration);
                // Heavily prefer krctype == 2 (KRC syllable format)
                if (c.krctype == 2) {
                    score += 0.25;
                }
                c.score = score;

                if (score > highestScore && score >= 0.30) {
                    highestScore = score;
                    best = c;
                }
            }

            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private String downloadLyricContent(String id, String accesskey) throws IOException {
        String encId = id;
        String encKey = accesskey;
        try {
            encId = URLEncoder.encode(id, "UTF-8");
            encKey = URLEncoder.encode(accesskey, "UTF-8");
        } catch (Exception ignored) {}
        String url = DOWNLOAD_URL + "?ver=1&client=pc"
                + "&id=" + encId
                + "&accesskey=" + encKey
                + "&fmt=krc&charset=utf8";
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }

            String bodyStr = response.body().string();
            JsonObject root = JsonParser.parseString(bodyStr).getAsJsonObject();
            return safeStr(root, "content");
        } catch (Exception e) {
            return null;
        }
    }

    private double scoreCandidate(String inTitle, String inArtist, long inDuration,
                                   String candSong, String candSinger, long candDuration) {
        double ts = tokenOverlap(inTitle, candSong);
        double as = tokenOverlap(inArtist, candSinger);
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
        for (String t : la) {
            if (ra.contains(t)) hits++;
        }
        double recall = hits / (double) la.size();
        double precision = hits / (double) ra.size();
        return (recall * 0.65d) + (precision * 0.35d);
    }

    private Set<String> tokens(String text) {
        Set<String> set = new HashSet<>();
        if (text == null) return set;

        String lower = text.toLowerCase(Locale.US);
        // Extract Latin / digit words
        String[] words = lower.split("[^a-z0-9\u4e00-\u9fa5\u3040-\u30ff\uac00-\ud7af]+");
        for (String w : words) {
            w = w.trim();
            if (w.length() > 1) {
                set.add(w);
            }
            // For CJK characters, add individual characters as tokens
            for (int i = 0; i < w.length(); i++) {
                char c = w.charAt(i);
                if ((c >= 0x4e00 && c <= 0x9fa5) || (c >= 0x3040 && c <= 0x30ff) || (c >= 0xac00 && c <= 0xd7af)) {
                    set.add(String.valueOf(c));
                }
            }
        }
        return set;
    }

    private static String safeStr(JsonObject obj, String... keys) {
        if (obj == null) return "";
        for (String key : keys) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                try {
                    return obj.get(key).getAsString();
                } catch (Exception ignored) {}
            }
        }
        return "";
    }

    private static long safeLong(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return 0;
        try {
            return obj.get(key).getAsLong();
        } catch (Exception e) {
            return 0;
        }
    }
}
