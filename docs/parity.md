# Engine parity — TS ↔ Kotlin measurement, strategy & tolerances

## What this document is

The Kotlin `:gpx` / `:engine` / `:elevation` modules are a port of the TypeScript
`virtual-cyclist` and `elevation` libraries. This document records the **measured**
numerical relationship between the two implementations, the divergences found, and the
parity strategy that follows from them.

Measured on all 7 sample GPX files, stage by stage across the whole `Enhancer` pipeline.
Re-measured 2026-08-17 against **`virtual-cyclist` 1.3.1** (`046688e`), the release that
carries the same constant corrections as this port (`G = 9.80665`, wheel **radius** 0.35 m,
`maxBrakeG` 0.4 — see [`docs/research/07`](research/07-vcyclist-implementation-notes.md#72-concrete-findings-to-act-on)).
**No divergence class changed**: every field-level verdict is the same as the 2026-07-28 run
against 1.3.0 (`6bf256e`) / `elevation` 3.2.3, which was itself byte-identical to the
2026-07-27 baseline. Both sides moved together, as intended. Only the point counts shifted —
the simulated rides are slightly slower, so they yield a few more 1 Hz samples — and one
consequence of that is recorded under Douglas-Peucker below. The 2026-08-17 sweep ran
`clock-pinned` only (the fidelity mode) and skipped the DEM sweep, which these constants do
not touch.

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
Four divergences are documented below; all are **explained, and in three of four the Kotlin
behaviour is the correct one**. Divergence 4 concerns the DEM path, which the `Enhancer`
pipeline sweep does not exercise (`fixElevation=false`), so it does not affect any figure in
this section.

### Point counts, all 7 fixtures (TS / Kotlin)

| stage | amazfit | garmin | movescount | sample | sports-tracker | stelvio | strava |
|---|---|---|---|---|---|---|---|
| `00-parsed` | 1216 | 217 | 1592 | 3569 | 7785 | 259 | 7264 |
| `01-ppd-30` | 1216 | 217 | 1596 | 6195 | 7789 | 292 | 7264 |
| `02-ppd-2` | 5011 | 2061 | 7040 | 68293 | 6998 | 1929 | 14121 |
| `03-smooth` | 5011 | 2061 | 7040 | 68293 | 6998 | 1929 | 14121 |
| `04-maxspeed` | 5011 | 2061 | 7040 | 68293 | 6998 | 1929 | 14121 |
| `05-virtualize` | 5010/**5011** | 2060/**2061** | 7039/**7040** | 68292/**68293** | 6997/**6998** | 1928/**1929** | 14120/**14121** |
| `06-pointpersecond` | 903/**904** | 385 | 2004 | 19168/**19169** | 1996 | 576/**577** | 2882/**2883** |
| `07-simplify` | 79 | 39 | 80 | 1014 | 83/**85** | 43 | 187 |

Douglas-Peucker returns identical point counts on **6 of 7** fixtures. On `sports-tracker` it
now returns **83 (TS) vs 85 (Kotlin)**. This is new as of the 2026-08-17 sweep and is *not* a
port defect : that fixture is the one where the 1 Hz stage now agrees exactly (1996 both
sides, where it used to be 1987/1988), so the two sides feed DP inputs that differ only by
ULP-scale noise, and DP's keep/drop decision is a threshold on those values — a point sitting
within ULPs of the 10 m tolerance falls on either side of it. The feared equidistant-tiebreak
divergence is therefore real but confined to points that are, by construction, at the
tolerance boundary.

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

### 1. `VirtualizeService` defaults its clock to the wall clock (TS defect, now overridable)

Up to `virtual-cyclist` 1.2.x, `src/physics/VirtualizeService.ts:67` had no way out:

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
  once the clock is pinned. Two full `as-is` sweeps a day apart disagree on the **point
  counts themselves** — `sample` `07-simplify` 1017 then 1022, `strava` 191 then 193,
  `sports-tracker` 85 then 84, `movescount` `06-pointpersecond` 2002 then 2003 — while the
  Kotlin column is identical throughout. The `clock-pinned` dumps of the same two sweeps
  are byte-identical.
- **The TS time axis loses ~7 significant digits.** At 1.785e12 ms the float64 ULP is
  2.44e-4 ms; Kotlin, starting at 0, has an ULP of 1.16e-10 ms at t ≈ 600 s — a factor of
  **2.1e6**. Since `computeDerivedData` recomputes `dt` and `speed` from stored times, this
  alone shifts `speed` by ~1e-7 relative from the very first point. `dx` (computed from the
  power balance, not from stored time) still agrees to 1e-10, which is what identifies the
  time axis rather than the trajectory as the source.

The Kotlin behaviour is correct. This is the `time(0) = 0` design recorded in
`VirtualizeService.kt`'s KDoc and Phase 2bis task 29.

**Mitigated upstream in `virtual-cyclist` 1.3.0** (`8532ba0`, *feat: allow custom start time
for track virtualization*): `virtualizeTrack(course, startTime)` takes an optional start
timestamp, surfaced as `EnhanceOptions.startTime`, and only falls back to `new Date()` when
it is null or undefined. A caller can now opt into a reproducible time axis.

The default is unchanged, so the defect still bites anyone who does not pass the option —
which is why this divergence stays documented rather than closed. What it does change is the
harness: `clock-pinned` mode used to monkey-patch `globalThis.Date`, and now passes
`startTime = 0` through the public API. All 8 stage dumps are byte-identical across that
switch, so no measurement in this document moved.

Kotlin has no equivalent option: `VirtualizeService.kt` always starts at 0. Adding one would
be an API-parity item, not a correctness one.

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
| strava | 1.63 m | 1000 ms |

Every distance delta is one ~2 m `PointPerDistance` segment, and every duration delta is
exactly one 1 Hz sample. Kotlin simulates the final point, so its time axis stays dense and
monotonic — see the "don't disable the timestamp-monotonicity invariant" note in
`CLAUDE.md`.

### 3. Unset sensor fields: `NaN` (TS) vs `0.0` (Kotlin) — **fixed**

**TS has two conventions, not one.** Its backing store is zero-initialised, exactly like
Kotlin's:

```ts
// AbstractPath.ts:69
this.chunks.push(new Float64Array(this.CHUNK_SIZE * FIELDS_PER_POINT));
```

`EMPTY_POINT` — the all-`NaN` literal in `Point.ts` — only reaches the store through
`GPXParser.ts:104` (`{ ...EMPTY_POINT }` → `addPoint`, which writes all 36 slots, `NaN`
included). So **parser-created points carry `NaN` on unset fields, while every point a
resampler creates** (`PointPerDistance`, `PointPerSecond`, `PathSimplifier` — i.e. stages
`01` through `07`) **lands on raw zeroed memory and reads `0.0`.**

Kotlin's zero-initialised `DoubleArray` matched the second convention and not the first. The
harness sizes it: before the fix, `temperature` / `cadence` / `heartRate` / `pInputPower`
accounted for 1 278 402 of the 1 774 326 NaN-mismatched values across the 7 fixtures — 72 %
of the total.

The port papered over it with `== 0.0` sentinels, which do not agree with TS:

```ts
// RhoProviderEstimate.ts
const temperatureC = isNaN(providedTemp) ? 15 : providedTemp;
```
```kotlin
// RhoProvider.kt — before the fix
val temperatureC = if (providedTemp.isNaN() || providedTemp == 0.0) 15.0 else providedTemp
```

A genuine 0 °C reading was treated as "missing" and replaced with 15 °C, giving a winter
ride a ~5.5 % air-density error. The same sentinel dropped a 0 rpm (freewheeling) cadence
and a 0 W sample from the GPX and FIT writers.

**Resolution — mirror both TS conventions.** The backing array stays zero-initialised, and
`GpxToPath` writes `Double.NaN` explicitly for the sensor fields the source GPX did not
carry (`temperature`, `pInputPower`, `heartRate`, `cadence`). Absence is now a value, so
every `== 0.0` sentinel could go:

- `RhoProvider.kt` — falls back to 15 °C on `NaN` only.
- `GpxFromPath.kt` — heart rate / cadence / temperature / power emitted whenever not `NaN`;
  a `NaN` `time(i)` emits no `<time>` at all, matching `GPXWriter.ts`'s `!isNaN` guard
  (it would otherwise throw on `roundToLong`).
- `PathToFit.kt` — `optionalSensor()` guards on `NaN` only. `pComputedPower` keeps a
  `== 0.0` guard under a separate `optionalComputed()`: it is a *computed* field, so nothing
  ever marks it absent, and a path that skipped `VirtualizeService` would otherwise encode a
  flat 0 W line.

Measured effect (`clock-pinned`, 7 fixtures): those four fields drop to **0** mismatched
values and **no other field moves**, total 1 774 326 → 495 924. Verdict census
`EXACT` 748 → 891, `NAN-MISMATCH` 349 → 173.

Two aggregates also shifted, both **towards** TS — `movescount` `elevationGain`
504.907138 → 504.906451 (TS: 504.906035) and `strava` 630.922769 → 630.922922 (TS:
630.922960). Cause: `movescount` and `strava` carry `<atemp>` on most points but not the
leading ones. Kotlin used to read `0.0` there and `PointPerDistance` interpolated a
fabricated `0 → 20.8 °C` ramp into `RhoProviderEstimate`; TS interpolated `NaN → NaN` and
fell back to 15 °C. The two now agree.

The remaining `00-parsed` mismatches (154) are the mirror image, and are the accepted cost:
TS's parser NaN-fills the *computed* slots too (`wind*`, `p*`, `radius`, …) where Kotlin
reads `0.0`. Aligning those would re-break stages `01`–`07`, per the warning below.

> **A blanket NaN-init is the wrong fix, and the harness proves it.** Filling the whole
> `DoubleArray` with `NaN` clears the 1.28 M sensor mismatches but breaks the resampler
> convention: every computed field (`wind*`, `p*`, `radius`, `speedMax*`, `aeroCoef`,
> `virtSpeedCurrent`) jumps from ~22 k to 235 k–476 k mismatched values, because TS reads
> `0.0` there and Kotlin would read `NaN`. Measured totals: 1 774 326 → **7 418 811**. Stage
> `00-parsed` improves (176 → 7) at the cost of all seven downstream stages (e.g. `01-ppd-30`
> 29 → 154). Don't "simplify" this to one convention.

### 4. TS elevation "bilinear interpolation" is a floor-pixel lookup (TS defect)

`elevation`'s `ElevationProvider` documents and advertises bilinear interpolation from the
four neighbouring DEM pixels. It computes a nearest-pixel lookup instead. The four-tap sum is
present, fetched, and then multiplied by zero.

`ElevationFunctions.ts:124-125` — `toPixel` already floors:

```ts
const x = Math.floor((tile.xFloat - tile.x) * tileSize);
const y = Math.floor((tile.yFloat - tile.y) * tileSize);
```

`ElevationCalculator.ts:52-58` — then derives the sub-pixel offsets from those integers:

```ts
const x0 = Math.floor(pixelFloat.x);   // pixelFloat.x is already an integer
const dx = pixelFloat.x - x0;          // therefore always exactly 0
```

So the weights collapse to `(1-dx)(1-dy) = 1` on `p00` and `0` on `p10`/`p01`/`p11`. The
fractional position needed for the interpolation is discarded one call before it is used.

Measured on the 10 `ELEVATION_COORDS` against `elevation` 3.2.3 as shipped — the first run
possible at all, since the TS Node decoder had to be repaired first:

| | |
|---|---|
| coordinates bit-identical | **2 / 10** |
| max abs delta | **8.5947 m** (`elevation.5` — TS 2717.13, Kotlin 2708.5352887058) |
| max rel delta | **6.41e-02** (`elevation.7` — TS 8.5, Kotlin 9.0819060612) |
| bar per `ElevationDump.kt` | `~1e-9` |

Independent corroboration without any tolerance argument: `ELEVATION_COORDS[2]` and `[3]` are
~2 m apart and TS returns the *identical* value `2759.23` for both — the signature of a
pixel-snapped lookup, not of interpolation. (`Tile.ts:50` also rounds to 2 decimals, which is
a separate, smaller quantisation.)

The Kotlin port already diverges here deliberately, and says so —
`elevation/…/ElevationCalculator.kt:10-13` keeps the true fractional position from
`toPixelFloat` and notes that the TS original degenerates into nearest-neighbour. What was
missing was the measurement. **Kotlin is correct; this is a TS defect**, like divergences 1
and 2.

Consequence for anyone repairing the TS Node decoder: fixing the decoder alone will *not*
bring the two sides to `1e-9`. A Definition of Done phrased as "the ten values agree to
~1e-9 once a working decoder lands" is unreachable — `toPixel`/`dx` must be fixed too, or the
criterion relaxed to the floor-pixel semantics.

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

Four of the five decoder stacks are now measured. Only the TS browser path has no harness.
The TS Node path was non-functional until `elevation` 3.2.3 replaced node-canvas with sharp;
it is now measured **as shipped**, and that first measurement is what produced divergence 4.

| Runtime | Decoder | State |
|---|---|---|
| Kotlin JVM | TwelveMonkeys `imageio-webp` | **measured** — `:tools:parity:dumpElevation` resolves the 10 reference coordinates live |
| Kotlin JS / Node | `@jsquash/webp` (WASM) | **measured** under `INTEGRATION=1` (`TileFetcherJsIntegrationTest`) |
| Kotlin JS / browser | `createImageBitmap` + canvas | **measured** — reproduces the JVM RGBA digest byte-for-byte, see below |
| TS Node | `sharp` (libvips + libwebp) since `elevation` 3.2.3 | **measured** — as shipped, no harness shim |
| TS browser | `createImageBitmap` + canvas | **unmeasured** — no harness |

### node-canvas cannot decode WebP at all

The earlier diagnosis in this document ("rejects the *lossless* WebP") was wrong, and it
mattered: it suggested a lossy/lossless distinction and therefore a server-side workaround.
`node-canvas` 3.2.3 links **no libwebp whatsoever**, so it decodes no WebP of any kind.
Observed in `../elevation/node_modules/canvas/build/Release/canvas.node`:

- `ldd` lists 30 shared objects — pixman, cairo, libpng16, libjpeg.62, libgif.7, librsvg,
  pango, harfbuzz, freetype, glib … — and **no `libwebp`**; `ldd … | grep -i webp` exits 1.
  `strings … | grep -ci webp` returns `0`, so there is no statically-linked copy either.
- The module's exports are `Canvas, Context2d, …, PNGStream, PDFStream, JPEGStream, …,
  cairoVersion, jpegVersion, gifVersion, freetypeVersion, rsvgVersion, pangoVersion` —
  a stream and a version probe per supported codec, none for WebP.
- `loadImage(fs.readFileSync('tile.webp'))` fails with `Unsupported image type`. That is
  cairo's generic "no loader matched the magic bytes", not a lossless-specific rejection.

There is no PNG fallback to switch to: `curl -o /dev/null -w '%{http_code}'
https://tiles.mapterhorn.com/12/2094/1467.png` → **404**, while the `.webp` variant returns
`200 image/webp`, 335 460 bytes, `RIFF`/`WEBP`/`VP8L` (512×512). So TS `fixElevation=true`
was non-functional in Node up to `elevation` 3.2.2.

> **Fixed upstream in `elevation` 3.2.3** (`33c7ecc`, *fix: decode WebP tiles in Node.js with
> sharp*): the node-canvas decode is replaced by `sharp` (libvips + libwebp), fetching the
> tile bytes and decoding straight to raw RGBA with no canvas round-trip — unpremultiplied,
> no colour management, no resample, with a guard that fails loudly rather than returning
> silently wrong metres. `node-canvas` is kept only for `ImageData`. The paragraphs above are
> retained as the diagnosis, not as the current state.
>
> The harness carries **no decoder shim**: the sweep exercises the shipped chain. An earlier
> substitution used to obtain a first reading was removed once 3.2.3 landed, and produced
> byte-identical elevations to the shipped decoder on all 10 coordinates — so divergence 4 is
> attributable to the Terrarium chain and not to either decoder.

### The browser path is now covered — byte-exactly

The gap used to sit here: on Kotlin/JS the gate `NodeIntegrationGate.kt` tested
`process.env.INTEGRATION`, which is false on a browser test page — so `jsBrowserTest` skipped
silently and reported green. That is closed:

- The gate is now `commonTest/…/IntegrationGate.kt` (`expect fun integrationEnabled()`) with
  a per-target `actual`. The browser one reads a Karma-injected flag
  (`elevation/karma.config.d/integration.js`), driven by the same `INTEGRATION=1` the Node
  and JVM paths already used. A skipped integration test now *prints* that it skipped.

The assertion is deliberately not "the elevation looks plausible" — that would mask the exact
corruption described below. `ReferenceTileDigestTest` freezes the SHA-256 of the decoded RGBA
of one reference tile, measured on the JVM through TwelveMonkeys, and asserts every target
reproduces it. **The browser target matches bit-for-bit**, so `createImageBitmap` + canvas
returns the same bytes as TwelveMonkeys on this tile in ChromeHeadless.

Two limits on what that proves, which matter more than the result:

- **It cannot detect premultiplication.** The reference tile is fully opaque (alpha `255`
  everywhere), and premultiplying by `255` is the identity map. So the digest test can only
  catch a `colorSpaceConversion` regression; the `premultiplyAlpha` branch is untestable by
  construction with this fixture. The honest statement is that premultiplication is a no-op
  on today's opaque tiles — *not* that Chrome does not premultiply. `ImageBitmapOptions` is
  still not passed at either call site, and that decision rests on tile properties rather
  than on the API contract.
- It covers ChromeHeadless on Linux only. Firefox and Safari are unmeasured.

`ReferenceTile.RGBA_SHA256` is a live-network fixture: an upstream re-render of the tile turns
every `INTEGRATION=1` run red until it is re-measured (the command is in its KDoc).
`./gradlew check` stays offline and is unaffected.

### The concrete risk: silent RGB corruption, not an exception

The browser path decodes through `createImageBitmap(blob)` → `drawImage` → `getImageData`:

- `elevation/src/jsMain/kotlin/io/github/glandais/elevation/TileFetcher.js.kt:75` (browser branch)

It does not pass an `ImageBitmapOptions`, so `premultiplyAlpha` and `colorSpaceConversion` are
left at `"default"` — implementation's discretion. Terrarium packs elevation into the RGB
bits (`ele = R*256 + G + B/256 - 32768`), so any premultiplication against a non-opaque alpha,
or any colour-space transform of the RGB triple, changes the decoded metres with no error
raised: no exception, just wrong elevation. A one-LSB change in R is 256 m.

The tiles sampled so far are opaque — `VP8L` header `alpha_is_used = 0` on the four tiles
checked, and PIL reports alpha extrema `(255, 255)` on `12/2094/1467.webp` — which makes
premultiplication a no-op *for those bytes*. Nothing in the code depends on that being true:
it is a property of today's tiles, not of the API contract. The digest test above inherits
that limitation rather than removing it; pinning the `premultiplyAlpha` behaviour down needs
a fixture with a real alpha plane, which no Terrarium tile has provided so far.

The browser harness was not blocked by CORS: `curl -D - -H 'Origin: http://localhost:9876'
https://tiles.mapterhorn.com/12/2094/1467.webp` returns `HTTP/2 200` with
`access-control-allow-origin: *`, so a Karma test page fetches real tiles directly.

### Two different tolerances, not one

The `±1 m` band in the tolerance table below is **Terrarium tile resolution** — noise between
different elevation data sources. It is *not* the acceptance bar between two decoders. The
requirement there is ~`1e-9`, as the KDoc of
`tools/parity/src/main/kotlin/io/github/glandais/parity/ElevationDump.kt` already states:

> On the same decoded tile the two must agree to ~1e-9, not to the ±1 m Terrarium
> resolution : a metre-scale gap would mean a decode or bilinear-interpolation bug, not
> tile noise.

Reading the `±1 m` band as the elevation-decoder tolerance would accept an 80 cm disagreement
between two decoders reading identical bytes — which would be a bug, not noise. The four
Kotlin stacks now clear a stricter bar than `1e-9`: they agree on the raw RGBA *bytes*. The
`~1e-9` figure is the bar the TS↔Kotlin comparison must be held to once the TS Node decoder
lands — and divergence 4 below is what it caught the first time it could be run at all.

## Parity strategy for the test suite

`ParityFixtures.kt` continues to assert the **Kotlin** values, not the TS ones.

This is a deliberate change of plan from "regenerate the fixtures from TS". Divergences #1
and #2 are TS defects that the Kotlin port deliberately fixes; adopting the TS numbers would
bake those bugs into the Kotlin test suite. On the two tiny inline fixtures the TS values
differ by more than the 0.5 % budget purely because of the missing last point — on
`GARMIN_GPX` a single 1.17 m segment is 7.9 % of a 14 m trace:

Re-measured 2026-08-17 against `virtual-cyclist` 1.3.1, both sides carrying the corrected
constants:

| fixture | metric | TS (clock-pinned) | Kotlin (asserted) | Δ rel | cause |
|---|---|---|---|---|---|
| SAMPLE | totalDistance | 418.2189961559547 | 420.0556496172967 | 4.4e-03 | one missing segment |
| SAMPLE | durationMs | 49000 | 49000 | **0** | was 2.0e-02 — see below |
| SAMPLE | elevationGain | 0.21471861131141168 | 0.21774882435903464 | 1.4e-02 | one missing segment |
| SAMPLE | elevationLoss | -0.307134056051666 | -0.30713405604768695 | **1.3e-11** | ULP |
| GARMIN | totalDistance | 13.75769637229516 | 14.929920010888091 | 7.9e-02 | one missing segment |
| GARMIN | durationMs | 5000 | 5000 | 0 | — |
| GARMIN | elevationLoss | -0.004688886304762718 | -0.004834919456122577 | 3.0e-02 | one missing segment |

`SAMPLE.durationMs` now agrees **exactly**, where it used to differ by one 1 Hz sample: the
slightly slower simulated ride pushes the TS side over the same second boundary the Kotlin
side was already past. The remaining gaps are unchanged in both size and cause — this is the
missing-last-point divergence (#2), not anything the constants touched.

The Kotlin `SAMPLE` numbers moved (distance 2.5e-05, gain 5.5e-03, loss 3.9e-03 rel) and were
refreshed in `ParityFixtures.kt`. Note **which test caught it**: `EnhancerParityTest` checks
elevation gain against an absolute ±1 m band, so a 5.5e-03 relative drift on a 0.22 m value is
invisible to it. `tools/wasi/test_engine.py` reads the same fixture and checks it at ±0.5 %
relative, and failed. The WASI host and the JVM agree to the digit — only the fixture was stale.

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
