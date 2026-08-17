# Racing-Line (Optimal-Trajectory) Stage for vcyclist — Final Design

> **Status update (2026-08-17).** This document is the *full* design. What has actually shipped is
> its geometry half — the curvature estimator of §3.1–3.3 and the `MaxSpeedComputer` hook of §8.2,
> with the lateral offset pinned to `n ≡ 0` — as ledger entry
> [**R23**](../research/improvements-ledger.md), on the feasibility study's recommendation that the
> estimator is where the measurable value sits. The QP, the corridor, corner detection, roundabouts
> and junction reconstruction are **not** implemented. Task specs:
> [`t01`](../tasks/t01-nan-default-curvature-field.md), [`t03`](../tasks/t03-curvature-estimator.md).
>
> Maintainer decisions taken since, which override the text below where they disagree:
>
> - **Corridor** stays `LANE` by default; `FULL_ROAD` requires an explicit opt-in documented as
>   closed-road/TT only. OSM width ingestion (t14) is not a prerequisite for the estimator.
> - **Original coordinates** will be preserved in two new `nanDefault` fields
>   (`SOURCE_LATITUDE` / `SOURCE_LONGITUDE`) when the QP stage lands and starts moving points —
>   §12 question 3 is answered, in favour of preservation. Not needed yet: nothing moves today.
> - **`JunctionPolicy.AGGRESSIVE` is dropped**, per §12 question 5 — the policy is `OFF` or
>   `DECLARED_ONLY` only. Fabricating road geometry that is not in the input, in a way that makes
>   the simulated corner *faster* than reality, is not worth having behind any flag.
>
> Corrections to the text below, from [`racing-line-feasibility.md`](racing-line-feasibility.md):
>
> - Field counts are **38 → 41**, not 36 → 39; `W_PRIME_BALANCE` and `P_BRAKE` landed after this
>   document's snapshot. (The estimator alone took it 38 → 39.)
> - **§11's t13 is already shipped** as ledger R11 (`63aa84e`) — delete it.
> - **§8.2's snippet is wrong**: it claims the caller still calls `setRadius`. It does not —
>   `setRadius` is called only from inside `computeRadiusWindowed`, and an early return that skips
>   it leaves `radius = 0.0`, which `computeBrakingLimit` reads as "straight road" and so disables
>   the R11 friction ellipse at every point. The shipped hook writes it.
> - **§10-T4's oracle is a knife-edge failure**: the inside-lane line gives exactly 15.00 m at
>   δ = 180°, not `< 15.0`. The correct general statement is *≥ the centreline, with equality only
>   at 180°* — for a 90° corner the inside-lane line is 36.0 m and genuinely faster.
> - **§3.8's solver tolerances are not scale-invariant** (`‖g‖∞ < 1e-7`, `E(n) − 1e-12` on sums
>   whose scale is `Σ Δs_i` in metres). Use a relative Armijo condition and normalise the gradient
>   test before implementing the QP.
> - **§8.2 omits the WASI surface** (`WasiOptions.ENHANCE_KEYS` rejects unknown keys, so w04 parity
>   breaks the moment any other façade gains an option) and **§3.10's op-list reuse is impossible**
>   — `PointPerDistance.Op` is private and in `:gpx`.
> - **§5.1's noise co-benefit is unevidenced** in the fixtures this repo ships.
>
> Finally, two things the measurement contradicted outright, recorded in R23: the aggregate
> `durationMs` bands in §10 are not reachable, and the estimator makes rides **slower**, not
> faster, because correcting an optimistic radius can only lower a speed ceiling.

