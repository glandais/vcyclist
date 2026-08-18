# Documentation index

Five kinds of document live here, and the distinction is the point:

| Directory | What it is | Do I trust it? |
|---|---|---|
| [`guides/`](#guides) | How to use and extend the project | **Yes** — describes the code as it is |
| [`ledgers/`](#ledgers) | Living inventories, updated as work lands | **Yes** — each row carries its own verdict |
| [`tasks/`](#open-tasks) | Specs for work not yet delivered | Yes as intent, no as description |
| [`research/`](#research) | The solo-rider simulation research report | Yes, per its own evidence grades |
| [`archive/`](#archive) | Finished plans and the task specs that built the project | **No** — frozen at their date, read for the *why* |

Anything under `guides/` and `ledgers/` describes the present, whatever language it is in —
most are English, a few (`kotlin-js-jvm-webp.md`, `kotlin-wasm-wasi.md`,
`ledgers/surface-coverage.md`) are French. `archive/` is frozen as written, French included,
and is never updated to match the code.

## Guides

| Document | Answers |
|---|---|
| [`guides/using-from-java.md`](guides/using-from-java.md) | How Java calls a Kotlin-first, `suspend`-heavy API: the `…Jvm` facades, `…Blocking` / `…Async` |
| [`guides/using-from-javascript.md`](guides/using-from-javascript.md) | The whole JS / TypeScript façade — parsing, `enhance` vs `enhanceWithCourse`, exports, climbs, DEM |
| [`guides/racing-line.md`](guides/racing-line.md) | What the optimal-line stage does, what it costs, why it is off by default |
| [`guides/publishing.md`](guides/publishing.md) | Release flow — semantic-release, Maven Central, npm |
| [`guides/kotlin-js-jvm-webp.md`](guides/kotlin-js-jvm-webp.md) | Kotlin/JS ↔ JS interop patterns behind the `@JsExport` façades, and WebP decoding per target |
| [`guides/kotlin-wasm-wasi.md`](guides/kotlin-wasm-wasi.md) | How the `wasmWasi` target is built and what it can reach |
| [`guides/wasm-wasi-abi.md`](guides/wasm-wasi-abi.md) | The WASI module's imports, exports and error codes — what a host must implement |

Module-level guides live next to their module: [`cli/README.md`](../cli/README.md),
[`elevation/README.md`](../elevation/README.md), [`map/README.md`](../map/README.md),
[`demo/README.md`](../demo/README.md), [`tools/wasi/README.md`](../tools/wasi/README.md).

## Ledgers

| Document | Tracks |
|---|---|
| [`ledgers/improvements-ledger.md`](ledgers/improvements-ledger.md) | One row per research-derived improvement (R1…), scored against the code: applied / recommended / deferred / rejected, with the measurement behind each verdict |
| [`ledgers/surface-coverage.md`](ledgers/surface-coverage.md) | Which of the six surfaces — core, CLI, JS, WASI, JVM/Java, demo — exposes which capability, and where each one is short. Its per-option table is **generated** from `OptionCatalog` (`./gradlew :codegen:generateSurfaceLedger`); the capability table above it is written by hand, because "reachable by a human in the UI" does not derive |
| [`ledgers/build-warnings-ledger.md`](ledgers/build-warnings-ledger.md) | Everything `./gradlew clean build` emits that is not a task line, and what was done about it |

## Open tasks

| Document | Goal |
|---|---|
| [`tasks/surface-alignment.md`](tasks/surface-alignment.md) | Make façade alignment build-enforced instead of review-enforced, and close the 23 confirmed gaps between the six doors. **S0–S9 and S11 delivered; S10 (code generation) deliberately not started — the reasoning is in the step.** |

Completed specs move to `archive/tasks/`.

## Research

[`research/README.md`](research/README.md) indexes the report: physical, physiological, mental and
behavioural modelling, cornering and descending, and what it all means for vcyclist. Its tracking
surface is `ledgers/improvements-ledger.md`.

## Archive

[`archive/README.md`](archive/README.md) — three completed plans, the racing-line design and its
feasibility review, the frozen port-coverage ledger, and 100+ task specs across four ID namespaces
(`NN`, `gNN`, `wNN`, `tNN`). Indexed in [`archive/tasks/README.md`](archive/tasks/README.md).
