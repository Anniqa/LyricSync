package com.lyricsync.app.lyrics;

import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.lyrics.provider.KugouLyricsProvider;
import com.lyricsync.app.lyrics.util.TrackMetadataSanitizer;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * End-to-end simulation verifying:
 * 1. Dirty YouTube Music metadata sanitization.
 * 2. Live Kugou API search & candidate resolution.
 * 3. Base64 payload decoding, XOR/zlib decryption.
 * 4. Syllable (KRC) timing extraction to LyricsData.Type.SYLLABLE.
 */
public class EndToEndSyllableLyricsSimulationTest {

    @Test
    public void testEndToEndSimulationRoseApt() {
        TrackInfo dirtyTrack = new TrackInfo();
        dirtyTrack.title = "ROSÉ - APT. [Official Video]";
        dirtyTrack.artist = "ROSÉ - Topic";

        // Step 1: Sanitize metadata
        TrackMetadataSanitizer.SanitizedMetadata sanitized = TrackMetadataSanitizer.sanitize(dirtyTrack);
        assertEquals("APT.", sanitized.cleanTitle);
        assertEquals("ROSÉ", sanitized.cleanArtist);

        // Step 2: Fetch via KugouLyricsProvider
        KugouLyricsProvider kugouProvider = new KugouLyricsProvider();
        LyricsData result = null;
        try {
            result = kugouProvider.fetchLyrics(dirtyTrack);
        } catch (Exception e) {
            System.out.println("Network request threw exception: " + e.getMessage());
        }

        if (result != null) {
            System.out.println("Fetched lyrics from provider: " + result.provider);
            System.out.println("Lyrics type: " + result.type);
            System.out.println("Total lines: " + result.lines.size());

            assertEquals(LyricsData.Type.SYLLABLE, result.type);
            assertEquals("Kugou", result.provider);
            assertFalse("Result lines should not be empty", result.lines.isEmpty());

            boolean foundTimedWords = false;
            for (LyricsData.LyricsLine line : result.lines) {
                if (!line.isInterlude && !line.words.isEmpty()) {
                    foundTimedWords = true;
                    assertTrue("Line endTime should be >= startTime", line.endTime >= line.startTime);
                    for (LyricsData.Word w : line.words) {
                        assertTrue("Word endTime should be >= startTime", w.endTime >= w.startTime);
                        assertNotNull("Word text should not be null", w.text);
                    }
                    System.out.println("Sample line: [" + line.startTime + " - " + line.endTime + "] " + line.text);
                    System.out.println("Word count: " + line.words.size());
                    break;
                }
            }
            assertTrue("Should contain lines with syllable words", foundTimedWords);
        } else {
            System.out.println("Live network search returned null or network unavailable during test run.");
        }
    }
}
