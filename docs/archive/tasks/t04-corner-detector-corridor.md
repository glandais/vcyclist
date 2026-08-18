# t04 — Corner detector + corridor

## Goal

Turn the curvature field into two things the trajectory solver will need: a list of **corners**,
and a per-point **feasible lateral interval** `[lo_i, hi_i]` the racing line may occupy.

This is `docs/design/racing-line.md` §3.4 and the detection half of §3.9, plus the
`RacingLineReport` skeleton. It produces no trajectory — nothing computes `n` until t05 — so it
ships as pure analysis: an API you can call and assert on, and which nothing in the pipeline
invokes yet.

## Why detection is demoted to analysis

Worth restating because the design is emphatic about it: corner detection is **not** the producer.
Earlier drafts made `R̄_c = L_c/δ_c` load-bearing, and it is biased by clothoid entry and exit —
by about +42 %. Here the detector only seeds, masks and reports; the QP is what produces the line,
and it enforces the corridor jointly over all corners. So a mis-detected corner costs accuracy in a
report, not correctness in a trajectory.

The same reasoning sets the radius statistic: `R_q20`, the 20th percentile of `1/|κ|` over the
span, rather than a mean. Robust to the clothoid tails that bias the mean, and it does not need the
span's ends to be correctly located.

## Depends on

- [t03](t03-curvature-estimator.md) — `PlanarFrame`, `CurvatureEstimator`
- [t02](t02-road-width.md) — `path.roadWidth(i)`

## Steps

1. **Options and enums.** `RacingLineOptions` with the subset this task needs;
   `CorridorMode { LANE, LANE_LEFT, FULL_ROAD }`, `CornerKind { GENTLE, CORNER, HAIRPIN }`.
   `ROUNDABOUT` and `CHICANE` are deliberately absent until t08 detects them — an enum constant
   nothing can ever produce is a lie in the API.
2. **`CornerDetector`.** State machine over the smoothed `κ` with hysteresis: enter at
   `|κ| > 1/cornerEnterRadiusM`, stay while `|κ| > 1/cornerExitRadiusM` **and** the sign holds,
   close on a sign flip. Merge same-sign neighbours closer than `max(15 m, 3w)`. Reject spans
   shorter than `minCornerLengthM` or turning less than `minCornerTurnDeg`. Per corner report
   `fromIndex`/`untilIndex`, `apexIndex`, turn, direction, `R_q20`, `R_min`, and a `kind`.
3. **`Corridor`.** Half-width `h_i = clamp(width_i/2 − edgeMarginM, 0, 6)` from `roadWidth` with
   `defaultRoadWidthM` substituted where it is NaN, **smoothed over 20 m of arclength** — the
   smoothing t02 deferred to here, because it needs arclength and the path has since been
   resampled. Then the box per mode, and three clamps in order:
   1. **Offset-curve regularity** — the offset map folds where `1 − κn = 0`, so
      `|n| ≤ regularityFactor/|κ|`.
   2. **Self-proximity** — a 12 m grid hash; for each `i`, the nearest point more than
      `selfProximityGapM` away *along the path*; then `h_i ← min(h_i, max(0, d_i/2 − 0.5))`.
   3. Pins: `lo = hi = 0` at both end pairs, and at the midpoint of every straight run.
4. **`RacingLineReport` + `RacingLine.analyze`.** Read-only entry point returning corners, the
   corridor and the curvature arrays.
5. **Tests.** Corner detection on synthetic geometry (single bend, chicane, hairpin, noise), and
   both clamps at the geometry that provokes them.

## Outputs

- `RacingLineOptions`, `CorridorMode`, `CornerKind`, `CornerSpan`, `RacingLineReport`
- `internal object CornerDetector`, `internal object Corridor`
- `object RacingLine { fun analyze(path, options): RacingLineReport }`

## Validation

