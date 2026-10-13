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

**A pixel's value is the terrain at the pixel's centre**, and the interpolation runs between
centres. Every source read here defines it that way: a WMS `GetMap` `BBOX` names the outer edges of
the image (IGN LiDAR HD), and Mapterhorn and the historical Terrarium tiles are GDAL warps whose
grid origin is the tile's corner and whose values are computed at cell centres. At a pixel's exact
centre the bilinear read is that pixel alone — the same pixel `interpolation = false` returns. Until
`elevation-half-pixel` the calculator put each value on its pixel's north-west *corner* instead, so
every altitude was the terrain half a pixel south-east of the point: 6–7 m per axis at Mapterhorn
z12, 1.6 m at IGN z15. The before/after figures are in that task document.

A neighbour post that falls in the next tile — east or south in the eastern / southern half of a
pixel, west or north in the other half — is read from that tile; at the edge of the world — within
half a pixel of longitude ±180° or of either Web-Mercator latitude limit — there is no next tile, and
`ElevationFunctions.normalizePixel` clamps it to the edge post. Longitude does not wrap across the
antimeridian.

Ground sampling at that configuration is `156543 · cos(lat) / (2¹² · 2)`:

| Latitude | metres per pixel |
|---|---|
| 0° | 19.1 |
| 45° | 13.5 |
| 60° | 9.6 |

One consequence worth stating before any algorithm: the elevation field is a **piecewise-bilinear
surface over ~13.5 m posts** (one post at each pixel centre), so its error is *spatially correlated*, not white — consecutive
trackpoints inside one cell interpolate the same four posts, and there is very little
point-to-point jitter for a dead band to remove. That is why the `dem` preset uses 3 m rather than
copying Strava's 10 m, which is sized for GPS-altimeter noise vcyclist does not have.

It does **not** follow that a road is indistinguishable from the hillside it is cut into. Measured,
a ±15 m lateral move changes individual elevations by up to 16.5 m (15.1 m with the old corner
reading the same day), and the DEM does resolve the road
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
5. …physics, 1 Hz resampling…
6. `ElevationGain` — an annotation pass writing `elevationGainFiltered` /
   `elevationLossFiltered`. It reads `sourceElevation`, so it measures the terrain rather than the
   kernel; it smooths a private copy at its own ~30 m scale; and it runs on the full-resolution
   trace (1 Hz, or the dense path when 1 Hz is off), **before** simplification.
