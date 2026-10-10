# Round 4 — hold-a-lyric + swipe left/right to shift timing live

Read-only design. Builds on rounds 1–3 and the verified touch code.

Verified gesture/touch facts:
- Row tap = seek: `lv.rootView.setOnClickListener(v -> seekToLine(index))` (`overlay:3762`),
  `seekToLine` (`overlay:3882-3902`).
- Row long-press = edit syllables: `setOnLongClickListener -> showEditSyllablesDialog(index, line)`,
  returns `true` (`overlay:3763-3766`).
- The floating window root `overlayView` has its own drag listener: DOWN records touch, MOVE beyond
  `dpToPx(10)` (touchSlop) moves the window (`overlay:2963-3010`). It returns `false` on DOWN, so
  children still get clicks/long-clicks; it only consumes MOVE once its own drag starts.
- The offset is one volatile scalar read every frame: `getCurrentPosition()` (`tracker:450-452`)
  at `overlay:3247`, then `updateHighlight` → `findActiveLine` (`:3904-3933`) and
  `springScroller.followView` (`:3931`). So writing `setSyncOffsetMs` takes effect next frame with
  **no re-fetch**.
- Rows are built in `renderOverlayLyrics` (`overlay:3747-3769`), each with a known `index`.

## 1. GESTURE CONFLICT — exact disambiguation

The three gestures on a lyric row (`rootView`) must coexist. Replace click+longClick with one
`OnTouchListener` state machine (per row). Thresholds:

| parameter | value | source/rationale |
|---|---|---|
| long-press timeout | **500 ms** | `ViewConfiguration.getLongPressTimeout()` default |
| tap slop | **8 dp** | `ViewConfiguration.getScaledTouchSlop()` (~8dp) |
| drag-start (after grab) | **8 dp** horizontal | reuse slop; below this it is jitter |
| direction lock | horizontal iff `|dx| > |dy|*1.2` | ±50° cone, prefer horizontal |
| window-drag slop | 10 dp (unchanged) | `overlay:2970` |

State machine (row-level):
```
DOWN:  armed=true, grabbed=false, moved=false, dx0; start 500 ms timer.
MOVE:  dx=rawX-x0, dy=rawY-y0
       if !grabbed && hypot>8dp: cancel timer; armed=false; return false  -> root may drag window
       if grabbed && |dx|<=|dy|*1.2: cancel sync-vs-drag; return false      -> root drags window
       if grabbed && |dx|>|dy|*1.2: SYNC mode; consume; live-preview offset
UP:    if SYNC mode        -> commit (applySyncOffset SONG), haptic confirm
       else if grabbed      -> open edit-syllables dialog (long-press released without drag)
       else if !moved       -> seekToLine(index)   (tap, unchanged)
       else                 -> nothing (window was moved)
CANCEL: if SYNC mode       -> revert preview, haptic thud
```
Timer at 500 ms: set `grabbed=true`; **do not** open the dialog yet; give a short haptic tick and show
the grab affordance. This is the one behavioural change: the syllable editor now opens on
**long-press release without movement** instead of at the 500 ms mark. That is strictly better UX
(the dialog no longer pops under a still-held finger) and preserves both features. If the dialog must
open at 500 ms exactly, then instead move the editor to a `⋯` action inside the translation/overflow
and reserve hold+drag entirely — but the release-based version is recommended.

Summary of who wins: movement ≤8dp & <500ms → tap/seek. ≥500ms no move (on release) → edit dialog.
≥500ms + horizontal move >8dp → sync drag. Vertical move after grab → window move (sync cancelled).

## 2. RATE MODEL — absolute vs relative vs hybrid

Alignment is done **by ear while dragging**, so the control must (a) be precise enough to stop on
the beat and (b) reach a 5 s intro in one gesture. 

- Pure **absolute** fixed scale (1 dp = c ms): choose c small for precision and 5 s is out of reach in
  a small window; choose c large for reach and it feels twitchy. One constant cannot do both.
- Pure **relative** (drag *speed* → ms/s): feels like scrubbing, but the resulting offset depends on
  how long/fast you drag, not where you stop — bad for stopping precisely on a sound.
