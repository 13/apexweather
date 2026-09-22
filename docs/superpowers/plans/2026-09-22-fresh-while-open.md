# Fresh While Open — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** While the app is open, each source refreshes as often as it actually changes — the station every 10 minutes, everything else every 30 — and when refreshing has been failing, the screen says so on the line that claims how fresh the data is.

**Architecture:** A foreground loop in the app-scoped `StaleRefresher`, driven by a pure `RefreshDue` rule; a station-only `WeatherRepository.refreshObservation(place)` that goes through the existing mutex; an OkHttp disk cache so a 10-minute poll of SIAG costs a `304`; and one honest line on the home screen.

**Tech Stack:** Kotlin, coroutines, Hilt, OkHttp 5, Room, Jetpack Compose, JUnit4, Robolectric, Compose UI tests.

Supersedes the unstarted plan in `TODO.md` ("Automatic refresh while the app is open", planned 2026-09-16).

---

## Why this one, and what it is worth

The app refreshes on resume when the cache is over 30 minutes old, on pull-to-refresh, and hourly
in the background. **While it stays open, nothing moves.** A reader who leaves it on the home screen
through a changing afternoon is looking at a number from when they opened it, over a station reading
that SIAG has republished four times since.

It was picked over the other three candidates because it is the only one that serves all of them:

- **Freshness** is the spine.
- **Accuracy**, but not the way it is tempting to claim. `station_history` is keyed by the hour and
  `mergeStationHour` merges into the existing row, so a ten-minute poll does **not** write six times
  the samples — it writes a better value into the same row, and more importantly it fills hours the
  hourly worker misses. That is the real gain: `RefreshWorker` is a `PeriodicWorkRequest` and Doze
  and app-standby buckets are allowed to be late, so an hour the phone spent asleep is an hour
  `BiasCorrector` and the statistics screen never see. While the app is open the loop cannot be
  deferred. **Do not justify this work as more samples per hour; it is fewer missing hours.**
- **What the reader sees** is the staleness marker. Today a failing refresh shows the offline banner
  only when the app believes it is offline; a phone with a bar of signal that cannot reach GeoSphere
  shows a confident "Aktualisiert 09:12" for hours.
- **Code health** is `RefreshDue`. `StaleRefresher` owns the entire refresh path — fetch, eviction,
  dismissal pruning, widget update — and has **no test at all**, which is how the resume bug it was
  built to fix got in. A loop is the worst thing to add to an untested class, so the decision it
  makes comes out as a pure function first, the way `RefreshWorker.pinsToRefresh` and
  `NotificationDecider` already are.

## Six decisions, taken while planning

**1. The loop lives in `StaleRefresher`, not in a ViewModel, and not in a `Worker`.** Same argument
as the resume hook it sits beside: being open is not a property of any one screen, and the reader who
leaves the app on the radar is owed a moving station reading as much as the one on the home screen.
`WeatherRepository.refresh` already coalesces, so the loop cannot double-fetch against the resume
hook or the hourly worker.

**2. It is driven from `ApexApplication` by an activity count, not by `ProcessLifecycleOwner`.**
`ProcessLifecycleOwner` is the textbook answer and needs `androidx.lifecycle:lifecycle-process`,
which this app does not have. It has exactly one Activity, so
`registerActivityLifecycleCallbacks` counting started activities answers the same question with no
new dependency: the loop runs while the count is above zero and is cancelled when it reaches zero.
If a second Activity ever appears the count still holds. Use `lifecycle-process` only if that stops
being true.

**3. `refreshObservation` takes the same mutex as `refresh`.** It is tempting to let the cheap
station call slip past the big one, and that would be a data race on `ObservationEntity`: the full
refresh's station branch reads the previous observation, applies `StationDry.withPrevious` and
writes — read-modify-write, exactly what `mergeStationHour` is careful about one table over. A
ten-minute poll waiting the few seconds a full refresh takes costs nothing, and the poll is then
serialised with the thing it would otherwise corrupt. **It must not reuse `refresh`'s
`COALESCE_WITHIN` memo**, which is keyed by place and language and describes a full fetch.

