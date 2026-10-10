package com.lyricsync.app.lyrics.provider;

import com.lyricsync.app.lyrics.model.LyricsData;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import static org.junit.Assert.*;

/**
 * Simulates the official Spicy Lyrics flow (GET /v1/lyrics/{id}) with no network:
 * a captured response envelope is fed through the same parser the provider uses at
 * runtime. Covers the Syllable, Line and Static bodies plus an error envelope.
 */
public class SpicyLyricsV1SimulationTest {

    private static String fixture(String name) throws Exception {
        try (InputStream in = SpicyLyricsV1SimulationTest.class.getResourceAsStream("/" + name)) {
            assertNotNull("Fixture missing from test classpath: " + name, in);
            try (Scanner sc = new Scanner(in, StandardCharsets.UTF_8.name())) {
                return sc.useDelimiter("\\A").next();
            }
        }
    }

    @Test
    public void parsesCapturedSyllablePayload() throws Exception {
        LyricsData lyrics = SpicyLyricsProvider.parseOfficialResponse(fixture("spicy_v1_syllable.json"));

        assertEquals(LyricsData.Type.SYLLABLE, lyrics.type);
        assertEquals("SpicyLyrics", lyrics.provider);
        assertFalse("Payload should produce lines", lyrics.isEmpty());

        boolean sawWords = false;
        int linesWithWords = 0;
        for (LyricsData.LyricsLine line : lyrics.lines) {
            assertTrue("line end >= start", line.endTime >= line.startTime);
            if (line.isInterlude) continue;
            if (!line.words.isEmpty()) linesWithWords++;
            for (LyricsData.Word w : line.words) {
                sawWords = true;
                assertTrue("word end >= start", w.endTime >= w.startTime);
                assertNotNull(w.text);
                assertFalse("word text must not be blank", w.text.trim().isEmpty());
            }
        }
        assertTrue("Expected timed words", sawWords);
        assertTrue("Expected several sung lines", linesWithWords >= 10);
    }

    @Test
    public void mergesSyllablesIntoWholeWords() throws Exception {
        LyricsData lyrics = SpicyLyricsProvider.parseOfficialResponse(fixture("spicy_v1_syllable.json"));
        boolean found = false;
        for (LyricsData.LyricsLine line : lyrics.lines) {
            if ("Mum-mum-mum-mah".equals(line.text)) { found = true; break; }
        }
        assertTrue("IsPartOfWord syllables must merge into one word (no run-on text)", found);
    }

    @Test
    public void parsesLinePayload() throws Exception {
        String json = "{\"Status\":200,\"Type\":\"object\",\"Body\":{\"Type\":\"Line\",\"Content\":["
                + "{\"Text\":\"Hello world\",\"StartTime\":1.0,\"EndTime\":3.5},"
                + "{\"Type\":\"Interlude\",\"StartTime\":4.0,\"EndTime\":6.0},"
                + "{\"Text\":\"Second line\",\"StartTime\":7.0,\"EndTime\":9.0}]}}";
        LyricsData lyrics = SpicyLyricsProvider.parseOfficialResponse(json);

        assertEquals(LyricsData.Type.LINE, lyrics.type);
        assertEquals(3, lyrics.lines.size());
        assertEquals("Hello world", lyrics.lines.get(0).text);
        assertEquals(1000, lyrics.lines.get(0).startTime);
        assertEquals(3500, lyrics.lines.get(0).endTime);
        assertTrue(lyrics.lines.get(1).isInterlude);
        assertEquals("Second line", lyrics.lines.get(2).text);
    }

    @Test
    public void parsesStaticPayload() throws Exception {
        String json = "{\"Status\":200,\"Type\":\"object\",\"Body\":{\"Type\":\"Static\",\"Lines\":["
                + "{\"Text\":\"Line A\"},{\"Text\":\"Line B\"}]}}";
        LyricsData lyrics = SpicyLyricsProvider.parseOfficialResponse(json);

        assertEquals(LyricsData.Type.STATIC, lyrics.type);
        assertEquals(2, lyrics.lines.size());
        assertEquals("Line A", lyrics.lines.get(0).text);
        assertEquals(0, lyrics.lines.get(0).startTime);
        assertEquals("Line B", lyrics.lines.get(1).text);
        assertEquals(5000, lyrics.lines.get(1).startTime);
    }

    @Test
    public void rejectsErrorEnvelope() {
        String json = "{\"Status\":401,\"Type\":\"object\",\"Body\":{\"error\":\"key_not_found\","
                + "\"message\":\"No key matches that hash.\"}}";
        try {
            SpicyLyricsProvider.parseOfficialResponse(json);
            fail("Expected an exception for a non-200 envelope");
        } catch (Exception e) {
            assertTrue("message should carry the status: " + e.getMessage(),
                    e.getMessage().contains("401"));
        }
    }
}
