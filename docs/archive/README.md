# Archive

**Nothing in this directory describes the code as it is today.** Each file was accurate on the day
it was written and has been frozen since. For current state, read [`../guides/`](../guides/) and
[`../ledgers/`](../ledgers/); this is where you come for the *why* behind a decision.

Two consequences, both deliberate:

- **The Kotlin/Wasm (`wasmJs`) target these documents assume no longer exists.** It was removed from
  the project: Kotlin/Wasm is not WASI, and it needs a JS runtime in the browser anyway — which the
  Kotlin/JS target already covers. The real targets are **JVM, JS (Node), JS (browser), and WASI**
  (`wasmWasi`, a separate target added later — see [`../guides/kotlin-wasm-wasi.md`](../guides/kotlin-wasm-wasi.md)).
  The plans below are left as written rather than retro-patched.
- **Most of it is in French**, as was the working language of the plans. Current documentation is
  English.

## Plans

| Plan | Scope | State |
|---|---|---|
| [`plans/PLAN.md`](plans/PLAN.md) | Tasks `00`–`45` — the port of the TypeScript `virtual-cyclist` and `elevation` references, then the demo and the research catch-up phases | Complete |
| [`plans/PLAN-GPX2WEB.md`](plans/PLAN-GPX2WEB.md) | Tasks `g01`–`g34` — porting what only existed in the Java gpx2web reference (FIT, climbs, maps, exports, CLI) | Complete |
| [`plans/PLAN-WASM-WASI.md`](plans/PLAN-WASM-WASI.md) | Tasks `w01`–`w13` — the standalone WASI module | Complete except `w07` (publication) and `w08` (Kotlin 2.4.20 final), both waiting on upstream |
| [`plans/racing-line-design.md`](plans/racing-line-design.md) | Tasks `t01`–`t14` — the full racing-line design, carrying the corrections measurement forced on it | Partially implemented; §11's t08–t10, t12–t13 were never built |
| [`plans/wasm-wasi-component-model.md`](plans/wasm-wasi-component-model.md) | Task `w13`'s measured verdict on the Component Model / WIT / WASI Preview 2 | Decision: not now, phase F scoped and waiting |

There is no plan file for the `t` series: its status lives in the header of
[`plans/racing-line-design.md`](plans/racing-line-design.md), and the user-facing result is
[`../guides/racing-line.md`](../guides/racing-line.md).

## Tasks

[`tasks/`](tasks/README.md) — one spec per task, `Goal / Depends on / Inputs / Steps / Outputs /
Validation / Done when / Notes`. The index lists every ID with its subject and state.
