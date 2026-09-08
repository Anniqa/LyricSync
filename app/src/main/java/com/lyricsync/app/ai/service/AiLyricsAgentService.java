package com.lyricsync.app.ai.service;

import android.content.Context;
import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.lyrics.cache.LyricsCacheManager;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.lyrics.provider.KugouLyricsProvider;
import com.lyricsync.app.lyrics.provider.LRCLIBProvider;
import com.lyricsync.app.lyrics.util.TrackMetadataSanitizer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intelligent AI-assisted lyrics retrieval and metadata disambiguation service.
 *
 * Enforces strict authenticity constraints:
 * 1. ZERO hallucinated/generated lyrics - only authentic lyrics from Kugou (KRC)
 *    and LRCLIB (LRC) are accepted.
 * 2. Strict performer-level resolution for cover/live tracks: Never queries or
 *    substitutes original song timing when a cover performer is identified.
 * 3. Priority hierarchy: Syllable (KRC) -> Line-synced (LRC) -> Graceful not found.
 * 4. Automatic caching of authentic lyrics in LyricsCacheManager.
 */
public class AiLyricsAgentService {

    public static final int STEP_DISAMBIGUATING = 1;
    public static final int STEP_FETCHING_SYLLABLE = 2;
    public static final int STEP_FALLBACK_LRC = 3;

    public interface AiLyricsCallback {
        void onStepChanged(int stepCode, String message);
        void onSuccess(LyricsData lyrics);
        void onError(String message);
    }

    private static final Pattern COVER_BY = Pattern.compile(
            "(?i)(?:[\\[\\(\\{]|\\s*[-—–|]\\s*)?\\s*cover\\s+by\\s+([^)\\]\\}|\\-]+?)(?:[\\]\\)\\}]|\\s*[-—–|]|$)"
    );

    private static final Pattern PERFORMER_COVER = Pattern.compile(
            "(?i)(?:[\\[\\(\\{]|\\s*[-—–|]\\s*)([^)\\]\\}|\\-]+?)\\s+cover(?:[\\]\\)\\}]|\\s*[-—–|]|$)"
    );

    private static final Pattern LIVE_BY = Pattern.compile(
            "(?i)(?:[\\[\\(\\{]|\\s*[-—–|]\\s*)?\\s*live\\s+by\\s+([^)\\]\\}|\\-]+?)(?:[\\]\\)\\}]|\\s*[-—–|]|$)"
    );

    private static final Pattern PERFORMER_LIVE = Pattern.compile(
            "(?i)(?:[\\[\\(\\{]|\\s*[-—–|]\\s*)([^)\\]\\}|\\-]+?)\\s+live(?:[\\]\\)\\}]|\\s*[-—–|]|$)"
    );

    private static final Pattern FEAT_PERFORMER = Pattern.compile(
            "(?i)(?:[\\[\\(\\{]|\\s*[-—–|]\\s*)?\\s*(?:feat\\.?|ft\\.?)\\s+([^)\\]\\}|\\-]+?)(?:[\\]\\)\\}]|\\s*[-—–|]|$)"
    );

    private static final Pattern LIVE_VENUE = Pattern.compile(
            "(?i)\\s*[\\[\\(\\{]\\s*live\\s+(?:at|from|in)\\b[^)\\]\\}]*[\\]\\)\\}]|\\s*[-—–|]\\s*live\\s+(?:at|from|in)\\b.*$"
    );

    private static final Pattern GENERIC_COVER = Pattern.compile(
            "(?i)\\s*[\\[\\(\\{]\\s*cover\\s*[\\]\\)\\}]|\\s*[-—–|]\\s*cover\\b"
    );

    private final KugouLyricsProvider kugouProvider;
    private final LRCLIBProvider lrclibProvider;
    private final LyricsCacheManager cacheManager;
    private final ExecutorService executor;