**Status:** design, approved for implementation. **Module:** `:engine`, package `io.github.glandais.engine.trajectory`, `commonMain`.
**Reference:** Zignoli & Biral 2020 (`docs/research/zignoli2020.pdf`) — curvilinear state `(s, n, α)`, corridor `|n| ≤ w/2`, `v_max = √(µ g R_traj)`, friction ellipse, `W_max = 0` for `|roll| ≥ 20°` (**Appendix**, not the body's 5° which applies to their pedal-clearance sub-case).

---

## 1. Overview & rationale

**Primary approach: a box-constrained convex quadratic program in the lateral offset `n(s)`, solved *exactly* by a projected-Newton active-set method over a pentadiagonal system, with time-aware reweighting; seeded and masked by an explicit corner/roundabout detector; junction geometry reconstructed up-front; a bounded lattice-DP fallback for spans the QP declares infeasible.**

Why, in ten lines, against the three critiques:

1. The QP is the only formulation of the three with a **unique minimizer** — no argmin ties (kills lattice-dp §7), no two-pass heuristic with no fixed point (kills geometric-apex §8), no dependence on window boundaries.
2. Replacing SOR/cascadic-multigrid with a **direct banded factorization** kills every convergence critique against min-curvature (spectral radius, `converged` flag lying, `ω` tuning, 3 m residual) — the free-set solve is exact in `O(n)`.
3. Corner detection is retained but demoted to **seeding, masking and diagnostics**, so `R̄_c = L_c/δ_c` stops being load-bearing (geometric-apex §3) and the closed form is only an oracle for tests.
4. The closed form used as oracle is **`R_line = R_c + h·cot²(δ/4)`** (tangency to both outer edges) — the only one of the three that is feasible; both competitors' `18.5 / 18.31 m` hairpin answers leave the road.
5. Time-awareness (lattice-dp's genuine advantage) is recovered cheaply by **IRLS reweighting toward `∫√κ ds`, masked to the cornering-limited support**, which also suppresses pointless motion on gentle bends.
6. Geometric templates (roundabout ring, junction arc) from geometric-apex/lattice-dp are grafted in as **corridor rewrites and a pre-densification reconstruction stage**, not as competing constructions.
7. `MaxSpeedComputer` is changed by **one guarded line** to read a written signed curvature field — this retires the `normalizeAngleDiff` ±π wrap, the ±10-point aliasing, and the `Δs`-change coupling in one move.
8. No active windows, no verbatim copy-through: the offset field is defined and smooth over the **whole path**, so there is no lateral step at any boundary.
9. Elevation stays index-aligned by design; grade legitimately rescales by `1/(1−κn)` because the trajectory really is a different length over the same climb.
10. Cost: ~15 linear passes + ≤12 banded solves — faster than min-curvature and lattice-dp, within 3–5× of geometric-apex, and correct.

### 1.1 FATAL/MAJOR findings and where each is fixed

| Origin | Finding | Fix |
|---|---|---|
| lattice-dp 1, min-curv 1 | Wrong closed-form radius (18.31 / 18.5 m; divergence below 87°) | §4 uses `R_line = R_c + h·cot²(δ/4)`; hairpin = `R_c + h` = 17.5 m. |
| lattice-dp 2, min-curv 1 | Test oracles encode the wrong number | §10 T2/T3 bands re-derived from the correct oracle (8.0 %, not 10.5 %). |
| lattice-dp 3, min-curv 5 | Appended `PointField`s init to `0.0`, `isNaN()` sentinels dead | §2.1: codegen gains a `nanDefault` flag; `GeneratedPath.data` is NaN-filled for those slots; readers use `isNaN() \|\| v <= 0`. |
| lattice-dp 4 | ~2 m lateral step at active-window boundaries → phantom 28 m corner | No windows: `n(s)` is one global field, `C²` by construction; straights get `n≈0` from the pin term, not from copy-through. |
| lattice-dp 5, min-curv 10, geom 10 | Smoothing shrinks corner radii / mis-applied compensation | §3.2: small `W_g = 5 m` kernel + **closed-form outward compensation `ΔR = W_g²/(12R)`** applied only where `|κ| > 1/60`; curvature itself comes from heading regression, which is shrink-free. |
| lattice-dp 6 | Normalized lattice ⇒ `maxPathAngle` unenforced | No lattice in the primary path; the DP fallback (§3.9) uses **absolute** metric offsets with per-station step limits. |
| lattice-dp 7, min-curv 11, geom 13 | Cross-target argmin/threshold flips vs 1e-9 tolerances | §6.4: bound-snap epsilon, hysteresis on all classification thresholds, and a stated **1e-3 m** offset tolerance; no argmin over simulated times anywhere. |
| lattice-dp 8 | Runtime optimistic 5–10× | §7.2 budgets from flop counts of a direct solve, not of 11×DP×3. |
| lattice-dp 9, geom 6 | Planned physics ≠ simulated physics; roll threshold misattributed | Trajectory stage never uses `µ`; it uses `tan θ_lean` only in the IRLS mask, so nothing is double-counted. Roll gate is an explicitly separate follow-up task, cited to the Appendix (20°). |
| lattice-dp 10 | Junction detector runs where its own test cannot | §3.10: `JunctionReconstructor` runs at slot **0**, on the raw input, before any densification. |
| lattice-dp 11 | `PathSimplifier` (10 m) erases the racing line | §8: tolerance capped at `2.0 m` when the stage ran. |
| min-curv 2, geom 11 | Roundabout detector swallows the hairpin | §5.1 requires **five** conjunctive gates incl. straight legs on both sides and endpoint separation `< 2R_fit`; a 180° hairpin fails gates 1 and 5. |
| min-curv 3, geom 6/7 | `MaxSpeedComputer` ±10-pt window aliases (>180° wrap ⇒ 2× overspeed) and is sheared by `computeBearing` | §8.2: it reads `TRAJECTORY_CURVATURE` when present. Guarded by NaN default ⇒ no behaviour change when disabled. |
| min-curv 4 | Forced 2.0 m resample changes every radius estimate | **No resample.** Non-uniform stencils; output size == input size. |
| min-curv 6/7/8 | SOR non-convergence, cascadic bias, single-pin non-decoupling | Direct banded solve; pins are always **two adjacent nodes**, which do decouple a bandwidth-2 system. |
| min-curv 9, geom 5 | `OWN_LANE` default displaces everything / invalidates the construction | §3.4: default `LANE` corridor is `[-h, 0]` (right-hand traffic) — **`n = 0` is feasible**, so straights are untouched; the QP is derived for arbitrary asymmetric boxes. |
| min-curv 12 | `Σ|d_i|²` ≠ `∫κ² ds`; 2× apex bias | §3.5 uses the affine curvature proxy `κ̂ = κ + n'' + κ²n` with arclength weights `Δs_i`, plus one metric reweighting `w_i ← Δs_i(1−κ_i n_i^{prev})`. |
| min-curv 13 | Module placement contradicts dependency arrow | Stage lives in `:engine` (needs `Cyclist`, `EngineConstants`). |
| geom 1/2/4 | Late-apex reparameterization leaves the corridor; chicane assembly undefined; C⁰ steps at corner mouths | The analytic profile is only a **seed**; the QP is the producer and enforces the box jointly over all corners. |
| geom 3 | `R̄_c` biased +42 % by clothoids | Not used by the producer; classification uses the 20th-percentile radius `R_q20`. |
| geom 12 | Shape-B reconstruction breaks 1–2 m spacing | Reconstruction at slot 0, re-densified by both `PointPerDistance` passes. |
| all | Switchback stacks / out-and-backs pushed into each other | §3.4 adds an `O(n)` grid-hashed self-proximity clamp. |

---

## 2. Data model changes

### 2.1 `PointField` (36 → 39) and NaN defaults

Appended at the end (ordinal is the wire slot):

| Field | prop | unit | category | writer |
|---|---|---|---|---|
| `ROAD_WIDTH` | `roadWidth` | `m` | geometry | `GpxToPath` (extension) or NaN |
| `LATERAL_OFFSET` | `lateralOffset` | `m` | geometry | `RacingLine`; left-positive (Zignoli) |
| `TRAJECTORY_CURVATURE` | `trajectoryCurvature` | `1/m` | geometry | `RacingLine`; **signed**, `+` = left |

**Required codegen change (blocking):** `PointField` gains `val nanDefault: Boolean = false`, set `true` for these three. `:codegen` emits, in `GeneratedPath`'s `init`, a loop that writes `Double.NaN` into every `nanDefault` slot of every row. Existing 36 fields keep zero-init, so nothing changes for them. Without this, every `isNaN()` sentinel in this document is dead and the `MaxSpeedComputer` hook caps the whole route at 21 km/h (lattice-dp finding 3). `PointPerDistance`/`PointPerSecond`/`PathSimplifier` need no change (NaN-propagating linear interpolation is correct for all three).

Readers use `fun width(i) = path.roadWidth(i).let { if (it.isNaN() || it <= 0.0) opts.defaultRoadWidthM else it }`.

Memory note: +3 doubles/point = +12 MB on a 500 k path. Accepted; `ROAD_WIDTH` must be per-point because `PointPerDistance` resamples and a scalar option cannot survive a per-segment OSM width.

### 2.2 GPX width extension

`GpxParser.parseExtensions`: recognise lowercased leaves `"roadwidth"`, `"width"` → `ExtensionsAccumulator.roadWidthM: Double?` (first value wins, matching existing style). `GpxTrackPoint` gains `roadWidthM: Double? = null`. `GpxToPath.pointsToPath` writes it or `Double.NaN`. `GpxWriter` emits `<extensions><vc:roadWidth>` under `NS_VCYCLIST = "https://github.com/glandais/vcyclist/xmlschemas/v1"`. Track-level default: a `<trk><extensions><roadwidth>` value is parsed into `GpxTrack` and used for points lacking their own. `<rtept>` keeps skipping extensions (existing deliberate behaviour) → routes get `options.defaultRoadWidthM`.

Sanity clamp on read: `w ∈ [2.5, 20] m`, then smoothed over 20 m of arclength (OSM widths are step functions; a step in `h` is a step in the constraint set).

---

## 3. Algorithm

Input: dense `Path` (1–2 m spacing), elevation final, `distance`/`bearing`/`grade` valid, `radius`/`speedMax` unwritten. Output: new `Path`, **same size**.

### 3.1 Local planar frame

Anchor once at the bounds centre; `k = cos(lat0)` constant so the map is exactly invertible:

```
lat0 = (bounds.minLat+bounds.maxLat)/2 ; lon0 = (bounds.minLon+bounds.maxLon)/2 ; k = cos(lat0)
x_i = R_E·k·(lon_i − lon0)          lat_i = lat0 + y_i/R_E
y_i = R_E·(lat_i − lat0)            lon_i = lon0 + x_i/(R_E·k)
```
`R_E = EarthConstants.MEAN_RADIUS`. Scale error `≈ tan(lat0)·Δlat` is E–W only; it is **not** re-anchored per corner (geometric-apex finding 15 — mixing frames creates seams). Its effect on curvature is a bounded anisotropy; on a 55 km N–S span at 45° it reaches 0.87 %, below the 5 % test bands. If `bounds` span > 1.5° of latitude, the path is split into contiguous chunks of ≤ 1° with 500 m overlap, solved independently, and blended over the overlap by a linear ramp on `n` (`n` is frame-independent, so the blend is exact).

Guards: unwrap longitudes by `±2π` about `lon0` if the span exceeds π; return `source.copy()` above 85° latitude.

Convention: `x` east, `y` north, standard math azimuth. `N_i = (−sin θ_i, cos θ_i)` is the **left** normal; `n > 0` = left; `κ > 0` = turning left. This is *not* `Path.computeBearing`'s inverted, sheared convention; the stage never reads `path.bearing(i)`.

### 3.2 Conditioning

**Geometry kernel `W_g = 5 m`** — distance-weighted triangular convolution of `x` and `y` (the `ElevationSmoother` kernel, generalised to `DoubleArray` here since that object is hard-wired to `CoordinatesElevation`), two-pointer, `O(n)`:
`x̃_i = Σ_j w_ij x_j / Σ_j w_ij`, `w_ij = max(0, 1 − |s_j − s_i|/W_g)`.

**Shrinkage compensation.** A triangular kernel of half-width `W` has `E[t²] = W²/6`; convolving a circular arc contracts its radius by `ΔR = W²/(12R)` (14 cm at `W=5, R=15`, quadratic in `W`). Where `|κ_i| > 1/60 m⁻¹`, displace the reference outward:
`(x̃,ỹ)_i += sign(κ_i)·(−N_i)·min(W_g²/(12·|1/κ_i|), 0.25 m)`.
`W_g` is deliberately **not** raised for noisy traces (min-curvature's 20–25 m advice would shrink a hairpin by 2.2 m); noise is handled by the curvature estimator instead (§6).

Arclength `s_i` = `path.distance(i)` (cumulative haversine, monotone by construction). Spacing `h_i^- = s_i − s_{i−1}`, `h_i^+ = s_{i+1} − s_i`, `Δs_i = (h^- + h^+)/2`.

### 3.3 Curvature by heading regression, multi-scale

Unwrapped heading `θ_i` from the smoothed reference (`atan2` of the `i−1 → i+1` chord, unwrapped by `wrap(a) = a − 2π·round(a/2π)`). Curvature is the OLS slope of `θ` on `s` over a window of half-width `W`:

```
κ_i^(W) = (m·D − A·C)/(m·B − A²),  A=Σs', B=Σs'², C=Σθ', D=Σs'θ'  over Ω_i
```
with `s' = s − s_i`, `θ' = θ − θ_i` (**local centering is mandatory** — global prefix sums on a 500 km route lose 3+ digits; geometric-apex finding 14). Computed by a sliding window with incremental add/drop of centered contributions using Welford-style updates, `O(n)` per scale. Guard `m·B − A² < 1e-6·m·W²` ⇒ `κ = 0`.

Scales `W ∈ {6, 12, 25} m`. Selection rule (fixing geometric-apex finding 9 — the smallest-window rule is selection-biased *upward*): take the **largest** window whose regression RMS residual on `θ` is `< 2·σ_θ`; if none, take the smallest. Largest-admissible minimises variance and lets the road's own bending, not the noise, pick the scale. Then one 8 m triangular smoothing of `κ` to remove scale-switch steps. `κ'` by central difference of the smoothed `κ`.

Heading regression is **shrink-free**: total turn over an arc is preserved by symmetric smoothing, so its slope is unbiased regardless of `W_g`.

### 3.4 Corridor `[lo_i, hi_i]`

```
h_i = clamp(width_i/2 − edgeMarginM, 0.0, 6.0)          edgeMarginM = 0.5
```
Corridor mode (`n` left-positive):

| mode | box | notes |
|---|---|---|
| `LANE` (**default**, right-hand traffic) | `[−h_i, 0]` | `n=0` feasible ⇒ straights untouched; no cutting into oncoming |
| `LANE_LEFT` | `[0, +h_i]` | UK/AU/JP |
| `FULL_ROAD` | `[−h_i, +h_i]` | closed road / TT only; explicitly labelled |

Three clamps applied to the box, in order:

1. **Offset-curve regularity.** The map folds at `1 − κn = 0`: `lo_i ← max(lo_i, −0.85/max(|κ_i|,1e-9))`, `hi_i ← min(hi_i, 0.85/max(|κ_i|,1e-9))`.
2. **Self-proximity** (new; the switchback-stack hazard all three designs shared). Bucket the reference points into a 12 m uniform grid keyed by `(floor(x/12), floor(y/12))`. For each `i`, `d_i = min |P_i − P_j|` over `j` with `|s_i − s_j| > 60 m` (probe the 9 neighbouring buckets; `O(n)` expected). Then `h_i ← min(h_i, max(0, d_i/2 − 0.5))` applied symmetrically to both bounds. On a hairpin stack with 12 m leg separation this caps `|n|` at 5.5 m — non-binding; where legs are 4 m apart it collapses the corridor, which is correct.
3. **Roundabout / junction rewrites** (§5).

Pins: `lo = hi = 0` at `i ∈ {0, 1, N−2, N−1}` (two adjacent nodes at each end — a bandwidth-2 system needs **two** to decouple; min-curvature finding 8). Additional pin pairs are inserted where a run of ≥ 150 m has `|κ| < 1/500`, at the run's midpoint, which segments the global solve into independent blocks.

### 3.5 The energy

First-order expansion of the exact offset curvature (§3.6) in `n`:

```
κ̂_i(n) = κ_i + n''_i + κ_i²·n_i          (affine in n; error O(n², n'²))
```

Energy, in physical units (`m⁻²` per metre of arclength), with `n̄_i` the projection of 0 onto the box:

```
E(n) = Σ_i w_i [ ρ_i·κ̂_i(n)²  +  L_R⁻²·(n'_i)²  +  L_C⁻⁴·(n_i − n̄_i)² ]
w_i  = Δs_i · max(0.2, 1 − κ_i·n_i^{prev})        (arclength metric; frozen from the previous outer round)
```

- `n'`, `n''`: non-uniform central differences —
  `n'_i = (h⁻²·n_{i+1} + (h⁺²−h⁻²)·n_i − h⁺²·n_{i−1}) / (h⁺h⁻(h⁺+h⁻))`,
  `n''_i = 2(h⁺·n_{i−1} − (h⁺+h⁻)·n_i + h⁻·n_{i+1}) / (h⁺h⁻(h⁺+h⁻))`.
  One-sided (zero-slope) at the pins; because pins are `lo=hi=0` pairs, the stencil never reaches past them.
- `L_R = 20 m` (steering-rate / length proxy; `∫n'²ds` is exactly the excess-length term to second order **and** Zignoli's steering-rate cost).
- `L_C = 60 m` (centring prior; makes the Hessian strictly PD and pins straights).
- `ρ_i ≥ 0`: time weight, §3.7. Round 0: `ρ ≡ 1`.

`E` is convex quadratic; the Hessian `H` is symmetric, positive definite, **pentadiagonal** (bandwidth 2: `κ̂²` couples `i±2`, `n'²` couples `i±1`).

### 3.6 Exact offset curvature (output, feasibility checks, tests)

With `u = 1 − κn`, `v = n'`, `u' = −(κ'n + κn')`:

```
κ_traj = [ κ(u² + v²) + u·n'' − v·u' ] / (u² + v²)^{3/2}
ds_traj = √(u² + v²) · ds
```
(This is the correct form — lattice-dp's variant has a wrong `2κn'` coefficient.) Evaluated once at the end, written to `TRAJECTORY_CURVATURE`, and used to verify that the linearization error `|κ_traj − κ̂|` stays below `0.1·|κ_traj|`; spans that fail go to §3.9.

### 3.7 Time weighting (IRLS) and the saturation mask

Cornering time is `T = √(µ_eff/g)·∫√κ ds` over the cornering-limited support, so the true geometric objective is `∫√κ ds`, not `∫κ²ds`. Two reweighting rounds:

```
µ_eff   = cyclist.tanMaxLeanAngle                       (0.7002 at 35°)
R_sat   = cyclist.maxSpeedMS² / (G·µ_eff)               (112.4 m at 100 km/h)
mask_i  = 1 if |1/κ_traj,i| < R_sat  else 0             (below saturation ⇒ curvature buys time)
ρ_i     = mask_i · clamp((|κ_traj,i| + 1/200)^(−3/2) · Z, 0.2, 5.0)   (Z normalises mean(ρ|mask)=1)
```
`ρ = κ^{−3/2}` makes `ρκ² = √κ` exactly at the current iterate — standard IRLS. The mask is the fix for geometric-apex finding 6: on a 150 m sweeper the cyclist is speed-capped, curvature buys nothing, `ρ = 0`, and the centring prior returns `n → 0` — no pointless 2.5 m displacement of the user's track.

**Grade coupling (optional, `gradeApexCoupling`, default 0.15).** Zignoli's downhill corners re-accelerate for free. Within each detected corner, scale `ρ` by `1 + gradeApexCoupling·φ_i·tanh(grade_exit/6)` with `φ ∈ [−1,+1]` linear across the corner — penalising the exit more when the exit is *uphill*, which shifts the apex late. Default is a mild late-apex bias; `0.0` gives the pure type-I line. This is the only place any type-II behaviour is modelled, and it is labelled a heuristic.

### 3.8 Solver: projected Newton on a pentadiagonal system

```
n ← seed (§3.8.1), projected onto the box
repeat r = 0 .. maxOuter-1 (default 12):
    g ← ∇E(n)                                            # O(n), banded matvec
    A ← { i : (n_i ≤ lo_i + εb and g_i > 0) or (n_i ≥ hi_i − εb and g_i < 0) }   # active set, εb = 1e-6 m
    F ← complement of A
    if ||g_F||_inf < gTolM (1e-7 m⁻¹) and A unchanged: converged; break
    solve H_FF · d_F = −g_F                              # banded LDLᵀ, bandwidth 2, O(|F|)
    d_A = 0
    α ← 1.0
    while E(proj(n + α·d)) > E(n) − 1e-12 and α > 2^-10:  α ← α/2       # exact-arithmetic-free safeguard
    n ← proj(n + α·d)
```
`H_FF` retains bandwidth 2 under symmetric deletion (index distance only shrinks), so the factorization is the standard 5-band `LDLᵀ`:
`d_i = H_ii − Σ_{k=1,2} L_{i,i−k}²·d_{i−k}`, `L_{i,i−1} = (H_{i,i−1} − L_{i,i−1}... )/d_{i−1}`, etc. — 9 flops per row to factor, 8 to solve. Positive definiteness of `H` (guaranteed by `L_C⁻⁴ > 0`) makes it stable without pivoting.

Projected Newton on a strictly convex box QP **identifies the active set in finitely many steps and then terminates at the exact minimizer**; in practice 3–6 iterations. This retires min-curvature findings 6, 7, 8 and 11 (a `converged` flag that means something; no `ω`; no cascade; a genuine residual test). `RacingLineReport.converged` reports `||g_F||_inf` and the final active-set size.

**3.8.1 Seed.** For each detected corner (§3.9), write the analytic out–in–out profile of geometric-apex §1.7 (radius `R_line = R_q20 + h·cot²(δ/4)`, capped by the available straight budget), *clipped to the box*, blended to `n=0` between corners by cubic Hermite with zero end slopes. This is a seed only: it costs `O(n)`, halves the active-set iterations, and — crucially — carries no correctness burden, so the late-apex/leg-branch/chicane defects of geometric-apex (findings 1, 2, 4) cannot reach the output.

### 3.9 Corner detection (masking, seeding, diagnostics) and the DP fallback

State machine on smoothed `κ`, with hysteresis: enter at `|κ| > 1/120`, stay while `|κ| > 1/250` and sign unchanged; close on sign flip; merge same-sign corners with a gap `< max(15 m, 3w)`; reject corners shorter than 8 m or turning less than 8°. Per corner: `δ_c` (endpoint unwrapped heading difference), `L_c`, **`R_q20` = 20th percentile of `1/|κ_i|`** (robust *and* unbiased by clothoid tails — geometric-apex finding 3), `R_min`, apex index, sign.

**Lattice-DP fallback.** A corner span is *flagged* when any of: (a) the regularity clamp `0.85/|κ|` binds, (b) `|κ_traj − κ̂| > 0.1|κ_traj|` after the QP, (c) `sign(κ)` flips more than 3 times within 20 m. For flagged spans shorter than 400 m, re-solve that span alone by dynamic programming over an **absolute-metric** offset lattice: stations every 4 m, levels every 0.25 m over `[lo,hi]` (≤ 25 levels), state `(n_{i−1}, n_i)`, transitions limited by an absolute `Δn ≤ 4·tan(15°) = 1.07 m` per station (not a fixed index count — lattice-dp finding 6), stage cost `Δs_i·(ρ_i·κ_Menger² + …)` matching §3.5, ties broken by lowest level index. Ends pinned to the QP's values. Deterministic, `O(len·M²·k)`, and confined to pathological geometry. Default `dpFallback = true`.

### 3.10 Junction reconstruction (pipeline slot 0)

Runs on the **raw input path**, before any densification, so its discriminating tests have the original vertices to work with (lattice-dp finding 10). Resizes the path via the existing `PointPerDistance` plan/materialize op-list (`Copy(i)` / `Interpolate(from,to,coef)`), all fields interpolated, NaN-propagating.

Policy `JunctionPolicy` = `OFF` | `DECLARED_ONLY` (**default**) | `AGGRESSIVE`.

`DECLARED_ONLY` fires on declared junctions only: a `<wpt><type>roundabout</type>` with an optional `<vc:radius>`, or an explicit `RoundaboutHint` in options. `AGGRESSIVE` additionally fires on the three-part kink test (heading change > 50° over a 10 m window; implied radius < 6 m; incoming/outgoing tangents fitted by TLS over 30 m each with residual < 1.5 m) — documented experimental, because fabricating 30 m of road that is not in the input is the most dangerous thing this design can do.

Reconstruction (closed form): offset the incoming tangent line by `τ·laneWidth/2` (`τ = −1` right-hand traffic), intersect with the circle `|P − V| = r`, `r = roundaboutRideRadiusM` (default 12 m, or the declared radius), take the intersection before `V`; same for the outgoing; connect along the circle in direction `−τ`, which yields the short way round for a right turn and the long way for a left or U-turn with no case analysis. Resample the arc and stubs at 3 m; both `PointPerDistance` passes then re-densify normally.

### 3.11 Materialization

```
X_i = x̃_i + n_i·Nx_i ;  Y_i = ỹ_i + n_i·Ny_i   → inverse-project → lat/lon (radians)
out = Path(source.size); copy all 39 slots; overwrite latitude/longitude
out.setLateralOffset(i, n_i); out.setRoadWidth(i, width_i); out.setTrajectoryCurvature(i, κ_traj,i)
out.computeDerivedData()
```
- **Elevation is copied index-aligned and this is correct**, not an approximation to apologise for: index `i` is the same road cross-section, and the cross-slope term `n·camber ≤ 2.5 %·3 m = 7.5 cm` is an order below the DEM tolerance. `computeDerivedData` then recomputes `distance` from the new lat/lon, so `grade` rescales by `1/(1−κn)` — which is physically right: the same `Δz` over a genuinely shorter inside line *is* a steeper grade.
- `radius`/`speedMax`/`speedMaxIncline` are still zero here, so nothing stale propagates.
- `TIME` untouched; meaningful only with `virtualizeTrack = true` (documented, not enforced).

---

## 4. Hairpin walkthrough — `R_c = 15 m`, 180°, `w = 6 m`, `FULL_ROAD`, dry

`h = 6/2 − 0.5 = 2.5 m`. Arc length `π·15 = 47.1 m`, ~31 stations at 1.5 m.

**Feasibility ceiling.** The trajectory circle must fit between two *parallel* outer edges `2(R_c+h) = 35 m` apart, so `ρ ≤ 17.5 m`. The tangency construction gives exactly that: `R_line = R_c + h·cot²(δ/4) = 15 + 2.5·cot²(45°) = 15 + 2.5 = 17.5 m`, centre on the bisector at `p = R_line − R_c + h = 5.0 m`. Both competitors' answers (18.31, 18.5) require 36.6/37.0 m of width and are **0.8–0.9 m off the tarmac on each side**.

**What the solver produces.** Seed = the tangency arc, `n` sweeping `−2.5 → +2.5 → −2.5` (right hairpin: outside is left ⇒ `n>0` on entry/exit is wrong — for a **left** hairpin `κ>0`, inside is left, so entry/exit saturate at `−2.5` and the apex at `+2.5`). The QP keeps the apex and both outer-edge saturations active, and relaxes the entry/exit transitions into the Euler-spiral-like shape that minimises `∫ρκ̂² + L_R⁻²n'²`. Lateral transit `5 m` over the `√(8h·R_line) = 18.7 m` lead-in ⇒ peak `|n'| ≈ 0.30` (path angle 17°) and a transition curvature `≈ 2h/d² = 0.014 m⁻¹` (R = 70 m) — never binding.

**Numbers.**

| | radius | `v = √(g·R·tan35°)` |
|---|---|---|
| centreline (today) | 15.00 m | 10.15 m/s = 36.5 km/h |
| racing line | 17.50 m | 10.97 m/s = 39.5 km/h |

**+8.0 %** apex speed (`√(17.5/15) = 1.080`), sustained ~50 m, plus the backward braking envelope starting 8 % higher ~60 m up the approach. Correctly modest: a 180° in a narrow road is a 180° in a narrow road, and any design reporting +30 % here is off the road.

Checks: `1 − κn = 1 − 2.5/15 = 0.833 > 0`; the 0.85/κ clamp (12.75 m) is inactive; self-proximity clamp inactive for legs > 6 m apart; roll at the apex `atan(v²κ/g) = 35.0°` — above Zignoli's 20° gate, so the follow-up roll-gate task (§12) will zero rider power through the apex, further rewarding the wide exit.

---

## 5. Roundabouts

### 5.1 Shape A — the ring is drawn

Detection requires **all five** (the fifth and first are what keep a 180° hairpin out — min-curvature finding 2, geometric-apex finding 11):

1. `|Σ κ Δs| ≥ 3π/2` (270°) with constant sign over a run of arclength ≤ 250 m. *A 180° hairpin fails here.*
2. Kåsa algebraic circle fit (3×3, closed-form Cramer, centred coordinates) with RMS radial residual `< max(1.5 m, 0.12 R_fit)`.
3. `R_fit ∈ [6, 40] m`.
4. Stations immediately before and after the run have `|κ| < 1/60` (straight legs both sides). *A switchback climb fails here.*
5. Run endpoints within `2·R_fit` of each other. *A hairpin fails here too.*

Where a traversal is only 90–150° of ring (the common 1st/2nd-exit case that gate 1 rejects), the span falls through to the ordinary `CORNER` template — which is acceptable **only** because of the corridor rewrite below, which applies whenever the span is inside a declared or detected roundabout footprint even if gate 1 fails.

**Corridor rewrite** (this is the whole special case):
- `roundaboutLaneWidthM = 5.0` overrides the default width inside the ring, so `h = 2.0` — with the 6 m default the constructed apex sits 0.5 m *inside* the kerb, i.e. on the island (geometric-apex finding 11).
- Island exclusion: `n` on the island side is bounded by `islandClearanceM = 0.3 m` from the fitted lane's inner edge.
- Circulation direction is read off `sign(κ)`, never guessed.
- Corridor mode is forced to `FULL_ROAD` inside the circulatory arc (the ring lane *is* the whole legal corridor there).
- Entry/exit splay: within 15 m of the detected mouth, ramp `h` linearly from the approach half-width to the lane half-width.

**Result**, 15 m ring, 5 m lane (`h = 2.0`), 120° of circulation: `R_line = 15 + 2.0·cot²(30°) = 15 + 6.0 = 21.0 m` ⇒ `v` from 32.1 to 38.0 km/h (**+18 %**), and — worth more in practice — the recorded ring's curvature noise, which today produces `R = 5 m` spikes and 20 km/h caps, is replaced by one clean radius.

### 5.2 Shape B — drawn straight through the island

Not detectable from GPX geometry alone; a route drawn straight through a roundabout is indistinguishable from a road that goes straight. Handled by §3.10 at slot 0:

- **`DECLARED_ONLY` (default):** an OSM/router-supplied `RoundaboutHint(lat, lon, radiusM, counterClockwise)` or a `<wpt><type>roundabout</type>` triggers closed-form reconstruction. The path gets *longer* (a 20 m roundabout right-exit traversal grows from ~40 m to ~70 m), which is physically correct and shows as extra time. Interior elevation is re-interpolated by fraction along the new arc.
- **`AGGRESSIVE`:** the three-part kink test; documented experimental, off by default, because a false positive fabricates a detour and a *faster* corner than reality.
- **`OFF` / undetected:** the line stays straight through the island. Wrong, but bounded, and it is exactly the error the current 1D pipeline already makes — no regression. The regularity clamp collapses the corridor at the kink so the QP returns `n ≈ 0` there rather than folding.

---

## 6. Noise & degenerate inputs

Threat model: 1–3 m lateral jitter, correlated over 5–20 s, then *interpolated* (not filtered) by both `PointPerDistance` passes. Naive 3-point curvature at 1.5 m spacing with 1 m noise gives `σ_κ ≈ 1 m⁻¹` — a 1 m radius.

1. **Heading regression, largest admissible scale** (§3.3): variance `≈ 12σ_θ²/(m W²)` vs `2σ_θ²/W²` for endpoint differencing. Note the honest caveat the competitors skipped: `θ` is computed from `W_g`-smoothed coordinates, so adjacent residuals are correlated and the effective `m` is `≈ W/W_g`, not the point count — the realistic floor is `σ_κ ≈ 0.008 m⁻¹` at `W = 25 m`, i.e. a 125 m radius resolution limit. `κ_in = 1/120` is chosen to sit at that floor, and `R_sat = 112 m` (§3.7) means everything above it is masked out of the objective anyway. The two numbers agreeing is not a coincidence — it is why the design is safe above the noise floor.
2. **The centring prior is the failure mode.** Noise-induced curvature has near-zero time value once masked; the `L_C` term wins and the output is `n ≈ 0`. **The algorithm's failure under noise is "do nothing", not "swerve".**
3. **Corridor is built on the smoothed reference**, so jitter never enters the constraint set.
4. **Hysteresis on every threshold** (`κ_in`/`κ_out`, corner merge, roundabout gates) plus a minimum corner length of 8 m: a noise excursion must sustain over 8 m to open a corner.
5. **Spike rejection** (`rejectSpikes = true`): stations where `dx(i) > 5·median(dx)` over 50 points *and* the heading reverses by > 150° have their `(x,y)` linearly interpolated from neighbours **for this stage only**; the output position at those stations is the interpolated one, and the count is reported in `RacingLineReport`.
6. **Degenerate inputs.** `size < 8` ⇒ return `source.copy()`. Zero-length segments (`h ≤ 1e-9`) are collapsed for stencil purposes with the duplicate carrying its neighbour's `n`. `|κ| < 1e-9` never divides (all clamps use `max(|κ|, 1e-9)`). NaN lat/lon ⇒ return `source.copy()` and report.
7. **Determinism.** No RNG, no hashing of floats (the proximity grid keys on integer bucket indices), no sorting by float key, no parallelism, fixed loop bounds, ascending summation order, `hypot` banned in favour of `sqrt(a*a+b*b)`. Cross-target: the only discrete decisions are threshold crossings and active-set membership; both are guarded (hysteresis; `εb = 1e-6 m` bound snap). **Stated tolerance for cross-target assertions on `lateralOffset` is `1e-3 m`, not `1e-9`** — a box-constrained problem's active set is a discontinuous function of the iterate and no amount of contraction argument changes that (min-curvature finding 11). Aggregate metrics keep the project's 0.5 % relative rule.

---

## 7. Complexity & parameters

### 7.1 Complexity

| Stage | Cost |
|---|---|
| projection, arclength | `O(n)` |
| geometry smoothing + shrink compensation | `O(n)` two-pointer |
| heading + unwrap + 3-scale regression | `O(n)` (Welford window updates) |
| corner detection, circle fits | `O(n)` |
| corridor + self-proximity grid | `O(n)` expected |
| seed profile + Hermite blend | `O(n)` |
| **projected-Newton QP** | `O(n)` per iteration × ≤12; typically 4 |
| IRLS outer rounds | ×3 (rounds 0,1,2) |
| DP fallback | `O(Σ len·M²·k)` over flagged spans only |
| materialize + `computeDerivedData` | `O(n)` |

Total `O(n)`; the QP term is `≈ 3 rounds × 4 iterations × 45 flops/point`.

### 7.2 Runtime, 100 k points (~150 km at 1.5 m)

| | JVM | JS (V8) |
|---|---|---|
| preprocessing (3 regressions + smoothing dominate) | 45 ms | 170 ms |
| QP: 12 banded factor+solve+matvec passes | 30 ms | 110 ms |
| materialize + `computeDerivedData` | 15 ms | 60 ms |
| **total** | **~90 ms** | **~340 ms** |

At 500 k points with a 90 %-curvy alpine profile: ~0.5 s JVM / ~1.9 s JS. Memory: 12 scratch `DoubleArray(n)` (9.6 MB at 100 k) + the band factor (3 arrays) + the output `Path`. These budgets come from flop counts of a direct solve; there is no argmin over simulated times and no 11× DP re-ranking, which is why they are an order below lattice-dp's realistic figures.

### 7.3 Parameters

| Parameter | Default | Rationale |
|---|---|---|
| `enabled` | `false` | opt-in; changes output coordinates |
| `defaultRoadWidthM` | `6.0` | two 3 m lanes |
| `edgeMarginM` | `0.5` | half handlebar + gutter |
| `corridor` | `LANE` | legal on open roads; `n = 0` feasible |
| `driveOnRight` | `true` | |
| `geometrySmoothWindowM` | `5.0` | shrink `≤ 14 cm`, compensated |
| `curvatureWindowsM` | `[6, 12, 25]` | |
| `headingNoiseRad` | `0.05` | scale-selection residual gate |
| `cornerEnterRadiusM` / `ExitRadiusM` | `120 / 250` | hysteresis at the noise floor |
| `minCornerLengthM` / `minCornerTurnDeg` | `8.0 / 8.0` | |
| `steeringLengthM` `L_R` | `20.0` | `∫n'²` weight = `L_R⁻²` |
| `centeringLengthM` `L_C` | `60.0` | prior weight = `L_C⁻⁴`; PD guarantee |
| `timeWeighting` | `true` | IRLS toward `∫√κ ds` + saturation mask |
| `irlsRounds` | `2` | plus round 0 |
| `gradeApexCoupling` | `0.15` | mild late apex on uphill exits |
| `maxNewtonIterations` | `12` | typically 4 |
| `gradientToleranceInvM` | `1e-7` | genuine residual test |
| `boundEpsilonM` | `1e-6` | active-set snap, cross-target stability |
| `regularityFactor` | `0.85` | `|n| ≤ 0.85/|κ|` |
| `selfProximityGapM` | `60.0` | `Δs` beyond which two points may collide |
| `dpFallback` | `true` | flagged spans < 400 m |
| `rejectSpikes` | `true` | |
| `junctions` | `DECLARED_ONLY` | fabricating road is opt-in |
| `roundaboutLaneWidthM` | `5.0` | overrides width inside a ring |
| `roundaboutRideRadiusM` | `12.0` | island 8 m + lane |
| `islandClearanceM` | `0.3` | |
| `simplifyToleranceCapM` | `2.0` | applied to step 8 when the stage ran |

Friction `µ` is deliberately **absent**: vcyclist parameterises grip by `Cyclist.maxLeanAngleDeg` (35° ⇔ `µ = 0.700`); Zignoli's dry 0.9 / wet 0.36 map to 42° / 19.8°. Adding a second friction model here would double-count (lattice-dp finding 9).

---

## 8. Enhancer integration

### 8.1 Pipeline position

```
0.  JunctionReconstructor.reconstruct(rawPath, opts)   ← NEW, resizes, junctions != OFF
1.  PointPerDistance(-1, 30)
2.  fixElevation                                        (optional)
3.  PointPerDistance(1, 2)
4.  smoothElevation (150 m)
4b. RacingLine.compute(course, opts)                    ← NEW, same size, rewrites lat/lon
5.  MaxSpeedComputer.computeMaxSpeeds                   ← reads TRAJECTORY_CURVATURE
6.  VirtualizeService.virtualizeTrack
7.  PointPerSecond
8.  PathSimplifier(min(toleranceM, 2.0) if 4b ran)
```

Slot 0 is where junction reconstruction can both *see* the original vertices (its tangent-residual test needs them) and have its inserted arc re-densified by the normal pipeline. Slot 4b is where the path is dense, elevation final, `distance`/`bearing`/`grade` valid, and `radius`/`speedMax` unwritten — so steps 5–6 consume the trajectory. Step 8's cap keeps the deliverable from being Douglas-Peucker'd away (the whole line lives inside 2.5 m of the centreline, well under the 10 m default).

### 8.2 The one `MaxSpeedComputer` change

```kotlin
private fun computeRadiusWindowed(path: Path, i: Int, k: Int): Double {
    val kt = path.trajectoryCurvature(i)
    if (!kt.isNaN()) return min(MAX_RADIUS_M, max(MIN_RADIUS_M, 1.0 / max(abs(kt), 1e-9)))
    … existing windowed estimate …
}
```
`path.setRadius(i, …)` is still performed by the caller, unchanged. Because `TRAJECTORY_CURVATURE` is NaN-defaulted (§2.1), this is a strict no-op unless the stage ran. It simultaneously retires: the `normalizeAngleDiff` ±π wrap that reports a *larger* radius below ~9.5 m (2× overspeed on fillets and kinks); the `Δs`-dependence of the ±10-point window; and the `computeBearing` shear (`x = lon·cos(lat)` per point, 4.2° of shear at 6°E/45°N, worse further east).

`EnhanceOptions` gains `val racingLine: RacingLineOptions = RacingLineOptions.DEFAULT` (disabled) — source-compatible for Kotlin. CLI gains `--racing-line`, `--road-width <m>`, `--corridor lane|lane-left|full-road`, `--junctions off|declared|aggressive`, defaults sourced from `RacingLineOptions.DEFAULT` (extend the existing cross-assertion test). JS façade: `EngineJsApi.enhance` gains a `racingLine?: RacingLineOptionsJs` external interface. Docs to update: `Enhancer.kt` KDoc, `README.md` ASCII diagram, `docs/gpx2web-coverage.md`.

---

## 9. Kotlin API sketch

```kotlin
package io.github.glandais.engine.trajectory

enum class CorridorMode { LANE, LANE_LEFT, FULL_ROAD }
enum class JunctionPolicy { OFF, DECLARED_ONLY, AGGRESSIVE }
enum class CornerKind { GENTLE, CORNER, HAIRPIN, ROUNDABOUT, CHICANE }
enum class RoundaboutSource { DRAWN_LOOP, HINT, DECLARED_WAYPOINT }

data class RoundaboutHint(
    val latitudeDeg: Double, val longitudeDeg: Double,
    val radiusM: Double, val counterClockwise: Boolean,
)

data class RacingLineOptions(
    val enabled: Boolean = false,
    val defaultRoadWidthM: Double = 6.0,
    val edgeMarginM: Double = 0.5,
    val corridor: CorridorMode = CorridorMode.LANE,
    val geometrySmoothWindowM: Double = 5.0,
    val curvatureWindowsM: List<Double> = listOf(6.0, 12.0, 25.0),
    val headingNoiseRad: Double = 0.05,
    val cornerEnterRadiusM: Double = 120.0,
    val cornerExitRadiusM: Double = 250.0,
    val minCornerLengthM: Double = 8.0,
    val minCornerTurnDeg: Double = 8.0,
    val steeringLengthM: Double = 20.0,
    val centeringLengthM: Double = 60.0,
    val timeWeighting: Boolean = true,
    val irlsRounds: Int = 2,
    val gradeApexCoupling: Double = 0.15,
    val maxNewtonIterations: Int = 12,
    val gradientToleranceInvM: Double = 1e-7,
    val boundEpsilonM: Double = 1e-6,
    val regularityFactor: Double = 0.85,
    val selfProximityGapM: Double = 60.0,
    val dpFallback: Boolean = true,
    val rejectSpikes: Boolean = true,
    val junctions: JunctionPolicy = JunctionPolicy.DECLARED_ONLY,
    val roundaboutLaneWidthM: Double = 5.0,
    val roundaboutRideRadiusM: Double = 12.0,
    val islandClearanceM: Double = 0.3,
    val roundaboutHints: List<RoundaboutHint> = emptyList(),
    val simplifyToleranceCapM: Double = 2.0,
) { companion object { val DEFAULT = RacingLineOptions() } }

object RacingLine {
    fun compute(course: CoursePhysics, options: RacingLineOptions = RacingLineOptions.DEFAULT): Path
    fun analyze(course: CoursePhysics, options: RacingLineOptions = RacingLineOptions.DEFAULT): RacingLineReport
}

object JunctionReconstructor {
    fun reconstruct(path: Path, options: RacingLineOptions = RacingLineOptions.DEFAULT): Path
}

class RacingLineReport(
    val size: Int,
    val newtonIterations: Int, val finalGradientInfNorm: Double, val converged: Boolean,
    val activeConstraints: Int, val spikesRejected: Int, val dpFallbackSpans: Int,
    val lateralOffsetM: DoubleArray,
    val centerlineCurvature: DoubleArray,
    val trajectoryCurvature: DoubleArray,
    val corners: List<CornerSpan>,
    val roundabouts: List<RoundaboutSpan>,
)   // class, not data class: DoubleArray members break equals/hashCode

data class CornerSpan(
    val fromIndex: Int, val untilIndex: Int, val apexIndex: Int, val kind: CornerKind,
    val turnRad: Double, val direction: Int,
    val centerlineRadiusM: Double, val lineRadiusM: Double,
    val halfWidthM: Double, val straightLimited: Boolean, val dpFallbackUsed: Boolean,
)
data class RoundaboutSpan(
    val fromIndex: Int, val untilIndex: Int,
    val fittedRadiusM: Double, val counterClockwise: Boolean, val source: RoundaboutSource,
)

// --- internal, one file each, pure functions over DoubleArray ---
internal class PlanarFrame(val x: DoubleArray, val y: DoubleArray, val s: DoubleArray,
                           val theta: DoubleArray, val kappa: DoubleArray, val kappaPrime: DoubleArray,
                           val lat0: Double, val lon0: Double, val k: Double)
internal object LocalFrame        { fun project(path: Path, o: RacingLineOptions): PlanarFrame
                                    fun unproject(f: PlanarFrame, x: Double, y: Double): DoubleArray }
internal object CurvatureEstimator{ fun headings(f: PlanarFrame): DoubleArray
                                    fun curvature(f: PlanarFrame, windows: DoubleArray, sigmaTheta: Double): DoubleArray }
internal object CornerDetector    { fun detect(f: PlanarFrame, o: RacingLineOptions): List<CornerSpan> }
internal object CircleFit         { fun kasa(x: DoubleArray, y: DoubleArray, from: Int, until: Int): DoubleArray }
internal object Corridor          { fun build(f: PlanarFrame, width: DoubleArray, o: RacingLineOptions,
                                              lo: DoubleArray, hi: DoubleArray) }
internal object SeedProfile       { fun build(f: PlanarFrame, corners: List<CornerSpan>,
                                              lo: DoubleArray, hi: DoubleArray): DoubleArray }
internal object OffsetQp          { fun solve(f: PlanarFrame, lo: DoubleArray, hi: DoubleArray,
                                              rho: DoubleArray, seed: DoubleArray,
                                              o: RacingLineOptions): QpResult }
internal object BandedLdl         { fun factor(d0: DoubleArray, d1: DoubleArray, d2: DoubleArray)
                                    fun solveInPlace(d0: DoubleArray, d1: DoubleArray, d2: DoubleArray, b: DoubleArray) }
internal object OffsetLatticeDp   { fun solveSpan(/* … */): DoubleArray }
internal object OffsetCurvature   { fun exact(kappa: DoubleArray, kappaPrime: DoubleArray,
                                              n: DoubleArray, s: DoubleArray): DoubleArray }
```

JVM: `engine/src/jvmMain/.../RacingLineJvm.kt`, `@file:JvmName("RacingLineJvm")`, top-level `@JvmOverloads` delegates named `computeRacingLine` / `analyzeRacingLine` (**not** `compute` — the g27 same-package self-recursion trap). `RacingLineOptions` gets a factory function rather than `@JvmOverloads` on the constructor (the ktlint re-indentation trap). Nothing is `suspend`, so no `…Blocking`/`…Async` pair.

---

## 10. Test plan

`engine/src/commonTest/.../trajectory/` — runs JVM + JS Node + JS browser. Fixtures from a `RacingLineFixtures` object emitting exact arcs/straights in metres at `lat0 = 45°, lon0 = 6°` and inverse-projecting; no GPX round-trip.

**T1 — 90° corner, `R_c = 30 m`, `w = 6 m`, `FULL_ROAD`.** 200 m straight + 90° arc + 200 m straight, 1.5 m spacing.
Oracle: `R_line = 30 + 2.5·cot²(22.5°) = 30 + 2.5·5.828 = 44.57 m`; needed tangent `44.57·tan45° = 44.6 m < 200 m`, so unconstrained.
Assert: exactly one `CornerSpan`, `kind == CORNER`, `turnRad ∈ π/2 ± 0.02`; `centerlineRadiusM ∈ 30 ± 1.5 m`; `min |1/trajectoryCurvature|` over the corner `∈ [0.90, 1.10]×44.57 = [40.1, 49.0] m`; `lateralOffset ∈ [−2.5−1e-9, +2.5+1e-9]` everywhere; `min ≤ −2.3` and `max ≥ +2.3`; endpoints `|n| < 0.02 m`; output size == input size; `totalDistance` shorter than input by 2–9 m; after `MaxSpeedComputer`, `min speedMax` up by `√(44.57/30) = 1.219 ± 5 %`; `report.converged`.

**T2 — hairpin, `R_c = 15 m`, 180°, `w = 6 m`, `FULL_ROAD`.** 100 m straight + arc + 100 m straight.
Oracle `R_line = 17.50 m` (feasibility ceiling `R_c + h`).
Assert: `kind == HAIRPIN`, `turnRad ∈ π ± 0.03`; `min |1/κ_traj| ∈ [16.6, 18.4] m` (`±5 %`); `|n| ≤ 2.5 + 1e-9` **everywhere** (the single most important safety assertion); exactly two sign changes in `n` (no chatter); `1 − κn > 0` at every station; apex offset `> +2.3` (left hairpin) at `0.45–0.80` of the corner arclength; `min speedMax` up by `≥ 1.06×` and `≤ 1.10×` the centreline run (`√(17.5/15) = 1.080`); `C¹` check `max_i |wrap(bearing(i+1)−bearing(i))| / dx(i) ≤ 1.15/17.5`; projection round-trip `< 1e-6 m`.

**T3 — noisy straight.** 1 km due east, 1.5 m spacing, lateral jitter from a fixed LCG (`seed = 12345`) with σ = 1.5 m — genuinely white, **not** a sum of sinusoids (a 40 m sinusoid is a real 60 m-radius corner and a correct optimiser *should* straighten it; lattice-dp T3 could not distinguish "robust" from "broken").
Assert: zero `CornerSpan`s; `max |lateralOffset| < 0.30 m`; **max deviation of output lat/lon from the true straight `< 0.8 m`** (input is ~1.5 m, so the stage denoises rather than amplifies) — this, not the offset, is the meaningful assertion; `totalDistance ∈ [999, 1001] m`; `max |trajectoryCurvature| < 1/300 m⁻¹`; two runs bit-identical (`==` on raw doubles: a reproducibility check, not a numerical comparison).

**T4 — `LANE` default, both hairpin handednesses.** Same fixture as T2 with `corridor = LANE`, `driveOnRight = true`.
Assert: left hairpin (rider's lane outside) `min |1/κ_traj| ∈ [16.2, 17.6]`; right hairpin (lane inside) `min |1/κ_traj| < 15.0` — the line correctly refuses to cross the centreline and is *slower* than the centreline; `lateralOffset ≤ 1e-9` everywhere in both.

**T5 — chicane.** Two opposite 60° corners, `R = 40 m`, 15 m apart.
Assert: `n` crosses zero exactly once between the apexes; no outside-edge anchor between them; `max |n'| ≤ tan(20°)`; corridor never violated.

**T6 — roundabout Shape A.** Synthetic 15 m ring, 300° of circulation, straight legs, lane 5 m.
Assert: exactly one `RoundaboutSpan`, `source == DRAWN_LOOP`, `fittedRadiusM ∈ 15 ± 0.5`; no station closer than `R_fit − h − islandClearance` to the fitted centre (island never entered), tolerance 0.05 m; `min |1/κ_traj| ∈ [19.0, 23.0] m` (oracle 21.0 for 120° of ride).

**T7 — roundabout Shape A negative control.** The T2 hairpin must produce **zero** `RoundaboutSpan`s (gates 1 and 5).

**T8 — roundabout Shape B with a hint.** Straight-through traversal + `RoundaboutHint(r = 12 m, counterClockwise = true)`, `junctions = DECLARED_ONLY`.
Assert: reconstructed path length grows by 25–45 m; all reconstructed stations lie in `[ρ − h, ρ + h]` of the hint centre and none inside `ρ − h`; post-reconstruction spacing after step 3 is in `(1, 2] m`.

**T9 — regularity & self-proximity.** A synthetic 3 m-radius GPS kink: assert `|n| ≤ 0.85/|κ| + 1e-9` and no folding (`1 − κn > 0`). A hairpin stack with 8 m leg separation: assert `max |n| ≤ 3.5 m`.

**Supporting.** Identity (`w = 2·edgeMargin` ⇒ output lat/lon within `1e-9 rad` of input). Disabled (`enabled = false` ⇒ `Enhancer` output byte-identical on a real `GpxFixtures` GPX — the regression guard for the codegen NaN change and the `MaxSpeedComputer` hook). `PointFieldTest` count 36 → 39; `GeneratedPathTest` round-trip for the three fields **plus an explicit assertion that a fresh `Path(3)` reads `NaN` for all three and `0.0` for the other 36**. `GpxParserTest`: `<roadwidth>4.5</roadwidth>` reaches `path.roadWidth(0)`; absent ⇒ `NaN`. `engine/src/jvmTest/java/.../RacingLineJavaTest.java` (JUnit 4) exercising every `@JvmOverloads` arity. CLI smoke: `enhance alpe.gpx --racing-line --gpx out.gpx`, `durationMs` down 0.5–4 %, no NaN in the output.

**Tolerances:** `1e-12` constants, `1e-9` composed trig and round-trips, **`1e-3 m` for `lateralOffset` across targets**, 5 % for constructed radii, 0.5 % for aggregate pipeline metrics. Never `==` on doubles except the two documented reproducibility checks.

---

## 11. Phased implementation plan

Each is a `docs/tasks/tNN-slug.md` in the repo's Goal / Depends on / Inputs / Steps / Outputs / Validation / Done when / Notes format. Two commits per task (`feat(engine|gpx|codegen): … (Phase T task tNN)` then `docs(plan): mark task tNN done`).

- **t01 — `nanDefault` in codegen + three new `PointField`s.** Add `nanDefault` to `PointField`, emit the NaN init loop in `GeneratedPath`, append `ROAD_WIDTH`/`LATERAL_OFFSET`/`TRAJECTORY_CURVATURE`, run `:codegen:run`, update `PointFieldTest` (36→39) and `GeneratedPathTest`. *Blocking for everything else; must land with the "fresh `Path` reads NaN" test.*
- **t02 — GPX width plumbing.** `parseExtensions` leaves, `GpxTrackPoint.roadWidthM`, track-level default, `GpxToPath`, `GpxWriter` + `vc:` namespace, parser tests.
- **t03 — planar frame + conditioning + curvature.** `LocalFrame`, triangular kernel over `DoubleArray`, shrink compensation, `CurvatureEstimator` with Welford windows and largest-admissible scale selection. Tests: exact arcs recover `κ` to 1 %; a 500 km fixture shows no precision loss.
- **t04 — corner detector + corridor.** `CornerDetector` (hysteresis, merge, `R_q20`), `Corridor` (modes, regularity clamp, self-proximity grid), `RacingLineReport` skeleton. Tests: T5 detection, T9 clamps.
- **t05 — QP core.** `BandedLdl`, gradient/Hessian assembly for `E(n)`, projected-Newton loop, `SeedProfile`. Tests: T1, T2, T3 without time weighting; a unit test that `H` is PD and that the solve reproduces a hand-built 12-node system to 1e-12.
- **t06 — IRLS time weighting + grade coupling.** `ρ` update, `R_sat` mask, metric reweight `w_i`. Tests: a 150 m sweeper must produce `max |n| < 0.15 m` (masked out); T1/T2 bands unchanged.
- **t07 — `MaxSpeedComputer` hook + Enhancer integration.** The guarded `trajectoryCurvature` read, `EnhanceOptions.racingLine`, slot 4b, the `simplifyToleranceCapM` cap, CLI flags + cross-assertion test, JS façade. Tests: "disabled ⇒ byte-identical", CLI smoke.
- **t08 — roundabout Shape A.** `CircleFit`, five-gate detector, corridor rewrite, splay. Tests: T6, T7.
- **t09 — junction reconstruction (slot 0).** `JunctionReconstructor`, hints + declared waypoints, closed-form arc, plan/materialize resize; `AGGRESSIVE` behind the flag. Tests: T8, plus a false-positive test that an alpine switchback drawn with 30 m spacing is **not** reconstructed under `DECLARED_ONLY`.
- **t10 — lattice-DP fallback.** `OffsetLatticeDp` for flagged spans, absolute-metric levels and step limit. Tests: a synthetic corner where the regularity clamp binds; assert the DP result beats the QP's energy and is corridor-feasible.
- **t11 — docs + demo.** `Enhancer` KDoc, README diagram, `docs/racing-line.md` (this document, trimmed), the browser demo drawing corridor + line from `RacingLineReport`.

Optional follow-ups, explicitly out of scope here: **t12** — `LEAN_ANGLE` field + Zignoli's roll gate (`W_max = 0` for `|roll| ≥ 20°`, Appendix) in `VirtualizeService`; **t13** — friction-ellipse coupling of the braking and cornering caps in `MaxSpeedComputer` (currently taken independently, optimistic by up to 41 % at the ellipse's 45° point — a pre-existing bug that the racing line makes *more* visible); **t14** — OSM `width`/`lanes`/`junction=roundabout` ingestion, which is the only real fix for §12's first question.

---

## 12. Open questions for the maintainer

1. **Corridor is a fiction.** A recorded GPX is the *rider's* line (already ~`w/4` right and partly apex-cutting), not the centreline; a routed GPX is roughly the centreline; the stage cannot tell which it has, and `defaultRoadWidthM = 6.0` will be wrong by 2× in both directions. `R_line − R_c = h·cot²(δ/4)` is **linear in `h`**, so a 2× width error is a 2× error in the entire gain. Is the OSM-hint path (t14) worth prioritising over t08–t10, or does the project accept a systematically optimistic line on narrow roads?
2. **Default `enabled` and default `corridor`.** Shipping `enabled = false` means nobody uses it; flipping it default-on is a `feat!:` that changes every output GPX's coordinates. And `FULL_ROAD` is the mode that produces the pretty numbers and is illegal on any open road. Do we ship `LANE`-only until OSM data exists, and gate `FULL_ROAD` behind a "closed road" acknowledgement in the CLI?
3. **Silent lossy edit to input data.** The output replaces the user's recorded lat/lon with a smoothed reference plus offset, everywhere — good for physics, potentially surprising for map-matching or segment detection. Should `RacingLine` also write the original coordinates into two new fields, or is `LATERAL_OFFSET` enough to reconstruct them?
4. **Type-II strategy.** `gradeApexCoupling` is a hand-tuned scalar standing in for a variational result; Zignoli's wet-condition slow-in/fast-out cannot emerge from a velocity-blind geometric objective. Is a v2 construct–simulate–reconstruct outer loop (two `VirtualizeService` runs) worth doubling the expensive stage's cost, or do we accept a 1–3 % bias, always in the "too slow" direction, on technical descents?
5. **`AGGRESSIVE` junction detection.** It is the only way to fix straight-through roundabouts without external data, and its worst failure (reconstructing an alpine switchback as a 12 m roundabout arc) produces a *faster* simulated corner than reality. Keep it in the codebase at all, or delete it and rely solely on hints?