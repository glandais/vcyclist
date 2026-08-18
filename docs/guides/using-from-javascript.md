# Using vcyclist from JavaScript / TypeScript

Two npm packages carry the Kotlin/JS build, and both run **in the browser and in Node.js / Bun**:

```bash
npm install @glandais/vcyclist-engine      # physics, GPX, FIT, CSV/JSON, climbs, racing line
npm install @glandais/vcyclist-elevation   # DEM tile lookups on their own
```

`:engine` declares `api(project(":elevation"))`, so `@glandais/vcyclist-engine` already carries the
elevation façade — install the second package only if you want DEM lookups *without* the physics.

`generateTypeScriptDefinitions()` is enabled on `js(IR)`, so a `.d.ts` ships next to each bundle
(`build/dist/js/productionExecutable/vcyclist-engine.d.{ts,mts}`).

## Getting at the exports

Kotlin/JS emits a **UMD bundle that preserves the package namespace**, so the module has exactly
one top-level export, `io`. Named imports do not work — `import { parseGpx } from
'@glandais/vcyclist-engine'` fails under Node ESM with *"Named export 'parseGpx' not found"*.
Unwrap the namespace once and destructure from it:

```js
import * as engineRaw from '@glandais/vcyclist-engine';   // or: require('@glandais/vcyclist-engine')

const engine    = engineRaw.io.github.glandais.engine;
const elevation = engineRaw.io.github.glandais.elevation;   // the :elevation façade, same bundle
```

Every snippet below assumes that `engine`. [`demo/src/engine-shim.ts`](../../demo/src/engine-shim.ts)
and [`demo/src/elevation-shim.ts`](../../demo/src/elevation-shim.ts) do the same unwrap and add
TypeScript types for the DTOs, which Kotlin/JS emits as referenced names only. They are worked
examples of everything here — and a warning: those types are **hand-written**, so a rename on the
Kotlin side stays silent until runtime.

## Parsing

```js
const { parseGpx, parseGpxTracks, parseGpxSegments, parseGpxWaypoints } = engine;

const path = parseGpx(gpxXml);          // the FIRST track — what most files contain
```

For documents with several `<trk>` or several `<trkseg>`:

```js
const tracks   = parseGpxTracks(gpxXml);     // one path per <trk> *and* per <rte>
const segments = parseGpxSegments(gpxXml);   // one path per <trkseg>, always continuous
const points   = parseGpxWaypoints(gpxXml);  // <wpt> elements as {latitude, longitude, …}
```

Concatenating segments folds the pause between them into `totalDistance` — a `<trkseg>` boundary
is a physical discontinuity. Use `parseGpxSegments` when that artefact matters.

`parseGpxTracks` also returns `<rte>` routes, which many planners emit and which used to be
dropped silently. `parseGpxTracksOnly` and `parseGpxRoutesOnly` select one container or the other.

## Metrics and fields

```js
const { pathSize, pathTotalDistance, pathDurationMs, pathElevationGain, pathElevationLoss,
        pathLatitudeDeg, pathLongitudeDeg, pointAt, getField, fieldDefinitions } = engine;

pathSize(path);                     // number of points
pathTotalDistance(path);            // metres
pathDurationMs(path);               // milliseconds — `time` is the only ms field in the engine
pathElevationGain(path);            // metres; pathElevationLoss is NEGATIVE by convention

pointAt(path, 0);                   // one point as an object
getField(path, 0, 'speed');         // one field by camelCase name; throws if unknown
fieldDefinitions();                 // the 43-field catalog: prop, unit, description, category
```

`fieldDefinitions()` is what lets a UI build a generic field picker instead of hard-coding the
list — it is how the demo's chart offers every field without knowing them.

## Running the physics

### `enhance` — the quick door

```js
const { enhance, writeGpx } = engine;

const out = await enhance(path, null);                    // physics only, no HTTP
const xml = writeGpx(out);
```

With elevation correction (Node.js / Bun, or a browser that can reach the tile host):

