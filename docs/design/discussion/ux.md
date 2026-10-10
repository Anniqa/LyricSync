# Sync control that lets a listener set "this line starts now" (angle: user-facing control)

Author: ux (subagent). Read-only analysis. Repo: /root/LyricSync @ bf1fb7f.

## 1. Diagnosis

**Offset is one global scalar, applied at the position layer (good: one seam).**
- `MediaSessionTracker.getCurrentPosition()` returns `projected - syncOffsetMs`, clamped >= 0
  (MediaSessionTracker.java:448-454); `setSyncOffsetMs()` at 477-479 is volatile.
- Every render path reads that single value: the overlay frame callback reads
  `sessionTracker.getCurrentPosition()` (FloatingOverlayService.java:3247) and feeds
  `updateHighlight`/`findActiveLine` (3904, 3935-3951). So any alignment control only has to
  write this scalar; it takes effect on the next 60 Hz frame with no re-fetch.
- Sign is already fixed: positive offset => reported position is smaller => a line's `startTime`
  is reached later => "lyrics later" (MainActivity.java:566-570; strings `lyrics_later`/`lyrics_earlier`).

**The control exists but is buried, coarse, and global.**
- Only reachable by opening the settings panel: tap gear (FloatingOverlayService.java:1087-1098)
  -> panel is `visibility="gone"` by default (overlay_lyrics.xml:138-145), shared with playback,
  font, animation and translation controls. The slider is a 3000-step SeekBar for only +-1500 ms
  (overlay_lyrics.xml:401-408; SYNC_LIMIT_MS=1500 at FloatingOverlayService.java:104-105), dragged
  with a finger inside a small floating window (bind at 1100-1108). No nudge buttons, no fine step,
  no overlay reset (reset exists only in the activity: MainActivity.java:450-454).
- **One pref for all songs**: key `"sync_offset_ms"` is written by four places
  (MainActivity.java:555/561, FloatingOverlayService.java:1083/1103/3156-3157, overlay AI
  1904-1911, activity AI 2366-2369). Calibrating one song changes every song. No per-song key,
  no scope indicator.
- **Divergent clamps between writers**: both UIs clamp +-1500 ms, but `setSyncOffsetStudio`
  clamps +-10000 ms (MainActivity.java:2367); its pref write is then read by the overlay's pref
  listener (FloatingOverlayService.java:297-300) and pushed into the tracker *unclamped*, so the
  live offset can be a value the slider cannot represent. Overlay AI path clamps +-1500 (1905).
- **No "align this line now" gesture.** Existing line gestures: single tap = seek the player to
  the line (3762 -> 3882-3902); long-press = edit syllables dialog (3763). Double-tap and a
  dedicated calibration affordance are unused.

**The slow first seconds are upstream (lazy resolve), not the offset control; the control cannot fix
missing lyrics.** Non-Spotify track, cold process: `SpicyLyricsProvider.fetchLyrics` (57-66) ->
`resolveSpotifyTrackId` (427-450) -> `SpotifyTrackResolver.resolve` (115-129) does a token bootstrap
(319-353) + `searchDesktop` (145-189), optionally Deezer+MusicBrainz (370-435), *then* the official
`/v1/lyrics` call (88-101). Best case ~3 sequential HTTPS round trips; cold worst case up to ~6.
The resolver cache is in-memory only, 256 entries (71-78), so every process restart is cold.
(This is the `latency`/`timing` siblings' territory; noted only so the control set is not expected
to paper over it.)

## 2. Candidate solutions (user-facing control)

### C1. Header "sync pill" + nudge row (fine trim, always visible)
- **Mechanism**: a compact pill in `overlay_header_actions` showing value + scope (`+120 ms · SONG`,
  `0 · GLOBAL`). Tap expands a one-row strip: `[-10] [-50] [aim] [+50] [+10] [undo]`. Each nudge:
  clamp, persist, `setSyncOffsetMs`, update pill/slider, `Haptics.tick`. Long-press a nudge = x5.
