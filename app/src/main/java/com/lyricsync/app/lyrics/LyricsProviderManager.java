package com.lyricsync.app.lyrics;

import android.content.Context;

import com.lyricsync.app.lyrics.cache.LyricsCacheManager;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.lyrics.provider.KugouLyricsProvider;
import com.lyricsync.app.lyrics.provider.LRCLIBProvider;
import com.lyricsync.app.lyrics.provider.LyricsProvider;
import com.lyricsync.app.lyrics.provider.NeteaseLyricsProvider;
import com.lyricsync.app.lyrics.provider.SpicyLyricsProvider;
import com.lyricsync.app.lyrics.util.TrackMetadataSanitizer;
import com.lyricsync.app.util.AppLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LyricsProviderManager {
    private static final String TAG = "LyricsProviderManager";

    private final List<LyricsProvider> providers = new ArrayList<>();
    private final ExecutorService executor;
    private final LyricsCacheManager cacheManager;
    private final android.os.Handler mainHandler;

    private static final java.util.Map<String, List<LyricsCallback>> inFlightRequests = new java.util.HashMap<>();
    private static final Object inFlightLock = new Object();

    public interface LyricsCallback {
        void onLyricsLoaded(LyricsData lyrics);
        void onLyricsError(String error);
    }

    public LyricsProviderManager(Context context) {
        this.cacheManager = new LyricsCacheManager(context);
        this.executor = Executors.newFixedThreadPool(4);

        android.os.Looper looper = null;
        try {
            looper = android.os.Looper.getMainLooper();
        } catch (Throwable ignored) {}
        this.mainHandler = looper != null ? new android.os.Handler(looper) : null;

        // SpicyLyrics first: it serves the highest-quality word-by-word (syllable)
        // lyrics through the official API. The rest are fallbacks for tracks it
        // cannot answer, and the loop below stops as soon as a SYLLABLE result lands.
        providers.add(new SpicyLyricsProvider(context));
        providers.add(new KugouLyricsProvider());
        providers.add(new NeteaseLyricsProvider());
        providers.add(new LRCLIBProvider());
    }

    public LyricsProviderManager(LyricsCacheManager cacheManager,
                                 List<LyricsProvider> customProviders,
                                 ExecutorService customExecutor,
                                 android.os.Handler customHandler) {
        this.cacheManager = cacheManager != null ? cacheManager : new LyricsCacheManager(null);
        this.executor = customExecutor != null ? customExecutor : Executors.newFixedThreadPool(4);
        this.mainHandler = customHandler;
        if (customProviders != null) {
            this.providers.addAll(customProviders);
        }
    }

    public void fetchLyrics(TrackInfo track, LyricsCallback callback) {
        fetchLyrics(track, null, callback);
    }

    public void fetchLyrics(TrackInfo track, String targetProvider, LyricsCallback callback) {
        if (track == null || track.title == null || track.title.trim().isEmpty()) {
            if (callback != null) {
                postCallback(() -> callback.onLyricsError("Track or title is null/empty"));
            }
            return;
        }

        String fetchKey = (track.title + "|||" + (track.artist != null ? track.artist : "") + "|||" + (targetProvider != null ? targetProvider : "all")).toLowerCase().trim();

        synchronized (inFlightLock) {
            List<LyricsCallback> existing = inFlightRequests.get(fetchKey);
            if (existing != null) {
                if (callback != null) {
                    existing.add(callback);
                }
                AppLog.d(TAG, "Joined in-flight lyrics fetch for: " + track.title);
                return;
            }
            List<LyricsCallback> list = new ArrayList<>();
            if (callback != null) {
                list.add(callback);
            }
            inFlightRequests.put(fetchKey, list);
        }

        try {
            executor.execute(() -> {
                try {
                    performFetch(track, targetProvider, fetchKey);
                } catch (Throwable t) {
                    AppLog.e(TAG, "Unexpected error in fetchLyrics", t);
                    dispatchError(fetchKey, "Fetch failed: " + t.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Executor already shut down: never leave the caller waiting or leak the entry.
            AppLog.w(TAG, "Lyrics fetch rejected (executor shut down)");
            dispatchError(fetchKey, "Fetch rejected: " + e.getMessage());
        }
    }

    public static boolean isSyllableTarget(String target) {
        if (target == null) return false;
        String t = target.trim().toLowerCase(Locale.ROOT);
        return t.contains("syllable") || t.contains("karaoke") || t.contains("suku kata")
                || t.contains("word_by_word") || t.contains("word") || t.contains("krc") || t.contains("yrc");
    }

    private static boolean isSyllableProvider(LyricsProvider provider) {
        if (provider == null) return false;
        String name = provider.getName().toLowerCase(Locale.ROOT);
        return name.contains("kugou") || name.contains("netease") || name.contains("spicy");
    }

    private static boolean providerMatches(LyricsProvider provider, String normTarget) {
        if (normTarget == null) return true;
        if (isSyllableTarget(normTarget)) {
            return isSyllableProvider(provider);
        }
        String pn = provider.getName().toLowerCase(Locale.ROOT);
        return pn.contains(normTarget) || normTarget.contains(pn);
    }

    private void performFetch(TrackInfo track, String targetProvider, String fetchKey) {
        // Sanitize metadata before querying
        TrackMetadataSanitizer.SanitizedMetadata meta = TrackMetadataSanitizer.sanitize(track);
        TrackInfo queryTrack = new TrackInfo();
        queryTrack.title = !meta.cleanTitle.isEmpty() ? meta.cleanTitle : track.title;
        queryTrack.artist = !meta.cleanArtist.isEmpty() ? meta.cleanArtist : (track.artist != null ? track.artist : "");
        queryTrack.album = track.album;
        queryTrack.albumArtUri = track.albumArtUri;
        queryTrack.albumArtBitmap = track.albumArtBitmap;
        queryTrack.duration = track.duration;
        queryTrack.packageName = track.packageName;
        queryTrack.trackId = track.trackId;

        final String normTarget = (targetProvider != null && !targetProvider.trim().isEmpty() && !"all".equalsIgnoreCase(targetProvider.trim()))
                ? targetProvider.trim().toLowerCase(Locale.ROOT) : null;

        final boolean requestingSyllableOnly = isSyllableTarget(normTarget);

        LyricsData cached = cacheManager.getCached(track);
        if (cached == null || cached.isEmpty()) {
            cached = cacheManager.getCached(queryTrack);
        }
        if (cached != null && !cached.isEmpty() && cached.type == LyricsData.Type.SYLLABLE) {
            AppLog.d(TAG, "Cache hit (SYLLABLE) for: " + track);
            dispatchSuccess(fetchKey, cached);
            return;
        }

        // If explicitly requesting syllable, do NOT settle for cached LINE lyrics
        LyricsData bestLyrics = (cached != null && !cached.isEmpty() && !requestingSyllableOnly) ? cached : null;
        String bestProvider = bestLyrics != null ? "cache" : null;

        // Build list of active providers to query
        List<LyricsProvider> activeProviders = new ArrayList<>();
        for (LyricsProvider p : providers) {
            if (providerMatches(p, normTarget)) {
                activeProviders.add(p);
            }
        }

        for (LyricsProvider provider : activeProviders) {
            if (bestLyrics != null && bestLyrics.type == LyricsData.Type.SYLLABLE) {
                break; // Syllable found! Early break to avoid slower/line providers
            }
            try {
                AppLog.d(TAG, "Trying provider: " + provider.getName() + " for: " + queryTrack);
                LyricsData lyrics = provider.fetchLyrics(queryTrack);

                // If not found and track has title aliases, try each alias on this provider
                if ((lyrics == null || lyrics.isEmpty()) && meta.titleAliases != null && !meta.titleAliases.isEmpty()) {
                    for (String alias : meta.titleAliases) {
                        if (alias == null || alias.trim().isEmpty() || alias.equalsIgnoreCase(queryTrack.title)) continue;
                        TrackInfo aliasTrack = new TrackInfo();
                        aliasTrack.title = alias.trim();
                        aliasTrack.artist = queryTrack.artist;
                        aliasTrack.duration = queryTrack.duration;
                        aliasTrack.album = queryTrack.album;
                        aliasTrack.packageName = queryTrack.packageName;
                        aliasTrack.trackId = queryTrack.trackId;
                        try {
                            LyricsData aliasLyrics = provider.fetchLyrics(aliasTrack);
                            if (aliasLyrics != null && !aliasLyrics.isEmpty()) {
                                lyrics = aliasLyrics;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }

                if (lyrics != null && !lyrics.isEmpty()) {
                    if (requestingSyllableOnly && lyrics.type != LyricsData.Type.SYLLABLE) {
                        // Syllable was specifically requested; ignore non-syllable results
                        continue;
                    }
                    if (betterThan(lyrics, bestLyrics)) {
                        bestLyrics = lyrics;
                        bestProvider = provider.getName();
                    }
                }
            } catch (Exception e) {
                AppLog.w(TAG, "Provider " + provider.getName() + " failed: " + e.getMessage());
            }
        }

        // Re-check cache in case a parallel task stored SYLLABLE lyrics
        LyricsData freshCache = cacheManager.getCached(track);
        if (freshCache != null && !freshCache.isEmpty() && betterThan(freshCache, bestLyrics)) {
            if (!requestingSyllableOnly || freshCache.type == LyricsData.Type.SYLLABLE) {
                bestLyrics = freshCache;
                bestProvider = "cache (" + (freshCache.provider != null ? freshCache.provider : "cached") + ")";
            }
        }

        if (bestLyrics != null) {
            final LyricsData result = bestLyrics;
            AppLog.i(TAG, "Got lyrics from " + bestProvider
                    + " - type: " + result.type
                    + " lines: " + result.lines.size());

            cacheManager.cache(track, result);
            dispatchSuccess(fetchKey, result);
            return;
        }

        AppLog.w(TAG, "No lyrics found for: " + track);
        dispatchError(fetchKey, "No lyrics found for: " + track);
    }

    private void postCallback(Runnable action) {
        if (mainHandler != null) {
            mainHandler.post(action);
        } else {
            action.run();
        }
    }

    private void dispatchSuccess(String fetchKey, LyricsData result) {
        List<LyricsCallback> callbacks;
        synchronized (inFlightLock) {
            callbacks = inFlightRequests.remove(fetchKey);
        }
        if (callbacks != null) {
            for (LyricsCallback cb : callbacks) {
                postCallback(() -> cb.onLyricsLoaded(result));
            }
        }
    }

    private void dispatchError(String fetchKey, String error) {
        List<LyricsCallback> callbacks;
        synchronized (inFlightLock) {
            callbacks = inFlightRequests.remove(fetchKey);
        }
        if (callbacks != null) {
            for (LyricsCallback cb : callbacks) {
                postCallback(() -> cb.onLyricsError(error));
            }
        }
    }

    public void updateCachedLyrics(TrackInfo track, LyricsData lyrics) {
        if (track == null || lyrics == null) return;
        executor.execute(() -> cacheManager.cache(track, lyrics));
    }

    public static boolean betterThan(LyricsData candidate, LyricsData current) {
        if (current == null) return candidate != null && !candidate.isEmpty();
        if (candidate == null || candidate.isEmpty()) return false;
        int candRank = LyricsData.rankOf(candidate.type);
        int currRank = LyricsData.rankOf(current.type);
        if (candRank > currRank) return true;
        if (candRank == currRank) {
            int candLines = candidate.lines != null ? candidate.lines.size() : 0;
            int currLines = current.lines != null ? current.lines.size() : 0;
            return candLines > currLines;
        }
        return false;
    }

    public void shutdown() {
        if (executor.isShutdown()) return;
        executor.shutdownNow();
    }
}
