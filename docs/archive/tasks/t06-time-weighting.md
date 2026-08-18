# t06 — Time weighting: IRLS toward `∫√κ ds`, and the saturation mask

> **Outcome: implemented, measured, and reverted.** Every variant made rides *slower* than the
> simpler formulation t05 already ships. Ledger entry **R25** ❌ carries the numbers. This file is
> kept as the record of a negative result, not as work still to do — see **Outcome** at the end
> before reimplementing any of it.

## Goal

Make the objective optimise for **time** rather than for curvature, by reweighting it toward the
quantity that actually sets cornering time — and stop it optimising where cornering does not set
the time at all.

This is `docs/design/racing-line.md` §3.7.

## Why `∫κ² ds` is the wrong objective

Cornering time over a bend ridden at the grip limit is

```
T = ∫ ds/v = ∫ ds/√(µ g R) = √(1/(µg)) · ∫ √κ ds
```

so the geometric quantity to minimise is `∫√κ ds`, not `∫κ² ds`. They are not the same ordering:
`κ²` over-weights the tightest point relative to its true cost in seconds, and under-weights the
long moderate bends where a rider actually spends the time.

`∫κ² ds` is nonetheless what makes the problem a *quadratic* program with a unique minimiser, which
is worth keeping. The standard reconciliation is iteratively reweighted least squares: solve the
quadratic problem with weights `ρ` chosen so that at the current iterate `ρκ² = √κ` exactly, i.e.
`ρ = κ^{-3/2}`, and repeat. Each round is still a QP; the sequence targets the objective we want.

## Why the mask matters more than the reweighting

`R_sat = v_max²/(µ g)` is the radius above which a rider is speed-limited rather than grip-limited
— about 112 m at the shipped defaults. Beyond it, opening a corner buys **nothing**, because the
rider was never going to corner faster than `maxSpeedMS` anyway.

Optimising there is not merely wasted: it is where the objective does damage. Curvature at that
scale is largely measurement noise, and the objective's response to curvature is `n'' ≈ −κ`, which
integrated twice is a random walk. t05 shipped a hard on/off form of this (`objectiveRadiusM`,
200 m) precisely because without it the solver moved the line *further* from the true road than the
input was. This task replaces that constant with the rider-derived `R_sat`.

## Depends on

[t05](t05-offset-qp.md) — the QP; [t07](t07-enhancer-integration.md) — the pipeline slot that will
pass the rider through.

## Steps

1. **Plumb the rider.** `RacingLine.compute`/`analyze` gain an optional `Cyclist`. Optional
   because the corridor and the geometry do not need one, and a caller analysing a route's shape
   should not have to invent a rider; when absent, fall back to the t05 constant.
2. **Saturation mask.** `R_sat = maxSpeedMS² / (G · tanMaxLeanAngle)`, masking `ρ` to zero beyond it.
3. **IRLS.** `irlsRounds` extra solves after round 0, with
   `ρ_i = clamp((|κ_traj,i| + 1/200)^{-3/2} · Z, 0.2, 5.0)` normalised so the masked mean is 1, and
   the metric reweight `w_i ← Δs_i·max(0.2, 1 − κ_i·n_i^{prev})` — the arclength the *offset* line
   actually covers, which is what the integral is over.
4. **Grade coupling**, `gradeApexCoupling`: scale `ρ` across a corner by
   `1 + coupling·φ·tanh(grade_exit/6)`, `φ ∈ [−1, +1]` linear from entry to exit, biasing the apex
   late when the exit climbs. Measure before choosing a default — see Notes.
5. **Measure**, and only then decide the defaults.

## Validation

- `./gradlew check ktlintCheck` green on all targets.
- A gentle sweeper beyond `R_sat` is left alone: `max |n|` stays near zero, because opening it buys
  no time.
- A tight corner still gets its line: t05's T1/T2 assertions continue to hold.
- The noisy straight stays denoised.
- Determinism: two runs bit-identical.
- Measured per-fixture deltas against t05's numbers, recorded in the ledger.

