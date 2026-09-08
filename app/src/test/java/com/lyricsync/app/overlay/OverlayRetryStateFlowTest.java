package com.lyricsync.app.overlay;

import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.ai.service.AiLyricsAgentService;
import com.lyricsync.app.lyrics.cache.LyricsCacheManager;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.lyrics.provider.KugouLyricsProvider;
import com.lyricsync.app.lyrics.provider.LRCLIBProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * End-to-end integration and verification tests for the AI-assisted syllable lyrics retry UX.
 *
 * Verifies:
 * 1. Overlay state machine flow (STATE_IDLE -> STATE_STANDARD_RETRYING -> STATE_AI_SEARCHING -> STATE_LOADED / STATE_FAILED).
 * 2. Strict Cover Song Rule: Cover tracks query strictly for the cover performer and NEVER fall back to or query the original artist.
 * 3. Syllable priority: authentic KRC syllable retrieval takes priority over line-synced LRC.
 * 4. Cancellation on track switch: when track changes during AI retrieval, active call is cancelled and state cleanly resets to IDLE.
 * 5. Standard retry state preservation: STATE_STANDARD_RETRYING is preserved during standard fetch and resets cleanly on error.
 */
public class OverlayRetryStateFlowTest {

    // --- Fake Test Providers & Cache ---

    static class FakeKugouProvider extends KugouLyricsProvider {
        final List<TrackInfo> queries = Collections.synchronizedList(new ArrayList<>());
        volatile LyricsData lyricsToReturn;
        volatile Exception exceptionToThrow;
        volatile CountDownLatch fetchStartedLatch;
        volatile CountDownLatch unblockLatch;

        FakeKugouProvider() {
            super(null);
        }

        @Override
        public LyricsData fetchLyrics(TrackInfo track) throws Exception {
            queries.add(track);
            if (fetchStartedLatch != null) {
                fetchStartedLatch.countDown();
            }
            if (unblockLatch != null) {
                unblockLatch.await(5, TimeUnit.SECONDS);
            }
            if (exceptionToThrow != null) {
                throw exceptionToThrow;
            }
            return lyricsToReturn;
        }
    }

    static class FakeLRCLIBProvider extends LRCLIBProvider {
        final List<TrackInfo> queries = Collections.synchronizedList(new ArrayList<>());
        volatile LyricsData lyricsToReturn;
        volatile Exception exceptionToThrow;

        @Override
        public LyricsData fetchLyrics(TrackInfo track) throws Exception {
            queries.add(track);
            if (exceptionToThrow != null) {
                throw exceptionToThrow;
            }
            return lyricsToReturn;
        }
    }

    static class FakeLyricsCacheManager extends LyricsCacheManager {
        TrackInfo cachedTrack;
        LyricsData cachedLyrics;

        FakeLyricsCacheManager() {
            super();
        }

        @Override
        public void cache(TrackInfo track, LyricsData lyrics) {
            this.cachedTrack = track;
            this.cachedLyrics = lyrics;
        }

        @Override
        public LyricsData getCached(TrackInfo track) {
            return cachedLyrics;
        }
    }

    /**
     * Overlay controller test harness mirroring FloatingOverlayService retry lifecycle,
     * view visibility flags, and asynchronous callback dispatch.
     */
    static class TestOverlayController {
        private int currentRetryState = FloatingOverlayService.STATE_IDLE;
        private TrackInfo currentTrack;
        private LyricsData currentLyrics;
        private volatile String pendingFetchKey = null;
        private AntigravityApiClient.Cancellable activeAiLyricsCall;
        private final AiLyricsAgentService aiLyricsAgentService;
        private final LyricsCacheManager cacheManager;

        // UI state representations matching FloatingOverlayService
        private boolean retryActionsVisible = true;
        private boolean aiProgressVisible = false;
        private boolean retryButtonsEnabled = true;
        private String emptyDescText = "";
        private String progressStatusText = "";

        private final List<Integer> stateHistory = Collections.synchronizedList(new ArrayList<>());
        private final List<Integer> observedAiSteps = Collections.synchronizedList(new ArrayList<>());
        private final List<String> observedAiStepMessages = Collections.synchronizedList(new ArrayList<>());