- **Hybrid with acceleration (recommended)**: displacement-based, with a fine gear then a coarse
  gear. It is predictable (offset is a function of travel), precise near zero, and reaches seconds.

Constants (display-density independent; `dp` not `px`):
```
|dx| <= 32 dp      : rate = 4 ms/dp          (fine: 1 dp ~ 4 ms -> +-2dp = +-8 ms hand precision)
|dx| >  32 dp      : rate = 60 ms/dp          (coarse)
optional fast flick: x1.5 while |velocity| > 1200 dp/s (cap 90 ms/dp)
dead zone after grab: 2 dp
```
Arithmetic for a **+5 s intro in one drag**:
```
fine segment:  32 dp * 4  = 128 ms
remaining:     5000 - 128 = 4872 ms  -> 4872 / 60 = 81.2 dp
total travel:  32 + 81.2 = 113 dp
```
A 220 dp minimum window (overlay:3091 minWidth) gives ≥110 dp from the centre to an edge, so grabbing
near the opposite side reaches ~6.6 s in one drag; a full 280 dp window reaches ~10–15 s
(`128 + 60*(280-32) ≈ 15 000 ms`). So **5 s fits in one comfortable drag**, and it is not twitchy near
the target because the last 128 ms are at 4 ms/dp. Larger values (or no room) are handled by
**additive re-drags** (lift, grab again; the value keeps accumulating) plus the optional ±1 s buttons.

## 3. DIRECTION / SIGN

Convention frozen in round 2/3: **positive offset = lyrics later**; `getCurrentPosition = projected −
offset` (`tracker:451`). Mental model: *"drag the lyric to where you hear it."*

- **Drag right ⇒ offset increases ⇒ lyrics later.** (Media-editor convention: right = later in time.)
- **Drag left ⇒ offset decreases ⇒ lyrics earlier.**

Intro case check: a 5 s intro makes the lyrics show *early*; the user drags **right** until the
highlight sits on the heard vocal → offset → +5000 ms. `deltaMs = sign(dx) * f(|dx|)`, then
`offset' = clamp(offsetStart + deltaMs)` via `applySyncOffset`, so left/right is exactly the sign of
the change. Show it as `+5.12 s · LATER`/`−0.20 s · EARLIER` so the mapping is learned instantly.
Alternative "rewind" mapping (left = later) is rejected: it contradicts the frozen sign and every
other control.

## 4. APPLY POLICY

- **Preview live, commit on release.** On each MOVE call `sessionTracker.setSyncOffsetMs(previewValue)`
  (not persisted). Because the render loop reads the offset every frame (`overlay:3247`), the highlight
  shifts in real time — exactly the "shift while they hear the sound" ask. On UP, persist the final
  value once through the single `applySyncOffset(scope=SONG, source=DRAG, ms)` path (clamp ±30 s, validate
  `< duration`, write `song_offset` keyed mediaId-first, update pill/slider/label, haptic). On CANCEL,
  restore the pre-drag value.
- **Auto-save, no confirm.** The drag already IS the confirmation ("release when it matches"). Persist
  per song/video automatically. This is the easy-to-use choice; a confirm dialog would defeat it.
