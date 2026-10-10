# Overlay + Detection scope review

Scope: app/src/main/java/com/lyricsync/app/overlay/**, app/src/main/java/com/lyricsync/app/detection/**
Files in scope: FloatingOverlayService.java (4044 lines, ~187KB), MediaSessionTracker.java,
MediaNotificationListener.java, BootReceiver.java.

## Findings and fixes

### 1. NPE in async stale-callback guards (real defect, fixed)
`fetchKey` is captured as `pendingFetchKey` (declared `null` and reset to `null` by
`cancelAiLyricsRetrieval()`), then the callback does `!fetchKey.equals(pendingFetchKey)`.
When the captured key is null this dereferences null on the callback thread and crashes the
service. Reachable when lyrics were applied without a track fetch in flight (e.g. the AI tool
`apply_lyrics` / `fetch_syllable_lyrics` path sets `currentLyrics` while `pendingFetchKey` is
null), then the user toggles translation or romaji.
- FloatingOverlayService.java:1707 (performTransliteration, was line 1798)
- FloatingOverlayService.java:1826 (autoFetchTransliterationForCurrentLyrics, was line 1916)
- FloatingOverlayService.java:3777 (fetchTranslationForCurrentLyrics, was line 3883)
Fix: compare with `java.util.Objects.equals(fetchKey, pendingFetchKey)`. This preserves the
existing behaviour for every non-null case and only changes the two null cases (previously a
crash): both-null -> not stale (apply), null->non-null -> stale (drop). The other
`fetchKey.equals(...)` sites build the key from `title + "|||" + artist`, which is never null,
so they are left as-is.

### 2. ObsoleteSdkInt: dead `else` branch with removed TYPE_PHONE (fixed)
minSdk is 26, so `Build.VERSION.SDK_INT >= Build.VERSION_CODES.O` is always true and the
`TYPE_PHONE` alternative is unreachable dead code (and a deprecated window type).
- FloatingOverlayService.java:2650 (dialog_ai_chat_history)
- FloatingOverlayService.java:2755 (clear-history confirm dialog)
- FloatingOverlayService.java:2774 (edit-syllables dialog)
Fix: always `setType(TYPE_APPLICATION_OVERLAY)`; removed the obsolete `android.os.Build` import.

### 3. Dead method + orphaned state (removed)
`triggerSongMeaningAnalysis()` (old lines 1512-1589) had zero references in the whole repo
(verified with grep over app/src). Its only exclusive dependencies were the `songMeaningService`
field, the `activeMeaningCall` field (plus no-op cancel guards in fetchLyrics and onDestroy), and
the `aiEmptyHintText` view field (write-only once the method was gone). All removed, including the
now-unused `SongMeaningService` (class) import. `SongMeaning` (nested type) is still needed for
`songChatManager.getMeaning()` at line 1167, so that import stays.
Note: this means the overlay currently has no code path that EVER calls
`SongMeaningService.getSongMeaning`; MainActivity does its own mood/meaning call. This is a
feature that was orphaned, not a defect I introduced — removing it changes no runtime behaviour.

### 4. Redundant assignment immediately overwritten (removed)
In `showAiChatOverlay`, `aiChatTitle`/`aiChatArtist` were set from `currentTrack` and then
unconditionally overwritten a few lines later (`ai_insights_title` and `"title • artist"`).
Removed the first, fully shadowed block.

### 5. Unused parameter (removed)
`addSourceChipsBelowBubble(TextView aiBubble, List<String>)` never used `aiBubble`; removed the
parameter and updated the two call sites.

### 6. Misc dead imports (removed)
- `android.os.Build` (after change 2)
- `com.lyricsync.app.ai.service.SongMeaningService` (after change 3)
- `android.text.method.LinkMovementMethod` (unused, pre-existing)
- duplicate `com.lyricsync.app.ai.client.AntigravityApiClient` import (pre-existing)

## Lint items assessed but intentionally NOT changed
- StaticFieldLeak (line 109, `static volatile FloatingOverlayService activeInstance`):
  It is set in onCreate and nulled in onDestroy, so it does not outlive the service;
  `getInstance()` is a public API used by MainActivity (lines 2536/2655). Changing it would be a
  cross-file API break for no leak win. Accepted/keep.
- InflateParams (line 520, `inflate(R.layout.overlay_lyrics, null)`): correct here — the view is
  added through WindowManager with explicit LayoutParams and has no parent view to attach to.
  Passing a parent would be wrong. Accepted/keep.
- ClickableViewAccessibility (header/bubble/resize OnTouchListeners): accessibility lint only.
  The gesture listeners return false for taps, so normal click dispatch still works. Fixing would
  require adding performClick() calls and could change gesture behaviour for no functional gain.
  Left as-is.

## Files changed
- app/src/main/java/com/lyricsync/app/overlay/FloatingOverlayService.java (only file edited)
No detection/ file needed changes.

## Verified
- Braces/parens removed in balanced pairs (delta vs HEAD: -30/-30 braces, -72/-72 parens).
- No unused private fields, no duplicate imports, no unused imports remain.
- No remaining references to any removed symbol; no remaining SDK_INT/TYPE_PHONE checks.

## Residual risk / not fixed (out of minimal-fix budget)
- MediaSessionTracker.updatePlaybackState runs on both the 16ms poll thread and the main thread.
  Fields are volatile so there are no torn reads, but interleaving can momentarily reorder the
  anchor update. Fixing needs synchronization of the anchor state; not done to avoid touching
  the position logic.
- The poll thread calls binder `getPlaybackState()`/`getMetadata()` every 16ms in steady state.
  Heavy by design; not changed.
- updateTrackInfo's Glide CustomTarget (URI album art) is not cancelled in onDestroy; guarded by
  isDestroyed, so no use-after-destroy, but the request outlives the service briefly.
- setupResizeHandling captures DisplayMetrics once; a rotation while the service runs keeps the
  old max bounds until the service is recreated.
- Unused public APIs observed but kept (no callers in repo): getRetryState(), toggleRomaji().

## File that should be split later
- FloatingOverlayService.java: ~4044 lines / ~187KB — one Service holds window management,
  drag/resize, transport controls, retry+AI state machine, lyrics rendering loop, AI chat +
  bubble windows, history dialogs, and playback tracking. Strong split candidates:
  OverlayWindowController (create/add/remove + drag/resize/persist), OverlayAiChatController
  (chat + bubble + history/dialogs), LyricsFetchCoordinator (fetchLyrics / retry / translation /
  transliteration / cache), OverlayRenderer (render loop, highlight, line views). Not done here
  (out of scope to split).
