# renderer-ui — findings & fixes

Scope: `app/src/main/java/com/lyricsync/app/renderer/**` and `app/src/main/java/com/lyricsync/app/ui/**`
Verified with: `javac` against `android.jar` (android-35) + appcompat/gradle-cache classpath (scope + whole main tree on `-sourcepath`), the pure-JVM self-tests (`Spline/Spring/AnimConfig/AnimStyleDef/AnimSelection`), and `SyllableHighlighterLayerTest` (14 tests, JUnit, all pass). Gradle was **not** run.

## Findings (file:line → defect)

1. `renderer/GradientTextView.java:11` — extends `android.widget.TextView`. Lint `AppCompatCustomView` (Error): custom TextView must extend `androidx.appcompat.widget.AppCompatTextView`.
2. `renderer/GradientWordView.java:12` — same `AppCompatCustomView` Error.
3. `renderer/GradientTextView.java:65-73` — 5× `DrawAllocation` (Warning): `new LinearGradient(...)` + `new int[]{}`/`new float[]{}` built inside `onDraw`. During an active line this allocates a fresh shader every frame.
4. `renderer/GradientTextView.java` — no `onTextChanged`; `cachedWidth` (and hence the gradient span) is only invalidated by size change, so `setText()` reuses a stale measured width. (Latent; current call sites set text once, so not visibly triggered.)
5. `renderer/WordFlowLayout.java:105-106` — 2× `DrawAllocation` (Warning): `new ArrayList<>()` for `rowViews`/`rowLefts` on every `onLayout`.
6. `renderer/SyllableHighlighter.java:91` — off-by-one: comment + unit test say a `DisplayWord` never exceeds 4 syllables, but `cur.syllables.size() < 5` allows merging 5. Reachable whenever romaji is empty/short (the romaji-length cap doesn't stop it), which can produce an unbreakable flow word — the exact overflow the cap exists to prevent.
7. `renderer/GradientWordView.java:754` — `float[] centers = new float[n]` allocated on every `onDraw` while letter emphasis is animating (per-frame allocation, same DrawAllocation class of issue).
8. `renderer/WordFlowLayout.java:28` — `LayoutParams.isWordBoundary` is written (`SyllableHighlighter.java:388,487`) but never read anywhere: dead field + dead writes.
9. `renderer/GradientWordView.java:274` — `setCompactPadding(boolean)` has no callers: dead public method.
10. `renderer/GradientWordView.java:208` — `getEndTime()` has no callers: dead public method.
11. `renderer/SyllableHighlighter.java:637` — `boolean isUpcoming = ...` computed and never used: dead local (the state machine only needs active/past).
12. `renderer/SyllableHighlighter.java:904` — `updateBackgroundVocals(..., boolean stateChanged)` ignores `stateChanged`: dead parameter.
13. `ui/AnimStyleChips.java:156` — selected preview chip label never gets the accent colour. `baseChip()` calls `styleChip()` when the chip has **0** children, and `makePreviewChip()` adds the label afterwards without re-styling, so `styleChip()`'s "keep the label colour in step with selection" branch never runs for the live-preview chips (selected chip = accented border but dim label).
14. `renderer/AnimStyleDef.java:321` — i18n: the `none` effect's `displayName` is `"Tanpa"` (Indonesian) while every other style name is English ("Spring Pop", "Neon Glow", …). Mixed-language chip label.

## Fixes applied

- `GradientTextView`: extends `AppCompatTextView`; moved shader construction into a private `ensureShader()` called from `setProgress()`, `onSizeChanged()` and `onDraw()` (only a first-frame fallback now). Added `onTextChanged()` to invalidate the cached width/shader. Removes all `new` from `onDraw` (fixes 1 & 3) and fixes the stale-width issue (4). Rendering math and thresholds unchanged.
- `GradientWordView`: extends `AppCompatTextView` (fixes 2); removed dead `setCompactPadding`/`getEndTime` (9,10); letter-emphasis `centers` is now a reused field, reallocated only when the letter count changes (7). Rendering unchanged.
- `WordFlowLayout`: `rowViews`/`rowLefts` are reused fields cleared per pass (fixes 5); removed the dead `isWordBoundary` field (8).
- `SyllableHighlighter`: syllable-merge cap `< 5` → `< 4` (fixes 6); removed dead `isUpcoming` (11), dead `stateChanged` parameter (12), and the dead `isWordBoundary` writes (8).
- `AnimStyleChips`: preview chip label now uses `selected ? COLOR_ACCENT : COLOR_TEXT_DIM` (fixes 13).
- `AnimStyleDef`: `none` displayName `"Tanpa"` → `"None"` (fixes 14).

## Files changed (6, all in scope)
- renderer/GradientTextView.java
- renderer/GradientWordView.java
- renderer/WordFlowLayout.java
- renderer/SyllableHighlighter.java
- renderer/AnimStyleDef.java
- ui/AnimStyleChips.java

## Residual risk / not changed
- **AppCompatTextView runtime context**: both views are also created with a `Service` context in the overlay (not an AppCompat-themed Activity). It compiles and the appcompat widgets wrap the context; not run here, so watch for a theme error at runtime, though `attrs` is always `null` and the app theme is MaterialComponents (AppCompat-derived).
- **ViewPropertyAnimator delay leak (latent, not fixed)**: `ViewPropertyAnimator` is cached per View and keeps its `startDelay`. `Anim.enter` sets a delay; `bubbleIn/bubblePulse/bubbleSettle/rotateTo/collapse/setTextAnimated` never reset it to 0 (`pop` does). No current call chain applies `enter(delay)` and then one of those to the same view, so it isn't triggered today; adding `.setStartDelay(0)` there would harden it. Left alone to avoid speculative edits.
- **Overlay font-chip refresh mismatch (out of scope)**: `FloatingOverlayService` calls `FontChips.refresh(..., prefs.getString(PREF_KEY, DEFAULT_KEY))` (raw key) while `build` uses `AppFont.currentKey(...)` (normalised). With an unknown stored key no chip highlights. Fix belongs in the overlay file.
- **Remaining lint warnings in scope (not real bugs / risky to fix)**: `ClickableViewAccessibility` in `FontChips.addPressFeedback` (the safe fix — `performClick()` in `onTouch` while returning `false` — would double-fire the click listener); `SetTextI18n` for the decorative literals "Aa"/"Lyric"; `ViewConstructor` on `InterludeDotView` (its `(Context, long, long)` ctor is correct for its use; adding an unused `(Context)` ctor would be dead code).
- `InterludeDotView` main/opacity springs aren't reset when the view hides, so a seek back into an interlude can resume mid-spring. Cosmetic, left as-is.

## Files that should be split later (not changed — out of scope to split)
- `renderer/GradientWordView.java` (~49 KB, 1042 lines): letter-empty/full frame paths, effects (`drawEffects`, welding/burning/fireworks), spring/letter state all in one class.
- `renderer/SyllableHighlighter.java` (~42 KB, ~970 lines): view construction, script/transliteration layers, per-word/per-line highlight state machines.
- `renderer/AnimStyleDef.java` (~18 KB): the 30-entry registry could move to a data file/table.
- `ui/AnimStyleChips.java` (~13 KB): chip styling + preview loop could split.
