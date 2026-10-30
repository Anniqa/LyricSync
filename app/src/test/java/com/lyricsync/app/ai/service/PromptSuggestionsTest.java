package com.lyricsync.app.ai.service;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Suggestion cards follow the song: romaji only for non-Latin lyrics, no translate card once translated. */
public class PromptSuggestionsTest {

    private static List<String> ids(String lang, boolean lyrics, boolean nonLatin, boolean translated, boolean playing) {
        PromptSuggestions.Song s = new PromptSuggestions.Song();
        s.hasLyrics = lyrics;
        s.nonLatin = nonLatin;
        s.hasTranslation = translated;
        s.playing = playing;
        List<String> out = new ArrayList<>();
        for (PromptSuggestions.Card c : PromptSuggestions.forSong(lang, s)) out.add(c.id);
        return out;
    }

    @Test
    public void nonLatinSongLeadsWithReadingCards() {
        List<String> ids = ids("id", true, true, false, true);
        assertEquals("romaji_meaning", ids.get(0));
        assertTrue(ids.contains("word_gloss"));
        assertTrue(ids.contains("latin_only"));
        assertTrue(ids.contains("this_line"));
        assertFalse(ids.contains("sing_along"));
    }

    @Test
    public void latinTranslatedPausedSong() {
        List<String> ids = ids("en", true, false, true, false);
        assertTrue(ids.contains("sing_along"));
        assertFalse(ids.contains("romaji_meaning"));
        assertFalse(ids.contains("translate"));
        assertFalse(ids.contains("this_line"));
    }

    @Test
    public void noLyricsOffersOnlyTalkCards() {
        assertEquals(2, ids("id", false, false, false, true).size());
    }

    @Test
    public void cardsAreUniqueAndComplete() {
        for (String lang : new String[]{"id", "en"}) {
            PromptSuggestions.Song s = new PromptSuggestions.Song();
            s.hasLyrics = true;
            s.nonLatin = true;
            s.playing = true;
            HashSet<String> seen = new HashSet<>();
            for (PromptSuggestions.Card c : PromptSuggestions.forSong(lang, s)) {
                assertTrue(c.id, seen.add(c.id));
                assertFalse(c.title.isEmpty() || c.description.isEmpty() || c.prompt.isEmpty());
            }
        }
    }
}
