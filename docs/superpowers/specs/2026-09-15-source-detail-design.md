# Source detail sheet — design

Date: 2026-09-15. Status: approved in conversation, awaiting written review.

## Why

The comparison screen's "Quellenstatus" card lists thirteen sources with a coloured timestamp and,
on failure, the first 40 characters of the error. A reader cannot learn from it what a model is,
how much it counts, whether the app is correcting it, or why it was dropped. Everything needed to
say so is already in the app except one fact — when the model's current run started — and that is
published by the providers.

This is the first of six sub-projects agreed on 2026-09-15, in this order: source detail, a fast
offline map, forecast trust (INCA scoring, p90 layer, rain notification), review loose ends, widget
and pinned-place extras. The wet-day checks of the map wait for rain.

## What the reader sees

Every row of the status card is tappable (`clickable`, role Button, accessible label "ICON-CH1,
aktuell, Details öffnen"). A tap opens a full-height `ModalBottomSheet`
(`skipPartiallyExpanded = true`) with a **close cross**, per the bottom-sheet rule in CLAUDE.md.
The sheet's title is the source's `displayName` and is a heading. It has four blocks, each with a
heading.

### 1. What it is

From a static table, `SourceInfo`, one entry per `Source`:

- provider (institution) and model family (`ModelFamily`, e.g. ICON, HARMONIE)
- horizontal grid resolution in km, and regional or global
- delivery path: Open-Meteo, GeoSphere Data Hub or SIAG / Open Data Hub
- licence of the data as the app receives it
- provider website (https)

Every value is **verified against the provider's own documentation during planning** and carries
the date it was checked in a comment. Nothing is written from memory.

### 2. Is it working

From the cached snapshot:

- status in words and colour (`Ok`, `Stale`, `Failed`, never loaded) with its timestamp
- "counts as stale after N h" from `Source.staleAfterHours`, with a one-sentence reason
- on failure: the **full** error text (selectable) and "last good data" from
  `SourceStatus.Failed.lastIssuedAt`
- reach: the last hour with a value in the cached forecast, as a day and time

Fetched when the sheet opens (see *Live metadata*):

- when the current run started, when it was published, how often the model updates

**Reach never comes from provider metadata.** Open-Meteo's `data_end_time` does not describe what
the forecast call returns: on 2026-09-14 it gave UKMO 61 h and ECMWF IFS 147 h, against 171 h and
336 h measured on the real call.

### 3. Share of the consensus

- vote weight from `ConsensusBlender.weightOf(source, among)`, where `among` is the set of sources in
  `forecastsForBlend`; written as "0,5 Stimmen — 4 Modelle teilen den ICON-Kern"
- whether it is in the consensus now, i.e. a key of `WeatherSnapshot.forecastsForBlend`
- for non-regional sources, one sentence: they only count where fewer than two regional sources
  reach an hour

### 4. Against the station

Only when the place has a station (`Place.station != null`):

- the station's name
- per `DayPart`, the model's measured bias for `LeadBucket.SIX` from `ModelBias.byPart`, signed
  ("+1,4 K", "±0,0 K"), labelled as the error six hours ahead. Not `NOW`: a lead of zero is nearly an
  analysis and says little about a forecast; not all three buckets: twelve numbers do not fit a
  phone sheet and `SIX` is the middle of what the correction is applied to.
- where a cell is absent: "noch zu wenige Stunden" — `BiasCorrector.MIN_SAMPLES` hours are needed
- `SIAG_KMOS` (not `checkableAtStation`) shows the existing `compare_never_checkable` sentence
  instead of the table

With no station near the place the block is omitted.

## Live metadata

| Source | Endpoint | Fields read |
|---|---|---|
| 11 Open-Meteo models | `https://api.open-meteo.com/data/<dataset>/static/meta.json` | `last_run_initialisation_time`, `last_run_availability_time`, `update_interval_seconds` (Unix seconds) |
| GeoSphere AROME | `https://dataset.api.hub.geosphere.at/v1/timeseries/forecast/nwp-v1-1h-2500m/metadata` — the dataset the forecast call already uses (HTTP 200, 3,3 kB, 2026-09-14) | `last_forecast_reftime`, `available_forecast_reftimes` (interval = difference of the two newest) |
| SIAG KMOS | none | its `SourceStatus` time is already the run |

The Open-Meteo **dataset name differs from the model id the forecast call uses** and is carried in
`SourceInfo`. Measured 2026-09-14, each answered HTTP 200 at 380–490 B gzipped:
`meteoswiss_icon_ch1`, `meteoswiss_icon_ch2`, `italia_meteo_arpae_icon_2i`, `dwd_icon_d2`,
`knmi_harmonie_arome_europe`, `dmi_harmonie_arome_europe`, `ecmwf_ifs025`, `ecmwf_aifs025_single`,
`ncep_gfs013` (for `gfs_seamless`), `ukmo_global_deterministic_10km`, `cmc_gem_gdps`.

`SourceMetaRepository` holds each answer in memory for 10 minutes, the radar's rule, and writes
nothing to Room. It does not touch the forecast, the consensus, staleness or refresh.

## Architecture

- `domain/SourceInfo.kt` — static table, `SourceInfo.of(source)`.
- `data/remote/SourceMetaApi.kt` — Retrofit interfaces for the two hosts, `SourceMetaMapper` to
  `SourceMeta(runStartedAt: Instant?, publishedAt: Instant?, updateEvery: Duration?)`.
- `data/SourceMetaRepository.kt` — cache, `runCatchingCancellable`, 5 s call timeout, `null` on any
  failure.
- `ui/compare/SourceDetail.kt` — `SourceDetailState` built by a pure
  `SourceDetailStateBuilder.build(source, snapshot, place, consensusSources, now)`, and
  `SourceDetailSheet(state, meta: SourceMetaUi, onClose)` where `SourceMetaUi` is Loading,
  Loaded(SourceMeta) or Unavailable.
- `CompareViewModel` gains `openSource(source)` / `closeSource()`; the open source is saved in
  `SavedStateHandle`; the detail state is derived from the same `holder.weather` flow, so a refresh
  while the sheet is open updates it. The meta fetch runs in a job cancelled by `closeSource`.
- `CompareContent` takes `onOpenSource`, keeping the `XScreen` / `XContent` split.

## Behaviour and errors

- The sheet opens at once with every cached fact; only the run line waits, showing a placeholder.
- Metadata failure, timeout or offline: the run line reads "Laufzeit nicht abrufbar"; nothing else
  changes. A missing or malformed field drops that line alone.
- A run start later than the phone's clock is shown as published "gerade eben" rather than in the
  future.
- A source never loaded: status "nie geladen", no reach, no share, no bias; the table facts remain.
- Links open with `LocalUriHandler`; no new library.
- All numbers through `Format`, all times in `SouthTyrol.ZONE`; resolution with the locale's decimal
  separator.

## Strings and accessibility

- Every new key in `values`, `values-it`, `values-en`.
- Title and block headings carry `heading()` semantics; status is always words as well as colour.
- The website button reads "Website von <provider> öffnen".
- The sheet is checked at font scale 2 on the phone.

## Tests

Unit (JVM / Robolectric):

- `SourceInfoTest`: every `Source` has an entry; licence non-blank; website starts with `https://`;
  Open-Meteo sources have a dataset name, others none.
- `SourceMetaMapperTest`: recorded fixtures `meta-dwd_icon_d2.json`, `meta-ecmwf_ifs025.json`,
  `geosphere-nwp-metadata.json`; a fixture with a missing field yields that field null.
- `SourceMetaRepositoryTest`: second call inside 10 min makes no request; a failure returns null and
  a later call retries; cancellation propagates; the fake asserts the dataset name in the URL it was
  asked for.
- `SourceDetailStateBuilderTest`: ICON-CH1 weight 0,5 with four ICON sources among the blend and
  AROME 1,0; a global source reported as not in the consensus when two regional sources reach;
  reach is the last hour with a value; bias per `DayPart` with "too few hours" for an absent cell;
  no bias block for `SIAG_KMOS` or a place without a station; never-loaded source.

Device (`androidTest`, no German asserted):

- tapping a status row opens the sheet; the cross closes it; a long `Failed` reason is displayed
  in full.

Screenshot (Roborazzi): the sheet for a fixed state.

## Verification and delivery

Unit, goldens, lint, device suite, release smoke; on the phone tap through all thirteen sources,
once with airplane mode on, and once at font scale 2. Release as v0.24.0.

## Out of scope

- Storing run times on refresh, or using them for staleness or the blend (considered and deferred:
  it changes the consensus and needs its own design).
- Retrying a single source, hiding a source from the sheet, copying the error.
