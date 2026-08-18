# CLAUDE.md

Instructions for Claude Code sessions working on **vcyclist** — a Kotlin Multiplatform
physics-based cycling simulator that turns GPS traces into physics-aware virtualized rides.

[`README.md`](README.md) has the pitch and runtime usage. [`docs/README.md`](docs/README.md) is the
documentation index; read it before going looking for anything.

## Modules

| Module | Targets | What it is |
|---|---|---|
| `:gpx` | JVM, JS, wasmWasi | `Path` model (`PointField`, resamplers, `PathSimplifier`) + GPX I/O. Packages stayed `io.github.glandais.engine.{path,gpx}` after the extraction from `:engine`. |
| `:elevation` | JVM, JS, wasmWasi | DEM tile fetching + 3D geometry |
| `:engine` | JVM, JS, wasmWasi | Physics, `Enhancer` pipeline, JS/WASI façades. `api(project(":gpx"))`, so consumers see the whole Path + GPX surface. |
| `:fit` | JVM, JS, wasmWasi | FIT course encoding (`PathToFit`) |
| `:map` | JVM only | Static map rendering on `java.awt`. Depends on `:gpx`/`:elevation`; **nothing may depend on it**. |
| `:cli` | JVM only | picocli command-line tool, shipped as an executable jar, not to Maven Central |
| `:codegen` | JVM only | Regenerates `GeneratedPath.kt` + `PointFieldAccessors.kt` into `:gpx` |
| `:demo` | — | Browser demo, consumes the JS façade via `demo/src/engine-shim.ts` |

## Build commands

```bash
./gradlew check                          # full build + tests, all targets
./gradlew ktlintCheck / ktlintFormat     # lint / auto-format
./gradlew :engine:allTests               # one module, all targets (also :gpx, :elevation, :fit)
./gradlew :engine:jvmTest                # JVM only — fast iteration
./gradlew :engine:jsNodeTest             # also :jsBrowserTest (headless Chrome)
./gradlew :map:test / :cli:test          # JVM-only modules
./gradlew :codegen:run                   # regenerate Path sources after editing PointField
./gradlew :cli:run -Pargs="enhance in.gpx --gpx out.gpx"    # CLI smoke
./gradlew :cli:executableJar             # distributable CLI jar
INTEGRATION=1 ./gradlew :elevation:jvmTest --tests '*Integration*'   # live HTTP
./gradlew :elevation:jsBrowserDevelopmentRun                        # demo with hot reload
```

## Architecture invariants

### `Path` model (`:gpx`)

- `Path` extends the generated `GeneratedPath(size)` and stores 43 fields as flat `DoubleArray`s.
  `PointField.kt` is the single source of truth; `GeneratedPath.kt` and `PointFieldAccessors.kt`
  are **generated** — never hand-edit them, run `:codegen:run`.
- `nanDefault = true` on a field NaN-fills it at construction. Use it whenever *absence* is
  meaningful and the natural zero is a legal value (a `0.0` curvature is a straight line, not
  "not computed").
- `Path` is **fixed-size**. Anything changing cardinality (resample, simplify) builds a new
  `Path(newSize)` with the 2-pass plan/materialize pattern — see `PointPerSecond.kt`.
- Latitude/longitude are stored in **radians**; use `path.latitudeDeg(i)` / `path.coordinatesAt(i)`.
- Go through the generated accessors, never into `data` directly.

### `Enhancer` pipeline order

1. `PointPerDistance(-1, 30)` — densify before DEM lookup
2. `fixElevation` (optional, needs an `ElevationProvider`)
3. `PointPerDistance(1, 2)`
4. `smoothElevation` (150 m kernel, always)
5. `PathCurvature` (writes `trajectoryCurvature`) **or** `RacingLine.compute` when
   `racingLine.enabled` — alternatives, never a sequence
6. `MaxSpeedComputer` (when `virtualizeTrack`) — prefers `trajectoryCurvature` over its own estimate
7. `VirtualizeService` (time-stepping simulation)
8. `PointPerSecond` (1 Hz)
9. `WPrimeBalanceComputer` — pure annotation pass: reads `pComputedPower`, writes `wPrimeBalance`,
   every other field is bit-identical whether it runs or not (`WPrimeBalanceComputerTest` pins this)
10. `PathSimplifier` (Douglas-Peucker 3D)

The order is load-bearing. If you change it, update `Enhancer.kt`'s docstring, the `README.md`
diagram, and run a CLI smoke.

### Physics

- Sign conventions: resistive powers **negative**, cyclist input **positive**, gravity negative
  climbing / positive descending.
