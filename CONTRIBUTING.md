# Contributing to vcyclist

For *using* the library, start at [`README.md`](README.md). This file is about working on it.

## Modules

| Module | Purpose | Targets |
|---|---|---|
| **`:gpx`** | `Path` model (44 fields × `DoubleArray`), resamplers, Douglas-Peucker simplifier, elevation steps, GPX I/O. Published to Maven Central; **not** to npm — its JS output ships inside `@glandais/vcyclist-engine`. | JVM, JS Node, JS browser, WASI |
| **`:elevation`** | Terrarium tile fetch + DEM lookup + Haversine + Douglas-Peucker 3D + triangular smoother. See [`elevation/README.md`](elevation/README.md). | JVM, JS Node, JS browser, WASI |
| **`:engine`** | Physics (resistive `PowerProvider`s + cyclist input + `MaxSpeedComputer` + `VirtualizeService`), the `Enhancer` pipeline, the JS and WASI façades. Re-exports `:gpx` via `api`, so `io.github.glandais.engine.{path,gpx}.*` stay importable from `:engine`. Also the module that links the standalone `.wasm`. | JVM, JS Node, JS browser, WASI |
| **`:fit`** | Garmin FIT encoding. `FitCourse` model, unit conversions and `FitEncoder` itself, all in `commonMain` over [`fit-kotlin-sdk`](https://github.com/glandais/fit-kotlin-sdk). One encoder, byte-identical output on every target, no vendor SDK for consumers to install. | JVM, JS Node, JS browser, WASI |
| **`:map`** | Static map rendering: Web Mercator projection, framing, tile download + cache, PNG output (`java.awt` / `ImageIO`). No default tile source — see [`map/README.md`](map/README.md) for the usage-policy obligations. **Nothing may depend on it.** | JVM only |
| **`:cli`** | Command-line tool (picocli). Not published as a library — distributed as an executable jar. | JVM only |
| **`:codegen`** | Regenerates `GeneratedPath.kt` + `PointFieldAccessors.kt` from `PointField`. Run only when the field list changes. | JVM only |
| **`demo/`** | Vue 3 + Vite browser demo, two routes. Not published. | — |

`:cli`, `:codegen` and `demo/` are not published. The other five go to Maven Central as
`io.github.glandais:vcyclist-<module>`; `:engine` and `:elevation` also go to npm.

## Build & test

```bash
./gradlew check                          # full build + tests, every target
./gradlew ktlintCheck                    # lint  (ktlintFormat to fix)
./gradlew :engine:allTests               # one module, all targets (also :gpx, :elevation, :fit)
./gradlew :engine:jvmTest                # JVM only — fast iteration
./gradlew :engine:jsNodeTest             # also :jsBrowserTest (headless Chrome)
./gradlew :map:test  /  :cli:test        # the JVM-only modules
./gradlew :codegen:run                   # regenerate Path sources after editing PointField
./gradlew :cli:run -Pargs="enhance in.gpx --gpx out.gpx"    # CLI smoke
./gradlew :engine:wasmModule             # -> engine/build/wasm/vcyclist-engine.wasm
cd demo && npm run dev                   # browser demo, hot reload

INTEGRATION=1 ./gradlew :elevation:jvmTest --tests '*Integration*'   # live HTTP
```

Testing conventions:

- `kotlin-test`. Put anything portable in `commonTest` — prefer it. `jvmTest` is for HTTP, file
  I/O and full-pipeline smokes.
- Fixtures live in `gpx/src/commonTestFixtures/.../GpxFixtures.kt` as Kotlin raw strings
  (`commonTest/resources` is not portable). Both `:gpx` and `:engine` wire that directory in.
- `src/jvmTest/java/` sources run as part of `jvmTest` and exist to pin **Java callability**,
  which no Kotlin test can check. The KMP JVM target runs **JUnit 4** — `org.junit.Test`.
- Never compare `Double` with `==`. Two trig implementations across libc and JS differ by a few
  ULPs; the tolerance table is in [`CLAUDE.md`](CLAUDE.md).

## Layout

```
vcyclist/
├── settings.gradle.kts       # multi-module Gradle KMP project
├── gradle/libs.versions.toml # version catalog — the source of truth for every version
├── docs/
│   ├── README.md             # documentation index — start here
│   ├── guides/               # how to use and extend the project
│   ├── ledgers/              # living inventories (improvements, warnings, surface coverage)
│   ├── research/             # solo-rider simulation research report
│   └── archive/              # finished plans + the task specs that built the project
├── gpx/  elevation/  engine/  fit/  map/  cli/  codegen/
├── demo/                     # Vue/Vite browser demo
└── tools/                    # reference WASI hosts
```

## Things that will bite you

- **Nothing JVM-only in `commonMain`** — it must compile on JVM, JS Node, JS browser and wasmWasi.
- **`GeneratedPath.kt` and `PointFieldAccessors.kt` are generated.** Never hand-edit them; edit
  `PointField.kt` and run `:codegen:run`. New fields go **last** — ordinals are the wire format.
- **No `kotlin.jvm.*` annotation resolves from a common source set** (`@JvmOverloads`,
  `@JvmStatic`, assume the rest). The convention is a `…Jvm.kt` facade in `jvmMain` — see
  [`docs/guides/using-from-java.md`](docs/guides/using-from-java.md).
- **The `Enhancer` stage order is load-bearing.** If you change it, update `Enhancer.kt`'s
  docstring, the `README.md` diagram, and run a CLI smoke.
- **A capability must cross four doors**: core → CLI → JS (`EngineJsApi`) → WASI (`WasiOptions`),
  then the demo shim, then a row in
  [`docs/ledgers/surface-coverage.md`](docs/ledgers/surface-coverage.md). This has been forgotten
  three times; the matrix is the only check. Defaults come from `EngineConstants`, never a literal
  — the façades once hardcoded 250 W against the CLI's 280 W.
- Curvature estimation and the racing line were tuned by measurement, not reasoning. Read
  [`docs/guides/racing-line.md`](docs/guides/racing-line.md) and ledger rows R23–R26 before
  touching `engine`'s `trajectory` package.

[`CLAUDE.md`](CLAUDE.md) carries the same map in more detail — it is written for coding agents,
but it is the fuller reference for physics conventions and architectural invariants.

## Branches, commits, releases

`develop` is the **default and only long-lived branch** — there is no `main`. Feature work goes on
short-lived topic branches; open PRs against `develop`.

Commits follow [Conventional Commits](https://www.conventionalcommits.org/):

```
type(scope): subject under 100 chars

Optional body explaining the why, wrapped at 100.
```

`feat` triggers a minor release, `fix` a patch, and `test` / `docs` / `chore` / `style` /
`refactor` no release. `feat!:` or a `BREAKING CHANGE:` body for a major. Scopes: `engine`, `gpx`,
`fit`, `map`, `cli`, `elevation`, `codegen`, `plan`, `build`, `deps`.

Every push to `develop` runs the full multi-target suite via `.github/workflows/release.yml` and,
if green, lets semantic-release tag a version, publish to Maven Central and npm, and commit the
version bump back with `[skip ci]`. So **never bump `gradle.properties` by hand** — use
`./gradlew -Pversion=1.2.3 …` to test a version locally.

The full flow is in [`docs/guides/publishing.md`](docs/guides/publishing.md).

## Documentation

- `docs/guides/` and `docs/ledgers/` describe the code **as it is** — keep them true. A capability
  landing without its ledger row is exactly the drift `surface-coverage.md` exists to catch.
- `docs/archive/` is **frozen**: completed plans and 100+ task specs, never retro-patched. Read
  them for the *why*; when a spec and the code disagree, the code is right.
- Module-level documentation lives next to its module ([`cli/`](cli/README.md),
  [`elevation/`](elevation/README.md), [`map/`](map/README.md), [`demo/`](demo/README.md),
  [`tools/wasi/`](tools/wasi/README.md)) and keeps ownership of its subject — the root README
  links to it rather than restating it.

## License

By contributing you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).
