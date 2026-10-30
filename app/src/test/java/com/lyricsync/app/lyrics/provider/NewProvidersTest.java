package com.lyricsync.app.lyrics.provider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.util.TrackQuery;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Better Lyrics and LyricsPlus responses (shapes captured from the live APIs). */
public class NewProvidersTest {

    private static final TrackQuery Q = TrackQuery.of("Blinding Lights", "The Weeknd", 200_000);

    @Test
    public void lyricsPlusWordTimelineWithBackground() {
        JsonObject root = JsonParser.parseString("{\"type\":\"Word\",\"metadata\":{\"source\":\"qApple\",\"title\":\"Blinding Lights\","
                + "\"artist\":\"The Weeknd\"},\"lyrics\":[{\"time\":31498,\"duration\":1031,\"text\":\"long enough\",\"syllabus\":["
                + "{\"time\":31498,\"duration\":341,\"text\":\"long \"},{\"time\":31839,\"duration\":157,\"text\":\"e\"},"
                + "{\"time\":31996,\"duration\":533,\"text\":\"nough \"},{\"time\":32100,\"duration\":400,\"text\":\"(yeah)\",\"isBackground\":true}]}]}")
                .getAsJsonObject();
        LyricsData l = LyricsPlusProvider.parse(root, Q);
        assertEquals(LyricsData.Type.SYLLABLE, l.type);
        assertEquals("LyricsPlus Apple", l.provider);
        LyricsData.LyricsLine line = l.lines.get(l.lines.size() - 1);
        assertEquals("long enough", line.text);
        assertEquals(3, line.words.size());
        assertTrue(line.words.get(1).isPartOfWord);
        assertEquals("(yeah)", line.backgroundVocals.get(0).text);
    }

    @Test
    public void lyricsPlusRejectsAnotherSong() {
        JsonObject root = JsonParser.parseString("{\"type\":\"Line\",\"metadata\":{\"title\":\"Save Your Tears\",\"artist\":\"Ariana Grande\"},"
                + "\"lyrics\":[{\"time\":0,\"duration\":1000,\"text\":\"x\"}]}").getAsJsonObject();
        assertNull(LyricsPlusProvider.parse(root, Q));
    }

    @Test
    public void betterLyricsParamsAndQrcTitleGuard() {
        assertEquals("s=Blinding%20Lights&a=The%20Weeknd&d=200", BetterLyricsProvider.params(Q));
        JsonObject other = JsonParser.parseString("{\"lyrics\":\"[ti:Something Else]\\n[0,1000]a(0,500)\"}").getAsJsonObject();
        assertNull(BetterLyricsProvider.parseQrcResponse(other, Q));
        JsonObject same = JsonParser.parseString("{\"lyrics\":\"[ti:Blinding Lights]\\n[0,1000]I (0,500)been(500,500)\"}").getAsJsonObject();
        assertEquals("I been", BetterLyricsProvider.parseQrcResponse(same, Q).lines.get(0).text);
    }
}
