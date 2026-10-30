package com.lyricsync.app.lyrics.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.SyllableLine;
import com.lyricsync.app.lyrics.model.SyllableLyricsParser;
import com.lyricsync.app.lyrics.util.TrackMatcher;
import com.lyricsync.app.lyrics.util.TrackQuery;

import java.io.IOException;

import okhttp3.OkHttpClient;

/**
 * LyricsPlus (the YouLy+ backend, open source): Apple Music, QQ, Musixmatch and Deezer
 * timelines as one JSON shape — lines with a "syllabus" of timed syllables, background
 * syllables flagged. Answers "Word" (syllable), "Line" or "None".
 */
public class LyricsPlusProvider implements LyricsProvider {

    /** Public mirrors of the same server; the first that answers is used. */
    static final String[] MIRRORS = {"https://lyricsplus.binimum.org", "https://youlyplus.binimum.org"};

    private final OkHttpClient client;

    public LyricsPlusProvider() {
        this(Http.CLIENT);
    }

    public LyricsPlusProvider(OkHttpClient client) {
        this.client = client != null ? client : Http.CLIENT;
    }

    @Override
    public String getName() {
        return "LyricsPlus";
    }

    @Override
    public LyricsData fetchLyrics(TrackQuery q) throws Exception {
        if (q == null || q.isEmpty() || !q.hasArtist()) return null;
        StringBuilder query = new StringBuilder("title=").append(Http.enc(q.title))
                .append("&artist=").append(Http.enc(q.artist));
        if (!q.album.isEmpty()) query.append("&album=").append(Http.enc(q.album));
        if (q.durationMs > 0 && !q.fromVideo) query.append("&duration=").append(Math.round(q.durationMs / 1000.0)); // "200.0" hangs the server
        for (String base : MIRRORS) {
            try {
                JsonObject root = Http.obj(Http.getJson(client, base + "/v2/lyrics/get?" + query,
                        "User-Agent", Http.BROWSER_UA, "X-Client-Package", "LyricSync"));
                return root == null ? null : parse(root, q); // 404 = not found anywhere
            } catch (IOException mirrorDown) {
                // try the next mirror
            }
        }
        return null;
    }

    /** Package-private: a captured /v2/lyrics/get response → lyrics, or null (wrong song or empty). */
    static LyricsData parse(JsonObject root, TrackQuery q) {
        JsonArray lines = root.has("lyrics") && root.get("lyrics").isJsonArray() ? root.getAsJsonArray("lyrics") : null;
        String type = Http.str(root, "type");
        if (lines == null || lines.size() == 0 || "None".equalsIgnoreCase(type)) return null;
        JsonObject meta = Http.obj(root.get("metadata"));
        if (meta != null && q != null && !Http.str(meta, "title").isEmpty()
                && TrackMatcher.score(q.title, q.aliases, q.artists, 0, Http.str(meta, "title"),
                Http.str(meta, "artist"), 0) < TrackMatcher.MIN_SCORE) {
            return null; // the server's fuzzy match picked another song
        }
        boolean word = "Word".equalsIgnoreCase(type) || "Syllable".equalsIgnoreCase(type);
        LyricsData out = new LyricsData(word ? LyricsData.Type.SYLLABLE : LyricsData.Type.LINE);
        boolean timed = false;
        for (JsonElement el : lines) {
            JsonObject l = Http.obj(el);
            if (l == null) continue;
            long start = Http.num(l, "time"), end = start + Http.num(l, "duration");
            SyllableLine syl = new SyllableLine();
            JsonArray parts = l.has("syllabus") && l.get("syllabus").isJsonArray() ? l.getAsJsonArray("syllabus") : null;
            if (word && parts != null) {
                for (JsonElement p : parts) {
                    JsonObject s = Http.obj(p);
                    if (s == null) continue;
                    long t = Http.num(s, "time");
                    syl.syllable(t, t + Http.num(s, "duration"), Http.str(s, "text"),
                            s.has("isBackground") && s.get("isBackground").getAsBoolean());
                }
            }
            LyricsData.LyricsLine line = syl.build(start, end, Http.str(l, "text"));
            if (line == null) continue;
            if (line.words.isEmpty()) SyllableLyricsParser.splitLineIntoWords(line);
            else timed = true;
            out.lines.add(line);
        }
        if (out.lines.isEmpty()) return null;
        if (!timed) out.type = LyricsData.Type.LINE; // "Word" claimed but no syllable carried a time
        out.provider = providerName(meta);
        LyricsData.insertInterludes(out, 3000);
        return out;
    }

    /** "LyricsPlus · Apple": which upstream answered, for the status line. */
    private static String providerName(JsonObject meta) {
        String src = Http.str(meta, "source").toLowerCase(java.util.Locale.ROOT);
        String up = src.contains("apple") ? "Apple" : src.contains("musixmatch") ? "Musixmatch"
                : src.contains("qq") ? "QQ" : src.contains("spotify") ? "Spotify" : src.contains("deezer") ? "Deezer" : "";
        return up.isEmpty() ? "LyricsPlus" : "LyricsPlus " + up;
    }
}
