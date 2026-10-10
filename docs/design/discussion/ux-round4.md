# Round 4 — hold+drag to scrub lyric timing (author: ux)

Read-only design. Code cited from prior rounds: row gestures (FloatingOverlayService.java:3760-3767),
`seekToLine` (3882-3908), row/word render, `setupDragging` on the overlay root (2963-3017), the
per-frame render loop reading the offset live (3222-3252, `getCurrentPosition` 3247),
`updateHighlight` -> `springScroller.followView` (3904-3933), `SpringScroller` (SpringScroller.java:19-64).

## 1. Gesture conflict — exact disambiguation

Current gestures on a lyric row: `OnClickListener` = seek (3762 -> 3882), `OnLongClickListener` =
syllable-edit dialog (3763). The overlay root also drags the whole window once movement exceeds
`touchSlop = 10dp` (2970-3004), and the body is a `ScrollView` (overlay_lyrics.xml:425). Adding
hold+drag needs a deterministic split.

Recommended (a **two-stage long press**: arm, then move or release):

| Phase | Condition | Result |
|---|---|---|
| DOWN | record `x0, t0`; `return false` so children still see it | — |
| UP before `T_hold` | `t < 500 ms` AND `|dx| < M_tap = 8dp` | **tap = seek** |
| move before `T_hold` | `|dx| >= M_tap` | not a row gesture -> ScrollView / window drag (existing 10dp path) |
| at `T_hold` | `t >= 500 ms` AND `|dx| < M_grab = 6dp` | **arm = grab** (row lifts, `Haptics.tick`, consume) |
| armed + move | `|dx| >= M_grab` | **drag = sync** (consume; `requestDisallowInterceptTouchEvent(true)`) |
| armed + UP, no move | released while armed | **edit syllables** (preserves today's long-press) |
| drag + UP/CANCEL | — | commit (see §4) |

- Thresholds: `T_hold = ViewConfiguration.getLongPressTimeout()` (500 ms; tune to 400 ms),
  `M_grab = 6 dp`, `M_tap = 8 dp`, keep the window drag at 10 dp (2970). Because the row consumes
  once armed, the window never moves during sync.
- What I'd change: nothing moves for seek; the edit dialog now needs the finger to *lift* while
  armed instead of firing at the 500 ms mark. That is the one behavioural change. (Alternative:
  double-tap = edit, freeing long-press entirely — rejected because it delays single-tap seek by the
  ~300 ms double-tap window, making seek feel laggy.) Keep the gesture handling in a small
  `overlay/SyncDragController` so FloatingOverlayService (4082 lines) does not grow further.

## 2. Rate model — hybrid velocity-accelerated absolute (recommended)

- **Pure absolute** (fixed `K ms/px`): with the small window (~280-300 px lyric width) and a 5 s
  intro, one full-width drag gives `K = 5000/300 ≈ 17 ms/px`; then a fine +-200 ms correction is
  only ~12 px and 3-4 px of finger jitter is ~60 ms. Twitchy. Reject as-is.
- **Pure relative** (drag speed -> ms/s, "jog shuttle"): the value depends on speed, so it never
  settles into a stable number; "release when it matches" becomes a moving target (stop to read it
  and it drifts). Reject for audio alignment.
- **Hybrid (recommended): absolute delta with speed-scaled gain.** Capture `offset0` at arm; each
  frame add `msPerPx * dx_px`, with
      `msPerPx = clamp(2 + |v| / 1000 * 30, 2, 32)`   where `v` = finger speed (px/s)
  Slow hand = precision, fast flick = range; a stationary hold keeps the value frozen (stable
  release). Arithmetic for a 5 s intro:

  | finger speed | ms/px | px to shift 5000 ms | time if ~constant speed |
  |---|---|---|---|
  | 100 px/s (fine) | ~5 | 1000 px | 10 s (won't be used for coarse) |
  | 500 px/s | ~17 | 294 px | ~0.6 s (about one window width) |
  | 1000 px/s (flick) | 32 (cap) | 156 px | ~0.16 s |

  So **a 156 px flick at ~1000 px/s fixes the 5 s intro in one quick swipe** that fits inside the
  window; a **slow 40 px drag trims 200 ms**; a **20 px drag trims 100 ms**. The value is stable the
  moment the hand stops, so releasing lands exactly where the user hears it.
- Optional assist: after the drag stops for `>250 ms`, drop gain to 2 ms/px ("precision lock", one
  haptic tick); resume normal gain when movement resumes. Optional magnetic snap to 0 within 30 ms.

## 3. Direction / sign

Freeze "positive offset = lyrics later" (tracker :448-454, label MainActivity.java:566-570).
Mental model = "drag the timing, not the text": think of a left-to-right time axis; later is right.
  `offset = offset0 + Σ msPerPx * dx`, `dx = rawX - x0` (right positive).
- **drag RIGHT -> offset increases -> lyrics later** (exactly the +5 s intro direction).
- **drag LEFT -> offset decreases -> lyrics earlier.**
Show a live label `+5.0 s · later` / `-120 ms · earlier`; the sign self-corrects because the user
watches the highlight land on the line they hear while dragging, then releases. Add a one-time hint
"swipe toward the voice".

## 4. Apply policy

- **Apply live per frame while dragging.** `setSyncOffsetMs` is a volatile write (:477-479) and the
  renderer reads it every frame (3247), so the highlight moves under the finger with zero extra
  plumbing — that live motion *is* the matching feedback.
- **Preview vs commit:** live = preview; on UP/CANCEL **commit once** (persist + scope label), not
  every frame. Keep `offset0` for Undo.
- **Scope:** auto-save to the current **player item** (round-3 key: mediaId else
  title|artist|player-duration), so the intro offset does not leak to the studio track. No confirm
  modal (matches "release when it matches"); provide **Undo** + **Reset** in the pill.
- **While dragging show:** `SYNC · +5.0 s · later · this video`, the anchored/active line text, and
  a thin ruler of ticks (±100 ms). Release shows a brief "Saved · Undo" toast.

## 5. Interference

- **Auto-scroll/animation:** while armed/dragging set `syncDragActive`, skip
  `springScroller.followView` (3931) and any scroll-to-current-line, so the frame loop cannot chase
  the jumping highlight; keep `highlighter.updateHighlight` so words still animate. On release,
  re-enable following.
- **Small window / edges:** use raw coordinates so the drag value keeps updating even past the
  window edge; if the pointer leaves and we get `ACTION_CANCEL`, **commit the current value** (do
  not discard) and show Undo. The accelerated gain (§2) keeps the needed distance (~156 px) inside a
  typical window, so edge behaviour is rarely hit.
- **Haptics:** `Haptics.tick` at arm; a tick per ~100 ms step (jog-wheel feel); `Haptics.confirm` on
  commit; optional `Haptics.snap` at the 0 detent.
- **Accidental seek:** Android cancels a View click once movement exceeds touch slop, so a micro
  move before 500 ms cannot fire seek; `M_tap = 8dp` is the guard.
- **Finger leaves window:** commit (§ above); do not require re-press.

## 6. Easy to use

- **Discoverability:** the gesture is otherwise invisible. Show a one-time coach mark on the first
  overlay session ("Hold a line, then swipe to sync"), and put a hint line in the SYNC pill's
  expanded state. The pill always displays the current offset, so a user can see a control exists.
- **One-handed:** a horizontal thumb swipe on a bottom lyric row is easy; the two-stage hold is the
  friction. Also make the **SYNC pill itself horizontally draggable** (no long-press needed) as the
  one-handed coarse scrub. Provide Resident "later/earlier" `+-50/+200 ms` buttons for no-drag use.
- **Primary vs fallback (recommendation):**
  1. **tap-to-sync** (round-2/3: tap the line you hear) = PRIMARY for setting it once, exact, one
     gesture, no dragging.
  2. **hold+drag scrub** = PRIMARY for fine/continuous alignment and direct manipulation (this
     round); reduces reliance on a reaction constant because the user zeroes in by ear/eye.
  3. **nudge buttons** `+-10/50/200 ms` = precise and accessible fallback.
  4. **slider** = demoted to the settings panel (coarse only).
  All four write the same scalar through one `applySyncOffset()`.

## 7. Accessibility

- A drag-and-listen loop is weak without sight (the matching cue is visual). TalkBack generally does
  not deliver raw drags to the app, so this cannot be the only path.
- **Non-gesture equivalent:** the SYNC pill with "earlier/later" buttons (`+-50`/`+-200 ms`) plus an
  exact numeric entry; every control has `contentDescription` and a >= 48 dp target. Announce value
  changes with `announceForAccessibility` (throttled to ~2/s) or a live region, e.g. "offset plus
  5 seconds, lyrics later".
- **tap-to-sync** works as a single button tap under TalkBack (it needs the target line; expose "sync
  to line N" via the row's accessibility actions). Keep the drag as a sighted-power-user accelerator,
  never the sole control.

## Device-only validation

Real intro lengths and drag distance comfort in the small window; whether `ACTION_CANCEL` fires when
the thumb leaves a `TYPE_APPLICATION_OVERLAY` window; and measured tap/drag residual vs a stopwatch.
