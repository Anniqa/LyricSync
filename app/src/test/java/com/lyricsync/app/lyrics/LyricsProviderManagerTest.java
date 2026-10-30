package com.lyricsync.app.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.lyricsync.app.lyrics.cache.LyricsCacheManager;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.lyrics.provider.LyricsProvider;
import com.lyricsync.app.lyrics.util.TrackQuery;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** The cascade: stop at word-sync, keep the best otherwise, survive failures, share fetches. */
public class LyricsProviderManagerTest {

    private static final class Fake implements LyricsProvider {
        final String name;
        final LyricsData.Type type;
        final AtomicInteger calls = new AtomicInteger();
        volatile CountDownLatch gate;

        Fake(String name, LyricsData.Type type) {
            this.name = name;
            this.type = type;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public LyricsData fetchLyrics(TrackQuery q) throws Exception {
            calls.incrementAndGet();
            if (gate != null) gate.await(5, TimeUnit.SECONDS);
            if (type == null) throw new RuntimeException("boom");
            LyricsData l = new LyricsData(type);
            l.provider = name;
            l.lines.add(new LyricsData.LyricsLine(0, 1000, q.title + " lyric"));
            return l;
        }
    }

    private static TrackInfo track(String title) {
        TrackInfo t = new TrackInfo();
        t.title = title;
        t.artist = "Artist";
        return t;
    }

    private static LyricsProviderManager manager(ExecutorService pool, LyricsProvider... ps) {
        return new LyricsProviderManager(new LyricsCacheManager(null), Arrays.asList(ps), pool, null);
    }

    /** Sources run at once, so a later SYLLABLE source is queried too; priority order still decides. */
    @Test
    public void firstSyllableInPriorityOrderWins() {
        Fake line = new Fake("A", LyricsData.Type.LINE), syl = new Fake("B", LyricsData.Type.SYLLABLE),
                later = new Fake("C", LyricsData.Type.SYLLABLE);
        assertEquals("B", manager(null, line, syl, later).find(track("Song")).provider);
    }

    @Test
    public void keepsBestWhenNoSyllableAndSkipsFailures() {
        Fake broken = new Fake("X", null), stat = new Fake("S", LyricsData.Type.STATIC),
                line = new Fake("L", LyricsData.Type.LINE);
        assertEquals("L", manager(null, broken, stat, line).find(track("Song")).provider);
    }

    /** Sources run at once: a slow better source is waited for only within the grace time. */
    @Test
    public void slowBetterSourceGetsOnlyTheGraceTime() {
        Fake slowBest = new Fake("Slow", LyricsData.Type.SYLLABLE), fast = new Fake("Fast", LyricsData.Type.SYLLABLE);
        slowBest.gate = new CountDownLatch(1); // never opens in time
        LyricsProviderManager m = manager(null, slowBest, fast);
        m.graceMs = 200;
        long t0 = System.currentTimeMillis();
        assertEquals("Fast", m.find(track("Song")).provider);
        assertTrue(System.currentTimeMillis() - t0 < 3000);
        slowBest.gate.countDown();
        m.shutdown();
    }

    @Test
    public void searchReportsEverySourceThatHasLyrics() throws Exception {
        Fake a = new Fake("A", LyricsData.Type.LINE), b = new Fake("B", LyricsData.Type.SYLLABLE), x = new Fake("X", null);
        LyricsProviderManager m = manager(null, a, b, x);
        List<String> seen = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        int[] found = {-1};
        m.search(track("Song"), "Other Title", "", new LyricsProviderManager.SearchListener() {
            @Override
            public void onCandidate(LyricsProviderManager.Candidate c) {
                synchronized (seen) { seen.add(c.source + ":" + c.lyrics.lines.get(0).text); }
            }

            @Override
            public void onFinished(int n) {
                found[0] = n;
                done.countDown();
            }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(2, found[0]);
        assertTrue(seen.contains("A:Other Title lyric") && seen.contains("B:Other Title lyric"));
        m.shutdown();
    }

    @Test
    public void nothingFound_isNull() {
        assertNull(manager(null, new Fake("X", null)).find(track("Song")));
    }

    @Test
    public void concurrentRequestsShareOneFetch() throws Exception {
        Fake p = new Fake("P", LyricsData.Type.SYLLABLE);
        p.gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        LyricsProviderManager m = manager(pool, p);
        CountDownLatch done = new CountDownLatch(2);
        List<LyricsData> got = new ArrayList<>();
        LyricsProviderManager.LyricsCallback cb = new LyricsProviderManager.LyricsCallback() {
            @Override
            public void onLyricsLoaded(LyricsData lyrics) {
                synchronized (got) { got.add(lyrics); }
                done.countDown();
            }

            @Override
            public void onLyricsError(String error) {
                done.countDown();
            }
        };
        m.fetchLyrics(track("Song"), cb);
        m.fetchLyrics(track("Song"), cb);
        p.gate.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, p.calls.get());
        assertEquals(2, got.size());
        m.shutdown();
    }

    @Test
    public void emptyTitle_reportsError() {
        String[] err = new String[1];
        manager(null).fetchLyrics(track(" "), new LyricsProviderManager.LyricsCallback() {
            @Override
            public void onLyricsLoaded(LyricsData lyrics) {}

            @Override
            public void onLyricsError(String error) {
                err[0] = error;
            }
        });
        assertTrue(err[0] != null);
    }
}
