# CLAUDE.md

Instructions for future Claude Code sessions working on **vcyclist** — a Kotlin Multiplatform
port of `@glandais/virtual-cyclist` (physics-based cycling simulator).

## Project overview

vcyclist is a multi-module Kotlin Multiplatform project transforming GPS GPX traces into
physics-aware virtualized rides. See [`README.md`](README.md) for the high-level pitch and the
runtime/CLI usage. See [`docs/PLAN.md`](docs/PLAN.md) for the full task-by-task progress with
commit hashes.

The port is structured as:

- `:elevation` — DEM tile fetching + 3D geometry utilities (Phase 1, tasks 00-09 ; Phase 3
  task 32 added Node.js / Bun support via runtime-detection in `TileFetcher.js.kt` + the
  `@jsquash/webp` WASM decoder).
- `:engine` — Path model + physics + GPX I/O + `Enhancer` pipeline + CLI + JS/Wasm façades
  (Phase 2, tasks 10-28 + Phase 2bis 29-31 + Phase 3 task 33 = Node integration tests +
  `ElevationProvider` plumbing in `EngineJsApi.enhance` for `fixElevation: true`).
- `:codegen` — tiny JVM helper that regenerates `GeneratedPath.kt` and `PointFieldAccessors.kt`
  from `PointField` when the field list changes.

## Reference projects (read-only siblings)

These three sibling projects under `../` are the reference / inspiration. **Read them, never
modify them.**

- `../virtual-cyclist/` — TypeScript reference. Canonical for the physics, the Enhancer
  pipeline ordering, and the field model (`src/types/path/fieldDefinitions.ts`).
- `../elevation/` — TypeScript reference for the DEM library that `:elevation` ports.
- `../gpx2web/` — Java reference (gpx2web). Inspirational for the `PowerProvider` strategy
  pattern, `MaxSpeedComputer`, `VirtualizeService`.

## Build commands

From the `vcyclist/` root :

```bash
./gradlew check                          # full build + tests on all targets
./gradlew :engine:allTests               # engine tests (JVM + JS Node + JS browser + Wasm browser)
./gradlew :elevation:allTests            # elevation tests (same targets)
./gradlew :engine:jvmTest                # JVM only (fast iteration)
./gradlew :engine:wasmJsBrowserTest      # Wasm browser tests in headless Chrome (Karma)
./gradlew :engine:jsBrowserTest          # JS browser tests in headless Chrome
./gradlew :engine:jsNodeTest             # JS Node tests
./gradlew ktlintCheck                    # lint
./gradlew ktlintFormat                   # auto-format
./gradlew :engine:run -Pargs="enhance <input.gpx> -o <output.gpx>"   # CLI smoke
INTEGRATION=1 ./gradlew :elevation:jvmTest --tests '*Integration*'   # live HTTP integration tests
```

Browser demos (in `:elevation`) :

```bash
./gradlew :elevation:wasmJsBrowserDevelopmentRun   # Wasm demo with hot-reload
./gradlew :elevation:jsBrowserDevelopmentRun       # Kotlin/JS demo with hot-reload
./gradlew :elevation:wasmJsBrowserDistribution     # static dist → build/dist/wasmJs/productionExecutable/
./gradlew :elevation:jsBrowserDistribution         # static dist → build/dist/js/productionExecutable/
```

## Architecture invariants

### `Path` model (engine)

- `Path` extends `GeneratedPath(size)` and stores **36 fields × `DoubleArray`** flat. Fields
  defined in `engine/src/commonMain/.../path/PointField.kt` (single source of truth).
- `GeneratedPath.kt` and `PointFieldAccessors.kt` are **generated** by the `:codegen` module.
  After editing `PointField`, run `./gradlew :codegen:run` (or follow the regen instructions
  in `engine/src/commonMain/.../path/GeneratedPath.kt` header).
- `Path` is **fixed-size**. Operations that change cardinality (resample, simplify) build a
  new `Path(newSize)` via a 2-pass plan/materialize pattern. See `PointPerSecond.kt`,
  `PointPerDistance.kt`, `PathSimplifier.kt`.
- Latitude / longitude are **stored in radians** (matches `PointField.LATITUDE.unit ==
  "radians"`). Use `path.latitudeDeg(i)` / `path.coordinatesAt(i)` for degree-based access.

### Enhancer pipeline order (must match TS)

1. `PointPerDistance(-1, 30)` — densify before DEM lookup.
2. `fixElevation` (optional, needs `ElevationProvider`).
3. `PointPerDistance(1, 2)` — refine for downstream physics.
4. `smoothElevation` (150 m kernel, always).
5. `MaxSpeedComputer` (always if `virtualizeTrack=true`).
6. `VirtualizeService` (time-stepping simulation).
7. `PointPerSecond` (1 Hz uniform sampling).
8. `PathSimplifier` (Douglas-Peucker 3D).