        public TestOverlayController(AiLyricsAgentService aiLyricsAgentService, LyricsCacheManager cacheManager) {
            this.aiLyricsAgentService = aiLyricsAgentService;
            this.cacheManager = cacheManager;
            setRetryState(FloatingOverlayService.STATE_IDLE);
        }

        public synchronized void setRetryState(int state) {
            this.currentRetryState = state;
            this.stateHistory.add(state);
            switch (state) {
                case FloatingOverlayService.STATE_AI_SEARCHING:
                    this.retryActionsVisible = false;
                    this.aiProgressVisible = true;
                    this.progressStatusText = "Searching syllable lyrics...";
                    break;
                case FloatingOverlayService.STATE_STANDARD_RETRYING:
                    this.retryActionsVisible = true;
                    this.aiProgressVisible = false;
                    this.retryButtonsEnabled = false;
                    break;
                case FloatingOverlayService.STATE_LOADED:
                    this.retryActionsVisible = false;
                    this.aiProgressVisible = false;
                    break;
                case FloatingOverlayService.STATE_FAILED:
                case FloatingOverlayService.STATE_IDLE:
                default:
                    this.retryActionsVisible = true;
                    this.aiProgressVisible = false;
                    this.retryButtonsEnabled = true;
                    break;
            }
        }

        public synchronized int getRetryState() {
            return currentRetryState;
        }

        public synchronized TrackInfo getCurrentTrack() {
            return currentTrack;
        }

        public synchronized void setCurrentTrack(TrackInfo track) {
            this.currentTrack = track;
        }

        public synchronized LyricsData getCurrentLyrics() {
            return currentLyrics;
        }

        public synchronized AntigravityApiClient.Cancellable getActiveAiLyricsCall() {
            return activeAiLyricsCall;
        }

        public synchronized boolean isRetryActionsVisible() {
            return retryActionsVisible;
        }

        public synchronized boolean isAiProgressVisible() {
            return aiProgressVisible;
        }

        public synchronized boolean isRetryButtonsEnabled() {
            return retryButtonsEnabled;
        }

        public synchronized String getEmptyDescText() {
            return emptyDescText;
        }

        public synchronized String getProgressStatusText() {
            return progressStatusText;
        }

        public synchronized List<Integer> getStateHistory() {
            return new ArrayList<>(stateHistory);
        }

        public synchronized List<Integer> getObservedAiSteps() {
            return new ArrayList<>(observedAiSteps);
        }

        public synchronized List<String> getObservedAiStepMessages() {
            return new ArrayList<>(observedAiStepMessages);
        }

        public synchronized void cancelAiLyricsRetrieval() {
            if (activeAiLyricsCall != null) {
                activeAiLyricsCall.cancel();
                activeAiLyricsCall = null;
            }
            if (currentRetryState == FloatingOverlayService.RETRY_STATE_AI) {
                setRetryState(FloatingOverlayService.RETRY_STATE_IDLE);
            }
        }

        public synchronized void onTrackChanged(TrackInfo newTrack) {
            this.currentTrack = newTrack;
            this.currentLyrics = null;
            fetchLyrics(newTrack);
        }

        public synchronized void fetchLyrics(TrackInfo track) {
            final String fetchKey = (track != null && track.isValid()) ? track.title + "|||" + track.artist : null;
            this.pendingFetchKey = fetchKey;
            cancelAiLyricsRetrieval();
        }

        public synchronized void triggerStandardRetry() {
            if (currentTrack == null || !currentTrack.isValid()) return;
            setRetryState(FloatingOverlayService.STATE_STANDARD_RETRYING);
            fetchLyrics(currentTrack);
        }

        public synchronized void onStandardFetchSuccess(LyricsData lyrics) {
            if (currentRetryState == FloatingOverlayService.STATE_STANDARD_RETRYING) {
                this.currentLyrics = lyrics;
                setRetryState(FloatingOverlayService.STATE_LOADED);
            }
        }

        public synchronized void onStandardFetchFailed(String error) {
            if (currentRetryState == FloatingOverlayService.STATE_STANDARD_RETRYING) {
                this.emptyDescText = error != null ? error : "No lyrics found";
                setRetryState(FloatingOverlayService.STATE_IDLE);
            }
        }

