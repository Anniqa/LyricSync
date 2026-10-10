# Dead-code removal: legacy SpicyLyrics /query transport

## Files changed
- app/src/main/java/com/lyricsync/app/lyrics/provider/SpicyLyricsProvider.java (edited)
- app/src/main/java/com/lyricsync/app/lyrics/provider/SpicyLyricsObjPack.java (deleted)
- app/src/test/java/com/lyricsync/app/lyrics/provider/SpicyLyricsObjPackTest.java (deleted)

## What was removed from SpicyLyricsProvider.java
- Legacy POST /query transport: API_URL query constant, request body build, retry loop,
  429/5xx backoff sleeps and rate-limit sleep (MIN_REQUEST_INTERVAL_MS).
- Session handshake: ensureSession(...), sessionToken, sessionCreatedAt, SESSION_TTL_MS.
- Embed-token code: getCachedToken, fetchEmbedToken, tryFetchEmbedPage, cachedEmbedToken,
  embedTokenFetchedAt, EMBED_TOKEN_MAX_AGE_MS, EMBED_URL, and the public invalidateEmbedToken().
- Response decoding for the legacy route: parseResponse(...) and SpicyLyricsObjPack.unpack usage.
- Now-dead constants: SPICY_VERSION, SPICY_ORIGIN, MAX_RETRIES, RETRY_DELAYS_MS, JSON MediaType,
  lastRequestTime field. Unused imports okhttp3.MediaType and okhttp3.RequestBody.
- fetchLyrics no longer falls back to /query after an official API failure; the official
  /v1 exception propagates (still IOException with a clear message).

## Deleted classes
- SpicyLyricsObjPack + SpicyLyricsObjPackTest. grep over app/src found the only reference was
  SpicyLyricsObjPack.unpack inside the removed parseResponse; no other source or test uses them.

## Kept (verified)
- Public API: class name, SpicyLyricsProvider(Context), getName(), fetchLyrics(TrackInfo),
  PREF_SPICY_CLIENT_KEY.
- Official route: fetchOfficialLyrics, getClientKey, DEFAULT_CLIENT_KEY, OFFICIAL_API_URL,
  SPICY_USER_AGENT.
- Track-id resolution: extractSpotifyId, resolveSpotifyTrackId, SpotifyTrackResolver.
- Parsing: parseSyllableLyrics, buildWordsFromSyllables, parseLineLyrics, parseStaticLyrics,
  getTimeMs, safeString, insertInterludes.
- No MainActivity.java or app/src/main/res/ edits. No gradle run.

## Residual risk
- Behavior change: an official /v1 failure now surfaces to callers instead of silently trying the
  (blocked) legacy route. This is intended; callers already handle a thrown exception.
- getClientKey() never returns null (falls back to DEFAULT_CLIENT_KEY); fetchLyrics calls
  fetchOfficialLyrics directly with its result.
- Not compiled locally per instructions. Brace/paren balance checked; all remaining imports are used.
- Stale gradle build artifacts still contain SpicyLyricsObjPack classes; a clean build removes them.