7. `PathSimplifier` — Douglas-Peucker on the smoothed profile; it **carries** the two filtered
   figures over to the path it builds, so `--no-simplify` and the file size no longer move the
   reported D+. See [Simplification and the reported D+](#simplification-and-the-reported-d).

Step 4 is the single most consequential number in this document, and today it is a hard-coded
constant (`ElevationStep.DEFAULT_SMOOTH_WINDOW_M`) that no CLI flag, JS DTO key or WASI option can
reach. `ElevationSmoother` integrates the piecewise-linear profile against the triangular kernel
`1 − d / windowSize` over path distance — each sample weighs by the length it represents, not by
being a sample — so the window is metric and the stage is resample-invariant: inserting points on
the profile's own segments leaves every original sample's output unchanged, and a segment longer
than the window is still smoothed rather than passed through. A 150 m half-width has an effective
averaging length of `150/√6 ≈ 61 m`.

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
| 50 m | 643 | 635 | 633 | 633 | 633 |
| **150 m** (shipped) | **632** | 632 | 632 | 632 | 632 |
| 300 m | 627 | 627 | 627 | 627 | 627 |

**`sports-tracker.gpx`** — 12.3 km, GPS-derived altitude (the file opens at `<ele>0.0</ele>`):

| smooth ↓ / band → | 0 m | 3 m | 10 m |
|---|---|---|---|
| **0 m** | **1278** | 1239 | 1232 |
| 25 m | 1149 | 1139 | 1139 |
| 50 m | 915 | 909 | 909 |
| **150 m** | **656** | 656 | 656 |
| 300 m | 566 | 566 | 561 |

**`stelvio.gpx`** — 3.6 km, a DEM-derived route from gpx.studio, on a switchback hillside:

| smooth ↓ / band → | 0 m | 3 m | 10 m |
|---|---|---|---|
| **0 m** | 222 | 213 | 166 |
| 50 m | 161 | 156 | 139 |
| **150 m** | **133** | 133 | 133 |
| 300 m | 124 | 124 | 124 |

**`sample.gpx`** — Étape du Tour 2025, 130 km, a clean DEM route: 4551 → 4475 at 150 m, **1.7 %**.

**`garmin.gpx`** — 3.9 km, genuinely flat: 6 m of gain at any band up to 5 m, and **0 m at 10 m**.

### What the tables say

1. **The dead band and the smoothing attack the same noise, and the smoothing wins.** They are not
   two independent filters. On `strava.gpx` the band alone takes 1066 → 661; the smoothing alone
   takes 1066 → 632; applying both changes nothing further. Once the profile is smoothed at 150 m,
   *every* band from 0 to 10 m gives the same answer. Strava's headline 2 m / 10 m thresholds are a
   guard rail, not the mechanism.
2. **A dead band cannot remove long-wavelength wander.** `sports-tracker.gpx` loses 4 % to a 10 m
   band and 49 % to the 150 m kernel, because its GPS altitude drifts over hundreds of metres with
   an amplitude far larger than any usable threshold. Only a low-pass touches that.
3. **A large dead band destroys real terrain on flat rides.** `garmin.gpx` reports 0 m at Strava's
   GPS preset for a ride with 6 m of genuine undulation. The band is all-or-nothing by design.
4. **150 m is not obviously right.** It halves `stelvio.gpx` (222 → 133) and cuts `sports-tracker`
   by 49 %, while costing `sample.gpx` only 1.7 %. On a DEM-derived switchback profile some of what
   it removes is DEM artefact — the cell average of road *and* hillside oscillates as the road
   traverses — and some of it is real. Nothing in the repo has ever measured which. That is
   ledger row **R28**.

## What the pipeline reports

Since R27 the enhancer runs an `ElevationGain` stage, and the figure it produces is what
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
| `sports-tracker` | source | 1278 | 1233 | **1082** | 910 |
| | smoothed | 656 | 649 | 641 | 629 |
| `stelvio` | source | 222 | 196 | **173** | 139 |
| | smoothed | 133 | 133 | 133 | 132 |
| `sample` | source | 4551 | 4511 | **4501** | 4458 |
| `garmin` | source | 6 | 6 | **6** | **0** |

Note the `garmin` row: Strava's GPS preset reports **zero** for a ride with 6 m of genuine
undulation. A dead band is all-or-nothing by design, and 10 m is a lot of ground.

The one leg exempt from the band is the opening one: the move from the first sample to the first
confirmed turning point is booked whatever its size, so the legs tile the profile from its start and
`gain + loss` closes on the net change to within one threshold. Without it, a route that opened
with a 2.9 m dip and then climbed counted the climb out of the dip but not the dip, and the closure
was off by up to two thresholds. See `ElevationGain.accumulate`.

Each preset carries its own smoothing as well as its own threshold, because a threshold without a
scale is not an answer — `barometric` is 2 m over 15 m, `dem` 3 m over 30 m, `gps` 10 m over 50 m,
`raw` neither.

## What the smoothing window is worth on the clock

D+ reads `sourceElevation`, and since E5 it is measured before simplification, so the smoothing
window barely moves the reported climbing — only through where the 1 Hz samples land, which
depends on the simulated speed. It moves the *ride*. (Before E5 it also moved D+ through
Douglas-Peucker, which runs on the smoothed profile: the Nantes bench route reported 44 m at 150 m
and 59 m at 50 m.) Duration with `--elevation-smooth-window`, `--no-simplify`, defaults
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

## Simplification and the reported D+

`PathSimplifier` runs Douglas-Peucker on the **smoothed** elevation, with a 10 m tolerance and a
vertical exaggeration of 3, and keeps about 5 % of the points (142 of 2 674 on the Nantes bench
route). Until E5 of [`elevation-bridge-decks`](../tasks/elevation-bridge-decks.md), `ElevationGain`
ran after it and read `sourceElevation` at the kept points only — and on a flat route the kept
points are not on the bridge ramps, so the climbing over the bridges vanished from the summary. Now
the stage runs on the full-resolution trace just before simplification, and the simplifier carries
the figure over. The file's points and size do not change.

Reported D+ (`dem` preset) against the LiDAR truth of the bench routes, at the shipped 150 m kernel:

| Route | Simplify, then measure (before) | Measure, then simplify (since E5) | Dense 5 m grid |
|---|---|---|---|
| `test2` Nantes, IGN | 44 m (0.49) | 84 m (0.95) | 85 m (0.95) |
| `test2` Nantes, Mapterhorn | 80 m (0.90) | 130 m (1.46) | 130 m |
| `test1` Saint-Nazaire, IGN | 59 m (0.82) | 78 m (1.08) | 78 m |
| `test1` Saint-Nazaire, Mapterhorn | 12 m (0.17) | 51 m (0.71) | 52 m |
| `test3` Mauves, IGN | 10 m (0.57) | 26 m (1.48) | 27 m |
| `test3` Mauves, Mapterhorn | 7 m (0.40) | 24 m (1.34) | 24 m |
| `test4` Vercors, IGN | 2 460 m (1.02) | 2 543 m (1.05) | 2 546 m |
| `test4` Vercors, Mapterhorn | 2 425 m (1.00) | 2 476 m (1.03) | 2 479 m |

On Mapterhorn, the default source, the trade is mixed. It has no decks, so the change also exposes
the canyons under the Nantes bridges that simplification used to hide: `test2` goes from 0.90 to
1.46 of the truth, `test4` from 1.00 to 1.03. `test1` and `test3` improve (0.17 → 0.71, 0.40 →
1.34). Over the four routes the absolute error goes 0.83 / 0.10 / 0.60 / 0.00 → 0.29 / 0.46 / 0.34
/ 0.03: better on average (0.38 → 0.28), worse on `test2`, where the old figure was right by
accident. The new figure is the one the full trace has always measured.

`test4` was low before only because simplification happened to remove mountain noise. `test3`'s
1.48 is two 25–75 m stretches beside the Loire where the IGN terrain model, as vcyclist reads it,
sits 3.5–5.8 m above the truth (a levee or a cutting caught by a sideways offset) — a resolution
and registration problem that no D+ preset removes without also removing small bridges.

Recorded rides without `--fix-elevation` (the new figure is the old `--no-simplify` one, within 3 m
of the `dem / source` column of [What the pipeline reports](#what-the-pipeline-reports)): `stelvio`
148 → 172 m (+16 %), `amazfit` 94 → 100, `sports-tracker` 1 126 → 1 081 (−4 %), `movescount`
514 → 519, `sample` 4 485 → 4 501, `strava` 632 → 634, `garmin` 6 → 6. With `--fix-elevation`,
`sample.gpx` goes 4 555 → 4 652 m on IGN and 4 545 → 4 599 m on Mapterhorn. Durations do not move:
the step is an annotation.

Two alternatives were measured and rejected: a tighter simplification (exaggeration 10, or a 5 m
tolerance) recovers at most 0.83 on `test2`, leaves `test1` at 0.82 and costs 13–68 % more points;
simplifying on the source profile reaches 0.73 and changes which points the file keeps.

**One thing the change gives up.** The figure no longer describes the file's own trace — but it
never did: the written `<ele>` is the smoothed profile, so on `test2` the file's own `<ele>` gave
36 m at `dem` while 44 m was reported. A summary is a measurement of the route, not of the file.

### The kernel and the D+ preset on LiDAR (E5: measured, unchanged)

With decks and tunnels in place, E5 measured the 150 m kernel against 100/75/50/30 m and the `dem`
preset (3 m over 30 m) against 1–3 m over 30–60 m. Both stay. A smaller kernel gains a few tenths
of a metre on flat routes (`test2` open-ground RMS 0.27 → 0.19 m at 50 m) and costs the mountains:
on `test4` the stations steeper than 15 % over 50 m go from 0 to 81 (truth: 2), the steepest 10 m
grade from 16 % to 76 %, and on `sample.gpx` from 54 to 113 stations. The 150 m kernel does not
flatten real decks (Saint-Nazaire's steepest 10 m grade 5.7 % against a true p95 of 5.9 %). The
+9.29 m "overshoot near decks" that E3 left open is a bridge the detector misses, whose truth is
the terrain model's canyon; elsewhere, near ramps, the smoothed profile is within 1.9 m. A 1–1.5 m
dead band changes what D+ means rather than making it more accurate (the truth itself moves from
18 to 36 m on `test3`), and 2 m over 60 m, nearly the same truth as `dem`, cuts recorded tracks by
12–21 % with nothing to check against. The full tables are in the task, *Mesures (E5)*; ledger R33.

## What the DEM costs

With `--fix-elevation` on, `strava.gpx` reports **714 m** (z12, `dem` preset, pixel centres) where
the ride's own barometric `<ele>` stream, measured with the same preset, gives **637 m**. The DEM
inflates mountain climbing by about **12 %** — the failure mode Strava's documentation admits in so
many words. When ledger row R30 was first measured, on 2026-08-18, the CLI printed 854 m against the
barometric 634 m, a 35 % inflation; that figure is not reproducible with today's code and tiles —
the old corner reading gave 726 m on 2026-10-13.

It is not a resolution problem. `tiles.mapterhorn.com` serves z13, z14 and z15 (its `tiles.json`
declares no `maxzoom`), and the answers barely move:

| Fixture | z12 | z13 | z14 | z15 |
|---|---|---|---|---|
| `stelvio` D+ | 132 m | 130 m | 130 m | 130 m |
| `stelvio` duration | 572 s | 568 s | 568 s | 568 s |
| `strava` D+ | 714 m | 728 m | 735 m | 736 m |
| `strava` duration | 2 945 s | 2 944 s | 2 945 s | 2 945 s |

Re-measured on 2026-10-13 at pixel centres (`--fix-elevation --dem-zoom Z --no-simplify`, D+ as
the CLI reports it, `dem` preset). 1.5 % of spread on D+ on `stelvio`, 3.0 % on `strava`, at most
0.7 % on the clock; the corner reading gave 1 % and 2 % the same day. Zoom 12 is at the source's native posting in both
regions; deeper zooms cost four times the tiles for interpolation ripple. If the remaining
over-report is not sampling density, it is either the model or *where along the road it is sampled* — which is what
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
| −15 m | 184 | 1.369 | 830 | 1.643 |
| −10 m | 156 | 1.042 | 796 | 1.126 |
| −5 m | 131 | 0.808 | 759 | 0.627 |
| **0 m** | **132** | **0.681** | **722** | **0.224** |
| +5 m | 133 | 0.746 | 700 | 0.600 |
| +10 m | 154 | 0.941 | 708 | 1.052 |
| +15 m | 179 | 1.185 | 732 | 1.536 |

Measured on 2026-10-13, pixel-centre reading (`elevation-half-pixel`). The first measurement is
not reproducible on today's code and tiles: `strava` read 861 m at the centre then, 728 m with the
old corner reading on the same day as this table.

**The recorded line is already the roughness minimum of its own corridor**, on both fixtures, and
roughness rises near-symmetrically with `|offset|`. A snapper has nowhere better to go — it would
return the centre everywhere. Ledger row **R29** closes as a measured ❌. D+ mostly rises with
`|offset|` too, with one exception: on `strava`, +5 m and +10 m read a few percent *less* climbing
than the centre (700 and 708 m against 722 m) — and they are rougher, so a snapper would not go
there either.

The corollary matters more than the row: **the over-report above is not a registration error.**
The lowest D+ anywhere in the corridor, 700 m, is still 10 % above the file's own 637 m, and it sits
off the smooth line. It is the model, or the scale at which a 30 m DEM can represent a graded road
at all. Read the other way, this is a mild endorsement of the plain lookup: a road is the smooth
thing in a landscape, and sampling at the recorded coordinate already finds it.

One trap worth keeping. Choosing the locally smoothest offset *independently per station* — the
naive shape of every "pick the best sample here" rule — gives roughness 8.092 against the centre's
0.224 on `strava`, and D+ 802. The offset track goes jagged and the profile inherits it. Any future
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
above, where four times the tiles buy 1.5–3 % of D+ and under 1 % on the clock. Mapterhorn is a
fused global product — Copernicus 30 m worldwide, national high-resolution models only where they
exist — so above the source's native posting a deeper zoom is resampling. Ledger row **R30**.

Reach it with `--dem-zoom` on the CLI, `demZoom` on the JS enhance options, or
`vcSetElevationConfig` over WASI. Note that the CLI's `--zoom` is a *different* option:
`ExportCommand`'s map zoom. Without it, the zoom is the **source's** own: 12 for Mapterhorn, 15 for
IGN LiDAR HD.

## Choosing the DEM source: IGN LiDAR HD

`ElevationSource` is the catalogue every door names by id — `--dem-source`, `demSource` on the JS
enhance options, `source` on the elevation package's `newElevationProvider` and on WASI's
`vcSetElevationConfig`:

| id | Model | Coverage | Default sampling | Tile |
|---|---|---|---|---|
| `mapterhorn` (default) | fused global DEM, Terrarium WebP | worldwide | z12 × 512 px, ~13.5 m at 45°N | ~65 kB |
| `ign-lidar-hd` | IGN LiDAR HD **terrain** model (MNT), 1 m grid, ~10 cm vertical | metropolitan France, Corsica | z15 × 256 px, ~3.4 m at 45°N | 256 kB |

The IGN source is the same service `leaflet-relief` renders: the Géoplateforme raster WMS
(`data.geopf.fr/wms-r/wms`, layer `IGNF_LIDAR-HD_MNT_ELEVATION.ELEVATIONGRIDCOVERAGE.WGS84G`),
asked for `image/x-bil;bits=32` — raw little-endian float32 metres. Each XYZ tile becomes one
`GetMap` whose `BBOX` is the tile's footprint in EPSG:4326 (`{bbox}` in the URL template). The
module decodes the floats itself (`decodeBil32Tile`, pure Kotlin, every target) and re-encodes them
as Terrarium, so `RawTile` — the contract of every custom fetcher — did not change.

**No data.** Abroad, at sea and in survey gaps IGN answers `-9999`. Those samples read as *no data*,
and the whole point is then asked of the **Mapterhorn fallback** instead — never blended with the
three IGN samples around it, which would put a step in the profile at every coastline. A ride
crossing into Italy is therefore IGN up to the border and Mapterhorn beyond it, not 0 m. Without a
fallback (a custom `ElevationProvider`, or WASI — see the ABI guide), a no-data point reads 0 m.
The one exception is `IgnLidarHdLayer.provider` (raw MNT / MNS / MNH layers for the bridge-deck
detector; no door can name them): there a no-data point reads `NaN`, never 0 m nor Mapterhorn,
and `withFallback` refuses such a provider.
Sample those with `setElevations`, since a smoother would spread the `NaN`.

**Measured** on `demo/public/gpx/sample.gpx` (130 km in Savoie, `--fix-elevation`, defaults
otherwise), each point against its nearest neighbour in the other run:

| | Mapterhorn | IGN LiDAR HD |
|---|---|---|
| Reported climbing (`dem` preset) | 2903 m | 2897 m |
| Simulated duration | 19 566 s | 19 600 s |
| Tiles fetched, cold | 18 (2.3 MB) | 132 (33 MB) |
| \|Δ elevation\| mean / p95 / max | — | 2.0 m / 4.6 m / 26.6 m |

So on this ride the two agree to within the 150 m smoother's reach, and IGN costs fifteen times the
download. Mapterhorn already fuses national models where they exist, which is why. Where IGN earns
its keep is the tail: the 26.6 m point is a road cut into a slope that a 13.5 m post averages with
the hillside. Pick it for a route on narrow benches and in gorges, not for a valley ride.

**No bridges — except where the surface model shows one.** A terrain model is the ground with
everything standing on it removed — trees, buildings, and *viaducts*. A road over a deep valley
samples the valley floor, and a 50 m dip is not something the 150 m kernel hides. The surface model
(MNS) keeps the bridge, and the tree canopy along every forest road with it, so it is never offered
as a DEM source. Instead, `ign-lidar-hd` at its default zoom (15) carries IGN's MNS as its
`ElevationProvider.surfaceModel`, and the enhancer's bridge-deck step (pipeline step 3b, after the
1–2 m refinement and before the smoother) reads it only where `DeckDetector` has found a deck:
smooth, over a canyon of the terrain, not steep. `DeckProfileBuilder` then rides the deck's own
profile — the MNS at clean deck stations, linear across holes and superstructure, a 25 m median, a
slope cap at 10 % — ramped back to the terrain at both ends. On Saint-Nazaire, `sourceElevation`
(the profile before the 150 m smoothing, which the D+ reads) is 0.54 m off its LiDAR truth at worst
instead of 65.8 m under it, and the route's D+ (`dem` preset, on the 5 m grid) goes from 0.74 to
1.08 of the truth. The smoothed `elevation` the physics rides is less exact: the kernel spreads each
deck onto its approaches. The worst point, 9.29 m above the truth on that route, is a bridge the
detector misses (its truth is the canyon), and 5.23 m on the Nantes one is a bench matcher artefact
at another missed bridge; near the ramps of the decks it finds, the smoothed profile stays within
1.9 m. E5 measured smaller kernels and kept 150 m — see
[The kernel and the D+ preset on LiDAR](#the-kernel-and-the-d-preset-on-lidar-e5-measured-unchanged).

How many decks it finds depends on which routes you ask. On the four routes the detector was
calibrated on, 18 bridges of 21; on a held-out series judged once, **4 spans of 11, 30 % of the deck
stations**, with no false positive on either. Three shapes are mostly missed: short bridges (≤ 60 m),
long decks under heavy superstructure (truss, suspension), and long arches steeper than its 10 % test
(Pont-Saint-Esprit). A missed deck is ridden on the valley floor, as before.

It costs one MNS tile per terrain tile (≈ 35 MB more on a cold 130 km ride) and never fails a ride:
a tile it cannot read leaves the terrain profile as it was — for every deck of the route, and
without telling you, so the same command can give either profile depending on the network. Not at
another zoom (the detector was calibrated at 15), not with Mapterhorn, and not on WASI, whose host
serves one layer. The measurements, and what is left (reporting a failed read), are in
[`docs/tasks/elevation-bridge-decks.md`](../tasks/elevation-bridge-decks.md), E1–E3 and E5.

**Tunnels — on every source.** No LiDAR sees into a tunnel: every terrain model, IGN's and
Mapterhorn's alike, is the mountain above it. Read at the point, the Grands Goulets tunnel (Vercors,
1.75 km) climbs 220 m over the ridge and back, and the 111 km route around it reports 2 732 m of
D+ for 2 414 true. The enhancer's tunnel step (pipeline step 3c, right after the deck step and
before the smoother) looks for the shape no road has: a bump at least 15 m above the line between two
*portals*, where the terrain leaves a road-like grade (≤ 20 % over 20 and 50 m) for a cut face, climbs
and falls at 30 % or more over 50 m on both sides, the portal line itself a road grade (≤ 12 %) and the
route between them straight (chord ≥ 0.8 × length), and the terrain already 15 m above that line
within 100 m of each portal (a mountain face, not the far wall of a stream gully whose bridge the
terrain model dropped); the road through is then that line. It needs no surface model, so it runs
with Mapterhorn, with `ign-lidar-hd` at any zoom and on WASI.

It was tuned for **precision**: a false tunnel flattens a real climb, anywhere in the world. On the
calibration set (13 negatives — Alpe d'Huez, Montvernier, Galibier, Ventoux, Montmartre, Lyon,
balconies, dams, galleries, a cutting — 16 bridge negatives and ten whole rides, `sample.gpx`
included) it moves no ground station on IGN and 13 on Mapterhorn, all in one 70 m cliff spike over
the Gorges de la Bourne, which it moves *towards* IGN's reading. It finds 7 of the 8 tuning tunnels
on IGN (5 on Mapterhorn), and three of the four Vercors ones on IGN (all four on Mapterhorn): Grands
Goulets ends 0.9 m (IGN) and 3.8 m (Mapterhorn) from its portal line instead of 220 m, and the
route's D+ (`dem`, 5 m grid) goes from 1.19 to 1.05 of the truth (1.18 to 1.03 on Mapterhorn). It does **not** see a cut-and-cover tunnel
under flat ground (a 4–6 m bump, a small error), a tunnel shorter than 50 m, a curved or spiral
one, nor a chain of short rock tunnels separated by glimpses of the gorge (Petits Goulets on IGN:
+68 m left). `sample.gpx` goes through none and comes out bit for bit unchanged, though not by a
wide margin: a real 72 m hill on the D 109 and a 25 m trail knoll on `sports-tracker.gpx` each fail
one criterion only (both pinned in `TunnelBenchProbeTest`). Judged once on a held-out series (7
tunnels, 4 steep negatives, never tuned on): no false tunnel and no ground moved on either source, 6
of 7 tunnels found; missed, a former rail tunnel under a low rounded hill whose flank falls less
than 30 % over 50 m. The face criterion and two step guards came after that judgement.

Two limits matter more than the misses. A **real** knoll that climbs and falls 30 % over 50 m — not
a paved road, but a gravel, MTB or hiking track — is flattened, and there is no option to turn the
step off yet. And precision has only been measured on LiDAR-derived terrain (IGN, and Mapterhorn in
France and Finland): Mapterhorn's worldwide GLO-30 base (30 m, a surface model) is **unmeasured**; a
probe on the Dadès gorge road in Morocco found three "tunnels" there. Details in E4 of the task.

**Rate limit.** The Géoplateforme answers HTTP 429 past 40 requests a second per IP, and
`BatchCalculator` fetches ten tiles at once — a cold run failed on its 51st tile before pacing
existed. `ElevationProviderConfig.maxRequestsPerSecond` (20 for IGN) paces the default fetcher and
the CLI's disk cache, and a 429 or 503 is retried after the server's `Retry-After` (1, 2, 4… s when
it is not readable, as in a browser). See `pacedTileBytesFetcher`.

```bash
INTEGRATION=1 ./gradlew :elevation:jvmTest --tests '*IgnLidarHdIntegrationTest*' --rerun-tasks
```
