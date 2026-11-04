# LyricSync

Synced lyrics floating over any music app on Android, highlighted word by word.

LyricSync reads the song from the player's media notification (Spotify, YouTube Music and others), finds its lyrics and shows them in a movable overlay or full screen.

## Features

- Word and syllable highlighting, with romaji, pinyin or romaja for non-Latin lyrics
- Translation under each line (Google Translate or AI)
- Motion and effect styles you can mix, plus a Combo Lab
- Playback controls, seek bar and tap-a-line-to-jump
- Lyra, an AI assistant that explains lyrics and can edit, re-time or translate them for you
- English and Bahasa Indonesia

Requires Android 8.0 or later.

## Install

1. Download the APK from [Releases](../../releases) and install it.
2. Grant **Notification Access** and **Display Over Apps**.
3. Tap **Start Overlay** and play a song.

## Lyrics key (recommended)

Word-by-word lyrics from Spicy Lyrics need a free key:

1. Open [developers.spicylyrics.org](https://developers.spicylyrics.org/) and sign in.
2. Create an app and copy its publishable key (`sl_pk_…`).
3. In LyricSync, open **Syllable Lyrics API → Set key** and paste it.

Without a key, lyrics still come from Better Lyrics, LyricsPlus, Kugou, Netease and LRCLIB.

## AI (optional)

Lyra and AI translation need one AI provider, set in the app:

- **Google Antigravity**: sign in with Google.
- **ChatGPT (Codex)**: sign in with your ChatGPT account.
- **Any OpenAI-compatible API**: base URL and API key.

Web search for Lyra uses an [Exa](https://dashboard.exa.ai) API key.

Keys stay on your device. None are included in the app or this repository.

## Build

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

To build in a Spicy Lyrics key, put `spicy.client.key=sl_pk_…` in `local.properties`, or set the `SPICY_CLIENT_KEY` environment variable. On GitHub Actions, add it as a repository secret.

GitHub Actions tests and builds every push. A `v*` tag publishes a release APK.

## License

[GPL-3.0](LICENSE). LyricSync is not affiliated with Spotify or with any lyrics provider.
