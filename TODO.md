# TODO

## Automatic refresh while the app is open — **done 2026-09-22**

Built as `docs/superpowers/plans/2026-09-22-fresh-while-open.md`. The station is polled every 10
minutes while the app is open (20 on a metered connection), everything else every 30, and the
"Aktualisiert" line says so when refreshing has been failing on a connected phone. `RefreshDue` is
the pure rule, `ForegroundRefreshLoop` the timing, `StaleRefresher` the wiring, and
`ApexApplication`'s count of started activities starts and stops it.

Two items from the original plan were **not** done, and deliberately:

- **Data Saver** belongs to the hourly worker and not to this loop: `getRestrictBackgroundStatus`
  restricts *background* data and a foreground app is explicitly exempt, so honouring it here would
  throttle the one case the platform says is fine.
- **Freshness on screen** was built as a fact about *failure* rather than about age — an hour-old
  cache on a mountain with no signal is not a fault and the offline banner already says so.

## Other open points

- [ ] The phone's `station_history` was deleted on 2026-09-16 by `tools/release-smoke.sh`. A
  Google backup set exists but was probably written after the reinstall; restoring would
  overwrite the app's current data — Ben's decision.
- [ ] The measured-rain rules (`MeasuredRain`, `StationDry`) have not yet been watched in real rain
  on the phone.
- [ ] The chance-of-rain threshold for ECMWF (0,5 mm/h) was scored on deterministic runs; log both
  ensembles' wet shares in `station_history` so it can be scored on the ensembles themselves.