- **Gain**: removes the need to open the buried panel; corrective feedback is visible while the
  lyrics scroll (each press moves the highlight on the next frame). ~1 press per step.
- **Cost/risk**: low; new layout row + ~40 lines in `FloatingOverlayService`. Adds some header
  clutter in a small window. No correctness change.
- **Correctness**: unchanged.

### C2. Tap-to-sync calibration ("anchor this line") — the smallest *repeatable* control
- **Mechanism**: an `aim` button puts the overlay in a one-shot calibration mode; the next tap on a
  lyric row anchors that line instead of seeking. Capture `P = sessionTracker.getCurrentPosition()`
  and the tapped line's `startTime S`; compute `newOffset = currentOffset + (P - S)` (algebra:
  projected - offset' = S, and P = projected - offset => offset' = offset + (P - S)). Clamp,
  persist, apply, `Haptics.confirm`, show a short "aligned" toast, exit mode. Optional shortcut:
  double-tap a row does the same without entering a mode.
- **Gain**: one gesture yields exact alignment regardless of the current error; repeatable on any
  line. Removes the "hunt with a slider" loop. Works with the existing scalar seam
  (`MediaSessionTracker.setSyncOffsetMs`) so no renderer change.
- **Cost/risk**: medium. One-shot mode state + hint text; must not break single-tap seek (only
  hijack when mode is on). Human reaction lag (100-300 ms) biases the anchor; add a configurable
  reaction constant rho (default ~150 ms), i.e. `newOffset = currentOffset + (P - (S + rho))`,
  then fine-trim with C1. Doesn't degrade correctness; a bad tap is still one undo away.
- **Correctness**: unchanged for everyone not using it; taps only remap when calibration is armed.

### C3. Per-song offset with visible scope + undo/reset
- **Mechanism**: new `SyncOffsetStore` keyed by track identity (normalized `title|||artist` plus a
  duration bucket, or the resolved Spotify id once known). On `onTrackChanged`
  (FloatingOverlayService.java:3162-3176) load the song offset if present, else the global
  `sync_offset_ms`; show scope in the pill. Reset clears the song override (falls back to global);
  undo restores the pre-edit value. Persist in a **separate** store (own SharedPreferences/DB), not
  `lyrics_cache.db` — that DB drops its table on upgrade (LyricsCacheManager.java:117-121).
- **Gain**: fixes the real case where some songs consistently need their own offset (live/remaster/
  YouTube rips); set once, sticks across sessions. Global remains the default.
- **Cost/risk**: highest of the three — new persistence, scope resolution, key ambiguity for
  covers/live versions, and the key may be unresolved at track-change (the Spotify id is lazily
  resolved, SpicyLyricsProvider.java:59) so the first write can only use title|artist. Additive,
  not a correctness change, but more state to reason about and test.
- **Correctness**: unchanged if global fallback is honored and clear/undo are atomic.

### C4. (Small) Overlay reset/undo affordance
- **Mechanism**: long-press the pill = undo last change; a `reset` action zeroes the current scope.
  Reuse `sync_reset` string. `Haptics.confirm`.
- **Gain**: recovery from accidental nudges without opening the activity. Very low cost.
- **Correctness**: unchanged.

### C5. (Complementary, not my angle) Hide offset until needed / warm-up feedback
- Show the pill only after the first nudge, and surface a clearer "syncing…" state while the lazy
  resolve runs so users understand the first-seconds gap and reach for the control. Low risk; helps
  discoverability without permanent clutter.

## 3. Recommended minimal change set (build in order)

1. **One write path (prerequisite).** In `FloatingOverlayService` add
   `applySyncOffset(scope, source, int ms)` that clamps once, persists, calls
   `sessionTracker.setSyncOffsetMs`, updates label/slider/pill, records `lastOffset` for undo, and
   ticks haptics. Route the existing writers through it: setupSyncOffsetUi bind (1100-1108),
   `setSyncOffsetLive` (1904-1911), pref listener (297-300). Mirror the clamp in
   `MainActivity.setSyncOffsetStudio` (2366-2369) — unify +-1500 vs +-10000. Fixes the drift today.
