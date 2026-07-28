# vcyclist

[![npm engine](https://img.shields.io/npm/v/@glandais/vcyclist-engine?label=%40glandais%2Fvcyclist-engine)](https://www.npmjs.com/package/@glandais/vcyclist-engine)
[![npm elevation](https://img.shields.io/npm/v/@glandais/vcyclist-elevation?label=%40glandais%2Fvcyclist-elevation)](https://www.npmjs.com/package/@glandais/vcyclist-elevation)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.glandais/vcyclist-engine?label=io.github.glandais%3Avcyclist-engine)](https://central.sonatype.com/artifact/io.github.glandais/vcyclist-engine)
[![Maven Central elevation](https://img.shields.io/maven-central/v/io.github.glandais/vcyclist-elevation?label=io.github.glandais%3Avcyclist-elevation)](https://central.sonatype.com/artifact/io.github.glandais/vcyclist-elevation)
[![Maven Central gpx](https://img.shields.io/maven-central/v/io.github.glandais/vcyclist-gpx?label=io.github.glandais%3Avcyclist-gpx)](https://central.sonatype.com/artifact/io.github.glandais/vcyclist-gpx)
[![Maven Central fit](https://img.shields.io/maven-central/v/io.github.glandais/vcyclist-fit?label=io.github.glandais%3Avcyclist-fit)](https://central.sonatype.com/artifact/io.github.glandais/vcyclist-fit)
[![Maven Central map](https://img.shields.io/maven-central/v/io.github.glandais/vcyclist-map?label=io.github.glandais%3Avcyclist-map)](https://central.sonatype.com/artifact/io.github.glandais/vcyclist-map)

Kotlin Multiplatform port of [`@glandais/virtual-cyclist`](https://github.com/glandais/virtual-cyclist):
physics-based cycling simulator that turns a static GPS trace into a virtualized ride with
realistic speeds, times and power estimates. Inspired by [gpx2web](https://github.com/glandais/gpx2web)
(Java) for the physics model and the [`@glandais/elevation`](https://github.com/glandais/elevation)
TypeScript library for elevation data.

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
            │  ├─ MaxSpeedComputer (cornering+braking)│
            │  ├─ VirtualizeService (1 Hz physics)    │
            │  ├─ PointPerSecond (uniform sampling)   │
            │  └─ PathSimplifier (Douglas-Peucker 3D) │
            └──────────────────┬──────────────────────┘
                               ▼
                        ┌──────────────┐
                        │ GpxWriter    │────▶ output.gpx
                        └──────────────┘
            (*) optional — needs an ElevationProvider
```

## Modules

| Module | Purpose | Targets |
|---|---|---|
| **`:elevation`** | Terrarium tile fetch + DEM lookup + Haversine + Douglas-Peucker 3D + triangular smoother. See [`elevation/README.md`](elevation/README.md). | JVM, JS Node, JS browser |
| **`:gpx`** | Path model (36 fields × `DoubleArray`), resamplers, Douglas-Peucker simplifier, elevation steps, GPX I/O. Published to Maven Central; **not** published to npm — its JS output ships inside `@glandais/vcyclist-engine`. | JVM, JS Node, JS browser |
| **`:engine`** | Physics (4 resistive `PowerProvider`s + cyclist input + `MaxSpeedComputer` + `VirtualizeService`), `Enhancer` pipeline, JVM CLI, JS façades. Re-exports `:gpx` via `api`, so `io.github.glandais.engine.path.*` and `…engine.gpx.*` stay importable from `:engine`. | JVM, JS Node, JS browser |
| **`:fit`** | Garmin FIT encoding. `FitCourse` model + unit conversions in commonMain; `expect object FitEncoder` with a JVM `actual` on `com.garmin:fit` and a JS `actual` on `@garmin/fitsdk`. | JVM, JS Node, JS browser |
| **`:map`** | Static map rendering: Web Mercator projection, image framing, tile download + cache, PNG output (`java.awt` / `ImageIO`). **JVM-only.** No default tile source — see [`map/README.md`](map/README.md) for the usage-policy obligations. | JVM only |
| **`:cli`** | Command-line tool (picocli). **JVM-only, not published as a library** — distributed as an executable jar. Replaces gpx2web's `gpxtools-cli`. | JVM only |
| **`:codegen`** | Tiny build-time helper that regenerates `GeneratedPath.kt` + `PointFieldAccessors.kt` from `PointField` (run only when the field list changes). | JVM only |

### Migrating from gpx2web

vcyclist replaces the `gpx` and `gpxtools-cli` modules of
[gpx2web](https://github.com/glandais/gpx2web).
[`docs/gpx2web-coverage.md`](docs/gpx2web-coverage.md) has one row per Java class — ported,
replaced, or not ported with the reason — plus the deliberate behavioural differences. For the
command-line options specifically, see [`cli/README.md`](cli/README.md).

## Install

### npm (Kotlin/JS consumers)

```bash
npm install @glandais/vcyclist-engine          # Kotlin/JS bundle
npm install @glandais/vcyclist-elevation       # Kotlin/JS bundle
```

### Gradle / Maven (JVM or KMP consumers)

```kotlin
// Gradle Kotlin DSL
dependencies {
    implementation("io.github.glandais:vcyclist-engine:1.0.0")    // pulls -jvm / -js per target
    implementation("io.github.glandais:vcyclist-elevation:1.0.0")
}
```

Replace `1.0.0` by the latest version shown in the badges above. KMP consumers automatically
get the platform-specific variant (`-jvm`, `-js`) for their target.
`vcyclist-gpx` (the `Path` model + GPX I/O) comes in transitively via `vcyclist-engine`, and
can also be depended on alone if you only need parsing and resampling.

See [`docs/publishing.md`](docs/publishing.md) for the release process.

## Quick start

### Run the CLI

The CLI is **not** on Maven Central — it is an application, distributed as a self-contained jar
attached to each [GitHub release](https://github.com/glandais/vcyclist/releases). Download
`vcyclist-cli-<version>-all.jar` and run it with Java 21+, or build it yourself:

```bash
# Build a self-contained jar, then run it from anywhere:
./gradlew :cli:executableJar
java -jar cli/build/libs/vcyclist-cli-*-all.jar enhance route.gpx --gpx out.gpx --csv out.csv

# Or, during development:
./gradlew :cli:run -Pargs="enhance route.gpx --gpx /tmp/out.gpx"
```

`enhance` runs the physics pipeline; `export` produces maps, FIT, CSV and JSON from a file you
already have. Elevation correction is off unless you pass `--fix-elevation`, so nothing touches
the network by default. Full usage, exit codes and the migration table from gpx2web's
`gpxtools-cli` are in [`cli/README.md`](cli/README.md).

### Try the browser demo (elevation only)

```bash
# Kotlin/JS demo
./gradlew :elevation:jsBrowserDevelopmentRun
```

The demo shares the [original TS demo](https://github.com/glandais/elevation) UI (Leaflet +
Chart.js + GPX upload). See [`elevation/README.md`](elevation/README.md) for details.

### Use from Kotlin

```kotlin
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.firstTrackAsPath

suspend fun virtualize(xml: String): String {
    val path = GpxParser.parse(xml).firstTrackAsPath()
    val out = Enhancer.enhanceCourseDefault(path)  // pure physics, no HTTP
    return io.github.glandais.engine.gpx.GpxWriter.write(
        out.toGpxDocument(trackName = "virtualized")
    )
}
```

### Use from Java

The library is Kotlin-first, so its asynchronous entry points — elevation lookups and the
`Enhancer` pipeline that may call them — are `suspend` functions. From Java that would mean
hand-writing a `Continuation`, so each of them has a JVM bridge in two shapes:

| Shape | Suffix | Returns | For |
|---|---|---|---|
| Blocking | `…Blocking` | the value | batch jobs, CLIs, tests |
| Asynchronous | `…Async` | `CompletableFuture<T>` | servers, UIs |

```java
import io.github.glandais.elevation.ElevationProvider;
import io.github.glandais.elevation.ElevationProviderJvm;
import io.github.glandais.engine.EnhancerJvm;
import io.github.glandais.engine.gpx.GpxParserJvm;
import io.github.glandais.engine.gpx.GpxToPathKt;
import io.github.glandais.engine.gpx.GpxWriterJvm;
import io.github.glandais.engine.path.Path;

String xml = Files.readString(java.nio.file.Path.of("route.gpx"));
Path input = GpxToPathKt.firstTrackAsPath(GpxParserJvm.parse(xml));

// Physics only, nothing touches the network:
Path enhanced = EnhancerJvm.enhanceCourseDefaultBlocking(input);

// Or with elevation correction, off the calling thread:
ElevationProvider provider = ElevationProviderJvm.newElevationProvider();
CompletableFuture<Path> future = EnhancerJvm.enhanceCourseDefaultAsync(input, provider);

String out = GpxWriterJvm.write(enhanced);
```

**Every entry point has a `…Jvm` twin**, in the same package as the Kotlin original:
`GpxParserJvm`, `GpxWriterJvm`, `GpxFromPathJvm`, `GpxModelJvm`, `PathSimplifierJvm`,
`ElevationStepJvm`, `TabularWritersJvm` (`:gpx`), `ElevationProviderJvm`, `TileFetcherJvm`
(`:elevation`), `EnhancerJvm`, `EngineModelJvm`, `ClimbDetectorJvm` (`:engine`), `PathToFitJvm`
(`:fit`), `MapFactoriesJvm` (`:map`). They exist for two reasons: Kotlin default arguments are
invisible to Java, and `@JvmOverloads` cannot be used on the common source sets where the API
lives. Call them and every optional parameter becomes optional again.

- `…Blocking` parks the calling thread. **Never call it from a UI thread, from inside a
  coroutine, or from a thread of the executor you passed** — the first two freeze the caller,
  the third can deadlock the pool. Exceptions propagate unchanged.
- `…Async` takes an optional `java.util.concurrent.Executor` (default: the coroutines IO
  dispatcher, the right pool for network-bound work). Cancelling the returned future cancels the
  work underneath; failures arrive as a `CompletionException`, so unwrap with `getCause()`.
- Everything else in the library is already synchronous and needs no bridge: `GpxParser`,
  `GpxWriter`, `ElevationStep.smoothElevation`, `PathSimplifier`, the resamplers, FIT and the
  CSV / JSON writers.

### Use from JavaScript / TypeScript

`generateTypeScriptDefinitions()` is enabled on `js(IR)`, so you get a `.d.ts` next to the
bundle in `build/dist/js/productionExecutable/vcyclist-engine.d.{ts,mts}`.

The Kotlin/JS variant (`@glandais/vcyclist-engine`, `@glandais/vcyclist-elevation`) runs
**in both browser and Node.js / Bun**.

#### Getting at the exports

Kotlin/JS emits a **UMD bundle that preserves the package namespace**, so the module has
exactly one top-level export, `io`. Named imports do not work — `import { parseGpx } from
'@glandais/vcyclist-engine'` fails under Node ESM with *"Named export 'parseGpx' not found"*.
Unwrap the namespace once and destructure from it :

```js
import * as engineRaw from '@glandais/vcyclist-engine';   // or: require('@glandais/vcyclist-engine')

const engine = engineRaw.io.github.glandais.engine;
```

Every snippet below assumes that `engine`. `demo/src/engine-shim.ts` does the same unwrap and
adds TypeScript types for the DTOs, which Kotlin/JS emits as referenced names only.

#### Browser

```js
const { parseGpx, enhance, writeGpx, pathSize, pathTotalDistance } = engine;

const handle = parseGpx(gpxXml);
console.log('input points:', pathSize(handle));
const out = await enhance(handle, null);                    // physics only, no HTTP
console.log('output:', pathSize(out), pathTotalDistance(out), 'm');
const xml = writeGpx(out);
```

#### Node.js / Bun (with elevation correction)

```js
const { parseGpx, enhance, writeGpx } = engine;

const handle = parseGpx(gpxXml);
const out = await enhance(handle, { fixElevation: true });  // fetches DEM tiles, decodes WebP
const xml = writeGpx(out);
```

`enhance(..., { fixElevation: true })` auto-instantiates a default `ElevationProvider`
(mapterhorn Terrarium tiles) and runs the full pipeline (densify → fix elevation → smooth →
max speeds → virtualize → resample → simplify).

#### FIT export

```js
const { parseGpx, enhance, pathToFit } = engine;

const out = await enhance(parseGpx(gpxXml), null);
const fit = pathToFit(out, 'My route', Date.parse('2026-08-01T08:00:00Z'));
// Kotlin/JS returns an Int8Array containing the FIT file.
```

FIT has no relative clock, so the start instant is mandatory. The output is a **Course** file
(a route to follow), which is what a virtualized trace should be — not an Activity.

#### Multi-track GPX

`parseGpx` returns the **first** track, which is what most files contain. For documents with
several `<trk>` or several `<trkseg>` :

```js
const { parseGpxTracks, parseGpxSegments, writeGpxTracks } = engine;

const tracks = parseGpxTracks(gpxXml);       // one path per <trk> *and* per <rte>
const segments = parseGpxSegments(gpxXml);   // one path per <trkseg>, always continuous
const xml = writeGpxTracks(tracks);          // one <trk> per path
```

Concatenating segments folds the pause between them into `totalDistance` — a `<trkseg>`
boundary is a physical discontinuity. Use `parseGpxSegments` when that artefact matters.

`parseGpxTracks` also returns `<rte>` routes, which many planners emit and which used to be
dropped silently. `parseGpxTracksOnly` and `parseGpxRoutesOnly` select one container or the other.

#### Options on the writers

```js
const bare = engine.writeGpx(path, false);   // no <extensions>: no power, heart rate, cadence
const fit = engine.pathsToFit([a, b], 'Two days', Date.parse('2026-08-01T08:00:00Z'));
```

`writeGpx`, `writeGpxAt` and `writeGpxTracks` take a trailing `writeExtensions` flag (default
`true`). `pathsToFit` encodes several paths into one FIT course — a lap and a timer event pair
each — where `pathToFit` takes a single one.

On Node.js / Bun, tile decoding uses [`@jsquash/webp`](https://www.npmjs.com/package/@jsquash/webp)
(a pure-WASM WebP decoder, ~50 KB, listed as a runtime `dependency` of
`@glandais/vcyclist-engine` and `@glandais/vcyclist-elevation`). It is loaded lazily via
`eval('require')`, so browser bundlers do not pull it into the browser build. Requires
Node ≥ 18 (`globalThis.fetch` is built-in since Node 18 / Bun) ; Node 22+ recommended for
ESM `require()` support.

## Try the interactive demo

The [`demo/`](demo/) module is a Vue 3 + Vite frontend that exercises the
Kotlin/JS engine end-to-end in a browser (GPX upload, configurable cyclist /
bike / wind / power, chart + map, hover sync).

```bash
./gradlew :demo:assemble
python -m http.server -d demo/dist 8000  # or any static file server
```

See [`demo/README.md`](demo/README.md) for the dev workflow and architecture.

## Build & test

```bash
./gradlew check                         # full build + all tests on all targets
./gradlew :engine:allTests              # engine tests across JVM / JS Node / JS browser
./gradlew :elevation:allTests           # elevation tests
./gradlew :elevation:jvmTest --tests '*Integration*' \
          -PINTEGRATION=1               # live HTTP tests against tiles.mapterhorn.com
./gradlew ktlintCheck                   # lint
```

## Layout

```
vcyclist/
├── settings.gradle.kts          # multi-module Gradle KMP project
├── gradle/libs.versions.toml    # version catalog (Kotlin 2.3.21, coroutines 1.11, xmlutil 0.91, …)
├── docs/
│   ├── PLAN.md                  # task-by-task progress (Phases 1-2bis)
│   ├── parity.md                # parity strategy vs the TS reference
│   ├── elevation-integration.md # how to run live HTTP integration tests
│   ├── kotlin-js-jvm-webp.md    # Kotlin/JS ↔ JS interop guide
│   └── tasks/                   # one Markdown per implementation task (00-31, + bonus demos)
├── elevation/                   # :elevation KMP module
├── engine/                      # :engine KMP module (depends on :elevation)
└── codegen/                     # :codegen JVM helper for Path accessor generation
```

## Status

- ✅ **Phase 1** — `:elevation` module port (tasks 00-09) : Terrarium tiles, Haversine, ECEF,
  Douglas-Peucker 3D, smoother, LRU cache + TileManager, `ElevationProvider`, live HTTP integration.
- ✅ **Phase 2** — `:engine` module port (tasks 10-28) : Path model, Cyclist/Bike/Course,
  GPX I/O, full physics, simulation, post-processing, `Enhancer`, CLI, `@JsExport` façades.
- ✅ **Phase 2bis** — pipeline fidelity fixes (tasks 29-31) : `VirtualizeService` last-point
  timestamp, `PointPerDistance` port, integration into `Enhancer`.
- ✅ **Phase 3** — Node.js / Bun support (tasks 32-33) : runtime-detection in
  `TileFetcher.js.kt` (browser path unchanged, Node path uses `globalThis.fetch` +
  `@jsquash/webp` WASM decoder loaded via lazy `eval('require')`), webpack externals to keep
  the browser bundle free of `@jsquash/webp`, `ElevationProvider` auto-instantiation in
  `EngineJsApi.enhance` when `opts.fixElevation` is true (JS façade), 6 jsTest classes
  gated by `INTEGRATION=1`.

Total `:engine` test coverage : 32 test classes / ~326 commonTest cases / 3 targets =
~1000 green executions, plus JVM-only smoke tests for the CLI and the full pipeline.

End-to-end smoke (after Phase 2bis) : sample.gpx (3569 source points, 130 km, ~4550 m gain)
runs through the complete `Enhancer` pipeline in ~1.7 s on JVM, producing ~1000 simplified
output points covering ~128.6 km / ~5.3 h of simulated ride.

## Documentation

- [`docs/PLAN.md`](docs/PLAN.md) — task-by-task plan with commit hashes for every step.
- [`docs/tasks/`](docs/tasks/) — detailed Markdown spec for each task (00-31 + bonus demos).
- [`docs/parity.md`](docs/parity.md) — TS↔Kotlin parity approach and tolerances.
- [`docs/kotlin-js-jvm-webp.md`](docs/kotlin-js-jvm-webp.md) — Kotlin/JS ↔ JS interop
  guide that underpins the `@JsExport` façades and the WebP tile decoding.
- [`docs/publishing.md`](docs/publishing.md) — release flow (Maven Central + npm via
  semantic-release on push to `develop`).
- [`elevation/README.md`](elevation/README.md) — `:elevation` module details + browser demos.

## Contributing

`develop` is the **default and only long-lived branch** — there is no `main`. Open PRs
against `develop` using [Conventional Commits](https://www.conventionalcommits.org/) :
`feat:` triggers a minor release, `fix:` a patch, anything else is a no-op release-wise.
Every push to `develop` runs the full multi-target test suite via
`.github/workflows/release.yml` and, if green, lets semantic-release tag a new version,
publish to Maven Central + npm, and commit the version bump back to `develop` with
`[skip ci]`. See [`docs/publishing.md`](docs/publishing.md) for the full flow.

## License

Apache License 2.0, aligned with the upstream `gpx2web` project. See the Maven Central POM
metadata in `engine/build.gradle.kts` and `elevation/build.gradle.kts`. A top-level `LICENSE`
file will be added before the first public release.
