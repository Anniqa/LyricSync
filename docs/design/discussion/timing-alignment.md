# Timing & alignment proposal — how the lyrics learn the right start time

*Angle: the timing/alignment half of the SpicyLyrics-on-YouTube-Music problem. Read-only analysis; no code changed.*

## 1. Diagnosis

### 1a. Where the "first seconds have no lyrics" time actually goes
The resolve+fetch is *synchronous on the fetch executor* and sits in front of every SpicyLyrics render.

* `LyricsProviderManager.performFetch` runs the provider loop on a 4-thread executor (`LyricsProviderManager.java:44,118`); `SpicyLyricsProvider` is provider #1 (`LyricsProviderManager.java:59-64`).
* `SpicyLyricsProvider.fetchLyrics` first calls `resolveSpotifyTrackId` (`SpicyLyricsProvider.java:60-63`, `:300-320`) which calls `SpotifyTrackResolver.resolve(...)`, and only then does `fetchOfficialLyrics` (`:83-95`).
* `SpotifyTrackResolver.resolve` (`:115-128`): in-memory LRU miss → `resolveViaSpotifySearch` (1 token bootstrap GET of the embed page, `:329`, cached 30 min `:55`, then up to 2 `searchDesktop` GETs for base+romaji title variants, `:132-158`), then fallback `resolveViaIsrc` (up to 2 Deezer GETs + 1 MusicBrainz GET, `:356-...`), then the Spicy GET.
* **Request count on the critical path (typical success):** 1 bootstrap (first track only) + 1 search + 1 `GET /v1/lyrics/{id}` = **2–3 sequential HTTPS round-trips**; worst case (401 retry / romaji variant / ISRC fallback) ≈ 5–6. Timeouts are 5–6 s (resolver) and 10 s (Spicy) (`SpotifyTrackResolver.java:44-46`, `SpicyLyricsProvider.java:47-49`).
* The static LRU is **in-memory only** (256 entries, `:66-76`); it dies with the process, so an overlay-service restart re-resolves.
* `SpotifyColorLyricsProvider` resolves independently (`SpotifyColorLyricsProvider.java:63-66`), but the shared static LRU makes that a cache hit — **there is exactly one network resolution per process per song**, it is just late.

=> On YT Music the overlay shows nothing until all of the above finishes; `positionAtLoad` can be 1–4 s (network dependent, needs device measurement).

