package com.lyricsync.app.lyrics.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Raw player metadata → a clean query, and how candidates are scored against it. */
public class TrackQueryTest {

    @Test
    public void youtubeTitleIsCleaned() {
        TrackQuery q = TrackQuery.of("Billie Eilish - BIRDS OF A FEATHER (Official Music Video)", "Billie Eilish", 230_000);
        assertEquals("BIRDS OF A FEATHER", q.title);
        assertEquals("Billie Eilish", q.artist);
        assertTrue(q.fromVideo);
    }

    @Test
    public void featAndUnknownArtist() {
        TrackQuery q = TrackQuery.of("Despacito ft. Daddy Yankee", "<unknown>", 0);
        assertEquals("Despacito", q.title);
        assertFalse(q.hasArtist());
        assertFalse(q.fromVideo);
    }

    @Test
    public void recordingVersionIsKept() {
        assertEquals("Heat Waves (Sped Up)", TrackQuery.of("Heat Waves (Sped Up)", "Glass Animals", 0).title);
    }

    @Test
    public void primaryArtistOfACollab() {
        assertEquals("Avicii", TrackQuery.of("Without You", "Avicii, Sandro Cavazza", 0).artist);
    }

    @Test
    public void studioBeatsRemixAndCover() {
        TrackQuery q = TrackQuery.of("Without You", "Avicii", 181_000);
        double studio = q.score("Without You", "Avicii", 181_000);
        assertTrue(studio > q.score("Without You (Henry Land Remix)", "Avicii", 181_000));
        assertTrue(q.score("Something Else", "Avicii", 181_000) < TrackMatcher.MIN_SCORE);
        assertTrue(q.score("Without You", "Some Cover Band", 181_000) < studio);
    }

    @Test
    public void durationBreaksTies() {
        TrackQuery q = TrackQuery.of("Song", "Band", 200_000);
        assertTrue(q.score("Song", "Band", 201_000) > q.score("Song", "Band", 260_000));
    }

    @Test
    public void crossScriptArtistIsNeutral() {
        // Spotify says "Kenshi Yonezu"; the player says 米津玄師. Title match must still win.
        TrackQuery q = TrackQuery.of("Lemon", "米津玄師", 0);
        assertTrue(q.score("Lemon", "Kenshi Yonezu", 0) >= TrackMatcher.MIN_SCORE);
    }

    @Test
    public void otherVersionDetection() {
        assertTrue(TrackMatcher.isOtherVersion("Without You", "Without You (Henry Land Remix)"));
        assertTrue(TrackMatcher.isOtherVersion("晴天", "晴天 (Live)"));
        assertFalse(TrackMatcher.isOtherVersion("Viva La Vida (Live)", "Viva La Vida (Live in São Paulo)"));
        assertFalse(TrackMatcher.isOtherVersion("Dreams", "Dreams"));
    }
}
