# MediaSessionTracker anchor synchronization fix

File: `app/src/main/java/com/lyricsync/app/detection/MediaSessionTracker.java`
Scope: this file only. Concurrency fix; no behavior/threshold/cadence change.

## Defect
The playback anchor is a group of fields describing ONE playback sample. They were
written by two threads with no atomicity:
- `updateFromController(...)` / `updatePlaybackState(...)` run on the MAIN thread
  (the `MediaController.Callback` is registered with `mainHandler`).
- `pollPosition()` -> `updatePlaybackState(...)` runs on the 16ms POLL thread.
- The track-change reset in `updateFromController` and the anchor trio in `seekTo`
  also wrote the group directly.

Readers read the fields separately (`projectedPosition` + `getCurrentPosition`, and
`isPlaying`), so they could observe a torn mix, e.g. a new `currentPosition` with an
old `lastPositionUpdateElapsed`, corrupting the interpolation. A related genuine bug:
`updateFromController` posted a lambda that read the `currentState` **field** on the
main handler later, i.e. an off-lock read outside the sample it was reporting.

## Anchor group (exact fields)
- `currentState`
- `currentPosition`
- `lastPositionUpdateElapsed`
- `positionInitialized`
- `playbackSpeed`

(`trackDurationMs`, `currentTrack`, `currentController`, `syncOffsetMs` are NOT part
of this group; they stay `volatile`, read independently. No over-engineering.)

## Fix
One lock: `private final Object anchorLock = new Object();`

Every read/write of the anchor group is now inside `synchronized (anchorLock)`:
- L316 `updatePlaybackState(...)` - whole method (all 5 fields).
- L461 `projectedPosition(long)` - reads position/state/update-time/speed (+ duration clamp).
- L448 `getCurrentPosition()` - wraps projection + `syncOffsetMs` subtraction.
- L568 `isPlaying()` - reads `currentState`.
- L492 `seekTo(...)` - the anchor trio only; the binder `getTransportControls().seekTo(...)`
  call stays OUTSIDE the lock (no blocking call under the monitor).
- L239 track-change reset (`currentPosition=0; lastPositionUpdateElapsed=...;
  positionInitialized=false;`) - atomic; `AppLog` stays outside.
- L262 `updateFromController` callback snapshot - `currentState` + `getCurrentPosition()`
  captured together under the lock and captured by the posted lambda as locals
  (previously the lambda read the `currentState` field off-thread).
- L427 `clearCurrentTrack()` - state/position/update-time/init/duration reset as a group.
- L510 `togglePlayPause()` - `currentState` snapshotted under the lock for the play/pause decision.

Lock scope is tiny: no callbacks, no logging, no blocking/binder calls inside.
Monitors are reentrant, so `updatePlaybackState`/`getCurrentPosition` calling the
synchronized `projectedPosition` while holding the lock is safe. Public signatures,
poll cadence, thresholds, arithmetic and callback threading are unchanged.

## Verification
1. Automated scan of the final file (comments/strings stripped, brace scopes tagged as
   `synchronized (anchorLock)`): all 44 occurrences of the 5 anchor fields are
   under the lock except the 5 field declarations at L59-L63 (declarations, not accesses).
   No unprotected access remains.
2. All lambdas re-checked: none captures an anchor field; the only callback lambda now
   captures `callbackState`/`callbackPosition` locals.
3. Brace/paren balance: 104/104, 268/268.
4. No gradle. Offline `javac -source 17 -target 17` against android.jar + the project
   sourcepath: exit 0 (clean).

## Residual risk
- Low. The lock is a single hot monitor acquired ~every 16ms on the poll thread and
  briefly on the main thread; sections are pure field reads/writes and `SystemClock`
  calls, so contention is negligible.
- `trackDurationMs` remains volatile and is written outside `anchorLock` (in
  `updateFromController`); `projectedPosition` reads it under the lock. A duration
  change can be observed one sample late. This matches the requested scope (duration is
  not part of the anchor group) and preserves behavior.
- `syncOffsetMs` unchanged (volatile, read independently, per instruction).
- Behavior note: the playback callback now reports the state/position sampled at post
  time rather than whenever the lambda runs. This is the intended `ONE sample` pairing
  and removes the off-lock read; it does not change any threshold or arithmetic.
