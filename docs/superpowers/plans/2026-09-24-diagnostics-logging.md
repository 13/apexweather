# Diagnostics: crash capture and a local log — plan

Date: 2026-09-24. Status: built in v0.34.0 (see `diagnostics/` and CLAUDE.md). Exporting is a Settings row; ANR capture is verified only as far as the code path — no ANR was forced on the phone.

## Why

The app has no way to explain a failure after the fact. Today it has four `Log` calls
(`WeatherRepository`, `NowcastRepository`), no uncaught-exception handler and no crash service. It is
a sideloaded release build, so `run-as` is refused and the cache database cannot be read over adb
(found again on 2026-09-24). logcat is gone within minutes on a phone. Every past failure worth
knowing about — AROME answering 400 for months, R8 breaking jhdf, the v0.29.0 APK that could not
start — was found by chance or by reading the screen.

## Decisions

- **Local only, sent by hand.** No Crashlytics, Sentry or ACRA backend: the app has no
  account, no server and no analytics, and a crash report carries the chosen place and the stations.
  Nothing leaves the phone until the reader shares it from Settings. ACRA was considered. It can
  do this too, but it is a dependency with its own R8 rules for about 200 lines of our own.
- **Files in `filesDir/diagnostics/`, not cache**, because the system clears the cache under storage
  pressure, which is exactly when you want the log.
- **Bounded**: log 2 × 256 kB rotating, the last 10 crash files, the last 20 exit records. Under 1 MB.
- **Never log the WU key.** Every URL goes through one `redact()` that drops `apiKey=` and friends.
  Coordinates of a place are fine (they are in the public catalogue).

## Tasks

### 1. `diagnostics/AppLog` — the log

Self-contained package, like `update/` and `notify/`.

- `AppLog.i/w/e(tag, message, throwable?)`, also forwarded to `android.util.Log`.
- One single-thread executor writes lines (`2026-09-24T09:31:02.123+02:00 W Refresh: …`); nothing
  touches disk on the caller's thread. Rotate at 256 kB to `log.1.txt`.
- `flushBlocking()` for the crash path.
- Replace the four existing `Log` calls.
- Test: rotation at the boundary, two files max, redaction of `apiKey=…` in a URL.

### 2. Uncaught exceptions

In `ApexApplication.onCreate`, first line, before Hilt-injected work runs:

- `Thread.setDefaultUncaughtExceptionHandler` that writes `crash-<epochMs>.txt` **synchronously**
  (version name/code, build type, Android version, device model, thread, time, full cause chain,
  the last 50 log lines), prunes to 10, then **chains to the previous handler** so the system
  still shows its dialog and kills the process.
- Coroutines: a `CoroutineExceptionHandler` on the app-scoped scopes (`WeatherStateHolder`,
  `StaleRefresher`, `ForegroundRefreshLoop`) that logs through `AppLog.e`. `viewModelScope`
  failures already reach the thread handler.
- Test (Robolectric): handler writes the file, prunes, and calls the previous handler.

### 3. What the system saw: `ApplicationExitInfo`

minSdk is 31, so `ActivityManager.getHistoricalProcessExitReasons` is always there. It is the
only way to learn about **ANRs** (with the main thread's stack, via `traceInputStream`), native
crashes, and low-memory kills. The last two never reach an exception handler.

- On each start, read the records newer than the last one seen (timestamp in `SharedPreferences`),
  write each as `exit-<ts>.txt` (reason, description, importance, PSS/RSS, and the ANR trace
  truncated to 64 kB), prune to 20.
- Keep the formatting in a pure function over plain fields so it is testable without a device.

### 4. What to log

Only events, nothing per frame:

- Refresh start/end, per source: status, HTTP code, duration, bytes, and the retry.
- Worker runs, and the result of `pinsToRefresh`.
- Stale → failed transitions (the thing `silentSources` shows).
- Nowcast read failures (already logged; move to `AppLog`).
- Updater: download, checksum or length mismatch, handing over to the installer.
- A station chosen, the key verdict changing, the place changing.
- Notifications decided and posted.

### 5. Export from Settings

- A row under **App**: "Diagnosedaten teilen" / "Condividi dati diagnostici" / "Share diagnostics",
  added to all three `values*`.
- It zips `diagnostics/` plus a header (version, device, settings **without** the key) into the
  existing shares cache directory, and uses the existing `FileProvider` and a chooser. The same
  pruning as `ShareCapture` applies.
- A small line under it: "3 Abstürze seit 20.09." when there are any. Say nothing when there are none.
- Test: the zip contains the expected entries and no key.

### 6. Readable stack traces from release builds

R8 obfuscates release stack traces. `release.yml` should upload
`app/build/outputs/mapping/release/mapping.txt` as a release asset beside the APK, so a crash
file from v0.x can be run through `retrace` against that version's mapping. Without this, step 2
produces traces nobody can read.

### 7. Verification

- Unit tests above; lint clean.
- `tools/release-smoke.sh` on the emulator: after the launch, `run-as` is unavailable, so add a
  debug-only intent or check that the export row exists. Crash on purpose once in a debug build
  (hidden developer action) and confirm the file appears and the next start does not crash.
- On the phone: force an ANR in a debug build (`Thread.sleep` on main for 10 s) and confirm the
  exit record carries its trace.
- CLAUDE.md: a paragraph under Architecture, and the tag list.

## Out of scope

- Automatic upload of any kind.
- Performance tracing (use Perfetto on demand).
- Logging model values. `station_history` already keeps what matters there.
