package com.lyricsync.app.lyrics;

import android.content.Context;

import com.lyricsync.app.lyrics.cache.LyricsCacheManager;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.lyrics.provider.LRCLIBProvider;
import com.lyricsync.app.lyrics.provider.LyricsProvider;
import com.lyricsync.app.lyrics.provider.NeteaseLyricsProvider;
import com.lyricsync.app.lyrics.provider.SpicyLyricsProvider;
import com.lyricsync.app.util.AppLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

public class LyricsProviderManager {
    private static final String TAG = "LyricsProviderManager";

    private final List<LyricsProvider> providers = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final LyricsCacheManager cacheManager;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final AtomicLong generation = new AtomicLong();
    private Future<?> pendingTask;

    public interface LyricsCallback {
        void onLyricsLoaded(LyricsData lyrics);
        void onLyricsError(String error);
    }

    public LyricsProviderManager(Context context) {
        cacheManager = new LyricsCacheManager(context);
        providers.add(new SpicyLyricsProvider(context));
        providers.add(new NeteaseLyricsProvider());
        providers.add(new LRCLIBProvider());
    }

    public synchronized void fetchLyrics(TrackInfo track, LyricsCallback callback) {
        if (track == null || callback == null || !track.isValid()) {
            if (callback != null) callback.onLyricsError("Invalid track metadata");
            return;
        }

        cancelPendingLocked();
        long requestGeneration = generation.incrementAndGet();
        try {
            pendingTask = executor.submit(() -> runFetch(track, callback, requestGeneration));
        } catch (RejectedExecutionException e) {
            postError(callback, requestGeneration, "Lyrics service is shutting down");
        }
    }

    private void runFetch(TrackInfo track, LyricsCallback callback, long requestGeneration) {
        try {
            LyricsData cached = cacheManager.getCached(track);
            if (isCancelled(requestGeneration)) return;
            if (cached != null && !cached.isEmpty() && cached.type == LyricsData.Type.SYLLABLE) {
                AppLog.d(TAG, "Cache hit (SYLLABLE) for: " + track);
                postLoaded(callback, requestGeneration, cached);
                return;
            }

            LyricsData bestLyrics = cached != null && !cached.isEmpty() ? cached : null;
            String bestProvider = bestLyrics != null ? "cache" : null;
            boolean improvedCache = false;

            for (LyricsProvider provider : providers) {
                if (isCancelled(requestGeneration)
                        || (bestLyrics != null && bestLyrics.type == LyricsData.Type.SYLLABLE)) break;
                try {
                    AppLog.d(TAG, "Trying provider: " + provider.getName() + " for: " + track);
                    LyricsData lyrics = provider.fetchLyrics(track);
                    if (isCancelled(requestGeneration)) return;
                    if (lyrics != null && !lyrics.isEmpty() && lyrics.type != null
                            && betterThan(lyrics, bestLyrics)) {
                        bestLyrics = lyrics;
                        bestProvider = provider.getName();
                        improvedCache = true;
                    }
                } catch (Exception e) {
                    if (!isCancelled(requestGeneration)) {
                        AppLog.w(TAG, "Provider " + provider.getName() + " failed: " + e.getMessage());
                    }
                }
            }

            if (isCancelled(requestGeneration)) return;
            if (bestLyrics != null) {
                LyricsData result = bestLyrics;
                AppLog.i(TAG, "Got lyrics from " + bestProvider + " - type: " + result.type
                        + " lines: " + result.lines.size());
                if (improvedCache) cacheManager.cache(track, result);
                postLoaded(callback, requestGeneration, result);
                return;
            }

            AppLog.w(TAG, "No lyrics found for: " + track);
            postError(callback, requestGeneration, "No lyrics found for: " + track);
        } catch (Exception e) {
            AppLog.e(TAG, "Lyrics fetch failed", e);
            postError(callback, requestGeneration, "Lyrics fetch failed: " + e.getMessage());
        }
    }

    private boolean isCancelled(long requestGeneration) {
        return Thread.currentThread().isInterrupted() || generation.get() != requestGeneration;
    }

    private void postLoaded(LyricsCallback callback, long requestGeneration, LyricsData lyrics) {
        mainHandler.post(() -> {
            if (generation.get() == requestGeneration) callback.onLyricsLoaded(lyrics);
        });
    }

    private void postError(LyricsCallback callback, long requestGeneration, String error) {
        mainHandler.post(() -> {
            if (generation.get() == requestGeneration) callback.onLyricsError(error);
        });
    }

    private static int typeRank(LyricsData.Type type) {
        switch (type) {
            case SYLLABLE: return 3;
            case LINE: return 2;
            default: return 1;
        }
    }

    private static boolean betterThan(LyricsData candidate, LyricsData current) {
        if (current == null) return true;
        return typeRank(candidate.type) > typeRank(current.type);
    }

    public synchronized void cancelPending() {
        generation.incrementAndGet();
        cancelPendingLocked();
    }

    private void cancelPendingLocked() {
        if (pendingTask != null) {
            pendingTask.cancel(true);
            pendingTask = null;
        }
        for (LyricsProvider provider : providers) provider.cancelPendingRequests();
    }

    public synchronized void shutdown() {
        if (executor.isShutdown()) return;
        generation.incrementAndGet();
        cancelPendingLocked();
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        for (LyricsProvider provider : providers) provider.close();
        cacheManager.close();
    }
}
