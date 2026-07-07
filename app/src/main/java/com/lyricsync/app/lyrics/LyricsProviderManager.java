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
            if (cached != null && !cached.isEmpty()) {
                AppLog.d(TAG, "Cache hit for: " + track);
                callback.onLyricsLoaded(cached);
                return;
            }

            LyricsData bestLyrics = null;
            String bestProvider = null;

            for (LyricsProvider provider : providers) {
                try {
                    AppLog.d(TAG, "Trying provider: " + provider.getName() + " for: " + track);
                    LyricsData lyrics = provider.fetchLyrics(track);

                    if (lyrics != null && !lyrics.isEmpty()) {
                        bestLyrics = lyrics;
                        bestProvider = provider.getName();
                        break;
                    }
                } catch (Exception e) {
                    AppLog.w(TAG, "Provider " + provider.getName() + " failed: " + e.getMessage());
                }
            }

            if (bestLyrics != null) {
                AppLog.i(TAG, "Got lyrics from " + bestProvider
                        + " - type: " + bestLyrics.type
                        + " lines: " + bestLyrics.lines.size());

                cacheManager.cache(track, bestLyrics);
                callback.onLyricsLoaded(bestLyrics);
                return;
            }

            AppLog.w(TAG, "No lyrics found for: " + track);
            callback.onLyricsError("No lyrics found for: " + track);
        });
    }

    public void shutdown() {
        executor.shutdown();
    }
}