### Physics

- Resistive forces : `WheelBearingsPowerProvider`, `RollingResistancePowerProvider`,
  `GravPowerProvider`, `AeroPowerProvider` (Isvan model with wind).
- Cyclist input : `CyclistPowerProvider` interface + `Constant`, `ConstantWithTiring`,
  `FromData`, `Muscular` impls. `Base` class adds optional harmonic variations.
- `PowerComputer` integrates the energy equation `v_new = √(v_old² + 2·Δt·P / m_eq)` with
  `m_eq = m + (I_front + I_rear) / r²`.
- `CoursePhysics` aggregates `Course(path, cyclist, bike)` + 4 providers (rho, aero, wind,
  cyclistPower).
- Conventions : resistive powers are **negative**, cyclist input is **positive**, gravity is
  negative climbing / positive descending.

### Kotlin/Wasm ↔ JS interop

The patterns for `@JsExport` façades and WebP decoding are documented exhaustively in
[`docs/kotlin-wasm-jvm-webp.md`](docs/kotlin-wasm-jvm-webp.md). **Always read this guide
before touching `wasmJsMain/` or `jsMain/` code.** Key points :

- Kotlin/Wasm 2.3 restricts `@JsExport` to **top-level functions**. Use a `JsReference<T>`
  opaque handle for class-like APIs.
- DTOs : `external interface … : JsAny` (Wasm) vs `external interface …` (Kotlin/JS).
- Build outputs : `js("({})")` + `unsafeCast` (Kotlin/JS) vs `@JsFun("(…) => ({ … })")` (Wasm).
- `suspend` → wrap in `Promise<T>` via `GlobalScope.promise { … }` (`@OptIn(DelicateCoroutinesApi)`).

The complete reference façades are :

- `elevation/src/wasmJsMain/.../ElevationJsApi.kt` + `engine/src/wasmJsMain/.../EngineJsApi.kt`
- `elevation/src/jsMain/.../ElevationJsApi.kt` + `engine/src/jsMain/.../EngineJsApi.kt`

## Task workflow

Each implementation task lives in `docs/tasks/NN-slug.md` with sections : Goal / Depends on /
Inputs / Steps / Outputs / Validation / Done when / Notes. The conventional flow is :

1. Write the task spec markdown.
2. Dispatch an agent to implement (using `general-purpose` agent).
3. Validate (tests green on all targets, ktlint OK, working tree clean).
4. Check off the `[x]` boxes in the task markdown.
5. Two commits per task : `feat(engine|elevation): <subject> (Phase N task NN)` then
   `docs(plan): mark task NN done in PLAN.md`.

`docs/PLAN.md` is the canonical progress tracker. Update its `Avancement` table after each task.

## Conventions

### Branching

`develop` is the **default and only long-lived branch** (no separate `main`). Feature work
happens on short-lived topic branches that PR back into `develop` ; semantic-release tags
`develop` directly on each push and commits the version bump + changelog back to `develop`
with `[skip ci]`. See [`docs/publishing.md`](docs/publishing.md) for the release flow.

### Commit messages

```
type(scope): subject under 100 chars

Optional body explaining the why, wrapped at 100.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
```

Types : `feat`, `fix`, `test`, `docs`, `chore`, `style`, `refactor`. Scopes : `engine`,
`elevation`, `codegen`, `plan`, `build`, `deps`. Always include the
`Co-Authored-By` trailer.

**The commit type drives the release** : `feat:` triggers a minor bump, `fix:` a patch
bump, anything else is a no-op for semantic-release. Use `feat!:` or include `BREAKING
CHANGE:` in the body for a major bump. The release pipeline runs on every push to
`develop`. See [`docs/publishing.md`](docs/publishing.md) for the full flow.

### Release versioning

`gradle.properties` always reflects the **last released** version, and semantic-release
rewrites it via `sed` (configured in `.releaserc.json`'s `@semantic-release/exec.prepareCmd`)
on every release. **Do not bump it manually** — to test a future version locally, use
`./gradlew -Pversion=1.2.3 …`. Between releases, `gradle.properties` may show a version
that is one behind the working tree's actual content (because semantic-release commits the
bump with `[skip ci]` and pushes it back to `develop`).

### Testing

- Use `kotlin-test` (multiplatform). Add `kotlinx-coroutines-test` if you need `runTest`.
- `commonTest` for portable logic — runs on every target. Prefer this whenever possible.
- `jvmTest` for JVM-only integration (HTTP servers, file I/O, full pipeline smokes).
- Inline test fixtures (e.g. GPX XML) as Kotlin raw strings in
  `engine/src/commonTest/kotlin/.../gpx/GpxFixtures.kt` — `commonTest/resources` is not
  portable across targets, but referenced files exist there for human / git diff readability.