        public synchronized AntigravityApiClient.Cancellable triggerAiLyricsRetrieval(final CountDownLatch completionLatch) {
            if (currentTrack == null || !currentTrack.isValid()) return null;
            cancelAiLyricsRetrieval();
            final TrackInfo track = currentTrack;
            final String fetchKey = track.title + "|||" + track.artist;
            this.pendingFetchKey = fetchKey;

            setRetryState(FloatingOverlayService.STATE_AI_SEARCHING);
            this.emptyDescText = "Searching syllable lyrics...";

            activeAiLyricsCall = aiLyricsAgentService.retrieveLyrics(track, new AiLyricsAgentService.AiLyricsCallback() {
                @Override
                public void onStepChanged(int stepCode, String message) {
                    synchronized (TestOverlayController.this) {
                        if (!fetchKey.equals(pendingFetchKey)) return;
                        observedAiSteps.add(stepCode);
                        observedAiStepMessages.add(message);
                        if (message != null && !message.isEmpty()) {
                            progressStatusText = message;
                        } else if (stepCode == AiLyricsAgentService.STEP_FETCHING_SYLLABLE) {
                            progressStatusText = "Searching syllable lyrics...";
                        } else if (stepCode == AiLyricsAgentService.STEP_FALLBACK_LRC) {
                            progressStatusText = "Falling back to line-synced lyrics...";
                        }
                    }
                }

                @Override
                public void onSuccess(LyricsData lyrics) {
                    try {
                        synchronized (TestOverlayController.this) {
                            if (!fetchKey.equals(pendingFetchKey)) return;
                            activeAiLyricsCall = null;
                            if (lyrics == null || lyrics.isEmpty()) {
                                setRetryState(FloatingOverlayService.STATE_FAILED);
                                emptyDescText = "No lyrics found.";
                                return;
                            }
                            currentLyrics = lyrics;
                            setRetryState(FloatingOverlayService.STATE_LOADED);
                        }
                    } finally {
                        if (completionLatch != null) {
                            completionLatch.countDown();
                        }
                    }
                }

                @Override
                public void onError(String message) {
                    try {
                        synchronized (TestOverlayController.this) {
                            if (!fetchKey.equals(pendingFetchKey)) return;
                            activeAiLyricsCall = null;
                            setRetryState(FloatingOverlayService.STATE_FAILED);
                            emptyDescText = message != null && !message.isEmpty() ? message : "Error retrieving lyrics";
                        }
                    } finally {
                        if (completionLatch != null) {
                            completionLatch.countDown();
                        }
                    }
                }
            });
            return activeAiLyricsCall;
        }
    }

    private FakeKugouProvider fakeKugou;
    private FakeLRCLIBProvider fakeLrclib;
    private FakeLyricsCacheManager fakeCache;
    private ExecutorService executor;
    private AiLyricsAgentService aiLyricsAgentService;
    private TestOverlayController controller;

    @Before
    public void setUp() {
        fakeKugou = new FakeKugouProvider();
        fakeLrclib = new FakeLRCLIBProvider();
        fakeCache = new FakeLyricsCacheManager();
        executor = Executors.newFixedThreadPool(2);
        aiLyricsAgentService = new AiLyricsAgentService(fakeKugou, fakeLrclib, fakeCache, executor);
        controller = new TestOverlayController(aiLyricsAgentService, fakeCache);
    }

