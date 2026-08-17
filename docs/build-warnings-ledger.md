# Build warnings ledger

Inventory of everything `./gradlew clean build` emits that is not a plain task line. Sections A-G
below are the **baseline measurement**, written before anything was changed: they describe the build
as it was on 2026-08-17, and they are deliberately left as measured so the fixes have something to
be diffed against. Each entry has an ID.

**Current status is the [Resolution](#resolution) section at the end** — read that first if you want
to know what the build emits *today*, then come back here for the detail of any single finding.

## How this was measured

```bash
./gradlew clean build                                     # baseline (heavily cached)
./gradlew clean build --warning-mode all --no-build-cache  # the run this ledger is based on
```

The uncached run is the authoritative one: with the build cache on, ~147 of 313 tasks resolve
`FROM-CACHE` and their Kotlin compiler warnings are **never replayed**, so a cached build looks far
quieter than it is.

- Date of measurement: 2026-08-17
- Result: **BUILD SUCCESSFUL** in 1 m 51 s, 313 actionable tasks (300 executed, 13 up-to-date)
- Toolchain actually resolved: Gradle **9.7.0** (wrapper), Kotlin **2.4.20-RC**
  (`gradle/libs.versions.toml`), ktlint plugin 14.2.0, webpack 5.108.1
- Full logs kept out of git; regenerate with the commands above

## Headline numbers

| Signal | Count |
|---|---|
| Compilation / test **errors** | **0** |
| Test failures / errors | **0** (3 681 test executions, 0 skipped) |
| ktlint violations | **0** |
| Gradle deprecation warnings (own scripts) | 1 distinct site |
| Kotlin compiler warnings | 74 distinct source locations |
| Kotlin compiler warnings (raw `w:` lines, incl. per-target repeats) | 75 |
| KGP misconfiguration warnings | 1 |
| webpack warnings | 3 per bundle × 2 bundles (`:fit`, `:engine`) |
| JVM `sun.misc.Unsafe` warnings | 14 occurrences (1 per Kotlin daemon compile task) |
| Mocha reporter warnings | 4 (one per `js*Test` module) |

Test executions per module/target (all green):

| Module | jvm | jsNode | jsBrowser | wasmWasi | jvm-only |
|---|---|---|---|---|---|
| `:elevation` | 322 | 300 | 300 | 303 | — |
| `:gpx` | 265 | 247 | 247 | 247 | — |
| `:engine` | 255 | 268 | 268 | 279 | — |
| `:fit` | 70 | 69 | 69 | 63 | — |
| `:map` | — | — | — | — | 54 |
| `:cli` | — | — | — | — | 55 |

## A. Errors

**None.** No `e:` line, no failed task, no test failure. Everything below is a warning.

## B. Gradle-level warnings

### B1 — Deprecated `by registering { }` delegate syntax (Gradle 10 removal)

```
> Configure project :cli
The 'val name by registering { }' property delegate syntax has been deprecated. This is scheduled
to be removed in Gradle 10. Use 'val element = register(name) { }' instead.
	at Build_gradle.<init>(build.gradle.kts:35)
```

- Location: `cli/build.gradle.kts:35` — `val generateVersionProperties by tasks.registering { … }`
- This is the **only** deprecation attributable to project scripts. It is also what makes the
  baseline run print *"Deprecated Gradle features were used in this build, making it incompatible
  with Gradle 10."*
- Only surfaces under `--warning-mode all`.

### B2 — Configuration cache not enabled (informational)

```
Consider enabling configuration cache to speed up this build
```

Printed on every run. Not a warning about correctness; recorded because it is part of the output.

### B3 — Incubating problems report

```
[Incubating] Problems report is available at: build/reports/problems/problems-report.html
```

Informational. The HTML report is the structured form of the compiler warnings in section C.

### B4 — Gradle daemon churn (baseline run only)

```
Starting a Gradle Daemon, 1 busy and 2 incompatible Daemons could not be reused
```

Environmental (leftover daemons on the dev machine), not a project defect. Logged for completeness.

## C. Kotlin compiler warnings

74 distinct source locations. Grouped by cause, largest first. C1–C4 arrive as `w:` lines on the
console; C5 and C6 appear **only** in the problems report / under `--warning-mode all`.

### C1 — `ExperimentalWasmInterop` opt-in missing — 36 sites

```
w: … This declaration needs opt-in. Its usage should be marked with
   '@kotlin.wasm.ExperimentalWasmInterop' or '@OptIn(kotlin.wasm.ExperimentalWasmInterop::class)'
```

| File | Sites |
|---|---|
| `engine/src/wasmWasiMain/kotlin/io/github/glandais/engine/wasi/EngineWasiApi.kt` | 35 |
| `elevation/src/wasmWasiMain/kotlin/io/github/glandais/elevation/HostTileSource.kt` | 1 |

`EngineWasiApi.kt` lines: 108, 114, 233, 240, 244, 248, 257, 280, 301, 311, 320, 334, 338, 342,
346, 350, 354, 361, 381, 388, 408, 436, 459, 490, 560, 607, 630, 649, 659, 679, 706, 718, 733,
755, 776. `HostTileSource.kt`: line 120.

Note: this is a **new** warning class relative to the toolchain CLAUDE.md documents — it appears
with Kotlin 2.4.20-RC's annotation on the wasm interop API surface.

### C2 — `@JsExport` non-exportable types — 32 sites

```
w: … Exported declaration uses non-exportable return type 'Path'.
w: … Exported declaration uses non-exportable parameter type 'Path'.
```

| File | Sites | Types involved |
|---|---|---|
| `engine/src/jsMain/kotlin/io/github/glandais/engine/EngineJsApi.kt` | 29 | `Path` (20), `Array<Path>` (7), `Promise<Path>` (2) |
| `elevation/src/jsMain/kotlin/io/github/glandais/elevation/ElevationJsApi.kt` | 3 | `ElevationProvider` (3) |

`EngineJsApi.kt` lines: 212, 223, 230, 239, 247, 262, 285, 288, 291, 294, 297, 301, 324, 339, 351,
353, 478, 480, 507, 540, 547, 562, 578, 594, 612, 641, 645, 716, 724.
`ElevationJsApi.kt` lines: 73, 89, 100.

These are the expected consequence of the opaque-handle façade pattern documented in
`docs/kotlin-js-jvm-webp.md` — `Path` / `ElevationProvider` cross the JS boundary as opaque
handles on purpose. Recorded so the count is pinned and a future regression is visible.

### C3 — `ExperimentalCoroutinesApi` opt-in missing — 3 sites

```
w: … This declaration needs opt-in. Its usage should be marked with
   '@kotlinx.coroutines.ExperimentalCoroutinesApi' or '@OptIn(...)'
```

- `elevation/src/commonTest/kotlin/io/github/glandais/elevation/FluxTest.kt:48`
- `elevation/src/commonTest/kotlin/io/github/glandais/elevation/LruCacheTest.kt:111`
- `elevation/src/commonTest/kotlin/io/github/glandais/elevation/LruCacheTest.kt:137`

Being in `commonTest`, each is re-reported once per target compilation (6 raw lines for 3 sites).

### C4 — `Condition is always 'true'` — 1 site

- `elevation/src/wasmWasiTest/kotlin/io/github/glandais/elevation/TileFetcherStubTest.kt:145:24`
- Code: `assertTrue(elevations.single().elevation != null, …)` — the `wasmWasi` `elevation` type is
  apparently non-nullable there, making the comparison vacuous.

### C5 — `DANGEROUS_CHARACTERS` — 1 site

```
Name contains character(s) that can cause problems on Windows: "
Location: cli/src/test/kotlin/io/github/glandais/cli/command/EnhanceCommandTest.kt line 268
```

- The backticked test name `` `case 14 — xlsx is refused with a pointer to csv, not "unknown option"` ``
  contains double quotes, which are illegal in Windows filenames (test report / class file names).

### C6 — `USELESS_ELVIS` — 1 site

```
Elvis operator (?:) always returns the left operand of non-nullable type 'Double'.
Location: tools/parity/src/main/kotlin/io/github/glandais/parity/UnitDump.kt line 229
```

- Code: `out["dp.$id.$i.ele"] = p.elevation ?: 0.0`.

**Moot since `865dd0b` on `develop`**, which removed the whole TS parity verification harness — the
file this warning lived in no longer exists. Nothing was fixed here; the site went away.

## D. Kotlin Gradle Plugin misconfiguration

### D1 — "JS Environment Not Selected" for `wasmWasi`

```
> Configure project :elevation
w: ⚠️ JS Environment Not Selected
Please choose a WebAssembly WASI environment to build distributions and run tests.
Not choosing any of them will be an error in the future releases.
kotlin { wasmWasi { nodejs() } }
```

Problem id: `kotlin:kgp:misconfiguration:wasm-wasi-environment-not-chosen-explicitly`.

Observation: all four KMP modules (`:elevation:52`, `:gpx:36`, `:engine:57`, `:fit:49`) declare
`wasmWasi { wasmtime() }`, and the `wasmWasiWasmtimeTest` tasks do run and pass — yet KGP still
reports no environment chosen, and only once, during `:elevation` configuration. **Will become an
error in a future KGP release.**

## E. JS bundling (webpack)

### E1 — Bundle size limit exceeded — `:engine` and `:fit`

```
asset size limit: The following asset(s) exceed the recommended size limit (244 KiB).
entrypoint size limit: … combined asset size exceeds the recommended limit (244 KiB).
webpack performance recommendations: … lazy load some parts of your application.
```

| Bundle | Minified size | Verdict |
|---|---|---|
| `engine.js` | **996 KiB** | over limit (`[big]`) |
| `fit.js` | **668 KiB** | over limit (`[big]`) |
| `elevation.js` | 201 KiB | under limit, no warning |

Three warnings per over-limit bundle (asset limit, entrypoint limit, recommendation block).

Largest contributors to `engine.js`: `fit-kotlin-sdk.js` 1.13 MiB, `xmlutil-core.js` 402 KiB,
`kotlin-kotlin-stdlib.js` 392 KiB, `kotlinx-coroutines-core.js` 194 KiB, `vcyclist-gpx.js` 144 KiB,
`vcyclist-elevation.js` 106 KiB, `vcyclist-engine.js` 98.2 KiB, `vcyclist-fit.js` 35.5 KiB
(pre-bundling module sizes).

### E2 — Per-module `[N warnings]` counts inside the bundles

webpack annotates each Kotlin/JS module with a warning count that it does **not** print in detail
at the default log level:

| Module | Warnings (in `engine.js` build) |
|---|---|
| `fit-kotlin-sdk.js` | 182 |
| `xmlutil-core.js` | 117 |
| `kotlinx-coroutines-core.js` | 67 |
| `vcyclist-gpx.js` | 15 |
| `vcyclist-engine.js` | 13 |
| `vcyclist-elevation.js` | 10 |
| `vcyclist-fit.js` | 10 |
| `kotlin-kotlin-stdlib.js` | 10 |
| `kotlinx-atomicfu.js` | 5 |

Not yet inspected — the detail needs `--info` or a webpack `stats` bump. Three of these modules are
first-party (`vcyclist-gpx`, `vcyclist-engine`, `vcyclist-elevation`, `vcyclist-fit`).

## F. Test-infrastructure noise

### F1 — Mocha reporter option ignored — 4 occurrences

```
Reporter option 'alsoWithHtml' has no effect. Because custom reporterOptions.Base was provided.
```

Emitted once per module that runs `jsNodeTest` / `jsBrowserTest` (`:gpx`, `:elevation`, `:engine`,
`:fit`). `alsoWithHtml` appears nowhere in the project's `.kts` files — it comes from KGP's own
mocha wiring.

### F2 — `sun.misc.Unsafe` terminal deprecation — 14 occurrences

```
WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
WARNING: sun.misc.Unsafe::objectFieldOffset has been called by
         org.jetbrains.kotlin.com.intellij.util.containers.Unsafe
         (kotlin-compiler-embeddable-2.1.0.jar)
WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release
```

Third-party (bundled IntelliJ util inside the Kotlin compiler). Note the jar is
**kotlin-compiler-embeddable 2.1.0**, i.e. the ktlint plugin's pinned compiler, not the 2.4.20-RC
one used to compile the project.

## G. Coverage gaps observed while reading the output

### G1 — `:demo` is not covered by `./gradlew build`

`settings.gradle.kts` includes `:demo`, and `demo/build.gradle.kts` registers `assemble`, `check`
and `clean` — but **no `build` task**. In the full run the only demo task that executed was
`:demo:clean`. Consequence: `npmBuild`, `npmTypecheck` and `npmLint` never run as part of
`clean build`, so the Vue/Vite demo's typecheck and lint are outside the standard build gate.

### G2 — Documented toolchain versions drifted from actual

`CLAUDE.md` (§ Tools) states Gradle 9.5.0 and Kotlin 2.3.21; the build actually resolves Gradle
**9.7.0** and Kotlin **2.4.20-RC**. Relevant because C1 (`ExperimentalWasmInterop`) is a
consequence of the newer Kotlin, and because the project is currently building on a **release
candidate** compiler.

Those two were what the build log exposed directly; reading `libs.versions.toml` while fixing this
turned up **five** stale entries in total (see [Resolution](#resolution)), which is why the index
counts 5 and not 2.

## Ledger index

| ID | Severity | Count | Where | Owner | Status |
|---|---|---|---|---|---|
| B1 | Breaks on Gradle 10 | 1 | `cli/build.gradle.kts:35` | first-party | ✅ fixed |
| B2 | Info | 1 | root | first-party | ⬜ left as is |
| B3 | Info | 1 | root | Gradle | ⬜ left as is |
| B4 | Environmental | 1 | dev machine | — | — n/a |
| C1 | Warning | 36 | `EngineWasiApi.kt`, `HostTileSource.kt` | first-party | ✅ fixed |
| C2 | Warning (by design) | 32 | `EngineJsApi.kt`, `ElevationJsApi.kt` | first-party | 🟡 kept on purpose |
| C3 | Warning | 3 | `FluxTest.kt`, `LruCacheTest.kt` | first-party | ✅ fixed |
| C4 | **Dead assert** (confirmed) | 1 | `TileFetcherStubTest.kt:145` | first-party | ✅ fixed |
| C5 | Warning (Windows portability) | 1 | `EnhanceCommandTest.kt:268` | first-party | ✅ fixed |
| C6 | Warning | 1 | `UnitDump.kt:229` | first-party | ➖ moot (harness deleted upstream) |
| D1 | Becomes an error later | 1 | 4 × `build.gradle.kts` | KGP | 🟡 documented, deferred to w08 |
| E1 | Perf advisory | 6 | `engine.js`, `fit.js` | first-party + deps | ⬜ open |
| E2 | Unknown (uninspected) | ~429 | Kotlin/JS modules | mixed | ⬜ open |
| F1 | Noise | 4 | KGP mocha wiring | third-party | ⬜ not ours |
| F2 | Noise | 14 | ktlint's embedded compiler | third-party | ⬜ not ours |
| G1 | Coverage gap | 1 | `demo/build.gradle.kts` | first-party | ⬜ open, needs a decision |
| G2 | Doc drift | 5 | `CLAUDE.md` | first-party | ✅ fixed |

## Resolution

Applied on branch `fix/build-warnings`, verified by a second
`clean build --warning-mode all --no-build-cache` on 2026-08-17, then re-verified after rebasing onto
`develop` at `865dd0b` (*"remove TS parity verification harness"*), which landed in between.

| Measure | Baseline | After |
|---|---|---|
| `w:` warning lines | 75 | **32** |
| Gradle deprecations (own scripts) | 1 | **0** |
| Kotlin compiler problem entries | 5 | **0** |
| KGP misconfiguration | 1 | 1 (D1, deliberately) |
| Tests / failures / skipped | 3 681 / 0 / 0 | 3 637 / 0 / 0 († ) |
| ktlint | clean | clean |

(† ) The 44 fewer test executions are **entirely** `865dd0b`'s parity-fixture removal, not this
branch: the whole delta is `:engine` (-11 on each of its four targets), while `:elevation`, `:gpx`,
`:fit`, `:map` and `:cli` are unchanged to the test. Baseline totals above were measured on the
pre-`865dd0b` tree and are left as measured.

Every remaining `w:` line is C2. B1, C1, C3, C4, C5 and G2 were fixed here; C6 disappeared on its
own when `develop` dropped the parity harness.

### Fixed

- **B1** — `tasks.register("generateVersionProperties")` replaces the `by registering` delegate.
  ktlint then required the multiline expression on its own line, hence the re-indent.
- **C1** — `ExperimentalWasmInterop` joins the existing file-level `@OptIn` in `EngineWasiApi.kt` and
  `HostTileSource.kt`, rather than being repeated across 36 declarations. **The emitted `.wasm` is
  byte-identical** (502 184 bytes, sha256 `99411d1a…f23f6fc9`), which is the evidence the change is
  annotation-only and leaves w06's size guard and the w09 host harness alone.
- **C3** — file-level opt-in on both test files, each with a line on why `runCurrent()` is used.
- **C4** — the assert was `elevation != null` on a non-nullable `Double`: **structurally incapable of
  failing**, so it verified nothing. Replaced by the fixture's encoded value — (0, 0) is the centre of
  the zoom-0 tile, i.e. pixel (2, 2) of a 4 × 4 one, so 202 m. Confirmed green (10/10 under wasmtime),
  not assumed.
- **C5** — renamed without the quotes. Worth noting the suite would not have run on Windows at all.
- **G2** — the drift was **5 entries**, not the 2 first recorded: Gradle 9.5.0 → 9.7.0, Kotlin 2.3.21 →
  2.4.20-RC, xmlutil 0.91.3 → 1.0.2, imageio-webp 3.13.1 → 3.14.0, and picocli / fit-kotlin-sdk were
  absent. The list now points at `libs.versions.toml` as the source of truth.

### Deliberately not fixed

- **C2** (32 sites) — the opaque-handle façade is intentional. Suppressing the warnings would also
  hide a *genuinely* new non-exportable type appearing later, which is exactly the regression signal
  section C2 exists to preserve. The count is the canary; leave it visible.
- **D1** — not a project misconfiguration: KGP's check only recognises `nodejs()`. The only way to
  silence it is to declare `nodejs()` on modules that are verified under wasmtime, which would create
  pointless Node test tasks. Recorded in `docs/kotlin-wasm-wasi.md` §1 instead, where it had been
  filed as a "Beta2 roughness" — it persists on 2.4.20-RC, so it is KGP behaviour, and it closes the
  first of w08's three re-verification questions early. Re-check at 2.4.20 final.
- **E1 / E2** — `engine.js` at 996 KiB and the ~429 uninspected per-module webpack warnings need real
  investigation (a `stats` bump to even read them), not a quick fix.
- **F1 / F2** — third-party: KGP's own mocha wiring, and ktlint's embedded compiler 2.1.0.
- **G1** — wiring `:demo` into `build` would make every contributor's `./gradlew build` run npm
  install + vite. That is a build-time policy call, not a warning fix.
