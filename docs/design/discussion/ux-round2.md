# Round 2 — cross-critique and merged plan (author: ux)

Inputs: `pipeline-latency.md` (latency), `timing-alignment.md` (timing), `ux.md` (this author).
Note: `timing-alignment.md` was 0 bytes on first read; I re-read it at the end of the wait and it
was present (11,869 bytes), so all three proposals are represented below.

## 1. Conflicts between the three proposals

1. **Per-song offset key.** latency: open question, leans mediaId / resolved id. timing: resolved
   Spotify id canonical, fall back to mediaId / title|artist. ux: title|artist|duration primarily,
   resolved id optionally (dual-key). -> concrete disagreement on the primary key.
2. **Where the per-song / id data lives.** latency: extend `LyricsCacheManager` or new table, asks
   whether bumping `DB_VERSION` is allowed (its `onUpgrade` DROPS the table, LyricsCacheManager.java:117-121).
   timing: a separate small SQLite file, explicitly to avoid `lyrics_cache.db`. ux: separate store,
   never `lyrics_cache.db`.
3. **Cached LINE while upgrading in background.** latency: dispatch cached LINE immediately,
   optional background SYLLABLE upgrade (open Q2). ux/timing: silent on policy; current code runs
   the full cascade even for a cached LINE (`LyricsProviderManager.java:163-170`).
4. **Tap-to-sync formula / reaction lag.** ux: `offset' = offset + (P - S)`, optional fixed
   reaction constant rho. timing: `offset = S - rawPos`, no compensation, tap the *active* line.
   These are different formulas AND different targets. Numeric sign check (tracker:
   `reported = raw - offset`, MediaSessionTracker.java:448-453; raw=4000, target line S=5000):
   ux -> newOffset=-1000 -> reported'=5000 (correct); timing -> newOffset=+1000 -> reported'=3000
   (wrong, and it also discards the existing offset).
5. **Which line the user taps.** timing: the active (highlighted) line. ux: the line the user
   *hears*, which may be a line not yet highlighted.
6. **Cache TTL.** latency: persistent id cache with TTL + short-TTL miss cache; asks what TTL.
   ux/timing: not specified.
