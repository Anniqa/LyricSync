# Round 3 — the +5 s cold-open scenario (intro bias, clamp, tap semantics, auto-assist)

Author: timing-alignment. Discussion only; no code edited. All line refs from the repo as read in round 1/2.

## 1. MATH — where the 5 s lives, and does the tap land on it?

**Two timelines.** Let `a` = time into the *song* (the timeline the Spotify lyrics are authored on, first vocal at some `S`), and `c` = the player's reported position (what `MediaSessionTracker.projectedPosition()` returns). A YT video with an `I`-ms cold-open gives

    c = a + I          (the player clock runs I ms AHEAD of the lyric timeline)

The renderer lights a line when the *displayed* position reaches its start: `getCurrentPosition() >= line.startTime` (`MediaSessionTracker.java:448-453` → `FloatingOverlayService.java:3247,3904,3935-3951`; `SyllableHighlighter.java:626-650`). With `getCurrentPosition() = c − offset`:

    display = c − o = a + I − o
    want display = S when a = S   →   I − o = 0   →   **o = I**

So the 5 s is **not** a property of the lyric file (lyrics are correct against the official 0-start rec), and not a "real timeline offset" to be added anywhere else — it is a **constant player-clock bias β = I**. For the scenario, β = +5000 ms, correct offset o = +5000 ms. **Positive = lyrics later** (same sign as `MainActivity.java:566-570`), which is exactly right: the vocals must wait 5 s.

**Tap-to-sync arithmetic (cleanest form).** Let `R` = raw projected = `c`. The user presses at the instant they *hear* the line start, so the finger registers ρ (reaction lag) after the true start: `a_tap = S + ρ`, hence `R = (S+ρ) + β`. My round-2 formula `o' = o + (P − (S+ρ))` with `P = getCurrentPosition() = R − o` simplifies to

    o' = R − S − ρ      (independent of the current o → idempotent)

Plug in: `o' = (S+ρ+β) − S − ρ = β = 5000 ms` ✓ — it lands exactly on the intro. Without ρ it lands on `β+ρ` = 5150 ms, i.e. lyrics ~150 ms **late** (line lights when a = S+150), so ρ compensation matters here too. Implementation should therefore use `getRawPositionMs()` (=R) and `S+ρ`; it never needs to read the current offset.

**Does the tracker fight it? No — it is blind to β and handles o cleanly.**
- `projectedPosition()` (`:461-474`) never sees `syncOffsetMs`; the offset is subtracted only in `getCurrentPosition()` (`:451`). So it is a pure display-time shift.
- resync compares fresh snapshots to the projection *in the player's own clock* (`:344-360`), so a constant β is invisible: resync neither removes nor worsens it, and it cannot churn because o is not in the comparison.
- `seekTo(target)` adds the offset back (`:495`), and `seekToLine()` passes the lyric start (`FloatingOverlayService.java:3882-3888`) → a row tap seeks the player to `S + o = S + β`, the correct player position. Consistent.
- `adjusted < 0 ? 0` (`:452`) means the intro region maps to display 0 → nothing highlights during the cold-open, correct.
- `trackDurationMs` clamps in the player clock (`:471-472`), fine for a longer video.

## 2. CLAMP — reach both ±200 ms and +5 s (or larger)

Today: `SYNC_LIMIT_MS = 1500` for both sliders (`FloatingOverlayService.java:104-105`, `MainActivity.java:108`), but `MainActivity.setSyncOffsetStudio` clamps ±10000 (`:2367`) while the overlay AI path clamps ±1500 (`:1905`) — the pref listener then pushes the ±10000 value into the tracker unclamped (`FloatingOverlayService.java:297-300`). A +5 s intro is unreachable by any UI.

**Design — two tiers on one stored value `offset`:**
- Keep the slider as a **fine** trim with the existing ±1500 (or ±2000) range.
- Add a separate hard bound `SYNC_MAX_MS = 60_000` (further clamped to `trackDurationMs` when known) for *all* absolute setters: tap-to-sync, coarse ±1 s/±5 s nudge buttons, AI.
- Fine trim via ±10/±50/±200 ms nudge buttons that add to the absolute value (so a jittery finger near ±1500 is not a problem); a `reset` zeroes it. Only this one value is stored — no base+trim duplication.
- Label shows the true absolute value (`+5000 ms · lyrics later`) even when the slider is out of range; the slider is disabled/hidden when |offset| > its range.

