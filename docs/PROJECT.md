# LyricSync — project map

Floating synced lyrics over any player that posts a media notification.

## Build & verify
```
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # minified release (R8)
./gradlew testDebugUnitTest      # 337 unit tests
./gradlew lintDebug              # must stay at 0 errors
```
Toolchain: JDK 17, Android SDK 35 (`platforms;android-35`, `build-tools;35.0.0`).
`local.properties` / `ANDROID_HOME` must point at the SDK.

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
| `ui/*` | Small view helpers (chips, palettes) |
| `MainActivity` | Settings screen |

## Provider order
`SpicyLyrics → Kugou → Netease → Spotify(Color) → LRCLIB`
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

## Conventions
- Log through `util/AppLog` (never `System.out`).
- A provider implements `LyricsProvider` (`getName`, `fetchLyrics`) and throws `IOException`
  with a clear message on failure — the manager treats that as "try the next provider".
- Parsing/ranking helpers that need a unit test are **package-private static** and take a
  raw string/JSON so tests run offline (see `app/src/test/resources/*.json`).
- Network uses OkHttp with tight timeouts; nothing blocks the main thread.
- `Locale.ROOT` for any case folding / cache key.

## Known split candidates (deliberately not done yet)
- `overlay/FloatingOverlayService.java` (~4.0k lines) — split window / gestures / chat / playback
- `MainActivity.java` (~3.0k lines) — split settings sections out
- `ai/client/AntigravityApiClient.java`, `ai/tools/AntigravityToolExecutor.java`
- `renderer/GradientWordView.java`, `renderer/SyllableHighlighter.java`

## Test fixtures
- `app/src/test/resources/spicy_v1_syllable.json` — captured `/v1/lyrics` response
- `app/src/test/resources/spotify_search_apt.json`, `spotify_search_suzume.json` — captured `searchDesktop` responses
