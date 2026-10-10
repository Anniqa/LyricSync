# FloatingOverlayService.java — static context, playback-state atomicity, Glide target

Scope: `app/src/main/java/com/lyricsync/app/overlay/FloatingOverlayService.java` (only file edited).
Baseline context: other agents had already modified this file; my edits are layered on top
(current file = 4080 lines).

## Defect 1 — static reference to the Service/Context (FIXED)

**Problem.** The service held itself in a strong static field
(`private static volatile FloatingOverlayService activeInstance`) and exposed it via
`getInstance()`. Until `onDestroy()` nulls it, a static strong reference pins the Service
(a `Context`) and its whole view/manager graph. Nulling in `onDestroy` only narrows the window;
any early-return path (`stopSelf()` before overlay/permission checks) or a missed teardown keeps
the Context reachable. A second static path also existed: in `onCreate`, a static supplier in
`AntigravityToolExecutor` captured the service (through the `sharedPrefs` instance field).

**Fix.**
- `activeInstance` → `activeInstanceRef`, a `private static volatile WeakReference<FloatingOverlayService>`
  (line 115; added `import java.lang.ref.WeakReference;` at line 93).
- `getInstance()` (lines 121-125) now reads the reference and returns `ref.get()`, returning `null`
  when the reference is null or the referent has been collected. Public signature unchanged.
- `onCreate()` (line 362): `activeInstanceRef = new WeakReference<>(this);`.
- `onDestroy()` (lines 4037-4038): clears the reference only if it still points at this instance.
- Static supplier (lines 452-456): capture the `SharedPreferences` instance (`chatModelPrefs`) into
  a local and use it in the lambda instead of the service field, so `AntigravityToolExecutor`'s
  static supplier no longer retains the Service.

**NPE/GC safety.** `getInstance()` null-checks the reference and the referent. All callers
(`MainActivity` lines 2434, 2553) already null-check the returned service. The framework holds the
strong reference to a live Service, so GC cannot collect it while it is running.

## Defect 2 — `updatePlaybackState(...)` atomicity (NOT PRESENT IN THIS FILE)

**Finding.** `grep -n updatePlaybackState FloatingOverlayService.java` returns nothing. The method
lives only in `detection/MediaSessionTracker.java` (definitions/calls at lines 248, 296, 300):
`updatePlaybackState` is called from the `MediaController` callback (registered on the main
handler, line 193) **and** from `pollPosition()` on the `MediaSessionPoll` thread (line 296),
writing the volatile anchor fields (`currentState`, `currentPosition`, `lastPositionUpdateElapsed`,
`positionInitialized`, ...) without atomicity. That is outside my scope (another agent owns
`detection/**`; their `overlay-detection.md` already flags this as residual risk).

**Why nothing was changed here.** Everything this service receives from the tracker is already
marshalled onto the main thread by `MediaSessionTracker.mainHandler` (`Looper.getMainLooper()`,
lines 48, 238, 242, 252, 423), so the service's track/lyrics/retry state is written on a single
thread. Adding a lock here would be a no-op that violates the minimal-change rule. Recommendation:
serialize `MediaSessionTracker.updatePlaybackState` (dedicated lock or run the poll-thread update
via `mainHandler`) in that file.

## Defect 3 — uncancelled Glide `CustomTarget` (FIXED)

**Problem.** `updateTrackInfo()` created an anonymous `CustomTarget<Bitmap>` for URI album art with
no retained reference, so a late callback could run after teardown and touch `overlayCover`, and
each replacement leaked a live request.

**Fix.**
- Added fields (lines 266-268): `coverArtTarget` (the target) and `glideRequestManager` (its
  `RequestManager`, resolved once via `Glide.with(this)`).
- `updateTrackInfo()` (line 3273): calls `clearCoverArtTarget()` first, so any in-flight request is
  cleared when the target is replaced; the URI branch stores the created target and manager.
- New `clearCoverArtTarget()` (lines 3336-3345): drops the reference, then calls
  `RequestManager.clear(target)` (falling back to `Glide.with(this).clear(target)`), which is
  Glide's supported cancellation path — no hand-rolled cancel. Safe/repeatable and null-safe.
- `onDestroy()` (line 4036): calls `clearCoverArtTarget()` before `super.onDestroy()`.

Existing guards (`isDestroyed`, `pendingArtUri`) are unchanged, so behaviour for live loads is
identical; only cancellation of superseded/finished requests was added. No `onDestroy`-time
`CustomTarget` existed anywhere else in the file (only line ~3298).

## Verification

- Read every touched path and confirmed field/line changes above.
- Compile-checked (no Gradle) with javac against `android.jar` (API 35), the app's compiled
  `classes.jar`, `R.jar`, and all cached dependency jars:
  `javac -proc:none -d /tmp/fos_out -cp "$CP" FloatingOverlayService.java` → **exit 0**
  (only a pre-existing deprecation note). `javap` confirms
  `activeInstanceRef: WeakReference<FloatingOverlayService>`, `getInstance()` unchanged, and the
  new `coverArtTarget`/`glideRequestManager`/`clearCoverArtTarget` members.
- Did NOT run Gradle (per instructions).

## Residual risk

- Not device-tested. The WeakReference relies on the framework's strong reference to a running
  Service (standard Android behaviour); the Glide clear path uses public Glide 4.16 APIs verified
  present in the cached AAR (`RequestManager.clear(Target)`).
- Defect 2's real site (`MediaSessionTracker.updatePlaybackState`) is out of scope and left
  unfixed; hand off to the detection-file owner.
- `getInstance()` may now return null slightly earlier (GC or destroy) than the old
  null-in-onDestroy behaviour; callers already null-check, and `isRunning()` remains the
  coordination signal for MainActivity.

## Split candidates (not done — file left intact)

`FloatingOverlayService.java` (~4080 lines) mixes: window add/remove + drag/resize/persist;
transport controls; retry + AI retrieval state machine; lyrics render loop/highlight; AI chat +
bubble windows + history dialogs; playback tracking glue. Natural extractions:
`OverlayWindowController`, `OverlayAiChatController`, `LyricsFetchCoordinator`, `OverlayRenderer`.
