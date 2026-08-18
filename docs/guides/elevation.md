# Elevation and cumulative ascent

Where vcyclist's altitudes come from, what the pipeline does to them, and why the D+ it reports
will never equal the D+ Strava reports for the same file.

Related: [`elevation/README.md`](../../elevation/README.md) for the tile transport and per-target
decoding, [`ledgers/improvements-ledger.md`](../ledgers/improvements-ledger.md) rows **R27–R30** for
the open work.

## Where altitudes come from

| Source | When | Where |
|---|---|---|
| The GPX's own `<ele>` | always, as the input | `:gpx` parser → `PointField.ELEVATION` |
| A DEM lookup | when `fixElevation` is on | `:elevation` → `ElevationStep.fixElevation` |

vcyclist has **no barometric source and no community basemap**. Every corrected altitude is a
lookup into a public digital elevation model — Terrarium-encoded WebP tiles, by default
`tiles.mapterhorn.com` at zoom 12 with 512-px tiles, bilinearly interpolated
(`ElevationCalculator.getInterpolatedElevation`).

Ground sampling at that configuration is `156543 · cos(lat) / (2¹² · 2)`:

| Latitude | metres per pixel |
|---|---|
| 0° | 19.1 |
| 45° | 13.5 |
| 60° | 9.6 |

One consequence worth stating before any algorithm: the elevation field is a **piecewise-bilinear
surface over ~13.5 m posts**, so its error is *spatially correlated*, not white — consecutive
trackpoints inside one cell interpolate the same four posts, and there is very little
point-to-point jitter for a dead band to remove. That is why the `dem` preset uses 3 m rather than
copying Strava's 10 m, which is sized for GPS-altimeter noise vcyclist does not have.

