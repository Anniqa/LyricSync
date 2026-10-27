package com.lyricsync.app.lyrics;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.lyricsync.app.lyrics.cache.LyricsCacheManager;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.lyrics.provider.KugouLyricsProvider;
import com.lyricsync.app.lyrics.provider.LRCLIBProvider;
import com.lyricsync.app.lyrics.provider.LyricsProvider;
import com.lyricsync.app.lyrics.provider.NeteaseLyricsProvider;
import com.lyricsync.app.lyrics.provider.SpicyLyricsProvider;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Live hit-rate benchmark against the real lyrics services: 99 tracks as players
 * report them (clean Spotify metadata, noisy YouTube titles, CJK, Indonesian).
 * Skipped unless LYRICSYNC_LIVE=1, so CI and normal test runs stay offline.
 *
 *   LYRICSYNC_LIVE=1 ./gradlew testDebugUnitTest --tests '*LyricsHitRateLiveTest'
 */
public class LyricsHitRateLiveTest {

    private static final double REQUIRED_HIT_RATE = 0.90;

    @Test
    public void findsLyricsForAtLeastNinetyPercentOfTracks() throws Exception {
        assumeTrue("live benchmark; set LYRICSYNC_LIVE=1", "1".equals(System.getenv("LYRICSYNC_LIVE")));

        List<TrackInfo> tracks = loadTracks();
        List<LyricsProvider> providers = Arrays.asList(
                new SpicyLyricsProvider(null), new KugouLyricsProvider(),
                new NeteaseLyricsProvider(), new LRCLIBProvider());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        LyricsProviderManager manager =
                new LyricsProviderManager(new LyricsCacheManager(null), providers, pool, null);

        String[] result = new String[tracks.size()];
        CountDownLatch done = new CountDownLatch(tracks.size());
        for (int i = 0; i < tracks.size(); i++) {
            final int idx = i;
            manager.fetchLyrics(tracks.get(i), new LyricsProviderManager.LyricsCallback() {
                @Override public void onLyricsLoaded(LyricsData lyrics) {
                    result[idx] = lyrics.provider + " " + lyrics.type + " (" + lyrics.lines.size() + ") "
                            + firstLine(lyrics);
                    done.countDown();
                }
                @Override public void onLyricsError(String error) {
                    result[idx] = null;
                    done.countDown();
                }
            });
        }
        assertTrue("benchmark timed out", done.await(15, TimeUnit.MINUTES));
        manager.shutdown();

        int hits = 0, synced = 0, syllable = 0;
        StringBuilder misses = new StringBuilder();
        StringBuilder report = new StringBuilder();
        for (int i = 0; i < tracks.size(); i++) {
            String r = result[i];
            report.append(r != null ? "HIT  " : "MISS ").append(tracks.get(i))
                    .append(r != null ? "  ->  " + r : "").append('\n');
            if (r == null) { misses.append("\n  ").append(tracks.get(i)); continue; }
            hits++;
            if (!r.contains(" STATIC ")) synced++;
            if (r.contains(" SYLLABLE ")) syllable++;
        }
        double rate = hits / (double) tracks.size();
        report.append(String.format("hit %d/%d (%.1f%%), synced %d, syllable %d%n",
                hits, tracks.size(), rate * 100, synced, syllable));
        // Full per-track report: app/build/hit-rate.txt
        java.nio.file.Files.write(java.nio.file.Paths.get("build", "hit-rate.txt"),
                report.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue(String.format("hit rate %.1f%% < 90%%; misses:%s", rate * 100, misses),
                rate >= REQUIRED_HIT_RATE);
    }

    private static String firstLine(LyricsData lyrics) {
        for (LyricsData.LyricsLine l : lyrics.lines) {
            if (!l.isInterlude && l.text != null && !l.text.trim().isEmpty()) {
                String t = l.text.trim();
                return "\"" + (t.length() > 48 ? t.substring(0, 48) + "…" : t) + "\"";
            }
        }
        return "";
    }

    private static List<TrackInfo> loadTracks() throws Exception {
        List<TrackInfo> out = new ArrayList<>();
        InputStream in = LyricsHitRateLiveTest.class.getClassLoader().getResourceAsStream("hit_rate_tracks.tsv");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\t");
                TrackInfo t = new TrackInfo();
                t.title = f[0];
                t.artist = f[1];
                t.duration = Long.parseLong(f[2].trim()) * 1000L;
                out.add(t);
            }
        }
        return out;
    }
}
