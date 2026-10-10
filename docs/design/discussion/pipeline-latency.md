# Pipeline latency / resolution bottleneck (YT Music -> Spotify id -> lyrics)

Author angle: pipeline latency & the resolve bottleneck. Read-only analysis; no code edited.

## 1. Diagnosis — what is slow, with evidence

### 1.1 The cold resolve is a serial 2–4 round-trip chain on the critical path
For a non-Spotify track (e.g. YouTube Music), `track.trackId` is the player media id,
which is not a 22-char Spotify id, so `SpicyLyricsProvider.fetchLyrics` falls through to
the resolver before it can call the lyrics API:

- `SpicyLyricsProvider.java:56-63` — `extractSpotifyId(track)` returns null, then `resolveSpotifyTrackId(track)`.
- `SpicyLyricsProvider.java:427-446` — calls `SpotifyTrackResolver.resolve(title, artist, duration)`.
- `SpotifyTrackResolver.java:115-130` — resolve(): in-memory LRU lookup, else Spotify search, else ISRC fallback.
- `SpotifyTrackResolver.java:143-149` — search path: `getAnonToken()` then `search()`.
- `SpotifyTrackResolver.java:53` — anonymous token bootstrap = `GET open.spotify.com/embed/track/...`.
- `SpotifyTrackResolver.java:297-342` — `search()` = `GET api-partner.spotify.com/pathfinder/v1/query`.

Measured live from this sandbox (single cold call, warm app, fresh token):
- token bootstrap `GET open.spotify.com/embed/...` -> **~0.21 s**
- `searchDesktop` pathfinder -> **~1.20 s** (response body **~289 KB**, then Gson-parsed)
- `GET api.spicylyrics.org/v1/lyrics/{id}` -> **~0.26 s**

So the first non-Spotify track costs **~1.7 s of network** before the first byte of lyrics
is even parsed, on top of the player's own metadata push delay and thread scheduling.
If the search misses, `resolveViaIsrc` adds:
- Deezer `api.deezer.com/search` -> measured **~0.43 s** (`SpotifyTrackResolver.java:373`)
- MusicBrainz `musicbrainz.org/ws/2/isrc/...` -> measured **~0.95 s** (`SpotifyTrackResolver.java:395`)

Worst case ≈ **3 s** of serial network before lyrics.

### 1.2 The provider cascade is serial and Spicy blocks everyone behind it
`LyricsProviderManager.performFetch` runs providers in one loop on **one** executor thread
(`LyricsProviderManager.java:141-227`), and Spicy is registered first
(`LyricsProviderManager.java:52`). Kugou/Netease/LRCLIB do NOT need a Spotify id, yet they
cannot start until Spicy's resolve+fetch finishes (or throws). The loop only breaks early on
a SYLLABLE result (`LyricsProviderManager.java:200`). So for a YT track the wall-clock to
*any* lyrics is `resolve(1.2–3s) + fetch(0.26s)` before the fallbacks even fire.

### 1.3 Duplicate resolve is mostly already avoided (so this is NOT the main win)
`performFetch` builds ONE `queryTrack` instance and passes the same object to every provider
(`LyricsProviderManager.java:143-153`). `SpicyLyricsProvider` mutates `queryTrack.trackId` on
success (`SpicyLyricsProvider.java:445`), and `SpotifyColorLyricsProvider.extractTrackId`
(`SpotifyColorLyricsProvider.java:97`) then sees `spotify:track:...`. The resolver also has a
static hit+miss LRU (`SpotifyTrackResolver.java:72-77`). => the second resolve is a cheap
memory hit; the problem is the **first, cold, on-critical-path** resolve and its 289 KB parse.

### 1.4 The on-disk cache cannot short-circuit the resolve
`LyricsCacheManager` keys final lyrics by `title|||artist` only (`LyricsCacheManager.java:97`);
it never stores the resolved Spotify id. Worse: a cached LINE/STATIC result does **not** skip
the network — `performFetch` sets `bestLyrics = cached` but still runs the whole provider list,
returning early only for SYLLABLE (`LyricsProviderManager.java:163-165,170,200`). So replaying
a track whose best result is LINE re-resolves and re-fetches every time.