    public AiLyricsAgentService(Context context) {
        this(new KugouLyricsProvider(),
             new LRCLIBProvider(),
             context != null ? new LyricsCacheManager(context) : null,
             Executors.newCachedThreadPool());
    }

    public AiLyricsAgentService(KugouLyricsProvider kugouProvider,
                                LRCLIBProvider lrclibProvider,
                                LyricsCacheManager cacheManager) {
        this(kugouProvider, lrclibProvider, cacheManager, Executors.newCachedThreadPool());
    }

    public AiLyricsAgentService(KugouLyricsProvider kugouProvider,
                                LRCLIBProvider lrclibProvider,
                                LyricsCacheManager cacheManager,
                                ExecutorService executor) {
        this.kugouProvider = kugouProvider != null ? kugouProvider : new KugouLyricsProvider();
        this.lrclibProvider = lrclibProvider != null ? lrclibProvider : new LRCLIBProvider();
        this.cacheManager = cacheManager;
        this.executor = executor != null ? executor : Executors.newCachedThreadPool();
    }

    public AntigravityApiClient.Cancellable retrieveLyrics(final TrackInfo track, final AiLyricsCallback callback) {
        final ServiceCancellable cancellable = new ServiceCancellable();

        if (track == null || !track.isValid()) {
            Future<?> future = executor.submit(new Runnable() {
                @Override
                public void run() {
                    if (!cancellable.isCancelled() && callback != null) {
                        callback.onError("Invalid track metadata.");
                    }
                }
            });
            cancellable.setFuture(future);
            return cancellable;
        }

        Future<?> future = executor.submit(new Runnable() {
            @Override
            public void run() {
                executeRetrieve(track, callback, cancellable);
            }
        });
        cancellable.setFuture(future);
        return cancellable;
    }

    private void executeRetrieve(TrackInfo track, AiLyricsCallback callback, ServiceCancellable cancellable) {
        // Step 1: Disambiguate Metadata
        if (cancellable.isCancelled()) return;
        if (callback != null) {
            callback.onStepChanged(STEP_DISAMBIGUATING, "Analyzing song metadata…");
        }
        if (cancellable.isCancelled()) return;

        TrackInfo queryTrack = disambiguateTrack(track);
        if (queryTrack == null || !queryTrack.isValid()) {
            if (!cancellable.isCancelled() && callback != null) {
                callback.onError("Invalid track metadata.");
            }
            return;
        }

        // Step 2: Fetch Syllable Lyrics (Kugou KRC)
        if (cancellable.isCancelled()) return;
        if (callback != null) {
            callback.onStepChanged(STEP_FETCHING_SYLLABLE, "Searching syllable lyrics (KRC / YRC / TTML)…");
        }
        if (cancellable.isCancelled()) return;

        LyricsData syllableLyrics = null;
        try {
            syllableLyrics = kugouProvider.fetchLyrics(queryTrack);
        } catch (Exception ignored) {
            // Error in Kugou provider; proceed to fallback
        }

        if (cancellable.isCancelled()) return;

        if (syllableLyrics != null && syllableLyrics.type == LyricsData.Type.SYLLABLE && !syllableLyrics.isEmpty()) {
            if (cacheManager != null) {
                cacheManager.cache(track, syllableLyrics);
            }
            if (!cancellable.isCancelled() && callback != null) {
                callback.onSuccess(syllableLyrics);
            }
            return;
        }

        // Step 3: Fallback to Line-Synced Lyrics (LRCLIB LRC)
        if (cancellable.isCancelled()) return;
        if (callback != null) {
            callback.onStepChanged(STEP_FALLBACK_LRC, "Syllable not found, searching line format (LRC)…");
        }
        if (cancellable.isCancelled()) return;

        LyricsData lrcLyrics = null;
        try {
            lrcLyrics = lrclibProvider.fetchLyrics(queryTrack);
        } catch (Exception ignored) {
            // Error in LRCLIB provider
        }

        if (cancellable.isCancelled()) return;

        if (lrcLyrics != null && lrcLyrics.isSynced()) {
            if (cacheManager != null) {
                cacheManager.cache(track, lrcLyrics);
            }
            if (!cancellable.isCancelled() && callback != null) {
                callback.onSuccess(lrcLyrics);
            }
            return;
        }

        // Neither provider returned synced lyrics
        if (!cancellable.isCancelled() && callback != null) {
            callback.onError("No synced lyrics found for this version.");
        }
    }

