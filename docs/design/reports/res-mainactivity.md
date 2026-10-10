# res-mainactivity — findings, fixes, residual risk

Scope: `app/src/main/res/**`, `MainActivity.java`, `LyricSyncApp.java`, `app/src/main/java/com/lyricsync/app/util/**`.
No gradle was run. Android XML validated with an XML parser; `MainActivity.java` parsed with `javac` (only expected "package android does not exist" symbol errors, zero syntax errors).

## Fixed — lint ERRORS
1. **StringFormatInvalid** `values/strings.xml:68,71` (`spotify_cookie_dialog_msg`, `spotify_connected_toast`): the literal `100%` was parsed as a format specifier. Added `formatted="false"` to both strings in `values/` **and** `values-in/` (English text unchanged). Scanned every `values*` string: only these two contain a bare `%`; all other `%` uses are real `%N$s`/`%N$d` specifiers.
2. **MissingTranslation** (7, error-level in this project): added Indonesian translations in `values-in/strings.xml` for `ai_chat_open_fullscreen`, `cd_expand_chat`, `cd_voice_input`, `ai_voice_no_result`, `ai_voice_error`, `ai_voice_permission`, `ai_voice_listening` (lines 149-156). Because `MissingTranslation` is an error here, every new default string added below also got a `values-in` entry; name-set and format-specifier parity between the two locales was verified programmatically (191 names each).

## Fixed — lint warnings
3. **UnusedResources (47)** — removed after verifying no `@type/name`, `R.type.name`, or `getIdentifier(...)` reference anywhere in `src` (main+test), manifest, proguard, themes:
   - strings (33, removed from **both** locales): `now_playing`, `overlay_size`, `logs_cleared`, `cd_open`, `ai_model_label`, `ai_effort_label`, `ai_web_search_label`, all 18 `ai_prompt_*`, `ai_model_gemini_pro_desc`, `ai_model_claude_desc`, `ai_new_chat_ready`, `romaji_enabled`, `romaji_disabled`, `ai_step_fetching_web`, `ai_step_syncing`, `ai_error_auth_required`.
   - colors: `transparent`, `spotify_green`, `background_elevated`, `overlay_bg`, `overlay_scrim`.
   - dimens: `icon_button`, `hero_height` (the drawable `bg_icon_button` is a different resource and is kept).
   - files deleted: `res/drawable/card_surface_bg.xml`, `res/layout/overlay_ai_sheet.xml`, `res/mipmap-anydpi-v26/ic_launcher_round.xml` (manifest only declares `android:icon`, never `roundIcon`).
   - **Deliberately kept** even though lint flagged them, because they are referenced dynamically by name and lint cannot see it:
     - `ai_source_web` (`CleanMarkdownFormatter.java:353` via `getIdentifier`),
     - `ic_bulb_info`, `ic_heart_mood`, `ic_person_artist` (`SongChatManager` prompt-template `iconName`s resolved via `getIdentifier` in `MainActivity.setupAiQuickChips` and `FloatingOverlayService`).
     Deleting those would have silently downgraded the prompt chips to the sparkles fallback.
4. **HardcodedText (17)** — now zero literals in `res/layout/**`.
   - Content descriptions moved to resources: `activity_main.xml` AI header icon → `@string/ai_studio_title`; `overlay_lyrics.xml` → `@string/ai_insights_title`; `overlay_ai_bubble.xml` → new `@string/cd_ai_chat_bubble`.
   - Numeric/brand placeholders that are **always overwritten in code** (`font_size_label`, `overlay_width_label`, `overlay_height_label`, `sync_offset_label`, `tv_quota_weekly`, `tv_quota_5h`, `ai_studio_model_pill_name`, `tv_dialog_search_model_sub`, `overlay_sync_offset_label`) were converted from `android:text` to `tools:text` (design-time only). Chose `tools:text` over new string resources on purpose: these values can never be shown (every one is set before the view is displayed — font-size/width/height/sync labels in `setup*Slider`, quota labels when `layout_ai_connected` is shown, model pills in `updateAiStudioModelUi`/`showModelSelectorDialog`), so string resources would add untranslatable bloat with no user-visible value. `xmlns:tools` added where needed.
   - `overlay_ai_sheet.xml` held 6 more HardcodedText warnings and is deleted (unused).
5. **SmallSp (15)** — left as-is on purpose. All are compact badges/captions (np_status, mood/quota badges, history meta, overlay labels, monospace log). 10sp→11sp risks wrapping in the badge rows and changing the intended compact layout, and the task allowed leaving them. Residual warning, not a defect.