```js
const out = await enhance(path, { fixElevation: true });  // fetches DEM tiles, decodes WebP
```

`{ fixElevation: true }` auto-instantiates a default `ElevationProvider` (mapterhorn Terrarium
tiles) and runs the full pipeline: densify → fix elevation → smooth → max speeds → virtualize →
resample → simplify.

The options object (`EnhanceOptionsDto`) is entirely optional, field by field:

| Key | Default | What it does |
|---|---|---|
| `fixElevation` | `false` | replace elevations from DEM tiles (network) |
| `computeMaxSpeeds` | `true` | cornering + braking speed limits |
| `virtualizeTrack` | `true` | the time-stepping simulation itself |
| `computeOnePointPerSecond` | `false` | resample the output to 1 Hz |
| `simplifyEnabled` / `simplifyToleranceM` / `simplifyZExaggeration` | `false` / 10 m / 3 | Douglas-Peucker 3D |
| `wPrimeBalanceEnabled` / `wPrimeBalanceCriticalPower` / `wPrimeBalanceWPrime` | `true` / 250 W / 20 kJ | the `wPrimeBalance` **output** field |
| `curvatureEnabled` | `true` | heading-regression curvature; off restores the older windowed estimate |
| `racingLineEnabled` / `racingLineCorridor` / `racingLineRoadWidthM` | `false` / `"lane"` / 6 m | optimal line through corners |

Three of those defaults — no DEM fetch, no 1 Hz resample, no simplify — are the **JavaScript
façade's**, not the engine's. Ask for them explicitly if you want the full pipeline.

`racingLineEnabled` **moves every coordinate** of the result; the originals stay in
`sourceLatitude` / `sourceLongitude`. Corridor modes are `"lane"`, `"lane-left"` and
`"full-road"` — the last is for closed roads and time trials only. Read
[`racing-line.md`](racing-line.md) before turning it on: it is off by default because the measured
benefit is small.

### `enhanceWithCourse` — the configurable door

`enhance` simulates the default rider on the default bike in no wind. `enhanceWithCourse` is the
same pipeline with all five inputs exposed:

```js
const out = await engine.enhanceWithCourse(
  path,
  { massKg: 72, cd: 0.7, frontalAreaM2: 0.5, maxLeanAngleDeg: 35, maxBrakeG: 0.4,
    maxSpeedKmH: 100, roadCondition: 'wet' },
  { crr: 0.004, inertiaFront: 0.05, inertiaRear: 0.07, wheelRadiusM: 0.35,
    efficiency: 0.976, maxPedalingLeanAngleDeg: 20 },
  { windSpeed: 5.0, windDirection: 270 },
  { type: 'critical-power', power: 280, criticalPower: 250, wPrime: 20000,
    pacing: true, maxSlewWPerS: 50 },
  { computeOnePointPerSecond: true },
);
```

Any of the five may be `null` for engine defaults. The parameters, in order:

- **`CyclistDto`** — all six numeric fields are required when the DTO is passed, plus an optional
  `roadCondition` of `"dry"` or `"wet"`. The preset sets cornering grip and braking *together*;
  wet cuts cornering speed by 1.58× and braking to 0.23 g. **The preset wins over `maxLeanAngleDeg`
  and `maxBrakeG`** — omit `roadCondition` to keep your raw values. This door's behaviour has not
  changed, but it is no longer the odd one out: the CLI used to let an explicit flag win, and was
  brought into line in the S8 alignment step. The rule now lives once, in `RoadCondition.applyTo`.
- **`BikeDto`** — `maxPedalingLeanAngleDeg` is the pedal-strike cut-off: past this lean the rider
  stops pedalling and coasts. `90` disables it.
- **`WindDto`** — `windDirection` is in degrees, `0` = north, `90` = east, naming the direction the
  wind blows **toward**. `dominantHeadwindAzimuth(path)` returns a value that goes into this field
  unchanged.