**4. Data Saver belongs to the worker, not to this loop.** `TODO.md` lists them together, and they
are different: `getRestrictBackgroundStatus` restricts *background* data, and an app in the
foreground is explicitly exempt. Honouring it here would throttle the one case the platform says is
fine. The metered rule does apply — the station goes to 20 minutes instead of 10, the full refresh
stays at 30, and pins stay Wi-Fi only as `RefreshWorker.pinsToRefresh` already has it.

**5. The OkHttp disk cache is what makes the poll affordable, and it is measured.** SIAG's station
list sends `ETag` and `max-age=600` and is about 80 kB uncompressed (measured 2026-09-16); a
conditional request answers `304` with no body. Note what `max-age=600` means for the cadence: inside
ten minutes OkHttp serves from disk **without a network call at all**, so a poll faster than ten
minutes would only re-read the same bytes. Ten is therefore not a round number, it is the number the
upstream chose. A few megabytes of cache, and nothing else in the app changes behaviour — every
other upstream either sends no validator or is not polled.

**6. The staleness marker is a fact about failure, not about age.** The data being an hour old on a
mountain with no signal is not a fault and the offline banner already says it. The line is marked
when the app is **connected, has tried since, and still has nothing newer** — that is the case with
no symptom today. One threshold, `STALE_ON_SCREEN` = 60 min, and it marks the existing
`updated_at` line rather than adding a second one; the hero's vertical budget was measured and
`HeroLine` is already two lines by deliberate choice.

---

## Global Constraints