### Numerical tolerances

Two `sin`/`cos`/`atan2`/`sqrt` implementations across libc / JS / Wasm produce slightly
different ULPs. Use these tolerances :

- Trivial constants : `1e-12`.
- Composed trig (Haversine, Vector3D distances) : `1e-9`.
- Full pipeline metrics (totalDistance, durationMs) : `0.5 %` relative.
- Elevation : `±1 m` (Terrarium tile resolution).

### Parity strategy

Parity tests are **self-referential** : `ParityFixtures.kt` hard-codes the Kotlin pipeline's
output on each input GPX, with a 0.5 % regression budget. See `docs/parity.md` for the
rationale (the TS reference is not executable in CI). When a pipeline change shifts the
output by more than the budget, regenerate the fixture values (run the pipeline once,
copy-paste, commit with a comment).

## Codebase touchpoints

### Adding a new `PointField`

1. Edit `engine/src/commonMain/.../path/PointField.kt`.
2. Run `./gradlew :codegen:run` (or invoke the regen script — see `:codegen/README.md`).
3. Update unit tests : `PointFieldTest` count, `GeneratedPathTest` round-trip.
4. The new field is now accessible via `path.<name>(i)` / `path.set<Name>(i, v)` and via the
   generic `path.get(i, field)` / `path.set(i, field, v)`.

### Adding a new physics provider

1. Implement the `PowerProvider` / `RhoProvider` / `WindProvider` interface in
   `engine/src/commonMain/.../physics/`.
2. If it has side-effects on the Path (writes to a `PointField`), document them in the
   provider's KDoc.
3. Add it to `CoursePhysics` if it's a 1ˢᵗ-class strategy, or instantiate it ad-hoc in tests
   and the Enhancer.
4. Add tests in `engine/src/commonTest/.../physics/` covering the formula at sentinel inputs.

### Touching `Enhancer`

The pipeline ordering matches the TS reference (see *Architecture invariants* above). If you
reorder steps, update the docstring of `Enhancer.kt`, the [`README.md`](README.md) ASCII
diagram, and run a smoke through `EngineCli` to verify the GPX output makes sense.

## What not to do

- Don't modify the sibling reference projects (`../virtual-cyclist`, `../elevation`,
  `../gpx2web`). They are read-only inspiration.
- Don't add JVM-only dependencies to `commonMain` source sets. Anything in `commonMain` must
  compile on JVM + JS Node + JS browser + Wasm browser.
- Don't bypass `Path`'s generated accessors by writing into `data` directly. The accessors
  are the only sanctioned API.
- Don't introduce floating-point comparisons via `==` on `Double` — always use a tolerance,
  even on "exact" arithmetic, because `0.1 + 0.2 != 0.3` everywhere on Earth.
- Don't disable the timestamp-monotonicity invariant in `VirtualizeService` (a pre-Phase 2bis
  bug let the input epoch leak into `time(n-1)`, causing `PointPerSecond` to OOM — the fix is
  to simulate every point including the last). Document in CHANGELOG if you have a reason to
  change this.
- Don't add JVM-only `application { mainClass }` plugin to a KMP module — use
  `tasks.register<JavaExec>("run") { … }` pulling `kotlin.targets.getByName("jvm")` classpath
  (cf. `engine/build.gradle.kts`).

## Tools

- Gradle 9.5.0 (wrapper)
- Kotlin 2.3.21 (KMP)
- kotlinx-coroutines 1.11.0
- kotlinx-browser 0.5.0 (js + wasmJs)
- xmlutil 0.91.3 (multi-target XML)
- TwelveMonkeys imageio-webp 3.13.1 (JVM WebP)
- ktlint 14.2.0

## Where to find things

| Question | Answer |
|---|---|
| What is task N about ? | `docs/tasks/N-slug.md` (and the `Avancement` table in `docs/PLAN.md`) |
| Why this design decision ? | The relevant task markdown's "Notes" section, or `docs/PLAN.md` if architectural |
| How does Kotlin/Wasm export this type ? | `docs/kotlin-wasm-jvm-webp.md` |
| What's the TS equivalent of `<class>` ? | Same name in `../virtual-cyclist/src/` — Kotlin file's KDoc names the TS source |
| Why does this fixture have these numbers ? | `engine/src/commonTest/.../parity/ParityFixtures.kt` + `docs/parity.md` |
| Why is `time(0) = 0` ? | `VirtualizeService.kt` KDoc (relative-time simulation) |
| How to run the CLI ? | [`README.md`](README.md) Quick start, or `./gradlew :engine:help` |
| How to cut a release / publish to npm or Maven Central ? | [`docs/publishing.md`](docs/publishing.md) |
