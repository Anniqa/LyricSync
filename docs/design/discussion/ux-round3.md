# Round 3 — the +5 s intro scenario (author: ux)

Inputs: my round-2 model plus `pipeline-latency.md` / `timing-alignment.md`. Read-only, no code edits.
Core fact (verified): `MediaSessionTracker.getCurrentPosition()` returns `projected - syncOffsetMs`
clamped >= 0 (MediaSessionTracker.java:448-454); the renderer uses it every frame
(FloatingOverlayService.java:3247) and `findActiveLine` picks the last line with
`startTime <= position` (3935-3951).

## 1. MATH — does tap-to-sync land on +5 s?

Model the two clocks. Player-clock time `t_p` (the video timeline); song-clock time `t_s` (the
official Spotify track the lyrics were authored against). A 5 s cold-open means the same audio
event is at `t_s` in the lyrics and at `t_p = t_s + 5000` in the player. So
`t_s = t_p - 5000`, i.e. a **timeline-origin offset beta = +5000 ms**, not a player bias.

Renderer: a line with `startTime S` highlights when reported `P >= S`. We want `P = S` exactly when
the player clock reaches the heard event `V = S + beta`:

    reported = raw - offset        (raw = projected player position, beta already inside raw)
    want:  raw - offset = S   when raw = S + beta
    => offset = beta = 5000

Positive => reported is smaller => the line's start is reached later on the player timeline =>
"lyrics later" (matches MainActivity.java:566-570). Correct sign.

Tap arithmetic. ux formula: `offset' = offset + (P - S)`, and since `P = raw - offset` this equals
`offset' = raw - S`. Plug in offset_0 = 0, first vocal line S = 0, user taps at video t = 5.0 s:

    raw = 5000, P = 5000 - 0 = 5000
    offset' = 0 + (5000 - 0) = +5000        (== raw - S = 5000)
    check: reported = 5000 - 5000 = 0 = S   -> the first line snaps on at t=5.0 s  ✓

It lands exactly on the constant, because any line the user taps contributes
`offset' = V_tapped - S_tapped = beta`. E.g. tap the S = 2000 line the moment it is heard
(video t = 7000): `offset' = 7000 - 2000 = +5000`, same value. Required: the user taps the line
they HEAR (ux). Tapping the *currently highlighted* line (timing) gives
`raw = 5000, S_active = 5000` -> `offset' = 0`, i.e. it "aligns" the already-wrong line and never
discovers the intro.

Where the 5 s lives: a **real timeline offset** (video origin precedes song origin), distinct from a
player measurement bias. Both are corrected by the same additive scalar because the tracker
faithfully follows the report; but only the structural one is hintable (see §4).

Anchor / resync / interpolation: **ignore it.** `projectedPosition()` advances the pre-offset
player clock (461-475); resync compares `projectedPosition(now)` against the fresh player snapshot
(346-347, 353-360) and `needsResync` uses `drift > RESYNC_THRESHOLD_MS = 400` (:35) — all before the
offset is applied. The offset is a pure display-time shift, so it cannot trigger resync and resync
cannot cancel it. `seekTo` re-adds the offset (`target = positionMs + syncOffsetMs`, :492-497), so
it also survives user seeks. Caveat: `getCurrentPosition` clamps at 0, so during the 5 s intro
reported is 0 and `findActiveLine` returns 0 when no line matches (3949) -> line 0 sits lit through
the intro. Optional polish: return -1 there so nothing is active before the first line.

## 2. CLAMP — reach both +-200 ms fine and +5 s (or more)

Today: UI `SYNC_LIMIT_MS = 1500` (FloatingOverlayService.java:104-105, MainActivity.java:108); the
AI/studio setter clamps +-10000 (MainActivity.java:2367) while the overlay live path clamps +-1500
(1905) -> inconsistent. A +5000 offset is unreachable through the UI.

Design: decouple **bound** from **resolution**.
- Hard bound: raise to **+-60000 ms** in the single `applySyncOffset()` seam (round-2 step 1), and
  when `trackDurationMs > 0` (tracker :65, set from `METADATA_KEY_DURATION` at :222-224) also clamp
  to `[-duration, +duration]` — an offset larger than the whole track is meaningless.
  Rationale: cold-opens/spoken intros can exceed 10 s; +-60 s is generous, and the dynamic clamp
  keeps it sane per track.
- Resolution stays fine: keep the existing +-1500 slider as the *fine* trim (3000 steps ~ 1 ms/step)
  and add explicit coarse controls for intros: nudge `+-10 / +-50 / +-200 ms` plus `+1 s / +5 s`
  jump buttons (and an exact "offset (s)" entry for power users). The value shown is always the
  single scalar.
- Why not just raise the slider bound: a 3000-step SeekBar stretched to +-60000 is ~40 ms/step and
  with a ~300 px thumb track ~400 ms/px, so the +-200 ms correction becomes impossible; the
  `progress - SYNC_LIMIT_MS` centering (MainActivity.java:558-559, overlay :1100-1101) and
  `setProgress(SYNC_LIMIT_MS)` reset (MainActivity.java:452) must move to the new centre; and the
  AI-vs-UI clamp divergence would widen. So raise the bound in one place, keep a fine slider, and
  add coarse steps.

## 3. TAP SEMANTICS (exact)

