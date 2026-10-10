# Settings UI polish: Spotify & Syllable Sync card

## Files changed
- app/src/main/res/layout/activity_main.xml
- app/src/main/res/values/strings.xml
- app/src/main/res/values-in/strings.xml
- app/src/main/java/com/lyricsync/app/MainActivity.java (only `showSpicyKeyDialog()`)

## What changed

### Layout (`card_spotify` only)
- Added a small section subtitle "Spotify session" (`@string/spotify_section_session`) above the connect/disconnect states, styled inline like the existing card labels: `@color/text_secondary`, `13sp`, bold, `marginBottom=8dp`.
- Added a subtle 1dp divider (`@color/divider`) between the Spotify-session block and the Spicy-key block, `marginTop/Bottom=16dp` (matches the spacing scale used elsewhere in the file).
- The Spicy-key heading is now a section subtitle (same `text_secondary`/13sp/bold style) instead of a full `SectionTitle`, so the card keeps one top-level title and two sub-sections.
- Unified buttons:
  - `btn_connect_spotify`: `backgroundTint` `#1DB954` -> `@color/accent`, text/icon tint `@color/white` -> `@color/black` (matches `btn_connect_google` in the sibling AI card); kept 50dp height, 14sp bold, `cornerRadius 14dp`.
  - `btn_spicy_key`: kept `OutlinedButton`, height raised 46dp -> 50dp and added `app:cornerRadius="14dp"` to match the filled buttons; 14sp bold.
- Connected status colour `#1DB954` -> `@color/state_ok` (no hardcoded colour left in the card).
- All existing IDs preserved: `card_spotify`, `layout_spotify_disconnected`, `layout_spotify_connected`, `btn_connect_spotify`, `btn_disconnect_spotify`, `btn_spicy_key`, `tv_spicy_key_status`.

### Strings (en + values-in, kept short and in sync)
- `spotify_account_desc`: rewritten short (dropped the "100% stable word-by-word rate ... sp_dc cookie" run-on).
- `spotify_connect_button`: "Connect Spotify Session (sp_dc)" -> "Connect Spotify" / "Hubungkan Spotify".
- `spotify_connected_status`: "Connected · 100% Syllable Rate Active" -> "Connected · full syllable rate" / "Terhubung · rate suku kata penuh".
- `spotify_disconnected_status`: "Anonymous Mode (Prone to SpicyLyrics 500 rate limits)" -> "Not connected" / "Belum terhubung".
- `spicy_key_title`: "Spicy Lyrics API Key" -> "Spicy Lyrics key" / "Kunci Spicy Lyrics".
- `spicy_key_desc`: shortened to one line pointing at developers.spicylyrics.org.
- `spicy_key_button`: "Set Spicy Lyrics Key (sl_pk_…)" -> "Set key" / "Atur kunci".
- `spicy_key_status_set` / `spicy_key_status_none`: shortened ("Key saved · official API" / "No key set"; "Kunci tersimpan · API resmi" / "Belum ada kunci").
- New keys (both locales): `spotify_section_session`, `spicy_key_save` ("Save" / "Simpan").

### MainActivity
- `showSpicyKeyDialog()`: positive button now uses the new `R.string.spicy_key_save` instead of the Spotify-shared `R.string.spotify_cookie_save` ("Verify & Save"). No other logic touched. The Spotify cookie dialog still uses `spotify_cookie_save`.

## Assumptions / notes
- `values-in` was already missing several unrelated strings (`ai_voice_*`, `cd_*`); those were left untouched.
- No gradle build was run (orchestrator builds). Both `strings.xml` files and `activity_main.xml` parse as valid XML; all `@string` refs in the layout resolve to existing keys.
- No new colours, dimens, or styles were introduced; only existing tokens (`@color/accent`, `@color/text_secondary`, `@color/state_ok`, `@color/text_dim`, `@color/divider`, `@style/TextAppearance.LyricSync.*`) were reused.
- Behavior preserved: connect opens the existing dialog, Spicy dialog validates/saves `sl_pk_...`/`sl_sk_...` keys and `updateSpicyKeyState()` still drives `tv_spicy_key_status`.
