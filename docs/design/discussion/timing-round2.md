# Round 2 — cross-critique & merged plan (timing/alignment angle)

Author: timing-alignment. Read-only; no code edited. Inputs: `pipeline-latency.md`, `ux.md`, `timing-alignment.md`.

## 1. CONFLICTS (concrete disagreements across the three)

| # | Conflict | pipeline-latency | ux | timing-alignment |
|---|----------|------------------|----|------------------|
| A | **Per-song offset KEY** | Q6: resolved Spotify id (stable) *or* title\|artist | normalized `title\|\|artist` + duration bucket, or resolved id once known; first write can only use title\|artist | resolved Spotify id primary; fallback player mediaId / `title\|\|artist` |
| B | **Store location / DB versioning** | extend `LyricsCacheManager` (DB_VERSION 1→2) or new table | separate store, explicitly NOT `lyrics_cache.db` (its `onUpgrade` drops the table) | separate `SQLiteOpenHelper`, own DB file |
| C | **Cached LINE short-circuit** | yes (option E): dispatch cached usable result, optional background SYLLABLE upgrade | not addressed | not addressed |
| D | **Tap-to-sync reaction lag** | not addressed | subtract ρ≈150 ms, user-tunable? | raw formula, no ρ |
| E | **Id-cache TTL** | TTL + miss-TTL, duration-guarded | not addressed | not specified (today in-memory LRU only) |
| F | **Offset sign** | not addressed | freeze "positive = lyrics later" | same (positive shifts position back) |
| G | **Overlay UI budget** | none | persistent pill + nudge row + aim + undo/reset | one small aim affordance; keep the slider |
| H | **When the id key exists / offset load point** | parallel race may expose id earlier | load at `onTrackChanged` | load at `onLyricsLoaded` |

Supporting evidence: sign `MainActivity.java:566-570` vs `MediaSessionTracker.java:66-68,451`; both UIs clamp ±1500 but `MainActivity.java:2367` clamps ±10000; `LyricsCacheManager.java:117-121` drops the table on upgrade; cached LINE still runs the whole cascade `LyricsProviderManager.java:163-165,200`; line tap already seeks `FloatingOverlayService.java:3762`.

## 2. Recommendation per conflict

**A — KEY: resolved Spotify id is canonical. Load the offset at `onLyricsLoaded`, not `onTrackChanged`.**
Alignment only matters once lyrics render, and lyrics can only render *after* the id exists, so the id is always known at the moment we need it. Sequence: at `onTrackChanged` set the offset to the global default; when lyrics load (id via `LyricsData.sourceTrackId`), apply `sp:<id>` if stored. Canonical key = `sp:<spotifyId>`; fallback `ta:<norm title|artist>|<durationBucket>` only when resolution failed. **Do not key by player mediaId** — it is upload-specific (Topic vs official upload differ) so it does not accumulate across sessions. *Correctness first:* avoids cover/live collisions and transfers across players/uploads. *Cost:* one store read at load.
Offline detail: when a lyrics-cache hit skips the resolver, recover the id from the **id cache** (no network) before rendering, so the offset key is still available.

**B — STORE: one new isolated DB (`song_sync.db`), never `lyrics_cache.db`.**
Reason: `onUpgrade` drops the lyrics table (`LyricsCacheManager.java:117-121`); offsets and ids have different lifetimes/TTLs; and we need one shared model anyway. *Cost:* a second small DB; acceptable.

**C — SHORT-CIRCUIT: yes.** Dispatch a usable cached LINE/STATIC result immediately when the request is not syllable-only, with an optional background SYLLABLE upgrade. Correctness is preserved by `betterThan` (`LyricsProviderManager.java:...`) and the overlay's never-downgrade guard (`FloatingOverlayService.java:3661-3665`). *Cost:* small `performFetch` branch.

