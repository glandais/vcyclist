# t05 — Offset QP: banded solver, energy, projected Newton

## Goal

Produce the racing line. Solve for the lateral offset `n(s)` that minimises a convex quadratic
energy subject to the corridor box, and materialise the result as a new `Path`.

This is `docs/design/racing-line.md` §3.5, §3.6, §3.8 and §3.11. It is the first task in this phase
that **moves coordinates**, so it also adds the fields that make that reversible.

## Why a box-constrained QP and not something simpler

The design considered three formulations and this is the only one with a **unique minimiser**.
That is not an aesthetic preference: a lattice DP has argmin ties, and a two-pass geometric
heuristic has no fixed point, so both give answers that depend on tie-breaking or on window
boundaries — and therefore change under a resample, or across targets, for no physical reason.

The second reason is the solver. Replacing iterative relaxation with a **direct banded
factorisation** removes every convergence question at once: the free-set solve is exact in `O(n)`,
so `converged` means something, there is no relaxation parameter to tune, and a residual test is a
residual test rather than a hope.

Projected Newton on a strictly convex box QP identifies the active set in finitely many steps and
then terminates at the exact minimiser — typically 3–6 iterations here.

## Depends on

- [t04](t04-corner-detector-corridor.md) — corridor `[lo, hi]`, corners, `PlanarFrame`
- [t01](t01-nan-default-curvature-field.md) — the `nanDefault` mechanism

## Steps

1. **Fields.** Append `LATERAL_OFFSET`, `SOURCE_LATITUDE`, `SOURCE_LONGITUDE`, all `nanDefault`,
   40 → 43. The source coordinates are the maintainer's answer to design §12 question 3: this
   stage replaces the rider's recorded position with *smoothed reference + offset*, and that must
   stay reversible for map-matching and segment detection.
2. **`BandedLdl`.** Symmetric `LDLᵀ` for a bandwidth-2 (pentadiagonal) system, factor and solve
   in place, `O(n)`. No pivoting: the Hessian is strictly positive definite by construction, which
   the centring term guarantees.
3. **`OffsetEnergy`.** Assemble `H` (three diagonals) and the linear term `b` for
   ```
   E(n) = Σ_i w_i [ ρ_i·κ̂_i(n)² + L_R⁻²·(n'_i)² + L_C⁻⁴·(n_i − n̄_i)² ]
   κ̂_i(n) = κ_i + n''_i + κ_i²·n_i
   ```
   with `w_i = Δs_i` and non-uniform central differences for `n'` and `n''`. `ρ ≡ 1` here; time
   weighting is t06.
4. **`OffsetQp`.** Projected Newton with an active set, a compacted banded free-set solve, and a
   backtracking line search. **Both tolerances must be scale-invariant** — see Notes.
5. **`SeedProfile`.** An out-in-out profile per corner, clipped to the box. A seed only: it halves
   the iteration count and carries no correctness burden.
6. **`OffsetCurvature`.** The *exact* offset curvature, for output and for feasibility checks:
   ```
   u = 1 − κn,  v = n',  u' = −(κ'n + κn')
   κ_traj = [κ(u² + v²) + u·n'' − v·u'] / (u² + v²)^{3/2}
   ```
7. **`RacingLine.compute`.** Materialise: `P_i = (x̃_i, ỹ_i) + n_i·N_i`, inverse-project, copy every
   other field, write the four new ones, `computeDerivedData()`.

## Validation

- `./gradlew check ktlintCheck` green on all targets.
- `BandedLdl` reproduces a hand-built 12-node system to `1e-12`, and round-trips `A·(A⁻¹b) = b`.
- `H` is positive definite: every pivot from the factorisation is `> 0` on every fixture.
- **The corridor is never violated**: `lo_i − 1e-9 ≤ n_i ≤ hi_i + 1e-9` at every station of every
  fixture. This is the single most important assertion in the phase — it is what keeps the line on
  the road.
- **The offset map never folds**: `1 − κ_i·n_i > 0` everywhere.
- **T1, 90° corner, `R = 30 m`, `w = 6 m`, `FULL_ROAD`**: the trajectory radius through the corner
  is larger than the centreline's, and the line saturates both edges (out–in–out) rather than
  sitting in the middle.
- **T2, hairpin, `R = 15 m`, 180°**: `|n| ≤ h` everywhere, the apex is on the inside, and the
  radius gain is bounded — the feasibility ceiling for a circle between parallel edges `2(R+h)`
  apart is `R + h = 17.5 m`, so anything above that is off the tarmac.
