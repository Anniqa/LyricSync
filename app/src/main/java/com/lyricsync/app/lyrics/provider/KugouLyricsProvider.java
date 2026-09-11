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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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

    private static final String SEARCH_URL = "https://krcs.kugou.com/search";
    private static final String DOWNLOAD_URL = "https://lyrics.kugou.com/download";

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
        if (track == null || track.title == null || track.title.trim().isEmpty()) return null;

        TrackMetadataSanitizer.SanitizedMetadata sanitized = TrackMetadataSanitizer.sanitize(track);
        String cleanTitle = sanitized.cleanTitle.isEmpty() ? track.title : sanitized.cleanTitle;
        String cleanArtist = sanitized.cleanArtist.isEmpty() ? track.artist : sanitized.cleanArtist;

        // Build high-yield permutations:
        // 1. "Artist - Title" (Kugou's primary catalog convention)
        // 2. "Title - Artist"
        // 3. "Title Artist"
        // 4. Aliases (Kanji / native script)
        List<String> queryList = buildQueryPermutations(cleanTitle, cleanArtist, sanitized.titleAliases);

        Candidate best = null;
        Set<String> seen = new HashSet<>();
        for (String query : queryList) {
            if (query.isEmpty() || !seen.add(query.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Candidate candidate = searchCandidate(query, cleanTitle, sanitized.titleAliases, cleanArtist, track.duration);
            if (candidate != null) {
                if (candidate.krctype == 2) {
                    best = candidate;
                    break; // Found preferred KRC syllable candidate
                }
                if (best == null || candidate.score > best.score) {
                    best = candidate;
                }
            }
        }

        if (best == null) {
            return null;
        }

        // 3. Download lyric content
        String fmt = best.krctype == 2 ? "krc" : "lrc";
        String rawBase64 = downloadLyricContent(best.id, best.accesskey, fmt);
        if (rawBase64 == null || rawBase64.trim().isEmpty()) {
            return null;
        }

        // 4. Decode base64 and parse KRC or LRC
        byte[] rawBytes;
        try {
            rawBytes = Base64.getMimeDecoder().decode(rawBase64);
        } catch (IllegalArgumentException e) {
            return null;
        }

        if (best.krctype == 2) {
            byte[] decryptedBytes = SyllableLyricsParser.decryptKrc(rawBytes);
            String krcText = new String(decryptedBytes, StandardCharsets.UTF_8);

            // 5. Parse KRC format
            LyricsData lyrics = SyllableLyricsParser.parseKrc(krcText);
            if (lyrics != null && !lyrics.isEmpty()) {
                lyrics.provider = getName();
                return lyrics;
            }
        } else {
            String rawText = new String(rawBytes, StandardCharsets.UTF_8);
            LyricsData lyrics = SyllableLyricsParser.parse(rawText, "lrc");
            if (lyrics != null && !lyrics.isEmpty()) {
                lyrics.provider = getName();
                return lyrics;
            }
        }

        return null;
    }

    static List<String> buildQueryPermutations(String cleanTitle, String cleanArtist, List<String> titleAliases) {
        List<String> queryList = new ArrayList<>();
        if (cleanArtist != null && !cleanArtist.isEmpty()) {
            queryList.add((cleanArtist + " - " + cleanTitle).trim());
            queryList.add((cleanTitle + " - " + cleanArtist).trim());
        }
        queryList.add((cleanTitle + " " + (cleanArtist != null ? cleanArtist : "")).trim());
        if (titleAliases != null) {
            for (String alias : titleAliases) {
                if (!alias.equalsIgnoreCase(cleanTitle)) {
                    if (cleanArtist != null && !cleanArtist.isEmpty()) {
                        queryList.add((cleanArtist + " - " + alias).trim());
                    }
                    queryList.add((alias + " " + (cleanArtist != null ? cleanArtist : "")).trim());
                }
            }
        }
        return queryList;
    }

    static class Candidate {
        String id;
        String accesskey;
        String song;
        String singer;
        long duration;
        int krctype;
        double score;
    }

    Candidate searchCandidate(String query, String title, String artist, long duration) throws IOException {
        return searchCandidate(query, title, Collections.emptyList(), artist, duration);
    }

    Candidate searchCandidate(String query, String title, List<String> aliases, String artist, long duration) throws IOException {
        String encodedKeyword = URLEncoder.encode(query, "UTF-8");
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
            Candidate bestKrc = null;
            double highestKrcScore = -1.0;
            Candidate bestNonKrc = null;
            double highestNonKrcScore = -1.0;

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

                double score = scoreCandidate(title, aliases, artist, duration, c.song, c.singer, c.duration);
                c.score = score;

                if (score >= 0.30) {
                    if (c.krctype == 2) {
                        if (score > highestKrcScore) {
                            highestKrcScore = score;
                            bestKrc = c;
                        }
                    } else {
                        if (score > highestNonKrcScore) {
                            highestNonKrcScore = score;
                            bestNonKrc = c;
                        }
                    }
                }
            }

            return bestKrc != null ? bestKrc : bestNonKrc;
        } catch (Exception e) {
            return null;
        }
    }

    private String downloadLyricContent(String id, String accesskey) throws IOException {
        return downloadLyricContent(id, accesskey, "krc");
    }

    private String downloadLyricContent(String id, String accesskey, String fmt) throws IOException {
        String format = (fmt != null && !fmt.isEmpty()) ? fmt : "krc";
        String url = DOWNLOAD_URL + "?ver=1&client=pc"
                + "&id=" + URLEncoder.encode(id, "UTF-8")
                + "&accesskey=" + URLEncoder.encode(accesskey, "UTF-8")
                + "&fmt=" + URLEncoder.encode(format, "UTF-8") + "&charset=utf8";
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

    double scoreCandidate(String inTitle, String inArtist, long inDuration,
                          String candSong, String candSinger, long candDuration) {
        return scoreCandidate(inTitle, null, inArtist, inDuration, candSong, candSinger, candDuration);
    }

    double scoreCandidate(String inTitle, List<String> aliases, String inArtist, long inDuration,
                          String candSong, String candSinger, long candDuration) {
        double ts = tokenOverlap(inTitle, candSong);
        if (aliases != null) {
            for (String alias : aliases) {
                ts = Math.max(ts, tokenOverlap(alias, candSong));
            }
        }
        double as = tokenOverlap(inArtist, candSinger);
        double ds = 0.5d;
        if (inDuration > 0 && candDuration > 0) {
            long diff = Math.abs(inDuration - candDuration);
            ds = Math.max(0d, 1d - (diff / 15000d));
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