**Why not simply raise the slider bound:** the SeekBar runs progress 0..3000 with about 1 ms/step (`setupSyncOffsetSlider`, `MainActivity.java:554-562`; bind at 1100-1108). Mapping ±60000 onto 3000 steps is **40 ms/step**; even 24001 steps gives ~200 ms per physical pixel on a ~600 px floating window — fine ±200 ms trim becomes impossible to drag. Raising the bound also silently changes the meaning of every stored value and makes the ±1500 labels wrong. So keep the fine control where it is and give the coarse part its own control.

**Bound value:** ±60 s. Intros are seconds; 60 s covers spoken cold-opens and matches the old ±10 s AI band with margin; clamping to track duration prevents nonsense.

## 3. TAP SEMANTICS (exact)

State to compute once at tap: `R = getRawPositionMs()`, `ρ = 150 ms` constant, and a chosen target line start `S`. Then `offset = clamp(R − S − ρ, −SYNC_MAX, +SYNC_MAX)`.

**Which line?** `S` must be chosen *before* the offset is known — and the app's displayed position is wrong by β, so "the line the app thinks is active" is wrong for the cold-open (`findActiveLine` at `:3935-3951` would pick a line ~5 s in). Therefore:
- **Primary (deterministic, works mid-song): the row the user taps.** S = that row's `startTime` (`seekToLine` already resolves a row index, `:3762,3882`). The user *reads* the row, so the target is unambiguous regardless of the current offset.
- **Dedicated single button (the user's ask): "press when the singing starts" → S = the first non-interlude line.** This is exactly the cold-open fix. To also serve fine re-alignment, define: if `getCurrentPosition() < firstLine.startTime + 2000` (we are still at the top / intro) → target the first line; otherwise → target the line the user is watching (fall back to a row tap). Interlude rows are never targets.
- Because the button's target is inferred, the feedback must *name* it (§ below).

**Corner cases**
- *Tap before any vocal (during the intro):* the button targets the first line but the press is 5 s too early → offset comes out ~5 s short (`R≈0` → `o'≈−S0−ρ`). Not detectable from code (no audio). Mitigation: label instructs "press when the singing starts", plus a visible computed value and one-press undo; optionally ignore the button until the first frame shows `isPlaying()`.
- *Mid-line tap:* anchoring always uses the **line start** S, so a press in the middle shifts the line's start to "now" and overshoots by the elapsed part. Deterministic and undoable; the name in the feedback makes it obvious. (Do not try to guess a sub-line anchor.)
- *Wrong row:* deterministic — that row becomes the anchor. Undo restores the exact prior value.
- *Twice / double-fire:* `offset = R − S − ρ` is idempotent (no dependence on the current offset), so two correct taps converge to the same value. Debounce input ~300 ms and keep an undo stack (last N) for accidental presses.
- *Sign check:* a line *thought* to be too early → user taps when it should start → R is small relative to S → offset becomes more negative → "lyrics earlier"? No: `offset = R − S − ρ`; if R < S the offset is negative, which makes display later (display = c − o grows), i.e. the line now lights when c reaches S + |o|… let me sanity check with β=0 and lyrics currently rendered 500 ms early (display = a+500): at the true start a=S, R=S, press at a=S+ρ → R=S+ρ, o'=R−S−ρ=0 → correct. Good, the formula is self-consistent with sign.

**Feedback that proves it worked**
- On tap, the highlight should move to the anchored line on the **next frame** — the offset is read live each frame at `:3247`, so there is no re-fetch/re-bind (expect < 1 frame + input latency). `startFrameCallback()` forces a frame even while paused (`:3898`).
- A short label/toast naming the result: `"Aligned to line 12 · +5000 ms"` (line text + applied offset), plus a pop/haptic on the row (`:3893-3894` style), and the pill/label value update. This makes a wrong auto-picked line visible immediately.
- Measurable: log `R − S` at tap (should be ≈ρ) and `getCurrentPosition() − S` after apply (should be ≈0); the active index should change to the anchored index.

## 4. AUTO-ASSIST — what is a safe hint vs a guess

- **Only the LEADING part matters.** Outro length does not create a constant offset (display just clamps at the end, `:471-472`); only leading silence/intro contributes to β. So `playerDuration − spotifyDuration = intro + outro` is **not** the offset.
- **Safe use (hint/bound only):** if `playerDuration > resolvedSpotifyDuration`, the intro is **at most** `Δ = playerDuration − resolvedSpotifyDuration` (assuming outro ≥ 0). That is a valid *upper bound* and can (a) cap `SYNC_MAX_MS`, (b) seed the suggested value, (c) focus the tap search. It is **not** a value to auto-apply: intro and outro are indistinguishable, the catalogue duration may itself be a different pressing, and the player's duration field can be 0/absent (then no hint at all). Present it as "Suggested: +5.0 s — tap Sync when the singing starts to confirm", never as an applied offset.
- **Guess (unsafe):** treating Δ as the exact offset; using the resolver's matched duration; using the first lyric timestamp as the exact vocal onset; auto-applying anything.
- **Human confirm/correct:** the suggested value is pre-filled; the user then either accepts it or presses the tap-to-sync button at the first vocal, which **overwrites** it with the measured `R − S − ρ`. The tap is always the ground truth; the hint only saves a guess.
- **Resolver interaction (important):** `pickBest` rejects any candidate whose duration differs by more than `MAX_DURATION_DELTA_MS = 8000` (`SpotifyTrackResolver.java:61,239`). A cold-open longer than 8 s therefore makes the *correct* Spotify track get **rejected**, so the intro case can degrade to "no lyrics at all", not just a wrong offset. A safe fix is an **asymmetric gate** — allow the player track to be *longer* than the catalogue (intros) while keeping a tight cap on the shorter side — since leading silence makes the player longer only. That should be a deliberate, tested change (it raises the chance of matching an intro-heavy live/extended cut), not a silent one.

## 5. INTERACTION WITH THE FIX PLAN

**The intro case forces one correction to my round-2 key decision.** β is a property of the **video/player timeline**, not of the Spotify recording: two YT uploads of the same song (one with a cold-open, one without) share a Spotify id but need different offsets. Round 2 recommended the resolved Spotify id as the canonical key; for the *offset* that is wrong. Corrected:
- **Offset key = the player's mediaId** (`METADATA_KEY_MEDIA_ID`, captured at `MediaSessionTracker.java:226-228`) when present, else `title|artist|durationBucket`. This key is available **immediately at `onTrackChanged`** (no need to wait for lyrics), so the offset should be **loaded at track change**, revising round 2's "load at `onLyricsLoaded`".
- The resolved Spotify id remains the right key for the **id/lyrics cache** (a property of the song) — keep it there, but do **not** key the offset by it. Optionally store a Spotify-id-keyed *fine trim* (≈0 for streaming) separately later; drop for simplicity.
- **Id cache / cached-LINE short-circuit stay safe:** they cache the lyrics (correct for the song); the per-video offset is applied at render time, so a cached lyric + the correct mediaId offset renders right. The only way the cache could serve a "wrong offset" is if we had keyed the offset by Spotify id — which the correction above removes.
- **"Never auto-seek" holds and is reinforced.** The intro is fixed by shifting the render clock, never the player. Auto-seeking to the first line would hijack playback and would be wrong anyway (the lyrics wait for the vocal, they don't move the audio).
- **Plan deltas:** step 2 changes the canonical key to mediaId-first and loads at `onTrackChanged`; step 2/4 gain the duration-hint (bound + suggestion) and the asymmetric resolver gate; step 2's clamp becomes `SYNC_MAX_MS = 60 s` while the slider keeps ±1500. Steps 1, 3, 5, 6 are unchanged.

## Device-only validation (additions)
- Does YT Music's reported position actually start at 0 for the video (so β is exactly the cold-open), or does it already skip the intro for some videos?
- Real `playerDuration − spotifyDuration` for cold-open tracks and whether it equals the audible intro; whether `METADATA_KEY_DURATION` is present for YT Music videos at all.
- Whether one video's β is stable across replays/sessions (validates mediaId keying) and whether different uploads of the same song need different offsets.
- Tap residual vs a stopwatch to calibrate ρ; how often the button's inferred first-line target matches what the user heard.