2. **Sync pill + nudge row (C1) + reset/undo (C4).** `overlay_lyrics.xml` header block (90-134),
   new drawables `ic_minus.xml`/`ic_plus.xml`/`ic_target.xml`/`ic_undo.xml`, strings. Smallest
   visible control; makes alignment obvious and trimmable without leaving the lyrics.
3. **Tap-to-sync (C2).** Arm via the `aim` button (and optional double-tap); intercept the next row
   tap in the click wiring at 3762; reuse `getCurrentPosition()`; one-shot mode + hint; reaction
   constant. Reuse `seekToLine` guard so calibration never seeks.
4. **Per-song scope + indicator (C3).** New `lyrics/SyncOffsetStore.java`; load on
   `onTrackChanged` (3162-3176); scope text in the pill; reset semantics.
5. **Accessibility + AI parity.** contentDescription on every nudge/aim button, and extend the
   `set_sync_offset` tool (AntigravityApiClient.java:331-333) with an optional `scope`
   (`global|song`) so voice/AI paths use the same model. TalkBack has no double-tap-through, so the
   `aim` button (not the double-tap shortcut) must be the accessible path.

Files/classes to touch: `overlay/FloatingOverlayService.java`, `MainActivity.java`,
`detection/MediaSessionTracker.java` (only a `getSyncOffsetMs()` getter if needed),
new `lyrics/SyncOffsetStore.java`, `res/layout/overlay_lyrics.xml`, `res/values/strings.xml`,
new drawables. No provider/resolver changes.

## 4. What to measure (to prove it worked)

- **Apply latency**: tap -> first frame using the new position. Instrument `applySyncOffset` and
  the frame callback (3222-3252); target < 1 frame (~16 ms) beyond input latency (offset is read
  live at 3247, so expect immediate).
- **Calibration residual**: after a C2 tap log `getCurrentPosition() - S` (naive) and the offset
  delta; verify `|residual|` is ~0 before rho and ~rho after. Report distribution over test songs.
- **Taps-to-align**: count nudge presses after each calibration; report median (goal: one tap then
  <=2 trims).
- **Persistence/scope**: relaunch app and re-enter a calibrated song -> offset restored and applied
  on `onTrackChanged` before the first highlight; a global change still affects songs with no
  override; reset/undo restore exact prior values.
- **No regressions**: single-tap seek still seeks; long-press still opens the syllable dialog; no
  new network calls; the pill value equals the tracker value equals the slider value.
- **Discoverability**: time-to-first-successful-align from cold start (optional usability check).

## 5. Open questions for the other participants

1. **Reaction constant**: should tap-to-sync subtract a fixed human reaction lag? If yes, what
   default (150 ms?) and should it be user-tunable?
2. **Per-song key**: normalized `title|||artist|duration bucket` vs resolved Spotify id (unknown at
   track-change for non-Spotify players, filled lazily) — or dual-key write? Which avoids
   cover/live collisions best?
3. **Persistence location**: separate SharedPreferences vs a new table in `lyrics_cache.db` (which
   drops data on schema upgrade, LyricsCacheManager.java:117-121)? My recommendation: separate.
4. **Latency siblings**: is a warm-up/pre-resolve being proposed that would change when the id
   exists, and thus the per-song key timing? Coordinate.
5. **Sign semantics freeze**: keep "positive = lyrics later" (MainActivity.java:566-570) as the
   single contract across all new controls and the AI tool?
6. **Gesture conflict**: is double-tap-to-anchor acceptable given single-tap already seeks and
   long-press edits? Or keep calibration strictly behind the explicit `aim` button?
7. **Clutter budget**: how much overlay header room can we spend on a persistent pill in the small
   floating window, vs revealing it only after the first adjustment?
