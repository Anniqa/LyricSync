# Auth scope report

Scope: `app/src/main/java/com/lyricsync/app/lyrics/auth/**` and
`app/src/main/java/com/lyricsync/app/ai/auth/**`.
Only the three files in that scope were touched.

## Findings

### SpotifyAuthManager.java
1. `isAuthenticated()` returned `true` unconditionally (was line 109). The Spotify
   "connected / not connected" UI in `MainActivity.updateSpotifyAccountState()` can
   therefore never show "not connected", and `SpotifyColorLyricsProvider` always
   passed its auth gate. **Fixed.**
2. `getEffectiveSpDc()` returned the baked-in `FALLBACK_SP_DC` cookie when the user
   had not saved one. That cookie is dead (the token exchange answers HTTP 401), so
   every unauthenticated refresh spent three round-trips on a guaranteed 401 before
   falling back to the anonymous token. It also kept a long-lived shared credential
   in source. **Fixed** (returns the saved cookie or `null`; constant removed).
3. Dead code: `buildTokenUrl()` was never called; `secretCacheExpiry` was written
   nowhere and read nowhere; imports `java.io.IOException`, `okhttp3.Call`,
   `okhttp3.Callback` were unused; `FALLBACK_SP_DC` was only self-referenced.
   **Removed.**

### OAuthLoopbackServer.java
4. `start()` leaked an unbound `ServerSocket` when `bind()` failed: the socket was
   created, never closed, and left in the `serverSocket` field. **Fixed** (close it
   before reporting the error).
5. Restart race on the same instance: the accept thread read the mutable
   `serverSocket` field and its `finally` called `stop()`, which acted on the field.
   After `stop()` + `start()` on one instance, the old thread's shutdown could close
   the *new* listener (or leave `running` wrong). **Fixed**: the thread captures its
   own socket, and `closeServerSocket(socket)` only mutates instance state when the
   socket it closes is still the current one.
6. The callback error page was `SUCCESS_HTML` ("Login Berhasil") even for OAuth
   errors (`error=access_denied`) and missing codes. The browser told the user login
   succeeded while the app got an error. **Fixed**: a small `ERROR_HTML` is served
   when no authorization code is present.
7. Dead field `serverThread` (assigned but never read) removed.

### AntigravityAuthManager.java
8. `loopbackServer` was read/written under two different monitors: `startBrowserLogin()`
   holds `this`; `logout()` touched it while holding `refreshLock`. A login racing a
   logout could strand a bound loopback port (subsequent login fails with
   "Address already in use"). **Fixed**: `logout()` now touches the field under
   `this` too. Lock order is unchanged (`refreshLock -> this`, already used by
   `executeRefreshTokenRequest`), so no deadlock is introduced.

## Files changed
- `app/src/main/java/com/lyricsync/app/lyrics/auth/SpotifyAuthManager.java`
- `app/src/main/java/com/lyricsync/app/ai/auth/OAuthLoopbackServer.java`
- `app/src/main/java/com/lyricsync/app/ai/auth/AntigravityAuthManager.java`

## Validation
Gradle was not run (orchestrator owns the build). I compiled the three files with
`javac` against the Android SDK + okhttp/gson jars from the Gradle cache (clean), and
compiled and ran the two in-scope test classes directly with JUnit:
`OAuthLoopbackServerTest` (7/7) and `AntigravityAuthManagerTest` (14/14) both pass.
A small restart probe (stop + start on one instance) also passes.

## Residual risk / notes
- After fixing `isAuthenticated()`, `SpotifyColorLyricsProvider` (out of scope, line
  49) now throws for users who never connected, so the anonymous web-player token in
  `getValidAccessTokenSync()` is only reachable as a fallback after a real session's
  token is rejected. If anonymous color lyrics are still wanted for logged-out users,
  the gate in that provider needs revisiting; I did not edit it.
- `fetchAnonymousToken()`/`tryFetchToken()` persist the anonymous token into
  `PREF_ACCESS_TOKEN`. It is currently only reached after the auth gate, so it does
  not flip the fixed `isAuthenticated()`, but the coupling is latent.
- The silent-refresh path is guarded against logout resuscitation; the
  authorization-code and manual-refresh paths are not. A logout while
  `exchangeCodeForTokens`/`loginWithRefreshToken` is in flight can still re-persist
  credentials. Fixing it needs an auth generation/epoch; I left it rather than guess.
- `getValidAccessTokenSync()` is unsynchronized; concurrent callers can stampede
  token exchanges. Current callers are single-task, so no fix applied.
- `getEffectiveSpDc()` changing from a cookie to `null` is a behavior change, but it
  only affects the internal refresh path (no other callers); the fallback was dead.

## Candidates for later splitting (not done: out of scope)
- `AntigravityAuthManager.java` (~690 lines) mixes token exchange, silent refresh,
  browser OAuth flow, and manual login; a `TokenStore` + `OAuthFlow` split would help.
- `OAuthLoopbackServer.java` now embeds two large HTML documents; move them to
  assets/resources.
- `SpotifyAuthManager.java` mixes cookie parsing, TOTP, token exchange, and login.
