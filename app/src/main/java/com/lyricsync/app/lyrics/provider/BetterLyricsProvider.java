package com.lyricsync.app.lyrics.provider;

import com.google.gson.JsonObject;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.SyllableLyricsParser;
import com.lyricsync.app.lyrics.model.TtmlParser;
import com.lyricsync.app.lyrics.util.TrackMatcher;
import com.lyricsync.app.lyrics.util.TrackQuery;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;

/**
 * Better Lyrics (https://api.betterlyrics.org, free): Apple Music TTML with syllable
 * timing, background vocals and duets; QQ Music QRC (word-timed) when TTML has nothing.
 * Lookups are by title/artist/album/duration; cache misses without a key answer 401,
 * which is just "not found" here.
 */
public class BetterLyricsProvider implements LyricsProvider {

    static final String BASE = "https://api.betterlyrics.org";
    private static final Pattern QRC_TITLE = Pattern.compile("\\[ti:([^\\]]*)]");

    private final OkHttpClient client;

    public BetterLyricsProvider() {
        this(Http.CLIENT);
    }

    public BetterLyricsProvider(OkHttpClient client) {
        this.client = client != null ? client : Http.CLIENT;
    }

    @Override
    public String getName() {
        return "BetterLyrics";
    }

    @Override
    public LyricsData fetchLyrics(TrackQuery q) throws Exception {
        if (q == null || q.isEmpty() || !q.hasArtist()) return null;
        String params = params(q);
        LyricsData ttml = parseTtmlResponse(get(BASE + "/getLyrics?" + params));
        if (ttml != null && ttml.type == LyricsData.Type.SYLLABLE) return ttml;
        LyricsData qrc = parseQrcResponse(get(BASE + "/qq/getLyrics?" + params), q);
        if (qrc != null && (ttml == null || LyricsData.rankOf(qrc.type) > LyricsData.rankOf(ttml.type))) return qrc;
        return ttml;
    }

    /** s/a/al/d: the cache key includes album and duration, so send all four. */
    static String params(TrackQuery q) {
        StringBuilder sb = new StringBuilder("s=").append(Http.enc(q.title)).append("&a=").append(Http.enc(q.artist));
        if (!q.album.isEmpty()) sb.append("&al=").append(Http.enc(q.album));
        if (q.durationMs > 0 && !q.fromVideo) sb.append("&d=").append(Math.round(q.durationMs / 1000.0));
        return sb.toString();
    }

    /** {"ttml": "<tt …>"} → lyrics, or null. */
    static LyricsData parseTtmlResponse(JsonObject root) {
        String ttml = Http.str(root, "ttml");
        if (ttml.isEmpty()) ttml = Http.str(root, "lyrics");
        if (ttml.trim().isEmpty()) return null;
        LyricsData l = TtmlParser.parse(ttml);
        if (l.isEmpty()) return null;
        l.provider = "BetterLyrics";
        return l;
    }

    /** {"lyrics": "<QrcInfos …>"} → lyrics, or null; a QRC whose [ti:] names another song is dropped. */
    static LyricsData parseQrcResponse(JsonObject root, TrackQuery q) {
        String qrc = Http.str(root, "lyrics");
        if (qrc.trim().isEmpty()) return null;
        Matcher ti = QRC_TITLE.matcher(qrc);
        if (ti.find() && !ti.group(1).trim().isEmpty() && q != null
                && TrackMatcher.titleSimilarity(q.title, ti.group(1)) < 0.5 && !matchesAlias(q, ti.group(1))) {
            return null;
        }
        LyricsData l = SyllableLyricsParser.parseQrc(qrc);
        if (l.isEmpty()) return null;
        l.provider = "BetterLyrics QQ";
        return l;
    }

    private static boolean matchesAlias(TrackQuery q, String title) {
        for (String a : q.aliases) if (TrackMatcher.titleSimilarity(a, title) >= 0.5) return true;
        return false;
    }

    private JsonObject get(String url) {
        try {
            return Http.obj(Http.getJson(client, url, "User-Agent", "LyricSync (https://github.com/Anniqa/LyricSync)"));
        } catch (IOException e) {
            return null; // 401 = not cached, 429/5xx = busy: the next source answers
        }
    }
}