### 1.5 No warm-up, no prefetch, no abort
- No token warm-up at startup: `LyricSyncApp.java:9-15` only builds a channel.
- No queue/prefetch anywhere: grep for `getQueue|prefetch|QueueItem` finds nothing.
- No cancellation: `SpotifyTrackResolver` uses static methods and a shared `OkHttpClient`
  with no per-call tag; a fast skip leaves the 289 KB search + parse running.
- Stale results are only *ignored* at the overlay by `pendingFetchKey`
  (`FloatingOverlayService.java:3656-3658`), not aborted.

### 1.6 Correctness guardrails that must be preserved
`pickBest` rejects candidates below title sim 0.72, artist sim 0.34, duration delta > 8 s, or
score < 68 (`SpotifyTrackResolver.java:62-69, 221-243`); `SpotifyTrackResolverSimulationTest`
covers the wrong-song / duration-mismatch rejection. Any cache or concurrency change must keep
these guards and never attach an id resolved from a *different* track's metadata.

## 2. Candidate solutions

### A. Persistent title|artist -> Spotify-id cache (SQLite), duration-guarded
- Mechanism: new table (or extend `LyricsCacheManager`) storing `norm(title)|norm(artist)`,
  `spotifyId`, `durationMs`, `timestamp`. `SpotifyTrackResolver.resolve` checks disk before
  network; stores hits and (short-TTL) misses. Also persist the id when lyrics are cached so a
  cached LINE result can skip the resolve.
- Expected gain: repeat plays and offline -> resolve becomes a ~ms disk read; cold *first* play
  unchanged. Removes the repeat-play re-resolve described in 1.4.
- Cost/risk: DB migration (`LyricsCacheManager` DB_VERSION 1 -> 2), eviction/TTL policy; key
  collision across pressings. Guard by storing duration and re-checking the <=8 s window on read.
- Correctness: safe if the duration guard is applied; only skips network, never bypasses ranking.

### B. Warm the anonymous pathfinder token at app/overlay start
- Mechanism: public `SpotifyTrackResolver.warmUpToken()` invoked on a background thread from
  `LyricSyncApp.onCreate` (or when the overlay service starts).
- Expected gain: **~0.2–0.35 s** off the first resolve; TTL is 30 min
  (`SpotifyTrackResolver.java:54`) so later tracks skip bootstrap entirely.
- Cost/risk: tiny; one request that is wasted if nothing plays; 401/rotation already handled.
- Correctness: neutral.

### C. Race the Spotify resolve against providers that don't need an id
- Mechanism: on track change, start the resolve future, run Kugou/Netease/LRCLIB in parallel,
  and hand the resolved id to Spicy when it lands; stop the losers on first SYLLABLE.
- Expected gain: time-to-first-SYLLABLE ≈ min(Spicy path, Kugou/Netease), hiding the ~1.2 s
  resolve behind the first provider.
- Cost/risk: concurrency + cancellation plumbing; more parallel network; a shared resolve must
  stay deduped.
- Correctness: keep the "never downgrade SYLLABLE" rule and the fetchKey guard; only use an id
  resolved for the same track.

### D. Exact player-mediaId -> Spotify map instead of fuzzy search
- Mechanism: `MediaSessionTracker` already captures `METADATA_KEY_MEDIA_ID`
  (`MediaSessionTracker.java:226`); for YT Music that is the video id. Map it exactly.
- D1 Odesli/song.link: **measured, rejected** — `api.song.link/v1-alpha.1/links` now returns
  `401 {"code":"PUBLIC_API_ACCESS_DEPRECATED"}`. Only viable with a paid/keyed plan.
- D2 Use mediaId as an additional cache key (stronger/unique than title|artist; avoids
  cover/remix collisions) feeding candidate A.
- Expected gain: fewer wrong matches + cache hits; only when the player exposes a stable id.
- Cost/risk: dependency on a third party (if D1) or extra cache key (D2); not all players expose it.
- Correctness: D1/D2 are exact-or-nothing, so they can only *reduce* wrong-song risk.

### E. Cache shortcut: return cached LINE immediately, upgrade in background
- Mechanism: if `cacheManager.getCached` returns a usable LINE/STATIC result and the request is
  not syllable-only, dispatch it immediately; optionally kick a background Spicy resolve to
  upgrade to SYLLABLE.
- Expected gain: instant lyrics on repeat plays; deletes the repeat cascade in 1.4.
- Cost/risk: users may see LINE longer if the upgrade never lands; small logic in `performFetch`.
- Correctness: never downgrade; upgrade only replaces with SYLLABLE.