    @After
    public void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * Test 1: Complete AI retry state progression from IDLE through
     * DISAMBIGUATING -> FETCHING_SYLLABLE -> LOADED.
     * Verifies authentic KRC syllable lyrics are prioritized over line LRC and properly cached.
     */
    @Test
    public void testCompleteAiRetryStateFlow_Success() throws Exception {
        TrackInfo track = new TrackInfo();
        track.title = "Sparkle";
        track.artist = "RADWIMPS";
        track.duration = 540000;

        LyricsData syllableLyrics = new LyricsData(LyricsData.Type.SYLLABLE);
        syllableLyrics.provider = "Kugou";
        syllableLyrics.lines.add(new LyricsData.LyricsLine(0, 1500, "Mada kono sekai wa"));
        syllableLyrics.lines.add(new LyricsData.LyricsLine(1500, 3200, "Boku wo kainarashitai mitai da"));
        fakeKugou.lyricsToReturn = syllableLyrics;

        LyricsData lrcLyrics = new LyricsData(LyricsData.Type.LINE);
        lrcLyrics.provider = "LRCLIB";
        lrcLyrics.lines.add(new LyricsData.LyricsLine(0, 1500, "Mada kono sekai wa"));
        fakeLrclib.lyricsToReturn = lrcLyrics;

        controller.setCurrentTrack(track);
        assertEquals("Initial state must be IDLE", FloatingOverlayService.STATE_IDLE, controller.getRetryState());
        assertTrue("Retry actions container must be visible in IDLE", controller.isRetryActionsVisible());
        assertFalse("AI progress container must be hidden in IDLE", controller.isAiProgressVisible());
        assertTrue("Retry buttons must be enabled in IDLE", controller.isRetryButtonsEnabled());

        CountDownLatch completionLatch = new CountDownLatch(1);
        controller.triggerAiLyricsRetrieval(completionLatch);

        // Immediate state after trigger
        assertEquals("State must transition to STATE_AI_SEARCHING",
                FloatingOverlayService.STATE_AI_SEARCHING, controller.getRetryState());
        assertFalse("Retry actions container hidden during AI search", controller.isRetryActionsVisible());
        assertTrue("AI progress container visible during AI search", controller.isAiProgressVisible());

        // Wait for background retrieval to finish
        assertTrue("AI retrieval should complete within timeout", completionLatch.await(5, TimeUnit.SECONDS));

        // Terminal state assertions
        assertEquals("State must transition to STATE_LOADED on success",
                FloatingOverlayService.STATE_LOADED, controller.getRetryState());
        assertFalse("Retry actions container hidden in STATE_LOADED", controller.isRetryActionsVisible());
        assertFalse("AI progress container hidden in STATE_LOADED", controller.isAiProgressVisible());
        assertNull("Active call handle cleared after completion", controller.getActiveAiLyricsCall());

        // Verify syllable lyrics received
        assertNotNull("Current lyrics must be populated", controller.getCurrentLyrics());
        assertEquals("Syllable KRC lyrics must be prioritized over LRC",
                LyricsData.Type.SYLLABLE, controller.getCurrentLyrics().type);
        assertEquals(2, controller.getCurrentLyrics().lines.size());
        assertEquals("Kugou", controller.getCurrentLyrics().provider);

        // Verify LRCLIB was never queried because Kugou returned authentic syllable lyrics
        assertEquals(1, fakeKugou.queries.size());
        assertEquals(0, fakeLrclib.queries.size());

        // Step progression assertions: DISAMBIGUATING -> FETCHING_SYLLABLE (no FALLBACK_LRC)
        List<Integer> steps = controller.getObservedAiSteps();
        assertTrue("Step list must contain STEP_DISAMBIGUATING",
                steps.contains(AiLyricsAgentService.STEP_DISAMBIGUATING));
        assertTrue("Step list must contain STEP_FETCHING_SYLLABLE",
                steps.contains(AiLyricsAgentService.STEP_FETCHING_SYLLABLE));
        assertFalse("Step list must NOT contain STEP_FALLBACK_LRC when syllable lyrics found",
                steps.contains(AiLyricsAgentService.STEP_FALLBACK_LRC));

        // Verify caching under original track
        assertNotNull("Lyrics must be cached in LyricsCacheManager", fakeCache.cachedLyrics);
        assertEquals(LyricsData.Type.SYLLABLE, fakeCache.cachedLyrics.type);
        assertEquals(track, fakeCache.cachedTrack);

        // Verify overall state history: IDLE -> AI_SEARCHING -> LOADED
        List<Integer> history = controller.getStateHistory();
        assertTrue("State history must record progression", history.size() >= 3);
        assertEquals(Integer.valueOf(FloatingOverlayService.STATE_IDLE), history.get(0));
        assertEquals(Integer.valueOf(FloatingOverlayService.STATE_AI_SEARCHING), history.get(1));
        assertEquals(Integer.valueOf(FloatingOverlayService.STATE_LOADED), history.get(history.size() - 1));
    }

