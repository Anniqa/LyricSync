# Report: ai/ scope (agent "ai")

Scope reviewed: `app/src/main/java/com/lyricsync/app/ai/**` EXCEPT `ai/auth/**`.
Verified by compiling the scope with `javac` (android.jar + okhttp/gson/okio from the
Gradle cache) and running the JUnit suites for `AntigravityApiClientTest`,
`AntigravityApiClientToolLoopTest`, `SongChatManagerTest`, `SongMeaningServiceTest`
(86 tests, all pass). No Gradle was run.

## Findings

1. **`AntigravityApiClient.dispatchOnDelta` delivered every text delta twice to
   `onChunk`-only listeners** (`AntigravityApiClient.java:1379`).
   `StreamListener.onDelta()` delegates to `onChunk()` by default, but the dispatcher
   called `onDelta(...)` and then `onChunk(...)` explicitly. A listener overriding only
   the legacy `onChunk` got each token twice (double rendering/duplicate accumulation).
   `onError`, `onComplete` do not have this problem.

2. **Unguarded tool-argument parsing could hang a stream forever**
   (`AntigravityApiClient.java:982-1043`, `handleToolExecution`).
   The `queryOrUrl` label block ran *before* the tool-dispatch try/catch and called
   `getAsInt()` / `getAsBoolean()` after only an `isJsonNull()` check. If the model sent a
   non-numeric/non-primitive argument (e.g. `"line_index":"3"`), a `NumberFormatException`
   escaped `onResponse` (the only enclosing catch is attached to the inner
   try-with-resources). OkHttp swallows exceptions thrown from a callback, so neither
   `onComplete` nor `onError` fired: the request hung and the `ApiCallHandle` stayed in
   `activeHandles`. Reproduced by inspection; no visible test covered it.

3. **Stale lyrics/meaning leaked into a new song's AI context**
   (`SongChatManager.java:187`, `setCurrentSong`).
   On a track change callers pass `("New Title","New Artist","",null)`
   (`MainActivity:2214`, `FloatingOverlayService:3695`). The old code only cleared
   `fullLyrics`/`meaning` when `title == null`, so the previous song's lyrics and meaning
   were kept and injected into `buildSongContext()` for the new song. The class javadoc
   says transitions should make prompts reflect the currently playing song.

4. **Default-locale case conversion** (`AntigravityApiClient.java:403,466`,
   `SongMeaningService.java:108,109,138`). `toLowerCase()` without a Locale produces
   locale-dependent results (notably Turkish `I/i`), which can change the normalized
   role string, the `thinkingLevel`, and — worst — the MD5 meaning-cache key
   (`getCacheKey`), causing cache misses/duplication. Lint reported "14x" but only these
   5 call sites exist in the scope.

5. **Dead code removed** in `SseChunkResult` (`AntigravityApiClient.java`):
   `functionCalls` (redundant with `functionCall`, never read), `finishReason` and
   `totalTokens` (parsed but never read anywhere in `app/src`).

## Fixes applied

- `dispatchOnDelta`: call only `onDelta()` (its default already forwards to `onChunk`),
  so each delta is delivered exactly once.
- `handleToolExecution`: wrapped the `queryOrUrl` derivation in try/catch; falls back to
  `toolArgs.toString()`. Prevents the silent stream hang.
- `SongChatManager.setCurrentSong`: added a `sameTrack` check. Empty lyrics/`null`
  meaning only clear state when the track changed (or is cleared entirely); same-track
  refreshes (e.g. a late meaning result) still preserve already-loaded lyrics.
- All 5 `toLowerCase()` calls switched to `Locale.ROOT` (imports added).
- Removed the three unused `SseChunkResult` fields and their population.

## Files changed

- `app/src/main/java/com/lyricsync/app/ai/client/AntigravityApiClient.java`
- `app/src/main/java/com/lyricsync/app/ai/service/SongChatManager.java`
- `app/src/main/java/com/lyricsync/app/ai/service/SongMeaningService.java`

## Residual risk

- `SongChatManager.loadSession()` has the same class of staleness: it restores
  title/artist but never clears `fullLyrics`/`meaning` from the previously active song.
  Left unchanged because it is not called per track change and no test pins the intent;
  flagging it rather than guessing.
- The tool-argument fallback in `handleToolExecution` still lets malformed numeric args
  reach the executor, where the existing try/catch turns them into an error ToolResult
  (correct behaviour: the loop continues).

## Candidates to split later (out of scope, not touched)

- `AntigravityApiClient.java` (~1400 lines): tool-declaration building, SSE parsing,
  streaming loop and tool dispatch should be separate collaborators.
- `AntigravityToolExecutor.java` (~1190 lines): SSRF/HTML utilities vs. search/fetch IO.
- `AiSyllableTransliterationService.java` (~985 lines): alignment math vs. API batching.
