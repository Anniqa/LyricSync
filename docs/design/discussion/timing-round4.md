# Round 4 — hold + drag to shift lyric timing (interaction design)

Author: timing-alignment. Discussion only; no code edited. Overlay is a small draggable window (`FloatingOverlayService`), lyrics auto-scroll via `SpringScroller`, offset is one value read live every frame (`FloatingOverlayService.java:3247` → `MediaSessionTracker.getCurrentPosition():448-453`).

## 1. GESTURE CONFLICT — exact disambiguation

Current topology:
- tap on a row = `seekToLine(index)` (`:3762` → `:3882-3908`);
- long-press on a row = `showEditSyllablesDialog` (`:3763-3766`);
- window drag is a separate touch listener on the whole overlay (`:2972`, slop 10 dp) and on the header (`:1311`, slop 6 dp).

A "hold then drag" gesture collides head-on with long-press-to-edit. Fix: **reassign long-press to sync and move the syllable-edit dialog off the row gesture.** Editing syllables is a power feature; put it behind the settings panel (or a small edit icon shown on the *active* line). This removes the ambiguity instead of stacking three meanings on one press.

Replace the two listeners at `:3762-3766` with **one OnTouchListener** per non-interlude row implementing a state machine:

```
DOWN   : consume. x0=rawX, y0=rawY. start ARM timer.
ARM    : after ViewConfiguration.getLongPressTimeout() (~500 ms):
         syncArmed=true; haptic tick; show hint "drag left/right to sync"; offsetAtStart = current offset.
MOVE   : if !syncArmed and hypot(dx,dy) > slop (~8 dp) -> cancel ARM timer, do NOT consume
         (let ScrollView/window drag have it: it was a scroll or a window drag).
         if syncArmed -> offset = clamp(offsetAtStart + gain(dx)); consume; never seek.
UP     : if !syncArmed and moved <= slop  -> tap  -> seekToLine(index)   (unchanged behavior)
         if syncArmed                      -> commit + persist; consume.
CANCEL : if syncArmed -> commit + keep undo (never silently lose the value).
```

Constants: movement slop from `ViewConfiguration.get(context).getScaledTouchSlop()` (~8 dp, do not hardcode); arm = `getLongPressTimeout()` (~500 ms); a **12 dp dead-zone** before the value starts changing (absorbs finger jitter after arming). Which wins: after arming, sync wins for both MOVE and UP; a pre-arm move > slop cancels arming and the row does not consume, so scrolling/window-drag still work. Tap (seek) only happens on a short release with movement < slop. Window drag cannot hijack the row because the row consumes DOWN and calls `requestDisallowInterceptTouchEvent(true)` on arming.

## 2. RATE MODEL — position-based hybrid with acceleration

Absolute (fixed px→ms) alone cannot reach a 5 s intro in one drag on a ~360 dp-wide floating window (at a fine 8 ms/dp that is 625 dp of travel). Velocity-based ("relative") scrubbing is twitchy and hard to reverse. **Use a position-based piecewise gain** (cumulative dx, not velocity), which is predictable and lets a quick drag cover seconds:

```
d = |dx| (dp)                       value contribution (sign applied to dx)
0  ..  12 dp : 0                    (dead-zone)
12 ..  36 dp : 8 ms/dp              (fine trim; band width 24 dp -> ±192 ms)
36 .. 132 dp : gain ramps 8 -> 96 ms/dp   (acceleration; 96 dp -> ~5.18 s)
> 132 dp     : 96 ms/dp             (coarse)
```

Arithmetic for the +5 s scenario: the first 24 dp after the dead-zone give 24·8 = 192 ms; the ramp averages (8+96)/2 = 52 ms/dp over 96 dp → 4 992 ms; total ≈ 5.18 s at **~132 dp** of finger travel — about one third of the window width, one comfortable swipe. A ±200 ms fine trim lives entirely inside the first ~37 dp, with ~8 ms per dp so jitter is imperceptible. Full-window travel (≈300 dp) reaches ≈ 22 s, and the value is clamped at `SYNC_MAX_MS = 60 s` (round 3). So: one drag fixes a 5 s cold-open **and** the fine band is not twitchy — the two requirements do not conflict because the gain is nonlinear.

## 3. DIRECTION / SIGN

Convention is frozen: `getCurrentPosition() = projected − offset`, positive = lyrics later (`MediaSessionTracker.java:451,66-68`; `MainActivity.java:566-570`). Display = c − o, so increasing o makes a line light when c reaches `S + o`, i.e. **later**.

