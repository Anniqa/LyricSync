package com.lyricsync.app.lyrics.provider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import static org.junit.Assert.*;

/**
 * Offline simulation of the title -> Spotify track id resolver, using a captured
 * `searchDesktop` response: the same ranking code the app runs is fed the payload.
 */
public class SpotifyTrackResolverSimulationTest {

    private static JsonObject fixture(String name) throws Exception {
        try (InputStream in = SpotifyTrackResolverSimulationTest.class.getResourceAsStream("/" + name)) {
            assertNotNull("Fixture missing from test classpath: " + name, in);
            try (Scanner sc = new Scanner(in, StandardCharsets.UTF_8.name())) {
                return JsonParser.parseString(sc.useDelimiter("\\\\A").next()).getAsJsonObject();
            }
        }
    }

    @Test
    public void resolvesFromCapturedSearch() throws Exception {
        JsonObject root = fixture("spotify_search_apt.json");
        String id = SpotifyTrackResolver.pickBest(root, "APT.", "ROS\u00c9", 169917L);
        assertEquals("5vNRhkKd0yEAg8suGBpjeY", id);
    }

    @Test
    public void resolvesWithoutDuration() throws Exception {
        JsonObject root = fixture("spotify_search_apt.json");
        String id = SpotifyTrackResolver.pickBest(root, "APT.", "ROS\u00c9", 0L);
        assertEquals("5vNRhkKd0yEAg8suGBpjeY", id);
    }

    @Test
    public void noMatchForADifferentSong() throws Exception {
        JsonObject root = fixture("spotify_search_apt.json");
        assertNull(SpotifyTrackResolver.pickBest(root, "Completely Different Song", "Nobody", 0L));
    }

    @Test
    public void durationMismatchIsRejected() throws Exception {
        JsonObject root = fixture("spotify_search_apt.json");
        // Same title/artist but a wildly different length: must not be treated as the recording.
        assertNull(SpotifyTrackResolver.pickBest(root, "APT.", "ROS\u00c9", 400000L));
    }

    @Test
    public void resolvesJapaneseTitleByRomanisingIt() throws Exception {
        JsonObject root = fixture("spotify_search_suzume.json");
        // The player shows "\u3059\u305a\u3081"; the catalogue stores "Suzume".
        String id = SpotifyTrackResolver.pickBest(root, "\u3059\u305a\u3081", "RADWIMPS", 0L);
        assertTrue("expected a Suzume track id, got " + id,
                "1n1y2kFPISpF9WGD3JaFo5".equals(id) || "7LHAKF7pBqHch8o6Yo0ad5".equals(id));
    }

    @Test
    public void cleansDirtyYouTubeMetadata() {
        assertEquals("APT.", SpotifyTrackResolver.cleanTitle("APT. [Official Video]"));
        assertEquals("ROS\u00c9 - APT.", SpotifyTrackResolver.cleanTitle("ROS\u00c9 - APT. (Official Video)"));
        assertEquals("ROS\u00c9", SpotifyTrackResolver.cleanArtist("ROS\u00c9 - Topic"));
    }

    @Test
    public void normalizesAccentsAndPunctuation() {
        assertEquals("rose", SpotifyTrackResolver.normalize("ROS\u00c9"));
        assertEquals("apt", SpotifyTrackResolver.normalize("APT."));
        assertEquals("and", SpotifyTrackResolver.normalize("&"));
    }
}