7. **Offset sign semantics.** All three state positive = "lyrics later". But timing's tap formula
   is inconsistent with that sign (see #4), so the sign contract is at risk.
8. **Overlay UI budget.** ux: header pill + nudge row (+ aim), revealed after first use. timing:
   one small affordance near the active line. latency: none. Disagreement on how much persistent
   overlay surface is acceptable in a small floating window.
9. **When the offset loads relative to resolve.** timing asks whether the latency work can surface
   the resolved id at `onTrackChanged` (Q4); latency notes the caller never learns the id today
   because `performFetch` passes a *copy* `queryTrack` (LyricsProviderManager.java:143-153).
10. **Auto-seek to first lyric when lyrics load late.** timing Q2 raises it; ux/latency do not
    endorse it.

## 2. Recommendations (correctness first, cost second)

1. **Key: provisional title|artist|duration bucket, promoted to resolved Spotify id.** Create the
   key at `onTrackChanged` (before any resolve, since the id is lazily unknown for YT Music), so the
   offset always loads instantly. When the resolve lands (`LyricsData.sourceTrackId`, see #9), write
   the same value under the Spotify id too and read either (dual-key). Player mediaId is only a
   secondary alias, never a required key. Reason: pure resolved-id keying fails the pre-resolve
   window (timing acknowledges the id must be surfaced first); pure title|artist collides on
   covers/live; the duration bucket + exact id promotion reduces both. Cost: slightly more state.
2. **Store: separate store, never `lyrics_cache.db`.** Its `onUpgrade` drops the table, so offsets
   and ids must survive upgrades. Use one new `SyncOffsetStore` (own SQLiteOpenHelper / own db file,
   or a small SharedPreferences map) and one new `SpotifyIdCache`. Reason: correctness/data-safety
   beats the convenience of reusing one DB. Cost: one more file.
3. **Cached LINE: yes, dispatch immediately, upgrade in background; keep syllable-only strict.**
   If the request is not syllable-only and a usable cached LINE/STATIC exists, return it now and
   kick a background Spicy upgrade; replace only with SYLLABLE (never downgrade). If the caller
   asked for syllable, keep today's strict path. Reason: deletes the repeat-play cascade
   (latency 1.4) with no quality loss because of the existing never-downgrade guard
   (FloatingOverlayService.java:3660-3665). Cost: small.
4. **Tap-to-sync: use `offset' = offset + (P - S)` == `rawPos - S`; user taps the line they HEAR.**
   Verified numerically: with tracker `reported = raw - offset` and raw=4000, S=5000, the ux formula
   gives offset'=-1000 -> reported'=5000 (correct); timing's `S - rawPos` gives reported'=3000
   (wrong) and also discards the existing offset. Anchoring to the *active* line only measures
   drift, not a constant offset, so it cannot fix a large misalignment. Reason: correctness.
5. **Do not bake in a reaction-lag constant yet.** Ship the exact math with
   `TAP_REACTION_COMP_MS = 0`, log the residual, and only add a default rho (or make it tunable)
   after device measurement. Reason: a fixed rho is a guess that can inject error; the exact form
   is provably correct, the fudge is not.
6. **TTL: ids are stable -> long hit TTL (~90 days or size-bounded); misses -> short (~24-72 h).**
   The duration guard (<=8 s, SpotifyTrackResolver.java:239) is what protects against re-uploads;
   TTL is only housekeeping. Reason: avoids re-burning the 289 KB search on genuinely absent tracks
   while letting the catalogue gain them.
7. **Freeze sign: positive = "lyrics later"** (`reported = raw - offset`,
   MediaSessionTracker.java:448-453; label MainActivity.java:566-570). Add a unit test for the tap
   math and for `seekTo` re-adding the offset (MediaSessionTracker.java:492-497). Reason: one
   contract, machine-checked.
8. **UI: one compact control first.** Header "sync" pill showing value + scope, expanding to the
   nudge row (`-10/-50/+50/+10`) plus aim/reset/undo; reveal only after first use. Keep the
   settings-panel slider as the coarse fallback. Reason: fits the small window, keeps the fast path
   visible without permanent clutter. Cost: low.
9. **Surface the resolved id via `LyricsData.sourceTrackId`** set by the providers, so the overlay
   learns the canonical key when it lands and can promote the offset. Reason: decouples offset
   loading (immediate) from resolve timing (late).
10. **Never auto-seek when lyrics load late.** Keep seek user-initiated (existing tap-to-line,
    FloatingOverlayService.java:3882). Reason: don't hijack playback.

## 3. Merged ordered implementation plan (6 steps)

Shared data model (built across steps 2-4):
- `SpotifyIdCache`: canonical `title|artist|durBucket` (+ mediaId alias) -> spotifyId, TTL/miss.
- `SyncOffsetStore`: canonical key -> offset_ms, with provisional->resolved promotion.
- `LyricsData.sourceTrackId`: the resolved id surfaced to the overlay.
- One write path: `FloatingOverlayService.applySyncOffset(scope, ms)` (clamp once, persist, set
  tracker, update UI, record undo) used by every control (slider, overlay, AI, tap, nudge).

1. **[SMALLEST USEFUL FIRST PR] One seam + one clamp + getters (no UI/persistence change).**
   Add `applySyncOffset()` and route `setupSyncOffsetUi` (FloatingOverlayService.java:1082-1108),
   `setSyncOffsetLive` (:1904-1911), the pref listener (:297-300) and
   `MainActivity.setSyncOffsetStudio` (MainActivity.java:2366-2369) through it; unify the clamp to
   +-1500 (fixes the live-vs-slider divergence). Add `MediaSessionTracker.getSyncOffsetMs()` and
   `getRawPositionMs()`; add resolve/fetch/`positionAtLoad` timing logs. Files:
   FloatingOverlayService.java, MainActivity.java, MediaSessionTracker.java.
2. **Persistent id cache + token warm-up.** New `lyrics/cache/SpotifyIdCache.java` (own db),
   consulted/written in `SpotifyTrackResolver.resolve`; `warmUpToken()` called from
   `LyricSyncApp.onCreate`; set `LyricsData.sourceTrackId` in `SpicyLyricsProvider`/
   `SpotifyColorLyricsProvider`. Files: SpotifyTrackResolver.java, LyricSyncApp.java,
   provider classes, LyricsData.java, new SpotifyIdCache.java.
3. **Fetch pipeline: cached-LINE shortcut + race id-free providers.** In
   `LyricsProviderManager.performFetch` dispatch a usable cached non-syllable result immediately
   (background SYLLABLE upgrade), and run Kugou/Netease/LRCLIB concurrently with the resolve; keep
   the SYLLABLE-only strict rule, the early stop, and never downgrade. File: LyricsProviderManager.java.
4. **Per-song offset store.** New `lyrics/sync/SyncOffsetStore.java` (own storage); resolve key
   (provisional title|artist|dur -> promote to `sourceTrackId`); load in `onTrackChanged`
   (FloatingOverlayService.java:3162-3176) before lyrics; write via `applySyncOffset`; scope
   indicator in the pill; reset/undo. Files: new SyncOffsetStore.java, FloatingOverlayService.java,
   MainActivity.java.
5. **Tap-to-sync + overlay controls.** `aim` action + optional double-tap anchors the line the user
   hears using `offset' = rawPos - line.startTime` (== `offset + (P - S)`); nudge row; reset/undo;
   `Haptics`; keep single-tap seek unless armed. Files: FloatingOverlayService.java,
   overlay_lyrics.xml, strings.xml, new drawables.
6. **Accessibility + AI parity + optional prefetch/abort.** contentDescription on all new controls;
   extend `set_sync_offset` (AntigravityApiClient.java:331-333) with `scope`; only add next-track
   prefetch / abortable resolve if step 2-3 measurements justify it.

Smallest useful first PR: **step 1** — it is self-contained, removes a live correctness bug (the
unclamped AI offset), introduces the single seam every later step depends on, and adds the getters
and logs needed to measure.

## 4. What still needs a real device

- YT Music's true constant lead/lag and whether `getLastPositionUpdateTime` is `elapsedRealtime`
  based (the 5 s resync window, MediaSessionTracker.java:31); snapshot granularity.
- `positionAtLoad` p50/p90 before and after steps 2-3; verify the cached-LINE shortcut has no
  visible quality regression.
- Tap-sync residual vs a stopwatch; whether any fixed reaction constant is warranted and its value.
- Offset variance across replays of the same song (does calibration stick?).
- Whether the resolved Spotify id is stable enough as a canonical key across YT uploads
  (Topic vs official audio).
- Persistence across process restart / overlay-service restart; token warm-up actual saving.
- Usability: does the pill/nudge fit the small floating window, and is it discoverable without
  permanent clutter.
