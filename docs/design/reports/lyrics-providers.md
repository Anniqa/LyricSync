# Lyrics providers / model / cache / translation / util — findings & fixes

Scope: `app/src/main/java/com/lyricsync/app/lyrics/provider/**`,
`lyrics/LyricsProviderManager.java`, `lyrics/model/**`, `lyrics/cache/**`,
`lyrics/translation/**`, `lyrics/util/**`.

Verification: could not run Gradle (per instructions). Compiled all changed files
with `javac` against the existing `app/build/.../classes.jar` + `android.jar` + gson/okhttp/okio
(exit 0), then ran the in-scope JUnit tests offline with JUnitCore:
`SpicyLyricsV1SimulationTest` + `SpotifyTrackResolverSimulationTest` = OK (12 tests),
plus the other lyrics tests = OK (71 tests total). Contracts
`SpicyLyricsProvider.parseOfficialResponse` and
`SpotifyTrackResolver.pickBest/normalize/cleanTitle/cleanArtist` unchanged.

## Real defects fixed

1. **Whitespace word-splitting bug (Netease + LRCLIB)** — `NeteaseLyricsProvider.splitLineIntoWords`
   (line ~481) and `LRCLIBProvider.splitLineIntoWords` (line ~344) used `text.split(" ")`.
   Double spaces / tabs produced empty word tokens (and blank rendered words) and skewed the
   per-word timing; a tab in the line was not split at all. Changed to `split("\\s+")`
   (matches the correct `SyllableLyricsParser.splitLineIntoWords`). No API/behaviour change
   beyond correct whitespace handling.

2. **NPE on malformed Spotify search results** — `SpotifyTrackResolver.pickBest` (line ~222)
   did `el.getAsJsonObject().getAsJsonObject("item").getAsJsonObject("data")`. A result object
   without `item` made `getAsJsonObject("item")` return null and the next call threw an
   uncaught NPE. Added null guards (`itemObj`/`data`) so malformed entries are skipped.

3. **In-flight map leak / lost callback on executor shutdown** — `LyricsProviderManager.fetchLyrics`
   put the caller into the static `inFlightRequests` map, then called `executor.execute(...)`.
   If the executor was already shut down (`shutdown()` / `shutdownNow()`),
   `RejectedExecutionException` escaped *before* the try/catch inside the Runnable, so the map
   entry leaked forever and the callback was never invoked. Wrapped `execute` in
   `try { ... } catch (RejectedExecutionException e)` that removes the entry via
   `dispatchError(...)`.

4. **Stale-callback race in AI translation** — `AiTranslationService` cancelled old work with a
   single shared `isCancelled` boolean. `translateLyrics()` resets it to `false`, so a callback
   from a *previous* AI request (already cancelled by a newer call) could observe `false` and
   deliver a stale translation over the newer one. Added a monotonically increasing
   `requestGeneration` counter (bumped in `cancel()`), captured per `translateLyrics` call
   (`myGeneration`), and the timeout task + `onComplete` + `onError` now ignore themselves when
   `requestGeneration != myGeneration`.

## Dead code removed

5. `KugouLyricsProvider`: two unreferenced overloads removed —
   `searchCandidate(String, String, String, long)` and
   `downloadLyricContent(String, String)` (both only delegated). Their only user,
   `import java.util.Collections`, also removed.

6. `SpotifyColorLyricsProvider.parseColorLyricsJson`: local `syncType` was read and never used —
   removed.

7. Unused imports removed: `java.util.Collections` (`LyricsProviderManager`,
   `SyllableLyricsParser`), `android.net.Uri` (`NeteaseLyricsProvider`, `LRCLIBProvider`),
   `com.google.gson.JsonElement` (`SpotifyColorLyricsProvider`).

## Reported, not changed (needs a fixture / product decision)

- **Spotify color-lyrics `UNSYNCED` handling** (`SpotifyColorLyricsProvider.parseColorLyricsJson`,
  `data.type = hasSyllables ? SYLLABLE : LINE`, line ~170). The removed `syncType` was the only
  clue: for `syncType == "UNSYNCED"` the payload has no real per-line timestamps, and the code
  would still label the result `LINE` while every line carries the estimated/zero timing — wrong
  for a synced overlay. Fixing needs the real UNSYNCED payload shape to map it to `STATIC` with
  distributed times. Left as-is (observed, not guessed).
- `LyricsProviderManager.inFlightRequests` is `static`, so it is shared across manager instances
  (and unit tests). In this app only one manager is normally live, so cross-instance result
  mixing is not observed, but it is a latent coupling. Kept behaviour.
- `fetchKey` / `LyricsCacheManager.makeKey` use default-locale `toLowerCase()` instead of
  `Locale.ROOT`. Consistent write/read within a device, so not a functional bug; only a Turkish
  locale cosmetic concern. Left as-is.
- `SpotifyColorLyricsProvider` does not call `LyricsData.insertInterludes(...)` while every other
  provider does, so >=3 s musical gaps are not marked as interludes for Spotify lyrics. Likely a
  behaviour gap rather than a bug; not changed.
- Three separate copies of LRC -> line/word parsing exist
  (`SyllableLyricsParser.parseLrc`, `NeteaseLyricsProvider.parseLRC`,
  `LRCLIBProvider.parseSyncedLyrics`) and four copies of candidate scoring
  (`Kugou`/`Netease`/`LRCLIB`/`SpotifyTrackResolver`). Deduping is desirable but would change
  word-timing semantics, so it was not done as part of this bug pass.

## Files changed

- `lyrics/LyricsProviderManager.java` (import; executor rejection handling)
- `lyrics/provider/NeteaseLyricsProvider.java` (import; split regex)
- `lyrics/provider/LRCLIBProvider.java` (import; split regex)
- `lyrics/provider/KugouLyricsProvider.java` (dead overloads + import)
- `lyrics/provider/SpotifyColorLyricsProvider.java` (unused import + dead local)
- `lyrics/provider/SpotifyTrackResolver.java` (pickBest null guard)
- `lyrics/model/SyllableLyricsParser.java` (unused import)
- `lyrics/translation/AiTranslationService.java` (generation-based stale-callback guard)

## Files that should be split later (out of scope to split now)

- `lyrics/model/SyllableLyricsParser.java` (~20 KB): format detection + 5 format parsers +
  KRC decryption + timestamp helpers in one class.
- `lyrics/provider/SpotifyTrackResolver.java` (~24 KB): pathfinder auth, search/ranking,
  Deezer/MusicBrainz fallback, text normalisation.
- `lyrics/provider/NeteaseLyricsProvider.java` (~20 KB): search/scoring + YRC/LRC parsing.
