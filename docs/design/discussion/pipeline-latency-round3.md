# Round 3 — long-intro (cold-open) scenario: tap-to-sync math, clamp, semantics, auto-assist

Read-only analysis. Uses the round-2 formula and the code already read
(`MediaSessionTracker.java`, `SpotifyTrackResolver.java`, `FloatingOverlayService.java`,
`MainActivity.java`, `SeekBars.java`, layouts).

## 1. MATH — plug a +5 s intro into the tap-to-sync formula

Definitions (all in milliseconds):
- `t_p` = player timeline position (what `projected` tracks; `MediaSessionTracker.projectedPosition`).
- `beta = 5000` = length of the YT-only intro (video cold-open); the official song content
  (whose Spicy timestamps are authored from 0) starts at `t_p = beta`.
- content time `t_c = t_p - beta`. Spicy lyric timestamp `S` is in `t_c`.
- renderer shows line `S` active iff `getCurrentPosition() >= S`.
- `getCurrentPosition() = projected - offset` (`tracker:451`), so **positive offset ⇒ lyrics later**
  (`tracker:66-68`).

**Required offset.** Line `S` must be active when `t_p = beta + S`:
```
projected - offset = S            with projected = t_p = beta + S
(beta + S) - offset = S
offset = beta = +5000 ms          ✓ (positive = later, as the user hears the vocal 5 s into the video)
```
So the 5 s lives as a **real timeline offset** `beta` (origin mismatch between the player timeline
and the canonical track), *not* as tracker bias.

**Tap formula check (round-2 / ux): `offset' = currentOffset + (P − S)`, where
`P = getCurrentPosition()` at the tap.** At the instant the user hears line `S`, `projected = beta + S`,
so `P = projected − currentOffset = beta + S − currentOffset`. Then:
```
offset' = currentOffset + (P − S)
        = currentOffset + (beta + S − currentOffset − S)
        = beta = +5000 ms          ✓ independent of currentOffset
```
Submit: the +5 s intro lands **exactly** on the right constant (`+5000`), and the result does not
depend on the offset that was active before the tap (so a second tap re-converges, not doubles).
timing-alignment's `offset = S − rawPos` would give `−5000` here — sign-inverted; do not use it
(round-2 C5).

**Residual.** The constant measured by a tap is `beta + gamma + rho`, where `gamma` is any
constant player-report bias and `rho` is human reaction. `beta` is what we want; `gamma` is absorbed
too (fine, as long as it is constant); `rho` is corrected explicitly (`TAP_REACTION_MS`).

**Does MediaSessionTracker's anchor/resync/interpolation fight it? No — it ignores it.**
- `getCurrentPosition` applies the offset only at the very end (`tracker:450-452`).
- `projectedPosition` (`tracker:461-474`, used by resync at `:344-360`) does **not** contain
  `syncOffsetMs`, so resync compares `newPosition` (player report) against the projection, both in
  `t_p`; the offset is invisible to it. Resync only corrects drift `gamma > RESYNC_THRESHOLD_MS=400`
  (`tracker:35,355`) — it cannot and will not discover `beta`.
- Interpolation (16 ms poll, `MAX_EXTRAPOLATION_MS=2000` cap `tracker:39,467`) runs in `t_p`; a
  constant display shift does not interact with it.
- Duration clamp uses the **player** duration (`trackDurationMs`, `tracker:224,472`), which already
  includes the intro — correct.
- Caveat: `adjusted < 0 ? 0` (`tracker:452`). During the 5 s intro `getCurrentPosition()=0`, so a
  line whose `startTime` is 0 (or an interlude starting at 0) is shown active *for the whole intro*.
  Cosmetic; optionally insert a lead-in interlude. It does not affect the tap calibration.
- `seekTo` adds the offset back (`tracker:495`), so tapping a line still seeks to the right content
  time after calibration.

## 2. CLAMP — reach both ±200 ms fine trim and ≥5 s intro

Current: `SYNC_LIMIT_MS = 1500` (`overlay:105`, `main:108`); overlay AI clamps ±1500 (`overlay:1905`);
`MainActivity.setSyncOffsetStudio` clamps **±10000** (`main:2367`) — already divergent.
A +5000 ms intro is **unreachable** through every UI path today (`clamp(5000, -1500, 1500) = 1500`).
The layout slider is `android:max="3000"` with midpoint 1500 (`overlay_lyrics.xml:402-408`,
`activity_main.xml:979-984`), and code does `progress = offset + SYNC_LIMIT_MS` (`overlay:1100`,
`main:558`) — i.e. **1 ms/step**.

