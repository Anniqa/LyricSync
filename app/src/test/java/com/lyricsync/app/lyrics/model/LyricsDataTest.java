package com.lyricsync.app.lyrics.model;

import org.junit.Test;
import static org.junit.Assert.*;

public class LyricsDataTest {

    @Test
    public void testConstructors() {
        LyricsData defaultData = new LyricsData();
        assertNull(defaultData.type);
        assertNotNull(defaultData.lines);

        LyricsData typedData = new LyricsData(LyricsData.Type.SYLLABLE);
        assertEquals(LyricsData.Type.SYLLABLE, typedData.type);
        assertNotNull(typedData.lines);

        LyricsData lineData = new LyricsData(LyricsData.Type.LINE);
        assertEquals(LyricsData.Type.LINE, lineData.type);
    }
}