- **T3, noisy straight**: `max |n|` stays small. Under noise the correct output is "do nothing".
- **`LANE` leaves a straight alone**: `n ≈ 0` where the road is straight, since zero is feasible
  and the centring term has no reason to leave it.
- Output size equals input size; two runs are bit-identical.

## Done when

- [x] Three fields appended, counts synced, sources regenerated
- [x] `BandedLdl` with the hand-built-system and PD tests
- [x] `OffsetEnergy`, `OffsetQp` with scale-invariant tolerances (`SeedProfile` dropped — see Outcome)
- [x] `OffsetCurvature` exact form
- [x] `RacingLine.compute` materialising a new `Path`
- [x] Corridor-containment and no-fold asserted on every fixture
- [x] T1 / T2 / T3 green
- [x] `./gradlew check ktlintCheck` green, working tree clean

## Notes

- **The design's solver tolerances are not scale-invariant and must not be copied.** It specifies
  `‖g_F‖_∞ < 1e-7` and a line-search guard of `E(n) − 1e-12`, both absolute, on quantities whose
  scale is `Σ w_i …` with `w_i` in metres. A 500 km route's energy and gradient are ~10⁵× a 5 m
  fixture's, so as written `converged` would mean something different on every route, and would be
  tightest relative to scale exactly where cross-target agreement is hardest. Use a relative
  Armijo condition (`E_new ≤ E − c₁·α·|gᵀd|`) and normalise the gradient test against the initial
  gradient.
- **`κ̂` is a linearisation and the QP is honest about it.** `κ̂ = κ + n'' + κ²n` is first order in
  `n`; the exact form in step 6 is what gets written out and what the feasibility check uses. The
  two agree to well under a percent inside the corridor, because the corridor is bounded away from
  the fold by the regularity clamp.
- **Cross-target equality is `1e-3 m` on the offset, not `1e-9`.** A box-constrained problem's
  active set is a discontinuous function of the iterate: two targets can legitimately place a
  station on its bound versus a nanometre inside it. Aggregate metrics keep the project's 0.5 %
  rule. Do not attempt to tighten this with a contraction argument — there isn't one.
- Materialisation uses the **smoothed** reference, so even `n = 0` moves a recorded point slightly.
  That is why `SOURCE_LATITUDE`/`SOURCE_LONGITUDE` land in this task and not later.
- Elevation is copied index-aligned, which is correct rather than an approximation to apologise
  for: index `i` is the same road cross-section, and the cross-slope term over a 3 m offset is
  centimetres, an order below the DEM's own tolerance.


## Outcome

Shipped. `RacingLine.compute` returns a new `Path` on the optimised line; nothing in the pipeline
calls it yet, so no existing output changes.

Three things the design got wrong, all found by measurement:

1. **The iteration cap of 12 is far too low.** Measured, a tight single corner takes ~40 projected-
   Newton iterations and a hairpin ~39; the design predicts three to six. The saving grace is that
   the count is bounded by *corner difficulty*, not route length — sixteen corners over 1286
   stations converge in 16 iterations, four corners in 12, a straight in 0. The default is now 200.
   At 12 the solver was being truncated mid-solve and returning a line that hugged the outside of
   every corner, with `converged = false` and a residual of 0.3.
2. **The analytic seed makes it slower, not faster.** The design specifies an out–in–out seed
   "halving the active-set iterations". Measured against starting from the projection of zero, it
   is slower on every fixture and faster on none: 60 iterations against 43 on a 90° corner, 16
   against 12 on a four-corner route. The reason shows up in the active-set count — a seed that
   saturates the corridor puts every station on a bound, and projected Newton then releases them a
   few at a time, while the solution itself has only a handful active. `SeedProfile` is deleted.
3. **Without a mask the solver amplifies noise instead of ignoring it.** On a 1.5 m-jitter straight
   it moved the line *further* from the true road than the input was — 2.11 m against 1.49 m. The
   objective's response to curvature is `n'' ≈ −κ`, and integrating noise twice is a random walk;
   the centring prior at `L_C = 60 m` is orders of magnitude too weak to hold it. The fix is
   `objectiveRadiusM`: bends gentler than 200 m are excluded from the objective outright, because
   `MaxSpeedComputer` applies no cornering limit beyond that radius, so straightening them buys
   exactly zero time. This is the hard on/off version of the design's saturation mask; the graded
   time weighting belongs to t06.

Also noted while implementing, and left as it is: the "give the active set its own descent
direction" remedy for slow constraint release is a **no-op** for a box QP. A variable is active
precisely because its gradient pushes it out of the box, so its descent direction is clipped
straight back by the projection; the variables that need releasing already test as free.
