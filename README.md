# vcyclist

[![npm engine](https://img.shields.io/npm/v/@glandais/vcyclist-engine?label=%40glandais%2Fvcyclist-engine)](https://www.npmjs.com/package/@glandais/vcyclist-engine)
[![npm elevation](https://img.shields.io/npm/v/@glandais/vcyclist-elevation?label=%40glandais%2Fvcyclist-elevation)](https://www.npmjs.com/package/@glandais/vcyclist-elevation)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.glandais/vcyclist-engine?label=io.github.glandais%3Avcyclist-engine)](https://central.sonatype.com/artifact/io.github.glandais/vcyclist-engine)
[![Maven Central elevation](https://img.shields.io/maven-central/v/io.github.glandais/vcyclist-elevation?label=io.github.glandais%3Avcyclist-elevation)](https://central.sonatype.com/artifact/io.github.glandais/vcyclist-elevation)
[![Maven Central gpx](https://img.shields.io/maven-central/v/io.github.glandais/vcyclist-gpx?label=io.github.glandais%3Avcyclist-gpx)](https://central.sonatype.com/artifact/io.github.glandais/vcyclist-gpx)
[![Maven Central fit](https://img.shields.io/maven-central/v/io.github.glandais/vcyclist-fit?label=io.github.glandais%3Avcyclist-fit)](https://central.sonatype.com/artifact/io.github.glandais/vcyclist-fit)
[![Maven Central map](https://img.shields.io/maven-central/v/io.github.glandais/vcyclist-map?label=io.github.glandais%3Avcyclist-map)](https://central.sonatype.com/artifact/io.github.glandais/vcyclist-map)

Kotlin Multiplatform physics-based cycling simulator: it turns a static GPS trace into a
virtualized ride with realistic speeds, times and power estimates. Elevation data comes from
Terrarium-encoded DEM tiles — mapterhorn by default — fetched and decoded by the `:elevation`
module.

```
                        ┌──────────────┐
        sample.gpx ────▶│ GpxParser    │
                        └──────┬───────┘
                               ▼
            ┌─────────────────────────────────────────┐
            │  Enhancer (orchestrator)                │
            │  ├─ PointPerDistance(-1, 30)            │
            │  ├─ fixElevation (Terrarium tiles)*     │
            │  ├─ PointPerDistance(1, 2)              │
            │  ├─ smoothElevation (150 m kernel)      │
            │  ├─ PathCurvature (turn radius)         │
            │  │   or RacingLine (optimal line)*      │
            │  ├─ MaxSpeedComputer (cornering+braking)│
            │  ├─ VirtualizeService (1 Hz physics)    │
            │  ├─ PointPerSecond (uniform sampling)   │
            │  ├─ W′bal (Critical Power annotation)   │
            │  ├─ PathSimplifier (Douglas-Peucker 3D) │
            │  └─ ElevationGain (D+/D− dead band)     │
            └──────────────────┬──────────────────────┘
                               ▼
                        ┌──────────────┐
                        │ GpxWriter    │────▶ output.gpx
                        └──────────────┘
            (*) optional — needs an ElevationProvider
```

## What it can do

**In**: GPX tracks, routes (`<rte>`), segments and waypoints.

**Physics**: elevation correction from DEM tiles, curvature estimation or an optimal racing line,
cornering and braking sharing one friction-ellipse budget, a configurable rider and bike, and a
1 Hz time-stepping simulation that produces speed, time and power at every point.

**Out**: GPX, Garmin FIT courses, CSV, column-oriented JSON, static PNG maps — plus climb
detection, a racing-line report and worst-case wind analysis.

Four doors reach the same engine, and a capability is available from all of them unless noted:

| Capability | CLI | Kotlin / Java | JavaScript / TS | WASI |
|---|---|---|---|---|
| Run the pipeline | `enhance` | `Enhancer.enhanceCourseDefault` | `enhance` | `vcEnhance` |
| Configure rider, bike, wind, power | `--cyclist-*`, `--bike-*`, `--wind-*` | `CoursePhysics(Course(…))` | `enhanceWithCourse` | `vcEnhanceWithCourse` |
| Power models `constant` · `durability` · `critical-power` · `from_data` | `--cyclist-model` | `CyclistPowerSpec` | `power.type` | `power.type` |
| Terrain pacing, power slew limit | `--cyclist-pacing`, `--cyclist-slew` | provider decorators | `power.pacing`, `power.maxSlewWPerS` | idem |
| Road condition, dry or wet | `--road-condition` | `Cyclist.withRoadCondition` | `cyclist.roadCondition` | idem |
| Pedal-strike clearance | `--bike-max-pedal-angle` | `Bike.maxPedalingLeanAngleDeg` | `bike.maxPedalingLeanAngleDeg` | idem |
| Racing line + corridor mode | `--racing-line`, `--corridor` | `RacingLineOptions` | `racingLineEnabled` | idem |
| DEM elevation correction | `--fix-elevation` | `ElevationProvider` | `fixElevation: true` | host serves tiles |
| Climb detection | — | `ClimbDetector.detect` | `detectClimbs` | `vcDetectClimbsJson` |
| Racing-line report | `--racing-line-report` | `RacingLine.analyze` | `analyzeRacingLine` | `vcAnalyzeRacingLineJson` |
| Write GPX / CSV / JSON | `--gpx --csv --json` | `GpxWriter`, `CsvWriter`, `JsonWriter` | `writeGpx`, `pathToCsv`, `pathToJson` | `vcWriteGpx`, `vcPathToCsv/Json` |
| Write FIT course | `--fit` | `Path.toFitBytes` | `pathToFit`, `pathsToFit` | `vcPathToFit`, `vcPathsToFit` |
| Static map PNG | `export --map` | `MapFactoriesJvm` | — | — |

Static maps are JVM-only by construction — `:map` draws on `java.awt`.
[`docs/ledgers/surface-coverage.md`](docs/ledgers/surface-coverage.md) tracks this matrix as
capabilities land, so that a feature cannot reach one door and quietly miss the others.

## Install

### npm — browser, Node.js and Bun

```bash
npm install @glandais/vcyclist-engine          # physics, GPX, FIT, CSV/JSON, climbs, racing line
npm install @glandais/vcyclist-elevation       # DEM lookups on their own
```

The engine bundle already carries the elevation façade; install the second package only if you
want DEM lookups without the physics.

### Gradle — JVM or Kotlin Multiplatform

```kotlin
dependencies {
    implementation("io.github.glandais:vcyclist-engine:4.2.1")     // pulls -jvm / -js per target
    implementation("io.github.glandais:vcyclist-elevation:4.2.1")
}
```

### Maven

```xml
<dependency>
  <groupId>io.github.glandais</groupId>
  <artifactId>vcyclist-engine-jvm</artifactId>
  <version>4.2.1</version>
</dependency>
```

The badges above are the source of truth for the current version. KMP consumers get the
platform-specific variant automatically; from plain Maven, name the `-jvm` artifact yourself.

`vcyclist-gpx` (the `Path` model + GPX I/O) comes in transitively via `vcyclist-engine`, and can be
depended on alone if you only need parsing and resampling. `vcyclist-fit` and `vcyclist-map` are
published separately.

### CLI and `.wasm`

The CLI is an application, not a library, so it is **not** on Maven Central: download
`vcyclist-cli-<version>-all.jar` from a [GitHub release](https://github.com/glandais/vcyclist/releases).
The WASI module is built from source (below) rather than published to a registry.

**Requirements**: Java 21+ for the JVM and CLI; Node ≥ 18 (22+ recommended) or Bun for JavaScript;
a WASI runtime with the `function-references`, `gc` and `exceptions` proposals — wasmtime 46+ is
known good.

## Quick start

### Command line

```bash
java -jar vcyclist-cli-*-all.jar enhance route.gpx --gpx out.gpx --csv out.csv
```

`enhance` runs the physics pipeline; `export` produces maps, FIT, CSV and JSON from a file you
already have. Elevation correction is off unless you pass `--fix-elevation`, so nothing touches the
network by default.

Full option reference, the rider models and what each is measured to be worth, and exit codes:
[`cli/README.md`](cli/README.md).

### Kotlin

```kotlin
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.GpxWriter
import io.github.glandais.engine.gpx.firstTrackAsPath
import io.github.glandais.engine.gpx.toGpxDocument

suspend fun virtualize(xml: String): String {
    val path = GpxParser.parse(xml).firstTrackAsPath()
    val out = Enhancer.enhanceCourseDefault(path)   // pure physics, no HTTP
    return GpxWriter.write(out.toGpxDocument(trackName = "virtualized"))
}
```

Pass an `ElevationProvider` as the second argument to correct elevations from DEM tiles, and an
`EnhanceOptions` as the third to configure the pipeline. For a configured rider, build a
`CoursePhysics(Course(path, cyclist, bike), …)` and call `Enhancer.enhanceCourse`.

### Java

```java
Path input = GpxToPathJvm.firstTrackAsPath(GpxParserJvm.parse(xml));
Path enhanced = EnhancerJvm.enhanceCourseDefaultBlocking(input);
String out = GpxWriterJvm.write(enhanced);
```

Every entry point has a `…Jvm` twin that restores Kotlin's default arguments, and every `suspend`
function has both a `…Blocking` and a `…Async` (`CompletableFuture`) bridge.
**[`docs/guides/using-from-java.md`](docs/guides/using-from-java.md)** has the calling rules — some
of them matter, `…Blocking` on a UI thread being the obvious one.

### JavaScript / TypeScript

Kotlin/JS emits a UMD bundle that preserves the package namespace, so there is exactly one
top-level export. Named imports do not work — unwrap it once:

```js
import * as engineRaw from '@glandais/vcyclist-engine';
const engine = engineRaw.io.github.glandais.engine;

const { parseGpx, enhance, writeGpx, pathSize, pathTotalDistance } = engine;

const path = parseGpx(gpxXml);
const out  = await enhance(path, null);          // physics only; { fixElevation: true } for DEM
console.log(pathSize(out), pathTotalDistance(out), 'm');

// `<power>` carries the SOURCE file's power by default — writing simulated data into a format the
// ecosystem reads as a recording is the caller's call. Ask for the simulation explicitly:
const xml  = writeGpx(out, true, 'computed-or-input', 'my route');
```

**[`docs/guides/using-from-javascript.md`](docs/guides/using-from-javascript.md)** covers the whole
façade — `enhanceWithCourse` and its five DTOs, FIT and CSV/JSON export, climbs, the racing-line
report, the standalone elevation API, and the Node/Bun specifics.

### WASI — no JVM, no JavaScript

`:engine` links a standalone WASI module, so the whole pipeline runs inside wasmtime, WasmEdge,
wazero, or an embedding in Go, Rust, Python or the JVM.

```bash
./gradlew :engine:wasmModule       # -> engine/build/wasm/vcyclist-engine.wasm + .sha256
```

The host implements three imports — `read_input`, `write_output` and `fetch_tile` (which may simply
answer "no tile") — and everything else is numeric exports over integer handles:

```python
staged["bytes"] = open("ride.gpx", "rb").read()
handle = exports["vcParseGpx"](store, len(staged["bytes"]))

staged["bytes"] = b'{"computeOnePointPerSecond": true}'
out = exports["vcEnhance"](store, handle, len(staged["bytes"]))
print(exports["vcPathDurationMs"](store, out) / 1000, "s")
```

This is not a reduced surface: `vcEnhanceWithCourse`, `vcPathToFit`, `vcPathToCsv`,
`vcDetectClimbsJson` and `vcAnalyzeRacingLineJson` are all there, and §10 of the guide is a
function-by-function parity table against the JavaScript façade.
**[`docs/guides/wasm-wasi-abi.md`](docs/guides/wasm-wasi-abi.md)** is the full contract;
[`tools/wasi`](tools/wasi/README.md) is a working host that CI runs on every pull request.

## Try the demo

**<https://glandais.github.io/vcyclist>** — no install, runs the real engine in your browser.

A Vue 3 + Leaflet + Chart.js app with two routes, both on the same Kotlin/JS bundle:

- `#/` — **GPX analysis**: upload a route, run the physics pipeline, inspect every field on a
  synchronized chart and map, with climb detection and the racing line, then download the result
  as GPX or as a Garmin FIT course.
- `#/elevation` — **elevation explorer**: query DEM tiles at a point or along a path, with
  smoothing, Douglas-Peucker simplification and hillshade/slope relief.

```bash
cd demo && npm run dev        # http://localhost:3000, against a locally built engine
```

See [`demo/README.md`](demo/README.md) for the architecture and the static-site build.

## Documentation

[`docs/README.md`](docs/README.md) is the index and says which documents are current and which are
frozen history. The short version:

- [`docs/guides/`](docs/README.md#guides) — how to use and extend the project: Java, JavaScript, the
  WASI ABI, the racing line, the release flow
- [`docs/ledgers/`](docs/README.md#ledgers) — living state: research improvements, build warnings,
  surface coverage
- [`docs/research/`](docs/research/README.md) — the solo-rider simulation research report
- Module documentation lives next to its module: [`cli/`](cli/README.md),
  [`elevation/`](elevation/README.md), [`map/`](map/README.md), [`demo/`](demo/README.md)

## Contributing

Open PRs against `develop` — the default and only long-lived branch — using
[Conventional Commits](https://www.conventionalcommits.org/). Build commands, module layout,
testing conventions and the release flow are in [`CONTRIBUTING.md`](CONTRIBUTING.md).

## License

[Apache License 2.0](LICENSE).
