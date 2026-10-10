# Round 2 — cross-critique & merge (pipeline-latency)

Inputs: `pipeline-latency.md` (d0d3f), `timing-alignment.md`, `ux.md`.
Note on timing-alignment.md: it was **0 bytes when first re-read**, then was rewritten; I
re-read the final 11 869-byte / 83-line version and it is used below. (If it is emptied again
after this note, treat this file as critiquing the version quoted here.)

All three agree on the core facts: one global `sync_offset_ms` at one seam
(`MediaSessionTracker.getCurrentPosition`, `tracker:448-453`); no per-song offset; the lazy
resolve sits in front of SpicyLyrics (`SpicyLyricsProvider.java:56-63`); a 2–6 round-trip cold
path. The disagreements are in the details.

## 1. Conflicts (concrete)

**C1 — Per-song offset KEY.**
- timing-alignment §2A: canonical key = **resolved Spotify id**, fallback player mediaId / `title|||artist`.
- ux §2C3 / §5.2: **normalized `title|||artist` + duration bucket**, or resolved Spotify id once known, with a **dual-key write**.
- pipeline: open question (id vs title) but id cache itself keyed `norm(title)|norm(artist)`+duration.
Disagreement: exact-id key (arrives late) vs fingerprint key (available at track change) vs dual-key.

**C2 — Do we key offsets by the player mediaId at all?**
- timing §2A lists mediaId as a fallback key; ux does not treat mediaId as a first-class key.
- pipeline diagnosed that `MediaSessionTracker` already captures `METADATA_KEY_MEDIA_ID` (`tracker:226`)
  and that for YT Music this is the video id — a better key than title|artist.
Disagreement: is mediaId part of the model?

**C3 — Where the stores live / may we touch `lyrics_cache.db` and bump DB_VERSION?**
- pipeline §3 (round 1) proposed extending `LyricsCacheManager` (bump `DB_VERSION`) for the id cache.
- timing §2A and ux §2C3/§5.3: **separate/own store**, explicitly NOT `lyrics_cache.db`.
- Fact: `LyricsCacheManager.onUpgrade` **DROPs the whole table** (`cache:118-120`). Bumping the
  version today destroys every cached lyric. This is the decisive evidence.
Disagreement: extend existing DB vs new DB.

**C4 — Short-circuit a cached LINE while upgrading in the background.**
- pipeline §2E: yes — dispatch a cached LINE immediately, upgrade to SYLLABLE in background.
- timing §2D and current code: the cascade always runs; only SYLLABLE short-circuits
  (`mgr:163-165,200`). Neither timing nor ux endorses returning a cached LINE early.
Disagreement: latency-first early return vs keep-trying-for-best-quality.

**C5 — Tap-to-sync reaction-lag compensation AND its sign.**
- timing §2B: `offset = lineStart − rawPosition` — **no reaction constant**.
- ux §2C2: `newOffset = currentOffset + (P − S)`, then **subtract a configurable reaction constant
  rho (~150 ms)**.
- These are **algebraically opposite**: with the documented convention (`adjusted = projected − offset`,
  positive = lyrics later, `tracker:451`), ux gives `offset' = projected − S`; timing gives
  `S − projected`. Only ux's sign makes `getCurrentPosition()` equal the tapped line's start at the tap.
  So there is both a sign bug and a rho disagreement.

**C6 — Cache TTLs.**
- pipeline round 1: id hits long, **misses short TTL**; lyrics DB uses 7 days (`cache:19`).
- ux/timing: per-song **offsets must persist indefinitely** (calibration must stick); no TTL stated.
- No one fixed an id-hit TTL.
Disagreement: durations/TTL policy per store.

**C7 — Offset sign semantics across all writers.**
- ux §1 freezes positive = "lyrics later", one seam.
- timing §4 measures `offset = lineStart − rawPos` (opposite sign, C5).
- AI tool `set_sync_offset` and `MainActivity.setSyncOffsetStudio` write the same pref with a ±10000
  clamp (`MainActivity:2367`) while UIs clamp ±1500 (`overlay:105`, `MainActivity:108`).
Disagreement: one sign + one clamp contract, or per-writer.

**C8 — Overlay UI footprint.**
- ux: persistent **sync pill + nudge row + reset/undo**, plus tap-to-sync; double-tap as optional shortcut.
- timing: **small affordance on the active line**, keep the slider as fallback; no pill/nudges.
- pipeline: only a "syncing…" status (ux C5).
Disagreement: how much permanent header UI, and double-tap vs explicit aim button.

**C9 — Load timing of the per-song offset (before vs after the resolve).**
- timing §3.2: load in `onTrackChanged`/`onLyricsLoaded`, id becomes known only after fetch.
- ux §2C3: load on `onTrackChanged`, first write can only use title|artist.
- pipeline: the offset must NOT wait for the network resolve.
Disagreement: whether offset load is coupled to the resolved id.