- Work on a branch; do not push, tag or merge.
- Run every shell script as `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c '…'`. Gradle in the foreground only, never two builds at once.
- Every new string key goes into `values/` (German — the app is **Apex Wetter** there), `values-it/` and `values-en/`.
- Nothing that runs on a device may assert a German string (CI emulators are en-US).
- Device tests on an **emulator** (`r8verify34`, API 34), never the phone: the run uninstalls and that deletes `station_history`, which is the one table that is not a cache.
- Numbers and times through `ui/common/Format.kt` with `LocalFormats.current`; times in `SouthTyrol.ZONE`.
- Suspend fetches use `runCatchingCancellable`, never `runCatching`.
- Every decision the loop makes is a pure function tested on the JVM; nothing about *when* to fetch lives inside a coroutine.
- `./gradlew :app:lintDebug` runs with `warningsAsErrors` and must be clean.
- Commit per task.

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/it/apexweather/data/RefreshDue.kt` (create) | The pure rule: `RefreshDue.next(now, lastStation, lastFull, failures, metered)` → what to fetch and when to ask again. |
| `app/src/main/kotlin/it/apexweather/ui/StaleRefresher.kt` (modify) | The foreground loop, backoff, the network callback, `refreshStation()`. |
| `app/src/main/kotlin/it/apexweather/ApexApplication.kt` (modify) | Counts started activities; starts and cancels the loop. |
| `app/src/main/kotlin/it/apexweather/data/WeatherRepository.kt` (modify) | `refreshObservation(place)`; `lastObservationAttempt` exposed for the rule. |
| `app/src/main/kotlin/it/apexweather/di/AppModule.kt` (modify) | An OkHttp `Cache` on the shared client. |
| `app/src/main/kotlin/it/apexweather/ui/home/HomeState.kt` (modify) | `staleOnScreen: Boolean` on `HomeUiState`, decided by `HomeStateBuilder`. |
| `app/src/main/kotlin/it/apexweather/ui/home/HomeSections.kt` (modify) | The marked `updated_at` line. |
| `app/src/main/res/values*/strings.xml` (modify) | `updated_at_stale` in three languages. |
| `TODO.md` (modify) | Strike the section this plan supersedes. |
| Tests | `RefreshDueTest`, `StaleRefresherTest` (new — its first), `WeatherRepositoryTest` (+3), `HomeStateBuilderTest` (+2), `HomeScreenTest` (+1), `HttpCacheTest`. |

---

### Task 1: `RefreshDue`, the pure rule

**Files:** `app/src/main/kotlin/it/apexweather/data/RefreshDue.kt` (create),
`app/src/test/kotlin/it/apexweather/data/RefreshDueTest.kt` (create)

Nothing about *when* to fetch may live inside the loop. This is the file that gets read when somebody
asks why the app made a request, and it must be answerable without a phone.

- [ ] `enum class RefreshKind { STATION, FULL }` and `data class RefreshDecision(val kind: RefreshKind?, val wait: Duration)` — what to do now, and how long to sleep before asking again.
- [ ] `RefreshDue.next(now, lastStation, lastFull, consecutiveFailures, metered, online)`:
  - `FULL` when `lastFull` is null or older than `STALE_ON_OPEN` (30 min) — the same constant the resume hook uses, so opening the app and sitting in it cannot disagree about what stale means.
  - else `STATION` when `lastStation` is null or older than `STATION_GAP` (10 min), or `STATION_GAP_METERED` (20 min) on a metered connection.
  - else nothing, and `wait` is whatever is left until the nearer of the two.
  - `online = false` → nothing, and a `wait` of `OFFLINE_WAIT`; the network callback is what actually wakes it, this is only the floor.
- [ ] Backoff: with `consecutiveFailures > 0` the wait is `min(BACKOFF_BASE × 2^(n-1), BACKOFF_MAX)` — 1, 2, 4, 8, 16, 30, 30 minutes. It delays the *next attempt*, never the cadence itself: one failure must not permanently slow a working app down.
- [ ] A full refresh satisfies the station too — it fetches the station as one of its branches — so a `FULL` resets both clocks. Getting this wrong makes the app fetch the station twice a minute after every full refresh.

**Tests** (`RefreshDueTest`, JVM, no Android):
- [ ] A cold start asks for `FULL`.
- [ ] Ten minutes after a full refresh it asks for `STATION`, not `FULL`.
- [ ] Thirty-one minutes after a full refresh it asks for `FULL`, not `STATION`.
- [ ] A `FULL` resets the station clock: no `STATION` is due in the minute after one.
- [ ] Metered moves the station to 20 minutes and leaves the full refresh at 30.
- [ ] Offline decides nothing, whatever is due.
- [ ] The backoff ladder is 1, 2, 4, 8, 16, 30, 30 — asserted as a list, not one case.
- [ ] A success after failures returns to the ordinary cadence in one step.
- [ ] `wait` is never zero or negative, at any input; a zero would be a busy loop on a phone.

**Verify:** `./gradlew :app:testDebugUnitTest --tests '*RefreshDueTest'`.

---

### Task 2: `WeatherRepository.refreshObservation`

**Files:** `app/src/main/kotlin/it/apexweather/data/WeatherRepository.kt` (modify),
`app/src/test/kotlin/it/apexweather/data/WeatherRepositoryTest.kt` (modify)

- [ ] `suspend fun refreshObservation(place: Place): Boolean` — the station alone. Returns whether a reading landed, which is what the loop's failure counter needs.
- [ ] It takes `refreshMutex` (decision 3) but **not** the `COALESCE_WITHIN` memo, which describes a full fetch keyed by place and language.
- [ ] It reuses the existing station branch verbatim — `SiagMappers.mapObservation`, `StationDry.withPrevious` against the cached reading, the same `ObservationEntity` write with `lastError` on failure. Factor that block out of `fetchEverything` and call it from both, rather than writing a second one: two code paths writing one row differently is how `StationDry`'s carried previous reading would quietly stop being carried.
- [ ] It calls `recordStationHour(place, now)` afterwards, so the hour the worker was too late for is filled. It must not prune history on this path — pruning is the hourly worker's job and a ten-minute poll doing it is 144 deletes a day for nothing.
- [ ] A place with no station returns false and makes no call, as `fetchEverything` already does.

**Tests** (`WeatherRepositoryTest`, existing file, its fakes assert the arguments they are given):
- [ ] `refreshObservation` writes the observation and touches no forecast, ensemble or bulletin row.
- [ ] It carries the previous reading forward, so `StationDry` still has its two points.
- [ ] It records the hour in `station_history` at `LeadBucket.NOW`.
- [ ] A place without a station makes no station call and returns false.
- [ ] A full refresh running concurrently does not interleave with it — both writes land, neither is lost.

**Verify:** `./gradlew :app:testDebugUnitTest --tests '*WeatherRepositoryTest'`.

---

### Task 3: The loop, and `StaleRefresher`'s first test

**Files:** `app/src/main/kotlin/it/apexweather/ui/StaleRefresher.kt`,
`app/src/main/kotlin/it/apexweather/ApexApplication.kt` (modify),
`app/src/test/kotlin/it/apexweather/ui/StaleRefresherTest.kt` (create)

- [ ] `fun start()` / `fun stop()` on `StaleRefresher`, holding one `Job` in the app scope. `start` is idempotent: the loop is started by an activity count going to one, and a configuration change must not leave two running.
- [ ] The loop: ask `RefreshDue.next(...)`, act, `delay(decision.wait)`, repeat. Every `delay` is on the injected `Clock`'s terms — the test drives it with `runTest`'s virtual time, which is only possible if nothing sleeps on a wall clock.
- [ ] Failures increment a counter; any success resets it. A `STATION` failure and a `FULL` failure share the counter: the phone is either reaching the internet or it is not.
- [ ] `ConnectivityManager.NetworkCallback` (`onAvailable`) cancels the current `delay` so the loop retries the moment signal returns. This is the mountain case and it is the only reason the callback is worth its registration: without it a reader walking back into coverage waits out a 30-minute backoff. Unregister in `stop()`.
- [ ] `ApexApplication` registers `ActivityLifecycleCallbacks`, counts `onActivityStarted`/`onActivityStopped`, calls `start()` on 0 → 1 and `stop()` on 1 → 0.
- [ ] The widget is updated after a `FULL` as it already is, and **not** after a `STATION`: the widget shows the consensus hero and a ten-minute Glance update while the app is in front of it is work nobody can see.

**Tests** (`StaleRefresherTest`, JVM + Robolectric, `runTest` virtual time, fake repository):
- [ ] Forty minutes of virtual time with the app open makes exactly one full refresh and three station refreshes. This is the test the whole task exists for; assert the counts, not just that something happened.
- [ ] `stop()` ends the loop: no further calls after it, however much time passes. Without this the loop outlives the app in the background, which is the `MapViewModel` `while (true)` bug one class over.
- [ ] `start()` twice runs one loop, not two.
- [ ] A failing station call backs the *next attempt* off and does not slow the cadence once it succeeds.
- [ ] Offline makes no calls; `onAvailable` wakes it immediately rather than after the backoff.
- [ ] The loop and the resume hook together still make one fetch, not two — the repository's coalescing is what guarantees it, and this asserts it end to end.

**Verify:** `./gradlew :app:testDebugUnitTest --tests '*StaleRefresherTest'`.

---

### Task 4: The HTTP cache

**Files:** `app/src/main/kotlin/it/apexweather/di/AppModule.kt`,
`app/src/test/kotlin/it/apexweather/data/remote/HttpCacheTest.kt` (create),
`gradle/libs.versions.toml` + `app/build.gradle.kts` (modify)

- [ ] `Cache(File(context.cacheDir, "http"), HTTP_CACHE_BYTES)` on the shared `OkHttpClient`, 5 MB. Named and sized in one place with the measurement beside it, not a bare number.
- [ ] Add `mockwebserver3` (`com.squareup.okhttp3:mockwebserver3-junit4`, same version as OkHttp 5) as `testImplementation`. It is the only way to prove a `304` is actually being made, and asserting "a cache object exists" would prove nothing.
- [ ] **Check the release build.** A new OkHttp feature is the same class of risk as jhdf's static initialiser: `proguard-rules.pro` may need nothing, but that is to be confirmed by running the smoke, not assumed.

**Tests** (`HttpCacheTest`):
- [ ] A response with `ETag` and `max-age=600` is served from the cache inside ten minutes with **no** network call.
- [ ] After ten minutes the client sends `If-None-Match` and a `304` costs no body.
- [ ] A response with no validator is refetched.

**Verify:** `./gradlew :app:testDebugUnitTest --tests '*HttpCacheTest'`.

---

### Task 5: Saying so on screen

**Files:** `HomeState.kt`, `HomeSections.kt`, `values*/strings.xml`,
`HomeStateBuilderTest.kt`, `HomeScreenTest.kt` (modify)

- [ ] `HomeUiState.staleOnScreen`, decided in `HomeStateBuilder`: true when `updatedAt` is older than `STALE_ON_SCREEN` (60 min) **and** the app is not offline. Offline already has the banner, and two things saying one thing is how the hero got long enough to need shortening.
- [ ] The `updated_at` line takes `updated_at_stale` ("Aktualisiert %1$s · nicht erreichbar") and a dimmer or amber tint when it is set. It stays one line: the hero's vertical budget was measured and three lines is what the footnote was cut back from.
- [ ] Amber here is chrome, as it is on the rain ribbon, and never a weather colour.

**Tests:**
- [ ] `HomeStateBuilderTest`: an hour-old cache while online is stale on screen; the same cache while offline is not.
- [ ] `HomeScreenTest` (device): the marked line is on screen for a stale state and the ordinary one is not, resolved from the resources rather than asserted as German.

---

### Task 6: Verify it, on the phone, with numbers

The point of this feature is requests over time, and nothing in a test suite measures that.

- [ ] `./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintDebug` clean.
- [ ] `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest` — the whole suite, on the **emulator**.
- [ ] `./gradlew :app:assembleRelease` then `./tools/release-smoke.sh` on the emulator.
- [ ] On the phone: `adb shell dumpsys netstats --uid` before and after **40 minutes with the app open**. Expect one full refresh and three station polls; record the bytes and put the number in `CLAUDE.md`. A ten-minute poll that is not hitting the cache will be obvious in that figure, and is the single most likely thing to be wrong.
- [ ] Leave it open an hour with flight mode on for ten minutes in the middle: the backoff must not have run away, and the loop must recover on the first frame after signal returns.
- [ ] Put it in the background for ten minutes and confirm with `dumpsys netstats` that the loop stopped — that `stop()` works is the difference between this feature and a battery bug.
- [ ] Check `station_history` afterwards (copy the `.db`, `-wal` **and** `-shm`) and confirm the hours are filled rather than duplicated.

---

## Deliberately not in this plan

Named so they are not quietly assumed to be covered, and so the next plan has a start:

- **Logging both ensembles' wet shares in `station_history`**, so the ECMWF 0,5 mm threshold can be
  scored on ensembles rather than on the deterministic runs it was tuned against. Real accuracy
  work, a schema migration, and nothing to do with freshness.
- **Watching `MeasuredRain` and `StationDry` in real rain** on the phone. Not a plan; a wet evening.
- **README.md is stale** — one fixed place, "seven models", `app-debug.apk` — and `about` says
  "10 Wettermodellen" against thirteen sources. Two one-line commits, worth doing before the next
  release rather than inside this branch.
- **`WeatherRepository` is 672 lines.** Task 2 extracts the station branch, which is the piece this
  work needs; the rest is not this plan's business.
- **A refresh-interval setting.** Considered and not recommended in `TODO.md`, and this plan does
  not reopen it: the cadences here are each upstream's own publication rate, which is not a
  preference.
