# t07 — Enhancer integration and the option surfaces

## Goal

Wire the racing line into the pipeline and expose it — `EnhanceOptions`, CLI, JS façade and WASI —
so a caller can actually ask for it.

This is `docs/design/racing-line.md` §8, with the WASI surface the design omits.

## The exposure decision

The maintainer's call is to expose the stage fully, `FULL_ROAD` included. So the safety work is in
the **defaults and the labelling**, not in withholding the feature:

- `enabled = false`. The stage rewrites every coordinate of the output, which is a change no
  existing caller asked for. It has to be requested.
- `corridor = LANE` when it *is* requested. `LANE` keeps `n = 0` feasible, so a straight is never
  displaced and the line cannot cross into oncoming traffic.
- `FULL_ROAD` is reachable but labelled in the CLI help as closed-road/time-trial only. It is the
  mode that produces the attractive numbers and it is illegal on any open road; a user choosing it
  should know which of those they are getting.

## Depends on

[t05](t05-offset-qp.md) — `RacingLine.compute`.

## Steps

1. **`RacingLineOptions.enabled`**, default `false`.
2. **`EnhanceOptions.racingLine`**, appended last — `EngineModelJvm` passes options positionally,
   so inserting ahead of an existing field would silently re-map a Java call site.
3. **Pipeline slot.** After elevation smoothing and before `MaxSpeedComputer`, replacing the
   curvature stage when it runs: `RacingLine.compute` writes `trajectoryCurvature` itself, for the
   *trajectory* rather than the centreline, which is exactly what the speed limits should be
   computed from.
4. **Simplify tolerance cap.** When the stage ran, cap Douglas-Peucker at `simplifyToleranceCapM`
   (2 m). The whole racing line lives inside about 2.5 m of the centreline, so the default 10 m
   tolerance would discard it entirely — the deliverable would be computed, written, and then
   simplified away.
5. **Four surfaces.** CLI `--racing-line`, `--corridor`, `--road-width`; JS `racingLine*` keys;
   WASI `racingLine*` in `ENHANCE_KEYS` (unknown keys are a hard error there, so w04 parity breaks
   the moment another façade gains an option); defaults derived from `RacingLineOptions.DEFAULT`,
   never restated.
6. **Docs.** `Enhancer` KDoc, README diagram, CLAUDE.md pipeline order.

## Validation

- `./gradlew check ktlintCheck` green on all targets.
- **Disabled is byte-identical.** With `enabled = false` the pipeline output matches the previous
  behaviour exactly. This is the guard that makes the whole phase safe to have landed.
- Enabled moves coordinates, writes `lateralOffset` and the source coordinates, and keeps the
  output size equal to the input's.
- The simplifier does not erase the line: with the stage on, the output still carries a non-zero
  offset after simplification.
- CLI flags parse, including the negatable form, and defaults come from the engine.
- A CLI smoke on a real fixture produces no NaN coordinates.

## Done when

- [x] `RacingLineOptions.enabled`, `EnhanceOptions.racingLine`
- [x] Pipeline slot + simplify tolerance cap
- [x] CLI, JS, WASI surfaces with engine-derived defaults
- [x] Disabled-is-byte-identical test green
- [x] Docs updated
- [x] `./gradlew check ktlintCheck` green, working tree clean

## Notes

- The curvature stage (t03) and the racing line are **alternatives**, not a sequence. Both write
  `trajectoryCurvature`; the racing line's is the curvature of the line actually ridden, which is
  the one the speed limits want. Running both would mean computing the centreline's curvature and
  then overwriting it.
- `--road-width` sets `defaultRoadWidthM`, the width assumed where the file supplies none. It is
  worth exposing precisely because the corridor half-width is *linear* in it: a user who knows
  their road is 4 m wide rather than 6 m can halve an otherwise optimistic line.


## Outcome

Shipped, opt-in. Ledger entry **R24** carries the measurement.

The integration itself was routine; the discovery was not. The first end-to-end run made rides
16–27 % *slower*, because the analytic offset-curvature formula spikes at every corridor-bound kink
in the solved offset — a finite `n''` at 1–2 m spacing turns a 0.1 m wiggle into a 23 m bend. The
fix is to re-measure curvature on the materialised path with the same estimator the centreline
gets, which also makes the two comparable for the first time. See R24.

Measured after the fix: `strava` −0.54 %, `sample` −0.81 %, `stelvio` +0.07 %, in `FULL_ROAD`.
`stelvio` is a climb, where the line's 2 % extra distance costs more than its corner speed buys —
an honest result worth knowing before enabling this for a hill climb.