## Done when

- [x] Rider plumbed through, with the no-rider fallback
- [x] `R_sat` mask replacing the fixed radius when a rider is present
- [x] IRLS rounds with the metric reweight
- [x] Grade coupling implemented, default chosen by measurement — **the measurement said off**
- [x] Ledger updated with measured deltas (**R25**, rejected)
- [x] Code reverted; `./gradlew check ktlintCheck` green, working tree clean

## Notes

- **`gradeApexCoupling` is a hand-tuned scalar with no source.** The design says so itself, and
  §12 question 4 records that a velocity-blind geometric objective cannot produce genuine
  slow-in/fast-out. It is the one part of this task that is a guess rather than a derivation, so
  its default is to be set by measurement, and it is to be labelled a heuristic wherever it
  appears. If it does not measurably help, it ships off.
- The IRLS weight floor and ceiling (`0.2`, `5.0`) are not decoration: `κ^{-3/2}` diverges as
  `κ → 0`, so without a ceiling a nearly-straight station would dominate the entire objective.
- Each IRLS round is a full QP solve, so the stage's cost multiplies by `1 + irlsRounds`. Worth
  checking that against the measured benefit rather than assuming two rounds are free.


## Outcome — rejected on measurement

All three parts were implemented and measured against the fixtures. Duration relative to the
plain centreline, `FULL_ROAD`, lower is better:

| variant | `stelvio` | `strava` | `sample` |
|---|---|---|---|
| **t05 as shipped** (fixed 200 m mask, no reweighting) | **+0.07 %** | **−0.54 %** | **−0.81 %** |
| `R_sat` mask (112 m), no reweighting | +0.27 % | +0.08 % | −0.51 % |
| `R_sat` mask + 2 IRLS rounds | +0.36 % | +0.29 % | −0.67 % |
| … + grade coupling 0.15 | +0.36 % | +0.29 % | −0.67 % |

Every row is worse than the first. On `strava` the full treatment turns a 0.54 % gain into a
0.29 % *loss*.

**Why the reweighting hurts.** The theory is not wrong — cornering time really is
`√(1/µg)·∫√κ ds`, so `∫κ² ds` really is the wrong objective. What fails is the reweighting as a
mechanism. Normalising `ρ` to a masked mean of 1 puts `ρ < 1` at exactly the tight apexes that
matter and `ρ` up to the ceiling on the gentle stretches around them, which shifts effort away from
the corners worth optimising. The line then weaves more — `strava`'s distance grows 21 118 →
21 408 m against 21 148 m without — and on these routes the extra distance costs more than the
corner speed buys.

An earlier attempt was worse still, and worth recording because it looked right: weighting on the
solved line's own curvature, as the design specifies, drove a 15 m hairpin to a 3.3 m line. The
analytic offset curvature spikes at every corridor-bound kink (the artefact t07 diagnosed), so
weights taken from it collapse at the spikes and saturate beside them, and feeding that back into
the objective amplifies it. Weighting on the reference curvature fixed that failure but not the
underlying one.

**Why the rider-derived mask hurts.** `R_sat = v_max²/(µg)` is 112 m at the defaults, and above it
the cornering limit genuinely cannot bind, so masking there should be free. Measured, it is not:
tightening the mask from 200 m to 112 m costs 0.3–0.6 pp on every fixture. The mechanism is
indirect — a different masked set gives a different solved offset, hence different geometry, hence
different measured curvature *elsewhere* — which is precisely why it needed measuring rather than
reasoning about.

**Grade coupling changed nothing at all**, to three significant figures, on every fixture. It was a
hand-tuned scalar standing in for a variational result, and the honest reading is that it does not
express anything the geometry responds to.

### If this is revisited

Not by tuning these knobs. Design §12 question 4 names the real gap: a velocity-blind geometric
objective cannot produce slow-in/fast-out, and the construct–simulate–reconstruct loop that could
needs two `VirtualizeService` runs per iteration. That is a different design, not a coefficient.
