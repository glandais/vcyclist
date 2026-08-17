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
  `@jsquash/webp` WASM decoder, a third-party WebP codec unrelated to the Kotlin/Wasm target).
- `:gpx` — Path model (`Path`, `PointField`, resamplers, `PathSimplifier`, `ElevationStep`) +
  GPX I/O, extracted from `:engine` by gpx2web task g01. **Package names are unchanged**
  (`io.github.glandais.engine.path.*`, `io.github.glandais.engine.gpx.*`) — only the Gradle
  module that hosts them moved. Published to Maven Central as `vcyclist-gpx`, **not** to npm.
- `:engine` — physics + `Enhancer` pipeline + CLI + JS façades (Phase 2, tasks 10-28 +
  Phase 2bis 29-31 + Phase 3 task 33 = Node integration tests + `ElevationProvider` plumbing
  in `EngineJsApi.enhance` for `fixElevation: true`). Does `api(project(":gpx"))`, so
  consumers of `:engine` keep seeing the whole Path + GPX surface.
- `:map` — **JVM-only** static map rendering: Web Mercator projection (`MapSpace`) and image
  framing (`MapImage`), on `java.awt` / `ImageIO`. Uses the `kotlin-jvm` plugin, not KMP, so it
  has no `commonMain` and the three-target invariant is untouched — but **nothing may depend on
  it**: the arrow only points from `:map` into `:gpx` / `:elevation`.
- `:cli` — **JVM-only** command-line tool on picocli, replacing gpx2web's `gpxtools-cli`.
  Deliberately **not** published to Maven Central: it is an application, distributed as an
  executable jar (`./gradlew :cli:executableJar`). Parameter defaults come from
  `EngineConstants`, never copied — a cross-assertion test enforces that.
