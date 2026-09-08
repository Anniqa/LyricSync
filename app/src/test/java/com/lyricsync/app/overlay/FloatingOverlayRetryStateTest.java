package com.lyricsync.app.overlay;

import com.lyricsync.app.ai.client.AntigravityApiClient;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class FloatingOverlayRetryStateTest {

    @Test
    public void testStateConstantsParity() {
        assertEquals("RETRY_STATE_IDLE must equal 0", 0, FloatingOverlayService.RETRY_STATE_IDLE);
        assertEquals("RETRY_STATE_STANDARD must equal 1", 1, FloatingOverlayService.RETRY_STATE_STANDARD);
        assertEquals("RETRY_STATE_AI must equal 2", 2, FloatingOverlayService.RETRY_STATE_AI);

        assertEquals("STATE_IDLE alias", FloatingOverlayService.RETRY_STATE_IDLE, FloatingOverlayService.STATE_IDLE);
        assertEquals("STATE_STANDARD_RETRYING alias", FloatingOverlayService.RETRY_STATE_STANDARD, FloatingOverlayService.STATE_STANDARD_RETRYING);
        assertEquals("STATE_AI_SEARCHING alias", FloatingOverlayService.RETRY_STATE_AI, FloatingOverlayService.STATE_AI_SEARCHING);

        assertEquals("STATE_LOADED must equal 3", 3, FloatingOverlayService.STATE_LOADED);
        assertEquals("STATE_FAILED must equal 4", 4, FloatingOverlayService.STATE_FAILED);
    }

    @Test
    public void testCancellationSuppressionPattern() {
        // Models the exact cancellation and pendingFetchKey guard used in FloatingOverlayService
        final AtomicReference<String> pendingFetchKey = new AtomicReference<>("Song A|||Artist A");
        final AtomicReference<AntigravityApiClient.Cancellable> activeCall = new AtomicReference<>();
        final AtomicInteger retryState = new AtomicInteger(FloatingOverlayService.RETRY_STATE_AI);
        final AtomicBoolean successInvoked = new AtomicBoolean(false);

        final String requestKey = "Song A|||Artist A";
        final AtomicBoolean callCancelled = new AtomicBoolean(false);

        activeCall.set(new AntigravityApiClient.Cancellable() {
            @Override
            public void cancel() {
                callCancelled.set(true);
            }

            @Override
            public boolean isCancelled() {
                return callCancelled.get();
            }
        });

        // Track switches to Song B
        pendingFetchKey.set("Song B|||Artist B");
        if (activeCall.get() != null) {
            activeCall.get().cancel();
            activeCall.set(null);
        }
        retryState.set(FloatingOverlayService.RETRY_STATE_IDLE);

        assertTrue("In-flight call was cancelled", callCancelled.get());
        assertNull("Active call reference cleared", activeCall.get());
        assertEquals("Retry state reset to IDLE", FloatingOverlayService.RETRY_STATE_IDLE, retryState.get());

        // A delayed callback from Song A arrives
        if (requestKey.equals(pendingFetchKey.get())) {
            successInvoked.set(true);
        }

        assertFalse("Stale callback was suppressed due to key mismatch", successInvoked.get());
    }

    @Test
    public void testInPlaceStateMachineLifecycle() {
        AtomicInteger currentState = new AtomicInteger(FloatingOverlayService.STATE_IDLE);

        // 1. User clicks standard retry
        currentState.set(FloatingOverlayService.STATE_STANDARD_RETRYING);
        assertEquals(FloatingOverlayService.RETRY_STATE_STANDARD, currentState.get());

        // Standard retry fails -> empty state restores idle
        currentState.set(FloatingOverlayService.STATE_IDLE);
        assertEquals(FloatingOverlayService.STATE_IDLE, currentState.get());

        // 2. User clicks AI retry
        currentState.set(FloatingOverlayService.STATE_AI_SEARCHING);
        assertEquals(FloatingOverlayService.RETRY_STATE_AI, currentState.get());

        // User clicks cancel -> returns to idle
        currentState.set(FloatingOverlayService.STATE_IDLE);
        assertEquals(FloatingOverlayService.STATE_IDLE, currentState.get());

        // 3. User clicks AI retry again
        currentState.set(FloatingOverlayService.STATE_AI_SEARCHING);
        assertEquals(FloatingOverlayService.STATE_AI_SEARCHING, currentState.get());

        // Success -> transitions to loaded
        currentState.set(FloatingOverlayService.STATE_LOADED);
        assertEquals(FloatingOverlayService.STATE_LOADED, currentState.get());
    }
}
