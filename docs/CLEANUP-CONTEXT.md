# LyricSync — cleanup handoff

State as of branch `simplify/trim-feature-surface` (6 commits on top of `464a65c`).
`app/src` holds 172 Java + XML files, 33,848 lines; the branch is +1,102 / −9,029.

This note exists so the next cleanup pass does not have to rediscover the
environment, the traps, or what was deliberately left alone.

## Verification standard — read this first

There is **no emulator, no display, and no Robolectric**. The test dependency list
is JUnit only (`app/build.gradle`). Nothing in this environment can instantiate an
Activity, inflate a layout, or drive the system overlay.

So "verified" here means exactly:

```
./gradlew assembleDebug testDebugUnitTest lintDebug
```

and nothing more. As of this writing: **BUILD SUCCESSFUL, 287 unit tests, 0 failures,
lint 0 errors / 74 warnings.** That proves compilation and tested logic. It does not
prove any UI works. Anything touching the overlay's runtime behaviour has to be tried
on the phone — say so rather than implying it was tested.

## Environment facts that cost time to learn

- Arch Linux ARM inside PRoot on an Android phone. `sudo` = Arch guest; `asudo` = the
  Android host as Magisk root.
- Google ships build-tools for x86-64 only, so `aapt2` is a static aarch64 build at
  `/opt/android-sdk/build-tools/35.0.0/aapt2`, wired up through
  `android.aapt2FromMavenOverride` in `~/.gradle/gradle.properties`. `app/build.gradle`
  pins `buildToolsVersion '35.0.0'` so AGP does not silently download 34.0.0.
- **Delivering an APK to the phone**: Arch cannot see `/sdcard`, and `asudo` forwards no
  stdin (a piped `cat | asudo sh -c 'cat > f'` writes a 0-byte file). The Arch rootfs is
  visible to the host, so:

  ```
  asudo cp /data/data/dev.omalauncher/files/arch/root/LyricSync/app/build/outputs/apk/debug/<x>.apk \
           /sdcard/Download/<name>.apk
  ```

  Verify with `md5sum` on one side and `asudo md5sum` on the other.
- To check what strings actually shipped, use `aapt2 dump strings <apk>`. Grepping the raw
  APK does not work — the string pool is UTF-16.

## What these 6 commits did

| Commit | What |
|---|---|
| `2dae471` | Removed the Spotify `sp_dc` cookie path and the AI lyric-finder; deduped the `ai` package |
| `bdd17e3` | Complexity sweep: duplicated blocks collapsed, `-207` lines |
| `18fa1b3` | Removed MainActivity's in-app AI chat (44 methods, 1141 lines) and the `RECORD_AUDIO` permission; the model picker moved to the account card |
| `1704880` | Deleted the now-unreferenced chat UI and its resources |
| `2e14aa0` | Dropped `SongMeaningService` (dead since `f9d1caf`) and 40 dead API members |
| `2418124` | Removed the last two orphans the chat removal created |

Net across the branch: **+1,102 / −9,029 lines.** `MainActivity` 2,957 → 1,465.

## Open items

1. **9 methods used only by their own tests.** All have zero callers in `app/src/main`,
   so they are unreachable from the app but kept alive by test coverage:
   `AiChatMessage.assistant()`, `CleanMarkdownFormatter.setMarkwonInstance()`,
   `AntigravityApiClient.cancelAll()`, `AntigravityApiClient.buildRequestBody()`,
   `SongChatManager.getTitle()`, `getArtist()`, `getModelId()`,
   `SyllableHighlighter.getLineViews()`, `SyllableHighlighter.updateProgress()`.
   Removing them means editing the tests that exercise them. Two were deliberately kept:
   `SyllableLyricsParser.decryptKrcToString()` and `JapaneseRomanizer.containsKana()` —
   for those the test is the only evidence the behaviour works.

2. **`R.color.accent_soft`** is dead and has been since before this branch. Left alone so
   the commits stayed mechanical.

3. **`FloatingOverlayService` is still 3,659 lines** and is the real remaining
   complexity. It is not duplication and not dead code — a duplication sweep found only
   −43 lines there, and dead-code removal only −113. It is one class carrying the window,
   drag, resize, render loop, lyric editing, chat and tool status. Only splitting it will
   move the number, and that touches init order and shared fields, so it needs the device.

4. **74 lint warnings remain**, all style/i18n/accessibility and mostly pre-existing:
   `SetTextI18n` (22), `DefaultLocale` (9), `SmallSp` (7), `ClickableViewAccessibility` (6),
   `TypographyEllipsis` (4). Not complexity — do not treat them as leftover cruft.

## Traps — do not break these

- **`ai_source_web`** is reported unused by lint *and* by any plain reference grep, because
  `CleanMarkdownFormatter` looks it up at runtime via `getIdentifier`. Deleting it silently
  changes the "Web Source" label on every grounding chip. This trap has been hit twice.
- **Internal names are contracts, not cosmetics.** `pref_romaji_enabled`, the
  `"romaji_only"` mode string, the AI tool-matching keywords (`"romaji"`, `"romanji"`,
  `"pinyin"`, `"romaja"`), and the tool declaration names/parameters/descriptions in
  `AntigravityApiClient` are a wire contract with the model or with saved user settings.
  Renaming them breaks existing installs or existing voice/AI commands.
- **CJK chunking** (`ScriptDetector.chunkByCodePoints`, the `splitDisplayUnits` paths in
  the renderer) fixes a reported bug where Japanese lyrics were sliced instead of wrapped.
  Its behaviour must be preserved exactly.
- **The model picker is not decorative.** `pref_ai_model` is read by the overlay in two
  places: `FloatingOverlayService` `setChatModelSupplier` (for the tool executor) and
  `songChatManager.setModelId` before sending. Removing or moving the picker without
  keeping that pref written would silently change which model runs.
- **`LyricsProvider`** declares only `getName()` and `fetchLyrics(TrackInfo)`. Any other
  `fetch*` method on a provider is not part of the interface.
- When deleting a view from a layout, locate the element with the tag match on the whole
  file (`m.start()` **is** the opening tag position). Using `rfind('<', 0, m.start())`
  finds the *previous* element's tag instead and deletes the wrong view — that mistake
  removed `overlay_artist` and `ai_chat_artist` once already.

## Still unverified on the device

None of the UI work on this branch has been seen running. Three things specifically:

1. The **"Model AI" row** in the account card — the only new UI written, never rendered.
2. The **overlay lyrics header** — the two mood badges were removed (they default to
   visible and had no text, so two empty pills were showing); the artist name must still
   be there.
3. The **overlay chat** — now the only chat that exists; history and web sources must work.