    /**
     * Disambiguates track metadata by extracting performing cover artists,
     * stripping noise descriptors, and sanitizing native script aliases.
     */
    public static TrackInfo disambiguateTrack(TrackInfo track) {
        if (track == null) return null;

        String rawTitle = track.title != null ? track.title.trim() : "";
        String rawArtist = track.artist != null ? track.artist.trim() : "";

        String performer = null;
        String title = rawTitle;

        // 1. Cover by [Performer]
        Matcher m = COVER_BY.matcher(title);
        if (m.find()) {
            performer = m.group(1).trim();
            title = m.replaceFirst("").trim();
        }

        // 2. [Performer] Cover
        if (performer == null) {
            m = PERFORMER_COVER.matcher(title);
            if (m.find()) {
                performer = m.group(1).trim();
                title = m.replaceFirst("").trim();
            }
        }

        // 3. Live by [Performer]
        if (performer == null) {
            m = LIVE_BY.matcher(title);
            if (m.find()) {
                performer = m.group(1).trim();
                title = m.replaceFirst("").trim();
            }
        }

        // 4. [Performer] Live
        if (performer == null) {
            m = PERFORMER_LIVE.matcher(title);
            if (m.find()) {
                performer = m.group(1).trim();
                title = m.replaceFirst("").trim();
            }
        }

        // 5. feat. [Performer]
        if (performer == null) {
            m = FEAT_PERFORMER.matcher(title);
            if (m.find()) {
                performer = m.group(1).trim();
                title = m.replaceFirst("").trim();
            }
        }

        // 6. Clean live venues and generic cover tags
        title = LIVE_VENUE.matcher(title).replaceAll("").trim();
        title = GENERIC_COVER.matcher(title).replaceAll("").trim();

        // 7. Clean residual separators and spaces
        title = title.replaceAll("\\s+", " ").replaceAll("^[\\s\\-|—–]+|[\\s\\-|—–]+$", "").trim();

        // Determine target artist:
        // If a cover performer was explicitly extracted, use the performer.
        // Otherwise retain rawArtist.
        String targetArtist = (performer != null && !performer.isEmpty()) ? performer : rawArtist;

        // Run through TrackMetadataSanitizer for native script / alias / noise cleanup
        TrackMetadataSanitizer.SanitizedMetadata sanitized = TrackMetadataSanitizer.sanitize(title, targetArtist);

        TrackInfo disambiguated = new TrackInfo();
        disambiguated.title = (sanitized.cleanTitle != null && !sanitized.cleanTitle.isEmpty())
                ? sanitized.cleanTitle : title;
        disambiguated.artist = (sanitized.cleanArtist != null && !sanitized.cleanArtist.isEmpty())
                ? sanitized.cleanArtist : targetArtist;
        disambiguated.album = track.album;
        disambiguated.duration = track.duration;
        disambiguated.albumArtUri = track.albumArtUri;
        disambiguated.albumArtBitmap = track.albumArtBitmap;
        disambiguated.packageName = track.packageName;
        disambiguated.trackId = track.trackId;

        return disambiguated;
    }

    private static class ServiceCancellable implements AntigravityApiClient.Cancellable {
        private volatile boolean cancelled = false;
        private volatile Future<?> future;

        void setFuture(Future<?> future) {
            this.future = future;
        }

        @Override
        public void cancel() {
            cancelled = true;
            if (future != null) {
                future.cancel(true);
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}
