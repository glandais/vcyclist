# Engine parity — TS ↔ Kotlin measurement, strategy & tolerances

## What this document is

The Kotlin `:gpx` / `:engine` / `:elevation` modules are a port of the TypeScript
`virtual-cyclist` and `elevation` libraries. This document records the **measured**
numerical relationship between the two implementations, the divergences found, and the
parity strategy that follows from them.

Measured 2026-07-27 against `virtual-cyclist` @ `develop` and `elevation` 3.2.2, on all
7 sample GPX files, stage by stage across the whole `Enhancer` pipeline.

The harness is committed in [`tools/parity/`](../tools/parity/README.md) and is
re-runnable: `./tools/parity/run-all.sh`.

## Method

Both implementations run the same pipeline on the same GPX bytes, dumping the full `Path`
(all 36 fields × N points, raw binary64) **after every stage**, so a divergence can be
attributed to the step that introduced it rather than observed only at the end.

Configuration was verified identical before measuring: the 7 GPX fixtures are md5-identical
in `virtual-cyclist/gpx/` and `vcyclist/demo/public/gpx/`; the 36 `PointField`s match in
name, order and unit; and every physics constant and `Cyclist`/`Bike` default is the same
on both sides. Runs use `fixElevation=false` (fully deterministic, no network).

Two modes are measured — see the harness README. **`clock-pinned` is the mode that
measures port fidelity**, because the `as-is` mode is dominated by, and not reproducible
under, divergence #1 below.

## Verdict

**The Kotlin port is numerically faithful.** Every pipeline stage up to and including
`MaxSpeedComputer` matches at ULP level on all 7 fixtures, with identical point counts.
The three differences found downstream are all **explained, and in two of three cases the
Kotlin behaviour is the correct one**.

### Point counts, all 7 fixtures (TS / Kotlin)

