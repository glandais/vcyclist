# t11 — Make the racing line inspectable

## Goal

Expose the racing-line analysis so it can be *looked at* — from JavaScript, and from the CLI —
rather than only inferred from a changed GPX.

Design §11 calls this "docs + demo". The `Enhancer` KDoc, README diagram and CLAUDE.md landed with
t07; what is missing is any way to see what the stage decided.

## Why this and not t08–t10

Because measurement says the remaining optimisation work has nothing to optimise. On a real
128 km router export:

| measure | share of ride time |
|---|---|
| at the 5 m radius clamp | **0.034 %** |
| radius < 20 m | 3.55 % |
| **speed envelope binding** | **1.23 %** |

Design §5.1 justifies the roundabout detector (t08) by claiming recorded rings produce "`R = 5 m`
spikes and 20 km/h caps". The feasibility study could not reproduce that on the shipped fixtures,
and it does not happen on a real router export either — the clamp accounts for 0.034 % of the ride.
There is no pathology there to fix, and **1.23 % is the entire addressable budget** for any
envelope refinement, which t08, t09 and t10 would all be competing for. Ledger R11, R24, R25 and
R26 have each measured what that budget yields in practice: fractions of a percent.

Inspection is the one remaining item whose value is not measured in seconds. The stage rewrites
every coordinate of a rider's file; being able to see the corridor it assumed and the offset it
chose is what makes that trustworthy rather than merely opt-in.

## Depends on

[t07](t07-enhancer-integration.md).

## Steps

1. **`RacingLineReport` on the JS façade.** `analyzeRacingLine(path, options)` returning a plain JS
   object: corridor bounds, offset, both curvatures, the corner list, and the solver's own
   diagnostics. Built with the `js("({})")` + `unsafeCast` pattern the façade already uses.
2. **A corner summary the CLI can print**, so the analysis is reachable without a browser:
   `--racing-line-report` writes a per-corner table.
3. **`docs/racing-line.md`** — the user-facing document: what the stage does, what it costs, what
   it assumes, and the measured numbers. Distinct from `docs/design/racing-line.md`, which is the
   original design and now carries a long list of corrections.

## Validation

- `./gradlew check ktlintCheck` green on all targets.
- The JS report round-trips: array lengths equal the path size, corner indices are inside it.
- The CLI report prints on a real fixture and agrees with the JS one.
- Disabled remains byte-identical.

## Done when

- [x] `analyzeRacingLine` exported and tested from JS
- [x] CLI corner report
- [x] `docs/racing-line.md` written
- [x] `./gradlew check ktlintCheck` green, working tree clean

## Notes

- The Vue demo itself is **not** in scope. Drawing corridor and line on the map is a front-end task
  of its own; what this one delivers is the data it would need, which is the part that has to live
  in Kotlin.
- The report is analysis-only and takes a `Path`, so calling it never moves a coordinate. That
  matters for a diagnostic: you can ask what the stage *would* do without applying it.


## Outcome

Shipped. `analyzeRacingLine` on the JS façade, `--racing-line-report` on the CLI, and
[`docs/racing-line.md`](../racing-line.md) as the user-facing document.

The CLI report immediately earned itself on a real file. Stelvio's hairpins open exactly as the
geometry predicts — `R_line ≈ R_road + h` — 7.8 → 10.3 m, 5.0 → 7.5 m, 4.0 → 6.2 m, with the offset
saturating at the ±2.50 m corridor edge. It also showed two corners the stage makes *worse*
(a 190° hairpin at 1064 m, 20.0 → 17.1 m, and a bend at 2032 m, 36.8 → 35.8 m), which nothing else
in the project would have surfaced: the aggregate duration is neutral-to-positive and hides them.

Writing the fixture-based JS test turned up that `GpxFixtures.SAMPLE_GPX` has seven points, one
below `LocalFrame.MIN_POINTS`, so the analysis declines on it. That is the documented contract
rather than a failure, and it is now pinned as its own assertion.