- `CoursePhysics` aggregates `Course(path, cyclist, bike)` + rho / aero / wind / cyclistPower providers.
- `PowerComputer` integrates `v_new = √(v_old² + 2·Δt·P / m_eq)`, `m_eq = m + (I_front + I_rear)/r²`.
- `CyclistPowerProvider` impls (`Constant`, `Durability`, `CriticalPower`, `FromData`, `Muscular`)
  plus two decorators composed outermost-last: `PowerProviderTerrainPacing`, then
  `PowerProviderSlewLimited`. The CLI wires `base → pacing → slew`.
- Several providers are **stateful** (`Durability`, `CriticalPower`, `SlewLimited`): one instance
  per simulation, keyed on `pointIndex`, no concurrent use.
- `Cyclist.maxLeanAngleDeg` *is* a tyre friction coefficient (`Cyclist.mu == tanMaxLeanAngle`);
  `RoadCondition` is the preset that sets grip and braking together.
- `MaxSpeedComputer` spends one friction-ellipse budget on cornering and braking together and
  solves it by **bisection** — the map is decreasing, fixed-point iterates oscillate.
- `MuscularPowerProvider` cuts power past `Bike.maxPedalingLeanAngleDeg`, and fails **open** when
  `radius` is absent — failing closed would zero a whole ride.
- Curvature estimation and the racing line are subtle and were tuned by measurement, not reasoning.
  Read [`docs/guides/racing-line.md`](docs/guides/racing-line.md) and ledger rows R23–R26 before
  touching `engine`'s `trajectory` package or `RacingLine`.

### `time` is milliseconds; `elapsed` and `dt` are seconds — everywhere

`TIME` is the only millisecond field, at every moment of the pipeline. `TemporalFieldUnitsTest`
pins it. `DT`'s **window** still changes: backward interval during the simulation, centred
half-interval after `computeDerivedData` — `DX` matches, so `speed = dx / dt` holds in both.

### Kotlin/JS ↔ JS interop

**Read [`docs/guides/kotlin-js-jvm-webp.md`](docs/guides/kotlin-js-jvm-webp.md) before touching
`jsMain/`.** In short: `external interface` for exported DTOs, `js("({})")` + `unsafeCast` to build
plain JS objects, `GlobalScope.promise { … }` to expose `suspend`. Reference façades:
`ElevationJsApi.kt`, `EngineJsApi.kt`.

## Touchpoints

### Adding a `PointField`

Append it last (ordinals are the wire format) → `./gradlew :codegen:run` → update `PointFieldTest`
and `GeneratedPathTest`. `COUNT` is a three-way sync: `PointField.COUNT`, `GeneratePath.FIELDS`,
`GeneratePath.EXPECTED_COUNT`.

### Adding a physics provider

Implement the interface in `engine/src/commonMain/.../physics/`, document any `PointField` it
writes in its KDoc, add it to `CoursePhysics` if it is a first-class strategy, and test the formula
at sentinel inputs.

### Adding a capability users can reach — the four surfaces

Core → CLI → JS (`EngineJsApi`) → WASI (`WasiOptions` + `docs/guides/wasm-wasi-abi.md`) →
`demo/src/engine-shim.ts` → a row in [`docs/ledgers/surface-coverage.md`](docs/ledgers/surface-coverage.md).
This has been forgotten three times; the matrix is the only check.

- A new **power model** is safe: adding a `PowerModel` entry breaks the `when` in `CyclistPowerSpec`
  in `commonMain`, and all three doors parse into that spec.
- A new **option** is not: JS and WASI reject unknown DTO keys, which catches a stale caller but
  never a façade that forgot the field.
- **Defaults come from `EngineConstants`, never a literal** — the façades once hardcoded 250 W
  against the CLI's 280 W.

### Java interop from `commonMain`

**No `kotlin.jvm.*` annotation resolves from a common source set** (`@JvmOverloads`, `@JvmStatic`,
assume the rest). The convention is a `…Jvm.kt` facade in `jvmMain`, same package, `@file:JvmName`,
top-level functions that delegate — plus a Java test in `src/jvmTest/java/`.

- **Never give a facade function the same name *and* signature as the common one** — same package
  means it shadows the original and calls itself.
- `@JvmOverloads` on a constructor makes ktlint re-indent the whole class body; prefer a factory
  function on big classes (see `MapFactoriesJvm`).
- `suspend` needs a bridge or Java cannot call it at all: `…Blocking` returns the value,
  `…Async` returns `CompletableFuture<T>` and takes `executor: Executor? = null` (`null` =
  `Dispatchers.IO`). The `suspend` original stays; the bridge is an addition.

## Conventions

### Branching and commits

`develop` is the only long-lived branch; feature work goes on short-lived topic branches.
semantic-release tags `develop` on every push and commits the version bump back with `[skip ci]` —
so **never bump `gradle.properties` by hand** (use `./gradlew -Pversion=1.2.3 …` to test).
See [`docs/guides/publishing.md`](docs/guides/publishing.md).