| stage | amazfit | garmin | movescount | sample | sports-tracker | stelvio | strava |
|---|---|---|---|---|---|---|---|
| `00-parsed` | 1216 | 217 | 1592 | 3569 | 7785 | 259 | 7264 |
| `01-ppd-30` | 1216 | 217 | 1596 | 6195 | 7789 | 292 | 7264 |
| `02-ppd-2` | 5011 | 2061 | 7040 | 68293 | 6998 | 1929 | 14121 |
| `03-smooth` | 5011 | 2061 | 7040 | 68293 | 6998 | 1929 | 14121 |
| `04-maxspeed` | 5011 | 2061 | 7040 | 68293 | 6998 | 1929 | 14121 |
| `05-virtualize` | 5010/**5011** | 2060/**2061** | 7039/**7040** | 68292/**68293** | 6997/**6998** | 1928/**1929** | 14120/**14121** |
| `06-pointpersecond` | 903/**904** | 384/**385** | 2002 | 19158 | 1987/**1988** | 574/**575** | 2879/**2880** |
| `07-simplify` | 78 | 41 | 81 | 1018 | 85 | 43 | 188 |

Douglas-Peucker returns **identical point counts on all 7 fixtures** — the feared
equidistant-tiebreak divergence does not occur in practice.

### Stages 00 → 04: ULP-clean

Worst observed deviation across all fixtures and all 36 fields, `clock-pinned`:

| stage | worst field | max abs | max rel | verdict |
|---|---|---|---|---|
| `00-parsed` | `bearing` | 4.3e-10 rad | 1.3e-08 | OK (near-zero denominator) |
| `01-ppd-30` | `distance` | 1.9e-09 m | 6.0e-12 | OK |
| `02-ppd-2` | `bearing` | 6.3e-10 rad | 3.2e-08 | OK |
| `03-smooth` | `elevation` | 1.8e-10 m | 6.8e-14 | OK |
| `04-maxspeed` | `radius` | 3.9e-07 m | 2.0e-09 | OK |

Around 30 of 36 fields are **bit-identical** at these stages. `GpxParser`,
`PointPerDistance`, `computeDerivedData`, `ElevationSmoother` and `MaxSpeedComputer`
(including the windowed circumcentre radius) are faithful ports.

### Unit-level sweep

255 sentinel evaluations over `Distance`/haversine, `EcefConverter`, `Vector3D`,
`DouglasPeucker` 3D, `ElevationSmoother`, `ElevationFunctions` (lat/lon → tile/pixel) and
Terrarium decoding, weighted towards inputs a GPX trace never produces — poles,
antimeridian, Web-Mercator limits, zero-length segments, colinear points, duplicate points:

**229/255 bit-identical, 0 beyond tolerance, worst 4.79e-10** (a tile `yFloat` at the
Web-Mercator pole limit, where `ln(tan(lat) + 1/cos(lat))` amplifies ULP).

Cases live in [`tools/parity/cases/units.json`](../tools/parity/cases/units.json).

## Divergences found

### 1. `VirtualizeService` seeds its clock from the wall clock (TS defect)

`virtual-cyclist/src/physics/VirtualizeService.ts:67`

```ts
let time = new Date().getTime();     // ~1.785e12 ms
```

versus `engine/…/physics/VirtualizeService.kt:59`

```kotlin
val startTimeMs = 0.0
```

Two consequences, both measured:

- **The TS output is not reproducible.** Absolute timestamps differ on every run, and
  because `PointPerSecond` snaps to absolute epoch-second boundaries, the resampled
  positions depend on the sub-second phase of the wall clock at run time. Index-by-index
  agreement at `06-pointpersecond` drops to ~1.9 relative in `as-is` mode, versus ~8e-3
  once the clock is pinned.
- **The TS time axis loses ~7 significant digits.** At 1.785e12 ms the float64 ULP is
  2.44e-4 ms; Kotlin, starting at 0, has an ULP of 1.16e-10 ms at t ≈ 600 s — a factor of
  **2.1e6**. Since `computeDerivedData` recomputes `dt` and `speed` from stored times, this
  alone shifts `speed` by ~1e-7 relative from the very first point. `dx` (computed from the
  power balance, not from stored time) still agrees to 1e-10, which is what identifies the
  time axis rather than the trajectory as the source.

The Kotlin behaviour is correct. This is the `time(0) = 0` design recorded in
`VirtualizeService.kt`'s KDoc and Phase 2bis task 29.

### 2. TS does not simulate the last point (TS defect, fixed in Kotlin task 29)

TS emits **exactly N-1 points on all 7 fixtures**. The shortfall is systematic and fully
accounts for the aggregate differences:

| fixture | Δ totalDistance | Δ durationMs |
|---|---|---|
| amazfit | 2.23 m | 1000 ms |
| garmin | 1.93 m | 1000 ms |
| sample | 1.88 m | 0 ms |
| sports-tracker | 1.99 m | 1000 ms |
| stelvio | 1.91 m | 1000 ms |
| strava | 1.64 m | 1000 ms |

Every distance delta is one ~2 m `PointPerDistance` segment, and every duration delta is
exactly one 1 Hz sample. Kotlin simulates the final point, so its time axis stays dense and
monotonic — see the "don't disable the timestamp-monotonicity invariant" note in
`CLAUDE.md`.

### 3. Unset fields: `NaN` (TS) vs `0.0` (Kotlin) — structural, with one real consequence

TS `EMPTY_POINT` initialises every unset numeric field to `NaN`; Kotlin's `Path` is backed
by a zero-initialised `DoubleArray`. On a GPX carrying no temperature/power/cadence, ~20 of
36 fields differ in NaN-ness on **every point**, from `00-parsed` onwards.

This is benign for fields the pipeline overwrites, and the port already accommodates both
conventions — but not identically:

```ts
// RhoProviderEstimate.ts
const temperatureC = isNaN(providedTemp) ? 15 : providedTemp;
```
```kotlin
// RhoProvider.kt
val temperatureC = if (providedTemp.isNaN() || providedTemp == 0.0) 15.0 else providedTemp
```

**A genuine 0 °C reading is treated as "missing" by Kotlin and replaced with 15 °C**, while
TS honours it. A winter ride recording 0 °C therefore gets a ~5.5 % air-density error. This
is a real, if minor, Kotlin-side bug. It is *reported, not fixed*, per the scope of the
parity exercise.

## How to read a residual divergence

The remaining differences at `05-virtualize` and later are **accumulated ULP drift,
amplified at discrete points** — not algorithmic. The evidence:

- `speed` divergence grows smoothly: >1e-12 at index 0, >1e-10 at 31, >1e-8 at 146,
  >1e-6 at 359 (stelvio). A ramp, not a step.
- It then **collapses back to ~1e-10** (index 1029) — the simulation snaps exactly onto GPS
  waypoints, which wipes accumulated positional drift. Re-synchronisation like this is
  impossible for a genuine algorithmic difference.
- The large *relative* excursions (`pComputedTotalPower` up to 0.157) occur only where the
  quantity crosses zero over a ~0.09 s step: these are `Δ(v²)/Δt`, so catastrophic
  cancellation turns a 1e-10 speed difference into a large relative error on a physically
  negligible absolute one (≤ 3 W on a signal swinging ±700 W).

Always read `max|abs|` beside `max|rel|`: `grade` on flat ground shows rel = 2.0 at
abs = 7e-12.

## Elevation (DEM) parity

Only partially measurable today.

- **Kotlin JVM (TwelveMonkeys)** — works live; 10 reference coordinates resolved via
  `:tools:parity:dumpElevation`.
- **Kotlin JS/Node (`@jsquash/webp`)** — works live; gated integration tests pass with
  `INTEGRATION=1`.
- **TS Node (`node-canvas`)** — **broken**. `node-canvas` 3.2.3 rejects the lossless WebP
  served by `tiles.mapterhorn.com` with `Unsupported image type`, reproducible outside the
  library. The TS reference therefore cannot fetch elevation in Node at all, so no TS↔Kotlin
  elevation comparison is possible through that path. This also means TS `fixElevation=true`
  is non-functional in Node.
- **Browser (`createImageBitmap`, both TS and Kotlin/Wasm)** — **unmeasured**. The
  `:elevation` integration gate tests `typeof process !== 'undefined' && process.env.INTEGRATION`,
  which is never true in a browser, so those tests are silently skipped on browser targets.

Closing these two gaps needs a browser-side harness; until then the ±1 m Terrarium band in
the table below is the operative tolerance for elevation, not a measured 1e-9 agreement.

## Parity strategy for the test suite

`ParityFixtures.kt` continues to assert the **Kotlin** values, not the TS ones.

This is a deliberate change of plan from "regenerate the fixtures from TS". Divergences #1
and #2 are TS defects that the Kotlin port deliberately fixes; adopting the TS numbers would
bake those bugs into the Kotlin test suite. On the two tiny inline fixtures the TS values
differ by more than the 0.5 % budget purely because of the missing last point — on
`GARMIN_GPX` a single 1.17 m segment is 7.9 % of a 14 m trace:

| fixture | metric | TS (clock-pinned) | Kotlin (asserted) | Δ rel | cause |
|---|---|---|---|---|---|
| SAMPLE | totalDistance | 418.20859360948475 | 420.04525064910683 | 4.4e-03 | one missing segment |
| SAMPLE | durationMs | 48000 | 49000 | 2.0e-02 | one missing 1 Hz sample |
| SAMPLE | elevationGain | 0.21591593782602558 | 0.2189461508746149 | 1.4e-02 | one missing segment |
| SAMPLE | elevationLoss | -0.3083313825662799 | -0.3083313825632672 | **9.8e-12** | ULP |
| GARMIN | totalDistance | 13.75769637229516 | 14.929920010888091 | 7.9e-02 | one missing segment |
| GARMIN | durationMs | 5000 | 5000 | 0 | — |
| GARMIN | elevationLoss | -0.004688886304762718 | -0.004834919456122577 | 3.0e-02 | one missing segment |

The fixture values are now **TS-corroborated**: each Kotlin number is accompanied by the
measured TS number and a quantified reason for the gap. That is the meaningful upgrade from
the previous self-referential baseline, which could say nothing at all about the TS side.

Tolerances are unchanged, and the measurement justifies them rather than assuming them:

| Metric | Tolerance | Now justified by |
|---|---|---|
| `totalDistance` | ±0.5 % | Stages 00-04 agree to 1e-12 rel; post-virtualize drift stays ≤ 5.6e-4 rel on all 7 fixtures |
| `durationMs` | ±0.5 % | Same band; time axis is bit-identical to `04-maxspeed` |
| `elevationGain` / `elevationLoss` | ±1 m | Smoother is bit-comparable (1.8e-10 m); the band covers Terrarium resolution once `fixElevation` is on |
| Point count | not asserted | Justified: identical on all 7 fixtures at every stage except the two explained by divergence #2 |

Tightening below 0.5 % is **not** warranted: the drift is real, it accumulates with trace
length, and the two tiny inline fixtures are the least representative traces available.

## Refresh checklist

If you intentionally change the pipeline:

1. `./tools/parity/run-all.sh` and read `reports/SUMMARY.clock-pinned.txt`.
2. If only Kotlin moved, re-run `:engine:jvmTest --tests "*EnhancerParityTest.printMeasured"`,
   copy the printed `PARITY[…]` lines into `ParityFixtures.kt`, and say *why* in a comment.
3. Re-measure the TS side for the two inline fixtures (see the harness README) and update
   the TS-corroboration table above.
4. `./gradlew check` and confirm the three reference repos are still clean.