It does **not** follow that a road is indistinguishable from the hillside it is cut into. Measured,
a ±15 m lateral move changes individual elevations by up to 14 m, and the DEM does resolve the road
corridor well enough that the recorded line is the smoothest place in it — see
[Snapping to the road](#snapping-to-the-road-measured-and-it-does-not-work).

## What the pipeline does

From [`Enhancer.kt`](../../engine/src/commonMain/kotlin/io/github/glandais/engine/Enhancer.kt):

1. `PointPerDistance(-1, 30)` — densify to ~30 m so the DEM lookup has granularity.
2. `fixElevation` — optional, replaces every altitude from the DEM.
3. `PointPerDistance(1, 2)` — refine to 1–2 m spacing for the physics.
4. `smoothElevation` — a **triangular kernel of 150 m half-width, always, unconditionally**. The
   pre-smoothing altitude of every point is kept in `PointField.SOURCE_ELEVATION`, the way the
   racing line keeps `sourceLatitude`/`sourceLongitude`.
5. …physics, resampling, simplification…
6. `ElevationGain` — an annotation pass, last, writing `elevationGainFiltered` /
   `elevationLossFiltered`. It reads `sourceElevation`, so it measures the terrain rather than the
   kernel; it smooths a private copy at its own ~30 m scale; and it runs after simplification so
   the figure describes the file the caller receives.

Step 4 is the single most consequential number in this document, and today it is a hard-coded
constant (`ElevationStep.DEFAULT_SMOOTH_WINDOW_M`) that no CLI flag, JS DTO key or WASI option can
reach. `ElevationSmoother` weights by `1 − d / windowSize` over path distance, so the window is
metric and the stage is resample-invariant; a 150 m half-width has an effective averaging length of
`150/√6 ≈ 61 m`.

`Path.elevationGain` / `elevationLoss` are computed in `Path.computeDerivedData` and are a **plain
sum of every positive/negative delta** — no dead band, no scale awareness. They are recomputed after
every stage, so `Path.elevationGain` on an unsmoothed input and on the delivered path are entirely
different quantities carrying the same name. They are kept exactly as they are, because they are the
control the filtered figures are measured against and because `ClimbDetector` sizes its adaptive
threshold from them.

What a summary should show is `Path.reportedElevationGain` / `reportedElevationLoss`: the
dead-banded figure when step 6 ran, the raw sum otherwise. `PathToFit` writes those as FIT
`total_ascent` / `total_descent`, and `JsonWriter` emits both pairs (`elevationGain` and
`elevationGainFiltered`, the latter `null` when the stage did not run).

## Cumulative ascent is a measurement, not a property

This is the part that surprises people, and it is not vcyclist's fault. Cumulative ascent behaves
like coastline length: it grows without bound as the measurement scale shrinks. Kollár's
*[Evaluating cumulative ascent: mountain biking meets Mandelbrot](https://arxiv.org/abs/1011.4778)*
measures the power law directly and finds exponents of **1.17 for GPS altitude and 1.06 for
barometric** over the 30–300 m averaging range — GPS altitude has to be averaged over nearly 200 m
before it even reaches the *unaveraged* barometric estimate.

So "the D+ of this route" is not a well-posed question. "The D+ of this route at a 30 m measurement
scale with a 3 m dead band" is.

### Measured on the shipped fixtures

Reproduce with:

```bash
python3 tools/elevation/dplus_scale.py demo/public/gpx/*.gpx
```

Rows are the triangular-kernel half-width, columns the hysteresis dead band, cells are D+ in metres,
computed on each file's own `<ele>` stream (no DEM, no pipeline).

**`strava.gpx`** — 21.1 km, 7264 pts, `creator="StravaGPX"`, 1 Hz, barometric-flavoured:

| smooth ↓ / band → | 0 m | 2 m | 3 m | 5 m | 10 m |
|---|---|---|---|---|---|
| **0 m** | **1066** | 752 | 661 | 641 | 635 |
| 25 m | 652 | 640 | 637 | 634 | 634 |
| 50 m | 643 | 636 | 633 | 633 | 633 |
| **150 m** (shipped) | **632** | 632 | 632 | 632 | 632 |
| 300 m | 627 | 627 | 627 | 627 | 627 |

**`sports-tracker.gpx`** — 12.3 km, GPS-derived altitude (the file opens at `<ele>0.0</ele>`):

| smooth ↓ / band → | 0 m | 3 m | 10 m |
|---|---|---|---|
| **0 m** | **1278** | 1239 | 1232 |
| 25 m | 1230 | 1218 | 1218 |
| 50 m | 1000 | 994 | 994 |
| **150 m** | **668** | 668 | 668 |
| 300 m | 568 | 568 | 564 |

**`stelvio.gpx`** — 3.6 km, a DEM-derived route from gpx.studio, on a switchback hillside:

| smooth ↓ / band → | 0 m | 3 m | 10 m |
|---|---|---|---|
| **0 m** | 222 | 213 | 166 |
| 50 m | 177 | 170 | 140 |
| **150 m** | **132** | 132 | 132 |
| 300 m | 124 | 124 | 124 |

**`sample.gpx`** — Étape du Tour 2025, 130 km, a clean DEM route: 4551 → 4484 at 150 m, **1.5 %**.

**`garmin.gpx`** — 3.9 km, genuinely flat: 6 m of gain at any band up to 5 m, and **0 m at 10 m**.

### What the tables say

1. **The dead band and the smoothing attack the same noise, and the smoothing wins.** They are not
   two independent filters. On `strava.gpx` the band alone takes 1066 → 661; the smoothing alone
   takes 1066 → 632; applying both changes nothing further. Once the profile is smoothed at 150 m,
   *every* band from 0 to 10 m gives the same answer. Strava's headline 2 m / 10 m thresholds are a
   guard rail, not the mechanism.
2. **A dead band cannot remove long-wavelength wander.** `sports-tracker.gpx` loses 4 % to a 10 m
   band and 48 % to the 150 m kernel, because its GPS altitude drifts over hundreds of metres with
   an amplitude far larger than any usable threshold. Only a low-pass touches that.
3. **A large dead band destroys real terrain on flat rides.** `garmin.gpx` reports 0 m at Strava's
   GPS preset for a ride with 6 m of genuine undulation. The band is all-or-nothing by design.
4. **150 m is not obviously right.** It halves `stelvio.gpx` (222 → 132) and cuts `sports-tracker`
   by 48 %, while costing `sample.gpx` only 1.5 %. On a DEM-derived switchback profile some of what
   it removes is DEM artefact — the cell average of road *and* hillside oscillates as the road
   traverses — and some of it is real. Nothing in the repo has ever measured which. That is
   ledger row **R28**.

## What the pipeline reports

Since R27 the enhancer runs a sixth stage, `ElevationGain`, and the figure it produces is what
`reportedElevationGain` returns. Reproduce with:

```bash
MEASURE=1 ./gradlew :engine:jvmTest --tests '*ElevationGainMeasurementTest*' --rerun-tasks -i
```

D+ in metres, by profile and preset — `source` is the densified profile before the 150 m kernel,
which is what the stage actually measures; `smoothed` is the one the physics rides:

| Fixture | profile | raw | barometric (2 m) | **dem (3 m)** | gps (10 m) |
|---|---|---|---|---|---|
| `strava` | source | 1007 | 643 | **637** | 633 |
| | smoothed | 632 | 632 | 632 | 631 |
| `sports-tracker` | source | 1278 | 1234 | **1088** | 907 |
| | smoothed | 655 | 649 | 641 | 628 |
| `stelvio` | source | 222 | 197 | **173** | 139 |
| | smoothed | 133 | 133 | 133 | 132 |
| `sample` | source | 4551 | 4511 | **4501** | 4459 |
| `garmin` | source | 6 | 6 | **6** | **0** |

Note the `garmin` row: Strava's GPS preset reports **zero** for a ride with 6 m of genuine
undulation. A dead band is all-or-nothing by design, and 10 m is a lot of ground.

Each preset carries its own smoothing as well as its own threshold, because a threshold without a
scale is not an answer — `barometric` is 2 m over 15 m, `dem` 3 m over 30 m, `gps` 10 m over 50 m,
`raw` neither.

## What the smoothing window is worth on the clock

Now that D+ reads `sourceElevation`, the smoothing window no longer moves the reported climbing at
all — it moves the *ride*. Duration with `--elevation-smooth-window`, `--no-simplify`, defaults
otherwise:

| Window | `stelvio` | `strava` | `sample` |
|---|---|---|---|
| 10 m | 693 s | 2918 s | 19 575 s |
| 50 m | 637 s | 2907 s | 19 562 s |
| **150 m** (shipped) | **594 s** | **2899 s** | **19 508 s** |
| 300 m | 571 s | 2888 s | 19 411 s |

**17.6 % on `stelvio.gpx`**, against 1 % elsewhere. The whole effect is switchback terrain, where a
DEM cell averages the road with the hillside it is cut into and the sampled profile oscillates as
the road traverses. Some of what the kernel removes there is artefact and some is real climbing;
nothing here distinguishes them without ground truth, which is why the default has not moved.

## What the DEM costs

With `--fix-elevation` on, `strava.gpx` reports **854 m** where the ride's own barometric stream
measures 634 m. The DEM inflates mountain climbing by **35 %** — the failure mode Strava's
documentation admits in so many words.

It is not a resolution problem. `tiles.mapterhorn.com` serves z13, z14 and z15 (its `tiles.json`
declares no `maxzoom`), and the answers barely move:

| Fixture | z12 | z13 | z14 | z15 |
|---|---|---|---|---|
| `stelvio` D+ | 131 m | 130 m | 130 m | 130 m |
| `stelvio` duration | 568 s | 568 s | 568 s | 568 s |
| `strava` D+ | 854 m | — | 851 m | — |

0.8 % of spread on D+, none on the clock. Zoom 12 is at the source's native posting in both
regions; deeper zooms cost four times the tiles for interpolation ripple. If the 35 % is not
sampling density, it is either the model or *where along the road it is sampled* — which is what
ledger row R29 exists to test.

## What Strava does

From Strava's own documentation:

- [Elevation](https://support.strava.com/en-us/articles/15401909-elevation) and
  [Elevation on Strava FAQs](https://support.strava.com/en-us/articles/15402093-elevation-on-strava-faqs)
- [Strava's Elevation Basemap](https://support.strava.com/hc/en-us/articles/115000024864-Strava-s-Elevation-Basemap)

| Strava | vcyclist |
|---|---|
| Barometric altimeter when the device has one | never — no barometer in the loop |
| Otherwise a lookup against an elevation database | `ElevationStep.fixElevation`, same idea |
| Otherwise their **basemap**, built by aggregating community barometric traces | not available — the basemap is not public |
| "Smoothing … which includes discarding outliers", **less** for barometric, **more** for GPS-only | one unconditional 150 m triangular kernel, no outlier rejection |
| Gain counts only after **>10 m** (GPS) / **2 m** (barometric) of consistent climbing | no dead band at all |
| The basemap "looks up the elevation for the road or trail you were actually on" | lookup at the recorded coordinate |

vcyclist's altitudes are always DEM-derived, so vcyclist is permanently in Strava's *"more
smoothing"* branch — but its noise is DEM noise, not GPS-altimeter noise, and the two have different
structure. Copying Strava's 10 m would be sizing a filter for white noise vcyclist does not have.

Prior art for the dead band outside Strava: **GoldenCheetah** ships a configurable "elevation
hysteresis" defaulting to **3.0 m**, and Garmin devices apply a double hysteresis that swallows the
first 1–5 m of a climb.

### Why the numbers will not match

- Strava's basemap is not public and cannot be reproduced.
- Strava's own FAQ says the same ride recorded on two devices gives two different totals, and that
  comparing barometric against corrected elevation is misleading even though both are "accurate".
- `demo/public/gpx/strava.gpx` carries **no** summary D+ — its `<metadata>` holds only `<time>`, and
  there is no `gpxx:TrackStatsExtension`. What it does carry is Strava's own `<ele>` stream, which
  makes it a usable *proxy* reference, not a published figure. Any comparison must say which.

Chasing Strava's exact number is therefore unfalsifiable, and vcyclist should not ship a mode that
claims to. Reproducing Strava's *documented thresholds* is a different and honest claim.

## Snapping to the road — measured, and it does not work

Strava's basemap works because it is built from real barometric traces recorded **on the road**, so
"look up the road, not the drifted GPS point" is meaningful. vcyclist would be snapping within a
single DEM. `RoadSnapProbeTest` samples the real DEM across a ±15 m corridor — the GPS-error scale
— at ~30 m stations, and reports D+ and mean `|second difference|`, the roughness any snapper would
minimise:

| offset | `stelvio` D+ | `stelvio` roughness | `strava` D+ | `strava` roughness |
|---|---|---|---|---|
| −15 m | 189 | 1.494 | 926 | 1.686 |
| −5 m | 132 | 0.980 | 872 | 0.664 |
| **0 m** | **131** | **0.873** | **861** | **0.233** |
| +5 m | 132 | 0.897 | 861 | 0.662 |
| +15 m | 177 | 1.266 | 899 | 1.671 |

**The recorded line is already the roughness minimum of its own corridor**, on both fixtures, and
roughness rises near-symmetrically with `|offset|`. A snapper has nowhere better to go — it would
return the centre everywhere. D+ rises with `|offset|` too, so every lateral move makes both numbers
worse. Ledger row **R29** closes as a measured ❌.

The corollary matters more than the row: **the 35 % over-report above is not a registration
error.** No point in the corridor is closer to the barometric 634 m than the centre's 861 m — they
are all further away. It is the model, or the scale at which a 30 m DEM can represent a graded road
at all. Read the other way, this is a mild endorsement of the plain lookup: a road is the smooth
thing in a landscape, and sampling at the recorded coordinate already finds it.

One trap worth keeping. Choosing the locally smoothest offset *independently per station* — the
naive shape of every "pick the best sample here" rule — gives roughness 9.325 against the centre's
0.233 on `strava`, and D+ 957. The offset track goes jagged and the profile inherits it. Any future
attempt needs a smoothness penalty on the offsets themselves, not just on the elevations.

Run it with:

```bash
INTEGRATION=1 ./gradlew :engine:jvmTest --tests '*RoadSnapProbeTest*' --rerun-tasks -i
```

## Raising the DEM zoom

`ElevationProviderConfig.zoomLevel` defaults to 12 and `ElevationProvider` validates `0..15`. That
upper bound is a validation limit, not a statement about the tile source, and
`map/.../MapSpace.kt:30`'s comment that the DEM "has no deeper tiles" is simply wrong — mapterhorn
serves z13, z14 and z15. It is still not worth using: see [what the DEM costs](#what-the-dem-costs)
above, where four times the tiles buy 0.8 % of D+ and nothing at all on the clock. Mapterhorn is a
fused global product — Copernicus 30 m worldwide, national high-resolution models only where they
exist — so above the source's native posting a deeper zoom is resampling. Ledger row **R30**.

Reach it with `--dem-zoom` on the CLI, `demZoom` on the JS enhance options, or
`vcSetElevationConfig` over WASI. Note that the CLI's `--zoom` is a *different* option:
`ExportCommand`'s map zoom.
