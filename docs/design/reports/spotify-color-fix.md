# spotify-color-fix

## Defect
`SpotifyColorLyricsProvider.parseColorLyricsJson` ignored `lyrics.syncType` and the
difference between a missing timestamp and a genuine `0`. An UNSYNCED color-lyrics
payload (Spotify omits per-line timings) parsed every line to `startTimeMs=0` +
estimated 3500 ms end, then labelled the result `LyricsData.Type.LINE`. Badly timed
LINE lyrics are worse than honest STATIC ones.

## Fix (app/src/main/java/.../SpotifyColorLyricsProvider.java)
- `parseLongSafe` now returns `-1` for an absent/unreadable key, so a genuine `0 ms`
  is no longer indistinguishable from a missing timestamp.
- Chose the payload class up front: `staticGrid = isUnsyncedSyncType(syncType) ||
  !hasRealStart` (a line with a real `>=0` start counts as timed).
- UNSYNCED / untimed payloads emit a STATIC grid: line *i* gets
  `startTimeMs = i * 3500`, `endTimeMs = start + 3500`, `data.type = STATIC`.
- Synced payloads with individual untimed lines infer the missing start from timed
  neighbours (leading untimed lands strictly between 0 and the first real start;
  trailing untimed advances from its neighbour by the document's median line gap).
  A genuine `0` stays `0`.
- Type selection: SYLLABLE when syllables exist, else STATIC when unsynced/untimed,
  else LINE. Syllable parsing and SYLLABLE type are unchanged.

## Verification
- `javac` (JDK17) against cached gson/okhttp/android/junit jars: compiles, 0 errors.
- Ran the test class directly on the JVM with JUnitCore (no gradle): `OK (12 tests)`
  — the 4 pre-existing tests plus 8 new ones, all offline.
- New fixtures under `app/src/test/resources/colorlyrics/` (schema-faithful, with
  `startTimeMs` as a STRING): fully synced, UNSYNCED (all `"0"`), syncType synced but
  no real timestamps, synced-with-missing-middle-line, leading untimed, trailing
  untimed, genuine zero, and syllables. Tests assert type + start/end times.

## Assumptions / residual risk
- Deliberate choice of `>= 0` (not `> 0`) for "real timestamp", so a lone genuine
  0 ms keeps LINE; per the task's explicit wording. `/tmp/spicy-ex`'s `anyTimed`
  uses `> 0`; the only divergence is a payload whose sole timestamp is 0.
- `syncType` is matched case-insensitively for the substring `UNSYNCED`; an unknown
  syncType falls back to timestamp detection (LINE when a real time exists).
- Color-lyrics path is untested on a live token; classification beyond syncType
  relies on fixture shape. `parseColorLyricsJson` stays package-private.