**Design:** one storage/validation bound + tiered UI.
- Set `SYNC_LIMIT_MS = 30_000` (±30 s) — used by the store, all writers and the AI tool. Rationale:
  cold-opens/spoken intros run seconds→tens of seconds; 30 s covers realistic cases; if a video
  exceeds it, the constant is trivial to raise. Also validate `|offset| < track duration`, because
  an offset ≥ duration pins `getCurrentPosition()` at 0 for the whole track (`tracker:451-452`).
- Fine trim: explicit nudge buttons `−100 / −10 / +10 / +100 ms` (and optionally ±200) around the
  current value — precision without a finger-drag, works at any magnitude.
- Coarse reach: **tap-to-sync is the primary way to reach +5 s** (one gesture, section 3). Optionally
  a seconds stepper `−5 / −1 / +1 / +5 s` for manual jumps.
- If the linear slider is kept: set `max = 2*SYNC_LIMIT_MS` **in code** and snap to 10 ms; the
  3000-step bar then spans ±30 s at 20 ms/step.

**What breaks if we simply raise the slider bound:**
1. `android:max` stays 3000 while `progress = offset + SYNC_LIMIT_MS` becomes up to 31500 → the
   SeekBar clamps progress to 3000 → displayed slider and actual offset desync (`overlay:1100`,
   `main:558`). Must also set `max` programmatically (e.g. `seekBar.setMax(2*SYNC_LIMIT_MS)` in
   `SeekBars.bind` or at bind sites).
2. Resolution collapses from 1 ms/step to 20 ms/step; fine trim by dragging becomes impossible →
   need nudges/snapping.
3. Global-pref contamination gets worse: `sync_offset_ms` is one value for all songs (`overlay:1083,
   1103,3156-3157`; `main:555-561`). A +5 s global offset shifts every track → the per-song
   (really per-video) store must ship before/with the bound increase.
4. Writer divergence worsens unless `main:2367` (±10000) and `overlay:1905` (±1500) are both routed
   through the single `applySyncOffset` with the new bound.
5. An offset larger than the song can pin position at 0 forever — validate against duration.

## 3. TAP SEMANTICS (exact)