    /**
     * Test 2: Strict Cover Song Rule.
     * Simulates cover track retry, verifies performer is queried and original artist is NEVER queried
     * on any provider (strict performer isolation with no original-artist fallback).
     */
    @Test
    public void testCoverSongStrictPerformerIsolation_NoOriginalFallback() throws Exception {
        TrackInfo coverTrack = new TrackInfo();
        coverTrack.title = "Bohemian Rhapsody (Cover by Marc Martel)";
        coverTrack.artist = "Queen";
        coverTrack.duration = 354000;

        // Kugou has no syllable lyrics for this cover
        fakeKugou.lyricsToReturn = null;

        // LRCLIB has synced line lyrics for Marc Martel's cover
        LyricsData marcMartelLyrics = new LyricsData(LyricsData.Type.LINE);
        marcMartelLyrics.provider = "LRCLIB";
        marcMartelLyrics.lines.add(new LyricsData.LyricsLine(0, 2000, "Is this the real life?"));
        marcMartelLyrics.lines.add(new LyricsData.LyricsLine(2000, 4500, "Is this just fantasy?"));
        fakeLrclib.lyricsToReturn = marcMartelLyrics;

        controller.setCurrentTrack(coverTrack);
        CountDownLatch completionLatch = new CountDownLatch(1);
        controller.triggerAiLyricsRetrieval(completionLatch);

        assertTrue("Retrieval should complete within timeout", completionLatch.await(5, TimeUnit.SECONDS));

        // State transitions to LOADED with Marc Martel lyrics
        assertEquals(FloatingOverlayService.STATE_LOADED, controller.getRetryState());
        assertNotNull(controller.getCurrentLyrics());
        assertEquals(LyricsData.Type.LINE, controller.getCurrentLyrics().type);
        assertEquals("LRCLIB", controller.getCurrentLyrics().provider);

        // Step progression: DISAMBIGUATING -> FETCHING_SYLLABLE -> FALLBACK_LRC
        List<Integer> steps = controller.getObservedAiSteps();
        assertEquals(3, steps.size());
        assertEquals(AiLyricsAgentService.STEP_DISAMBIGUATING, (int) steps.get(0));
        assertEquals(AiLyricsAgentService.STEP_FETCHING_SYLLABLE, (int) steps.get(1));
        assertEquals(AiLyricsAgentService.STEP_FALLBACK_LRC, (int) steps.get(2));

        // Verify Kugou was queried for Marc Martel, NOT Queen
        assertEquals(1, fakeKugou.queries.size());
        assertEquals("Marc Martel", fakeKugou.queries.get(0).artist);
        assertEquals("Bohemian Rhapsody", fakeKugou.queries.get(0).title);

        // Verify LRCLIB was queried for Marc Martel, NOT Queen
        assertEquals(1, fakeLrclib.queries.size());
        assertEquals("Marc Martel", fakeLrclib.queries.get(0).artist);
        assertEquals("Bohemian Rhapsody", fakeLrclib.queries.get(0).title);

        // STRICT COVER RULE VERIFICATION: Original artist "Queen" must NEVER be queried on any provider
        for (TrackInfo query : fakeKugou.queries) {
            assertNotEquals("Kugou must never query original artist 'Queen'", "Queen", query.artist);
            assertFalse("Kugou artist must not contain original artist", query.artist.equalsIgnoreCase("Queen"));
        }
        for (TrackInfo query : fakeLrclib.queries) {
            assertNotEquals("LRCLIB must never query original artist 'Queen'", "Queen", query.artist);
            assertFalse("LRCLIB artist must not contain original artist", query.artist.equalsIgnoreCase("Queen"));
        }

        // Verify cached under the original track
        assertNotNull(fakeCache.cachedLyrics);
        assertEquals(coverTrack, fakeCache.cachedTrack);
    }

