package com.lyricsync.app.ai.util;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

/** The follow-up block is split off the answer and never flashes while streaming. */
public class FollowUpsTest {

    @Test
    public void splitsAnswerAndSuggestions() {
        FollowUps.Reply r = FollowUps.split("Done, romaji is on.\n\n[[followups]]\n- Add the meaning\n* Hide the kanji\n1. Explain line 3\n- one more");
        assertEquals("Done, romaji is on.", r.text);
        assertEquals(Arrays.asList("Add the meaning", "Hide the kanji", "Explain line 3"), r.suggestions);
    }

    @Test
    public void replyWithoutBlockIsUnchanged() {
        FollowUps.Reply r = FollowUps.split("  Just an answer. ");
        assertEquals("Just an answer.", r.text);
        assertEquals(Collections.emptyList(), r.suggestions);
    }

    @Test
    public void streamingHidesBlockAndHalfTypedMarker() {
        assertEquals("Answer\n", FollowUps.visible("Answer\n[[followups]]\n- a"));
        assertEquals("Answer\n", FollowUps.visible("Answer\n[[follo"));
        assertEquals("a [[link]] b", FollowUps.visible("a [[link]] b"));
    }
}
