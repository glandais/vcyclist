# Racing-Line Design — Feasibility Study

Assessment of [`racing-line.md`](racing-line.md) against the code as it stands on
`feat/racing-line` (`4031d33`). Everything below was checked in the repo or measured, not
recalled.

**Verdict: technically feasible, and the mathematics is sound — but the design is calibrated
against a stale snapshot of the codebase, and its measured payoff on aggregate metrics is
roughly an order of magnitude below what its own test plan asserts.** Three defects would
land as real bugs if implemented verbatim. Recommend re-scoping around the *estimator* fix,
which is where the measurable value is, before committing to the 11-task plan.

---

## 1. What checks out

### 1.1 The closed form is correct

`R_line = R_c + h·cot²(δ/4)` is not asserted in the design, so I derived it. Corner vertex
`V` at the origin, interior bisector along `+x`, straights at `±(π−δ)/2`; centreline arc
centre at `d₀ = R_c/cos(δ/2)`. A trajectory circle centred at `(t, 0)` tangent to both outer
edges has `ρ = t·cos(δ/2) + h`; tangency to the inner edge at the apex gives
`t − ρ = d₀ − (R_c − h)`. Solving with `c = cos(δ/2)`:

```
ρ = R_c + h·(1+c)/(1−c) = R_c + h·cot²(δ/4)
```

`δ = 180°` → `R_c + h` = 17.5 m; `δ = 90°` → `30 + 2.5·5.828` = 44.57 m. Both match §4 and
§10-T1 exactly, and the hairpin answer coincides with the independent feasibility ceiling
(a circle between parallel edges `2(R_c+h)` apart). The design's §1.1 claim that the two
competing designs' `18.31 / 18.5 m` answers leave the road is arithmetically right.

### 1.2 The three bugs it diagnoses in `MaxSpeedComputer` are all real

Verified in `engine/.../physics/MaxSpeedComputer.kt` and `gpx/.../path/Path.kt`:

- **`normalizeAngleDiff` ±π wrap** (`MaxSpeedComputer.kt:87,100`): the ±10-point window at
  1–2 m spacing spans ~30 m; a bend tighter than ~9.5 m radius turns more than π over that
  span and wraps to a *smaller* angle, hence a *larger* radius and an overspeed. The design's
  "below ~9.5 m" threshold is right.
- **`Δs` coupling**: the window is a fixed *point* count, so the radius estimate silently
  depends on the resampler's spacing.
- **`computeBearing` shear** (`Path.kt:250-266`): `x = lon·cos(lat)` uses *absolute* longitude
  with a per-point cosine, so `∂x/∂lat = −lon·sin(lat)`. At 6°E / 45°N that is `−0.074`, i.e.
  `atan(0.074) = 4.2°` of shear on a due-north segment — exactly the number the design quotes,
  and it grows linearly with longitude.

### 1.3 The mechanical prerequisites are all cheap and non-invasive

| Claim | Status |
|---|---|
| `PointPerDistance` interpolation is NaN-propagating | ✅ `PointPerDistance.kt:141` |
| `PointPerSecond` likewise | ✅ `PointPerSecond.kt:115` |
| `PathSimplifier` copies fields, no interpolation | ✅ `PathSimplifier.kt:71` |
| `computeDerivedData` rebuilds `distance`/`grade`/`bearing` from lat/lon | ✅ `Path.kt:168-236` |
| `radius`/`speedMax`/`speedMaxIncline` unwritten at slot 4b | ✅ only `MaxSpeedComputer` writes them, at step 5 |
| `Cyclist.tanMaxLeanAngle`, `maxSpeedMS` exist for §3.7 | ✅ `Cyclist.kt:26,45` |
| `ElevationSmoother` is hard-wired to `CoordinatesElevation` | ✅ `ElevationStep.kt:51-52` — generalising it locally is the right call |
| Adding fields has low blast radius | ✅ `:fit` and `demo/` do not enumerate `PointField`; CSV/JSON writers already render NaN as `""` / `null` (`CsvNumberFormat.kt:47`, `JsonWriter.kt:132`) |

The `nanDefault` codegen change is ~10 lines in `GeneratePath.kt` plus an `init` loop.

