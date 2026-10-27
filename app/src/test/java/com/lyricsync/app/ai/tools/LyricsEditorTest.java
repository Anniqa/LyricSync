package com.lyricsync.app.ai.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.SyllableLyricsParser;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The AI's edit language: ops, atomicity, undo and the read view. */
public class LyricsEditorTest {

    private LyricsData lyrics;
    private LyricsEditor editor;

    @Before
    public void setUp() {
        lyrics = new LyricsData(LyricsData.Type.LINE);
        lyrics.lines.add(line(0, 2000, "kimi ga suki"));
        lyrics.lines.add(line(2000, 4000, "zutto issho"));
        lyrics.lines.add(line(4000, 6000, "sayonara"));
        editor = new LyricsEditor();
    }

    private static LyricsData.LyricsLine line(long start, long end, String text) {
        LyricsData.LyricsLine l = new LyricsData.LyricsLine(start, end, text);
        SyllableLyricsParser.splitLineIntoWords(l);
        return l;
    }

    private static JsonArray ops(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    /** The headline use case: a word-by-word gloss, one label per word. */
    @Test
    public void wordRomajiLabelsEachWord() {
        LyricsEditor.Result r = editor.apply(lyrics, ops(
                "[{\"op\":\"set\",\"line\":0,\"word_romaji\":[\"kamu\",\"\",\"suka\"],\"translation\":\"Aku suka kamu\"}]"));
        LyricsData.LyricsLine l = lyrics.lines.get(0);
        assertEquals("kamu", l.words.get(0).romanji);
        assertEquals("", l.words.get(1).romanji);
        assertEquals("suka", l.words.get(2).romanji);
        assertEquals("kamu suka", l.romanji);
        assertEquals("Aku suka kamu", l.translation);
        assertTrue(r.wroteRomaji);
        assertTrue(r.wroteTranslation);
    }

    @Test
    public void wrongWordCountIsRejectedWithAHint() {
        try {
            editor.apply(lyrics, ops("[{\"op\":\"set\",\"line\":0,\"word_romaji\":[\"a\",\"b\"]}]"));
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("3 word(s)"));
        }
    }

    @Test
    public void opsAreAllOrNothing() {
        try {
            editor.apply(lyrics, ops("[{\"op\":\"set\",\"line\":0,\"text\":\"changed\"},{\"op\":\"delete\",\"line\":9}]"));
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("ops[1]"));
        }
        assertEquals("kimi ga suki", lyrics.lines.get(0).text);
        assertEquals(0, editor.undoSteps(lyrics));
    }

    @Test
    public void insertDeleteShiftReplace() {
        editor.apply(lyrics, ops("["
                + "{\"op\":\"insert\",\"line\":3,\"text\":\"mata ne\"},"
                + "{\"op\":\"delete\",\"line\":1},"
                + "{\"op\":\"shift\",\"ms\":500},"
                + "{\"op\":\"replace\",\"find\":\"SUKI\",\"replace\":\"daisuki\"}]"));
        assertEquals(3, lyrics.lines.size());
        assertEquals("kimi ga daisuki", lyrics.lines.get(0).text);
        assertEquals(500, lyrics.lines.get(0).startTime);
        assertEquals(500, lyrics.lines.get(0).words.get(0).startTime);
        assertEquals("mata ne", lyrics.lines.get(2).text);
        assertEquals(6500, lyrics.lines.get(2).startTime);
    }

    @Test
    public void newTextDropsTheStaleReading() {
        editor.apply(lyrics, ops("[{\"op\":\"set\",\"line\":2,\"romaji\":\"sayounara\"}]"));
        editor.apply(lyrics, ops("[{\"op\":\"set\",\"line\":2,\"text\":\"mata ashita\"}]"));
        LyricsData.LyricsLine l = lyrics.lines.get(2);
        assertNull(l.romanji);
        assertEquals(2, l.words.size());
        assertNull(l.words.get(0).romanji);
    }

    @Test
    public void undoStepsBackAndAllRestoresTheOriginal() {
        editor.apply(lyrics, ops("[{\"op\":\"set\",\"line\":0,\"text\":\"one\"}]"));
        editor.apply(lyrics, ops("[{\"op\":\"set\",\"line\":0,\"text\":\"two\"}]"));
        editor.undo(lyrics, false);
        assertEquals("one", lyrics.lines.get(0).text);
        editor.apply(lyrics, ops("[{\"op\":\"delete\",\"line\":2}]"));
        editor.undo(lyrics, true);
        assertEquals("kimi ga suki", lyrics.lines.get(0).text);
        assertEquals(3, lyrics.lines.size());
        try {
            editor.undo(lyrics, false);
            fail();
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void historyIsPerLyrics() {
        editor.apply(lyrics, ops("[{\"op\":\"set\",\"line\":0,\"text\":\"x\"}]"));
        LyricsData other = new LyricsData(LyricsData.Type.LINE);
        other.lines.add(line(0, 1000, "a"));
        other.lines.add(line(1000, 2000, "b"));
        assertEquals(0, editor.undoSteps(other));
        editor.apply(other, ops("[{\"op\":\"set\",\"line\":0,\"text\":\"c\"}]"));
        assertEquals(1, editor.undoSteps(other));
        assertEquals(0, editor.undoSteps(lyrics));
    }

    @Test
    public void readShowsWordsAndPages() {
        editor.apply(lyrics, ops("[{\"op\":\"set\",\"line\":1,\"word_romaji\":[\"selalu\",\"bersama\"]}]"));
        JsonObject view = LyricsEditor.read(lyrics, 1, 1, true);
        assertEquals(3, view.get("total_lines").getAsInt());
        assertEquals(2, view.get("next_from").getAsInt());
        JsonObject l = view.getAsJsonArray("lines").get(0).getAsJsonObject();
        assertEquals("zutto issho", l.get("text").getAsString());
        assertEquals(2, l.getAsJsonArray("words").size());
        assertEquals("bersama", l.getAsJsonArray("word_romaji").get(1).getAsString());
        assertFalse(LyricsEditor.read(lyrics, 0, -1, false).getAsJsonArray("lines").get(0)
                .getAsJsonObject().has("words"));
    }
}
