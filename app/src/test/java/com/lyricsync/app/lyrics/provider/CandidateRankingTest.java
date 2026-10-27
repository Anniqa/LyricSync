package com.lyricsync.app.lyrics.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.util.TrackQuery;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Scanner;

/** Offline: each provider picks the right entry out of a captured search response. */
public class CandidateRankingTest {

    private static JsonObject fixture(String name) {
        InputStream in = CandidateRankingTest.class.getClassLoader().getResourceAsStream(name);
        try (Scanner s = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
            return JsonParser.parseString(s.next()).getAsJsonObject();
        }
    }

    private static JsonArray json(String s) {
        return JsonParser.parseString(s).getAsJsonArray();
    }

    @Test
    public void spotify_picksTheStudioRecording() {
        TrackQuery q = TrackQuery.of("Suzume", "RADWIMPS", 238_000);
        assertEquals("1n1y2kFPISpF9WGD3JaFo5", SpotifyTrackResolver.pickBest(fixture("spotify_search_suzume.json"), q));
    }

    @Test
    public void spotify_collabWithLocalisedArtist() {
        TrackQuery q = TrackQuery.of("APT.", "ROSÉ & Bruno Mars", 170_000);
        assertEquals("5vNRhkKd0yEAg8suGBpjeY", SpotifyTrackResolver.pickBest(fixture("spotify_search_apt.json"), q));
    }

    @Test
    public void spotify_rejectsWrongLength() {
        assertNull(SpotifyTrackResolver.pickBest(fixture("spotify_search_suzume.json"),
                TrackQuery.of("Suzume", "RADWIMPS", 300_000)));
    }

    @Test
    public void kugou_prefersKrcStudioOverRemix() {
        JsonArray c = json("["
                + "{\"id\":\"1\",\"accesskey\":\"a\",\"song\":\"Without You (Remix)\",\"singer\":\"Avicii\",\"duration\":181000,\"krctype\":2},"
                + "{\"id\":\"2\",\"accesskey\":\"b\",\"song\":\"Without You\",\"singer\":\"Avicii\",\"duration\":181000,\"krctype\":1},"
                + "{\"id\":\"3\",\"accesskey\":\"c\",\"song\":\"Without You\",\"singer\":\"Avicii\",\"duration\":181000,\"krctype\":2},"
                + "{\"id\":\"4\",\"accesskey\":\"d\",\"song\":\"Other\",\"singer\":\"Nobody\",\"duration\":90000,\"krctype\":2}]");
        List<KugouLyricsProvider.Candidate> ranked =
                KugouLyricsProvider.rankCandidates(c, TrackQuery.of("Without You", "Avicii", 181_000));
        assertEquals("3", ranked.get(0).id);
        assertEquals(3, ranked.size()); // the unrelated song is dropped
    }

    @Test
    public void netease_ranksMatchesAndParsesYrcFirst() {
        JsonObject search = JsonParser.parseString("{\"result\":{\"songs\":["
                + "{\"id\":10,\"name\":\"晴天 (Live)\",\"duration\":269000,\"artists\":[{\"name\":\"周杰伦\"}]},"
                + "{\"id\":11,\"name\":\"晴天\",\"duration\":269000,\"artists\":[{\"name\":\"周杰伦\"}]}]}}").getAsJsonObject();
        assertEquals(Long.valueOf(11), NeteaseLyricsProvider.rankSongs(search, TrackQuery.of("晴天", "周杰伦", 269_000)).get(0));

        JsonObject lyric = JsonParser.parseString("{\"lrc\":{\"lyric\":\"[00:01.00]line\"},"
                + "\"yrc\":{\"lyric\":\"[1000,500](1000,500,0)word\"}}").getAsJsonObject();
        assertEquals(LyricsData.Type.SYLLABLE, NeteaseLyricsProvider.parseLyricResponse(lyric).type);
        lyric.remove("yrc");
        assertEquals(LyricsData.Type.LINE, NeteaseLyricsProvider.parseLyricResponse(lyric).type);
    }

    @Test
    public void lrclib_syncedBeatsPlainAndParses() {
        JsonArray r = json("["
                + "{\"trackName\":\"Monokrom\",\"artistName\":\"Tulus\",\"duration\":213,\"plainLyrics\":\"a\\nb\"},"
                + "{\"trackName\":\"Monokrom\",\"artistName\":\"Tulus\",\"duration\":213,\"syncedLyrics\":\"[00:01.00]Lembaran foto\"}]");
        JsonObject best = LRCLIBProvider.pickBest(r, TrackQuery.of("Monokrom", "Tulus", 213_000));
        LyricsData l = LRCLIBProvider.parse(best);
        assertEquals(LyricsData.Type.LINE, l.type);
        assertTrue(l.lines.get(0).text.startsWith("Lembaran"));
        assertEquals(LyricsData.Type.STATIC, LRCLIBProvider.parse(r.get(0).getAsJsonObject()).type);
    }
}