### F. Prefetch the next queue item
- Mechanism: read `controller.getQueue()` at track start; pre-resolve + prefetch the next item.
- Expected gain: next track instant.
- Cost/risk: queue is often null/unreliable for third-party players; extra background network/battery.
- Correctness: prefetch keyed by title|artist; harmless if wrong, it is only a warm cache.

### G. Abortable resolve/fetch
- Mechanism: pass a `fetchKey`/CancellationSignal into resolve+fetch and cancel OkHttp calls on
  a new track.
- Expected gain: less wasted network/battery on fast skipping; avoids a stale 289 KB parse.
- Cost/risk: static resolver needs instance/context or a call tag; must not cancel a joined
  in-flight fetch another callback still wants.
- Correctness: neutral.

## 3. Recommended minimal change set (in order)

1. **Token warm-up** — add `SpotifyTrackResolver.warmUpToken()` (new public static wrapper over
   the existing `getAnonToken(false)`) and call it on a background thread from
   `LyricSyncApp.onCreate` (or overlay `startTracker`). Files: `SpotifyTrackResolver.java`,
   `LyricSyncApp.java` (or `FloatingOverlayService.startTracker`).
2. **Persistent Spotify-id cache** — new `lyrics/cache/SpotifyIdCache` (SQLite, own table,
   `norm(title)|norm(artist)` + `durationMs` + `timestamp`, TTL + miss cache), consulted in
   `SpotifyTrackResolver.resolve` before network; write the id from `LyricsProviderManager` when
   caching lyrics. Files: `SpotifyTrackResolver.java`, `LyricsProviderManager.performFetch`.
3. **Cache shortcut** — in `performFetch`, when a non-syllable-only request hits a usable cached
   result, dispatch it immediately instead of running the cascade (optionally background-upgrade).
   File: `LyricsProviderManager.java`.
4. **Parallel resolve + id-free providers** — run the resolve concurrently with Kugou/Netease/
   LRCLIB and feed the id to Spicy when ready; keep the SYLLABLE early-stop and fetchKey guard.
   Files: `LyricsProviderManager.java` (and a small `SpotifyTrackResolver` handle if the id is
   resolved off the provider call).
5. (Later) mediaId->id cache key and next-track prefetch; abortable fetch only if measurements
   show skipped tracks wasting meaningful network.

Do NOT rely on Odesli/song.link for (D1) without a keyed plan — the public endpoint is dead.

## 4. What to measure to prove it worked

- Instrument `SpotifyTrackResolver` and `LyricsProviderManager` to log, per track: number of HTTP
  calls by type (bootstrap / search / Deezer / MusicBrainz / lyrics) and wall time from
  `onTrackChanged` (`MediaSessionTracker.java:247`) to the overlay's first
  `onLyricsLoaded` (`FloatingOverlayService.java:3655`), split resolve vs fetch.
- Cold app first YT track: expect ~1.7 s today; after step 1 expect ~1.4–1.5 s; after steps 1+2
  a **second play** of the same track should drop to ~0.3–0.5 s (lyrics fetch only).
- Persistent-id-cache hit ratio and miss rate.
- Repeat-play network count: a cached LINE result should produce **zero** resolve/search calls
  (today it re-runs the full cascade).
- Regression: run `SpotifyTrackResolverSimulationTest`, `LyricsProviderManagerTest`,
  `SpicyLyricsV1SimulationTest`; verify the wrong-song/duration-mismatch tests still pass.

## 5. Open questions for the other participants

1. Is a paid/mapped videoId->Spotify service acceptable, or do we stay with Spotify search?
   (public Odesli is 401).
2. Is returning a cached LINE immediately acceptable, or must we always attempt the SYLLABLE
   upgrade (current behavior)? Latency vs quality trade-off.
3. Should the persistent cache key include `mediaId` and/or `duration`, and what miss TTL?
4. Are we allowed to bump `LyricsCacheManager` DB_VERSION (migration drops the table today)?
5. Should the cascade reorder/race for non-Spotify tracks (Kugou/Netease alongside Spicy), or
   keep deterministic order?
6. For the per-song-offset angle: if offsets are stored, key them by the resolved Spotify id
   (stable) or by title|artist (available before resolve)?
