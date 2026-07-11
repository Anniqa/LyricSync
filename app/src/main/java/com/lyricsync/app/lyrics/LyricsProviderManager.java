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

public class LyricsProviderManager {
    private static final String TAG = "LyricsProviderManager";

    private final List<LyricsProvider> providers = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final LyricsCacheManager cacheManager;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

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

    public void fetchLyrics(TrackInfo track, LyricsCallback callback) {
        executor.execute(() -> {
            LyricsData cached = cacheManager.getCached(track);
            if (cached != null && !cached.isEmpty() && cached.type == LyricsData.Type.SYLLABLE) {
                AppLog.d(TAG, "Cache hit (SYLLABLE) for: " + track);
                mainHandler.post(() -> callback.onLyricsLoaded(cached));
                return;
            }

            LyricsData bestLyrics = cached != null && !cached.isEmpty() ? cached : null;
            String bestProvider = bestLyrics != null ? "cache" : null;

            for (LyricsProvider provider : providers) {
                if (bestLyrics != null && bestLyrics.type == LyricsData.Type.SYLLABLE) break;
                try {
                    AppLog.d(TAG, "Trying provider: " + provider.getName() + " for: " + track);
                    LyricsData lyrics = provider.fetchLyrics(track);

                    if (lyrics != null && !lyrics.isEmpty() && betterThan(lyrics, bestLyrics)) {
                        bestLyrics = lyrics;
                        bestProvider = provider.getName();
                    }
                } catch (Exception e) {
                    AppLog.w(TAG, "Provider " + provider.getName() + " failed: " + e.getMessage());
                }
            }

            if (bestLyrics != null) {
                final LyricsData result = bestLyrics;
                AppLog.i(TAG, "Got lyrics from " + bestProvider
                        + " - type: " + result.type
                        + " lines: " + result.lines.size());

                cacheManager.cache(track, result);
                mainHandler.post(() -> callback.onLyricsLoaded(result));
                return;
            }

            AppLog.w(TAG, "No lyrics found for: " + track);
            mainHandler.post(() -> callback.onLyricsError("No lyrics found for: " + track));
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

    public void shutdown() {
        if (executor.isShutdown()) return;
        executor.shutdownNow();
    }
}
