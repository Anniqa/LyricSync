package com.lyricsync.app.ai.service;

import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.lyrics.cache.LyricsCacheManager;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.lyrics.provider.KugouLyricsProvider;
import com.lyricsync.app.lyrics.provider.LRCLIBProvider;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class AiLyricsAgentServiceTest {

    // --- Fake Providers and Cache ---

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

    static class TestCallback implements AiLyricsAgentService.AiLyricsCallback {
        final List<Integer> stepCodes = Collections.synchronizedList(new ArrayList<>());
        final List<String> stepMessages = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<LyricsData> successLyrics = new AtomicReference<>();
        final AtomicReference<String> errorMessage = new AtomicReference<>();
        final CountDownLatch doneLatch = new CountDownLatch(1);

        @Override
        public void onStepChanged(int stepCode, String message) {
            stepCodes.add(stepCode);
            stepMessages.add(message);
        }

        @Override
        public void onSuccess(LyricsData lyrics) {
            successLyrics.set(lyrics);
            doneLatch.countDown();
        }

        @Override
        public void onError(String message) {
            errorMessage.set(message);
            doneLatch.countDown();
        }
    }

    // --- Tests ---

    @Test
    public void testCoverSongResolutionQueriesCoverArtistAndNeverOriginalArtist() throws Exception {
        FakeKugouProvider fakeKugou = new FakeKugouProvider();
        FakeLRCLIBProvider fakeLrclib = new FakeLRCLIBProvider();
        FakeLyricsCacheManager fakeCache = new FakeLyricsCacheManager();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        AiLyricsAgentService service = new AiLyricsAgentService(fakeKugou, fakeLrclib, fakeCache, executor);

        LyricsData syllableLyrics = new LyricsData(LyricsData.Type.SYLLABLE);
        syllableLyrics.lines.add(new LyricsData.LyricsLine(0, 1000, "Is this the real life?"));
        fakeKugou.lyricsToReturn = syllableLyrics;

        TrackInfo track = new TrackInfo();
        track.title = "Bohemian Rhapsody (Cover by Marc Martel)";
        track.artist = "Queen";
        track.duration = 354000;

        TestCallback callback = new TestCallback();
        service.retrieveLyrics(track, callback);

        assertTrue("Callback should complete within timeout", callback.doneLatch.await(3, TimeUnit.SECONDS));
        assertNotNull("Should succeed with lyrics", callback.successLyrics.get());
        assertNull("Should have no error", callback.errorMessage.get());

        // Verify Kugou was queried with Marc Martel, NOT Queen
        assertEquals(1, fakeKugou.queries.size());
        TrackInfo queriedTrack = fakeKugou.queries.get(0);
        assertEquals("Marc Martel", queriedTrack.artist);
        assertEquals("Bohemian Rhapsody", queriedTrack.title);

        // Verify Queen was NEVER queried
        for (TrackInfo q : fakeKugou.queries) {
            assertNotEquals("Queen", q.artist);
        }
        for (TrackInfo q : fakeLrclib.queries) {
            assertNotEquals("Queen", q.artist);
        }

        // Verify cached
        assertNotNull(fakeCache.cachedLyrics);
        assertEquals(track, fakeCache.cachedTrack);

        executor.shutdown();
    }

    @Test
    public void testCoverDisambiguationVariousFormats() {
        // 1. Bracket format: "[Cover by Marc Martel]"
        TrackInfo t1 = new TrackInfo();
        t1.title = "Bohemian Rhapsody [Cover by Marc Martel]";
        t1.artist = "Queen";
        TrackInfo d1 = AiLyricsAgentService.disambiguateTrack(t1);
        assertEquals("Bohemian Rhapsody", d1.title);
        assertEquals("Marc Martel", d1.artist);

        // 2. Pipe format: "| Marc Martel Cover"
        TrackInfo t2 = new TrackInfo();
        t2.title = "Bohemian Rhapsody | Marc Martel Cover";
        t2.artist = "Queen";
        TrackInfo d2 = AiLyricsAgentService.disambiguateTrack(t2);
        assertEquals("Bohemian Rhapsody", d2.title);
        assertEquals("Marc Martel", d2.artist);

        // 3. Parentheses performer cover: "(Marc Martel Cover)"
        TrackInfo t3 = new TrackInfo();
        t3.title = "Bohemian Rhapsody (Marc Martel Cover)";
        t3.artist = "Queen";
        TrackInfo d3 = AiLyricsAgentService.disambiguateTrack(t3);
        assertEquals("Bohemian Rhapsody", d3.title);
        assertEquals("Marc Martel", d3.artist);

        // 4. Raw artist already contains performer: title "(Cover)", artist "Marc Martel"
        TrackInfo t4 = new TrackInfo();
        t4.title = "Bohemian Rhapsody (Cover)";
        t4.artist = "Marc Martel";
        TrackInfo d4 = AiLyricsAgentService.disambiguateTrack(t4);
        assertEquals("Bohemian Rhapsody", d4.title);
        assertEquals("Marc Martel", d4.artist);

        // 5. Feat performer in title: "(feat. Marc Martel)"
        TrackInfo t5 = new TrackInfo();
        t5.title = "Bohemian Rhapsody (feat. Marc Martel)";
        t5.artist = "Queen";
        TrackInfo d5 = AiLyricsAgentService.disambiguateTrack(t5);
        assertEquals("Bohemian Rhapsody", d5.title);
        assertEquals("Marc Martel", d5.artist);

        // 6. Live venue cleaning: "(Live at Wembley)" - artist remains Queen
        TrackInfo t6 = new TrackInfo();
        t6.title = "Bohemian Rhapsody (Live at Wembley)";
        t6.artist = "Queen";
        TrackInfo d6 = AiLyricsAgentService.disambiguateTrack(t6);
        assertEquals("Bohemian Rhapsody", d6.title);
        assertEquals("Queen", d6.artist);
    }

    @Test
    public void testSyllableLyricsPrioritizedOverLrc() throws Exception {
        FakeKugouProvider fakeKugou = new FakeKugouProvider();
        FakeLRCLIBProvider fakeLrclib = new FakeLRCLIBProvider();
        FakeLyricsCacheManager fakeCache = new FakeLyricsCacheManager();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        AiLyricsAgentService service = new AiLyricsAgentService(fakeKugou, fakeLrclib, fakeCache, executor);

        LyricsData syllableLyrics = new LyricsData(LyricsData.Type.SYLLABLE);
        syllableLyrics.lines.add(new LyricsData.LyricsLine(0, 1000, "Sparkle"));
        fakeKugou.lyricsToReturn = syllableLyrics;

        LyricsData lrcLyrics = new LyricsData(LyricsData.Type.LINE);
        lrcLyrics.lines.add(new LyricsData.LyricsLine(0, 1000, "Sparkle LRC"));
        fakeLrclib.lyricsToReturn = lrcLyrics;

        TrackInfo track = new TrackInfo();
        track.title = "Sparkle";
        track.artist = "RADWIMPS";

        TestCallback callback = new TestCallback();
        service.retrieveLyrics(track, callback);

        assertTrue(callback.doneLatch.await(3, TimeUnit.SECONDS));
        assertEquals(LyricsData.Type.SYLLABLE, callback.successLyrics.get().type);
        assertEquals(1, fakeKugou.queries.size());
        assertEquals(0, fakeLrclib.queries.size()); // LRCLIB never queried because KRC was found

        // Step progression: DISAMBIGUATING -> FETCHING_SYLLABLE (no FALLBACK_LRC)
        assertTrue(callback.stepCodes.contains(AiLyricsAgentService.STEP_DISAMBIGUATING));
        assertTrue(callback.stepCodes.contains(AiLyricsAgentService.STEP_FETCHING_SYLLABLE));
        assertFalse(callback.stepCodes.contains(AiLyricsAgentService.STEP_FALLBACK_LRC));

        executor.shutdown();
    }

    @Test
    public void testFallbackToLrcWhenSyllableNotFound() throws Exception {
        FakeKugouProvider fakeKugou = new FakeKugouProvider();
        FakeLRCLIBProvider fakeLrclib = new FakeLRCLIBProvider();
        FakeLyricsCacheManager fakeCache = new FakeLyricsCacheManager();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        AiLyricsAgentService service = new AiLyricsAgentService(fakeKugou, fakeLrclib, fakeCache, executor);

        // Kugou returns null (no syllable lyrics)
        fakeKugou.lyricsToReturn = null;

        // LRCLIB returns synced line lyrics
        LyricsData lineLyrics = new LyricsData(LyricsData.Type.LINE);
        lineLyrics.lines.add(new LyricsData.LyricsLine(0, 1000, "Line synced"));
        fakeLrclib.lyricsToReturn = lineLyrics;

        TrackInfo track = new TrackInfo();
        track.title = "Bohemian Rhapsody (Cover by Marc Martel)";
        track.artist = "Queen";

        TestCallback callback = new TestCallback();
        service.retrieveLyrics(track, callback);

        assertTrue(callback.doneLatch.await(3, TimeUnit.SECONDS));
        assertNotNull(callback.successLyrics.get());
        assertEquals(LyricsData.Type.LINE, callback.successLyrics.get().type);

        // Verify both providers queried strictly with Marc Martel
        assertEquals(1, fakeKugou.queries.size());
        assertEquals("Marc Martel", fakeKugou.queries.get(0).artist);
        assertEquals(1, fakeLrclib.queries.size());
        assertEquals("Marc Martel", fakeLrclib.queries.get(0).artist);

        // Step progression: DISAMBIGUATING -> FETCHING_SYLLABLE -> FALLBACK_LRC
        assertEquals(3, callback.stepCodes.size());
        assertEquals(AiLyricsAgentService.STEP_DISAMBIGUATING, (int) callback.stepCodes.get(0));
        assertEquals(AiLyricsAgentService.STEP_FETCHING_SYLLABLE, (int) callback.stepCodes.get(1));
        assertEquals(AiLyricsAgentService.STEP_FALLBACK_LRC, (int) callback.stepCodes.get(2));

        executor.shutdown();
    }

    @Test
    public void testNeitherProviderReturnsSyncedLyricsCallsOnError() throws Exception {
        FakeKugouProvider fakeKugou = new FakeKugouProvider();
        FakeLRCLIBProvider fakeLrclib = new FakeLRCLIBProvider();
        FakeLyricsCacheManager fakeCache = new FakeLyricsCacheManager();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        AiLyricsAgentService service = new AiLyricsAgentService(fakeKugou, fakeLrclib, fakeCache, executor);

        fakeKugou.lyricsToReturn = null;
        fakeLrclib.lyricsToReturn = null; // No synced lyrics

        TrackInfo track = new TrackInfo();
        track.title = "Obscure Cover (Cover by Indie Performer)";
        track.artist = "Original Band";

        TestCallback callback = new TestCallback();
        service.retrieveLyrics(track, callback);

        assertTrue(callback.doneLatch.await(3, TimeUnit.SECONDS));
        assertNull(callback.successLyrics.get());
        assertNotNull(callback.errorMessage.get());
        assertEquals("No synced lyrics found for this version.", callback.errorMessage.get());

        // Cache must not receive unsynced/null lyrics
        assertNull(fakeCache.cachedLyrics);

        executor.shutdown();
    }

    @Test
    public void testCancellationHaltsInFlightExecutionAndPreventsCallbacks() throws Exception {
        FakeKugouProvider fakeKugou = new FakeKugouProvider();
        FakeLRCLIBProvider fakeLrclib = new FakeLRCLIBProvider();
        FakeLyricsCacheManager fakeCache = new FakeLyricsCacheManager();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        fakeKugou.fetchStartedLatch = new CountDownLatch(1);
        fakeKugou.unblockLatch = new CountDownLatch(1);

        LyricsData syllableLyrics = new LyricsData(LyricsData.Type.SYLLABLE);
        syllableLyrics.lines.add(new LyricsData.LyricsLine(0, 1000, "Never seen"));
        fakeKugou.lyricsToReturn = syllableLyrics;

        AiLyricsAgentService service = new AiLyricsAgentService(fakeKugou, fakeLrclib, fakeCache, executor);

        TrackInfo track = new TrackInfo();
        track.title = "Song";
        track.artist = "Artist";

        final AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        AntigravityApiClient.Cancellable handle = service.retrieveLyrics(track, new AiLyricsAgentService.AiLyricsCallback() {
            @Override
            public void onStepChanged(int stepCode, String message) {
                // Initial disambiguation step may happen before cancel
            }

            @Override
            public void onSuccess(LyricsData lyrics) {
                callbackInvoked.set(true);
            }

            @Override
            public void onError(String message) {
                callbackInvoked.set(true);
            }
        });

        // Wait until fetch starts inside Kugou
        assertTrue(fakeKugou.fetchStartedLatch.await(2, TimeUnit.SECONDS));

        // Cancel now
        assertFalse(handle.isCancelled());
        handle.cancel();
        assertTrue(handle.isCancelled());

        // Unblock provider
        fakeKugou.unblockLatch.countDown();

        // Allow any background work to complete
        Thread.sleep(100);

        // Verify neither onSuccess nor onError was called
        assertFalse("No terminal callback should be invoked after cancel", callbackInvoked.get());
        assertNull("Cache should not be modified after cancel", fakeCache.cachedLyrics);

        executor.shutdown();
    }

    @Test
    public void testInvalidTrackMetadataCallsOnErrorImmediately() throws Exception {
        FakeKugouProvider fakeKugou = new FakeKugouProvider();
        FakeLRCLIBProvider fakeLrclib = new FakeLRCLIBProvider();
        FakeLyricsCacheManager fakeCache = new FakeLyricsCacheManager();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        AiLyricsAgentService service = new AiLyricsAgentService(fakeKugou, fakeLrclib, fakeCache, executor);

        TrackInfo invalidTrack = new TrackInfo();
        invalidTrack.title = "";
        invalidTrack.artist = "";

        TestCallback callback = new TestCallback();
        service.retrieveLyrics(invalidTrack, callback);

        assertTrue(callback.doneLatch.await(2, TimeUnit.SECONDS));
        assertNotNull(callback.errorMessage.get());
        assertEquals("Invalid track metadata.", callback.errorMessage.get());
        assertEquals(0, fakeKugou.queries.size());
        assertEquals(0, fakeLrclib.queries.size());

        executor.shutdown();
    }

    @Test
    public void testKugouExceptionFallsBackToLrclib() throws Exception {
        FakeKugouProvider fakeKugou = new FakeKugouProvider();
        FakeLRCLIBProvider fakeLrclib = new FakeLRCLIBProvider();
        FakeLyricsCacheManager fakeCache = new FakeLyricsCacheManager();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        fakeKugou.exceptionToThrow = new java.io.IOException("Kugou timeout");

        LyricsData lineLyrics = new LyricsData(LyricsData.Type.LINE);
        lineLyrics.lines.add(new LyricsData.LyricsLine(0, 1000, "Line synced fallback"));
        fakeLrclib.lyricsToReturn = lineLyrics;

        TrackInfo track = new TrackInfo();
        track.title = "Song";
        track.artist = "Artist";

        AiLyricsAgentService service = new AiLyricsAgentService(fakeKugou, fakeLrclib, fakeCache, executor);
        TestCallback callback = new TestCallback();
        service.retrieveLyrics(track, callback);

        assertTrue(callback.doneLatch.await(3, TimeUnit.SECONDS));
        assertNotNull(callback.successLyrics.get());
        assertEquals(LyricsData.Type.LINE, callback.successLyrics.get().type);
        assertEquals(1, fakeLrclib.queries.size());

        executor.shutdown();
    }

    @Test
    public void testStaticLyricsFromLrclibRejectedAsUnsynced() throws Exception {
        FakeKugouProvider fakeKugou = new FakeKugouProvider();
        FakeLRCLIBProvider fakeLrclib = new FakeLRCLIBProvider();
        FakeLyricsCacheManager fakeCache = new FakeLyricsCacheManager();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        fakeKugou.lyricsToReturn = null;

        // Static (unsynced) lyrics
        LyricsData staticLyrics = new LyricsData(LyricsData.Type.STATIC);
        staticLyrics.lines.add(new LyricsData.LyricsLine(0, 1000, "Unsynced plain text"));
        fakeLrclib.lyricsToReturn = staticLyrics;

        TrackInfo track = new TrackInfo();
        track.title = "Song";
        track.artist = "Artist";

        AiLyricsAgentService service = new AiLyricsAgentService(fakeKugou, fakeLrclib, fakeCache, executor);
        TestCallback callback = new TestCallback();
        service.retrieveLyrics(track, callback);

        assertTrue(callback.doneLatch.await(3, TimeUnit.SECONDS));
        assertNull("Static lyrics must not trigger onSuccess", callback.successLyrics.get());
        assertEquals("No synced lyrics found for this version.", callback.errorMessage.get());
        assertNull("Static lyrics must not be cached", fakeCache.cachedLyrics);

        executor.shutdown();
    }

    @Test
    public void testBothProvidersThrowCallsOnErrorGracefully() throws Exception {
        FakeKugouProvider fakeKugou = new FakeKugouProvider();
        FakeLRCLIBProvider fakeLrclib = new FakeLRCLIBProvider();
        FakeLyricsCacheManager fakeCache = new FakeLyricsCacheManager();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        fakeKugou.exceptionToThrow = new java.io.IOException("Kugou error");
        fakeLrclib.exceptionToThrow = new java.io.IOException("LRCLIB error");

        TrackInfo track = new TrackInfo();
        track.title = "Song";
        track.artist = "Artist";

        AiLyricsAgentService service = new AiLyricsAgentService(fakeKugou, fakeLrclib, fakeCache, executor);
        TestCallback callback = new TestCallback();
        service.retrieveLyrics(track, callback);

        assertTrue(callback.doneLatch.await(3, TimeUnit.SECONDS));
        assertNull(callback.successLyrics.get());
        assertEquals("No synced lyrics found for this version.", callback.errorMessage.get());

        executor.shutdown();
    }

    @Test
    public void testNullCallbackDoesNotThrow() throws Exception {
        FakeKugouProvider fakeKugou = new FakeKugouProvider();
        FakeLRCLIBProvider fakeLrclib = new FakeLRCLIBProvider();
        FakeLyricsCacheManager fakeCache = new FakeLyricsCacheManager();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        LyricsData syllableLyrics = new LyricsData(LyricsData.Type.SYLLABLE);
        syllableLyrics.lines.add(new LyricsData.LyricsLine(0, 1000, "Lyrics"));
        fakeKugou.lyricsToReturn = syllableLyrics;

        TrackInfo track = new TrackInfo();
        track.title = "Song";
        track.artist = "Artist";

        AiLyricsAgentService service = new AiLyricsAgentService(fakeKugou, fakeLrclib, fakeCache, executor);
        AntigravityApiClient.Cancellable cancellable = service.retrieveLyrics(track, null);

        assertNotNull(cancellable);
        Thread.sleep(100);
        assertNotNull("Should still cache even with null callback", fakeCache.cachedLyrics);

        executor.shutdown();
    }

    @Test
    public void testTrackMetadataSanitizerCleansNoiseFromTitleAndRetainsPerformer() {
        TrackInfo track = new TrackInfo();
        track.title = "Bohemian Rhapsody (Cover by Marc Martel) [Official Music Video] (HD)";
        track.artist = "Queen - Topic";

        TrackInfo disambiguated = AiLyricsAgentService.disambiguateTrack(track);

        assertEquals("Bohemian Rhapsody", disambiguated.title);
        assertEquals("Marc Martel", disambiguated.artist);
    }
}

