package com.lyricsync.app.lyrics.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.SyllableLyricsParser;
import com.lyricsync.app.lyrics.util.TrackMatcher;
import com.lyricsync.app.lyrics.util.TrackQuery;

import java.io.IOException;

import okhttp3.OkHttpClient;

/**
 * LRCLIB: open, community line-synced lyrics. Exact /get first (title, artist, duration),
 * then /search per spelling, then a free-text query. Synced lyrics beat plain text.
 */
public class LRCLIBProvider implements LyricsProvider {

    private static final String BASE = "https://lrclib.net/api";
    private static final String[] HEADERS = {
            "User-Agent", "LyricSync (https://github.com/Anniqa/LyricSync)"};

    private final OkHttpClient client;

    public LRCLIBProvider() {
        this(Http.CLIENT);
    }

    public LRCLIBProvider(OkHttpClient client) {
        this.client = client != null ? client : Http.CLIENT;
    }

    @Override
    public String getName() {
        return "LRCLIB";
    }

    @Override
    public LyricsData fetchLyrics(TrackQuery q) throws Exception {
        if (q == null || q.isEmpty()) return null;
        if (q.hasArtist() && q.durationMs > 0) {
            LyricsData l = parse(getObject(BASE + "/get?track_name=" + Http.enc(q.title)
                    + "&artist_name=" + Http.enc(q.artist) + "&duration=" + q.durationMs / 1000));
            if (l != null && l.isSynced()) return l;
        }
        JsonObject best = null;
        double bestScore = 0;
        for (String t : q.titles()) {
            String url = BASE + "/search?track_name=" + Http.enc(t)
                    + (q.hasArtist() ? "&artist_name=" + Http.enc(q.artist) : "");
            JsonObject c = pickBest(search(url), q);
            double s = c == null ? 0 : rank(c, q);
            if (s > bestScore) { best = c; bestScore = s; }
            if (best != null && hasSynced(best)) break;
        }
        if (best == null) {
            best = pickBest(search(BASE + "/search?q=" + Http.enc((q.title + " " + q.artist).trim())), q);
        }
        return parse(best);
    }

    /** Package-private: best matching entry with lyrics in a captured /search response. */
    static JsonObject pickBest(JsonArray results, TrackQuery q) {
        if (results == null) return null;
        JsonObject best = null;
        double bestScore = TrackMatcher.MIN_SCORE;
        for (JsonElement el : results) {
            JsonObject o = Http.obj(el);
            if (o == null || (!hasSynced(o) && Http.str(o, "plainLyrics").trim().isEmpty())) continue;
            double s = rank(o, q);
            if (s > bestScore) { best = o; bestScore = s; }
        }
        return best;
    }

    /** Package-private: synced → LINE, plain → STATIC, nothing → null. */
    static LyricsData parse(JsonObject o) {
        if (o == null) return null;
        LyricsData l = null;
        if (hasSynced(o)) l = SyllableLyricsParser.parseLrc(Http.str(o, "syncedLyrics"));
        if (l == null || l.isEmpty()) {
            String plain = Http.str(o, "plainLyrics");
            if (plain.trim().isEmpty()) return null;
            l = new LyricsData(LyricsData.Type.STATIC);
            long t = 0;
            for (String line : plain.split("\n")) {
                if (line.trim().isEmpty()) continue;
                l.lines.add(new LyricsData.LyricsLine(t, t + 5000, line.trim()));
                t += 5000;
            }
        }
        l.provider = "LRCLIB";
        return l.isEmpty() ? null : l;
    }

    private static double rank(JsonObject o, TrackQuery q) {
        double s = q.score(Http.str(o, "trackName"), Http.str(o, "artistName"), Http.num(o, "duration") * 1000);
        return s <= 0 ? 0 : s + (hasSynced(o) ? 0.2 : 0);
    }

    private static boolean hasSynced(JsonObject o) {
        return !Http.str(o, "syncedLyrics").trim().isEmpty();
    }

    private JsonObject getObject(String url) {
        try {
            return Http.obj(Http.getJson(client, url, HEADERS));
        } catch (IOException e) {
            return null; // 5xx/timeouts are common on LRCLIB; fall through to /search
        }
    }

    private JsonArray search(String url) {
        try {
            JsonElement e = Http.getJson(client, url, HEADERS);
            return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
        } catch (IOException e) {
            return null;
        }
    }
}