**C10 — Auto-apply / seek on late lyrics.**
- timing §5.2/§5.5: product questions — seek to first line? auto-apply suggested offset?
- ux: calibration is user-driven; heuristics never auto-applied.
Disagreement: auto vs user-confirmed.

## 2. Recommendations (correctness first, cost second)

**C1 → Fingerprint-primary, id-alias reconcile.** Use one row per song keyed by
`fingerprint = norm(title)|norm(artist)|durationBucket` (known at `onTrackChanged`, no network),
and store `media_id` and `spotify_id` as columns. On read, match `fingerprint OR media_id OR
spotify_id` and prefer an exact-id match, then most-recent. When the resolved id lands, reconcile
aliases (if a differently-keyed row exists, prefer the newer and merge). Reason: availability wins
at track-change; exactness wins once known; no blocking on the resolve. Cost: one extra column set +
one reconcile call.

**C2 → Yes, mediaId is a first-class alias.** For YT Music it is exact and available before the
resolve; it is strictly more precise than title|artist for uploads. Reason: correctness +
availability. Cost: near zero (column + probe).

**C3 → New `lyricsync_meta.db` (`MetaStore`), do NOT touch `lyrics_cache.db`.** Decisive: the
current `onUpgrade` DROPs the table (`cache:118-120`), so a version bump wipes lyrics. One new DB
holds both `spotify_id` and `song_offset`; one migration owner. If we ever must extend
`lyrics_cache.db`, first change `onUpgrade` to additive (`CREATE TABLE IF NOT EXISTS` /
`ALTER TABLE ADD COLUMN`) — but that is a separate fix, not this feature. I retract my round-1
"sit in LyricsCacheManager" suggestion.

**C4 → Short-circuit a cached non-SYLLABLE result, but only for a non-syllable-only request, and
keep a background upgrade.** Reason: latency-first and never downgrades; displayable lyrics beat a
blank overlay. Guard: the upgrade is keyed to the same track, only replaces with SYLLABLE, and
never overwrites a translated/edited state; surface "syncing…" (ux C5). Cost: one branch + one
background task. This is a deliberate quality/latency trade — flag to the user.

**C5 → Adopt ux's algebra, fix the sign, make rho explicit and small.** Contract:
`offset' = currentOffset + (P − S)`, with `P = getCurrentPosition()` and `S = line.startTime`;
positive = lyrics later. Add `TAP_REACTION_MS` (default ~150 ms) subtracted as
`S + TAP_REACTION_MS` in the target (i.e. `offset' = currentOffset + (P − (S + rho))`), user-tunable,
never hidden. timing's `S − rawPos` is sign-inverted under the shared convention and must not be
implemented as written. Reason: correctness (one equation, one sign).

**C6 → Per-store TTLs.** `song_offset`: **no TTL**, overwritten when re-calibrated; cleared if the
matched `media_id`/`spotify_id` changes for the same fingerprint. `spotify_id` hits: **no TTL**
(mappings are stable; optionally re-validate after ~90 days); misses: **short TTL (~1 day)** so a
newly-added catalogue match can be retried. Lyrics cache stays 7 days (`cache:19`). Reason:
calibration must stick; negative results are the volatile ones.

**C7 → One sign, one clamp.** Positive = later, applied only in
`MediaSessionTracker.getCurrentPosition` (`tracker:451`). Single `applySyncOffset(...)` write path
(below) clamps ±1500; fix `MainActivity:2367` from ±10000 to ±1500. Reason: the current divergence
is an active bug (AI can set a value the slider cannot display).

**C8 → Ship tap-to-sync behind an explicit `aim` button first; reveal a compact pill only after the
first adjustment; defer the nudge row.** Keep single-tap = seek (`overlay:3762`) untouched; do not
bind double-tap-to-anchor (conflicts with seek and is inaccessible to TalkBack). Reason: small
floating window + accessibility + smallest diff. Cost: less discoverable initially; acceptable.

**C9 → Load the offset on `onTrackChanged` using mediaId/fingerprint; reconcile when the resolved id
arrives.** Never block offset load on the network resolve. Reason: decouples the two features and
keeps timing correct even while lyrics are still resolving.

**C10 → Never seek/hijack playback and never auto-apply a heuristic offset.** A duration-difference
hint is allowed but user-confirmed only. Reason: correctness + product safety.

## 3. Merged ordered implementation plan (<=6 steps)

