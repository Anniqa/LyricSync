package com.lyricsync.app.lyrics;

import com.lyricsync.app.lyrics.cache.LyricsCacheManager;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.lyrics.provider.LyricsProvider;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class LyricsProviderManagerTest {

    static class MockLyricsProvider implements LyricsProvider {
        private final String name;
        private final LyricsData lyricsToReturn;
        final AtomicBoolean wasCalled = new AtomicBoolean(false);

        MockLyricsProvider(String name, LyricsData lyricsToReturn) {
            this.name = name;
            this.lyricsToReturn = lyricsToReturn;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public LyricsData fetchLyrics(TrackInfo track) throws Exception {
            wasCalled.set(true);
            return lyricsToReturn;
        }
    }

    private static LyricsData createLyrics(LyricsData.Type type, String provider, int lineCount) {
        LyricsData data = new LyricsData(type);
        data.provider = provider;
        for (int i = 0; i < lineCount; i++) {
            LyricsData.LyricsLine line = new LyricsData.LyricsLine(i * 1000L, (i + 1) * 1000L, "Line " + i);
            if (type == LyricsData.Type.SYLLABLE) {
                line.words.add(new LyricsData.Word(i * 1000L, (i + 1) * 1000L, "Word " + i));
            }
            data.lines.add(line);
        }
        return data;
    }

    @Test
    public void testSyllablePrioritizedOverLine() throws Exception {
        LyricsData lineLyrics = createLyrics(LyricsData.Type.LINE, "LRCLIB", 20);
        LyricsData syllableLyrics = createLyrics(LyricsData.Type.SYLLABLE, "Kugou", 15);

        MockLyricsProvider kugou = new MockLyricsProvider("Kugou", syllableLyrics);
        MockLyricsProvider lrclib = new MockLyricsProvider("LRCLIB", lineLyrics);

        LyricsProviderManager manager = new LyricsProviderManager(
                null,
                Arrays.asList(kugou, lrclib),
                Executors.newSingleThreadExecutor(),
                null
        );

        TrackInfo track = new TrackInfo();
        track.title = "Song";
        track.artist = "Artist";

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<LyricsData> resultRef = new AtomicReference<>();

        manager.fetchLyrics(track, new LyricsProviderManager.LyricsCallback() {
            @Override
            public void onLyricsLoaded(LyricsData lyrics) {
                resultRef.set(lyrics);
                latch.countDown();
            }

            @Override
            public void onLyricsError(String error) {
                latch.countDown();
            }
        });

        assertTrue("Callback should complete", latch.await(3, TimeUnit.SECONDS));
        assertNotNull("Should return lyrics", resultRef.get());
        assertEquals("Should prioritize syllable lyrics over line lyrics",
                LyricsData.Type.SYLLABLE, resultRef.get().type);
        assertEquals("Kugou", resultRef.get().provider);
    }

    @Test
    public void testSyllableEarlyReturnSkipsSubsequentProviders() throws Exception {
        LyricsData syllableLyrics = createLyrics(LyricsData.Type.SYLLABLE, "Kugou", 10);
        LyricsData lineLyrics = createLyrics(LyricsData.Type.LINE, "LRCLIB", 10);

        MockLyricsProvider kugou = new MockLyricsProvider("Kugou", syllableLyrics);
        MockLyricsProvider slowProvider = new MockLyricsProvider("LRCLIB", lineLyrics);

        LyricsProviderManager manager = new LyricsProviderManager(
                null,
                Arrays.asList(kugou, slowProvider),
                Executors.newSingleThreadExecutor(),
                null
        );

        TrackInfo track = new TrackInfo();
        track.title = "Fast Song";
        track.artist = "Fast Artist";

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<LyricsData> resultRef = new AtomicReference<>();

        manager.fetchLyrics(track, new LyricsProviderManager.LyricsCallback() {
            @Override
            public void onLyricsLoaded(LyricsData lyrics) {
                resultRef.set(lyrics);
                latch.countDown();
            }

            @Override
            public void onLyricsError(String error) {
                latch.countDown();
            }
        });

        assertTrue("Callback should complete", latch.await(3, TimeUnit.SECONDS));
        assertNotNull(resultRef.get());
        assertEquals(LyricsData.Type.SYLLABLE, resultRef.get().type);
        assertTrue("Kugou should have been called", kugou.wasCalled.get());
        assertFalse("Slow provider should NOT have been called once syllable lyrics were found",
                slowProvider.wasCalled.get());
    }

    @Test
    public void testTargetProviderSyllableAlias() throws Exception {
        LyricsData lineLyrics = createLyrics(LyricsData.Type.LINE, "LRCLIB", 10);
        LyricsData syllableLyrics = createLyrics(LyricsData.Type.SYLLABLE, "Kugou", 10);

        MockLyricsProvider kugou = new MockLyricsProvider("Kugou", syllableLyrics);
        MockLyricsProvider lrclib = new MockLyricsProvider("LRCLIB", lineLyrics);

        LyricsProviderManager manager = new LyricsProviderManager(
                null,
                Arrays.asList(lrclib, kugou),
                Executors.newSingleThreadExecutor(),
                null
        );

        TrackInfo track = new TrackInfo();
        track.title = "Karaoke Song";
        track.artist = "Karaoke Singer";

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<LyricsData> resultRef = new AtomicReference<>();

        manager.fetchLyrics(track, "syllable", new LyricsProviderManager.LyricsCallback() {
            @Override
            public void onLyricsLoaded(LyricsData lyrics) {
                resultRef.set(lyrics);
                latch.countDown();
            }

            @Override
            public void onLyricsError(String error) {
                latch.countDown();
            }
        });

        assertTrue("Callback should complete", latch.await(3, TimeUnit.SECONDS));
        assertNotNull("Should find syllable lyrics", resultRef.get());
        assertEquals(LyricsData.Type.SYLLABLE, resultRef.get().type);
        assertEquals("Kugou", resultRef.get().provider);
        assertFalse("LRCLIB should be skipped when 'syllable' is targeted", lrclib.wasCalled.get());
    }

    @Test
    public void testIsSyllableTargetHelper() {
        assertTrue(LyricsProviderManager.isSyllableTarget("syllable"));
        assertTrue(LyricsProviderManager.isSyllableTarget("karaoke"));
        assertTrue(LyricsProviderManager.isSyllableTarget("suku kata"));
        assertTrue(LyricsProviderManager.isSyllableTarget("word_by_word"));
        assertTrue(LyricsProviderManager.isSyllableTarget("krc"));
        assertTrue(LyricsProviderManager.isSyllableTarget("yrc"));
        assertFalse(LyricsProviderManager.isSyllableTarget("kugou"));
        assertFalse(LyricsProviderManager.isSyllableTarget("lrclib"));
        assertFalse(LyricsProviderManager.isSyllableTarget("all"));
        assertFalse(LyricsProviderManager.isSyllableTarget(null));
    }
}