- **Which line?** The line the user *hears* (ux's call), and the cleanest programmatic pick is the
  **lyric row they tap**. In aim mode, a row tap anchors that row instead of seeking (single-tap
  already seeks: `overlay:3762` → `seekToLine`). `S = tappedRow.startTime`; `P = getCurrentPosition()`;
  `offset' = currentOffset + (P − S)` applied via the single `applySyncOffset(SONG, …)`.
  Why not "the currently highlighted line"? Because the highlight is computed *with the wrong
  offset*, so for a 5 s intro it is already several lines ahead of what the user hears — anchoring it
  would pick the wrong line and silently mis-calibrate. Row-tap makes `S` the user's ground truth.
- **Tap before the first lyric:** anchor line 0 (`S = firstLine.startTime`). If the first line starts
  at 0 this yields `offset' = projected at tap` = intro length — exactly the cold-open case. Valid.
  (If there is no lyric line at all, ignore with feedback.)
- **Mid-line tap:** the calculation is biased by how far into the line the user tapped. Show a hint
  "tap when the line starts"; a mid-line tap still converges after one more tap.
- **Wrong line:** re-arm aim mode and tap the right line; the formula is absolute, so one correction
  fixes it. Keep a one-step undo that restores the previous offset.
- **Twice:** recompute on every tap (`offset'` is independent of `currentOffset`), so two taps on the
  same line at its true start are idempotent; a second tap on the correct line repairs a bad first tap.
- **Feedback that proves it worked:** apply immediately through the single write path; the frame
  callback reads `getCurrentPosition()` at 60 Hz (`overlay:3247`), so the **tapped line becomes the
  active line on the next frame** and subsequent lines track the audio. Also show the computed value
  (`+5.12 s · SONG`), a confirm haptic, and an undo affordance. Best proof is a replay: the calibrated
  row lights up with the heard vocal and needs no further taps.
- **Never seek while calibrating** — aim-mode taps must not call `seekToLine` (`overlay:3762,3882`).

## 4. AUTO-ASSIST — is a safe prior available?

Let `D_player` = player track duration (`trackDurationMs`, `tracker:224`) and `D_spot` = the
resolved Spotify track's duration. Define `delta = D_player − D_spot`.

- **Safe hint (not a value):** if `delta > 0`, the video is longer than the canonical track, so an
  intro and/or outro exists and the calibrated offset satisfies `0 ≤ beta ≤ delta`. A UI prompt is
  safe: *"This video is 5.0 s longer than the Spotify track — it may have an intro; tap the first line
  you hear to align."* Use `delta` to prefill the coarse control/slider *around* the region, never as
  an applied value.
- **A guess, not safe:** auto-setting `offset = delta` assumes the extra time is all intro and no
  outro. When the song is followed by an outro (or the durations differ for an unrelated reason —
  edit/remix), `delta` overstates the intro. `D_spot > D_player` means the resolved track is a
  different/longer edit, so no offset hint at all.
- **Human tap confirms/corrects:** tap-to-sync computes the absolute offset regardless of any hint,
  so the hint only improves discoverability; the tap is the ground truth.
- **Bottleneck note (this scenario also breaks resolution):** `pickBest` rejects any candidate with
  `delta > MAX_DURATION_DELTA_MS = 8000` (`resolver:61,239`), and `DURATION_WINDOW_MS = 15000`
  reduces `durationScore` (`resolver:60,235`). A 5 s intro (delta 5000) still resolves: score ≈
  `100*(0.55+0.30+0.15*(1−5000/15000)) − versionPenalty ≈ 95` (well above `MIN_SCORE=68`). But a
  **10 s+ cold-open yields delta > 8000 and the correct Spotify track is rejected outright**, so no
  Spicy lyrics arrive and tap-to-sync has nothing to align. Fix: for non-Spotify players relax the
  duration filter to **one-sided padding tolerance** (accept `D_spot ≤ D_player` when
  `D_player − D_spot ≤ ~30 s`), while still rejecting `D_spot > D_player + small`, and penalise score
  gently rather than hard-reject. This is concrete work for "keep fixing the bottleneck".

## 5. INTERACTION WITH THE FIX PLAN

- **Offset key must be per-video, so mediaId is mandatory, not just an alias.** Two YT uploads of the
  same song can have different cold-opens; a `title|artist|durationBucket(5 s)` fingerprint would
  collide (a 5 s intro difference can fall in the same bucket), and a global pref definitely
  contaminates. Recommendation (tightens round-2 C1/C2): when `METADATA_KEY_MEDIA_ID` is present
  (`tracker:226`), **key `song_offset` by mediaId first**, reconcile with the resolved Spotify id for
  cross-player transfer, and use the fingerprint only when no media id exists.
- **Cached LINE / id cache do NOT serve a wrong offset.** Lyrics content is shared and keyed by
  `title|artist` (`cache:97`); the offset is a separate row keyed by the video. So a cached LINE is
  safe to short-circuit (round-2 C4) *provided* the offset store is per-video. If the offset were
  keyed by the same coarse title|artist key, a cached result would indeed inherit the wrong shift —
  that is an argument against a coarse offset key, not against the short-circuit.
- **Ordered plan changes:** fold the clamp widening (`SYNC_LIMIT_MS → 30_000`, fix SeekBar `max`,
  route `main:2367`/`overlay:1905` through `applySyncOffset`) into Step 1, because without it
  tap-to-sync cannot reach +5 s. Make the offset key mediaId-primary in Step 1 too. Add the resolver
  one-sided duration tolerance to the id-cache step (Step 2) so long-intro videos resolve at all.
  Steps 3-6 are unchanged.
- **`never auto-seek` is unchanged.** The tap writes the offset; it must not move the playhead. The
  intro is handled by shifting the lyric clock (`-offset`), not by seeking. Likewise no auto-apply of
  the duration hint — user-confirmed only.
- One extra: with `offset = +5000`, the first line may already display during the intro
  (`tracker:451-452`); optionally prepend a lead-in interlude so the overlay is blank during the
  cold-open rather than showing line 0.

## Recommended concrete numbers (for the plan)
- `SYNC_LIMIT_MS = 30_000` (store + all writers + AI), validate `|offset| < duration`.
- Fine nudges ±10/±100 ms; coarse via tap-to-sync or ±1/±5 s stepper; slider `max = 2*SYNC_LIMIT_MS`
  with 10 ms snap if kept.
- `TAP_REACTION_MS = 150` (tunable), subtracted inside `S` as `offset' = currentOffset + (P − (S+rho))`.
- Resolver duration filter: accept `0 ≤ D_player − D_spot ≤ 30_000`; keep hard reject when
  `D_spot > D_player + 2_000` (truncated/different edit).
