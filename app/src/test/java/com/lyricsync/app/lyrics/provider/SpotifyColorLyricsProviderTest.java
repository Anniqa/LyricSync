package com.lyricsync.app.lyrics.provider;

import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

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

    @Test
    public void testGenerateTotp() {
        String totp = com.lyricsync.app.lyrics.auth.SpotifyAuthManager.generateTotp(1788801879L);
        assertEquals("767052", totp);
    }

    // --- syncType / timing-fallback coverage (offline fixtures) ---

    private static LyricsData parse(String fixtureName) throws Exception {
        SpotifyColorLyricsProvider provider = new SpotifyColorLyricsProvider(null);
        return provider.parseColorLyricsJson(readFixture(fixtureName));
    }

    private static String readFixture(String name) throws Exception {
        try (InputStream in = SpotifyColorLyricsProviderTest.class
                .getResourceAsStream("/colorlyrics/" + name)) {
            if (in == null) throw new AssertionError("Missing fixture " + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void testFullySyncedPayloadStaysLineSynced() throws Exception {
        LyricsData data = parse("synced_line.json");
        assertEquals(LyricsData.Type.LINE, data.type);
        assertEquals(3, data.lines.size());
        assertEquals(1000L, data.lines.get(0).startTime);
        assertEquals(4000L, data.lines.get(1).startTime);
        assertEquals(9000L, data.lines.get(2).startTime);
        assertEquals(4000L, data.lines.get(0).endTime);
        assertEquals(9000L, data.lines.get(1).endTime);
        assertEquals(12500L, data.lines.get(2).endTime);
    }

    @Test
    public void testUnsyncedPayloadBecomesStaticGrid() throws Exception {
        LyricsData data = parse("unsynced.json");
        assertEquals(LyricsData.Type.STATIC, data.type);
        assertEquals(3, data.lines.size());
        assertEquals(0L, data.lines.get(0).startTime);
        assertEquals(3500L, data.lines.get(1).startTime);
        assertEquals(7000L, data.lines.get(2).startTime);
        assertEquals(3500L, data.lines.get(0).endTime);
        assertEquals(7000L, data.lines.get(1).endTime);
        assertEquals(10500L, data.lines.get(2).endTime);
    }

    @Test
    public void testSyncedTypeButNoRealTimestampBecomesStatic() throws Exception {
        LyricsData data = parse("synced_no_times.json");
        assertEquals(LyricsData.Type.STATIC, data.type);
        assertEquals(0L, data.lines.get(0).startTime);
        assertEquals(3500L, data.lines.get(1).startTime);
        assertEquals(7000L, data.lines.get(2).startTime);
    }

    @Test
    public void testSyncedMissingLineIsInferredBetweenNeighbours() throws Exception {
        LyricsData data = parse("synced_missing_line.json");
        assertEquals(LyricsData.Type.LINE, data.type);
        assertEquals(3, data.lines.size());
        assertEquals(1000L, data.lines.get(0).startTime);
        assertEquals(9000L, data.lines.get(2).startTime);
        long placed = data.lines.get(1).startTime;
        assertTrue("placed line must follow its previous neighbour", placed > 1000L);
        assertTrue("placed line must precede its next neighbour", placed < 9000L);
    }

    @Test
    public void testLeadingUntimedLineIsNotPinnedToZero() throws Exception {
        LyricsData data = parse("synced_leading_untimed.json");
        assertEquals(LyricsData.Type.LINE, data.type);
        assertEquals(5000L, data.lines.get(1).startTime);
        long leading = data.lines.get(0).startTime;
        assertTrue("leading untimed line must not be pinned to the start", leading > 0L);
        assertTrue("leading untimed line must precede the first timed line", leading < 5000L);
    }

    @Test
    public void testTrailingUntimedLineFollowsItsNeighbour() throws Exception {
        LyricsData data = parse("synced_trailing_untimed.json");
        assertEquals(LyricsData.Type.LINE, data.type);
        assertEquals(1000L, data.lines.get(0).startTime);
        assertTrue("trailing untimed line must follow its neighbour",
                data.lines.get(1).startTime > 1000L);
    }

    @Test
    public void testGenuineZeroTimestampIsKept() throws Exception {
        LyricsData data = parse("genuine_zero.json");
        assertEquals(LyricsData.Type.LINE, data.type);
        assertEquals(2, data.lines.size());
        assertEquals(0L, data.lines.get(0).startTime);
        assertEquals(2000L, data.lines.get(1).startTime);
    }

    @Test
    public void testSyllablePayloadStaysSyllable() throws Exception {
        LyricsData data = parse("syllable.json");
        assertEquals(LyricsData.Type.SYLLABLE, data.type);
        assertEquals(1, data.lines.size());
        LyricsData.LyricsLine line = data.lines.get(0);
        assertEquals(1000L, line.startTime);
        assertEquals(3000L, line.endTime);
        assertEquals(2, line.words.size());
        assertEquals("hello", line.words.get(0).text);
        assertEquals("world", line.words.get(1).text);
    }

}
