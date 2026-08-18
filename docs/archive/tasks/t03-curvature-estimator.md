# t03 — Planar frame + heading-regression curvature estimator

## Goal

Replace `MaxSpeedComputer`'s windowed bearing-difference radius estimate with a curvature field
computed by heading regression in a properly anchored local planar frame, with the lateral
offset pinned to `n ≡ 0`.

This is `docs/design/racing-line.md` §3.1–3.3 and §8.2 **without** the corridor, the QP, the
corner detector or any coordinate change. The maintainer scoped the branch this way on the
feasibility study's recommendation (§3.1, §6.1): the estimator
touches the **40–60 % of ride time with `radius < 200 m`**, whereas the trajectory itself only
moves the clock over the **1–5 %** where the speed envelope actually binds. It is one task
instead of eleven, needs one new `PointField` instead of three, requires no width data, and
changes no output coordinate.

It fixes three demonstrable bugs (all verified in the feasibility study, §1.2):

1. **`normalizeAngleDiff` ±π wrap** (`MaxSpeedComputer.kt:87,100`) — the ±10-point window spans
   ~30 m at 1–2 m spacing, so a bend tighter than ~9.5 m radius turns more than π, wraps to a
   *smaller* angle, and yields a *larger* radius: a 2× overspeed on hairpins, fillets and kinks.
2. **`Δs` coupling** — the window is a fixed *point* count, so every radius silently depends on
   the resampler's spacing.
3. **`computeBearing` shear** (`Path.kt:250-266`) — `x = lon·cos(lat)` uses *absolute* longitude
   with a per-point cosine, so `∂x/∂lat = −lon·sin(lat)`: 4.2° of shear on a due-north segment
   at 6°E/45°N, growing linearly with longitude.

Because `radius` feeds the R11 friction ellipse, `pBrake`, `speedMax` and
`MuscularPowerProvider.pedalsClear`, correcting it corrects all four.

## Depends on

[t01](t01-nan-default-curvature-field.md) — `TRAJECTORY_CURVATURE` with `nanDefault = true`.

## Inputs

- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/MaxSpeedComputer.kt` —
  `computeRadiusWindowed` L80-98, `computeCorneringLimit` L61-78, `computeBrakingLimit` L139-173
- `engine/src/commonMain/kotlin/io/github/glandais/engine/Enhancer.kt` — `enhanceCourse` L96-158
- `engine/src/commonMain/kotlin/io/github/glandais/engine/EnhanceOptions.kt`
- `elevation/.../ElevationSmoother.kt` — the distance-based triangular-kernel pattern to transpose
- `elevation/.../Constants.kt` — `EarthConstants.MEAN_RADIUS`, `MathConstants`
- Fixtures for measurement: `demo/public/gpx/{stelvio,strava,sample}.gpx`

## Steps

> **These steps are as planned, not as shipped.** Three of them were contradicted by measurement
> during implementation — the arclength `s` in step 2, the scale-selection rule in step 3, and the
> curvature-smoothing width. See **Outcome** at the end for what actually landed and why; the code
> comments carry the reasoning at each call site.

1. **`LocalFrame`** (`engine/src/commonMain/.../trajectory/LocalFrame.kt`, `internal`).
   Anchor once at the bounds centre; `k = cos(lat0)` held **constant** so the map is exactly
   invertible and no per-corner re-anchoring introduces seams (design §3.1):
   ```
   x_i = R_E·k·(lon_i − lon0)      y_i = R_E·(lat_i − lat0)
   ```
   Convention: `x` east, `y` north, standard math azimuth, `κ > 0` = turning left. This is
   **not** `Path.computeBearing`'s `atan2(-dy, dx)` screen convention — the stage never reads
   `path.bearing(i)`, and the sign convention must be stated in the KDoc because the two differ.
   Guards: unwrap longitudes by ±2π about `lon0` if the span exceeds π; bail out (leave the
   field NaN) above 85° latitude or on NaN lat/lon. If the bounds span > 1.5° of latitude, split
   into ≤1° chunks with 500 m overlap and blend — or, simpler and sufficient here since `n ≡ 0`
   and curvature is frame-local, re-anchor per chunk and document the 0.87 %/55 km anisotropy
   bound.
2. **Geometry conditioning.** Triangular distance-weighted kernel `W_g = 5 m` over `x` and `y`,
   two-pointer, `O(n)` — the `ElevationSmoother` algorithm generalised to `DoubleArray` (that
   object is hard-wired to `CoordinatesElevation`, so a local variant is the right call).
   Arclength `s_i = path.distance(i)`.
   **Do not** raise `W_g` for noisy traces: noise is handled by the estimator's scale selection,
   and a 20–25 m kernel would shrink a hairpin by 2.2 m.
   Shrinkage compensation is **not needed here** — it exists in the design to keep the *corridor*
   geometry honest, and heading regression is shrink-free (symmetric smoothing preserves total
   turn, so the regression slope is unbiased). Say so in the KDoc rather than porting §3.2's
   outward displacement.
3. **`CurvatureEstimator`** (`internal`). Unwrapped heading `θ_i` from the `i−1 → i+1` chord of
   the smoothed reference, unwrapped by `wrap(a) = a − 2π·round(a/2π)` — this is what retires
   bug 1: a continuous unwrapped heading has no ±π wrap to alias. Curvature is the OLS slope of
   `θ` on `s` over a window of half-width `W`:
   ```
   κ_i^(W) = (m·D − A·C)/(m·B − A²),  A=Σs', B=Σs'², C=Σθ', D=Σs'θ'
   ```
   with `s' = s − s_i`, `θ' = θ − θ_i`. **Local centering is mandatory** — global prefix sums lose
   3+ digits on a 500 km route. Sliding window with incremental add/drop, `O(n)` per scale.
   Guard `m·B − A² < 1e-6·m·W²` ⇒ `κ = 0`.
   Windows are **metric** (`W ∈ {6, 12, 25} m`), not point counts — this is what retires bug 2.
   Scale selection: take the **largest** window whose regression RMS residual on `θ` is
   `< 2·σ_θ`, else the smallest. Largest-admissible minimises variance and lets the road's
   bending rather than the noise pick the scale (a smallest-window rule is biased upward).
   Then one 8 m triangular smoothing of `κ` to remove scale-switch steps.
4. **Pipeline slot.** New step between 1d (`smoothElevation`) and 2 (`MaxSpeedComputer`), where
   the path is dense, elevation is final, and `radius`/`speedMax` are unwritten. Writes
   `TRAJECTORY_CURVATURE` only; **touches no other field and no coordinate.**
5. **`MaxSpeedComputer` hook.** In `computeRadiusWindowed`:
   ```kotlin
   val kt = path.trajectoryCurvature(i)
   if (!kt.isNaN()) {
       val r = min(MAX_RADIUS_M, max(MIN_RADIUS_M, 1.0 / max(abs(kt), 1e-9)))
       path.setRadius(i, r)   // ← NOT optional; see Notes
       return r
   }
   ```
6. **Option surfaces.** Default-on, so there is nothing to plumb through CLI / JS / WASI for the
   normal path — but add an escape hatch to `EnhanceOptions` (appended **last**, per the
   positional-Java-call-site rule at `EnhanceOptions.kt:61-62`) that restores the legacy
   estimator, because the measurement in step 7 needs an A/B and the parity tests need a
   pinned-old-behaviour mode. Mirror it in `WasiOptions.ENHANCE_KEYS` (unknown keys are a hard
   error there, so w04 parity breaks the moment any other surface gains the flag),
   `EngineJsApi.EnhanceOptionsDto`, and `EnhanceCommand` (`Boolean?`, never `Boolean` — picocli
   `negatable` inverts a non-null default silently, per the comment at `EnhanceCommand.kt:116-125`).
7. **Measure, then write the bands.** Run old vs new across `stelvio`, `strava`, `sample` and the
   two reversed descents, the way R11 was measured: `durationMs`, the `radius < 200 m` time
   fraction, the envelope-binding fraction (`speed ≥ speedMax − 0.05`), and the distribution of
   `radius` deltas. Record as a new ledger entry in `docs/research/improvements-ledger.md`.
   **Derive the aggregate test band from this measurement — do not assert a predicted number.**
   The design's own `durationMs` band (0.5–4 %) is known to fail on every shipped fixture.

## Outputs

- `engine/src/commonMain/.../trajectory/{LocalFrame,PlanarFrame,CurvatureEstimator}.kt`
- One `Enhancer` step + the `MaxSpeedComputer` hook
- `EnhanceOptions` legacy-estimator flag, mirrored on all four surfaces
- A ledger entry with measured per-fixture deltas
- Re-baselined pipeline parity expectations

## Validation

- `./gradlew check ktlintCheck` green on JVM + JS Node + JS browser.
- **Arc recovery**: synthetic exact arcs (`R = 5, 15, 30, 100, 200 m`) at 1.5 m spacing recover
  `κ` to 1 %, at every radius — including below 9.5 m, where the old estimator is wrong by ~2×.
  This is the test that pins the bug-1 fix.
- **Spacing invariance**: the same arc sampled at 1 m and at 2 m yields the same radius to 1 %.
  Pins bug 2, which no existing test can see.
- **Shear**: a due-north straight at 6°E/45°N yields `|κ| < 1/1000`, and the recovered heading
  matches truth to `1e-3 rad`. Pins bug 3.
- **Noise**: 1 km straight with σ = 1.5 m white lateral jitter from a fixed LCG (seed 12345) —
  genuinely white, **not** a sum of sinusoids, since a 40 m sinusoid is a real 60 m-radius corner
  a correct estimator *should* report. Assert `max |κ| < 1/300` and two runs bit-identical.
- **Legacy flag**: with the legacy estimator selected, `Enhancer` output is byte-identical to
  `develop` on a real fixture. This is the regression guard for both the t01 codegen change and
  the hook.
- **Ellipse still live**: assert `radius` is written (non-zero, finite) at every point on a
  new-estimator run, and that `FrictionEllipseTest` / `PedalStrikeTest` / `BrakePowerTest` still
  exercise the cornering branch rather than silently falling through to the straight-road one.
- CLI smoke: `enhance stelvio.gpx --gpx out.gpx`, no NaN in the output.

## Done when

- [x] `LocalFrame` + `CurvatureEstimator` implemented, `internal`, pure functions over `DoubleArray`
- [x] `TRAJECTORY_CURVATURE` written in the new pipeline slot, no other field touched
- [x] `MaxSpeedComputer` hook in place **including `setRadius`**
- [x] Legacy-estimator flag on all four option surfaces + cross-assertion test extended
      (`EnhanceOptions.curvature`, CLI `--curvature`/`--no-curvature`, JS `curvatureEnabled`,
      WASI `curvatureEnabled` in `ENHANCE_KEYS`)
- [x] Arc-recovery, spacing-invariance, shear, noise and byte-identical-legacy tests green
- [x] Measured deltas recorded in the ledger (**R23**); no aggregate band asserted — the
      measurement showed 0.24–8.95 %, far outside the 0.5 % parity budget and fixture-dependent,
      so per-corner assertions carry the correctness burden instead
- [x] `./gradlew check ktlintCheck` green, working tree clean

## Notes

- **`setRadius` is the trap.** The design (§8.2) claims *"`path.setRadius(i, …)` is still
  performed by the caller, unchanged."* **It is not** — `setRadius` is called only inside
  `computeRadiusWindowed` itself (`MaxSpeedComputer.kt:91,96`); the caller
  `computeCorneringLimit` writes only `speedMaxIncline`. An early return that skips it leaves
  `radius = 0.0`, and `computeBrakingLimit` reads it four lines later
  (`MaxSpeedComputer.kt:150-153`) where `radius <= 0.0` takes the straight-road branch. Following
  the design verbatim therefore **silently disables the R11 friction ellipse at every point** and
  empties the `radius` field in every export. Hence the explicit assertion in Validation.
- **This is a `fix(engine):` commit, not a `feat:`.** It changes `radius`/`speedMax` on every
  existing ride, in the direction of correctness, beyond the project's 0.5 % aggregate parity
  budget. The re-baselining is expected; the ledger entry is what justifies it.
- The design's §5.1 claim that recorded rings produce `R = 5 m` spikes and 20 km/h caps is
  **unevidenced in the fixtures the repo ships** — the `MIN_RADIUS_M` clamp fires on exactly one
  point per fixture, an endpoint artefact (feasibility study, §3). Do not cite it as a
  motivation; the three bugs above stand on their own.
- Determinism (design §6.7): no RNG, no hashing of floats, no sorting by float key, no
  parallelism, fixed loop bounds, ascending summation order, `hypot` banned in favour of
  `sqrt(a*a+b*b)`. With `n ≡ 0` there is no active set, so the cross-target story is much
  simpler than the full stage's — the project's normal `1e-9` composed-trig tolerance applies.
- The noise floor is real and worth stating in the KDoc: `θ` comes from `W_g`-smoothed
  coordinates, so adjacent residuals are correlated and the effective sample count is `≈ W/W_g`,
  not the point count. The realistic floor is `σ_κ ≈ 0.008 m⁻¹` at `W = 25 m` — a 125 m radius
  resolution limit, which sits just under `MAX_RADIUS_M = 200 m`.
- Design-document corrections to fold in while here: field counts are 38→41 (not 36→39); t13
  (friction-ellipse coupling) is **already shipped** as R11 (`63aa84e`) and should be deleted
  from §11; T4's `min |1/κ_traj| < 15.0` oracle is a knife-edge failure — the correct statement
  is `≥ centreline, with equality only at δ = 180°`; §3.8's absolute solver tolerances are not
  scale-invariant; and `JunctionPolicy.AGGRESSIVE` is dropped by maintainer decision.


## Outcome

Shipped. Ledger entry **R23** carries the measurement.

Two things the design got wrong, both found by measuring rather than reading, both now commented
at their call sites:

1. **Heading must be regressed against the smoothed curve's own arclength**, not `path.distance`.
   Mixing the smoothed curve's heading with the raw curve's length understates curvature by 13 %
   on a 7 m hairpin — in the unsafe direction.
2. **The scale-selection allowance must be measured from the trace, at the widest window.** A
   fixed absolute gate rejects wide windows first, which is backwards; and measuring at the
   narrowest window mistakes jitter for signal, because a fit with barely more observations than
   parameters tracks noise instead of measuring it. Measured wrong, 1.5 m of jitter invents 20 m
   corners on a straight; measured right, 265 m.

A third was caught by a test rather than a measurement: the design's `curvatureSmoothWindowM = 8 m`
is wider than a tight bend is long — a 6 m-radius 90° corner is 9.4 m of arc — so it reported that
corner as a 13 m one, a 47 % overspeed. The shipped default is 3 m, and the ladder gained a 3 m
regression window so the tight end is resolvable at all.

Residual, documented rather than hidden: the estimator reads ~15 % high at `R = 5 m` and ~11 % at
6 m, high meaning optimistic. Narrowing further starts reporting noise as corners; `MIN_RADIUS_M`
clamps consumers at 5 m regardless.