- `:codegen` — tiny JVM helper that regenerates `GeneratedPath.kt` and `PointFieldAccessors.kt`
  from `PointField` when the field list changes (writes into `:gpx` since g01).

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
./gradlew :engine:allTests               # engine tests (JVM + JS Node + JS browser)
./gradlew :gpx:allTests                  # Path model + GPX I/O tests (same targets)
./gradlew :map:test                      # static map rendering (JVM only)
./gradlew :cli:test                      # CLI option parsing (JVM only)
./gradlew :cli:run -Pargs="--help"       # run the CLI
./gradlew :elevation:allTests            # elevation tests (same targets)
./gradlew :engine:jvmTest                # JVM only (fast iteration)
./gradlew :engine:jsBrowserTest          # JS browser tests in headless Chrome
./gradlew :engine:jsNodeTest             # JS Node tests
./gradlew ktlintCheck                    # lint
./gradlew ktlintFormat                   # auto-format
./gradlew :cli:run -Pargs="enhance <input.gpx> --gpx <output.gpx>"   # CLI smoke
INTEGRATION=1 ./gradlew :elevation:jvmTest --tests '*Integration*'   # live HTTP integration tests
```

Browser demos (in `:elevation`) :

```bash
./gradlew :elevation:jsBrowserDevelopmentRun       # Kotlin/JS demo with hot-reload
./gradlew :elevation:jsBrowserDistribution         # static dist → build/dist/js/productionExecutable/
```

## Architecture invariants

### `Path` model (`:gpx`)

- `Path` extends `GeneratedPath(size)` and stores **39 fields × `DoubleArray`** flat. Fields
  defined in `gpx/src/commonMain/.../path/PointField.kt` (single source of truth).
- A field may declare `nanDefault = true`, which makes `:codegen` NaN-fill that slot at
  construction instead of leaving it `0.0`. Use it whenever *absence* is meaningful and the
  natural zero is a legal value — `trajectoryCurvature` is the first: a `0.0` curvature is a
  straight line, not "not computed", so without the flag every `isNaN()` sentinel reading it is
  dead and `MaxSpeedComputer` would cap the whole route.
- `GeneratedPath.kt` and `PointFieldAccessors.kt` are **generated** by the `:codegen` module.
  After editing `PointField`, run `./gradlew :codegen:run` (or follow the regen instructions
  in `gpx/src/commonMain/.../path/GeneratedPath.kt` header).
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
4b. `PathCurvature` — writes `trajectoryCurvature`, moves nothing. **Not in TS.**
    *Or* `RacingLine.compute` when `racingLine.enabled` (off by default), which replaces every
    coordinate with an optimised trajectory and writes the same field for the line it built. The
    two are alternatives, never a sequence.
5. `MaxSpeedComputer` (always if `virtualizeTrack=true`) — prefers that field over its own estimate.
6. `VirtualizeService` (time-stepping simulation).
7. `PointPerSecond` (1 Hz uniform sampling).
8. `WPrimeBalanceComputer` (W′ balance annotation) — **not in TS**, see below.
9. `PathSimplifier` (Douglas-Peucker 3D).

Step 8 is a **deliberate divergence** from the TS reference, which has no physiological layer.
It is an annotation pass : it reads `pComputedPower`, writes the `wPrimeBalance` field and
touches nothing else, so the other 36 fields — the ones the TS reference also has — are
bit-identical whether it runs or not (`WPrimeBalanceComputerTest` pins exactly that). Making the
simulated rider *react* to a low W′ is a separate change — see
[`docs/research/improvements-ledger.md`](docs/research/improvements-ledger.md) R16.

### Physics

- Resistive forces : `WheelBearingsPowerProvider`, `RollingResistancePowerProvider`,
  `GravPowerProvider`, `AeroPowerProvider` (Isvan model with wind).
- Cyclist input : `CyclistPowerProvider` interface + `Constant`, `Durability`, `CriticalPower`,
  `FromData`, `Muscular` impls. `Base` class adds optional harmonic variations. `PowerProviderDurability`
  fades power with work accumulated **above CP** (intensity-weighted, per the durability
  literature). `PowerProviderCriticalPower` carries a running W′ balance — the same
  `WPrimeBalanceComputer.step` the post-pipeline field uses — and tapers its target toward CP as
  the reserve empties. Both are **stateful**, as is `PowerProviderSlewLimited` : one instance per
  simulation, no concurrent use, keyed on `pointIndex`. They replaced
  `PowerProviderConstantWithTiring`, whose elapsed-time decay had no source.
- `PowerComputer` integrates the energy equation `v_new = √(v_old² + 2·Δt·P / m_eq)` with
  `m_eq = m + (I_front + I_rear) / r²`.
- `CoursePhysics` aggregates `Course(path, cyclist, bike)` + 4 providers (rho, aero, wind,
  cyclistPower).
- Conventions : resistive powers are **negative**, cyclist input is **positive**, gravity is
  negative climbing / positive descending.
- `Cyclist.maxLeanAngleDeg` **is** a tyre friction coefficient : `v_max = √(g·R·tan θ)` is
  `√(µ·g·R)`, so `Cyclist.mu == tanMaxLeanAngle`. [`RoadCondition`] is the preset that sets µ and
  braking together (`DRY` reproduces the shipped defaults bit-for-bit ; `WET` is 40 % of the grip).
- Two **decorators** (**not in TS**), composed outermost-last :
  `PowerProviderTerrainPacing` (harder uphill / into headwind, rise dispersed over ~300 m, fall
  immediate, with a causal energy account so it redistributes rather than adds) and
  `PowerProviderSlewLimited` (caps |ΔP| per second, 50 W/s). The CLI wires them
  `base → pacing → slew`.
- **Friction ellipse** (**not in TS**) : `MaxSpeedComputer` spends one grip budget on cornering and
  braking together — `a_x = a_xmax·√(1 − (a_y/a_ymax)²)` — so a rider at full lean cannot brake.
  Solved by **bisection**, not fixed-point iteration : the map is decreasing and iterates oscillate
  outside the ellipse.
- **Pedal-strike clearance** (**not in TS**) : `MuscularPowerProvider` delivers no power past
  `Bike.maxPedalingLeanAngleDeg` (20°), lean being `atan(v²/(g·R))` from the path's own `speed` and
  `radius`. Set 90 to disable. It fails *open* when `radius` is absent — `MaxSpeedComputer` may not
  have run — because failing closed would zero a whole ride.
- **Curvature estimation** (**not in TS**, ledger R23) : `:engine`'s `trajectory` package regresses
  unwrapped heading on arclength in a *single anchored* planar frame and writes
  `trajectoryCurvature`; `MaxSpeedComputer` reads it in preference to its ±10-point bearing
  difference. Retires three real defects — the `normalizeAngleDiff` ±π wrap (a bend under ~9.5 m
  read as twice as open), the fixed *point*-count window (radius depended on the resampler), and
  `computeBearing`'s `x = lon·cos(lat)` shear (4.2° at 6°E/45°N, growing with longitude). Rides get
  **slower** by 0.2–9 %; `MEASURE=1 ./gradlew :engine:jvmTest --tests '*CurvatureMeasurementTest*'`
  reproduces the table. Two non-obvious constraints, both found by measurement and both commented
  at their call sites : heading must be regressed against the **smoothed** curve's own arclength,
  and the scale-selection allowance must be measured from the trace at the **widest** window.
- **Racing line** (**not in TS**, ledger R24) : `RacingLine.compute` solves for a lateral offset
  `n(s)` minimising a convex quadratic energy over a corridor box — projected Newton on a
  pentadiagonal `LDLᵀ`. Off by default because it **rewrites every coordinate**; the originals are
  kept in `sourceLatitude`/`sourceLongitude`. `CorridorMode.LANE` (default) keeps `n = 0` feasible,
  so straights are untouched and the line never crosses the centreline; `FULL_ROAD` is closed-road
  only. Three traps, all measured rather than reasoned : the iteration cap must be ~200 not 12, the
  analytic out–in–out seed makes it *slower*, and the trajectory curvature must be **re-measured**
  on the materialised path rather than taken from the analytic offset formula — the latter reads
  `n''` off a finite difference and spikes at every corridor-bound kink, which made rides 16–27 %
  slower until it was fixed.
- `pBrake` (**not in TS**) records the energy `VirtualizeService`'s `speedMax` clip removes, as
  `min(0, pComputedWheelPower)` — negative, at the wheel, so **not** divided by drivetrain
  efficiency. A speed cap the rider merely sits at is not braking : resistance alone explains it.

### Kotlin/JS ↔ JS interop

The patterns for `@JsExport` façades and WebP decoding are documented exhaustively in
[`docs/kotlin-js-jvm-webp.md`](docs/kotlin-js-jvm-webp.md). **Always read this guide
before touching `jsMain/` code.** Key points :

- DTOs : `external interface …` for the exported JS-facing types.
- Build outputs : `js("({})")` + `unsafeCast` to assemble plain JS objects from Kotlin/JS.
- `suspend` → wrap in `Promise<T>` via `GlobalScope.promise { … }` (`@OptIn(DelicateCoroutinesApi)`).
- WebP decoding on JS relies on `@jsquash/webp`, which itself loads a `.wasm` binary at
  runtime — that third-party codec is unrelated to (and unaffected by) the removal of the
  Kotlin/Wasm compilation target.

The complete reference façades are :

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
`gpx`, `fit`, `map`, `cli`, `elevation`, `codegen`, `plan`, `build`, `deps`. Always include the
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
  `gpx/src/commonTestFixtures/kotlin/.../gpx/GpxFixtures.kt` — `commonTest/resources` is not
  portable across targets, but referenced files exist there for human / git diff readability.
  That directory is added as an extra `commonTest` source dir by **both** `gpx/build.gradle.kts`
  and `engine/build.gradle.kts` : KMP has no `java-test-fixtures`, and `:engine`'s JS-façade
  tests need the same strings.
- **Java sources in `src/jvmTest/java/`** are compiled and run as part of `jvmTest` (`:elevation`,
  `:gpx`, `:engine` each have some since g22). They exist to pin *Java callability*, which no
  Kotlin test can check — from Kotlin every call compiles whether the JVM bridges and
  `@JvmOverloads` exist or not. The KMP JVM target runs **JUnit 4** (no `useJUnitPlatform()`), so
  use `org.junit.Test` and `org.junit.Assert.*` there, not JUnit 5. Kotlin and Java sources of a
  test compilation see each other, so anything Java cannot express (a `suspend` lambda, a default
  argument) goes in a `JvmBridgeFixtures` object next to it.

### `elapsed` and `dt` are seconds after the pipeline, not ms

`PointField.ELAPSED` and `DT` declare **ms**, and `VirtualizeService` writes ms — but
`Path.computeDerivedData` rewrites both in **seconds** (`(time − timeStart) / 1000`, `Δtime / 2000`)
and runs last, so a finished path carries seconds under fields labelled ms. The TS reference does
the same (`Path.ts:219`), so it is inherited, not introduced.

Consequence for new code: **read `time` (unambiguously ms) when you need an interval outside the
simulation.** Providers are safe because they run *during* it, where the millisecond values are
still in place — `WPrimeBalanceComputer` runs after, read `dt`, and silently under-integrated the
whole W′ balance by 1000× until R16 caught it.

### Numerical tolerances

Two `sin`/`cos`/`atan2`/`sqrt` implementations across libc / JS produce slightly
different ULPs. Use these tolerances :

- Trivial constants : `1e-12`.
- Composed trig (Haversine, Vector3D distances) : `1e-9`.
- Full pipeline metrics (totalDistance, durationMs) : `0.5 %` relative.
- Elevation : `±1 m` (Terrarium tile resolution).

## Codebase touchpoints

### Adding a new `PointField`

1. Edit `gpx/src/commonMain/.../path/PointField.kt` (append last — ordinals are the wire format;
   set `nanDefault = true` if the natural zero would be misread as a value).
2. Run `./gradlew :codegen:run` (or invoke the regen script — see `:codegen/README.md`).
3. Update unit tests : `PointFieldTest` count, `GeneratedPathTest` round-trip. **`COUNT` is a
   three-way sync** — `PointField.COUNT`, `GeneratePath.FIELDS`, `GeneratePath.EXPECTED_COUNT` —
   plus the JS façade's `fieldDefinitions` test, which binds to `PointField.COUNT` rather than a
   literal so it does not become a fourth.
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

### Adding a capability users can reach (the four surfaces)

The engine has **four doors** — core, CLI, JS façade, WASI façade — plus the demo behind JS. A
capability added to the core does not reach them by itself, and three times running it did not:
`g29`, then tasks `41` and `43`. R17 renamed one string on one surface and left the demo broken
for nine ledger entries.

So, in order: core → CLI → JS (`EngineJsApi`) → WASI (`WasiOptions` + `docs/wasm-wasi-abi.md`) →
`demo/src/engine-shim.ts` → the ledger's `Surfaces` line. The matrix and the reasoning live in
[`docs/surface-coverage.md`](docs/surface-coverage.md).

Two mechanical guards exist, and neither covers everything:

- **A new cyclist power model needs no façade work.** Add a `PowerModel` entry and the `when` in
  `CyclistPowerSpec` stops compiling until it is handled — in `commonMain`, so on all three
  targets at once. The CLI, JS and WASI all parse into a `CyclistPowerSpec`, so they inherit it,
  along with the `base → pacing → slew` composition order.
- **A new *option* is not covered.** JS and WASI reject unknown DTO keys (`requireOnlyKeys` /
  `requireOnly`), which catches a stale *caller* but not a façade that never exposed the field.
  That one is on the checklist above.

**Defaults come from `EngineConstants`, never a literal.** The JS and WASI façades each hardcoded
250 W while the CLI read `DEFAULT_CYCLIST_POWER_W` = 280 W, so the same unconfigured rider rode
differently depending on which door you came through. Found in task 43, by reading the three
surfaces side by side rather than by any test.

### Adding a public function with default arguments

Kotlin defaults do not exist for Java callers, and **`@JvmOverloads` cannot be resolved from a
common source set** (verified on Kotlin 2.3.21 in task g23). So a `commonMain` API with defaults
is only reachable from Java in its longest form.

The convention (task g27): a `…Jvm` facade file in `jvmMain`, same package, `@file:JvmName`, whose
top-level functions carry `@JvmOverloads` and delegate. Two traps found while writing them:

- **Never give a facade function the same name *and* signature as the common one.** Same package
  means it shadows the original for Kotlin callers compiled against that source set — and calls
  itself. `GpxFromPathJvm.toGpxDocument` is named that way because the first attempt was called
  `pathsToGpxDocument` and blew the stack.
- **`@JvmOverloads` on a constructor** forces ktlint to move it to its own line, which re-indents
  the whole class body. Fine for a small class, a thousand lines of churn for a big one — prefer a
  factory function in that case (see `MapFactoriesJvm`).

A `val` in an `object` reaches Java as `Xxx.INSTANCE.getFoo()`; make it `const val` when it is a
compile-time constant.

**No `kotlin.jvm.*` annotation resolves from a common source set** — `@JvmOverloads` (g23),
`@JvmStatic` (g33), and expect the same of the rest of the family. Don't test them one at a time:
if the declaration lives in `commonMain`, the answer is a `…Jvm` facade, whatever the annotation
would have done.

### Adding a public `suspend` function

Anything `suspend` on the public surface needs a JVM bridge, or Java consumers cannot call it at
all (a `Continuation` is not something anyone writes by hand). Task g22 set the convention — two
shapes, named identically everywhere:

| Shape | Suffix | Returns |
|---|---|---|
| Blocking | `…Blocking` | the value, exceptions unchanged |
| Asynchronous | `…Async` | `CompletableFuture<T>`, cancellation propagated to the coroutine |

1. Add them to the module's `…Jvm.kt` file in `jvmMain` (`ElevationProviderJvm`,
   `ElevationStepJvm`, `EnhancerJvm`) — top-level functions under `@file:JvmName(...)`, so Java
   sees a static utility class rather than `Xxx.INSTANCE` as the first argument.
2. `…Async` takes `executor: Executor? = null` (JDK type, not `CoroutineDispatcher`: Java callers
   have executors, and the coroutines dependency stays `implementation`). `null` means
   `Dispatchers.IO`.
3. `@JvmOverloads` on anything with a default, and a Java test in `src/jvmTest/java/`.

Nothing JVM-only goes into `commonMain`, and the `suspend` original stays as it is — the bridge
is an addition, never a replacement.

### Touching `Enhancer`

The pipeline ordering matches the TS reference (see *Architecture invariants* above). If you
reorder steps, update the docstring of `Enhancer.kt`, the [`README.md`](README.md) ASCII
diagram, and run a smoke through the CLI (`./gradlew :cli:run -Pargs="enhance …"`) to verify
the GPX output makes sense.

## What not to do

- Don't modify the sibling reference projects (`../virtual-cyclist`, `../elevation`,
  `../gpx2web`). They are read-only inspiration.
- Don't add JVM-only dependencies to `commonMain` source sets. Anything in `commonMain` must
  compile on JVM + JS Node + JS browser.
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

`gradle/libs.versions.toml` is the source of truth — this list is a convenience copy, so check the
catalogue before trusting a number here.

- Gradle 9.7.0 (wrapper)
- Kotlin **2.4.20-RC** (KMP) — a release candidate, deliberately: wasmtime support in KGP landed in
  the 2.4.20 line, and 2.4.20 final is not on Maven Central yet. Task w08 does the bump and blocks
  the real publication (w07).
- kotlinx-coroutines 1.11.0
- kotlinx-browser 0.5.0 (js)
- xmlutil 1.0.2 (multi-target XML)
- TwelveMonkeys imageio-webp 3.14.0 (JVM WebP)
- ktlint 14.2.0 (its embedded compiler is 2.1.0, hence the `sun.misc.Unsafe` warnings in build logs)
- picocli 4.7.7 (`:cli`), fit-kotlin-sdk 21.213.0 (`:fit`)

## Where to find things

| Question | Answer |
|---|---|
| What is task N about ? | `docs/tasks/N-slug.md` (and the `Avancement` table in `docs/PLAN.md`) |
| What is task gNN / wNN about ? | `docs/PLAN-GPX2WEB.md` / `docs/PLAN-WASM-WASI.md` + the matching `docs/tasks/` file |
| How does the `wasmWasi` target work ? | [`docs/kotlin-wasm-wasi.md`](docs/kotlin-wasm-wasi.md) (engineering notes), `docs/PLAN-WASM-WASI.md` (the work) |
| Why no Component Model / WIT ? | [`docs/wasm-wasi-component-model.md`](docs/wasm-wasi-component-model.md) — w13's measured verdict; [`tools/wasi-component`](tools/wasi-component/README.md) replays it |
| How does a WASI host call the engine ? | [`docs/wasm-wasi-abi.md`](docs/wasm-wasi-abi.md) — imports, exports, error codes. A working host: [`tools/wasi`](tools/wasi/README.md) |
| Why this design decision ? | The relevant task markdown's "Notes" section, or `docs/PLAN.md` if architectural |
| How does Kotlin/JS export this type ? | `docs/kotlin-js-jvm-webp.md` |
| What's the TS equivalent of `<class>` ? | Same name in `../virtual-cyclist/src/` — Kotlin file's KDoc names the TS source |
| Why is `time(0) = 0` ? | `VirtualizeService.kt` KDoc (relative-time simulation) |
| How to run the CLI ? | [`cli/README.md`](cli/README.md) — usage, exit codes, and the gpxtools-cli migration table |
| Where did gpx2web's `<class>` go ? | [`docs/gpx2web-coverage.md`](docs/gpx2web-coverage.md) — one row per Java class, ported / replaced / not ported with the reason |
| How to cut a release / publish to npm or Maven Central ? | [`docs/publishing.md`](docs/publishing.md) |
