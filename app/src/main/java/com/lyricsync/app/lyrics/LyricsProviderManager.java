package com.lyricsync.app.lyrics;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.lyricsync.app.lyrics.cache.LyricsCacheManager;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.lyrics.provider.BetterLyricsProvider;
import com.lyricsync.app.lyrics.provider.KugouLyricsProvider;
import com.lyricsync.app.lyrics.provider.LRCLIBProvider;
import com.lyricsync.app.lyrics.provider.LyricsPlusProvider;
import com.lyricsync.app.lyrics.provider.LyricsProvider;
import com.lyricsync.app.lyrics.provider.NeteaseLyricsProvider;
import com.lyricsync.app.lyrics.provider.SpicyLyricsProvider;
import com.lyricsync.app.lyrics.util.LyricsCleanup;
import com.lyricsync.app.lyrics.util.TrackQuery;
import com.lyricsync.app.util.AppLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Finds lyrics for the playing track: cache, then every source at once. Sources are
 * listed by quality; the first word-by-word (SYLLABLE) result in that order wins as soon
 * as every better source has answered, and a slow better source gets at most
 * {@link #graceMs} once a SYLLABLE result is in hand. Without one, the best line-synced
 * result wins. The same engine serves {@link #search}, where the user (or the AI) edits
 * the query and picks a result. Concurrent requests for one track share a fetch.
 * Callbacks run on the main thread.
 */
public class LyricsProviderManager {
    private static final String TAG = "LyricsProviderManager";
    /**
     * Safety cap for one lookup. Without a SYLLABLE result every source is awaited, as the
     * old cascade did (Spicy can take 15 s+ resolving a Spotify id); each has its own HTTP timeouts.
     */
    private static final long TOTAL_MS = 60_000;

    public interface LyricsCallback {
        void onLyricsLoaded(LyricsData lyrics);

        void onLyricsError(String error);
    }

    /** One source's answer in an interactive search. */
    public static final class Candidate {
        public final String source;
        public final LyricsData lyrics;

        Candidate(String source, LyricsData lyrics) {
            this.source = source;
            this.lyrics = lyrics;
        }
    }

    public interface SearchListener {
        /** A source found lyrics; called once per source that has any. */
        void onCandidate(Candidate candidate);

        /** Every source answered (or the time ran out). */
        void onFinished(int found);
    }

    /** Handle for a running search. */
    public interface Search {
        void cancel();
    }

    private final List<LyricsProvider> providers = new ArrayList<>();
    private final LyricsCacheManager cache;
    private final ExecutorService executor;
    private final ExecutorService sources = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "lyrics-source");
        t.setDaemon(true);
        return t;
    });
    private final Handler mainHandler;
    private final Map<String, List<LyricsCallback>> inFlight = new HashMap<>();
    long graceMs = 2500;

    public LyricsProviderManager(Context context) {
        this(new LyricsCacheManager(context), null, null, mainHandler());
        // Quality order: Apple-grade syllable timing first, then the big CJK catalogues, then line sync.
        providers.add(new SpicyLyricsProvider(context));
        providers.add(new BetterLyricsProvider());
        providers.add(new LyricsPlusProvider());
        providers.add(new KugouLyricsProvider());
        providers.add(new NeteaseLyricsProvider());
        providers.add(new LRCLIBProvider());
    }

    /** For tests: explicit cache, providers, executor; a null handler runs callbacks inline. */
    public LyricsProviderManager(LyricsCacheManager cache, List<LyricsProvider> providers,
                                 ExecutorService executor, Handler handler) {
        this.cache = cache != null ? cache : new LyricsCacheManager(null);
        this.executor = executor != null ? executor : Executors.newFixedThreadPool(3);
        this.mainHandler = handler;
        if (providers != null) this.providers.addAll(providers);
    }

    public void fetchLyrics(TrackInfo track, LyricsCallback callback) {
        if (track == null || track.title == null || track.title.trim().isEmpty()) {
            if (callback != null) post(() -> callback.onLyricsError("Track or title is empty"));
            return;
        }
        String key = (track.title + "|" + (track.artist == null ? "" : track.artist)).toLowerCase(Locale.ROOT).trim();
        synchronized (inFlight) {
            List<LyricsCallback> waiting = inFlight.get(key);
            if (waiting != null) {
                if (callback != null) waiting.add(callback);
                return;
            }
            List<LyricsCallback> list = new ArrayList<>();
            if (callback != null) list.add(callback);
            inFlight.put(key, list);
        }
        try {
            executor.execute(() -> {
                LyricsData result = null;
                try {
                    result = find(track);
                } catch (Throwable t) {
                    AppLog.e(TAG, "Lyrics lookup crashed", t);
                }
                finish(key, result, track);
            });
        } catch (RejectedExecutionException e) {
            finish(key, null, track); // shut down: never leave a caller waiting
        }
    }

    /** The best lyrics for {@code track}, or null. Runs on a worker thread. */
    LyricsData find(TrackInfo track) {
        LyricsData cached = cache.getCached(track);
        if (cached != null && !cached.isEmpty() && cached.type == LyricsData.Type.SYLLABLE) {
            AppLog.d(TAG, "Cache hit (SYLLABLE): " + track);
            return cached;
        }
        TrackQuery q = TrackQuery.from(track);
        Race race = new Race(q);
        race.start(null);
        LyricsData best = race.awaitBest(cached != null && !cached.isEmpty() ? cached : null);
        race.cancel();
        if (best != null && best != cached) cache.cache(track, best);
        return best;
    }

    /**
     * Interactive search: every source with {@code title}/{@code artist} (blank = the playing
     * track's), each result delivered as it arrives, on the main thread.
     */
    public Search search(TrackInfo track, String title, String artist, SearchListener listener) {
        TrackInfo t = new TrackInfo();
        t.title = blank(title) ? (track != null ? track.title : "") : title.trim();
        t.artist = blank(artist) ? (track != null ? track.artist : "") : artist.trim();
        t.album = track != null && blank(title) ? track.album : null; // a new title is another song: drop the album
        t.duration = track != null && blank(title) ? track.duration : 0;
        t.trackId = track != null && blank(title) && blank(artist) ? track.trackId : null;
        Race race = new Race(TrackQuery.from(t));
        if (t.title == null || t.title.trim().isEmpty()) {
            post(() -> listener.onFinished(0));
            return race::cancel;
        }
        race.start(c -> post(() -> {
            if (!race.cancelled) listener.onCandidate(c);
        }));
        try {
            executor.execute(() -> {
                int found = race.awaitAll();
                post(() -> {
                    if (!race.cancelled) listener.onFinished(found);
                });
            });
        } catch (RejectedExecutionException e) {
            post(() -> listener.onFinished(0));
        }
        return race::cancel;
    }

    /** The user picked these lyrics for {@code track}: store them even over a "better" type. */
    public void useLyrics(TrackInfo track, LyricsData lyrics) {
        if (track == null || lyrics == null) return;
        try {
            executor.execute(() -> cache.replace(track, lyrics));
        } catch (RejectedExecutionException ignored) {
        }
    }

    /** Every source queried at once for one query; results land in priority slots. */
    private final class Race {
        final TrackQuery q;
        final int n = providers.size();
        final LyricsData[] got = new LyricsData[n];
        final boolean[] done = new boolean[n];
        final BlockingQueue<Integer> finished = new LinkedBlockingQueue<>();
        final List<Future<?>> futures = new ArrayList<>();
        volatile boolean cancelled;

        Race(TrackQuery q) {
            this.q = q;
        }

        void start(Consumer<Candidate> onEach) {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                final LyricsProvider p = providers.get(i);
                try {
                    futures.add(sources.submit(() -> {
                        LyricsData l = null;
                        try {
                            if (!cancelled) l = p.fetchLyrics(q);
                            if (l != null) {
                                LyricsCleanup.clean(l, q.title);
                                if (l.isEmpty()) l = null;
                                else if (l.provider == null) l.provider = p.getName();
                            }
                        } catch (Exception e) {
                            AppLog.w(TAG, p.getName() + " failed: " + e.getMessage());
                        }
                        if (l != null) {
                            AppLog.d(TAG, p.getName() + ": " + l.type + " (" + l.lines.size() + " lines) for " + q);
                            if (onEach != null && !cancelled) onEach.accept(new Candidate(p.getName(), l));
                        }
                        synchronized (got) {
                            got[idx] = l;
                        }
                        finished.add(idx);
                    }));
                } catch (RejectedExecutionException e) {
                    finished.add(idx);
                }
            }
        }

        /** Waits as described in the class comment; {@code floor} (cached LINE) is kept unless beaten. */
        LyricsData awaitBest(LyricsData floor) {
            long now = System.currentTimeMillis(), deadline = now + TOTAL_MS, graceEnd = Long.MAX_VALUE;
            for (int answered = 0; answered < n; answered++) {
                long wait = Math.min(deadline, graceEnd) - System.currentTimeMillis();
                Integer i = wait > 0 ? poll(wait) : null;
                if (i == null) break;
                synchronized (got) {
                    done[i] = true;
                    if (decided()) break;
                    if (graceEnd == Long.MAX_VALUE && got[i] != null && got[i].type == LyricsData.Type.SYLLABLE) {
                        graceEnd = System.currentTimeMillis() + graceMs;
                    }
                }
            }
            synchronized (got) {
                return pick(got, floor);
            }
        }

        /** A SYLLABLE result whose better-ranked sources have all answered. */
        private boolean decided() {
            for (int i = 0; i < n; i++) {
                if (!done[i]) return false;
                if (got[i] != null && got[i].type == LyricsData.Type.SYLLABLE) return true;
            }
            return true;
        }

        int awaitAll() {
            long deadline = System.currentTimeMillis() + TOTAL_MS;
            for (int answered = 0; answered < n; answered++) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0 || poll(wait) == null) break;
            }
            int found = 0;
            synchronized (got) {
                for (LyricsData l : got) if (l != null) found++;
            }
            return found;
        }

        private Integer poll(long ms) {
            try {
                return finished.poll(ms, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        void cancel() {
            cancelled = true;
            for (Future<?> f : futures) f.cancel(true);
        }
    }

    /** First SYLLABLE in source order; otherwise the best by {@link #betterThan}, ties to the earlier source. */
    static LyricsData pick(LyricsData[] bySource, LyricsData floor) {
        for (LyricsData l : bySource) if (l != null && l.type == LyricsData.Type.SYLLABLE) return l;
        LyricsData best = floor;
        for (LyricsData l : bySource) if (betterThan(l, best)) best = l;
        return best;
    }

    private void finish(String key, LyricsData result, TrackInfo track) {
        List<LyricsCallback> callbacks;
        synchronized (inFlight) {
            callbacks = inFlight.remove(key);
        }
        if (result != null) {
            AppLog.i(TAG, "Lyrics from " + result.provider + " (" + result.type + ", "
                    + result.lines.size() + " lines) for " + track);
        } else {
            AppLog.w(TAG, "No lyrics found for: " + track);
        }
        if (callbacks == null) return;
        for (LyricsCallback cb : callbacks) {
            if (result != null) post(() -> cb.onLyricsLoaded(result));
            else post(() -> cb.onLyricsError("No lyrics found for: " + track));
        }
    }

    /** Store user/AI edits so the next play shows them. */
    public void updateCachedLyrics(TrackInfo track, LyricsData lyrics) {
        if (track == null || lyrics == null) return;
        try {
            executor.execute(() -> cache.cache(track, lyrics));
        } catch (RejectedExecutionException ignored) {
        }
    }

    /** SYLLABLE beats LINE beats STATIC; within a type, more lines wins. */
    public static boolean betterThan(LyricsData candidate, LyricsData current) {
        if (candidate == null || candidate.isEmpty()) return false;
        if (current == null || current.isEmpty()) return true;
        int c = LyricsData.rankOf(candidate.type), b = LyricsData.rankOf(current.type);
        return c > b || (c == b && candidate.lines.size() > current.lines.size());
    }

    public void shutdown() {
        executor.shutdownNow();
        sources.shutdownNow();
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private void post(Runnable r) {
        if (mainHandler != null) mainHandler.post(r);
        else r.run();
    }

    private static Handler mainHandler() {
        try {
            return new Handler(Looper.getMainLooper());
        } catch (Throwable t) {
            return null; // plain JVM (tests)
        }
    }
}
