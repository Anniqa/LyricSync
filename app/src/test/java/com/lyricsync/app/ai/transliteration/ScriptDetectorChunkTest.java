package com.lyricsync.app.ai.transliteration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * A run without word spaces has to stay breakable: the overlay renders one view per
 * unit, so an unchunked Japanese line would measure wider than the overlay and be
 * sliced at both edges instead of wrapping.
 */
public class ScriptDetectorChunkTest {

    @Test
    public void japaneseLineIsChunkedSoItCanWrap() {
        List<String> chunks = ScriptDetector.chunkByCodePoints("僕らの歩き慣れていた道", 2);
        assertEquals(6, chunks.size());
        assertEquals("僕ら", chunks.get(0));
        assertEquals("の歩", chunks.get(1));
        assertEquals("道", chunks.get(chunks.size() - 1));
        assertEquals("僕らの歩き慣れていた道", String.join("", chunks));
    }

    @Test
    public void emptyAndNullProduceNoChunks() {
        assertTrue(ScriptDetector.chunkByCodePoints(null, 2).isEmpty());
        assertTrue(ScriptDetector.chunkByCodePoints("", 2).isEmpty());
    }

    @Test
    public void chunkSizeIsClampedToAtLeastOneCharacter() {
        List<String> chunks = ScriptDetector.chunkByCodePoints("あい", 0);
        assertEquals(2, chunks.size());
        assertEquals("あ", chunks.get(0));
    }

    @Test
    public void supplementaryCharactersAreNeverSplitInHalf() {
        List<String> chunks = ScriptDetector.chunkByCodePoints("\uD83D\uDE00\uD83D\uDE01", 2);
        assertEquals(1, chunks.size());
        assertEquals(2, chunks.get(0).codePointCount(0, chunks.get(0).length()));
    }

    @Test
    public void scriptClassificationDrivesTheChunkingDecision() {
        assertTrue(ScriptDetector.isNonLatin("僕ら"));
        assertTrue(ScriptDetector.isNonLatin("インフェルノ"));
        assertFalse(ScriptDetector.isNonLatin("Inferno"));
    }
}