---

## 2. Blocking defects

### 2.1 The `MaxSpeedComputer` patch in §8.2 disables the friction ellipse

The design's snippet:

```kotlin
private fun computeRadiusWindowed(path: Path, i: Int, k: Int): Double {
    val kt = path.trajectoryCurvature(i)
    if (!kt.isNaN()) return min(MAX_RADIUS_M, max(MIN_RADIUS_M, 1.0 / max(abs(kt), 1e-9)))
    … existing windowed estimate …
}
```

with the note *"`path.setRadius(i, …)` is still performed by the caller, unchanged."*

**It is not.** `setRadius` is performed by `computeRadiusWindowed` itself
(`MaxSpeedComputer.kt:92,96`); the caller `computeCorneringLimit` only writes
`speedMaxIncline`. The early return therefore leaves `radius` at `0.0`, and
`computeBrakingLimit` reads it four lines later (`MaxSpeedComputer.kt:150-153`):

```kotlin
if (radius >= MAX_RADIUS_M || !radius.isFinite() || radius <= 0.0) {
    // Straight: the whole friction budget is available for braking.
```

`radius <= 0.0` is true, so **every point on a racing-line path takes the straight-road
branch and the R11 friction ellipse is silently switched off**, along with the `radius` field
in every export. One added `path.setRadius(i, r)` fixes it; the point is that the design's
stated invariant is wrong, so a reviewer following it would ship the bug.

### 2.2 Follow-up task t13 is already done

§11 lists as an optional follow-up: *"t13 — friction-ellipse coupling of the braking and
cornering caps in `MaxSpeedComputer` (currently taken independently, optimistic by up to 41 %
… a pre-existing bug)"*.

Ledger **R11** shipped it (`63aa84e`, `improvements-ledger.md:52` ✅), and the ellipse is in
the code I read above. Delete t13. This matters beyond bookkeeping: R11's own measured
outcome is the best available prior for what this stage will buy (§3 below).

### 2.3 Field counts are two short throughout

The design says `PointField` goes 36 → 39. The repo is at **38** (`PointField.kt:220`,
`GeneratePath.kt:66`, `PointFieldTest.kt:11`) — `W_PRIME_BALANCE` and `P_BRAKE` were added
after the design's snapshot. So it is **38 → 41**, and §2.1, §3.11 ("copy all 39 slots") and
§10 ("`PointFieldTest` count 36 → 39", "`0.0` for the other 36") all need the correction.
Cosmetic on its own, but it dates the whole document to before R12/R16.

### 2.4 T4's oracle contradicts the design's own closed form

§10-T4 asserts that in `LANE` mode the *unfavourable* hairpin handedness gives
`min |1/κ_traj| < 15.0` — "*slower* than the centreline".

Apply the design's own construction. `LANE` = `[−h, 0]`; for a right hairpin the corridor is
the inside half, a band of width `h` whose midline is at radius `R_c − h/2` with half-width
`h/2`:

```
ρ = (R_c − h/2) + (h/2)·cot²(δ/4) = 13.75 + 1.25 = 15.00 m exactly
```

Not `< 15.0`. The test is a knife-edge failure. Worse, the general statement is wrong: for a
90° corner the same computation gives `28.75 + 1.25·5.828 = 36.0 m`, i.e. **the inside-lane
line is still faster than the centreline**, because a rider can run wide-apex-wide inside one
lane. `δ = 180°` is the degenerate case where the two effects cancel. The narrative "the line
correctly refuses to cross the centreline and is slower" should read "≥ the centreline, with
equality only at 180°".

Note the same computation for the *favourable* handedness gives `R_c + h = 17.50 m` — identical
to `FULL_ROAD`, because at 180° the ceiling is set by the outer edges alone. That does land
inside T4's `[16.2, 17.6]` band, so T4's first assertion is fine.

---

## 3. The payoff is smaller than the test plan asserts

§10 requires the CLI smoke to show `durationMs` **down 0.5–4 %**. I measured the headroom.

Ran the shipped fixtures through the real pipeline (`--no-simplify
--no-one-point-per-second --csv`, defaults otherwise: 280 W constant, 35° lean, dry) and
integrated `dt` over the points where the speed envelope actually binds
(`speed ≥ speedMax − 0.05`, `radius < 200 m` — this includes the backward braking envelope,
not just apexes):

