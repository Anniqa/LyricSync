package com.lyricsync.app.ai.service;

import android.content.Context;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.lyrics.cache.LyricsCacheManager;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.lyrics.provider.KugouLyricsProvider;
import com.lyricsync.app.lyrics.provider.LRCLIBProvider;
import com.lyricsync.app.lyrics.provider.NeteaseLyricsProvider;
import com.lyricsync.app.lyrics.provider.SpicyLyricsProvider;
import com.lyricsync.app.lyrics.util.TrackMetadataSanitizer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intelligent AI-assisted lyrics retrieval and metadata disambiguation service.
 *
 * Enforces strict authenticity constraints:
 * 1. ZERO hallucinated/generated lyrics - only authentic lyrics from Kugou (KRC),
 *    Netease (YRC/LRC), SpicyLyrics (TTML), and LRCLIB (LRC) are accepted.
 * 2. Strict performer-level resolution for cover/live tracks: Never queries or
 *    substitutes original song timing when a cover performer is identified.
 * 3. Priority hierarchy: Syllable (KRC/YRC/TTML) -> Line-synced (LRC) -> Graceful not found.
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
    private final NeteaseLyricsProvider neteaseProvider;
    private final LRCLIBProvider lrclibProvider;
    private final SpicyLyricsProvider spicyLyricsProvider;
    private final LyricsCacheManager cacheManager;
    private final AntigravityApiClient apiClient;
    private final ExecutorService executor;

    public AiLyricsAgentService(Context context) {
        this(context, null);
    }

    public AiLyricsAgentService(Context context, AntigravityApiClient apiClient) {
        this(new KugouLyricsProvider(),
             new NeteaseLyricsProvider(),
             new LRCLIBProvider(),
             context != null ? new SpicyLyricsProvider(context) : null,
             context != null ? new LyricsCacheManager(context) : null,
             apiClient,
             Executors.newCachedThreadPool());
    }

    public AiLyricsAgentService(KugouLyricsProvider kugouProvider,
                                LRCLIBProvider lrclibProvider,
                                LyricsCacheManager cacheManager) {
        this(kugouProvider, null, lrclibProvider, null, cacheManager, null, Executors.newCachedThreadPool());
    }

    public AiLyricsAgentService(KugouLyricsProvider kugouProvider,
                                LRCLIBProvider lrclibProvider,
                                LyricsCacheManager cacheManager,
                                ExecutorService executor) {
        this(kugouProvider, null, lrclibProvider, null, cacheManager, null, executor);
    }

    public AiLyricsAgentService(KugouLyricsProvider kugouProvider,
                                NeteaseLyricsProvider neteaseProvider,
                                LRCLIBProvider lrclibProvider,
                                SpicyLyricsProvider spicyLyricsProvider,
                                LyricsCacheManager cacheManager,
                                AntigravityApiClient apiClient,
                                ExecutorService executor) {
        this.kugouProvider = kugouProvider != null ? kugouProvider : new KugouLyricsProvider();
        this.neteaseProvider = neteaseProvider;
        this.lrclibProvider = lrclibProvider != null ? lrclibProvider : new LRCLIBProvider();
        this.spicyLyricsProvider = spicyLyricsProvider;
        this.cacheManager = cacheManager;
        this.apiClient = apiClient;
        this.executor = executor != null ? executor : Executors.newCachedThreadPool();
    }

    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
        }
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
        if (!cancellable.isCancelled() && callback != null) {
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

        List<String> searchQueries = new ArrayList<>();

        // 1a. If AI client is available and authenticated, request intelligent disambiguation and queries
        if (apiClient != null && apiClient.isAuthenticated()) {
            AiDisambiguationResult aiResult = requestAiDisambiguation(track, cancellable);
            if (aiResult != null) {
                if (aiResult.isCover && aiResult.performer != null && !aiResult.performer.trim().isEmpty()) {
                    queryTrack.artist = aiResult.performer.trim();
                }
                if (aiResult.canonicalTitle != null && !aiResult.canonicalTitle.trim().isEmpty()) {
                    queryTrack.title = aiResult.canonicalTitle.trim();
                }
                for (String q : aiResult.searchQueries) {
                    if (q != null && !q.trim().isEmpty() && !searchQueries.contains(q.trim())) {
                        searchQueries.add(q.trim());
                    }
                }
            }
        }

        // Add heuristic queries if not already present
        String h1 = (queryTrack.title + " " + queryTrack.artist).trim();
        String h2 = (queryTrack.artist + " " + queryTrack.title).trim();
        String h3 = queryTrack.title.trim();
        if (!searchQueries.contains(h1)) searchQueries.add(h1);
        if (!searchQueries.contains(h2)) searchQueries.add(h2);
        if (!searchQueries.contains(h3)) searchQueries.add(h3);

        // Step 2: Fetch Syllable Lyrics (Kugou KRC / Netease YRC / SpicyLyrics TTML)
        if (cancellable.isCancelled()) return;
        if (!cancellable.isCancelled() && callback != null) {
            callback.onStepChanged(STEP_FETCHING_SYLLABLE, "Searching syllable lyrics (KRC / YRC / TTML)…");
        }
        if (cancellable.isCancelled()) return;

        // 2a. Kugou KRC
        LyricsData syllableLyrics = null;
        try {
            syllableLyrics = kugouProvider.fetchLyrics(queryTrack);
        } catch (Exception ignored) {}

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

        // 2b. Netease YRC
        if (neteaseProvider != null) {
            for (String q : searchQueries) {
                if (cancellable.isCancelled()) return;
                try {
                    LyricsData neteaseLyrics = neteaseProvider.fetchLyricsByQuery(q, queryTrack.duration);
                    if (neteaseLyrics != null && neteaseLyrics.type == LyricsData.Type.SYLLABLE && !neteaseLyrics.isEmpty()) {
                        if (cacheManager != null) {
                            cacheManager.cache(track, neteaseLyrics);
                        }
                        if (!cancellable.isCancelled() && callback != null) {
                            callback.onSuccess(neteaseLyrics);
                        }
                        return;
                    }
                } catch (Exception ignored) {}
            }
        }

        // 2c. SpicyLyrics TTML
        if (spicyLyricsProvider != null && !cancellable.isCancelled()) {
            try {
                LyricsData spicyLyrics = spicyLyricsProvider.fetchLyrics(queryTrack);
                if (spicyLyrics != null && spicyLyrics.type == LyricsData.Type.SYLLABLE && !spicyLyrics.isEmpty()) {
                    if (cacheManager != null) {
                        cacheManager.cache(track, spicyLyrics);
                    }
                    if (!cancellable.isCancelled() && callback != null) {
                        callback.onSuccess(spicyLyrics);
                    }
                    return;
                }
            } catch (Exception ignored) {}
        }

        // Step 3: Fallback to Line-Synced Lyrics (LRCLIB LRC / Netease LRC / Kugou LRC)
        if (cancellable.isCancelled()) return;
        if (!cancellable.isCancelled() && callback != null) {
            callback.onStepChanged(STEP_FALLBACK_LRC, "Syllable not found, searching line format (LRC)…");
        }
        if (cancellable.isCancelled()) return;

        // 3a. LRCLIB with queryTrack
        LyricsData lrcLyrics = null;
        try {
            lrcLyrics = lrclibProvider.fetchLyrics(queryTrack);
        } catch (Exception ignored) {}

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

        // 3b. LRCLIB with multi-queries (only if lrcLyrics was not found)
        if (lrcLyrics == null) {
            for (String q : searchQueries) {
                if (cancellable.isCancelled()) return;
                try {
                    LyricsData lrc = lrclibProvider.fetchLyricsByQuery(q, queryTrack.duration);
                    if (lrc != null && lrc.isSynced()) {
                        if (cacheManager != null) {
                            cacheManager.cache(track, lrc);
                        }
                        if (!cancellable.isCancelled() && callback != null) {
                            callback.onSuccess(lrc);
                        }
                        return;
                    }
                } catch (Exception ignored) {}
            }
        }

        // 3c. Netease LRC
        if (neteaseProvider != null) {
            for (String q : searchQueries) {
                if (cancellable.isCancelled()) return;
                try {
                    LyricsData lrc = neteaseProvider.fetchLyricsByQuery(q, queryTrack.duration);
                    if (lrc != null && lrc.isSynced()) {
                        if (cacheManager != null) {
                            cacheManager.cache(track, lrc);
                        }
                        if (!cancellable.isCancelled() && callback != null) {
                            callback.onSuccess(lrc);
                        }
                        return;
                    }
                } catch (Exception ignored) {}
            }
        }

        // 3d. Kugou line-synced LRC if retrieved earlier
        if (syllableLyrics != null && syllableLyrics.isSynced()) {
            if (cacheManager != null) {
                cacheManager.cache(track, syllableLyrics);
            }
            if (!cancellable.isCancelled() && callback != null) {
                callback.onSuccess(syllableLyrics);
            }
            return;
        }

        // Neither provider returned synced lyrics
        if (!cancellable.isCancelled() && callback != null) {
            callback.onError("No synced lyrics found for this version.");
        }
    }

    private static class AiDisambiguationResult {
        String canonicalTitle;
        String canonicalArtist;
        boolean isCover;
        String performer;
        List<String> searchQueries = new ArrayList<>();
    }

    private AiDisambiguationResult requestAiDisambiguation(TrackInfo track, ServiceCancellable cancellable) {
        if (apiClient == null || cancellable.isCancelled()) return null;

        final CountDownLatch latch = new CountDownLatch(1);
        final String[] responseHolder = new String[1];

        String prompt = "You are an expert music metadata disambiguation assistant.\n"
                + "Analyze this playing track:\n"
                + "Title: " + (track.title != null ? track.title : "") + "\n"
                + "Artist: " + (track.artist != null ? track.artist : "") + "\n\n"
                + "Respond strictly with valid JSON only in this format:\n"
                + "{\n"
                + "  \"canonical_title\": \"Clean Title\",\n"
                + "  \"canonical_artist\": \"Clean Artist\",\n"
                + "  \"is_cover\": false,\n"
                + "  \"performer\": null,\n"
                + "  \"search_queries\": [\"Query 1\", \"Query 2\", \"Query 3\"]\n"
                + "}\n"
                + "Rules:\n"
                + "- If this is a cover song (e.g. 'Cover by X', 'X Cover'), is_cover must be true and performer must be 'X'. Search queries must ONLY feature performer 'X' (NEVER original artist audio timing)!\n"
                + "- For Asian languages (Japanese, Korean, Chinese), provide both native script and Romanized queries.\n"
                + "- Omit video tags, noise, resolutions.\n"
                + "- Output pure JSON, no markdown fences, no comments.";

        List<com.lyricsync.app.ai.model.AiChatMessage> messages = new ArrayList<>();
        messages.add(com.lyricsync.app.ai.model.AiChatMessage.user(prompt));

        try {
            AntigravityApiClient.Cancellable call = apiClient.generateContent(
                    "gemini-2.5-flash",
                    messages,
                    false,
                    "low",
                    new AntigravityApiClient.CompletionCallback() {
                        @Override
                        public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                            responseHolder[0] = fullText;
                            latch.countDown();
                        }

                        @Override
                        public void onError(Exception error) {
                            latch.countDown();
                        }
                    });
            cancellable.setNestedCall(call);

            // Wait up to 3.5 seconds
            latch.await(3500, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            return null;
        }

        if (responseHolder[0] != null && !responseHolder[0].trim().isEmpty()) {
            return parseAiJson(responseHolder[0]);
        }
        return null;
    }

    private static AiDisambiguationResult parseAiJson(String text) {
        try {
            String json = text.trim();
            if (json.startsWith("```json")) {
                json = json.substring(7);
            } else if (json.startsWith("```")) {
                json = json.substring(3);
            }
            if (json.endsWith("```")) {
                json = json.substring(0, json.length() - 3);
            }
            json = json.trim();

            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            AiDisambiguationResult res = new AiDisambiguationResult();
            if (obj.has("canonical_title") && !obj.get("canonical_title").isJsonNull()) {
                res.canonicalTitle = obj.get("canonical_title").getAsString().trim();
            }
            if (obj.has("canonical_artist") && !obj.get("canonical_artist").isJsonNull()) {
                res.canonicalArtist = obj.get("canonical_artist").getAsString().trim();
            }
            if (obj.has("is_cover") && !obj.get("is_cover").isJsonNull()) {
                res.isCover = obj.get("is_cover").getAsBoolean();
            }
            if (obj.has("performer") && !obj.get("performer").isJsonNull()) {
                res.performer = obj.get("performer").getAsString().trim();
            }
            if (obj.has("search_queries") && obj.get("search_queries").isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray("search_queries")) {
                    if (el != null && !el.isJsonNull()) {
                        String q = el.getAsString().trim();
                        if (!q.isEmpty()) {
                            res.searchQueries.add(q);
                        }
                    }
                }
            }
            return res;
        } catch (Throwable t) {
            return null;
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
        private volatile AntigravityApiClient.Cancellable nestedCall;

        void setFuture(Future<?> future) {
            this.future = future;
        }

        void setNestedCall(AntigravityApiClient.Cancellable nestedCall) {
            this.nestedCall = nestedCall;
            if (cancelled && nestedCall != null) {
                nestedCall.cancel();
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            if (future != null) {
                future.cancel(true);
            }
            if (nestedCall != null) {
                nestedCall.cancel();
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}
