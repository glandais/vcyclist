# vcyclist

[![npm engine](https://img.shields.io/npm/v/@glandais/vcyclist-engine?label=%40glandais%2Fvcyclist-engine)](https://www.npmjs.com/package/@glandais/vcyclist-engine)
[![npm elevation](https://img.shields.io/npm/v/@glandais/vcyclist-elevation?label=%40glandais%2Fvcyclist-elevation)](https://www.npmjs.com/package/@glandais/vcyclist-elevation)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.glandais/vcyclist-engine?label=io.github.glandais%3Avcyclist-engine)](https://central.sonatype.com/artifact/io.github.glandais/vcyclist-engine)

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
| **`:elevation`** | Terrarium tile fetch + DEM lookup + Haversine + Douglas-Peucker 3D + triangular smoother. See [`elevation/README.md`](elevation/README.md). | JVM, JS Node, JS browser, Wasm browser |
| **`:engine`** | Path model (36 fields × `DoubleArray`), physics (4 resistive `PowerProvider`s + cyclist input + `MaxSpeedComputer` + `VirtualizeService`), GPX I/O, `Enhancer` pipeline, JVM CLI. | JVM, JS Node, JS browser, Wasm browser |
| **`:codegen`** | Tiny build-time helper that regenerates `GeneratedPath.kt` + `PointFieldAccessors.kt` from `PointField` (run only when the field list changes). | JVM only |

## Install

### npm (Kotlin/JS or Kotlin/Wasm consumers)

```bash
npm install @glandais/vcyclist-engine          # Kotlin/JS bundle
npm install @glandais/vcyclist-engine-wasm     # Kotlin/Wasm bundle
npm install @glandais/vcyclist-elevation       # Kotlin/JS bundle
npm install @glandais/vcyclist-elevation-wasm  # Kotlin/Wasm bundle
```

### Gradle / Maven (JVM or KMP consumers)

```kotlin
// Gradle Kotlin DSL
dependencies {
    implementation("io.github.glandais:vcyclist-engine:1.0.0")    // pulls -jvm / -js / -wasm-js per target
    implementation("io.github.glandais:vcyclist-elevation:1.0.0")
}
```

Replace `1.0.0` by the latest version shown in the badges above. KMP consumers automatically
get the platform-specific variant (`-jvm`, `-js`, `-wasm-js`) for their target.

See [`docs/publishing.md`](docs/publishing.md) for the release process.

## Quick start

### Run the JVM CLI

```bash
# Enhance a GPX file with the default cyclist (80 kg / 280 W) and bike (Crr 0.004) :
./gradlew :engine:run -Pargs="enhance path/to/input.gpx -o /tmp/output.gpx"
```

The CLI runs the full enhancement pipeline (no elevation correction — no HTTP) and writes the
simulated trace back to a GPX file. See [`engine/src/jvmMain/.../EngineCli.kt`](engine/src/jvmMain/kotlin/io/github/glandais/engine/EngineCli.kt).

### Try the browser demos (elevation only)

```bash
# Kotlin/Wasm demo
./gradlew :elevation:wasmJsBrowserDevelopmentRun
# Kotlin/JS demo (sibling, same UI)
./gradlew :elevation:jsBrowserDevelopmentRun
```

Both demos share the [original TS demo](https://github.com/glandais/elevation) UI (Leaflet +
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

### Use from JavaScript / TypeScript

`generateTypeScriptDefinitions()` is enabled on both `js(IR)` and `wasmJs`, so you get a
`.d.ts` next to the bundle in `build/dist/{js,wasmJs}/productionExecutable/vcyclist-engine.d.{ts,mts}`.

The Kotlin/JS variant (`@glandais/vcyclist-engine`, `@glandais/vcyclist-elevation`) runs
**in both browser and Node.js / Bun**. The Wasm variants (`*-wasm`) are browser-only.

#### Browser

```js
import { parseGpx, enhance, writeGpx, pathSize, pathTotalDistance } from '@glandais/vcyclist-engine';

const handle = parseGpx(gpxXml);
console.log('input points:', pathSize(handle));
const out = await enhance(handle, null);                    // physics only, no HTTP
console.log('output:', pathSize(out), pathTotalDistance(out), 'm');
const xml = writeGpx(out);
```

#### Node.js / Bun (with elevation correction)

```js
import { parseGpx, enhance, writeGpx } from '@glandais/vcyclist-engine';

const handle = parseGpx(gpxXml);
const out = await enhance(handle, { fixElevation: true });  // fetches DEM tiles, decodes WebP
const xml = writeGpx(out);
```

`enhance(..., { fixElevation: true })` auto-instantiates a default `ElevationProvider`
(mapterhorn Terrarium tiles) and runs the full pipeline (densify → fix elevation → smooth →
max speeds → virtualize → resample → simplify).

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
./gradlew :engine:allTests              # engine tests across JVM / JS Node / JS browser / Wasm browser
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
│   ├── kotlin-wasm-jvm-webp.md  # Kotlin/Wasm ↔ JS interop guide
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
  `EngineJsApi.enhance` when `opts.fixElevation` is true (JS + Wasm façades), 6 jsTest classes
  gated by `INTEGRATION=1`.

Total `:engine` test coverage : 32 test classes / ~326 commonTest cases / 4 targets =
~1300 green executions, plus JVM-only smoke tests for the CLI and the full pipeline.

End-to-end smoke (after Phase 2bis) : sample.gpx (3569 source points, 130 km, ~4550 m gain)
runs through the complete `Enhancer` pipeline in ~1.7 s on JVM, producing ~1000 simplified
output points covering ~128.6 km / ~5.3 h of simulated ride.

## Documentation

- [`docs/PLAN.md`](docs/PLAN.md) — task-by-task plan with commit hashes for every step.
- [`docs/tasks/`](docs/tasks/) — detailed Markdown spec for each task (00-31 + bonus demos).
- [`docs/parity.md`](docs/parity.md) — TS↔Kotlin parity approach and tolerances.
- [`docs/kotlin-wasm-jvm-webp.md`](docs/kotlin-wasm-jvm-webp.md) — Kotlin/Wasm ↔ JS interop
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
