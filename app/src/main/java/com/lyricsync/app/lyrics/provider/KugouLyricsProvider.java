package com.lyricsync.app.lyrics.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.SyllableLyricsParser;
import com.lyricsync.app.lyrics.util.TrackMatcher;
import com.lyricsync.app.lyrics.util.TrackQuery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;

/**
 * Kugou: the widest word-by-word (KRC) catalogue, strong on Asian and Indonesian music.
 * Search "artist - title" for each spelling; take the best-scoring candidate (KRC first).
 */
public class KugouLyricsProvider implements LyricsProvider {

    private static final String SEARCH_URL = "https://krcs.kugou.com/search";
    private static final String DOWNLOAD_URL = "https://lyrics.kugou.com/download";

    private final OkHttpClient client;

    public KugouLyricsProvider() {
        this(Http.CLIENT);
    }

    public KugouLyricsProvider(OkHttpClient client) {
        this.client = client != null ? client : Http.CLIENT;
    }

    @Override
    public String getName() {
        return "Kugou";
    }

    @Override
    public LyricsData fetchLyrics(TrackQuery q) throws Exception {
        if (q == null || q.isEmpty()) return null;
        List<Candidate> ranked = new ArrayList<>();
        for (String keyword : keywords(q)) {
            ranked.addAll(rankCandidates(search(keyword, q.fromVideo ? 0 : q.durationMs), q));
            ranked.sort((a, b) -> Double.compare(b.rank(), a.rank()));
            if (!ranked.isEmpty() && ranked.get(0).krc && ranked.get(0).score >= 0.85) break; // good enough
        }
        // Kugou files remixes/live takes under the plain song name; only the KRC [ti:] tag
        // tells them apart, so try a few and keep the first that is the same version.
        LyricsData fallback = null;
        Set<String> tried = new HashSet<>();
        for (Candidate c : ranked) {
            if (tried.size() >= 3) break;
            if (!tried.add(c.id)) continue;
            Download d = download(c);
            if (d == null || d.lyrics == null || d.lyrics.isEmpty()) continue;
            d.lyrics.provider = getName();
            if (!TrackMatcher.isOtherVersion(q.title, d.title)) return d.lyrics;
            if (fallback == null) fallback = d.lyrics;
        }
        return fallback;
    }

    /** "Artist - Title" (Kugou's catalogue convention), then the bare title, for each spelling. */
    static Set<String> keywords(TrackQuery q) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : q.titles()) {
            if (q.hasArtist()) out.add(q.artist + " - " + t);
            out.add(t);
        }
        return out;
    }

    static final class Candidate {
        final String id, accessKey, song, singer;
        final boolean krc;
        final double score;

        Candidate(String id, String accessKey, String song, String singer, boolean krc, double score) {
            this.id = id;
            this.accessKey = accessKey;
            this.song = song;
            this.singer = singer;
            this.krc = krc;
            this.score = score;
        }

        /** Word-by-word beats line-synced unless the line candidate matches clearly better. */
        double rank() {
            return score + (krc ? 0.1 : 0);
        }
    }

    /** Package-private: the usable candidates of a captured /search response, best first. */
    static List<Candidate> rankCandidates(JsonArray candidates, TrackQuery q) {
        List<Candidate> out = new ArrayList<>();
        if (candidates == null) return out;
        for (JsonElement el : candidates) {
            JsonObject o = Http.obj(el);
            if (o == null) continue;
            String id = Http.str(o, "id"), key = Http.str(o, "accesskey");
            if (id.isEmpty() || key.isEmpty()) continue;
            String song = Http.str(o, "song"), singer = Http.str(o, "singer");
            double score = q.score(song, singer, Http.num(o, "duration"));
            if (score < TrackMatcher.MIN_SCORE) continue;
            out.add(new Candidate(id, key, song, singer, Http.num(o, "krctype") == 2, score));
        }
        out.sort((a, b) -> Double.compare(b.rank(), a.rank()));
        return out;
    }

    private JsonArray search(String keyword, long durationMs) {
        String url = SEARCH_URL + "?ver=1&man=yes&client=mobi&hash=&keyword=" + Http.enc(keyword)
                + "&duration=" + (durationMs > 0 ? durationMs : "");
        try {
            JsonObject root = Http.obj(Http.getJson(client, url, "User-Agent", Http.BROWSER_UA));
            return root != null && root.has("candidates") && root.get("candidates").isJsonArray()
                    ? root.getAsJsonArray("candidates") : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static final class Download {
        final String title;
        final LyricsData lyrics;

        Download(String title, LyricsData lyrics) {
            this.title = title;
            this.lyrics = lyrics;
        }
    }

    private static final Pattern TI_TAG = Pattern.compile("(?m)^\\[ti:([^\\]]*)]");

    private Download download(Candidate c) {
        try {
            return downloadOrThrow(c);
        } catch (Exception e) {
            return null; // one broken file must not hide the next candidate
        }
    }

    private Download downloadOrThrow(Candidate c) throws Exception {
        String url = DOWNLOAD_URL + "?ver=1&client=pc&charset=utf8&id=" + Http.enc(c.id)
                + "&accesskey=" + Http.enc(c.accessKey) + "&fmt=" + (c.krc ? "krc" : "lrc");
        JsonObject root = Http.obj(Http.getJson(client, url, "User-Agent", Http.BROWSER_UA));
        String content = Http.str(root, "content");
        if (content.trim().isEmpty()) return null;
        byte[] raw = Base64.getMimeDecoder().decode(content);
        String text = c.krc
                ? new String(SyllableLyricsParser.decryptKrc(raw), StandardCharsets.UTF_8)
                : new String(raw, StandardCharsets.UTF_8);
        Matcher ti = TI_TAG.matcher(text);
        String title = ti.find() ? ti.group(1).trim() : c.song;
        return new Download(title, c.krc ? SyllableLyricsParser.parseKrc(text) : SyllableLyricsParser.parseLrc(text));
    }
}
