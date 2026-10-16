package com.lyricsync.app.lyrics.provider;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.util.AppLog;
import com.lyricsync.app.ai.transliteration.ScriptDetector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SpicyLyricsProvider implements LyricsProvider {
    private static final String TAG = "SpicyLyrics";
    private static final String OFFICIAL_API_URL = "https://api.spicylyrics.org/v1/lyrics/";
    /** User-supplied Spicy Lyrics client key (sl_pk_...), stored in the shared prefs. */
    public static final String PREF_SPICY_CLIENT_KEY = "pref_spicy_client_key";
    /** The app-wide SharedPreferences file the settings screen writes to. */
    private static final String PREF_FILE = "lyricsync";
    /**
     * Publishable key shipped with the app so the official API works out of the box.
     * A key pasted in Settings overrides this. Publishable keys are meant to live in
     * client apps (that is what "publishable" means); rotate it in the dashboard if abused.
     */
    private static final String DEFAULT_CLIENT_KEY =
            "sl_pk_KKPqmweywKoS6MWFkaru-nRLsZMDkpAu7WMvtnTfXis";
    private static final String SPICY_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 Spotify/1.2.52.442";

    private final Context context;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    public SpicyLyricsProvider(Context context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "SpicyLyrics";
    }

    @Override
    public LyricsData fetchLyrics(TrackInfo track) throws Exception {
        String trackId = extractSpotifyId(track);
        if (trackId == null) {
            trackId = resolveSpotifyTrackId(track);
        }
        if (trackId == null) {
            throw new IOException("No Spotify track ID available");
        }

        // Official developer API (/v1/lyrics) — the supported route.
        return fetchOfficialLyrics(trackId, getClientKey());
    }

    /** The user's Spicy Lyrics client key, or null when not configured/invalid. */
    private String getClientKey() {
        try {
            String stored = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                    .getString(PREF_SPICY_CLIENT_KEY, null);
            if (stored != null) {
                String key = stored.trim();
                if (key.matches("sl_(pk|sk)_[A-Za-z0-9_-]+")) return key;
            }
        } catch (Exception ignored) {}
        return DEFAULT_CLIENT_KEY;
    }

    /**
     * Fetches lyrics from the official developer API:
     * GET https://api.spicylyrics.org/v1/lyrics/{trackId}
     * with `Authorization: Bearer sl_pk_...`. The response is {"Status":200,"Type":"object",
     * "Body":{Type, Content|Lines, ...}} and Body has the same lyric shape we already parse.
     */
    private LyricsData fetchOfficialLyrics(String trackId, String clientKey) throws Exception {
        Request request = new Request.Builder()
                .url(OFFICIAL_API_URL + trackId)
                .get()
                .header("Authorization", "Bearer " + clientKey)
                .header("Accept", "application/json")
                .header("User-Agent", SPICY_USER_AGENT)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.body() == null) throw new IOException("Empty response body");
            return parseOfficialResponse(response.body().string());
        }
    }

    /**
     * Parses a /v1/lyrics response envelope. `Body` carries the same lyric shape the
     * legacy route used, so the parsers below are shared. Package-private so a unit
     * test can run it against a captured payload without any network.
     */
    static LyricsData parseOfficialResponse(String raw) throws IOException {
        JsonObject envelope;
        try {
            envelope = JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("Spicy Lyrics invalid response");
        }
        int status = envelope.has("Status") && !envelope.get("Status").isJsonNull()
                ? envelope.get("Status").getAsInt() : -1;
        if (status != 200) {
            String message = "";
            if (envelope.has("Body") && envelope.get("Body").isJsonObject()) {
                message = safeString(envelope.getAsJsonObject("Body"), "message", "error");
            }
            throw new IOException("Spicy Lyrics HTTP " + status
                    + (message.isEmpty() ? "" : ": " + message));
        }
        if (!envelope.has("Body") || !envelope.get("Body").isJsonObject()) {
            throw new IOException("Spicy Lyrics: missing Body");
        }

        JsonObject data = envelope.getAsJsonObject("Body");
        LyricsData lyrics = new LyricsData();
        lyrics.provider = "SpicyLyrics";

        String type = safeString(data, "Type", "type");
        switch (type) {
            case "Syllable":
                lyrics.type = LyricsData.Type.SYLLABLE;
                parseSyllableLyrics(data, lyrics);
                break;
            case "Line":
                lyrics.type = LyricsData.Type.LINE;
                parseLineLyrics(data, lyrics);
                break;
            default:
                lyrics.type = LyricsData.Type.STATIC;
                parseStaticLyrics(data, lyrics);
                break;
        }
        AppLog.i(TAG, "Official API v1: Type=" + type + " lines=" + lyrics.lines.size());
        return lyrics;
    }

    static void parseSyllableLyrics(JsonObject data, LyricsData lyrics) {
        JsonArray content = data.getAsJsonArray("Content");
        if (content == null) return;

        for (JsonElement groupEl : content) {
            JsonObject group = groupEl.getAsJsonObject();
            String groupType = safeString(group, "Type", "type");

            // Log all keys for debugging bg vocal detection
            StringBuilder keys = new StringBuilder();
            for (String k : group.keySet()) keys.append(k).append(",");
            AppLog.d(TAG, "Group keys: [" + keys + "] type=" + groupType);

            // Interlude: {"Type":"Interlude","StartTime":...,"EndTime":...}
            if ("Interlude".equals(groupType)) {
                long start = getTimeMs(group, "StartTime");
                long end = getTimeMs(group, "EndTime");
                if (start < 0 || end < 0) continue;
                LyricsData.LyricsLine line = new LyricsData.LyricsLine(start, end, "");
                line.isInterlude = true;
                lyrics.lines.add(line);
                continue;
            }

            // Vocal: {"Type":"Vocal","Lead":{...},"Background":[...]}
            if (!group.has("Lead")) continue;
            JsonObject lead = group.getAsJsonObject("Lead");
            JsonArray leadSyllables = lead.getAsJsonArray("Syllables");
            if (leadSyllables == null || leadSyllables.size() == 0) continue;

            long lineStart = getTimeMs(lead, "StartTime");
            long lineEnd = getTimeMs(lead, "EndTime");
            if (lineStart < 0 || lineEnd < 0) continue;

            // SpicyLyrics syllables are sub-word timing units and carry NO spaces.
            // The IsPartOfWord flag marks a syllable that continues into the NEXT
            // syllable, so we must merge syllables back into real words or the line
            // renders as one run-on word (e.g. "Ibeentrynacall").
            StringBuilder lineText = new StringBuilder();
            List<LyricsData.Word> leadWords = buildWordsFromSyllables(leadSyllables, lineText);

            if (leadWords.isEmpty()) continue;

            LyricsData.LyricsLine line = new LyricsData.LyricsLine(lineStart, lineEnd, lineText.toString().trim());
            line.words.addAll(leadWords);

            // Parse background vocals (Spicy EX: checks both "Background" and "background")
            JsonArray bgArray = null;
            if (group.has("Background")) bgArray = group.getAsJsonArray("Background");
            else if (group.has("background")) bgArray = group.getAsJsonArray("background");
            if (bgArray != null && bgArray.size() > 0) {
                    AppLog.d(TAG, "BG vocals found: " + bgArray.size() + " entries for line: " + lineText);
                    line.backgroundVocals = new java.util.ArrayList<>();
                    for (JsonElement bgEl : bgArray) {
                        JsonObject bg = bgEl.getAsJsonObject();
                        JsonArray bgSyllables = bg.getAsJsonArray("Syllables");
                        if (bgSyllables == null || bgSyllables.size() == 0) continue;

                        long bgStart = getTimeMs(bg, "StartTime");
                        long bgEnd = getTimeMs(bg, "EndTime");
                        if (bgStart < 0 || bgEnd < 0) continue;

                        StringBuilder bgText = new StringBuilder();
                        List<LyricsData.Word> bgWords = buildWordsFromSyllables(bgSyllables, bgText);
                        if (bgWords.isEmpty()) continue;

                        LyricsData.LyricsLine bgLine = new LyricsData.LyricsLine(bgStart, bgEnd, bgText.toString().trim());
                        bgLine.words.addAll(bgWords);

                        line.backgroundVocals.add(bgLine);
                    }
                }

            lyrics.lines.add(line);
        }

        int bgCount = 0;
        for (LyricsData.LyricsLine l : lyrics.lines) {
            if (l.backgroundVocals != null && !l.backgroundVocals.isEmpty()) bgCount++;
        }
        AppLog.i(TAG, "Parsed " + lyrics.lines.size() + " lines, " + bgCount + " with bg vocals");

        // SpicyLyrics generates the musical-line (instrumental dots) from gaps
        // between lines (>= getLyricsBetweenShow() = 3s), not only from explicit
        // Interlude types. Match that behaviour.
        LyricsData.insertInterludes(lyrics, 3000);
    }

    /**
     * Merge SpicyLyrics syllables into real words. A syllable whose IsPartOfWord flag
     * is true continues the word that the NEXT syllable begins, so consecutive
     * syllables are grouped into one Word until a syllable that is not part of a word
     * starts a new one. The merged, space-joined text is appended to {@code lineText}.
     */
    static List<LyricsData.Word> buildWordsFromSyllables(JsonArray syllables, StringBuilder lineText) {
        List<LyricsData.Word> words = new ArrayList<>();
        if (syllables == null) return words;

        // Check if syllables contain non-Latin scripts (Japanese Kanji/Kana, Chinese, Korean)
        boolean hasNonLatin = false;
        for (int i = 0; i < syllables.size(); i++) {
            JsonObject syl = syllables.get(i).getAsJsonObject();
            String text = safeString(syl, "Text", "text");
            if (ScriptDetector.isNonLatin(text)) {
                hasNonLatin = true;
                break;
            }
        }

        if (hasNonLatin) {
            // In CJK languages without word spaces, keep each syllable/mora distinct with its own
            // timing from SpicyLyrics. Merging all syllables into a single Word destroys per-syllable
            // timing and creates an unbreakable run-on line that overflows the overlay.
            for (int i = 0; i < syllables.size(); i++) {
                JsonObject syl = syllables.get(i).getAsJsonObject();
                String text = safeString(syl, "Text", "text");
                if (text.isEmpty()) continue;
                long start = getTimeMs(syl, "StartTime");
                long end = getTimeMs(syl, "EndTime");
                if (start < 0 || end < 0) continue;
                boolean partOfWord = syl.has("IsPartOfWord") && !syl.get("IsPartOfWord").isJsonNull()
                        && syl.get("IsPartOfWord").getAsBoolean();

                LyricsData.Word w = new LyricsData.Word(start, end, text);
                w.isPartOfWord = partOfWord;
                words.add(w);
                lineText.append(text);
            }
            return words;
        }
        StringBuilder cur = new StringBuilder();
        long curStart = 0;
        long curEnd = 0;
        boolean prevPartOfWord = false;

        for (int i = 0; i < syllables.size(); i++) {
            JsonObject syl = syllables.get(i).getAsJsonObject();
            String text = safeString(syl, "Text", "text");
            if (text.isEmpty()) continue;
            long start = getTimeMs(syl, "StartTime");
            long end = getTimeMs(syl, "EndTime");
            if (start < 0 || end < 0) continue;
            boolean partOfWord = syl.has("IsPartOfWord") && !syl.get("IsPartOfWord").isJsonNull()
                    && syl.get("IsPartOfWord").getAsBoolean();

            if (cur.length() == 0) {
                curStart = start;
                curEnd = end;
                cur.append(text);
            } else if (prevPartOfWord) {
                // previous syllable was part of a word -> this one continues it
                curEnd = end;
                cur.append(text);
            } else {
                // previous syllable ended a word -> flush, start a new one
                words.add(new LyricsData.Word(curStart, curEnd, cur.toString().trim()));
                if (lineText.length() > 0) lineText.append(' ');
                lineText.append(cur.toString().trim());
                cur.setLength(0);
                curStart = start;
                curEnd = end;
                cur.append(text);
            }
            prevPartOfWord = partOfWord;
        }

        if (cur.length() > 0) {
            words.add(new LyricsData.Word(curStart, curEnd, cur.toString().trim()));
            if (lineText.length() > 0) lineText.append(' ');
            lineText.append(cur.toString().trim());
        }
        return words;
    }

    static void parseLineLyrics(JsonObject data, LyricsData lyrics) {
        JsonArray content = data.getAsJsonArray("Content");
        if (content == null) content = data.getAsJsonArray("Lines");
        if (content == null) return;

        for (JsonElement lineEl : content) {
            JsonObject lineObj = lineEl.getAsJsonObject();
            String lineType = safeString(lineObj, "Type", "type");

            if ("Interlude".equals(lineType)) {
                long start = getTimeMs(lineObj, "StartTime", "startTime");
                long end = getTimeMs(lineObj, "EndTime", "endTime");
                if (start < 0 && lineObj.has("Time")) {
                    JsonObject time = lineObj.getAsJsonObject("Time");
                    start = getTimeMs(time, "Start");
                    end = getTimeMs(time, "End");
                }
                if (start >= 0 && end >= 0 && start < end) {
                    LyricsData.LyricsLine line = new LyricsData.LyricsLine(start, end, "");
                    line.isInterlude = true;
                    lyrics.lines.add(line);
                }
                continue;
            }

            String text = safeString(lineObj, "Text", "text");
            if (text.isEmpty()) continue;

            // SpicyLyrics Line type: StartTime/EndTime at top level
            long start = getTimeMs(lineObj, "StartTime", "startTime");
            long end = getTimeMs(lineObj, "EndTime", "endTime");
            // Fallback: Time sub-object
            if (start < 0 && lineObj.has("Time")) {
                JsonObject time = lineObj.getAsJsonObject("Time");
                start = getTimeMs(time, "Start");
                end = getTimeMs(time, "End");
            }
            if (start < 0 || end < 0) continue;

            LyricsData.LyricsLine line = new LyricsData.LyricsLine(start, end, text);
            lyrics.lines.add(line);
        }

        LyricsData.insertInterludes(lyrics, 3000);
        AppLog.d(TAG, "Line lyrics: " + lyrics.lines.size() + " lines");
    }

    static void parseStaticLyrics(JsonObject data, LyricsData lyrics) {
        // SpicyLyrics API uses "Lines" for Static type, not "Content"
        JsonArray content = data.getAsJsonArray("Lines");
        if (content == null) content = data.getAsJsonArray("Content");
        if (content == null) return;

        long time = 0;
        for (JsonElement lineEl : content) {
            JsonObject lineObj = lineEl.getAsJsonObject();
            String text = safeString(lineObj, "Text", "text");
            if (!text.isEmpty()) {
                LyricsData.LyricsLine line = new LyricsData.LyricsLine(time, time + 5000, text);
                lyrics.lines.add(line);
                time += 5000;
            }
        }
        AppLog.d(TAG, "Static lyrics: " + lyrics.lines.size() + " lines");
    }

    static long getTimeMs(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key)) {
                try {
                    return (long) (obj.get(key).getAsDouble() * 1000);
                } catch (Exception ignored) {
                }
            }
        }
        return -1;
    }

    private static String safeString(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                try { return obj.get(key).getAsString(); } catch (Exception ignored) {}
            }
        }
        return "";
    }

    private String extractSpotifyId(TrackInfo track) {
        if (track.trackId == null || track.trackId.isEmpty()) return null;
        String id = track.trackId.trim();
        if (id.startsWith("spotify:track:")) {
            id = id.substring("spotify:track:".length());
        } else if (id.contains("open.spotify.com/track/")) {
            int start = id.indexOf("open.spotify.com/track/") + "open.spotify.com/track/".length();
            int end = id.indexOf('?', start);
            id = end >= 0 ? id.substring(start, end) : id.substring(start);
        }
        if (!isSpotifyTrackId(id)) return null;
        return id;
    }

    private String resolveSpotifyTrackId(TrackInfo track) {
        if (!track.isValid()) return null;

        String id = extractSpotifyId(track);
        if (id != null) return id;

        String title = track.title;
        String artist = track.artist;
        if (title != null && title.contains(" - ")) {
            String[] parts = title.split(" - ", 2);
            if (artist == null || artist.trim().isEmpty() || artist.toLowerCase(Locale.US).contains("topic")) {
                artist = parts[0];
                title = parts[1];
            }
        }

        String isrcResolvedId = SpotifyTrackResolver.resolve(title, artist, track.duration);
        if (isrcResolvedId != null) {
            track.trackId = "spotify:track:" + isrcResolvedId;
            AppLog.i(TAG, "Resolved trackId via SpotifyTrackResolver: " + isrcResolvedId + " for " + title);
            return isrcResolvedId;
        }
        return null;
    }
    private boolean isSpotifyTrackId(String id) {
        return id != null && id.matches("[A-Za-z0-9]{22}");
    }
}
