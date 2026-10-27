package com.lyricsync.app.lyrics;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.lyricsync.app.lyrics.cache.LyricsCacheManager;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.lyrics.provider.KugouLyricsProvider;
import com.lyricsync.app.lyrics.provider.LRCLIBProvider;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Finds lyrics for the playing track: cache, then the provider cascade
 * SpicyLyrics → Kugou → Netease → LRCLIB. Stops at the first word-by-word (SYLLABLE)
 * result; otherwise keeps the best line-synced one. Concurrent requests for the same
 * track share one fetch. Callbacks run on the main thread.
 */
public class LyricsProviderManager {
    private static final String TAG = "LyricsProviderManager";

    public interface LyricsCallback {
        void onLyricsLoaded(LyricsData lyrics);

        void onLyricsError(String error);
    }

    private final List<LyricsProvider> providers = new ArrayList<>();
    private final LyricsCacheManager cache;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final Map<String, List<LyricsCallback>> inFlight = new HashMap<>();

    public LyricsProviderManager(Context context) {
        this(new LyricsCacheManager(context), null, null, mainHandler());
        providers.add(new SpicyLyricsProvider(context));
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
        LyricsData best = cached != null && !cached.isEmpty() ? cached : null;

        TrackQuery q = TrackQuery.from(track);
        for (LyricsProvider p : providers) {
            try {
                LyricsData l = p.fetchLyrics(q);
                if (l == null) continue;
                LyricsCleanup.clean(l, q.title);
                if (l.isEmpty()) continue;
                AppLog.d(TAG, p.getName() + ": " + l.type + " (" + l.lines.size() + " lines) for " + q);
                if (betterThan(l, best)) best = l;
                if (best.type == LyricsData.Type.SYLLABLE) break;
            } catch (Exception e) {
                AppLog.w(TAG, p.getName() + " failed: " + e.getMessage());
            }
        }
        if (best != null && best != cached) cache.cache(track, best);
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