- `./gradlew check ktlintCheck` green on all targets.
- A straight-bend-straight fixture yields exactly one corner, with the right sign, a turn within
  0.02 rad of truth, and `R_q20` within 10 % of the true radius.
- A chicane yields exactly two corners of opposite sign, not one merged span.
- 1.5 m of white jitter on a straight yields **zero** corners — a noise excursion must sustain
  over `minCornerLengthM` to open one.
- `LANE` keeps `0` inside `[lo, hi]` at every point, so a straight is never displaced. This is the
  property that lets the mode be the default.
- `FULL_ROAD` on `w = 2·edgeMargin` collapses the corridor to a point.
- The regularity clamp binds on a 3 m-radius kink: `|n| ≤ 0.85/|κ| + 1e-9` everywhere.
- A hairpin stack with 8 m between legs caps `|n|` at 3.5 m, and legs 4 m apart collapse it.
- `lo_i ≤ hi_i` at **every** point, whatever the clamps did — the QP has no answer for an empty
  interval.

## Done when

- [x] `CornerDetector` with hysteresis, merging, rejection, `R_q20`, apex, kind
- [x] `Corridor` with the three modes, width smoothing, and all three clamps
- [x] `RacingLine.analyze` + `RacingLineReport`
- [x] Detection tests (bend, chicane, hairpin, noise) and clamp tests green
- [x] `lo ≤ hi` invariant asserted across every fixture
- [x] `./gradlew check ktlintCheck` green, working tree clean

## Notes

- **`driveOnRight` is dropped** from the design's option list as redundant: `CorridorMode.LANE`
  already means "the right-hand lane" and `LANE_LEFT` the other. Two switches for one decision
  invite the state where they disagree.
- **`LANE` is the default and `n = 0` must stay feasible** in it — the box is `[−h, 0]`, not
  centred on the lane. That is what makes the mode safe to default: on a straight the solver has no
  reason to move, so the rider's own line is preserved, and the racing line never crosses into
  oncoming traffic. `FULL_ROAD` is the mode that produces the attractive numbers and is illegal on
  any open road; it stays opt-in and labelled.
- Pins are **two adjacent nodes** at each end, not one. The QP's Hessian is pentadiagonal
  (bandwidth 2) because the curvature proxy couples `i ± 2`, and a bandwidth-2 system needs two
  consecutive pinned nodes to decouple. Pinning one leaves the solve coupled across the pin. This
  matters in t05, but the corridor is where the pins are set, so it is decided here.
- The self-proximity clamp is not in any single source design — it is the switchback-stack hazard
  all three candidate designs shared. Two hairpin legs 4 m apart have overlapping corridors, and
  without the clamp the solver would happily push the line from one leg into the other.


## Outcome

Shipped as analysis only — `RacingLine.analyze(path, options)` returns a `RacingLineReport` and
nothing in the pipeline calls it yet.

The self-proximity clamp needed two corrections before it did its job, and the second is the
interesting one:

1. **An along-path test alone exempts the geometry the clamp exists for.** "Different piece of
   road" was first written as "more than `selfProximityGapM` away along the path" — straight from
   the design. On a switchback the opposite leg is only tens of metres away *along the path*, so
   that test skips it, and skips it precisely beside the hairpin where the two legs are closest.
   Stations there kept the full road width.
2. **A detour ratio is too blunt to fix it.** Comparing straight-line distance against along-path
   distance reads 0.59 across a hairpin entry and 0.72 along a legitimate 5 m bend — not enough
   separation to threshold. **Heading** is the sharp discriminator, because doubling back *is* a
   heading reversal: it reads π against nearly zero. Where the heading test over-triggers, on a
   genuinely tight single bend, the offset it allows already exceeds what the regularity clamp
   permits, so nothing changes.

`RacingLineReport.maxCorridorWidthM` reports the full interval rather than a half-width: `LANE` is
one-sided, so a half-width would report it as the same size as a `FULL_ROAD` corridor twice as
large — which is how the first version of the mode-comparison test managed to pass and fail at the
same time.
