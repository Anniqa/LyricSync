# LyricSync — cleanup handoff

So the next cleanup pass does not have to rediscover the environment, the traps, or
what was deliberately left alone. Build setup lives in `docs/PROJECT.md`.

Archived docs live **outside** the repo at `~/LyricSync-docs/` (`superpowers/` plans
and specs, `design/` discussions and reports).

## Verification standard — read this first

No emulator, no display, no Robolectric (JUnit only). "Verified" means exactly:

```
./gradlew assembleDebug testDebugUnitTest lintDebug
```

and nothing more. As of 2026-10-22: BUILD SUCCESSFUL, lint **0 errors / 73 warnings**.
That proves compilation and tested logic only — overlay runtime behaviour must be
tried on the phone; say so rather than implying it was tested.

## Environment facts that cost time to learn

- Arch Linux ARM in PRoot on the phone; `sudo` = Arch guest, `asudo` = Android host (Magisk root).
- `aapt2` is a static aarch64 build wired via `~/.gradle/gradle.properties` — setup in `docs/PROJECT.md`.
- **APK delivery:** Arch can't see `/sdcard` and `asudo` forwards no stdin (a piped
  `cat | asudo sh -c 'cat > f'` writes a 0-byte file). The Arch rootfs is host-visible, so:
  `asudo cp /data/data/dev.omalauncher/files/arch/root/LyricSync/app/build/outputs/apk/debug/<x>.apk /sdcard/Download/<name>.apk`
  then `md5sum` one side, `asudo md5sum` the other. (From Termux, `/sdcard` is directly
  writable but reads come back Permission denied — verify with `su -c md5sum`.)
- Shipped strings: `aapt2 dump strings <apk>` — raw APK grep fails (UTF-16 pool).

## Recent passes (details live in `git log`)

- **`simplify/trim-feature-surface`** (6 commits, net +1,102/−9,029): `2dae471` Spotify
  `sp_dc` cookie + AI lyric-finder removal, `ai` package dedup · `bdd17e3` duplication
  sweep −207 · `18fa1b3` Activity AI chat removal (44 methods, 1,141 lines) +
  `RECORD_AUDIO` · `1704880` chat UI resources · `2e14aa0` `SongMeaningService` + 40 dead
  members · `2418124` last orphans. `MainActivity` 2,957 → 1,465.
- **2026-10-22 refactor pass** `6fc8cb6..a712495` (plan:
  `~/LyricSync-docs/superpowers/plans/2026-10-22-safe-refactor-pass.md`): `6fc8cb6`
  `typeRank` → `LyricsData.rankOf` · `a9e7b32` `SongChatManager` seams (`stale` /
  `safeForward` / `failFast`) · `9a9b19f` `onResponse` seams (`cancelledExit` /
  `failAndCleanup` / `withStreamSignature`) · `637bdf2` `manageLines` → `LineOp` param
  object · `04fe8fb` dead API (`assistant()`, `setMarkwonInstance()`, `cancelAll()`,
  `buildRequestBody()`) + `accent_soft` · `a712495` first overlay split slice:
  `OverlayChatHistoryController` (service 3,659 → 3,457). Kept on purpose:
  `SongChatManager.getTitle/getArtist/getModelId` (only assertion surface for
  `setCurrentSong`/`setModelId`), `SyllableHighlighter.getLineViews()` (test injection
  seam), `SyllableHighlighter.updateProgress()` (pins layer math). Deferred minors:
  `activeCalls` now write-only; `stale()` is an instance method.

## Open items

1. **`FloatingOverlayService` — 3,457 lines**, the real remaining complexity. Not
   duplication (sweep found −43) nor dead code (−113): one class carrying window, drag,
   resize, render loop, lyric editing, chat, tool status. Splits touch init order and
   shared fields → need the device. Next slices: bubbles/tool-status cluster,
   prompt-templates wiring.
2. **Lint:** 73 warnings, style/i18n/accessibility, mostly pre-existing
   (`SetTextI18n` 22, `DefaultLocale` 9, `SmallSp` 7, `ClickableViewAccessibility` 6,
   `TypographyEllipsis` 4). Not complexity — not cruft.
3. **Kept deliberately, test is the only evidence the behaviour works:**
   `SyllableLyricsParser.decryptKrcToString()`, `JapaneseRomanizer.containsKana()`.
   Do not treat them as dead API.

## Traps — do not break these

- **`ai_source_web`** is reported unused by lint *and* plain grep because
  `CleanMarkdownFormatter` resolves it at runtime via `getIdentifier`. Deleting it
  silently changes the "Web Source" label on every grounding chip. Hit twice already.
- **Internal names are contracts.** `pref_romaji_enabled`, the `"romaji_only"` mode
  string, AI tool-matching keywords (`"romaji"`, `"romanji"`, `"pinyin"`, `"romaja"`),
  and tool declaration names/params/descriptions in `AntigravityApiClient` are a wire
  contract with the model or saved settings. Renaming breaks installs / voice commands.
- **CJK chunking** (`ScriptDetector.chunkByCodePoints`, the `splitDisplayUnits` paths in
  the renderer) fixes Japanese lyrics being sliced instead of wrapped. Preserve exactly.
- **Effect overlays clip to the combination's true fill edge, never the raw sweep.**
  `EffectLayout.fillEdge` maps the three fill paths (sweep / TYPEWRITER's struck run /
  CLASSIC's whole-word flip) to one edge; Shimmer, Sparkle, Welding, Burning clip to it
  and the Shimmer band rides it. Reverting to `p` re-breaks combos (Shimmer vanished for
  12%–57% of every word; Sparkle twinkled over unfilled under Classic/Typewriter).
  Pinned by `renderer/EffectLayoutTest`. Background-vocal words need
  `setWordIndex`/`setLineActive` (`SyllableHighlighter`) or Wave renders them flat.
- **The model picker is not decorative.** `pref_ai_model` is read twice by the overlay
  (`setChatModelSupplier` for the tool executor, `songChatManager.setModelId` before
  sending). Moving it without keeping the pref written silently changes the model.
- **The overlay chat has no model picker — deliberate.** The pill moved to the account
  card when the Activity chat dialog died; `overlay_ai_chat.xml` had 0 model elements at
  `464a65c`. Confirmed on device 2026-10-17. Don't "add back" a missing picker.
- **`LyricsProvider`** declares only `getName()` and `fetchLyrics(TrackInfo)`. Any other
  `fetch*` on a provider is not part of the interface.
- **Deleting a view from a layout:** locate the element with the tag match on the whole
  file (`m.start()` IS the opening-tag position). `rfind('<', 0, m.start())` finds the
  *previous* element and deletes the wrong view — this removed `overlay_artist` and
  `ai_chat_artist` once already.

## Still unverified on the device

1. The **"Model AI" row** in the account card — never rendered.
2. The **overlay lyrics header** — mood badges removed; artist name must still show.
3. The **overlay chat** — the only chat left: history, web sources, and the new
   `OverlayChatHistoryController` flows (restore / delete-one / clear-all).
4. The **motion × effect geometry** (Shimmer band riding the fill edge, Sparkle clipped
   to the struck/flipped fill) and the **background-vocal Wave ripple** — unit-tested
   math, nobody has watched it animate.