### 1b. The alignment problem is *not* solved by the current offset
* The renderer compares the **live player position** against absolute Spicy timestamps in the same clock: `FloatingOverlayService.frameCallback` → `sessionTracker.getCurrentPosition()` (`FloatingOverlayService.java:3247`) → `updateHighlight` → `findActiveLine` binary search (`:3935-3951`) → `SyllableHighlighter.updateHighlight` / `GradientWordView.updateState(position)` (`SyllableHighlighter.java:626-650,752-757`). Words light up when `position >= word.startTime`.
* So the whole thing is only as correct as **"player position ≡ audio time, and Spicy t=0 ≡ audio t=0"**. There is no load-time anchoring anywhere (good — the parent's fear of "assumes 0 at load" is not present), but there is also nothing that *corrects* a constant mismatch.
* The only correction is **one global offset**: `sync_offset_ms`, applied as `adjusted = projected - syncOffsetMs` in `MediaSessionTracker.getCurrentPosition()` (`:448-453`, sign documented `:66-68`), shared by the studio slider (`MainActivity.java:554-562`), the overlay slider (`FloatingOverlayService.java:1082-1105`) and the AI tool (`FloatingOverlayService.java:1904-1913`, `AntigravityApiClient.java:331,999,1206`), all writing the same pref key. It is **not keyed to the song** (`LyricsCacheManager` keys lyrics by `title|||artist`, `:107-109` — no offset column; no per-song offset exists anywhere).
* Clamp inconsistency (bug): slider path clamps ±1500 (`SYNC_LIMIT_MS`, `FloatingOverlayService.java:105`, `MainActivity.java:108`) but the AI/studio setter clamps ±10000 (`MainActivity.java:2367`). The overlay label can never display what the AI set.

### 1c. Why the anchor/resync cannot fix a wrong offset
`RESYNC_THRESHOLD_MS = 400` (`:35`) resyncs the **projection** to a **fresh player snapshot** (`:344-360`), and it compares `projectedPosition()` — which is computed **before** the offset (`:461-474`, no `syncOffsetMs` inside). Therefore:
* the user offset is a pure display-time shift; it can never fight or trigger resync;
* but resync also **cannot** correct a *constant* bias between the player's reported position and the real audio (it faithfully follows the report). A constant YT-Music lead/lag up to any size is invisible to the tracker and must come from the offset.
* Interpolation is otherwise sound: 16 ms poll (`:25`), trust `getLastPositionUpdateTime` only inside a 5 s window (`:31,336-338`), cap free-run at 2 s (`:39,467`), clamp to duration (`:471-472`), re-anchor on resume/seek (`:357-360,492-502`).

### 1d. The hard limit on "automatic" alignment
Both sides of the equation share the same unknown. The player gives us a clock but *no audio*; Spicy gives us timestamps in its own clock. With no independent audio reference there is **no signal** that tells us the first vocal actually started. Consequences:
* Load-time anchoring is meaningless for alignment: the fixture `spicy_v1_syllable.json` has first vocal at `StartTime=7.337 s`, so "lyrics t=0 == play t=0" is simply the right model and needs no anchor.
* The first syllable's own timestamp is **not** self-aligning — it is part of the same unknown offset.
* Therefore only two things can ground alignment: (i) the human ear (calibration), or (ii) an independent reference the app does not currently have (Spicy's own per-song offset field if it existed — the `/v1` Body has `StartTime/EndTime/SongWriters` but **no offset field**; verified in the fixture).

## 2. Candidate solutions

**A. Per-song offset persisted, keyed by canonical song id** (recommended core)
* *Mechanism:* resolved Spotify id is the canonical key (fall back to player media id / `title|||artist`). On track change load the stored offset (0 if none) into `sessionTracker.setSyncOffsetMs()`; every slider/AI change is written back under the current song's key instead of a single global pref.
* *Gain:* calibrate once per song, survives track changes, app restarts, and (because the key is the Spotify id) transfers between YT Music and Spotify. Removes cross-song contamination of the global slider.
* *Cost/risk:* medium — new `SyncOffsetStore` (its own small SQLite file to avoid touching `lyrics_cache.db` migration), plus need to surface the resolved Spotify id to the overlay (today `performFetch` passes a *copy* `queryTrack` to providers, so the caller never learns the id).
* *Correctness:* does not degrade: default 0 keeps current behaviour; worst case is a stale offset for a re-uploaded pressing, which the user can re-tap.

**B. Tap-to-sync ("align this line now") — the only sound ground truth**
* *Mechanism:* add `MediaSessionTracker.getRawPositionMs()` (projected *before* offset). A small sync affordance on the active line computes `offset = lines.get(i).startTime - track.getRawPositionMs()` and applies+persists it. (Use the active line; a "next line within 1.5 s" variant needs the lyric list.)
* *Gain:* exact per-song offset in one tap, ±100–200 ms human error, no slider guessing; directly answers "this line should start now". Combined with A it is one-and-done.
* *Cost/risk:* low — one tracker getter + one overlay handler + button; reuses `setSyncOffsetMs`.
* *Correctness:* deterministic math; residual is human timing only.

**C. Reject load-time anchoring; keep the absolute model, add a sanity/suggestion pass**
* *Mechanism:* on `onLyricsLoaded`, log `positionAtLoad`; if it is small (<~1.5 s) leave timing untouched. Optionally *suggest* (never auto-apply) an offset only when a structural mismatch is provable (e.g. matched Spotify duration differs from player duration by Δ → hint `offset ≈ Δ`).
* *Gain:* prevents the tempting wrong fix; gives one cheap hint.
* *Cost/risk:* low.
* *Correctness:* the hint is a heuristic and can be wrong; must stay user-confirmed.

**D. Fetch latency reduction (complementary, other participants' angle)**
* *Mechanism:* kick off `SpotifyTrackResolver.resolve` at `onTrackChanged` in parallel with sanitization; pass the resolved id into the provider; persist resolution in the cache DB.
* *Gain:* shrinks `positionAtLoad`, which is the other half of "first seconds have no lyrics".
* *Cost/risk:* low-medium; only correctness risk is duplicate writes to the shared LRU (already synchronized).
* *Correctness:* none.

**E. Try to auto-derive offset from the player clock alone** — **not viable.** No equation exists without an independent audio clock; would need a device/PCM reference the app cannot get from another app's MediaSession.

## 3. Recommended minimal change set (in order)

1. **Instrument only (no behaviour change).** Log `AppLog.i` timing at: `onTrackChanged` (`FloatingOverlayService.java:3162`), `SpotifyTrackResolver.resolve` in/out (`:115`), `SpicyLyricsProvider.fetchLyrics` in/out (`:57`), `LyricsProviderManager.dispatchSuccess` (`:280`), and `onLyricsLoaded` including `positionAtLoad = sessionTracker.getCurrentPosition()`. Files: `MediaSessionTracker.java`, `SpotifyTrackResolver.java`, `SpicyLyricsProvider.java`, `LyricsProviderManager.java`, `FloatingOverlayService.java`.
2. **Per-song offset store (A).** New `lyrics/sync/SyncOffsetStore.java` (own SQLiteOpenHelper, table `song_offset(canonical_key PK, offset_ms, updated_at)`); add `LyricsData.sourceTrackId` set by `SpicyLyricsProvider`/`SpotifyColorLyricsProvider` so the overlay learns the resolved id; load in `onTrackChanged`/`onLyricsLoaded`; write from `setupSyncOffsetUi` (`FloatingOverlayService.java:1082-1105`), `setSyncOffsetLive` (`:1904`) and `MainActivity` slider/AI.
3. **Raw-position getter + tap-to-sync (B).** `MediaSessionTracker.getRawPositionMs()`; overlay handler `syncNowAtLine(index)` using the active line; keep the slider as fallback.
4. **Unify the clamp** (±1500 everywhere, fix `MainActivity.java:2367`); reset offset to the per-song value on every track change; reload when the canonical id becomes known.

## 4. What to measure
* **Without a device (instrumented logs / offline):** `resolveLatencyMs`, `spicyFetchLatencyMs`, HTTP request count per track; `positionAtLoadMs` distribution; per-song offset round-trip through the store; unit-test the pure math (`offset = lineStart - rawPos`; `seekTo` re-adds offset `MediaSessionTracker.java:495`; resync independent of offset). The tracker itself uses `SystemClock` directly (`:336,450`) so full time-based tests need a small injectable clock seam — worth adding.
* **Needs a device:** YT Music snapshot granularity and true lag; whether `getLastPositionUpdateTime` is `elapsedRealtime`-based for YT Music (the 5 s window at `:31` implies some players are not); actual `positionAtLoad`; the true nonzero-offset rate and **offset variance across replays of the same song** (does calibration stick?); tap-sync residual vs a stopwatch.
* **Success criteria:** `positionAtLoad` p50/p90 drops (with D); after one tap, residual `|position − firstWordTime|` < ~200 ms on replay; zero songs need re-calibration across sessions.

## 5. Open questions for the other participants
1. Does the SpicyLyrics API (or the Spicy client) expose a per-song timing offset we could consume instead of guessing? The `/v1` Body does not, per the fixture.
2. Should we ever *seek* the player to the first lyric line when lyrics load late, or never hijack playback? (Product call.)
3. Is the resolved Spotify id stable enough to be the canonical offset key across YT Music uploads (Topic vs official audio)?
4. Can the fetch-latency work (D) surface the resolved id at `onTrackChanged` time, or only after the fetch — this decides whether the offset loads before or after lyrics appear?
5. Product: auto-apply a suggested offset, or always require the user to confirm?
6. Is there any legitimate audio-side reference available (Visualizer/audio session)? Assume no for a third-party player.
