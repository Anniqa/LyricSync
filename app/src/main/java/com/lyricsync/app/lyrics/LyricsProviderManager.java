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
import com.lyricsync.app.lyrics.provider.SpotifyColorLyricsProvider;
import com.lyricsync.app.lyrics.util.TrackMetadataSanitizer;
import com.lyricsync.app.util.AppLog;

import java.util.ArrayList;
import java.util.Collections;
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

        // Prioritize fast, high-coverage SYLLABLE providers first!
        providers.add(new KugouLyricsProvider());
        providers.add(new NeteaseLyricsProvider());
        providers.add(new SpicyLyricsProvider(context));
        providers.add(new SpotifyColorLyricsProvider(context));
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

        executor.execute(() -> {
            try {
                performFetch(track, targetProvider, fetchKey);
            } catch (Throwable t) {
                AppLog.e(TAG, "Unexpected error in fetchLyrics", t);
                dispatchError(fetchKey, "Fetch failed: " + t.getMessage());
            }
        });
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
        return name.contains("kugou") || name.contains("netease") || name.contains("spicy") || name.contains("spotify");
    }

    private static boolean providerMatches(LyricsProvider provider, String normTarget) {
        if (normTarget == null) return true;
        if (isSyllableTarget(normTarget)) {
            return isSyllableProvider(provider);
        }
        String pn = provider.getName().toLowerCase(Locale.ROOT);
        if (pn.contains(normTarget) || normTarget.contains(pn)) return true;
        if ("spotify".equals(normTarget) && pn.contains("spicy")) return true;
        return false;
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

        // If track is from Spotify and target is all/default, prioritize Spotify/Spicy first
        if (normTarget == null && queryTrack.trackId != null
                && (queryTrack.trackId.startsWith("spotify:") || queryTrack.trackId.matches("[A-Za-z0-9]{22}"))) {
            List<LyricsProvider> reordered = new ArrayList<>();
            for (LyricsProvider p : activeProviders) {
                if (p.getName().toLowerCase(Locale.ROOT).contains("spotify") || p.getName().toLowerCase(Locale.ROOT).contains("spicy")) {
                    reordered.add(p);
                }
            }
            for (LyricsProvider p : activeProviders) {
                if (!reordered.contains(p)) {
                    reordered.add(p);
                }
            }
            activeProviders = reordered;
        }

        for (LyricsProvider provider : activeProviders) {
            if (bestLyrics != null && bestLyrics.type == LyricsData.Type.SYLLABLE) {
                break; // Syllable found! Early break to avoid slower/line providers
            }
            try {
                AppLog.d(TAG, "Trying provider: " + provider.getName() + " for: " + queryTrack);
                LyricsData lyrics = provider.fetchLyrics(queryTrack);

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

    private static int typeRank(LyricsData.Type type) {
        if (type == null) return 0;
        switch (type) {
            case SYLLABLE: return 3;
            case LINE: return 2;
            default: return 1;
        }
    }

    public static boolean betterThan(LyricsData candidate, LyricsData current) {
        if (current == null) return candidate != null && !candidate.isEmpty();
        if (candidate == null || candidate.isEmpty()) return false;
        int candRank = typeRank(candidate.type);
        int currRank = typeRank(current.type);
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
