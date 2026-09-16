# TODO

## Automatic refresh while the app is open

Planned 2026-09-16, not started. Today the app refreshes on resume when the cache is older than
30 min, on pull-to-refresh, and hourly in the background (WorkManager); while it stays open,
nothing refreshes. Each source should be refreshed as often as it changes, not everything on one
timer.

| Source | Changes | Refresh while open |
|---|---|---|
| Station (SIAG) | every 10–20 min | every 10 min |
| Radar at the place | every 10 min | already (`RadarRepository.latestAt`, one tile) |
| Models, ensembles, bulletin, warnings | hourly to a few times a day | every 30 min (`STALE_ON_OPEN`) |
| Map forecast (INCA) | every 15 min | already (`NowcastRepository.due`) |

- [ ] **Foreground loop in `StaleRefresher`**, `repeatOnLifecycle(STARTED)` at process level, so it
  stops in the background by itself. Every 10 min the station alone, every 30 min a full refresh.
  Skips while offline (`NetworkCallback`) and resumes when the network returns. Exponential
  backoff after failures (1 → 2 → 4 min, at most 30). Goes through the repository's existing
  coalescing, so it never fetches twice.
- [ ] **`WeatherRepository.refreshObservation(place)`**: a station-only refresh that also writes
  `station_history`, so the 10-minute readings help the statistics.
- [ ] **OkHttp disk cache** (a few MB). SIAG's station list sends `ETag` and `max-age=600`; a
  conditional request answers `304` with 0 bytes against 80 kB uncompressed (measured
  2026-09-16), which is what makes a 10-minute poll affordable. Open Data Hub's single-station
  `latest` is 834 B but ran more than an hour behind SIAG that evening, so it cannot replace it.
- [ ] **Background stays at 60 min.** Respect Data Saver (`getRestrictBackgroundStatus`) as well
  as metered networks. A background-interval setting was considered and not recommended.
- [ ] **Metered connection**: the station every 20 min instead of 10; the full refresh stays at
  30; pins stay Wi-Fi only.
- [ ] **Freshness on screen**: when the data is older than 60 min because refreshing failed, mark
  the "Aktualisiert" line, not only the offline banner.
- [ ] **Tests**: a pure `due(now, lastStation, lastFull, failures, metered)` rule tested like
  `RefreshWorker.pinsToRefresh`; the loop against the test clock; the cache against a fake server
  answering `304`. On the phone: 40 minutes open, then count requests and bytes with
  `dumpsys netstats`.

## Other open points

- [ ] The phone's `station_history` was deleted on 2026-09-16 by `tools/release-smoke.sh`. A
  Google backup set exists but was probably written after the reinstall; restoring would
  overwrite the app's current data — Ben's decision.
- [ ] The measured-rain rules (`MeasuredRain`, `StationDry`) have not yet been watched in real rain
  on the phone.
- [ ] The chance-of-rain threshold for ECMWF (0,5 mm/h) was scored on deterministic runs; log both
  ensembles' wet shares in `station_history` so it can be scored on the ensembles themselves.
