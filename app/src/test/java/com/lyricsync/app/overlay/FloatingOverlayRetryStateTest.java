package com.lyricsync.app.overlay;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Covers the retry state machine the overlay's "no lyrics found" empty state drives.
 * Lyric retrieval itself is the provider chain's job, so the states here are only
 * idle / standard-retrying / loaded / failed.
 */
public class FloatingOverlayRetryStateTest {

    @Test
    public void testStateConstantsParity() {
        assertEquals("RETRY_STATE_IDLE must equal 0", 0, FloatingOverlayService.RETRY_STATE_IDLE);
        assertEquals("RETRY_STATE_STANDARD must equal 1", 1, FloatingOverlayService.RETRY_STATE_STANDARD);

        assertEquals("STATE_IDLE alias", FloatingOverlayService.RETRY_STATE_IDLE, FloatingOverlayService.STATE_IDLE);
        assertEquals("STATE_STANDARD_RETRYING alias",
                FloatingOverlayService.RETRY_STATE_STANDARD, FloatingOverlayService.STATE_STANDARD_RETRYING);

        assertEquals("STATE_LOADED must equal 3", 3, FloatingOverlayService.STATE_LOADED);
        assertEquals("STATE_FAILED must equal 4", 4, FloatingOverlayService.STATE_FAILED);
    }

    @Test
    public void testTerminalStatesAreDistinct() {
        int[] states = {
                FloatingOverlayService.STATE_IDLE,
                FloatingOverlayService.STATE_STANDARD_RETRYING,
                FloatingOverlayService.STATE_LOADED,
                FloatingOverlayService.STATE_FAILED,
        };
        for (int i = 0; i < states.length; i++) {
            for (int j = i + 1; j < states.length; j++) {
                if (states[i] == states[j]) {
                    throw new AssertionError("retry states must be distinct: " + states[i]);
                }
            }
        }
    }
}