- **Undo:** keep the pre-drag value; expose a one-step undo (android snackbar or the pill's long-press).
  Re-dragging is also trivially corrective, so undo is a safety net, not a requirement.
- **Shown while dragging:** the live value `+5.12 s`, the word `LATER/EARLIER`, scope `SONG`, the grabbed
  line's text, and a thin ruler/ticks so magnitude is visible. Also keep the value visible during the
  first ~1 s after release ("+5.12 s · saved").
- Clamp: the value follows the ±30 s store bound from round-3; the live preview also respects it.

## 5. INTERFERENCE

- **Auto-scroll fights the drag.** `updateHighlight` calls `springScroller.followView(activeView,…)`
  (`overlay:3928-3932`) every frame. While a live offset preview is changing, the active line moves and
  the window would scroll under the finger. Fix: a `syncing` flag; while true, **freeze auto-scroll**
  (skip `followView`/`jumpToCurrentLine`) and keep the grabbed row pinned; resume with a smooth
  `scrollToView` on release. Also the `seeked` full-rehighlight branch (`overlay:3911-3923`) should be
  suppressed while syncing.
- **Small window / edges.** Because the row captures the touch, MOVE events keep arriving even when
  the finger leaves the row bounds, so a drag can run past the window edge. Android overlay windows
  still route the active touch stream to the capturing view; verify on device (it can differ by
  OEM/Android version). Never require the finger to stay inside the 220 dp box.
- **Accidental seek.** Tap fires only if `!moved` (≤8 dp). Once grabbed and moving horizontally, the
  click is cancelled, so a sync drag can never seek.
- **Vertical = window move.** After grab, `|dy|≥|dx|` cancels sync and lets the root drag the window
  (`overlay:2982-3005`). Document this so users know vertical moves the window, horizontal syncs.
- **Haptics:** tick on grab (500 ms); a light tick per ~25 ms of accumulated change (rate-limited) so
  the user feels progress without buzzing; confirm on release; thud on cancel. Do not tick on every
  16 ms frame.
- **Finger leaves window / ACTION_CANCEL:** revert to pre-drag offset, haptic thud, no commit.
- **No re-fetch, no seek.** The drag writes the offset only; it must not call `seekToLine` and must not
  touch the lyrics cache.

## 6. EASY TO USE

- **Discoverability:** one-time coach mark on first lyrics ("Hold a line and swipe ⟷ to sync"), a
  Settings → "How syncing works" item, and a visible "⟷" chip plus the live value as soon as the user
  holds for 500 ms. The grab affordance is the teaching moment.
- **One-handed:** the window is draggable to a thumb-reachable spot (existing, `overlay:2963-3010`);
  horizontal swipe is one-thumb friendly. Nudge buttons remain for precision that a one-handed drag
  cannot hit.
- **Primary vs fallback (recommendation):**
  - **PRIMARY (coarse/manual, listening-driven): hold + horizontal swipe.** Best for intros/outros
    because you align continuously against the audio with live visual feedback. This is the user's ask.
  - **PRIMARY (one-shot precision): tap-to-sync "aim" (round 2/3).** Best when you can catch a line
    start; lands the exact constant in one tap.
  - **FALLBACK: ±10 / ±100 ms nudge buttons** (one-handed precision, accessible).
  - **LAST RESORT: the slider** — either demote it to ±10 s or hide it behind Settings, since the drag
    and nudges cover its job better.
  - Keep **long-press-release → syllable editor** available; nothing is lost.
- Both primary paths share `applySyncOffset` and the per-video `song_offset` store, so they compose.

## 7. ACCESSIBILITY

- A sightless user cannot use a continuous drag or read a live value, and TalkBack intercepts
  touch-explore. So the drag is an **enhancement**, and the accessible path must be explicit:
  - **TalkBack custom actions** on each row (via `ViewCompat.replaceAccessibilityAction` or
    `AccessibilityDelegate`): "Align this line" (= tap-to-sync math, round 2/3), "Nudge later",
    "Nudge earlier". These announce the resulting value.
  - **Focusable nudge buttons** ±10/±100 ms and a **numeric offset field / stepper** where the value
    is typed or stepped with +/− and announced (`+5.12 seconds, lyrics later`).
  - **D-pad/keyboard** left/right on a focused row nudges by the fine step (same handler).
  - Announce the scope (SONG/GLOBAL) and the new value on every change.
- Content descriptions on the grab chip and nudge controls; the drag itself needs no description
  because no accessibility user is required to use it.

## Recommended numbers (for the plan)
- Timeouts/slops: long-press 500 ms; tap slop 8 dp; drag-start 8 dp; direction lock `|dx|>1.2·|dy|`.
- Rate: 4 ms/dp for |dx|≤32 dp, 60 ms/dp beyond; optional ×1.5 flick; dead zone 2 dp.
- Reach: +5 s ≈ 113 dp; a 220 dp window covers ~6.6 s from centre; re-drag to accumulate; ±1 s buttons.
- Sign: right = +offset = later.
- Apply: live preview via `setSyncOffsetMs`, commit on release via `applySyncOffset(SONG, DRAG, ms)`
  (auto-save, one-step undo), freeze auto-scroll while syncing.
- Store: `song_offset`, mediaId-primary (round 3), bound ±30 s.
- Accessible equivalent: row custom actions + nudge buttons + numeric field; never gesture-only.