Shared data model — new DB `lyricsync_meta.db`, owner class `lyrics/cache/MetaStore.java`
(single `SQLiteOpenHelper`; do NOT touch `lyrics_cache.db`, see C3):
```
spotify_id(norm_key TEXT PRIMARY KEY, spotify_id TEXT, duration_ms INTEGER, updated_at INTEGER)
   -- norm_key = normalize(title)+'|'+normalize(artist); spotify_id='' = cached miss
song_offset(
   fingerprint TEXT,        -- norm(title)|norm(artist)|durationBucket(5s)
   media_id    TEXT,        -- player METADATA_KEY_MEDIA_ID (YT video id)
   spotify_id  TEXT,        -- resolved Spotify id, '' until resolved
   offset_ms   INTEGER,
   updated_at  INTEGER,
   PRIMARY KEY(updated_at)) with indices on fingerprint, media_id, spotify_id
```
Single write path — `FloatingOverlayService.applySyncOffset(scope, source, int ms)`: clamp ±1500
once -> persist (global pref when `scope=GLOBAL`, else upsert `song_offset`) -> 
`sessionTracker.setSyncOffsetMs` -> update label/slider/pill -> record undo -> haptic. Route ALL
writers through it: overlay slider bind (`overlay:1100-1108`), pref listener (`overlay:297-300`),
`setSyncOffsetLive` (`overlay:1904-1911`), `MainActivity` slider (`main:558-561`),
`setSyncOffsetStudio` (`main:2366-2369`).

1. **[SMALLEST USEFUL FIRST PR] Per-song offset MVP + one write path.** New `MetaStore.java`
   (`song_offset` table + upsert/read); `applySyncOffset` in `FloatingOverlayService`; load on
   `onTrackChanged` (`overlay:3162-3176`) by fingerprint/mediaId; fix clamp `main:2367`; route
   writers. Files: `lyrics/cache/MetaStore.java`, `overlay/FloatingOverlayService.java`,
   `MainActivity.java`, `detection/MediaSessionTracker.java` (add `getRawPositionMs()` +
   `getSyncOffsetMs()`). No resolver/provider changes. Fixes the live clamp bug and adds the
   headline feature with zero network risk.
2. **Persistent Spotify-id cache + token warm-up.** Add `spotify_id` table to `MetaStore`;
   consult in `SpotifyTrackResolver.resolve` (`:115-130`) before network; store hits and short-TTL
   misses; add `warmUpToken()` and call from `LyricSyncApp.onCreate` (or overlay start).
   Files: `lyrics/provider/SpotifyTrackResolver.java`, `LyricSyncApp.java`.
3. **Surface the resolved id + reconcile.** Add `LyricsData.sourceTrackId`, set by
   `SpicyLyricsProvider`/`SpotifyColorLyricsProvider`, write it in `LyricsProviderManager`
   (`performFetch`/`cache`) and into `MetaStore.spotify_id`; reconcile the current `song_offset` row
   alias and reload if a newer id-keyed offset exists. Files: `lyrics/model/LyricsData.java`,
   `lyrics/provider/*.java`, `lyrics/LyricsProviderManager.java`, `MetaStore.java`.
4. **Tap-to-sync.** `aim` button arms one-shot mode; next lyric-row tap computes
   `offset' = currentOffset + (P − (S + rho))` via the single write path; add `TAP_REACTION_MS`.
   Files: `overlay/FloatingOverlayService.java`, `res/layout/overlay_lyrics.xml`,
   `res/values/strings.xml`, drawables; `MediaSessionTracker.java` gets the raw getter.
5. **Latency: cached-LINE short-circuit + background SYLLABLE upgrade + parallel resolve.**
   In `performFetch`, dispatch a usable cached non-SYLLABLE result for non-syllable-only requests
   and upgrade in background; kick the resolve concurrently with the id-free providers
   (Kugou/Netease/LRCLIB) and feed Spicy when ready; keep the SYLLABLE early-stop and fetchKey guard.
   File: `lyrics/LyricsProviderManager.java` (+ a resolve handle in `SpotifyTrackResolver`).
6. **Rollout + measurement + optional prefetch/abort.** Instrument both paths (see §4); only if
   measurements justify it, add mediaId-keyed prefetch and abortable OkHttp calls.

Smallest single step: **Step 1** — it is self-contained, fixes an existing bug, and delivers the
per-song offset without any resolver/concurrency risk.

## 4. What still needs a real device to validate

1. YT Music `MediaSession`: is `METADATA_KEY_MEDIA_ID` the video id and is it stable? Is
   `getLastPositionUpdateTime` `elapsedRealtime`-based (the 5 s trust window, `tracker:31`)?
2. Snapshot granularity and true constant lag of YT Music; the real `positionAtLoad` p50/p90
   (timing's instrumentation).
3. Tap-sync residual after one tap, and whether `rho≈150 ms` helps or hurts; offset variance across
   replays (does calibration stick?).
4. Whether the resolved Spotify id is stable enough as a cross-player offset alias across YT uploads
   (Topic vs official audio) — decides C1's id-alias value.
5. Overlay header space for the pill on real screen sizes; TalkBack behaviour of the `aim` button.
6. Whether the cold resolve (~1.2 s search, 289 KB body) is the dominant felt latency, vs player
   metadata-callback delay — confirm the ordering of the instrumentation in Step 6.
