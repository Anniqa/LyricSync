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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LyricsProviderManager {
    private static final String TAG = "LyricsProviderManager";

    private final List<LyricsProvider> providers = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final LyricsCacheManager cacheManager;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private static final java.util.Map<String, List<LyricsCallback>> inFlightRequests = new java.util.HashMap<>();
    private static final Object inFlightLock = new Object();
    public interface LyricsCallback {
        void onLyricsLoaded(LyricsData lyrics);
        void onLyricsError(String error);
    }

    public LyricsProviderManager(Context context) {
        cacheManager = new LyricsCacheManager(context);
        providers.add(new SpicyLyricsProvider(context));
        providers.add(new SpotifyColorLyricsProvider(context));
        providers.add(new NeteaseLyricsProvider());
        providers.add(new KugouLyricsProvider());
        providers.add(new LRCLIBProvider());
    }

    public void fetchLyrics(TrackInfo track, LyricsCallback callback) {
        fetchLyrics(track, null, callback);
    }

    public void fetchLyrics(TrackInfo track, String targetProvider, LyricsCallback callback) {
        if (track == null) {
            if (callback != null) {
                mainHandler.post(() -> callback.onLyricsError("Track is null"));
            }
            return;
        }

        String fetchKey = (track.title + "|||" + track.artist + "|||" + (targetProvider != null ? targetProvider : "all")).toLowerCase().trim();

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

    private void performFetch(TrackInfo track, String targetProvider, String fetchKey) {
        // Sanitize metadata before querying
        TrackMetadataSanitizer.SanitizedMetadata meta = TrackMetadataSanitizer.sanitize(track);
        TrackInfo queryTrack = new TrackInfo();
        queryTrack.title = !meta.cleanTitle.isEmpty() ? meta.cleanTitle : track.title;
        queryTrack.artist = !meta.cleanArtist.isEmpty() ? meta.cleanArtist : track.artist;
        queryTrack.album = track.album;
        queryTrack.albumArtUri = track.albumArtUri;
        queryTrack.albumArtBitmap = track.albumArtBitmap;
        queryTrack.duration = track.duration;
        queryTrack.packageName = track.packageName;
        queryTrack.trackId = track.trackId;

        LyricsData cached = cacheManager.getCached(track);
        if (cached == null || cached.isEmpty()) {
            cached = cacheManager.getCached(queryTrack);
        }
        if (cached != null && !cached.isEmpty() && cached.type == LyricsData.Type.SYLLABLE) {
            AppLog.d(TAG, "Cache hit (SYLLABLE) for: " + track);
            dispatchSuccess(fetchKey, cached);
            return;
        }

        LyricsData bestLyrics = cached != null && !cached.isEmpty() ? cached : null;
        String bestProvider = bestLyrics != null ? "cache" : null;
        final String normTarget = (targetProvider != null && !targetProvider.trim().isEmpty() && !"all".equalsIgnoreCase(targetProvider.trim()))
                ? targetProvider.trim().toLowerCase() : null;

        for (LyricsProvider provider : providers) {
            if (normTarget != null) {
                String pn = provider.getName().toLowerCase();
                if (!pn.contains(normTarget) && !normTarget.contains(pn)) {
                    if (!("spotify".equals(normTarget) && pn.contains("spicy"))) {
                        continue;
                    }
                }
            }
            if (bestLyrics != null && bestLyrics.type == LyricsData.Type.SYLLABLE) break;
            try {
                AppLog.d(TAG, "Trying provider: " + provider.getName() + " for: " + queryTrack);
                LyricsData lyrics = provider.fetchLyrics(queryTrack);

                if (lyrics != null && !lyrics.isEmpty() && betterThan(lyrics, bestLyrics)) {
                    bestLyrics = lyrics;
                    bestProvider = provider.getName();
                }
            } catch (Exception e) {
                AppLog.w(TAG, "Provider " + provider.getName() + " failed: " + e.getMessage());
            }
        }

        // Re-check cache in case a parallel or earlier task stored SYLLABLE lyrics
        LyricsData freshCache = cacheManager.getCached(track);
        if (freshCache != null && !freshCache.isEmpty() && betterThan(freshCache, bestLyrics)) {
            bestLyrics = freshCache;
            bestProvider = "cache (" + (freshCache.provider != null ? freshCache.provider : "cached") + ")";
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

    private void dispatchSuccess(String fetchKey, LyricsData result) {
        List<LyricsCallback> callbacks;
        synchronized (inFlightLock) {
            callbacks = inFlightRequests.remove(fetchKey);
        }
        if (callbacks != null) {
            for (LyricsCallback cb : callbacks) {
                mainHandler.post(() -> cb.onLyricsLoaded(result));
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
                mainHandler.post(() -> cb.onLyricsError(error));
            }
        }
    }

    public void updateCachedLyrics(TrackInfo track, LyricsData lyrics) {
        if (track == null || lyrics == null) return;
        executor.execute(() -> cacheManager.cache(track, lyrics));
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
        if (candidate == null) return false;
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