    /**
     * Test 3: Cancellation on track switch.
     * Simulates in-flight AI retrieval when a new track notification arrives;
     * verifies immediate cancellation of active call, clean reset to IDLE, and suppression of delayed callbacks.
     */
    @Test
    public void testAiRetryTrackSwitchCancellation() throws Exception {
        TrackInfo trackA = new TrackInfo();
        trackA.title = "Song A";
        trackA.artist = "Artist A";
        trackA.duration = 200000;

        TrackInfo trackB = new TrackInfo();
        trackB.title = "Song B";
        trackB.artist = "Artist B";
        trackB.duration = 240000;

        // Block Kugou fetch to simulate slow network call
        fakeKugou.fetchStartedLatch = new CountDownLatch(1);
        fakeKugou.unblockLatch = new CountDownLatch(1);

        LyricsData trackALyrics = new LyricsData(LyricsData.Type.SYLLABLE);
        trackALyrics.lines.add(new LyricsData.LyricsLine(0, 1000, "Song A delayed lyrics"));
        fakeKugou.lyricsToReturn = trackALyrics;

        controller.setCurrentTrack(trackA);
        AntigravityApiClient.Cancellable activeCall = controller.triggerAiLyricsRetrieval(null);

        // Wait until fetch starts inside provider thread
        assertTrue("Provider fetch should begin", fakeKugou.fetchStartedLatch.await(3, TimeUnit.SECONDS));

        // In-flight assertions
        assertEquals(FloatingOverlayService.STATE_AI_SEARCHING, controller.getRetryState());
        assertNotNull(controller.getActiveAiLyricsCall());
        assertFalse("Active call must not be cancelled while in flight", activeCall.isCancelled());

        // Simulate new track arrives from MediaSessionTracker
        controller.onTrackChanged(trackB);

        // Immediate cancellation assertions
        assertTrue("Active call handle must be cancelled immediately on track change", activeCall.isCancelled());
        assertNull("Active call reference must be cleared", controller.getActiveAiLyricsCall());
        assertEquals("State must cleanly reset to IDLE", FloatingOverlayService.STATE_IDLE, controller.getRetryState());
        assertTrue("Retry actions container must be visible after reset", controller.isRetryActionsVisible());
        assertFalse("AI progress container must be hidden after reset", controller.isAiProgressVisible());
        assertTrue("Retry buttons must be enabled after reset", controller.isRetryButtonsEnabled());

        // Unblock provider background thread
        fakeKugou.unblockLatch.countDown();

        // Allow background thread to complete
        Thread.sleep(150);

        // Verify delayed response from Song A was completely suppressed
        assertEquals("State must remain IDLE (not overridden to LOADED by late Track A response)",
                FloatingOverlayService.STATE_IDLE, controller.getRetryState());
        assertNull("Current lyrics must NOT be populated with Track A's lyrics", controller.getCurrentLyrics());
        assertNull("Cache must NOT be populated by cancelled Track A fetch", fakeCache.cachedLyrics);
    }

    /**
     * Test 4: Standard retry state preservation and failure handling.
     * Tests that STATE_STANDARD_RETRYING is preserved during fetch (not overridden to IDLE
     * by cancelAiLyricsRetrieval), retry buttons are disabled, and error transitions back to IDLE.
     */
    @Test
    public void testStandardRetryStatePreservationAndFailure() {
        TrackInfo track = new TrackInfo();
        track.title = "Standard Song";
        track.artist = "Standard Artist";
        track.duration = 180000;

        controller.setCurrentTrack(track);
        assertEquals(FloatingOverlayService.STATE_IDLE, controller.getRetryState());
        assertTrue(controller.isRetryButtonsEnabled());

        // Step 1: Trigger standard retry
        controller.triggerStandardRetry();

        // Step 2: Verify STATE_STANDARD_RETRYING is active and buttons are disabled
        assertEquals("State must be STATE_STANDARD_RETRYING",
                FloatingOverlayService.STATE_STANDARD_RETRYING, controller.getRetryState());
        assertFalse("Retry buttons must be disabled during standard fetch", controller.isRetryButtonsEnabled());
        assertTrue("Retry container must remain visible during standard fetch", controller.isRetryActionsVisible());
        assertFalse("AI progress container must be hidden during standard fetch", controller.isAiProgressVisible());

        // Step 3: Inside fetchLyrics, cancelAiLyricsRetrieval() is called.
        // Verify STATE_STANDARD_RETRYING is strictly preserved and NOT reset to IDLE!
        controller.cancelAiLyricsRetrieval();
        assertEquals("STATE_STANDARD_RETRYING must be preserved during fetch",
                FloatingOverlayService.STATE_STANDARD_RETRYING, controller.getRetryState());

        // Step 4: Standard fetch fails -> transitions back to IDLE
        controller.onStandardFetchFailed("No lyrics found");
        assertEquals("State must return to STATE_IDLE on failure",
                FloatingOverlayService.STATE_IDLE, controller.getRetryState());
        assertTrue("Retry buttons must be re-enabled on failure", controller.isRetryButtonsEnabled());
        assertTrue("Retry actions container must be visible on failure", controller.isRetryActionsVisible());
        assertEquals("No lyrics found", controller.getEmptyDescText());

        // Step 5: Subsequent standard retry succeeds -> transitions to STATE_LOADED
        controller.triggerStandardRetry();
        assertEquals(FloatingOverlayService.STATE_STANDARD_RETRYING, controller.getRetryState());

        LyricsData standardLyrics = new LyricsData(LyricsData.Type.LINE);
        standardLyrics.provider = "LRCLIB";
        standardLyrics.lines.add(new LyricsData.LyricsLine(0, 1000, "Standard line lyrics"));

        controller.onStandardFetchSuccess(standardLyrics);
        assertEquals("State must transition to STATE_LOADED on standard fetch success",
                FloatingOverlayService.STATE_LOADED, controller.getRetryState());
        assertEquals(standardLyrics, controller.getCurrentLyrics());
        assertFalse("Retry actions container must be hidden in STATE_LOADED", controller.isRetryActionsVisible());
    }

