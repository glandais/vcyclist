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

**Investigated 2026-08-17 — the warning points at an artefact nobody ships; the real cost is
elsewhere.** See [E1 investigation](#e1-investigation).

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

**Inspected 2026-08-17 — verdict: benign, no action.** See
[E2 investigation](#e2-investigation) below for how it was measured and why nothing was changed.

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
| E1 | Perf advisory (mis-targeted) | 6 | `engine.js`, `fit.js` | first-party + deps | 🟡 measured, deferred to w08 |
| E2 | Benign (investigated) | 429 | Kotlin/JS modules | third-party | ✅ closed, no action |
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
- **E1** — measured, not yet acted on: the warning is about an unshipped bundle, while the real cost is
  1.2 MB of dead FIT encoder in the deployed demo. See [E1 investigation](#e1-investigation) for the
  options, one of which is an API-shape decision.
- **E2** — investigated and closed as benign; see [E2 investigation](#e2-investigation).
- **F1 / F2** — third-party: KGP's own mocha wiring, and ktlint's embedded compiler 2.1.0.
- **G1** — wiring `:demo` into `build` would make every contributor's `./gradlew build` run npm
  install + vite. That is a build-time policy call, not a warning fix.

## E2 investigation

*2026-08-17. Question: what are the ~429 warnings webpack counts per module but never prints?*

### How they were read

`stats.warnings = true` does **not** surface them, and neither does `--info`: they are not compilation
warnings. They are `ModuleWarning`s attached to individual modules, reachable only through the API. A
throwaway webpack plugin walking `compilation.modules` and calling `module.getWarnings()` produced all
429, which were then classified. The plugin was temporary and is **not** committed — nothing in the
build was left behind by this investigation.

### What they are

All 429 are a **single** message shape, from `source-map-loader`:

```
Failed to parse source map from '<path>/Foo.kt' file: Error: ENOENT: no such file or directory
```

365 distinct missing `.kt` files, by root:

| Root | Files | Whose |
|---|---|---|
| `/runner/work/...` | 160 | `fit-kotlin-sdk`, path baked in by its GitHub Actions build |
| `/Users/runner/...` | 101 | `xmlutil`, same but a macOS runner |
| `/opt/buildAgent/...` | 53 | JetBrains TeamCity — stdlib, coroutines, atomicfu |
| local `build/js/packages/...` | 51 | Kotlin stdlib / JS-runtime sources KGP does not materialise |

### Why no action

- **Not our sources.** The counts are attributed to the bundle module, which made `vcyclist-*` look
  implicated — but every missing file is a Kotlin stdlib or third-party source. Our own code is
  referenced by relative path (`../../../../../engine/src/commonMain/…`), resolves, and debugs
  normally. 26 of the 39 sources in `vcyclist-engine.js.map` are ours and all resolve.
- **They are never printed.** `grep -c "Failed to parse source map"` over two full `clean build` logs
  returns **0**. They surface only as the `[N warnings]` counts above, and they do **not** feed the
  `compiled with 3 warnings` total — so they were never hiding E1's size warnings, which was the
  original worry.
- **`ignoreWarnings` does not touch them.** A narrow `config.ignoreWarnings = [/Failed to parse source
  map/]` was tried on all three modules and changed the output *not at all* (byte-identical webpack
  blocks apart from timings). It was reverted rather than committed as a no-op that looks like a fix.
- Actually removing the counts would mean excluding stdlib and library JS from `source-map-loader`, i.e.
  fighting KGP's own webpack wiring and risking the source mapping that does work. Not worth it for
  something that never reaches the console.

### One real curiosity, deliberately left alone

`vcyclist-elevation.js.map` lists `TileFetcher.js.kt` **twice**: once correctly as
`../../../../../elevation/src/jsMain/…/TileFetcher.js.kt`, and once as a bare `TileFetcher.js.kt` that
resolves nowhere — the only one of the 429 that names a file of ours. The working entry is present, so
mapping into that file is unaffected; the duplicate is a compiler artefact around the `.js.kt`
per-target naming. Recorded rather than chased.

## E1 investigation

*2026-08-17. Question: is the 996 KiB `engine.js` a real cost, and to whom?*

### Nobody downloads the bundle the warning is about

`engine.js` and `fit.js` come from `binaries.executable()`, whose browser webpack output exists to run
the Karma tests. What actually ships is a different artefact:

| Channel | Artefact | Built by |
|---|---|---|
| npm `@glandais/vcyclist-engine` | **library** distribution | `jsBrowserProductionLibraryDistribution` |
| Maven Central | JVM / klib | — |
| GitHub Release | CLI jar, `.wasm` | `:cli:executableJar`, `:engine:wasmModule` |

`demo/package.json`'s `prebuild` also asks for the *library* distribution, not the executable. So the
webpack size warning is fired at a file that reaches no consumer — which is why it should not be the
thing that gets optimised.

### Where the cost actually lands

The deployed demo is what real visitors download. Measured on a fresh `:demo:assemble`:

| Chunk | Raw | Gzipped |
|---|---|---|
| `engine-*.js` | 1 011 KiB | **254 KiB** |
| `nuxtui-*.js` | 555 KiB | 163 KiB |
| `chartjs-*.js` | 166 KiB | 57 KiB |
| `leaflet-*.js` | 145 KiB | 42 KiB |

Attributing the engine chunk by `sourcesContent` in its sourcemap (2 543 KiB of unminified sources):

| Source | Share |
|---|---|
| `fit-kotlin-sdk.js` | **45.6 %** |
| `xmlutil-core.js` | 15.8 % |
| `kotlin-kotlin-stdlib.js` | 15.4 % |
| `kotlinx-coroutines-core.js` | 7.6 % |
| `vcyclist-gpx.js` | 5.7 % |
| `vcyclist-elevation.js` | 4.2 % |
| `vcyclist-engine.js` | 3.9 % |
| `vcyclist-fit.js` | 1.4 % |
| `kotlinx-atomicfu.js` | 0.4 % |

**47 % of the chunk is FIT encoding, and the demo never uses it.** `demo/src` contains no FIT, CSV or
JSON export — `grep` for `pathToFit`, `toFit`, `download`, `createObjectURL` finds only
`writeGpx` in `engine-shim.ts`. `xmlutil` by contrast is genuinely needed: it parses the GPX.

### Why it cannot simply be tree-shaken

- `engine-shim.ts` does `import * as ns from '@glandais/vcyclist-engine'`, which forfeits tree-shaking
  before the bundler even starts.
- More fundamentally, Kotlin/JS emits **per-module** granularity in UMD/CommonJS, so `pathToFit` pulls
  `vcyclist-fit.js` → `fit-kotlin-sdk.js` in wholesale regardless of what the consumer imports.
- Splitting `pathToFit` into its own npm package is blocked by an existing architectural constraint:
  `Path` handles cannot cross a bundle boundary, which is precisely why the FIT façade lives in
  `:engine` (see `docs/publishing.md`, *`:fit` on npm*).

Tried and rejected: `kotlin.js.ir.output.granularity=per-file`, the setting that would let a bundler
drop `pathToFit`. It requires `useEsModules()` (the compiler says so outright); with ESM enabled on all
four JS modules it **compiles clean, but the property has no observable effect** on Kotlin 2.4.20-RC —
output stays at 13 per-module files, and the demo chunk is byte-identical. Reverted; ESM alone would
also flip the published package format, which is a breaking change for CommonJS consumers.

### Open options (none taken — this needs a decision)

1. **Reclassify the warning.** It fires on a non-shipped bundle; the honest fix is to say so rather than
   optimise for it. Cheap, but mutes a signal if `engine.js` ever becomes a deliverable.
2. **Cut FIT out of the demo's chunk.** Worth ~120 KiB gzipped for every visitor. Needs an engine-side
   split, which the `Path`-handle constraint blocks — so it means a deliberate API change (e.g. a
   handle-free FIT entry point), not a build tweak.
3. **Re-test per-file granularity** on Kotlin 2.4.20 final (task w08), where the property may work
   again; combine with named imports in `engine-shim.ts`.

**Decision (2026-08-17): option 3.** E1 is parked until the compiler is stable — the clean fix is the
granularity setting, and re-testing it costs nothing once w08 happens, whereas an API change made now
would be spending a `Path`-handle redesign to work around a compiler bug that may not survive the
release. Carried as step 6 and a validation box of
[`docs/tasks/w08-kotlin-2420-final.md`](tasks/w08-kotlin-2420-final.md), with the numbers to beat, so
it cannot quietly evaporate. The demo keeps shipping ~120 KiB gzipped of unused FIT until then.