| fixture | duration | `radius < 200 m` | **envelope binding** |
|---|---|---|---|
| `stelvio.gpx` | 578 s / 3.6 km | 55.6 % of time | **5.36 %** |
| `strava.gpx` | 2 892 s / 21.1 km | 59.6 % of time | **1.56 %** |
| `sample.gpx` | 19 220 s / 130.4 km | 40.8 % of time | **0.98 %** |
| `stelvio` reversed (descent) | 661 s | 56.8 % | **2.89 %** |
| `sample` reversed (descent) | 15 450 s | 41.3 % | **2.17 %** |

So the rider spends **1–5 % of ride time at the cornering/braking limit**. The design's own
best case is `+8 %` apex speed on a `FULL_ROAD` hairpin (§4), and `LANE` — the legal default —
roughly halves that on the favourable handedness and yields nothing on the other (§2.4). Even
crediting the full 8 % over the whole binding fraction:

```
0.08 × 5.4 %  = 0.43 %   (stelvio, the curviest fixture)
0.08 × 1.0 %  = 0.08 %   (sample)
```

**0.05–0.4 %, against an asserted 0.5–4 %.** The T-series assertion would fail on every
shipped fixture, and the effect sits at or below the project's own 0.5 % parity budget for
aggregate metrics. This is the same lesson R11 recorded from measurement rather than
prediction (+0.03 % to +0.17 %, `improvements-ledger.md:302-310`) — and for the same
structural reason: constraint algebra only moves the clock where the constraint binds.