- **Target = the line the user hears** (ux), not the highlighted line (timing): see §1.
- **Programmatic pick**: the row `OnClickListener` already yields the index
  (FloatingOverlayService.java:3762); take `S = currentLyrics.lines.get(index).startTime`, skip
  interlude rows (`line.isInterlude`, :3760). Then
  `newOffset = getRawPositionMs() - S` (new getter == `offset + (getCurrentPosition() - S)`),
  minus a reaction constant `rho` (default 0; see round 2). Prime the UI: highlight the tapped row
  and scroll it to centre.
- **Before the first lyric**: the user scrolls up and taps the first line; if it is truly not
  reachable, a fallback "sync to first line" uses `lines.get(0).startTime`, clearly labelled as an
  assumption. (Do not silently use the highlighted line.)
- **Mid-line**: `S` is the line start, so tapping mid-line adds the offset into the line as error
  (up to one line length). Instruction is "tap when the line begins"; log the residual; the nudge
  fixes it. Optional refinement: snap to the nearest word `startTime` (SpicyLyricsProvider builds
  per-word timings, :246-324) for a finer anchor.
- **Wrong line**: error = gap to the true line (can be a multi-second instrumental gap). The pill
  shows exactly what was anchored ("Aligned to «line text» · +5.0 s") with Undo, so a mis-tap costs
  one tap.
- **Twice**: the formula is absolute (`offset' = raw - S`), so a second tap on the correct line is
  idempotent (if already aligned, P at tap = S -> offset' = offset); a second tap on another line
  re-anchors to it. No accumulation, no drift.
- **Feedback that proves it worked**: the highlight snaps onto the tapped line on the next frame
  (render loop reads the position live, :3247), `Haptics.confirm`, and a pill/toast with the exact ms
  and the anchored line text, plus an Undo chip. That is the observable proof.

## 4. AUTO-ASSIST — safe prior?

The duration delta is the only prior available, and it is a **hint, not a truth**. Player duration
`D_p` (`TrackInfo.duration`, tracker :222-224) vs the resolved Spotify track duration `D_s` (the
candidate's `totalMilliseconds`, SpotifyTrackResolver.java:233):

    delta = D_p - D_s = intro + outro   (video starts before and/or ends after the song)

- It is an **upper bound on the intro**, not the intro: if there is also an outro, using `delta`
  shifts the lyrics too late by the outro length. So it is a suggestion to seed a first guess, never
  silently applied.
- Is it even available? Only for a `delta` the resolver accepts: it hard-rejects candidates whose
  duration differs by more than `MAX_DURATION_DELTA_MS = 8000` (SpotifyTrackResolver.java:60-61,
  239). So `delta <= 8 s` for any accepted match, and small deltas are indistinguishable from
  pressing/rounding noise. **This also means a long cold-open (intro > 8 s) makes the real track fail
  resolution entirely -> no Spotify id -> no Spicy lyrics at all** (SpicyLyricsProvider.java:56-67,
  427-450). That is the "remaining bottleneck" this scenario exposes (see §5).
- Safe hint vs guess: safe to *offer* `offset ~ delta` as a chip ("Intro ~+5.0 s") only when
  `delta` is well above noise (say > 1500 ms) and the id match passed; it is a guess when
  `delta <= ~1.5 s` (rounding) or when the match's duration guard is loose (large delta). Never
  auto-apply; always show the number and an Undo.
- Human confirmation: applying the hint sets the scalar; the user then tap-syncs (which corrects the
  outro residual) or nudges +-10/50 ms. The tap is the ground truth; the hint only saves the user
  from hand-cranking 5000 ms.

## 5. INTERACTION WITH THE FIX PLAN

- **Cached-LINE short-circuit is safe and unchanged.** The offset is applied at read time in
  `getCurrentPosition()` (448-454) and is not stored in `LyricsData`; serving a cached LINE/STATIC
  at a new offset shifts only the clock, so the intro case cannot bake a wrong offset into cache.
- **The id cache must stay loose enough to resolve intro variants.** For `delta <= 8 s` the current
  gate is fine; for longer intros it rejects the real song. Fix (part of the latency work): make
  duration a soft ranking signal (it already contributes `durationScore`, SpotifyTrackResolver.java
  :235/242) and widen/disable the hard gate for known video players (YTMUSIC_PKG, tracker :24) while
  keeping it strict for Spotify. Otherwise a >8 s intro yields no lyrics and nothing to align.
- **Offset key must change** (correcting my own round-2 call and timing's): do **not** promote the
  offset to the resolved Spotify id. The intro offset is **per player item / per video**; the same
  song on Spotify (no intro) must not inherit it. Key the offset store by the player identity:
  `track.trackId` (the player mediaId, captured at tracker :226; pipeline D2) when it is a stable
  non-Spotify id, else normalized `title|artist|player-duration` bucket. The **id/lyrics cache stays
  keyed to the song** (resolved Spotify id / `title|artist`); the two caches intentionally differ.
- **Never auto-seek is unchanged.** Applying a big offset shifts only the reported clock; playback
  is never moved. `seekTo` re-adds the offset (492-497), so it stays consistent after a user seek.
- **Ordered-plan deltas** (round-2 plan otherwise stands): step 2 adds the duration-gate
  relaxation; step 4 keys the offset store on the player item (mediaId / title+artist+player-dur)
  and can seed the duration-delta hint; step 5 adds the interlude-skip and pre-first-line handling;
  optional `findActiveLine` -1 polish (3949) so nothing lights during the intro.

## 6. Device-only validation

Whether YT Music's duration is the video length (so `delta` is meaningful), real intro/outro split,
tap residual vs stopwatch (to set `rho`), and offset variance across replays of the same video.
