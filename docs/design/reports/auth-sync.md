# auth-sync: SpotifyAuthManager token-refresh concurrency

Scope: `app/src/main/java/com/lyricsync/app/lyrics/auth/SpotifyAuthManager.java` (only file changed).

## Defect
`getValidAccessTokenSync()` read the token/time prefs and, on a stale/absent token,
ran the `sp_dc` -> access-token exchange with no synchronization. Two provider worker
threads could both observe stale state and each perform the exchange (2x network, last
writer wins). `invalidateToken()` (called from `SpotifyColorLyricsProvider` on HTTP 401)
could interleave with an in-flight exchange, and the exchange's write could then
resurrect the token that invalidation had just removed.

Only call site of `getValidAccessTokenSync()` is `SpotifyColorLyricsProvider.fetchLyrics`
(worker/provider thread). `invalidateToken()` is called there on 401. `exchangeSpDcSync`
is also used by `loginWithSpDc` (own thread).

## Fix
Two private monitors plus a generation counter, added to the existing singleton:

- `refreshLock` — held for the whole `getValidAccessTokenSync()` body, including the
  network exchange. Concurrent caller B waits, then re-reads prefs under `stateLock` and
  returns the token A just persisted. One exchange per stale token; no lock held over
  unrelated work.
- `stateLock` — short critical section guarding the token/time prefs pair and
  `tokenGeneration`. Never held across the network call.
- `tokenGeneration` — bumped under `stateLock` by every explicit credential change
  (`invalidateToken`, `logout`, `loginWithRawToken`, `loginWithSpDc`). `exchangeSpDcSync`
  captures the generation before the exchange and `persistTokenIfCurrent(token, epoch)`
  writes only if it is still current. After a successful exchange the caller re-checks the
  generation and returns `null` (never the invalidated token) if it changed.

Refactor kept public signatures identical: `exchangeSpDcSync(String)` now delegates to a
private `exchangeSpDcSync(String cleanSpDc, long epoch)`; the prefs write was moved out of
`tryExchangeAtTime` into `persistTokenIfCurrent`. TTL/clock-drift logic
(`TOKEN_VALIDITY_MS`, `System.currentTimeMillis() - time`) is byte-for-byte unchanged.
`isAuthenticated()` is untouched.

## Concurrency argument
- Single exchange: `refreshLock` serializes all `getValidAccessTokenSync()` calls. The
  second caller either sees a fresh token in the fast path or, after waiting on the lock,
  sees the freshly persisted token; only the first performs the exchange. (The TOTP
  `-30/0/+30` fallback chain is inside that single refresh.)
- Invalidation cannot be undone: `invalidateToken()` bumps `tokenGeneration` and removes
  the prefs atomically under `stateLock`. An exchange's persist and this bump both take
  `stateLock`, so the check-and-write is atomic w.r.t. invalidation. Any interleaving ends
  with the token removed: bump-before-write -> write skipped; write-before-bump -> remove
  happens last.
- No invalidated token is resurfaced: the caller compares its captured epoch to the
  current one after the exchange and returns `null` if invalidation (or logout/login)
  intervened.
- No blocking of invalidation: `stateLock` is never held across the exchange, so a 401
  handler's `invalidateToken()` is not stuck behind a 10-30s refresh.
- Guarded prefs: token/time reads in the fast path, token/time writes in
  `persistTokenIfCurrent`, and the SP_DC/token writes in login/logout all run under
  `stateLock`, so the pair is never observed half-written.

## Verification
- Compile-checked the single file with `javac 17` against the cached `android.jar` (SDK 35),
  `gson-2.10`, `okhttp-4.12.0`, `okio-jvm-3.6.0`, kotlin-stdlib, and the project's
  `build/intermediates/javac/debug/classes` (for `AppLog`). Result: exit 0, clean.
- Per hard rules, did NOT run gradle; orchestrator's build is authoritative.
- Existing tests touching this area: `SpotifyColorLyricsProviderTest` (uses
  `generateTotp` + JSON parsing) and `AntigravityAuthManagerTest` (unrelated). No test
  covers `SpotifyAuthManager` concurrency, so behavior is unchanged for them.

## Residual risk
- `loginWithSpDc`'s exchange (`exchangeSpDcSync(String)`) does not take `refreshLock`, so a
  user-initiated login can still overlap one worker refresh. Its final write bumps the
  generation, so the login wins and an in-flight worker cannot overwrite it; at worst an
  extra exchange happens during a simultaneous login. Not a device-tested path.
- `invalidateToken()` blocks only on `stateLock` (brief), never on the network, but a
  worker that already captured a fresh token before invalidation may still use that token
  for its in-flight request (returns 401 -> next call refreshes). This matches the
  pre-existing "last resort" behavior and self-corrects.
- Thread-safety was reasoned and compile-checked, not exercised on a device; no
  deterministic concurrency test was added (would require a test seam on SharedPreferences
  and HTTP, out of scope for a surgical fix).