- **`PowerProviderDto`** — `type` is `"constant"`, `"durability"`, `"critical-power"` or
  `"from_data"`. `pacing` and `maxSlewWPerS` are **decorators**, not types: they compose over
  whichever `type` you chose, slew outermost. What each model is worth is measured in
  [`cli/README.md`](../../cli/README.md#--cyclist-model) and the
  [improvements ledger](../ledgers/improvements-ledger.md).
- **`EnhanceOptionsDto`** — the table above.

**Passing a key a DTO does not declare is an error.** That is deliberate: a typo in `massKg` must
not silently simulate the default rider.

## Outputs

```js
const { writeGpx, writeGpxAt, writeGpxTracks, pathToCsv, pathToJson, pathToFit, pathsToFit } = engine;

const xml   = writeGpx(out);                    // GPX 1.1, one <trk>
const bare  = writeGpx(out, false);             // no <extensions>: no power, HR, cadence
const power = writeGpx(out, true, 'computed');  // the SIMULATED power in <power>
const named = writeGpx(out, true, null, 'stelvio');   // <trk><name>, default "virtualized"
const multi = writeGpxTracks([a, b]);           // one <trk> per path
const csv   = pathToCsv(out, ',', true);        // separator, units in the header
const json  = pathToJson(out, false);           // column-oriented: one array per field
```

`writeGpx`, `writeGpxAt` and `writeGpxTracks` all take a `writeExtensions` flag (default `true`)
and a `powerSource`. `writeGpx` and `writeGpxAt` also take a `trackName`; `writeGpxAt`
additionally stamps an absolute start time.

`writeGpxTracks` takes two more: `trackNames`, which names the tracks positionally — a shorter
list, or none, leaves the rest unnamed, which is *not* the `"virtualized"` default `writeGpx` puts
on its single track — and `startTimeEpochMs`, which does what `writeGpxAt` does, to every track at
once:

```js
const dated = writeGpxTracks(tracks, [], true, 'computed', ['montee', 'descente'], Date.now());
```

One asymmetry survives: **only `writeGpxTracks` takes waypoints.** `writeGpx` and `writeGpxAt` have
no `waypoints` parameter, so a load → enhance → write round trip through them destroys every `<wpt>`
of the source file. Parse them with `parseGpxWaypoints` and write through `writeGpxTracks` if you
need them to survive.

`pathToCsv` has exactly three parameters and `pathToJson` two, so **`decimals`, `includeMeta`,
`fields` and `lineSeparator` are unreachable from JavaScript** although the core writers accept
them and the WASI door exposes three of them. Every export therefore writes all 43 `PointField`s.
Step S4 closes this.

### Which power lands in `<power>`

A `Path` carries two: `pInputPower`, read from the source file, and `pComputedPower`, what the
simulation produced. `powerSource` picks — the CLI's `--gpx-power-source`, same three spellings:

| Value | Writes |
|---|---|
| `'input'` | **the default** — what the source file said. Nothing invented. |
| `'computed'` | what the simulation produced. Empty on a path that was never virtualized. |
| `'computed-or-input'` | the simulation where it produced one, the file's value otherwise. |

The default is `'input'` on every door because `<power>` has no provenance field: a round-trip
launders a simulated value into a measured-looking one, and that is the caller's decision to make.
Pass `'computed'` or `'computed-or-input'` explicitly when you want the simulation — the browser
demo's GPX download does. An unrecognised spelling **throws**; it does not fall back.

FIT:

```js
const fit = pathToFit(out, 'My route', Date.parse('2026-08-01T08:00:00Z'));
// Kotlin/JS returns an Int8Array containing the FIT file.

const both = pathsToFit([a, b], 'Two days', Date.parse('2026-08-01T08:00:00Z'), 0);
```

FIT has no relative clock, so the start instant is **mandatory**. The output is a **Course** file
(a route to follow), which is what a virtualized trace should be — not an Activity. `pathsToFit`
encodes several paths into one course, a lap and a timer event pair each; `interPathGapMs` shifts
each path after the first, and is *not* a pause (FIT expresses those with events this port does
not emit).

## Analysis

```js
const { detectClimbs, detectClimbsWithOptions, analyzeRacingLine,
        dominantHeadwindAzimuth, dominantHeadwindAzimuthOfTracks } = engine;

const climbs = detectClimbs(out);
const tuned  = detectClimbsWithOptions(out, 10, 35, 100, 3, 1.3, 1.3);
//   minMinClimbElevationM, maxMinClimbElevationM, minClimbElevationRatio,
//   minGradePercent, maxDiffRealGrade, booster  — these are the defaults
//   `ClimbOptions` has a seventh, maxAnalysisPoints (3000, the O(n²) guard), not exposed here

const report = analyzeRacingLine(out, { racingLineCorridor: 'lane' });  // moves nothing
const worst  = dominantHeadwindAzimuth(out);                            // degrees, or NaN
```

`analyzeRacingLine` returns the racing-line report **without** applying it — corner spans,
curvature before and after, the corridor, the solver's convergence. It returns `null` when there
is nothing to analyse, and `NaN` slots arrive as `null`.

`dominantHeadwindAzimuth` returns the wind direction that makes a course hardest on average. It
says nothing about wind *speed*. It returns `NaN` when the question has no answer — fewer than
4 points, or a perfectly symmetric loop — and `0` is a valid answer, so check with `Number.isNaN`.

## DEM elevation on its own

> The elevation options are key-checked like every engine DTO: a misspelled `step` throws rather
> than being silently ignored, and the error names the offending key. It throws **synchronously**,
> at the call site, not as a rejected promise — so it does not need an `await` to be seen.

```js
const { newElevationProvider, getElevation, getElevationsAlong } = elevation;

const provider = newElevationProvider({ zoomLevel: 12, cacheSize: 100 });

const ele  = await getElevation(provider, 45.0, 6.0, true);   // last arg: bilinear interpolation
const along = await getElevationsAlong(
  provider,
  [{ latitude: 45.0, longitude: 6.0 }, { latitude: 45.1, longitude: 6.1 }],
  { step: 10, minDistance: 1, interpolation: true,
    smoothingOptions: { enabled: true, windowSize: 150 },
    filterOptions:    { enabled: true, tolerance: 10, zExaggeration: 3 } },
);
```

`newElevationProvider(null)` uses the defaults: zoom 12, a 100-tile LRU cache, and mapterhorn
Terrarium tiles at `https://tiles.mapterhorn.com/{z}/{x}/{y}.webp`. The provider is an opaque
handle — pass it back in, do not reach into it. Reuse one across calls: the tile cache lives on it.

See [`elevation/README.md`](../../elevation/README.md) for attribution and coverage.

## Node.js / Bun

Both packages run unchanged under Node and Bun. Tile decoding there uses
[`@jsquash/webp`](https://www.npmjs.com/package/@jsquash/webp) — a pure-WASM WebP decoder, ~50 KB,
listed as a runtime `dependency` of both packages. It is loaded lazily via `eval('require')`, so
browser bundlers do not pull it into the browser build, where the platform's own decoder is used
instead.

Requires **Node ≥ 18** (`globalThis.fetch` is built-in since Node 18 / Bun); Node 22+ recommended
for ESM `require()` support.

## See also

- [`kotlin-js-jvm-webp.md`](kotlin-js-jvm-webp.md) — the interop patterns behind these façades
  (`external interface`, `js("({})")` + `unsafeCast`, `GlobalScope.promise`), and WebP per target
- [`wasm-wasi-abi.md`](wasm-wasi-abi.md) — the same surface from a WASI host, with a
  function-by-function parity table in §10
- [`../ledgers/surface-coverage.md`](../ledgers/surface-coverage.md) — which option reaches which
  door (French; internal drift tracking)
- [`../../demo/README.md`](../../demo/README.md) — a Vue app built on exactly this API