I also could not reproduce the noise pathology §5.1 cites as a co-benefit ("the recorded
ring's curvature noise … produces `R = 5 m` spikes and 20 km/h caps"): across all three
fixtures the `MIN_RADIUS_M = 5 m` clamp fires on exactly **1 point each** — an endpoint
artefact. These fixtures may be router-generated and smoother than a phone recording, but the
claim is unevidenced in the data the repo ships.

### 3.1 Where the value actually is

The estimator swap (§8.2) is worth more than the trajectory. It touches the **40–60 % of ride
time with `radius < 200 m`**, not the 1–5 % where the envelope binds, and it fixes three
demonstrable bugs (§1.2) that today corrupt `radius`, `speedMax`, the R11 ellipse and `pBrake`
alike.

**This is separable from the racing line and much cheaper.** A curvature field computed with
`n ≡ 0` — heading regression in a proper local planar frame, no QP, no corridor, no corner
detector — is §3.1–3.3 alone, one task instead of eleven.

The design's test plan cannot see this, because it conflates the two effects: T1 asserts
`min speedMax` rises by `√(44.57/30) = 1.219 ± 5 %` after `MaxSpeedComputer`, attributing all
of it to the line, when part is the estimator change. **The two need to be measured
separately**, e.g. via a `RacingLineOptions` mode that writes `TRAJECTORY_CURVATURE` with the
offset pinned to zero.

---

## 4. Integration gaps

### 4.1 The WASI ABI surface is not mentioned

§8.2 lists CLI, JS façade and docs. It omits `engine/src/wasmWasiMain/.../WasiOptions.kt`,
which is a **documented, versioned ABI** (`docs/wasm-wasi-abi.md`, task w03/w04 parity) and
which rejects unknown keys rather than ignoring them:

```kotlin
private val ENHANCE_KEYS = setOf("fixElevation", …, "simplifyZExaggeration")
…
requireOnly(ENHANCE_KEYS)
```

A host passing `racingLine` would get an error, and w04's parity requirement is broken the
moment the JS façade gains the option. Not hard, but it is a fourth surface plus its tests,
and it should be in t07.

### 4.2 `JunctionReconstructor` cannot reuse the op-list it plans to

§3.10: *"Resizes the path via the existing `PointPerDistance` plan/materialize op-list
(`Copy(i)` / `Interpolate(from,to,coef)`)."* `Op` is a **`private sealed interface` nested
inside `PointPerDistance`** (`PointPerDistance.kt:44-54`) in `:gpx`, while
`JunctionReconstructor` lives in `:engine`. Either the op-list is promoted to `internal` in
`:gpx` and re-exposed (a `:gpx` API change, so a separate task), or t09 writes its own
~40-line materialiser. Prefer the latter — it keeps the arrow pointing one way.

### 4.3 Two non-scale-invariant tolerances in the solver

§3.8's stopping rule `‖g_F‖_∞ < 1e-7` and line-search guard
`E(proj(n+αd)) > E(n) − 1e-12` are **absolute** thresholds on quantities whose scale is
`Σ w_i …` with `w_i = Δs_i` in metres. A 500 km route's energy and gradient are ~10⁵× a 5 m
fixture's. As written, `report.converged` means different things on different routes, and the
JVM/JS agreement the design promises at `1e-3 m` (§6.7) is least likely exactly where the
tolerance is tightest relative to scale. Use a relative Armijo condition
(`E_new ≤ E − c₁·α·|gᵀd|`, `c₁ = 1e-4`) and normalise the gradient test by `‖g⁰‖_∞` or by
total arclength. Cheap to fix; expensive to debug later, cross-target.

### 4.4 The justification table cites documents that are not in the repo

§1.1's FATAL/MAJOR table — the document's entire warrant for its architecture — indexes
findings as "lattice-dp 4", "min-curv 8", "geom 11". `docs/design/` contains only
`racing-line.md`. Those three critiques should be committed alongside it, or the table
reduced to self-contained statements; as it stands the design's central argument cannot be
audited.

---

## 5. Effort and risk

| Task | Size | Risk |
|---|---|---|
| t01 codegen `nanDefault` + 3 fields | S | low — must land with the "fresh `Path` reads NaN" test |
| t02 GPX width plumbing | S/M | low; `"width"` is a risky generic leaf name to claim in `parseExtensions` |
| t03 frame + conditioning + curvature | M | low — **highest value/effort ratio in the plan** |
| t04 corner detector + corridor | M | low |
| t05 QP core (`BandedLdl`, projected Newton, seed) | **L** | medium — the numerical heart; see §4.3 |
| t06 IRLS + grade coupling | M | medium — `gradeApexCoupling` is admittedly a hand-tuned scalar |
| t07 `MaxSpeedComputer` + Enhancer + CLI + JS + **WASI** | M/L | medium — four API surfaces, see §2.1 and §4.1 |
| t08 roundabout Shape A | M | medium — five conjunctive gates, no real-world corpus to validate against |
| t09 junction reconstruction | **L** | **high** — fabricates road geometry that is not in the input |
| t10 lattice-DP fallback | M | medium — a second producer to keep consistent with the first |
| t11 docs + demo | S | low |

Realistically 4 000–6 000 lines with tests across three targets. The design is honest about
t09 ("the most dangerous thing this design can do") and about §12's open questions — question
1 in particular is decisive and unresolved: `R_line − R_c = h·cot²(δ/4)` is **linear in `h`**,
so `defaultRoadWidthM = 6.0` being wrong by 2× makes the entire gain wrong by 2×, in an
unknown direction, on a corridor the stage cannot observe.

---

## 6. Recommendation

1. **Split t03 out and ship it alone**, as a curvature-estimator fix with the offset pinned to
   zero. It fixes three real bugs, touches 40–60 % of ride time instead of 1–5 %, needs one
   new `PointField` instead of three, no QP, no corridor, no OSM data, and no `feat!:`
   coordinate change. Measure it against the three fixtures the way R11 was measured.
2. **Fix §2.1, §2.2, §2.3 and §2.4 in the design document** before any of it is implemented.
3. **Re-derive §10's aggregate-metric bands** from §3's measurements — as written they fail on
   every shipped fixture. Per-corner assertions (T1/T2 radius bands) are unaffected and remain
   the right way to test the stage.
4. **Answer §12 question 1 first.** Without a width source the stage is calibrated on a
   fiction, and t14 (OSM ingestion) is arguably the true blocking dependency for t08–t10, not
   an optional follow-up.
5. If the full stage proceeds anyway, add WASI to t07, give t09 its own materialiser, and make
   the solver tolerances scale-invariant.
