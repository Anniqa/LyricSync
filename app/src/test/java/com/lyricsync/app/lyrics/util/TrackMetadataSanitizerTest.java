package com.lyricsync.app.lyrics.util;

import com.lyricsync.app.lyrics.model.TrackInfo;

import org.junit.Test;

import static org.junit.Assert.*;

public class TrackMetadataSanitizerTest {

    @Test
    public void testYoasobiIdolWithNoise() {
        // Input: "YOASOBI - アイドル (Idol) [Official Music Video]"
        TrackMetadataSanitizer.SanitizedMetadata result = TrackMetadataSanitizer.sanitize(
                "YOASOBI - アイドル (Idol) [Official Music Video]",
                "YOASOBI"
        );
        assertEquals("アイドル", result.cleanTitle);
        assertEquals("YOASOBI", result.cleanArtist);
        assertEquals(1, result.titleAliases.size());
        assertEquals("Idol", result.titleAliases.get(0));
    }

    @Test
    public void testCreepyNutsBlingBangBangBorn() {
        // Input: "Creepy Nuts - Bling-Bang-Bang-Born (Official Audio)"
        TrackMetadataSanitizer.SanitizedMetadata result = TrackMetadataSanitizer.sanitize(
                "Creepy Nuts - Bling-Bang-Bang-Born (Official Audio)",
                "Creepy Nuts"
        );
        assertEquals("Bling-Bang-Bang-Born", result.cleanTitle);
        assertEquals("Creepy Nuts", result.cleanArtist);
        assertTrue(result.titleAliases.isEmpty());
    }

    @Test
    public void testArtistExtractionFromTitleWhenArtistGeneric() {
        TrackMetadataSanitizer.SanitizedMetadata result = TrackMetadataSanitizer.sanitize(
                "YOASOBI - アイドル (Idol) [Official Music Video]",
                "YOASOBI - Topic"
        );
        assertEquals("アイドル", result.cleanTitle);
        assertEquals("YOASOBI", result.cleanArtist);
        assertEquals(1, result.titleAliases.size());
        assertEquals("Idol", result.titleAliases.get(0));
    }

    @Test
    public void testDualScriptEnglishFirst() {
        TrackMetadataSanitizer.SanitizedMetadata result = TrackMetadataSanitizer.sanitize(
                "KAIKAI KITAN (廻廻奇譚)",
                "Eve"
        );
        assertEquals("KAIKAI KITAN", result.cleanTitle);
        assertEquals("Eve", result.cleanArtist);
        assertEquals(1, result.titleAliases.size());
        assertEquals("廻廻奇譚", result.titleAliases.get(0));
    }

    @Test
    public void testMultipleArtistsCleanup() {
        TrackMetadataSanitizer.SanitizedMetadata result = TrackMetadataSanitizer.sanitize(
                "APT. [Official Video]",
                "ROSÉ & Bruno Mars"
        );
        assertEquals("APT.", result.cleanTitle);
        assertEquals("ROSÉ", result.cleanArtist);
    }

    @Test
    public void testTrackInfoOverload() {
        TrackInfo track = new TrackInfo();
        track.title = "YOASOBI - アイドル (Idol) [Official Music Video]";
        track.artist = "YOASOBI";
        TrackMetadataSanitizer.SanitizedMetadata result = TrackMetadataSanitizer.sanitize(track);
        assertEquals("アイドル", result.cleanTitle);
        assertEquals("YOASOBI", result.cleanArtist);
    }

    @Test
    public void testHyphenDualScriptUsseewa() {
        TrackMetadataSanitizer.SanitizedMetadata result = TrackMetadataSanitizer.sanitize(
                "うっせぇわ - Usseewa",
                "Ado"
        );
        assertEquals("うっせぇわ", result.cleanTitle);
        assertEquals("Ado", result.cleanArtist);
        assertEquals(1, result.titleAliases.size());
        assertEquals("Usseewa", result.titleAliases.get(0));
    }

    @Test
    public void testWesternSongNoiseRemasteredAndFeat() {
        TrackMetadataSanitizer.SanitizedMetadata r1 = TrackMetadataSanitizer.sanitize(
                "Bohemian Rhapsody - Remastered 2011",
                "Queen"
        );
        assertEquals("Bohemian Rhapsody", r1.cleanTitle);
        assertEquals("Queen", r1.cleanArtist);

        TrackMetadataSanitizer.SanitizedMetadata r2 = TrackMetadataSanitizer.sanitize(
                "Shape of You (feat. Stormzy)",
                "Ed Sheeran"
        );
        assertEquals("Shape of You", r2.cleanTitle);
        assertEquals("Ed Sheeran", r2.cleanArtist);
    }

    @Test
    public void testAlbumNoiseSuffixStripping() {
        TrackMetadataSanitizer.SanitizedMetadata r1 = TrackMetadataSanitizer.sanitize(
                "POST HUMAN: NeX GEn Album",
                "Bring Me The Horizon"
        );
        assertEquals("POST HUMAN: NeX GEn", r1.cleanTitle);
        assertEquals("Bring Me The Horizon", r1.cleanArtist);

        TrackMetadataSanitizer.SanitizedMetadata r2 = TrackMetadataSanitizer.sanitize(
                "Meteora Full Album",
                "Linkin Park"
        );
        assertEquals("Meteora", r2.cleanTitle);
        assertEquals("Linkin Park", r2.cleanArtist);
    }

    @Test
    public void testSmartAliasesStylisticAndOst() {
        // BMTH track "a bulleT w- my namE On" or "a bulleT w/ my namE On"
        TrackMetadataSanitizer.SanitizedMetadata r1 = TrackMetadataSanitizer.sanitize(
                "a bulleT w- my namE On",
                "Bring Me The Horizon"
        );
        assertTrue("Should produce alias with 'with'", r1.titleAliases.contains("a bulleT with my namE On"));

        // Dotted abbreviations "R.i.p." -> "Rip", "p.u.s.s.-e" -> "puss-e"
        TrackMetadataSanitizer.SanitizedMetadata r2 = TrackMetadataSanitizer.sanitize(
                "R.i.p.",
                "Bring Me The Horizon"
        );
        assertTrue("Should produce un-dotted alias 'Rip'", r2.titleAliases.contains("Rip"));

        TrackMetadataSanitizer.SanitizedMetadata r2b = TrackMetadataSanitizer.sanitize(
                "p.u.s.s.-e",
                "Bring Me The Horizon"
        );
        assertTrue("Should produce un-dotted alias 'puss-e'", r2b.titleAliases.contains("puss-e"));

        // [ost] preservation with alias
        TrackMetadataSanitizer.SanitizedMetadata r3 = TrackMetadataSanitizer.sanitize(
                "[ost] dreamseeker",
                "Bring Me The Horizon"
        );
        assertEquals("[ost] dreamseeker", r3.cleanTitle);
        assertTrue("Should produce alias without [ost]", r3.titleAliases.contains("dreamseeker"));
    }

    @Test
    public void testNullAndEmptyHandling() {
        TrackMetadataSanitizer.SanitizedMetadata nullResult = TrackMetadataSanitizer.sanitize((TrackInfo) null);
        assertNotNull(nullResult);
        assertEquals("", nullResult.cleanTitle);
        assertEquals("", nullResult.cleanArtist);
        assertTrue(nullResult.titleAliases.isEmpty());

        TrackMetadataSanitizer.SanitizedMetadata emptyResult = TrackMetadataSanitizer.sanitize("", "");
        assertNotNull(emptyResult);
        assertEquals("", emptyResult.cleanTitle);
        assertEquals("", emptyResult.cleanArtist);
    }
}
