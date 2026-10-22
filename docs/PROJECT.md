# LyricSync — project map

Floating synced lyrics over any player that posts a media notification.

## Build & verify
```
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # minified release (R8)
./gradlew testDebugUnitTest      # all JVM unit tests
./gradlew lintDebug              # must stay at 0 errors / ~73 warnings
```
Toolchain: JDK 17, Android SDK 35 (`platforms;android-35`, `build-tools;35.0.0`).
`local.properties` / `ANDROID_HOME` must point at the SDK.

### Building on this device (aarch64)
Google ships build-tools for x86-64 only, so the binaries in `build-tools/35.0.0/`
(`aapt2`, `zipalign`, `aapt`, `aidl`, `dexdump`, `split-select`) were replaced with the
aarch64 static builds from [lzhiyong/android-sdk-tools](https://github.com/lzhiyong/android-sdk-tools).
AGP must be told via user-level `~/.gradle/gradle.properties` (repo/CI stay untouched):
`android.aapt2FromMavenOverride=/opt/android-sdk/build-tools/35.0.0/aapt2`.
`buildToolsVersion '35.0.0'` is pinned in `app/build.gradle` so AGP doesn't silently
download x86-64 `34.0.0`. The aarch64 `aapt2` prints a harmless
`__bionic_open_tzdata` warning on every resource task.

## Where things live (`app/src/main/java/com/lyricsync/app`)
| Area | Responsibility |
|---|---|
| `detection/MediaSessionTracker` | Reads what is playing from the media notification |
| `lyrics/LyricsProviderManager` | Provider cascade + in-flight de-dup + cache lookup |
| `lyrics/provider/*` | One class per lyrics source |
| `lyrics/model/*` | `LyricsData`, `TrackInfo`, `SyllableLyricsParser` |
| `lyrics/cache/LyricsCacheManager` | On-disk cache |
| `renderer/*` | Syllable highlight, gradient text, flow layout, animations |
| `overlay/FloatingOverlayService` | The window: layout, gestures, controls, AI sheet |
| `ai/*` | Antigravity (Gemini) chat, translation, transliteration, tools |
| — scope | The AI **does not** search for lyrics; the provider chain alone finds/loads them. The AI translates, transliterates, edits lines, drives playback/appearance, explains the song. Keep it that way when adding tools. |
| `ui/*` | Small view helpers (chips, palettes) |
| `MainActivity` | Settings screen |

## Provider order
`SpicyLyrics → Kugou → Netease → LRCLIB`
The manager stops as soon as a **SYLLABLE** result lands; everything after is a fallback.

## Key flows
1. **Now playing** → `MediaSessionTracker` → `LyricsProviderManager.fetchLyrics`
2. **Track id** → `SpotifyTrackResolver.resolve(title, artist, durationMs)`
   - Spotify pathfinder `searchDesktop` (anonymous token from the embed page) — primary
   - Deezer → ISRC → MusicBrainz — fallback
   - kana → romaji via `util/JapaneseRomanizer` so `すずめ` matches `Suzume`
   - in-memory LRU so a track resolves once
3. **Lyrics** → `SpicyLyricsProvider` calls `GET https://api.spicylyrics.org/v1/lyrics/{id}`
   - `Authorization: Bearer sl_pk_…`; a key pasted in Settings overrides the built-in one
   - response `{Status, Body}`; `Body` is parsed by `parseOfficialResponse` (Syllable/Line/Static)
4. **Render** → `LyricsData` → `overlay` → `renderer`

### Cover-version reality (live-verified 2026-10-22)
For cover releases (e.g. "Braaten - Without You", `4L065RwyTOcNXKejPQaX98`):
- **SpicyLyrics** → 404: the cover release has no lyrics; only the original recording
  maps to syllables.
- **Kugou** → deterministic miss: every query permutation carries the cleaned artist,
  and krcs indexes the original artist only ("Avicii - Without You" hits; anything with
  "Braaten" → `candidates:[]`; a bare title can also be empty). Not a code bug.
- **Netease** → finds the cover entry, but it has `lrc` (LINE) only, no `yrc`; the
  original's `yrc` syllables are gated out by the artist match, and the title-only
  fallback only fires on an *empty* combined result. Transient empty searches happen;
  the cache then serves the earlier LINE fetch (by design).
- **LRCLIB** → 503s are transient.

## Conventions
- Log through `util/AppLog` (never `System.out`).
- A provider implements `LyricsProvider` (`getName`, `fetchLyrics`) and throws `IOException`
  with a clear message on failure — the manager treats that as "try the next provider".
- Parsing/ranking helpers that need a unit test are **package-private static** and take a
  raw string/JSON so tests run offline (see `app/src/test/resources/*.json`).
- Network uses OkHttp with tight timeouts; nothing blocks the main thread.
- `Locale.ROOT` for any case folding / cache key.
- An AI-tool host implements `AntigravityApiClient.LyricActionDelegate` and wires itself
  with `setLyricActionDelegate(new MainThreadLyricActionRouter(handler, this))` — the
  router is only the main-thread hop, so no host hand-rolls the delegate.

## Known split candidates (deliberately not done yet)
- `overlay/FloatingOverlayService.java` (~3.5k lines) — split window / gestures / chat / playback
- `MainActivity.java` (~1.5k lines) — split settings sections out
- `ai/client/AntigravityApiClient.java`, `ai/tools/AntigravityToolExecutor.java`
- `renderer/GradientWordView.java`, `renderer/SyllableHighlighter.java`

## Test fixtures
- `app/src/test/resources/spicy_v1_syllable.json` — captured `/v1/lyrics` response
- `app/src/test/resources/spotify_search_apt.json`, `spotify_search_suzume.json` — captured `searchDesktop` responses