**D — REACTION LAG: yes, with a compiled constant.** Derivation with player bias β (`c = a + β`): desired offset is β. A tap lands at true audio `a = S + ρ`, captured `P = (S+ρ+β) − o`. ux's `o' = o + (P − (S+ρ)) = β` — **algebraically exact** for constant ρ. Implement the raw form first, keep ρ as one named constant (default 150 ms), expose later. *Correctness:* only affects the tap gesture; a bad tap stays one undo away.

**E — TTL: hits 30 d, misses 24 h.** Ids are stable; the ≤8 s duration guard on read already catches wrong matches (`SpotifyTrackResolver.java:239`), so long hit TTL is safe. Short miss TTL avoids poisoning on a network blip. Lyric content cache stays 7 d.

**F — SIGN: freeze positive = lyrics later, one `SYNC_LIMIT_MS`.** Fix `MainActivity.java:2367` (±10000) to match the shared constant, else the pref listener pushes an unrepresentable value into the tracker (`FloatingOverlayService.java:297-300`).

**G — UI: minimal first.** Ship the aim affordance + tap-to-sync and keep the existing slider; defer the persistent pill/nudge-row (small floating window, more surface area). Reveal value on demand. *Correctness > polish.*

**H — load point: `onLyricsLoaded`** (see A). If the parallel-resolve race lands, the id may appear a few hundred ms earlier but the load point is unchanged.

## 3. Shared data model + ONE merged ordered plan

**Shared model (both features):**
- New `com.lyricsync.app.lyrics.sync.SongSyncStore` — own `SQLiteOpenHelper`, DB `song_sync.db`:
  - `spotify_id(cache_key PK, spotify_id, duration_ms, timestamp, is_miss)` — `cache_key = normalize(cleanTitle|cleanArtist)`.
  - `song_offset(canonical_key PK, offset_ms, updated_at)` — `canonical_key = "sp:"+id` else `"ta:"+norm(title|artist)+"|"+durationBucket`.
  - helpers: `getSpotifyId/putSpotifyId`, `getOffset/putOffset`, `canonicalKey(track, resolvedId)`.
- `LyricsData.sourceTrackId` (new) set by `SpicyLyricsProvider` / `SpotifyColorLyricsProvider`.
- `MediaSessionTracker.getRawPositionMs()` + `getSyncOffsetMs()` (getters only; offset math stays in `getCurrentPosition():448-453`).
- **Single write path** `FloatingOverlayService.applySyncOffset(scope, source, ms)`: clamp once, persist (song→store, global→pref), `sessionTracker.setSyncOffsetMs`, update label/slider, remember last for undo, haptic. All four existing writers route through it.

**Plan (≤6 steps):**
1. **Single apply path + clamp unification** — `FloatingOverlayService.java`, `MainActivity.java`. *Smallest useful first PR*: fixes today's live ±10000/±1500 drift and creates the one seam.
2. **`SongSyncStore` + per-song offset scope** — new `lyrics/sync/SongSyncStore.java`, `lyrics/model/LyricsData.java`, `SpicyLyricsProvider.java`, `SpotifyColorLyricsProvider.java`, `FloatingOverlayService.java` (load at `onLyricsLoaded`, reset to global at `onTrackChanged:3162`).
3. **Tap-to-sync** — `MediaSessionTracker.java` (`getRawPositionMs`), `FloatingOverlayService.java` (aim affordance + `syncNowAtLine(index)` with ρ), layout/strings.
4. **Persistent Spotify-id cache** — `SpotifyTrackResolver.java` (check store before network; duration guard on read), `LyricsProviderManager.java` (wire Context, write id on resolve), `SongSyncStore.java`.
5. **Cached-LINE shortcut + token warm-up** — `LyricsProviderManager.java`, `SpotifyTrackResolver.java` (`warmUpToken()`), `LyricSyncApp.java`.
6. **Parallel resolve race + AI/scope parity + a11y** — `LyricsProviderManager.java`, `AntigravityApiClient.java:331` (`scope` arg), `FloatingOverlayService.java`, layouts.

Order is correctness-first: the store/key model (2) lands before anything that depends on the id (4-6); the id cache (4) is what makes offsets survive an offline/cached-lyrics replay.

## 4. Needs a real device to validate
- YT Music reported-position granularity & lag; whether `getLastPositionUpdateTime` is `elapsedRealtime` (the 5 s window at `MediaSessionTracker.java:31` only exists because some players are not).
- True `positionAtLoad` distribution and actual resolver request counts/latency on mobile networks.
- Per-song offset **magnitude and stability** across replays of the same song (does calibration stick?), and tap-to-sync residual vs a stopwatch to calibrate ρ.
- Whether one resolved Spotify id maps to one stable offset across YT uploads (validates the canonical key).
- TalkBack/accessibility flow for the aim affordance.