    /**
     * Test 5: AI retry failure when neither provider returns synced lyrics.
     * Verifies transition from STATE_AI_SEARCHING to STATE_FAILED, with retry actions restored.
     */
    @Test
    public void testAiRetryFailureTransitionsToFailedStateAndRestoresButtons() throws Exception {
        TrackInfo track = new TrackInfo();
        track.title = "Unknown Song";
        track.artist = "Unknown Artist";
        track.duration = 180000;

        fakeKugou.lyricsToReturn = null;
        fakeLrclib.lyricsToReturn = null;

        controller.setCurrentTrack(track);
        CountDownLatch completionLatch = new CountDownLatch(1);
        controller.triggerAiLyricsRetrieval(completionLatch);

        assertTrue("Retrieval should complete within timeout", completionLatch.await(5, TimeUnit.SECONDS));

        assertEquals("State must transition to STATE_FAILED on failure",
                FloatingOverlayService.STATE_FAILED, controller.getRetryState());
        assertTrue("Retry actions container must be visible on failure", controller.isRetryActionsVisible());
        assertFalse("AI progress container must be hidden on failure", controller.isAiProgressVisible());
        assertTrue("Retry buttons must be re-enabled on failure", controller.isRetryButtonsEnabled());
        assertEquals("No synced lyrics found for this version.", controller.getEmptyDescText());
        assertNull("Cache must remain empty on failure", fakeCache.cachedLyrics);
    }

    /**
     * Test 6: Manual cancellation of AI retrieval via cancel button.
     * Verifies active call is cancelled and state returns immediately to STATE_IDLE.
     */
    @Test
    public void testManualAiCancelReturnsToIdle() throws Exception {
        TrackInfo track = new TrackInfo();
        track.title = "Slow Song";
        track.artist = "Slow Artist";
        track.duration = 200000;

        fakeKugou.fetchStartedLatch = new CountDownLatch(1);
        fakeKugou.unblockLatch = new CountDownLatch(1);

        controller.setCurrentTrack(track);
        AntigravityApiClient.Cancellable activeCall = controller.triggerAiLyricsRetrieval(null);

        assertTrue(fakeKugou.fetchStartedLatch.await(3, TimeUnit.SECONDS));
        assertEquals(FloatingOverlayService.STATE_AI_SEARCHING, controller.getRetryState());

        // User taps cancel button (btnAiCancel)
        controller.cancelAiLyricsRetrieval();

        assertTrue("Active call must be cancelled on manual cancel", activeCall.isCancelled());
        assertNull("Active call reference must be cleared", controller.getActiveAiLyricsCall());
        assertEquals("State must return to STATE_IDLE", FloatingOverlayService.STATE_IDLE, controller.getRetryState());
        assertTrue("Retry actions visible in IDLE", controller.isRetryActionsVisible());
        assertFalse("AI progress hidden in IDLE", controller.isAiProgressVisible());
        assertTrue("Retry buttons enabled in IDLE", controller.isRetryButtonsEnabled());

        fakeKugou.unblockLatch.countDown();
    }
}
