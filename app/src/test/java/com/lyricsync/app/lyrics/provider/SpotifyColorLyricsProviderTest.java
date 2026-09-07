package com.lyricsync.app.lyrics.provider;

import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;

import org.junit.Test;

public class SpotifyColorLyricsProviderTest {

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("Expected: " + expected + " but got: " + actual);
    }

    private static void assertTrue(String msg, boolean condition) {
        if (!condition) throw new AssertionError(msg);
    }

    private static void assertTrue(boolean condition) {
        assertTrue("Condition was false", condition);
    }

    private static void assertNotNull(String msg, Object obj) {
        if (obj == null) throw new AssertionError(msg);
    }

    @Test
    public void testExtractTrackId() {
        SpotifyColorLyricsProvider provider = new SpotifyColorLyricsProvider(null);

        TrackInfo track1 = new TrackInfo();
        track1.trackId = "spotify:track:4fouWK6XVHhzl78KzQ1UjL";
        assertEquals("4fouWK6XVHhzl78KzQ1UjL", provider.extractTrackId(track1));

        TrackInfo track2 = new TrackInfo();
        track2.trackId = "4fouWK6XVHhzl78KzQ1UjL?si=abcdef123";
        assertEquals("4fouWK6XVHhzl78KzQ1UjL", provider.extractTrackId(track2));

        TrackInfo track3 = new TrackInfo();
        track3.trackId = "spotify:track:4fouWK6XVHhzl78KzQ1UjL?si=123";
        assertEquals("4fouWK6XVHhzl78KzQ1UjL", provider.extractTrackId(track3));
    }

    @Test
    public void testParseColorLyricsWithSyllables() throws Exception {
        SpotifyColorLyricsProvider provider = new SpotifyColorLyricsProvider(null);

        String json = "{\n" +
                "  \"lyrics\": {\n" +
                "    \"syncType\": \"LINE_SYNCED\",\n" +
                "    \"lines\": [\n" +
                "      {\n" +
                "        \"startTimeMs\": \"1000\",\n" +
                "        \"endTimeMs\": \"3000\",\n" +
                "        \"words\": \"hello world\",\n" +
                "        \"syllables\": [\n" +
                "          {\"startTimeMs\": \"1000\", \"endTimeMs\": \"2000\", \"text\": \"hello\"},\n" +
                "          {\"startTimeMs\": \"2000\", \"endTimeMs\": \"3000\", \"text\": \"world\"}\n" +
                "        ]\n" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "}";

        LyricsData data = provider.parseColorLyricsJson(json);
        assertNotNull("Data should not be null", data);
        assertEquals(LyricsData.Type.SYLLABLE, data.type);
        assertEquals(1, data.lines.size());

        LyricsData.LyricsLine line = data.lines.get(0);
        assertEquals(1000L, line.startTime);
        assertEquals(3000L, line.endTime);
        assertEquals("hello world", line.text);
        assertEquals(2, line.words.size());
        assertEquals("hello", line.words.get(0).text);
        assertEquals(1000L, line.words.get(0).startTime);
        assertEquals(2000L, line.words.get(0).endTime);
        assertEquals("world", line.words.get(1).text);
        assertEquals(2000L, line.words.get(1).startTime);
        assertEquals(3000L, line.words.get(1).endTime);
    }

    @Test
    public void testParseColorLyricsLineOnly() throws Exception {
        SpotifyColorLyricsProvider provider = new SpotifyColorLyricsProvider(null);

        String json = "{\n" +
                "  \"lyrics\": {\n" +
                "    \"syncType\": \"LINE_SYNCED\",\n" +
                "    \"lines\": [\n" +
                "      {\n" +
                "        \"startTimeMs\": \"5000\",\n" +
                "        \"endTimeMs\": \"8000\",\n" +
                "        \"words\": \"line without syllables\",\n" +
                "        \"syllables\": []\n" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "}";

        LyricsData data = provider.parseColorLyricsJson(json);
        assertNotNull("Data should not be null", data);
        assertEquals(LyricsData.Type.LINE, data.type);
        assertEquals(1, data.lines.size());
        assertEquals("line without syllables", data.lines.get(0).text);
        assertTrue(data.lines.get(0).words.isEmpty());
    }
}