- **Drag right → increase offset → lyrics later.** Drag left → decrease → lyrics earlier.
- Axis: use horizontal dx (`rawX`) only; vertical jitter is ignored. This also avoids the vertical `ScrollView`.
- Mental model: it is a **timeline scrub**, not literally dragging the text. Right = forward in time (same as any scrubber); left = rewind. Say that in the hint text (`"⟵ earlier   +5.00 s   later ⟶"`). If we ever want the "grab the lyric and pull it to where you hear it" model, that is the *opposite* horizontal sign and would contradict the frozen convention — do not use it.
- Mapping: `offset = clamp(offsetAtStart + sign(dx)·G(|dx|), −SYNC_MAX, +SYNC_MAX)`.

## 4. APPLY POLICY

- **Apply live, per frame, while dragging.** The offset is re-read each frame at `:3247`, so the highlight moves immediately — that live audio↔visual loop is the entire point of the mechanic. No preview/commit split: the "preview" *is* the real offset.
- **Persist once on release** into the per-item store (round-3 key = player mediaId, else title|artist|duration bucket) — not on every frame.
- **Auto-save, no confirm dialog** — the release is the confirmation. Keep a **one-tap undo** shown for a few seconds (`"Saved +5.00 s for this video · Undo"`), plus a small undo stack. Undo restores the exact pre-drag value.
- Show while dragging: the live value (e.g. `+5.02 s`), the **scope** (`THIS VIDEO` / `GLOBAL`), and the anchored line — but the anchor is not needed for a pure shift, so show only the value + scope. Haptic tick on milestones (every 250 ms, at 0, at ±MAX).

## 5. INTERFERENCE

- **Auto-scroll fight:** freeze `springScroller` while dragging — suppress `followView` (`:3929-3931`) and keep the viewport still, but let the highlight keep moving (that is the feedback). Resume and jump to the active line on release.
- **Window edges / finger outside:** track with `event.getRawX()`; Android keeps delivering MOVE to the view that captured DOWN even outside its bounds, so the gesture continues past the window edge. Do not cancel on leaving bounds. Only ACTION_CANCEL ends it (commit + undo).
- **Accidental seek:** after arming, consume all MOVEs and the UP so the click listener can never fire; require the 500 ms arm before any value change, so a fast horizontal flick on a row does nothing bad.
- **Window drag:** the row consumes DOWN, and arm calls `requestDisallowInterceptTouchEvent(true)`, so `overlayView`'s drag listener (`:2972`) and the ScrollView cannot move the window/scroll mid-sync.
- **Haptics:** one tick on arm, milestone ticks during, one confirm on release, one for undo. Sample-based ticks can fire ~60/s and feel like buzzing — gate them.
- **Accidental tiny movements:** the 12 dp dead-zone plus pre-arm slop absorbs them.

## 6. EASY TO USE — recommendation

- **Discoverability:** a one-time coach-mark over the lyrics ("Hold a line, drag left/right to sync"), a persistent hint in the sync label while armed, and a row in Settings. Also surface it from the existing sync pill/slider ("Hold a lyric to drag-sync").
- **One-handed:** a horizontal thumb sweep inside the window is one-handed friendly; the arm-then-drag is a single continuous gesture, no menu.
- **Primary vs fallbacks:** make **hold+drag the primary** control (direct manipulation while listening, live feedback, fixes a 5 s intro in one motion). Keep **tap-to-sync** (round-3 "press when the singing starts") as the decisive one-shot / no-drag path; **±10/50/200 ms nudges** for fine trim; the **slider** for absolute/global and for users who prefer it. The drag is the fast path, not the only path.

## 7. ACCESSIBILITY

- The mechanic is inherently auditory (you align by listening), so a blind user *can* use hold+drag if the value is announced — but TalkBack intercepts long-press and drag, so it is unreliable under a screen reader.
- Non-gesture equivalent (required): the **±10/50/200 ms nudge buttons** and the **tap-to-sync button**, each with `contentDescription` that updates to the current value and announces the new one, plus haptics. TalkBack custom actions on the active line ("Earlier", "Later", "Align now") are a good addition.
- Also move the syllable-edit action (currently long-press on a row, `:3763`) to an explicit, labelled control, because long-press is not a reliable TalkBack trigger.
- Conclusion: hold+drag is an accelerator for sighted one-handed use; the accessible path is nudges + tap-to-sync.

## Device-only validation
- Real finger travel for a 5 s fix and whether the chosen gains feel right (tune `8 / 96 / dead-zone` on hardware).
- Whether the arm-then-drag reads as "hold and drag" to users, or needs a visible armed state; and whether moving the edit dialog hurts anyone.
- Haptic cadence and whether the live highlight is smooth at 60 fps while dragging in the small window.
- TalkBack behavior of the nudge buttons + custom actions.