## Fixed — real defects in MainActivity
6. **Wrong-fallback bug: Spotify cookie vs access token misclassification** (`MainActivity.java:739`). Old:
   `value.startsWith("BQA") || value.startsWith("BQC") || (value.length() > 100 && !value.contains("="))`.
   A real `sp_dc` cookie is ~211 chars and contains no `=` (verified against the bundled `FALLBACK_SP_DC`), so the documented "paste sp_dc cookie" flow was routed to `loginWithRawToken`, stored the cookie as a Bearer token, and reported success. Now only genuine Spotify access tokens (`BQ…`) take the raw-token path; everything else is validated through `loginWithSpDc`. (Note: this dialog is currently latent because of the out-of-scope bug in item 10.)
7. **Dead code removed** (unreachable / no callers; verified by whole-repo search):
   - `fetchSongMeaning(...)` — private, zero callers (regression from the 0ca7d74 AI-chat redesign, which dropped its two call sites) — plus its now-empty plumbing `songMeaningService` field/creation and `activeMeaningCall` field and the three cancel blocks that could only ever be null.
   - `performTransliteration()` no-arg overload (the used one is the 2-arg version; `FloatingOverlayService` has its own separate method).
   - `onNowPlayingChanged(NowPlaying track)` overload — unreachable: `NowPlaying` has a private constructor, so the parameter type can never be instantiated; it only forwarded to the `Snapshot` overload.
   - unused import `android.text.method.LinkMovementMethod`.
8. **i18n: hard-coded Indonesian text ignored the app language setting.** The app explicitly supports English/Indonesian (`LocaleHelper`, `card_language`) and defaults to English, yet dialogs/toasts always spoke Indonesian. Replaced the user-facing literals with resources (10 new strings in both locales, reusing existing `save`/`cancel`): `settings_open_failed`, `spotify_validating_session`, `google_signin_opening/success/failed`, `google_signed_out`, `google_connected`, `google_connect_failed`, `ai_model_set`, `transliteration_failed`. English users now get English; Indonesian output is unchanged.

## Reported, NOT fixed (outside my scope / needs a decision)
9. **`SpotifyAuthManager.isAuthenticated()` always returns `true`** (`lyrics/auth/SpotifyAuthManager.java`). Consequence in my scope: `updateSpotifyAccountState()` always shows the "connected" layout and hides `layout_spotify_disconnected`, so `btn_connect_spotify` and `showSpotifyCookieDialog()` are currently unreachable — the connect-Spotify UI is effectively dead. Owned by the `auth` agent.
10. **Remaining hard-coded Indonesian in `MainActivity` LyricActionDelegate results** (lines ~2339-2593: "Tidak ada lirik aktif", "Berhasil mengambil…", "Fitur tidak dikenal…", "Tampilan overlay berhasil diperbarui!", "Baris … berhasil diperbarui.", etc.). These are AI tool-result strings that also reach the user. Left untouched because it is a larger batch and the AI tool-result channel may be intentionally Indonesian; flagged for a follow-up.
11. **`SetTextI18n` (24, warning only)**: e.g. `label.setText(saved + "%")` at MainActivity:516-552, `"AI Quota: " + h5Pct + "%"` at :863, `" · "` track-info join at :2164. Numbers/units, no user-visible defect. Left alone to avoid churn.
12. **NestedScrolling (activity_main.xml log ScrollView inside the page ScrollView)** — real usability smell but fixing it means restructuring the dev log panel; not a defect worth a risky rewrite now.
13. `LyricSyncApp` notification channel name/description are hard-coded English; channels are not re-localized on language change anyway, so low value.

## Files changed
- `app/src/main/java/com/lyricsync/app/MainActivity.java`
- `app/src/main/res/values/strings.xml`, `values/colors.xml`, `values/dimens.xml`
- `app/src/main/res/values-in/strings.xml`
- `app/src/main/res/layout/activity_main.xml`, `overlay_lyrics.xml`, `overlay_ai_bubble.xml`, `dialog_ai_chat_fullscreen.xml`, `dialog_model_selector.xml`
- deleted: `res/drawable/card_surface_bg.xml`, `res/layout/overlay_ai_sheet.xml`, `res/mipmap-anydpi-v26/ic_launcher_round.xml`
- `LyricSyncApp.java` and `util/**`: reviewed, no change needed (no concrete defect found).

## Residual risk
- Resource deletions were verified by name search, not by a lint run (no gradle allowed). If any resource is consumed only via reflection outside the searched trees, it would fail at runtime; none found.
- `tools:text` relies on every placeholder being overwritten before display; each path was traced, so no blank-label regression is expected.
- The SpotifyAuthManager `isAuthenticated()==true` bug means my item-6 fix is currently latent.

## File that should be split later
- `MainActivity.java` (~3.2k lines): owns activity wiring + app settings + Spotify card + full AI Song Studio/chat/voice + history dialogs + model selector. Natural split: an `AiStudioController`/`AiChatView` for the chat/voice/history block and an `AppearanceSettingsController` for sliders/chips. Not done here (out of scope).