```
type(scope): subject under 100 chars

Optional body explaining the why, wrapped at 100.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
```

Types: `feat` (minor bump), `fix` (patch), and `test`/`docs`/`chore`/`style`/`refactor` (no
release). `feat!:` or a `BREAKING CHANGE:` body for a major. Scopes: `engine`, `gpx`, `fit`, `map`,
`cli`, `elevation`, `codegen`, `plan`, `build`, `deps`. Always include the trailer.

### Testing

- `kotlin-test`; `commonTest` for anything portable — prefer it. `jvmTest` for HTTP, file I/O and
  full-pipeline smokes.
- Fixtures go in `gpx/src/commonTestFixtures/.../GpxFixtures.kt` as Kotlin raw strings
  (`commonTest/resources` is not portable). Both `:gpx` and `:engine` wire that directory in.
- `src/jvmTest/java/` sources run as part of `jvmTest` and exist to pin **Java callability**, which
  no Kotlin test can check. The KMP JVM target runs **JUnit 4** — `org.junit.Test`, not JUnit 5.
  Anything Java cannot express goes in a `JvmBridgeFixtures` object beside it.

### Numerical tolerances

Two trig implementations across libc / JS differ by a few ULPs:

| Comparison | Tolerance |
|---|---|
| Trivial constants | `1e-12` |
| Composed trig (Haversine, Vector3D) | `1e-9` |
| Pipeline metrics (totalDistance, durationMs) | 0.5 % relative |
| Elevation | ±1 m (Terrarium tile resolution) |

Never compare `Double` with `==`.

### Documentation

- `docs/guides/` and `docs/ledgers/` describe the code **as it is** — keep them true. A capability
  landing without its ledger row is exactly the drift `surface-coverage.md` exists to catch.
- `docs/archive/` is **frozen**: completed plans and 100+ task specs, mostly French, never
  retro-patched (they still assume a Kotlin/Wasm `wasmJs` target that no longer exists). Read them
  for the *why*; when a spec and the code disagree, the code is right.
- Research-derived work is tracked as `RNN` rows in
  [`docs/ledgers/improvements-ledger.md`](docs/ledgers/improvements-ledger.md), not in a plan.
- The three plans are complete, so new work needs no task spec. When one is warranted, write
  `docs/tasks/<slug>.md` (Goal / Depends on / Inputs / Steps / Outputs / Validation / Done when /
  Notes), then move it to `docs/archive/tasks/` and index it once delivered.

## Gotchas

- Nothing JVM-only in `commonMain` — it must compile on JVM, JS Node, JS browser and wasmWasi.
- Don't disable the timestamp-monotonicity invariant in `VirtualizeService`: letting the input epoch
  leak into `time(n-1)` made `PointPerSecond` OOM. The fix is to simulate every point, last included.
- Don't add the JVM `application { mainClass }` plugin to a KMP module — register a `JavaExec` task
  against the `jvm` target's classpath (see `engine/build.gradle.kts`).

## Tools

`gradle/libs.versions.toml` is the source of truth for versions. Notable: Gradle 9.7 (wrapper),
Kotlin 2.4.20-RC (a release candidate on purpose — wasmtime support in KGP landed in that line),
kotlinx-coroutines, xmlutil, TwelveMonkeys imageio-webp (JVM) / `@jsquash/webp` (JS), ktlint,
picocli (`:cli`), fit-kotlin-sdk (`:fit`).

## Where to find things

| Question | Answer |
|---|---|
| Where is *any* document? | [`docs/README.md`](docs/README.md) |
| What was task `NN` / `gNN` / `wNN` / `tNN`? | [`docs/archive/tasks/README.md`](docs/archive/tasks/README.md) |
| How does a WASI host call the engine? | [`docs/guides/wasm-wasi-abi.md`](docs/guides/wasm-wasi-abi.md), host in [`tools/wasi`](tools/wasi/README.md) |
| How is the `wasmWasi` target built? | [`docs/guides/kotlin-wasm-wasi.md`](docs/guides/kotlin-wasm-wasi.md) |
| Why no Component Model / WIT? | [`docs/archive/plans/wasm-wasi-component-model.md`](docs/archive/plans/wasm-wasi-component-model.md) |
| How does Kotlin/JS export this type? | [`docs/guides/kotlin-js-jvm-webp.md`](docs/guides/kotlin-js-jvm-webp.md) |
| What is the racing line worth? | [`docs/guides/racing-line.md`](docs/guides/racing-line.md), ledger R23–R26 |
| Why is `time(0) = 0`? | `VirtualizeService.kt` KDoc |
| How do I run the CLI? | [`cli/README.md`](cli/README.md) |
| How do I cut a release? | [`docs/guides/publishing.md`](docs/guides/publishing.md) |
| Why this design decision? | The task markdown's "Notes", or its plan in `docs/archive/plans/` |
