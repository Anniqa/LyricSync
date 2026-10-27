package com.lyricsync.app.lyrics.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.SyllableLyricsParser;
import com.lyricsync.app.lyrics.util.TrackMatcher;
import com.lyricsync.app.lyrics.util.TrackQuery;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import okhttp3.OkHttpClient;

/**
 * Netease Cloud Music: word-by-word (YRC) for many tracks, line-synced (LRC) for most.
 * Searches "title artist" for each spelling, then the title alone; fetches the top matches.
 */
public class NeteaseLyricsProvider implements LyricsProvider {

    private static final String SEARCH_URL = "https://music.163.com/api/search/get";
    private static final String LYRIC_URL = "https://music.163.com/api/song/lyric";
    private static final String[] HEADERS = {
            "Referer", "https://music.163.com", "User-Agent", Http.BROWSER_UA};
    private static final int MAX_FETCHES = 3;

    private final OkHttpClient client;

    public NeteaseLyricsProvider() {
        this(Http.CLIENT);
    }

    public NeteaseLyricsProvider(OkHttpClient client) {
        this.client = client != null ? client : Http.CLIENT;
    }

    @Override
    public String getName() {
        return "Netease";
    }

    @Override
    public LyricsData fetchLyrics(TrackQuery q) throws Exception {
        if (q == null || q.isEmpty()) return null;
        List<Long> ids = new ArrayList<>();
        for (String keyword : keywords(q)) {
            for (long id : rankSongs(search(keyword), q)) if (!ids.contains(id)) ids.add(id);
            if (!ids.isEmpty()) break;
        }
        LyricsData line = null;
        for (int i = 0; i < ids.size() && i < MAX_FETCHES; i++) {
            LyricsData l = fetchById(ids.get(i));
            if (l == null || l.isEmpty()) continue;
            if (l.type == LyricsData.Type.SYLLABLE) return l;
            if (line == null) line = l;
        }
        return line;
    }

    /** "title artist" for every spelling, then the bare title (the artist may be spelled differently). */
    static Set<String> keywords(TrackQuery q) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : q.titles()) out.add(q.hasArtist() ? t + " " + q.artist : t);
        out.add(q.title);
        return out;
    }

    /** Package-private: song ids of a captured search response that match, best first. */
    static List<Long> rankSongs(JsonObject root, TrackQuery q) {
        List<long[]> scored = new ArrayList<>(); // {id, score*1000}
        JsonObject result = root == null ? null : Http.obj(root.get("result"));
        JsonArray songs = result != null && result.has("songs") && result.get("songs").isJsonArray()
                ? result.getAsJsonArray("songs") : null;
        if (songs != null) {
            for (JsonElement el : songs) {
                JsonObject s = Http.obj(el);
                long id = Http.num(s, "id");
                if (id <= 0) continue;
                double score = q.score(Http.str(s, "name"), artists(s), Http.num(s, "duration"));
                if (score >= TrackMatcher.MIN_SCORE) scored.add(new long[]{id, Math.round(score * 1000)});
            }
        }
        scored.sort((a, b) -> Long.compare(b[1], a[1]));
        List<Long> ids = new ArrayList<>();
        for (long[] s : scored) ids.add(s[0]);
        return ids;
    }

    /** Package-private: YRC (word) first, then LRC (line); empty when neither has text. */
    static LyricsData parseLyricResponse(JsonObject root) {
        String yrc = lyric(root, "yrc");
        if (!yrc.isEmpty()) {
            LyricsData l = SyllableLyricsParser.parseYrc(yrc);
            if (!l.isEmpty() && l.type == LyricsData.Type.SYLLABLE) return l;
        }
        return SyllableLyricsParser.parseLrc(lyric(root, "lrc"));
    }

    private JsonObject search(String keyword) {
        try {
            return Http.obj(Http.getJson(client,
                    SEARCH_URL + "?type=1&limit=10&offset=0&s=" + Http.enc(keyword), HEADERS));
        } catch (IOException e) {
            return null;
        }
    }

    private LyricsData fetchById(long id) {
        try {
            JsonObject root = Http.obj(Http.getJson(client,
                    LYRIC_URL + "?id=" + id + "&lv=1&tv=-1&rv=-1&yv=1", HEADERS));
            if (root == null) return null;
            LyricsData l = parseLyricResponse(root);
            l.provider = getName();
            return l;
        } catch (IOException e) {
            return null;
        }
    }

    private static String artists(JsonObject song) {
        StringBuilder sb = new StringBuilder();
        if (song != null && song.has("artists") && song.get("artists").isJsonArray()) {
            for (JsonElement a : song.getAsJsonArray("artists")) {
                String n = Http.str(Http.obj(a), "name");
                if (!n.isEmpty()) sb.append(sb.length() > 0 ? ", " : "").append(n);
            }
        }
        return sb.toString();
    }

    private static String lyric(JsonObject root, String key) {
        return Http.str(Http.obj(root == null ? null : root.get(key)), "lyric");
    }
}
