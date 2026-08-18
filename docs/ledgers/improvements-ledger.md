# Research improvements ledger

One row per improvement suggested by (or derivable from) [`docs/research/`](../research/README.md), scored
against **the code as it actually stands**. Each entry has an ID so a later task can reference it.

**This file records assessment; implementation status is tracked in the Status column.** It is the tracking surface for
the research; [`07-vcyclist-implementation-notes.md`](../research/07-vcyclist-implementation-notes.md) remains
the narrative synthesis, and where the two disagree the disagreement is stated explicitly in the
entry (R7, R12, R19, R21).

- Date of assessment: 2026-08-17
- Addendum 2026-08-18: **R27–R30** (elevation and cumulative ascent, section F) — sourced from
  Strava's elevation documentation and Kollár (arXiv 1011.4778), not from `docs/research/`.
  **R27 and R28 shipped**; R29 and R30 measured and rejected, R30's door shipped anyway.
- Assessed against: `develop` @ `0f979af`
- Shipped since: **R15** (W′bal output field), **R12** (`pBrake`), **R9** (`RoadCondition`),
  **R17** (`PowerProviderDurability`), **R10** (pedal-strike cut-off),
  **R18** (`PowerProviderSlewLimited`), **R16** (`PowerProviderCriticalPower`),
  **R11** (friction ellipse), **R19** (`PowerProviderTerrainPacing`)
- Sources read: all 8 research chapters + `EngineConstants`, `Cyclist`, `MaxSpeedComputer`,
  `PowerComputer`, `VirtualizeService`, `PowerProviderConstantWithTiring`,
  `CyclistPowerProviderBase`, the four resistive providers, `RhoProvider`, `AeroProvider`,
  `ClimbDetector`, `PointField`

## How to read this

**Status**

| | Meaning |
|---|---|
| ✅ | Applied — already in the codebase |
| ✔ | Already satisfied — the research (or ch. 07) lists it as open, but the code already does it |
| 🔵 | Open, recommended |
| ⚪ | Open, deferred — real but not now |
| ❌ | Rejected — measured as not worth it, or actively wrong for this project |

**Value** is *realism of the simulated ride*, not accuracy in watts. The mechanical layer is already
at the literature's ceiling (§A), so nothing below buys meaningful wattage accuracy; what the open
items buy is a ride trace a human recognises as their own.

**✅ names the surfaces reached.** For five entries in a row it meant "landed in the core and the
CLI", and nobody noticed the JS façade had been left behind until the demo broke — see
[`docs/archive/tasks/41-js-facade-ledger-catchup.md`](../archive/tasks/41-js-facade-ledger-catchup.md). Every
behavioural entry now carries a **Surfaces** line: core / CLI / JS / WASI. An entry that moves
rider behaviour is not done until all four say so, or until the entry says why one is
deliberately skipped. The matrix lives in
[`docs/ledgers/surface-coverage.md`](surface-coverage.md).

## Headline

| ID | Improvement | Value | Cost | Status |
|---|---|---|---|---|
| R1 | Wheel radius 0.7 → 0.35 m | — | — | ✅ |
| R2 | `maxBrakeG` 0.6 → 0.4 | — | — | ✅ |
| R3 | `G` 9.8 → 9.80665 | — | — | ✅ |
| R4 | 35° lean documented as µ = 0.70 | — | — | ✅ |
| R5 | Spoke-rotation drag `F_w` | Negligible | Low | ❌ |
| R6 | Yaw-dependent CdA | Negligible | Low | ❌ |
| R7 | Distance as the integration variable | — | — | ✔ |
| R8 | Vertical-curvature rolling term | Negligible | Med-high | ❌ |
| R9 | Wet/dry µ condition preset | **High** | **Very low** | ✅ |
| R10 | Pedal-strike power cut-off at high lean | Med-high | Low | ✅ |
| R11 | Friction ellipse (combined braking + cornering) | Low (measured) | Medium | ✅ |
| R12 | **Brake power as a `PointField`** | **High** | **Very low** | ✅ |
| R13 | Brake actuation lag (0.13 s) | Negligible | Low | ❌ |
| R14 | Posture-dependent CdA | Medium | Med (unbounded validation) | ⚪ |
| R15 | W′bal as an **output** field (ODE form) | **High** | **Very low** | ✅ (units fixed in R16) |
| R16 | W′bal as a **behaviour** driver (CP-aware provider) | High | High | ✅ |
| R17 | Durability: decay on supra-CP work, not elapsed time | Med-high | Low | ✅ |
| R18 | Power slew-rate limit (50 W/s) | Medium | Low | ✅ |
| R19 | Pacing heuristic (ramp up slow / drop fast) | Medium | Medium | ✅ (no anticipation) |
| R20 | RPE + Hazard Score | Low-med | Low | ⚪ |
| R21 | Fuelling / glycogen state variable | Low | High | ❌ (for now) |
| R22 | Optimal-pacing solver as rider behaviour | Negative | Very high | ❌ |
| R23 | **Curvature by heading regression in a planar frame** | **High (measured)** | Low | ✅ |
| R24 | Racing line (optimal trajectory through corners) | Low (measured) | High | ✅ (opt-in) |
| R25 | Time-weighted racing-line objective (IRLS toward `∫√κ ds`) | Negative (measured) | Medium | ❌ |
| R26 | Road width from the OSM `highway` class | Negligible (measured) | Low | ✅ (shipped, ~0 effect) |
| R27 | **Hysteresis dead band on cumulative ascent** | Med (measured) | Low | ✅ |
| R28 | **Elevation smoothing scale as an option** | **High (measured, 17 % on a switchback climb)** | Low | ✅ (default unchanged) |
| R29 | DEM road-snapping corridor (Strava's basemap idea) | **None (measured)** | High | ❌ |
| R30 | DEM zoom above 12 | Negligible (measured) | Very low | ❌ (door shipped) |

Recommended order if acted on: ~~R15 → R12 → R9 → R17 → R10 → R18 → R16 → R11 → R19 → R23 → R24~~ —
**all shipped**. Left: **R14** (posture CdA) and **R20** (RPE / Hazard Score), both deferred on
evidence rather than effort.

**R27–R30 have a different provenance** and are marked as such: they come from Strava's published
elevation documentation and from Kollár's scale-dependence paper, not from `docs/research/`. They
are tracked here because this file is the project's improvement surface, and because R28 is the
control variable for the other three. The measurements behind them are in
[`../guides/elevation.md`](../guides/elevation.md).

## A. Mechanical layer — closed

The longitudinal power balance is **complete against Martin et al. (1998)**, not merely adequate.
Verified term by term against [`01`](../research/01-physical-modeling.md):

| Martin term | vcyclist |
|---|---|
| Aerodynamic, wind/yaw-resolved | `AeroPowerProvider` (Isvan variant with wind) |
| Rolling with `cos(atan(G_R))` | `RollingResistancePowerProvider` — the `cos` factor is present |
| Wheel bearings `V·(91 + 8.7·V)·10⁻³` | `WheelBearingsPowerProvider` — verbatim |
| Potential energy `sin(atan(G_R))` | `GravPowerProvider` |
| Kinetic energy with `I/r²` | `PowerComputer.equivalentMass` |
| Drivetrain efficiency | `Bike.efficiency` = 0.976 |
| Air density | `RhoProviderEstimate` (ISA + temperature) — *better* than the literature's altitude-only form |

Missing terms are R5 and R6, both measured below as not worth adding.

### R1–R4 — the four constant fixes ✅

Applied in `EngineConstants` with the reasoning inline in the KDoc: `DEFAULT_WHEEL_RADIUS_M = 0.35`,
`DEFAULT_MAX_BRAKE_G = 0.4`, `G = 9.80665`, and the lean-angle KDoc now states the µ ≡ tan θ
identity and the Zignoli dry/wet anchors. Nothing left to do; recorded so the ledger is complete.

### R5 — Spoke-rotation drag `F_w = 0.0044 m²` ❌

Martin's incremental drag *area* for rotating spokes, omitted by Dahmen and Danek both.

- **Magnitude**: 0.0044 / 0.35 = **1.3 %** of the default CdA — inside the uncertainty of the CdA
  input itself, and indistinguishable from choosing `frontalAreaM2 = 0.506`.
- **Verdict**: not worth a term. Anyone who wants it can fold it into `frontalAreaM2`.

### R6 — Yaw-dependent CdA ❌

Martin interpolates CdA across 0/5/10/15° yaw (0.269 / 0.265 / 0.265 / 0.255 m²).

- The source itself reports the differences are **not statistically significant**.
- The table **stops at 15°**; real crosswinds at low ground speed exceed that routinely, so the
  common case is unsupported extrapolation.
- The trend is for an **aero TT position with a disc rear wheel** and *contradicts* the deep-section
  "sail effect" literature — it does not generalise to a road bike on the hoods, which is what
  vcyclist's 0.7 × 0.5 default describes.
- **Verdict**: low value confirmed by the research itself. The higher-value variant is R14.

### R7 — Distance as the independent variable ✔

[`01 §1.1`](../research/01-physical-modeling.md) and [`04 §4.4`](../research/04-behavioral-modeling.md) both flag
Sundström & Bäckström's ODE transform (*"so that the numerical solver can stop at a predetermined
distance"*) as *"arguably the more natural integration domain than vcyclist's current
time-stepping"*.

**This is already what the code does.** `PowerComputer.getDt` (`PowerComputer.kt:78`) solves for the
`dt` that covers a *given* `dx`, and `VirtualizeService` drives it segment by segment from
`input.distance(i) − input.distance(i-1)`. The integration is already distance-indexed; the time
step is the unknown, not the driver.

**Correction to the research**: this is not an open item and should not be re-opened.

### R8 — Vertical-curvature rolling term ❌

`F_RR = C_RR · m · (g·cos α + v²/R_v)`, `R_v = [1+(y′)²]^{3/2}/y″` — the extra normal force in a
compression (Sundström & Bäckström).

- **Magnitude**: a fairly sharp dip, `R_v = 500 m` at 15 m/s, gives `v²/R_v = 0.45 m/s²` against
  `g = 9.80665` → **+4.6 % on the rolling term only**. Rolling is 10–20 % of demand at speed, so
  **< 1 % of total power**, transiently, at the bottom of a dip.
- **Cost**: needs `y″` — a *second* derivative of DEM-sourced elevation. Even after the 150 m
  smoothing kernel this is the numerically nastiest quantity in the pipeline, and it would have to
  be clamped, which means the term's magnitude would be set by the clamp rather than by the terrain.
- **Verdict**: cost is in the wrong place relative to a sub-1 % transient effect.

## B. Cornering, braking, descending

The layer with the highest realism-per-line ratio, and where the remaining §7.2 items live.

### R9 — Wet/dry µ condition preset ✅

[`05 §5.1`](../research/05-cornering-braking-descending.md): µ = **0.90 dry**, **0.36 wet**, i.e. wet grip is
40 % of dry → a **1.58× cut in cornering speed**. vcyclist's `maxLeanAngleDeg` *is* µ in disguise
(`Cyclist.tanMaxLeanAngle`), so the change is a parameter preset, not physics.

Speeds implied by `v_max = √(µ·g·R)` at the current defaults:

| R | µ = 0.70 (35°, today) | µ = 0.36 (19.8°, wet) | Zignoli's simulated riders |
|---|---|---|---|
| 15 m (flat hairpin) | 36.5 km/h | 26.2 km/h | ~29 dry / ~25 wet |
| 200 m (`MAX_RADIUS_M`) | 133.4 km/h | 95.7 km/h | — |

Two things that fall out of that table and should be part of any implementation:

1. **The `MAX_RADIUS_M = 200` clamp becomes a global speed cap in the wet.** Dry it yields
   133 km/h, well above `maxSpeedKmH = 100`, so cornering never binds on open road. Wet it yields
   **95.7 km/h**, *below* the max-speed cap — so a wet preset would clip straight-line descents too,
   via the clamp rather than via any corner. Defensible, but it should be a decision, not a surprise
   (`MaxSpeedComputer.kt:29`).
2. **Ship it as a condition preset, not a lone µ knob.** Wet lowers braking as well as cornering;
   moving µ while leaving `maxBrakeG = 0.4` untouched models a rider who can't corner but can still
   brake like it's dry.

Validation anchor available for free: [`05 §5.2`](../research/05-cornering-braking-descending.md) gives the
dry→wet time penalty over 40 km as **1.8–3.4 % with technical sections, 0–0.5 % without**. A route
with no tight corners that shifts by more than ~0.5 % indicates the clamp artefact above.

Also worth adopting from the same section: re-express the parameter as µ directly. Every source uses
that form, `tanMaxLeanAngle` is already the value being consumed, and it makes the wet/dry mapping
self-evident.

#### What landed

**Surfaces** : core ✅ · CLI ✅ (`--road-condition`) · JS ✅ (task 41) · WASI ✅ (task 43). The two
façades lagged from R9 shipping until those tasks.

`RoadCondition` (engine) with `DRY` / `WET`, `Cyclist.mu` / `withMu()` / `withRoadCondition()`, and
`--road-condition=dry|wet` on the CLI. `Cyclist.maxLeanAngleDeg` stays the stored parameter — µ is
exposed as the property it already was, not as a second source of truth, which keeps the CLI mixin,
the WASI DTO and the JS façade untouched.

**The wet numbers are derived, not chosen.** vcyclist's 35° default is 77.8 % of the dry physical
limit (`tan 35° / 0.90`), i.e. the *rider's* margin. `WET` holds that margin constant and changes
only the road: `0.778 × 0.36 = 0.280` (15.6°). Braking follows the same logic with one twist — the
dry ceiling is pitch-over (0.63 g, geometric, unaffected by rain) and 0.4 g is 63 % of it, while in
the wet the binding limit is grip, so `0.635 × 0.36 g = 0.23 g`. The resulting cornering ratio is
`√(0.70/0.28) = 1.581`, which *is* the research's 1.58× — both are ratios of the same two µ values,
so that agreement is arithmetic, not evidence.

`DRY` reproduces the shipped defaults **bit-for-bit** (`atan(tan(35°))` round-trips exactly), so the
preset cannot move an existing simulation.

**The 200 m clamp artefact is fixed, and had to be.** Saturating `MAX_RADIUS_M` now means "no
measurable curvature" and applies *no* cornering limit, instead of `√(µ·g·200)`. At the dry default
that expression gives 133 km/h — above `maxSpeedKmH`, so it never bound and nobody noticed. In the
wet it gives 84 km/h, which would have capped dead-straight road. No dry output moves.

Measured penalty (CLI, `--no-fix-elevation`, default rider):

| Route | dry | wet | penalty |
|---|---|---|---|
| `stelvio.gpx` (3.5 km, hairpins throughout) | 576 s | 617 s | **+7.1 %** |
| `strava.gpx` (20.8 km) | 2 882 s | 3 039 s | **+5.4 %** |
| `sample.gpx` (128.6 km) | 19 168 s | 19 763 s | **+3.1 %** |

Against the research's 1.8–3.4 % over 40 km *with* technical sections and 0–0.5 % without: the
128 km fixture lands inside the band, and the shorter ones exceed it in proportion to how
corner-dense they are (Zignoli's courses were ~25 % technical; `stelvio.gpx` is essentially all
corners). The ordering is the check that matters — penalty scales with technicality.

**Not modelled**, deliberately: rain does not touch `Crr`, air density or power. The research is
explicit that road conditions change performance time and peak power but *not* pacing strategy, so
this stays a limits-only knob. One consequence worth knowing: a wet ride is never *exactly* equal to
a dry one even on a dead-straight route, because every track ends and `MaxSpeedComputer` brakes to
its end-of-track sentinel with the weaker wet deceleration. That is a fixed ~1 s at the finish,
which is why the straight-route test asserts < 0.5 % rather than 0.

### R10 — Pedal-strike power cut-off ✅

Zignoli zeroes pedal power above a roll-angle threshold for pedal-ground clearance. vcyclist's rider
currently pedals at full power through a hairpin.

- **Cheap**: lean is derivable from state already on the path — `θ = atan(v² / (g·R))`, and `radius`
  is a `PointField`.
- **Caveat**: at any cornering-limited point the simulation rides *exactly* at `speedMax`, which is
  *exactly* `maxLeanAngleDeg` = 35° by construction. So a 20° threshold zeroes power in **every**
  grip-limited corner, not in some of them. That is defensible — nobody pedals at the limit of grip
  — and it produces the coast-in / accelerate-out signature of [`05 §5.4`](../research/05-cornering-braking-descending.md)
  (600–700 W exits). But it is a larger behavioural change than "add a threshold" sounds, and it
  will move time on technical routes.
- **Source disagrees with itself**: 5° in the methods text, 20° in the appendix. Treat 20° as the
  value, expose it, and say in the KDoc that the source is inconsistent.

#### What landed

**Surfaces** : core ✅ · CLI ✅ (`--bike-max-pedal-angle`) · JS ✅ · WASI ✅ — the one entry of the
series that relayed to every façade when it shipped.

`MuscularPowerProvider` — the single funnel for cyclist power into the balance, and the right place
for a *bike geometry* constraint — delivers nothing past `Bike.maxPedalingLeanAngleDeg` (default
20°, `90` disables). Lean is `atan(v²/(g·R))` from the path's own `speed` and `radius`, compared in
`tan` space so there is no `atan` per point. `--bike-max-pedal-angle` on the CLI, plus the JS and
WASI DTOs.

It **fails open** when `radius` is absent (zero, negative, non-finite — which is the state when
`MaxSpeedComputer` has not run): failing closed would zero a rider's power for an entire ride.

The provider is still called when the pedals are up, so `pCyclistProvidedOptimalPower` keeps showing
the rider's *intent* while `pCyclistProvidedMuscular` goes to zero — the difference between the two
slots is the cut-off. One accounting nit recorded in the KDoc: `PowerProviderDurability` accumulates
its dose from what it returned, not from what survives the cut-off, so it slightly over-counts work
in corners.

**The caveat above proved right, and the consequence was the opposite of alarming.** The cut-off
fires in every grip-limited corner (as predicted — at `speedMax` the lean *is* `maxLeanAngleDeg`),
which is 19–38 % of points on real fixtures, yet it costs only 0.25–0.35 % of time:

| Route | pedals up | time cost |
|---|---|---|
| `stelvio.gpx` | 38 % of points | +0.35 % |
| `strava.gpx` | 21 % | +0.31 % |
| `sample.gpx` (128 km) | 19 % | +0.25 % |

The reason is worth recording: it fires exactly where the rider is *already* limited by cornering or
braking, so the power it removes was being thrown away by the speed clip anyway — it was showing up
as `pBrake` (R12). The old model had the rider pedalling at 280 W into a corner it was simultaneously
braking for. So R10 is a fix to the **power trace**, not to the finish time, and it is the first of
these changes to move the default output at all — by well under the 0.5 % that used to be the parity
budget.

### R11 — Friction ellipse (combined braking + cornering) ✅

`MaxSpeedComputer` takes `min(cornering, braking)` — the two constraints are independent, so the
simulated rider may brake at 0.4 g while already at full lean. The physical constraint
([`05 §5.1`](../research/05-cornering-braking-descending.md), appendix) is
`(a_x/(µ_x·g))² + (a_y/(µ_y·g))² ≤ 1`.

**This is the mechanism behind the one measurable calibration gap in the layer**: at Zignoli's
R = 15 m flat hairpin, vcyclist gives **36.5 km/h** dry against his simulated **~29 km/h** — ~25 %
fast, *despite* the deliberate 78 %-of-dry-grip margin in the 35° default. Independent constraints
explain the direction of that gap better than the µ value does.

#### What landed

`MaxSpeedComputer.computeBrakingLimit` now spends one friction budget on both axes:
`a_x = a_xmax · √(1 − (a_y/a_ymax)²)` with `a_y = v²/R`, so a rider at full lean has no braking
left. `a_xmax` and `a_ymax` are the rider's own limits (`maxBrakeG`, `tan θ_lean`), so the ellipse
inherits the margin those already encode rather than stacking a second one. Saturated-radius points
count as straight (the R9 rule) and keep the full budget.

**The obvious implementation is wrong, and the tests caught it.** `a_y` depends on the speed being
solved for, so the constraint is implicit — but iterating `v ← √(v_next² + 2·a_x(v)·d)` does not
converge onto it: a higher `v` leaves *less* braking, so the map is **decreasing** and the iterates
oscillate around the answer, landing outside the ellipse half the time. `g(v) = v² − v_next² −
2·a_x(v)·d` is strictly increasing, so it is now plain bisection, which always lands on the safe
side. A per-point invariant test would have passed by luck with an even iteration count.

#### The measurement corrects this entry's own premise

| Route | before | with ellipse | cost |
|---|---|---|---|
| `stelvio.gpx` | 578 s | 579 s | +0.17 % |
| `strava.gpx` | 2 891 s | 2 892 s | +0.03 % |
| `sample.gpx` | 19 215 s | 19 220 s | +0.03 % |

Almost nothing — and the reason matters more than the number. This entry claimed the ellipse was
*"the mechanism behind the calibration gap"* against Zignoli's 29 km/h hairpin. **It is not.** The
ellipse only lengthens the braking zone *approaching* a corner; the apex is still
`v = √(µ·g·R)` = 36.5 km/h, untouched, because a rider who has finished braking spends the whole
budget laterally by definition.

So the 36.5-vs-29 comparison was between two different quantities: ours is an **upper bound** on
cornering speed, Zignoli's is an **optimised behaviour** under constraints we do not model (his
solver trades corner speed against the cost of re-accelerating, and carries roll-rate and
pedal-clearance limits). Nor can trajectory explain it in that direction — his riders *cut* the
turn, which is a larger radius and a higher speed, not a lower one. Closing that gap needs a
cornering-behaviour model, not more constraint algebra, and no source parameterises how far below
`√(µgR)` real riders actually corner (§5.7 lists exactly that as missing).

What R11 does buy is that the speed profile is now physically admissible everywhere — no point
brakes at 0.4 g while already at full lean — which is worth having whether or not it moves the
clock.

### R12 — Brake power as a `PointField` ✅ **not proposed by the research**

`VirtualizeService.kt:77` enforces `speedMax` by clipping: `speedNew = speedMax`, then
`dt = 2·dx/(v+v′)`. The kinetic energy removed is **silently discarded** — no provider records it,
no field carries it.

[`05 §5.4`](../research/05-cornering-braking-descending.md) says a corner-aware simulator should show
**−200 to −460 W of braking** alongside the 600–700 W re-accelerations. vcyclist currently produces
the second and not the first: its power trace has no braking in it at all.

**Surfaces** : core ✅ · CLI ✅ · JS ✅ · WASI ✅ (an output field — `fieldDefinitions()` carries it
with no façade work needed, which is why this one never drifted).

**Implemented** as `PointField.P_BRAKE` (#38), written by `PowerComputer.computeCyclistPower`.

It landed in a different place than planned, and the difference matters. Rather than measuring the
clip in `VirtualizeService`, it records `min(0, pComputedWheelPower)` — the exact quantity the
existing `max(0.0, powerWheel)` was throwing away. That is the same energy, but expressed as an
invariant of the inverse problem rather than a second bookkeeping path, so the two cannot drift.

**A capped speed is not automatically braking.** Holding 3 m/s on the flat costs ~20 W, so the
inverse problem attributes a capped flat segment to a rider *soft-pedalling*, and `pBrake` stays 0.
Only a deceleration the resistive forces cannot explain is recorded. That is the conservative
reading and it is the one that matches corner entries — but note it also means the simulated rider
never brakes *and* pedals at once, which a real one does.

Measured on the shipped fixtures (CLI defaults, 280 W, no elevation fix, post-simplification):

| Route | Braking points | Peak | Mean while braking |
|---|---|---|---|
| `stelvio.gpx` (3.5 km) | 26 % | −3 867 W | −1 115 W |
| `sample.gpx` (128 km) | 6.9 % | −5 371 W | −1 708 W |

Two checks on those numbers:

- **They sit at the configured limit, not above it.** Worst observed
  `|pBrake| / (m·0.4g·v)` is 0.86 (stelvio) and 1.18 (sample, the overshoot being the end-of-interval
  speed used in the ratio). Braking power is bounded by `m·a_max·v` by construction, and a test
  asserts it on a `MaxSpeedComputer`-derived course.
- **They are an order of magnitude above the research's figure, and the research is the one to
  distrust.** [`05 §5.4`](../research/05-cornering-braking-descending.md) quotes *"hard braking of −200 to
  −460 W"*. Reading Zignoli's Figure 2 caption directly: that is the tail of a **normalised
  probability distribution** of power, not a bound on braking power. The kinematics settle it —
  0.4 g at 15 m/s on an 80 kg system *is* 4.7 kW, and no bicycle sheds 40 km/h of speed at 460 W.
  Chapter 05 should not be quoted as a magnitude target.

### R13 — Brake actuation lag ❌

[`05 §5.5`](../research/05-cornering-braking-descending.md) measures 124–129 ms and proposes
`v² − v_c² ≥ 2a(d − v·t_lag)`.

- **Magnitude**: 0.13 s at 40 km/h (11.1 m/s) moves the braking point **1.44 m** — less than one
  sample of the 2 m-resampled path the physics runs on (`PointPerDistance(1, 2)`).
- **Verdict**: below the pipeline's own spatial resolution. Not measurable in the output.

### R14 — Posture-dependent CdA ⚪

Hoods vs drops vs sitting-up is a far larger CdA swing than yaw (R6), and it interacts with
`MaxSpeedComputer` — a rider braking into a corner is not in a tuck. `AeroProvider` is already a
`fun interface` taking `(course, path, pointIndex)`, so the extension point exists at zero
structural cost (`AeroProvider.kt:13`).

Deferred on evidence, not on value: [`07 §7.2(e)`](../research/07-vcyclist-implementation-notes.md) states
plainly that **no verified source covers this** — it would be a project-owned model with no
literature to calibrate against, and its parameters would be invented. Worth doing eventually,
worth labelling as ours when it lands.

### R23 — Curvature by heading regression in a local planar frame ✅

Not from the research chapters. It came out of the racing-line design work
([`docs/archive/plans/racing-line-design.md`](../archive/plans/racing-line-design.md) §3.1–3.3, §8.2), whose feasibility study
argued that the *estimator* — not the trajectory — was where the measurable value sat, and that
the estimator could ship on its own. It did, as `:engine`'s `trajectory` package writing a
`trajectoryCurvature` field that `MaxSpeedComputer` reads in preference to its own estimate.

#### Three defects, all real, all verified in the code before the change

1. **The ±π wrap.** `normalizeAngleDiff` folded the bearing difference into `(-π, π]`, but the
   ±10-point window spans ~30 m at the 1–2 m spacing the pipeline resamples to, so any bend under
   ~9.5 m radius turned further than π across it. It wrapped to a *smaller* angle, hence a
   *larger* radius, hence a `√2` overspeed — at the tightest points on the route.
2. **`Δs` coupling.** The window was a fixed *point* count, so every radius silently depended on
   the resampler's spacing rather than on the road.
3. **`computeBearing` shear.** `x = lon·cos(lat)` with *absolute* longitude gives
   `∂x/∂lat = −lon·sin(lat)` — 4.2° of shear on a due-north segment at 6°E/45°N, growing linearly
   with longitude.

Because `radius` feeds the cornering limit, the R11 friction ellipse, `pBrake` and R10's
pedal-strike cut-off, one wrong radius is wrong in four places.

#### Measurement

Old versus new through the full pipeline, `fixElevation` off, defaults otherwise — reproduce with
`MEASURE=1 ./gradlew :engine:jvmTest --tests '*CurvatureMeasurementTest*' --rerun-tasks -i`:

| fixture | dist | duration old → new | Δ | median radius old → new | p1 radius old → new |
|---|---|---|---|---|---|
| `stelvio` | 3.5 km | 579 s → 594 s | **+2.59 %** | 153.8 → 196.9 m | 10.6 → 5.0 m |
| `strava` | 20.8 km | 2 892 s → 2 899 s | +0.24 % | 167.7 → 187.5 m | 13.1 → 16.2 m |
| `sample` | 128.6 km | 19 220 s → 19 508 s | +1.50 % | 200.0 → 200.0 m | 14.1 → 10.0 m |
| `garmin` | 3.8 km | 391 s → 426 s | **+8.95 %** | 121.7 → 142.3 m | 16.1 → 7.2 m |
| `movescount` | 12.1 km | 2 006 s → 2 013 s | +0.35 % | 200.0 → 200.0 m | 12.1 → 10.9 m |

The two columns move in *opposite* directions, and that is the whole result. The **median** radius
rises everywhere: straight and gently-curved road, which is most of any ride, now reads straighter
because the shear and the jitter are gone. The **1st percentile** falls on the curvy fixtures: the
tight bends the wrap was flattening are finally resolved. Between 1.9 % and 5.1 % of points now
report under half their previous radius.

Rides therefore get **slower**, by 0.24 % to 8.95 %. That is well outside the project's 0.5 %
aggregate-parity budget, so it shipped as a `fix` with baselines re-recorded rather than as a
silent improvement. The `garmin` outlier is instructive: its p1 radius drops 16.1 → 7.2 m, and
because `MaxSpeedComputer` propagates a low apex speed *backwards* through the braking envelope, a
handful of newly-resolved corners slows a long approach — its envelope-binding time goes from
2.1 % to 25.8 %.

#### What it does not fix

Nothing about apex *behaviour*. Like R11, this is constraint algebra: it makes the speed ceiling
correct, and says nothing about how far below the ceiling a real rider actually corners — still
the gap §5.7 names and no source parameterises.

The residual bias is at the tight end, and it is honest rather than hidden: a 90° bend of radius
`R` is only `πR/2` of arc, so the smoothing kernels take a real bite out of anything under ~10 m.
Measured error is ~15 % high at `R = 5 m`, ~11 % at 6 m, under 2 % from 15 m up — high meaning
*optimistic*. Narrowing the kernels further starts reporting noise as corners, which is the worse
failure; `MIN_RADIUS_M = 5 m` clamps consumers regardless.

Under noise the estimator degrades toward "no corner" rather than "invent one": 1.5 m of white
lateral jitter on a straight yields a worst-case radius of ~265 m, beyond the 200 m at which the
cornering limit stops being applied at all. Getting there took two corrections that are worth
recording, because both looked right and were measurably wrong — see the comments in
`CurvatureEstimator.computeCurvature` and `LocalFrame.project`: heading must be regressed against
the *smoothed* curve's own arclength, not the raw path's; and the scale-selection allowance must be
measured from the trace at the *widest* window, since a fixed allowance rejects wide windows first
and a narrow-window measurement mistakes jitter for signal.

### R24 — Racing line (optimal trajectory through corners) ✅, opt-in

The trajectory half of [`docs/archive/plans/racing-line-design.md`](../archive/plans/racing-line-design.md), shipped behind
`racingLine.enabled = false`. A lateral offset `n(s)` is solved as a box-constrained convex QP —
curvature objective, steering penalty, centring prior — by projected Newton over a pentadiagonal
`LDLᵀ`, then the path is rebuilt on the resulting line. Tasks t04, t05, t07.

#### Measurement

Full pipeline, `FULL_ROAD`, no simplification, `fixElevation` off. Reproduce with
`MEASURE=1 ./gradlew :engine:jvmTest --tests '*RacingLineMeasurementTest*' --rerun-tasks -i`:

| fixture | duration | distance | p1 radius | p10 radius | stations opened / tightened |
|---|---|---|---|---|---|
| `stelvio` | +0.07 % | 3 574 → 3 650 m | 5.0 → 6.5 m | 23.0 → 26.2 m | 528 / 407 |
| `strava` | **−0.54 %** | 21 118 → 21 148 m | 16.2 → 17.2 m | 58 → 69 m | 3 977 / 2 144 |
| `sample` | **−0.81 %** | 130 393 → 131 086 m | 10.0 → 13.2 m | 65 → 67 m | 13 577 / 9 400 |

Half a percent, on the curviest fixtures, in `FULL_ROAD` — the mode that is illegal on an open
road. `LANE`, the legal default, gives less. This is the same order the feasibility study predicted
from first principles (0.05–0.4 %) and for the same structural reason R11 recorded: **constraint
algebra only moves the clock where the constraint binds**, and a rider spends 1–5 % of a ride at
the cornering limit.

`stelvio` going very slightly *slower* is not a defect. It is a 3.5 km climb of hairpins where
speed is power-limited rather than corner-limited, and the racing line is 2 % **longer** — the
objective penalises curvature, not length, so it buys corner speed with distance. On a climb that
trade is a loss. Worth knowing before anyone enables this for a hill climb.

#### The trap that made it look catastrophic

The first integrated measurement showed rides **16–27 % slower**, with the median corner radius on
`strava` collapsing from 187 m to 85 m and the 10th percentile hitting the 5 m clamp. The line was
apparently turning every corner into a hairpin.

The cause was not the solver, which was solving correctly, but how its output was *measured*. The
exact offset-curvature formula reads `n''` off a finite second difference at 1–2 m spacing, where a
0.1 m wiggle between adjacent stations already looks like a 23 m bend — and a box-constrained
solution is only C¹ where it meets a bound, so it has exactly such wiggles. Every corridor-bound
kink became a spurious hairpin, and `MaxSpeedComputer` dutifully braked for all of them.

The fix is to **re-run the curvature estimator on the materialised path** instead of trusting the
analytic form. That reports the curvature of the geometry actually written, and — just as
importantly — measures the racing line and the centreline the same way, so the two can be compared
at all. It is worth stating plainly because the analytic formula is *correct*: what failed was
applying an exact continuous identity to a finite difference of a nearly-C¹ function.

#### Why it stays off by default

It rewrites every coordinate of the output. That is not a refinement an existing caller asked for,
and it is visible to anything that map-matches or detects segments — hence `sourceLatitude` /
`sourceLongitude`, which keep the edit reversible rather than merely documented.

The corridor is also still a fiction on any route without width data: the gain is **linear** in the
assumed road width, so `defaultRoadWidthM = 6.0` being wrong by 2× makes the whole result wrong by
2×, in an unknown direction. Design §12 question 1 remains the honest caveat, and `--road-width` is
exposed precisely so a user who knows better can say so.

**Surfaces** : core ✅ · CLI ✅ (`--racing-line`, `--racing-line-report`, `--corridor`) · JS ✅ · WASI
✅ · JVM/Java ✅ · démo ✅ — mais **trois** des vingt-trois champs de `RacingLineOptions` seulement.
Les vingt autres, dont la `CurvatureOptions` **imbriquée** (que le `curvatureEnabled` des portes ne
vise pas : il vise `EnhanceOptions.curvature`) et `simplifyToleranceCapM`, restent Kotlin, et c'est
délibéré — réglés par la mesure, arité épinglée à 3 par `EngineModelJvmCoverageTest.kt:77` pour que
l'élargir soit une décision. Voir `EngineModelJvm.kt:97-104`. Le **rapport**, lui, est partiel en
aval : le CLI n'a qu'une table texte sans variante JSON, et la démo lit 3 de ses 13 champs —
`converged` n'est jamais lu, donc une résolution non convergée est tracée sans avertissement
(`useMap.ts:289`).

### R25 — Time weighting of the racing-line objective ❌

`docs/archive/plans/racing-line-design.md` §3.7: reweight the trajectory objective toward `∫√κ ds` by IRLS, mask
it with the rider-derived saturation radius `R_sat = v_max²/(µg)`, and bias the apex late on uphill
exits. Implemented in full, measured, and reverted. Task
[`t06`](../archive/tasks/t06-time-weighting.md) keeps the detail.

Duration against the plain centreline, `FULL_ROAD`, lower is better:

| variant | `stelvio` | `strava` | `sample` |
|---|---|---|---|
| **R24 as shipped** (fixed 200 m mask, no reweighting) | **+0.07 %** | **−0.54 %** | **−0.81 %** |
| `R_sat` mask (112 m), no reweighting | +0.27 % | +0.08 % | −0.51 % |
| `R_sat` mask + 2 IRLS rounds | +0.36 % | +0.29 % | −0.67 % |
| … + grade coupling 0.15 | +0.36 % | +0.29 % | −0.67 % |

The premise is sound and the implementation is not the problem: cornering time really is
`√(1/µg)·∫√κ ds`, so `∫κ² ds` really is the wrong objective, and IRLS really does target the right
one. What fails is what the reweighting does to the *balance* of the energy. Normalising `ρ` to a
masked mean of 1 leaves `ρ < 1` at the tight apexes that matter and near the ceiling on the gentle
stretches beside them, so effort moves away from the corners worth optimising. The line weaves
more — `strava` grows to 21 408 m against 21 148 m — and the extra distance costs more than the
corner speed returns.

Two details worth keeping:

- **Weighting on the solved line's curvature, as the design specifies, is actively unstable.** It
  drove a 15 m hairpin to a 3.3 m line, because the analytic offset curvature spikes at every
  corridor-bound kink (see R24) and the weights then collapse at the spikes and saturate beside
  them. Weighting on the reference curvature avoids that, and still loses.
- **Grade coupling changed nothing to three significant figures** on any fixture. It was a
  hand-tuned scalar standing in for a variational result, and it turns out not to express anything
  the geometry responds to.

This is the third entry now — after R11 and R24 — where constraint algebra was predicted to move
the clock and measurement said otherwise. The pattern is consistent enough to be worth stating as a
prior: **on this engine, refinements to the speed *envelope* buy fractions of a percent, because a
rider spends 1–5 % of a ride against it.** Design §12 question 4 names what would actually be
needed — a construct–simulate–reconstruct loop with two `VirtualizeService` runs per iteration —
and that is a different design, not a coefficient.

### R26 — Road width from the OSM `highway` class ✅, and worth almost nothing

Design §12 question 1 calls the corridor "a fiction" without width data, and the feasibility study
called OSM ingestion the true blocking dependency for the whole racing line. Task
[`t14`](../archive/tasks/t14-osm-highway-width.md) built it. It is not a dependency, because the data is
not there.

Two real router exports (gpx.studio) carry `highway` and `surface` on every track point and
**neither carries `width` nor `lanes`**. The best available signal is therefore a road-class proxy,
and on any one route it is close to constant: a 128 km sample is 69 % `secondary`, 23 % `tertiary`,
6 % `primary`, 1 % `cycleway`.

| run (128 km, `FULL_ROAD`) | duration | vs plain |
|---|---|---|
| plain, no racing line | 19 396 s | — |
| racing line, inferred widths | 19 326 s | −0.36 % |
| racing line, uniform 6.0 m | 19 327 s | −0.36 % |

Inference against a flat default is worth **one second in five and a half hours**. `secondary`'s
class-typical width is 6.0 m, which is already the default, so for two-thirds of the route the
inference agrees with the guess it replaces.

It ships anyway — it is cheap, it is correct, and it varies where the default cannot, which will
matter on a route that genuinely changes character. But it does not close §12 question 1, and
nothing available in GPX does. **The corridor is still a global assumption**, and `--road-width`
is still the honest way to correct it.

#### The finding that was worth more

The same run showed `LANE` beating `FULL_ROAD`: **19 312 s against 19 326 s**. The legal corridor,
which keeps to the rider's own side and never crosses the centreline, is *faster* than the one that
uses the whole carriageway — because full-road weaving costs more distance than the extra corner
speed returns. Same mechanism as R25. The mode that is safe to default is also the one to prefer,
which is a happier alignment than this feature had any right to expect.

#### What `surface` would be worth

`surface` is parsed past and deliberately not ingested: nothing reads per-point grip, `µ` is a
scalar on `Cyclist`, and making it per-point touches `MaxSpeedComputer`, the friction ellipse and
the pedal-strike cut-off. It is nonetheless the more promising of the two tags, because grip enters
`v_max = √(µgR)` directly and so moves the envelope *everywhere* rather than refining it where the
envelope binds — the distinction R11, R24 and R25 all turn on. The caveat is that the available
data is as thin here as for width: 4965 of 4966 tagged points on the 128 km sample are `asphalt`.

**Surfaces** : core ✅ · CLI ✅ (`--road-width`, qui pose `racingLineRoadWidthM`) · JS ✅ · WASI ✅ ·
JVM/Java ✅ · démo ✅. Même réserve que R24 : c'est l'un des trois champs de `RacingLineOptions` qui
franchissent une porte.

## C. Physiological

### R15 — W′bal as an output field ✅

[`02 §2.1`](../research/02-physiological-modeling.md) + [`06 §6.1`](../research/06-implementations-and-validation.md).

**Split from ch. 07's ranking.** Ch. 07 ranks "W′bal" #1 as a single item; it is two items with very
different risk, and only the first is low-risk:

| | R15 — metric | R16 — behaviour |
|---|---|---|
| Reads | `pComputedPower` after step 7 | drives `optimalPower` inside the sim |
| Touches | one new field, one post-pass | `VirtualizeService`, `Cyclist`, every provider |
| Other fields | **untouched** — trajectory unchanged | every value shifts |
| New required input | none (defaults CP 250 W / W′ 20 kJ) | CP and W′ become load-bearing |

**Surfaces** : core ✅ · CLI ✅ · JS ✅ (task 41) · WASI ✅ (task 43). Note the field
was *already being written* on JS all along — `WPrimeBalanceOptions` defaults to enabled and the
façade never overrode it — at CP 250 / W′ 20 kJ. Task 41 made it calibrable, not enabled.

**Implemented** as `WPrimeBalanceComputer` (`engine/…/physiology/`), pipeline step 8, behind
`EnhanceOptions.wPrimeBalance`. Field `wPrimeBalance` is `PointField` #37. What landed, against
what was planned:

| Planned | Landed |
|---|---|
| Differential (ODE) form | Closed-form exponential recovery, not GoldenCheetah's Euler recursion — so it is correct off a 1 Hz grid |
| Defaults CP 250 / W′ 20 kJ | `EngineConstants.DEFAULT_CRITICAL_POWER_W` / `DEFAULT_W_PRIME_J` |
| Other fields untouched | Confirmed — a test asserts every other field is bit-identical with the pass on and off |
| No new required input | CP/W′ live on `WPrimeBalanceOptions`, not `Cyclist` — see the KDoc for why, and R16 for when they should move |

τ is not yet exposed as configuration (Bartram's elite form) — still open.

⚠ **Corrected by R16.** The pass integrated `path.dt(i) / 1000.0`, but a finished path carries `dt`
in *seconds*, so the balance was under-integrated by 1000×. The measurement once quoted here — a
5.3 h ride at 280 W against CP 250 spending 568 J — was that bug, not a finding. With the fix the
same ride empties the 20 kJ reserve and holds it at zero, which is the honest verdict on riding
30 W above CP for five hours.

R15 was essentially free: `PointPerSecond` already delivers the 1 Hz grid the recursion assumes.

- Use the **differential (ODE)** form. Skiba himself calls the integral form *"theoretically
  untenable"* for continuous severe-intensity work, and the two diverge by ~300 s in predicted time
  to exhaustion — this is a real fork, not a rounding choice.
- GoldenCheetah's shipped recursion is directly portable:
  `if (P < CP) W += (CP − P)·(W′ − W)/W′ else W += (CP − P)`.
- **Implementation trap**: that recursion is Euler at an *implicit* `dt = 1 s`. It is only correct
  where the path is 1 Hz — scale by `dt` if the field is ever computed before `PointPerSecond`, or
  document the stage ordering as a precondition.
- Expose τ so Bartram's elite form (`τ = 2287.2·D_CP^−0.688`) can be swapped in.
- Label the output as an approximation of a mechanism the field is actively replacing: [`02`](../research/02-physiological-modeling.md)
  reports hydraulic three-component models **outperforming** W′bal on intermittent recovery.

### R16 — CP-aware `CyclistPowerProvider` ✅

The step that makes the simulated rider *behave* like a rider: back off when W′ is low. High value,
and correctly identified by ch. 07.

#### What landed

**Surfaces** : core ✅ · CLI ✅ (`--cyclist-model=critical-power`) · JS ✅ (task 41) · WASI ✅
(task 43).

`PowerProviderCriticalPower(powerW, criticalPowerW, wPrimeJ, taperStartFraction)`. It carries a
running W′ balance — via literally the same `WPrimeBalanceComputer.step` the post-pipeline field
uses, so the rider's bookkeeping and the reported field cannot drift — and rations its target:
full power until half the reserve is gone, then linearly back toward CP.

**The state is the literature's, the taper is ours**, and the KDoc says so. CP and W′ are
descriptive; no source maps a reserve level to a power target.

One property worth recording because it was not designed: **the approach to CP is asymptotic.**
Below the taper point the depletion rate is itself proportional to what is left, so the reserve
decays exponentially (time constant `taperStartFraction × W′ / (target − CP)` = 100 s at the
defaults) and power converges on CP without ever quite arriving or dipping below it. A test pins
the exponential.

Scope kept deliberately narrow: **no gradient or wind modulation, no lookahead.** Those are pacing
decisions rather than fatigue state, they are listed under R19, and §4.5 is emphatic that
optimal-pacing models are prescriptive rather than descriptive.

Measured against `constant` (280 W, CP 250 W):

| Route | `constant` | `critical-power` | cost |
|---|---|---|---|
| `stelvio.gpx` (3.5 km, 10 min) | 578 s | 579 s | +0.2 % |
| `strava.gpx` (20.8 km) | 2 891 s | 3 060 s | +5.8 % |
| `sample.gpx` (128.6 km, 5.3 h) | 19 215 s | 20 810 s | **+8.3 %** |

Short rides barely move, because the reserve is never exhausted; long ones move a lot, because
`constant` was letting the rider hold 280 W against a 250 W CP for five hours. This is the largest
behavioural change of the series, and it is opt-in.

The CLI's three power models are now one `--cyclist-model=constant|durability|critical-power`
selector; R17's `--cyclist-durability` flag was folded into it before either shipped.

#### And it caught a bug in R15

Comparing the provider's internal reserve against the `wPrimeBalance` field showed them disagreeing
by three orders of magnitude. The field was right in shape and wrong in scale:
`WPrimeBalanceComputer.compute` read `path.dt(i) / 1000.0`, but a *finished* path carries `dt` in
**seconds** (see the cross-cutting note below), so every interval was 1000× too short. That is why
the R15 entry originally reported a five-hour ride 30 W above CP as having spent **568 J of a
20 kJ reserve** — it should empty the reserve in about eleven minutes, which is what it now does.
Fixed by reading `time`, which means milliseconds everywhere, and pinned by an end-to-end test.

A second, smaller finding from the same comparison: the W′ pass runs *before* `PathSimplifier`, and
the simplifier keeps points on 3D geometry with no knowledge of this field — so a simplified path
carries a **sampled** W′bal trace and a deep trough between two kept points can vanish. Recorded in
the KDoc.

### R17 — Durability: decay on supra-CP work, not elapsed time ✅

**Under-weighted by ch. 07**, which folds durability into the fuelling item (R21) and inherits its
weak evidence grade. It deserves its own row, because it is a fix to code that already exists.

`PowerProviderConstantWithTiring` decayed power as
`c = max(0.5, 1 − 0.6·elapsed/durationSeconds)` — open-loop on **elapsed time**, which is precisely
the formulation [`02 §2.2`](../research/02-physiological-modeling.md) says is wrong. The systematic review is
explicit: durability is **intensity-weighted, not kJ-weighted, and certainly not time-weighted** —
10–20 % power decline after only **2.5–15 kJ/kg of work above CP**, versus < 5 % for comparable or
larger sub-CP volumes.

- **Cost**: re-base one expression in one class on accumulated supra-CP work. No new stage.
- **Evidence**: strongest-graded finding in ch. 2, and it *replaces* a formula that currently has no
  source at all.
- **Qualification to carry into the KDoc**: decrements are strongly duration-dependent — largest at
  the shortest efforts, with **no effect measured on a 12-min TT**. A model that degrades sustained
  power as hard as sprint power overfits the headline. Population is male professional cyclists.
- **Do not implement** the specific figures "CP −0.06 vs −0.007 W/kg; W′ −3.02 kJ after 2000 kJ" —
  refuted 0–3.

#### What landed

**Surfaces** : core ✅ · CLI ✅ (`--cyclist-model=durability`) · JS ✅ · WASI ✅. The JS rename is what
broke the demo for nine entries — see task 40.

`PowerProviderDurability`, plus `--cyclist-durability` / `--cyclist-cp` on the CLI — and
**`PowerProviderConstantWithTiring` is removed**, not deprecated. It shipped first as a new provider
alongside the old one (rewriting a public class in place would have silently changed every existing
caller's output); the API owner's call was to delete the old one outright, so the elapsed-time decay
is gone from the codebase rather than left as a documented trap.

That makes this a **breaking change** across three surfaces: the Kotlin class, the JS façade's
`power.type = "constant_tiring"` (now `"durability"`, with `tiringDuration` → `criticalPower`), and
the WASI JSON ABI (same rename, `docs/guides/wasm-wasi-abi.md` updated). The frozen WIT spike under
`tools/wasi-component/` still describes `constant-tiring`; it is a replay artifact of the w13
experiment, not built by Gradle, and was left alone deliberately.

Two judgement calls worth pinning:

- **The fade rate is conservative on purpose.** The default reaches 10 % at 15 kJ/kg — the *bottom*
  of the published band at the *top* of its work range. The band is dominated by short maximal
  efforts (−53.8 % at 5 s), while Spragg found **no effect on a 12-minute TT**. A simulated rider
  holding a sustained target is the 12-minute case. Applying the headline to sustained power would
  be exactly the overfitting §2.2 warns about, so the rate is a parameter, not a constant.
- **It is the only stateful provider.** The supra-CP dose is a path integral; recomputing it per
  point would be O(n²). The accumulator is keyed on `pointIndex` — re-reading a point counts once,
  a backwards index resets — and it is single-simulation, non-concurrent by contract.

Measured (CLI, `--no-fix-elevation`, 280 W against CP 250):

| Route | constant | durability | cost |
|---|---|---|---|
| `strava.gpx` (20.8 km, 48 min) | 2 882 s | 2 887 s | +0.17 % |
| `sample.gpx` (128.6 km, 5.3 h) | 19 168 s | 19 466 s | **+1.6 %** |

The ordering is the result that matters: five hours above CP accumulates ~7 kJ/kg and ~5 % of fade
by the finish, 48 minutes accumulates almost nothing. Under the old elapsed-time model both rides
would have faded by the same fraction of their own duration.

### R21 — Fuelling / glycogen ❌ (for now)

Ranked #4 by ch. 07. Recommended **against** for the current cycle:

- [`02b`](../research/02b-fuelling-and-thermal.md) is the report's only chapter graded **⚠ extracted, not
  verified** — the 3-vote refutation never ran on it.
- It depends on a **gross mechanical efficiency** figure the research explicitly **did not obtain**
  ([`README` known gaps](../research/README.md#known-gaps-in-this-report)); the kJ → fuel bridge would be
  sourced outside the report.
- The chapter concedes its own power decrement is **phenomenological, not mechanistic** (*"implement
  the power decrement directly as a function of the glycogen state variable, and label it as a fit,
  not a mechanism"*) — i.e. it would rebuild `PowerProviderConstantWithTiring` with more parameters
  and no more validation. **R17 gets most of that value with one expression and better evidence.**
- It only bites past ~3 h.

Revisit when 2b gets its dedicated verification pass — that pass is already recorded as owed in
[`README`](../research/README.md) (GAP C produced zero surviving claims twice).

## D. Behavioural / tactical

**Framing constraint for this whole section.** [`04 §4.2`](../research/04-behavioral-modeling.md): optimal pacing
is worth **1–3 %** on realistic courses, and a real professional rides within **1.2 %** of the
optimum. So R18/R19 must be justified as *realism of the power trace*, never as accuracy of
predicted time. If a pacing heuristic moves finish time by more than ~0.5 %, that is a **bug
signal, not a feature**.

### R18 — Power slew-rate limit ✅

Zignoli & Biral use **50 W/s**. Nothing stopped `CyclistPowerProviderBase` from stepping power
discontinuously, and [`04 §4.3`](../research/04-behavioral-modeling.md) names a discontinuous step at each
gradient change as *the* specific artefact to avoid. Cheap, and it is the part of R19 that needs no
anticipation machinery.

#### What landed

**Surfaces** : core ✅ · CLI ✅ (`--cyclist-slew`) · JS ✅ (task 41) · WASI ✅ (task 43).

`PowerProviderSlewLimited(delegate, maxSlewWPerS = 50.0)` — a **decorator**, not a change to
`CyclistPowerProviderBase`. Two reasons: the base class is shared by `object` singletons
(`PowerProviderFromData`), so putting mutable state there would make it global; and a decorator
composes, `PowerProviderSlewLimited(PowerProviderDurability(…))`. `--cyclist-slew` on the CLI, off
by default.

**The 50 W/s figure was checked against the source, not the summary.** Zignoli's appendix lists
*"vWnmax = 50 W/s, maximal power output variation"* as a hard constraint, with the rate of change of
power *also* penalised in the cost function — so quoting it as a slew bound is right. It remains a
**modelling bound, not a physiological measurement**: nobody has measured how fast a rider can
change power.

Measured cost is small — `stelvio.gpx` 578 → 581 s, `sample.gpx` 19 215 → 19 218 s — and that is
expected: with a *constant* power target there is nothing to smooth except the start of the ride and
the corner exits. Worst observed |ΔP/Δt| between pedalling points on `sample.gpx` drops from 62 to
48 W/s. It becomes load-bearing only when a provider reacts to terrain, which is R19.

**One emergent property worth keeping.** The pedal-strike cut-off (R10) is applied downstream in
`MuscularPowerProvider` and is deliberately *not* rate-limited, so power drops instantly at the lean
threshold and ramps back at 50 W/s on the way out. That is the "drop quickly and locally, rise
gradually" asymmetry [`04 §4.3`](../research/04-behavioral-modeling.md) describes — reproduced without modelling
it, from two independent constraints.

### R19 — Pacing heuristic ✅ (the reactive half)

The qualitative rules from [`04 §4.3`](../research/04-behavioral-modeling.md), worth capturing eventually:

- Harder uphill / into headwind, easier downhill / with tailwind.
- **Asymmetric in time**: ramp up gradually over several hundred metres into a climb; drop off
  quickly and locally on a descent.
- Anticipatory on rolling terrain: spend W′ *before* a descent, recover ~8 % through it, spend that
  on the next climb.
- Prioritise **climb-plus-descent** sequences — 2.84 % versus 1.41 % for a pure climb and 0.45 %
  flat. Flat routes barely repay the effort.

#### What landed

**Surfaces** : core ✅ · CLI ✅ (`--cyclist-pacing`) · JS ✅ (task 41) · WASI ✅ (task 43).

`PowerProviderTerrainPacing(delegate, …)` — a decorator, composing as
`PowerProviderSlewLimited(PowerProviderTerrainPacing(PowerProviderCriticalPower(…)))`, and
`--cyclist-pacing` on the CLI. It implements `raw = 1 + gradientGain·grade +
headwindGainPerMS·headwind`, clamped, with **rises dispersed over 300 m and falls applied at once**.

Two of the four bullets above shipped; two did not, on purpose:

- **Anticipation is not implemented.** Reading Bach's "dispersed over several hundred metres" as
  *anticipatory* is an interpretive step beyond the quote, the 2 %/8 % figures behind it are
  single-source and single-subject, and lookahead is the first step toward the prescriptive
  optimiser R22 forbids. The rider reacts to the road it is on.
- **W′ scheduling is not implemented** either — choosing which climb to empty the reserve on is a
  plan, not a state. The reserve stays R16's.

Only the asymmetry is sourced; every magnitude is ours and labelled as such in the KDoc.

#### The finding: a pacing rule without an energy account is just riding harder

First measurement, before any budget: **10 % faster on 11 % more average power**. Climbs are slow,
so a boosted multiplier applies for far more *time* than the descent discount does, and the whole
"gain" was extra work. Any pacing heuristic that does not conserve energy will show this, and
reporting the time alone would have been a fabricated result.

So the rule now carries a **causal energy account**: joules spent above the delegate's target are
remembered and the multiplier is pulled back in proportion, over a tolerance of ten minutes of
riding. Nothing looks ahead — the rider simply notices it has been overspending. A test asserts the
account closes within 5 % of total work; without it the assertion fails by a factor of two.

Re-measured with the account:

| Route | time | mean power | reading |
|---|---|---|---|
| `strava.gpx` (20.8 km, rolling) | −3.0 % | −2.0 % | a real gain, at lower power |
| `sample.gpx` (128.6 km) | −2.7 % | +1.8 % | ~2 % genuine once the extra watts are discounted |
| `stelvio.gpx` (3.5 km, all climb) | −7.8 % | +3.0 % | **not a valid comparison** |

The first two land inside the **1–3 %** the literature reports for the *entire* pacing optimum,
which is the most satisfying corroboration in this series — a rule with no optimiser in it, on real
GPX, landing where the optimal-control papers say the whole prize is.

`stelvio.gpx` is honest to report and dishonest to claim: a nine-minute pure climb gives the rule
nothing to redistribute *to*, so it raises power almost everywhere and the account never gets a
descent to settle on. That row says "rode harder", not "paced better" — and it is the same shape of
error as the pre-account measurement, surviving at fixture scale.

### R22 — Optimal-pacing solver as rider behaviour ❌

[`07 §7.4`](../research/07-vcyclist-implementation-notes.md) is correct and should stay load-bearing:

- The best optimal-control ITT model matches real professional velocity for **18–32 % of course
  duration** (and 32 → 18 % between tuning and test set — an overfitting signal).
- **Every** published time saving is model-internal; the one real-world trial returned 3 % and was
  **invalidated by a programming error**.
- The full DP takes **53 min for an 18 km route** — a non-starter as a pipeline stage.
- These models are **prescriptive**. vcyclist's job is what a ride *would look like*.

Legitimate as a separate, clearly-labelled feature ("here is how you could have ridden this"). Never
as the default simulated rider.

## E. Mental

### R20 — RPE + Hazard Score ⚪

Two constructs survived verification: `RPE(x) ≈ RPE₀ + (RPE_max − RPE₀)·x` against **fraction of
route completed** (never elapsed time — that form was refuted 0–3), and
`HS = RPE × fraction_remaining` with bands < 1.5 / 1–3 / > 3.

Cheap, and it is the layer that makes output legible to a human. Deferred only on ordering: RPE has
to be scaled by intensity **relative to CP**, so it inherits R15's state anyway. Doing it first would
mean inventing an intensity reference twice.

Three traps recorded now so they are not rediscovered: the published bands **overlap** between 1 and
1.5 and the paper does not resolve it; RPE must be **Borg CR10**, not 6–20 (wrong by ~2×); and the
HS → power mapping is **ours, not literature-derived** — the score is published, the control law is
not. If it ever drives power it also closes a loop with R16, which needs thinking about before, not
after.

Do not implement the three refuted constructs: summated HS ↔ session RPE (0–3), RPE rate-of-rise
predicting TTE (1–2), RPE linear in elapsed time (0–3).

## F. Elevation and cumulative ascent

Provenance: Strava's [Elevation](https://support.strava.com/en-us/articles/15401909-elevation),
[Elevation FAQs](https://support.strava.com/en-us/articles/15402093-elevation-on-strava-faqs) and
[Elevation Basemap](https://support.strava.com/hc/en-us/articles/115000024864-Strava-s-Elevation-Basemap)
articles; GoldenCheetah's shipped hysteresis default; Kollár, *[Evaluating cumulative ascent:
mountain biking meets Mandelbrot](https://arxiv.org/abs/1011.4778)*. Measurements and the full
argument live in [`../guides/elevation.md`](../guides/elevation.md); reproduce with
`python3 tools/elevation/dplus_scale.py demo/public/gpx/*.gpx`.

**The finding that orders this whole section**: the dead band and the smoothing kernel attack the
same noise, and the kernel wins. On `strava.gpx` the band alone takes D+ from 1066 m to 661 m, the
150 m kernel alone takes it to 632 m, and applying both changes nothing further — after smoothing,
*every* band from 0 to 10 m returns the same number. Strava's headline 2 m / 10 m thresholds are a
guard rail; the smoothing scale is the mechanism. So **R28 is the load-bearing row**, and R27 is
what makes the *unsmoothed* figure defensible.

### R27 — Hysteresis dead band on cumulative ascent ✅

`Path.computeDerivedData` sums every positive delta with no dead band, and it re-runs after every
pipeline stage, so `elevationGain` on a raw input and on a delivered path are different quantities
under one name. On `strava.gpx` the input figure is **1066 m** for a ride whose own barometric
stream measures 634 m at a stated scale. That inflated number is what `EnhanceCommand` printed and
what `PathToFit` wrote as FIT `totalAscent`.

- **Algorithm**: a turning-point accumulator, not a per-delta filter. A leg is banked once, in full,
  when the profile reverses by the threshold. Per-delta filtering is the tempting one-liner and it
  is wrong — on a smooth 500 m climb sampled at 2 m it reports zero.
- **Placement**: beside `elevationGain`, never replacing it. `ClimbDetector` sizes its adaptive
  threshold from the raw sum (`ClimbDetector.kt:67`, and `clamp(gain/50, 10, 50)` at ~line 240);
  redefining the field would have silently retuned climb detection on every route. `Path` gained
  `elevationGainFiltered` / `elevationLossFiltered` (NaN when unmeasured) and
  `reportedElevationGain` / `reportedElevationLoss`, which fall back to the raw sum.
- **Threshold**: 3.0 m. DEM error is spatially correlated rather than white — consecutive points
  inside one ~13.5 m cell interpolate the same four posts — so Strava's 10 m, sized for
  GPS-altimeter white noise, is an unjustified haircut: `garmin.gpx`, 6 m of genuine undulation
  over 3.9 km, reports **0 m** at that preset. But DEM error is not zero either. 3.0 m is
  GoldenCheetah's default and the only independent prior-art value derived from corrected rather
  than device elevation. **A defensible starting point, not a measured optimum.**
- **Cost**: O(n), branch-only, no allocation, run once at the end of the pipeline.

**Measured** — `MEASURE=1 ./gradlew :engine:jvmTest --tests '*ElevationGainMeasurementTest*'`.
D+ in metres by profile and preset (`source` = densified, before the 150 m kernel):

| Fixture | profile | raw | barometric | dem | gps |
|---|---|---|---|---|---|
| `strava` (21 km, 1 Hz baro) | source | **1007** | 643 | **637** | 633 |
| | smoothed | 632 | 632 | 632 | 631 |
| `sports-tracker` (12 km, GPS altitude) | source | **1278** | 1234 | **1088** | 907 |
| | smoothed | 655 | 649 | 641 | 628 |
| `stelvio` (3.6 km, DEM switchbacks) | source | 222 | 197 | **173** | 139 |
| `sample` (130 km, clean DEM route) | source | 4551 | 4511 | **4501** | 4459 |
| `garmin` (3.9 km, flat) | source | 6 | 6 | **6** | **0** |

Two things this settles. The dead band earns its place on the *source* profile — 1007 → 637 on
`strava.gpx`, a 37 % correction — and it is nearly a no-op once the 150 m kernel has run, which is
why R28's window and not this threshold is the load-bearing choice. And Strava's 10 m preset is
measurably destructive on gentle terrain, which is the argument for `dem` over a literal copy of
their number.

### R28 — Elevation smoothing scale as an option ✅ (default unchanged)

`ElevationStep.DEFAULT_SMOOTH_WINDOW_M = 150.0` was hard-coded, unconditional, and reachable from
no door — while being the largest single determinant of the gradients the simulation rides. It has
now been exposed as `EnhanceOptions.elevationSmoothWindowM` on all four surfaces, and **measured**.

What 150 m removes from the *profile*, against no smoothing at all:

| Fixture | unsmoothed | @150 m | change |
|---|---|---|---|
| `sample.gpx` (130 km, clean DEM route) | 4551 | 4484 | −1.5 % |
| `strava.gpx` (21 km, 1 Hz barometric) | 1066 | 632 | −41 % |
| `stelvio.gpx` (3.6 km, DEM switchbacks) | 222 | 132 | −41 % |
| `sports-tracker.gpx` (12 km, GPS altitude) | 1278 | 668 | −48 % |

What it is worth **on the clock**, which is the number that matters now that D+ is measured on
`sourceElevation` and is therefore independent of this window (CLI, `--no-simplify`, defaults):

| Window | `stelvio` | `strava` | `sample` |
|---|---|---|---|
| 10 m | 693 s | 2918 s | 19 575 s |
| 50 m | 637 s | 2907 s | 19 562 s |
| **150 m** (shipped) | **594 s** | **2899 s** | **19 508 s** |
| 300 m | 571 s | 2888 s | 19 411 s |

**17.6 % on `stelvio.gpx` between 10 m and 300 m**, against 1.0 % on `strava` and 0.8 % on
`sample`. The whole effect lives on switchback terrain, where a DEM cell averages the road with the
hillside it is cut into and the sampled profile oscillates as the road traverses. Some of what the
kernel removes there is artefact and some is real climbing, and **nothing distinguishes them without
ground truth** — which is precisely why the default does not move on the strength of this table.
What changed is that it is now a claim someone can falsify rather than a constant nobody could see.

A 150 m triangular half-width has an effective averaging length of `150/√6 ≈ 61 m`, inside the
30–300 m band where Kollár measures real terrain — so D+ read off the physics profile runs
systematically low, which is why R27 smooths its own copy at its own ~30 m scale.

### R29 — DEM road-snapping corridor ❌ (measured)

Strava's basemap "looks up the elevation for the road or trail you were actually on". It works
because it is built from real barometric traces recorded on the road; vcyclist would be snapping
within a single DEM. R30 gave this row its target: with `--fix-elevation`, `strava.gpx` reports
**854 m** where its own barometric stream measures 634 m, and z12/z15 agreeing to 0.8 % rules out
resolution — leaving *where along the road the DEM is sampled* as the remaining hypothesis.

**Measured before writing a solver.** `RoadSnapProbeTest` samples the real DEM at seven lateral
offsets across a ±15 m corridor (the GPS-error scale, not the lane scale), at the ~30 m station
spacing `fixElevation` sees, and reports D+ and mean `|second difference|` — the roughness a
snapper would minimise:

| offset | `stelvio` D+ | `stelvio` roughness | `strava` D+ | `strava` roughness |
|---|---|---|---|---|
| −15 m | 189 | 1.494 | 926 | 1.686 |
| −5 m | 132 | 0.980 | 872 | 0.664 |
| **0 m** | **131** | **0.873** | **861** | **0.233** |
| +5 m | 132 | 0.897 | 861 | 0.662 |
| +15 m | 177 | 1.266 | 899 | 1.671 |

**The recorded line is already the roughness minimum of its own corridor**, on both fixtures, and
roughness rises near-symmetrically with `|offset|` — a factor of 2.8 at ±15 m on `strava`. A
roughness-minimising snapper would therefore choose the centre everywhere: it has nowhere better to
go. D+ rises monotonically with `|offset|` too, so every lateral move makes both numbers worse.

Two corollaries worth keeping:

- **The 35 % over-report is not a registration error.** No point in the ±15 m corridor is closer to
  the barometric 634 m than the centre's 861 m; they are all further away. It is the model, or the
  scale at which a 30 m DEM can represent a graded road at all — neither of which a lookup
  strategy fixes.
- **The naive per-station rule is not merely useless, it is destructive.** Choosing the locally
  smoothest offset independently per station gives roughness 9.325 against the centre's 0.233 on
  `strava` (D+ 957) — the offset track goes jagged and the profile inherits it. That is the shape
  every "pick the best offset here" rule has before a smoothness penalty is added, and it is why
  the design called for a DP rather than a per-station `min` or `median`.

**Prediction and outcome.** The a-priori argument in this row was that a ±3 m *lane* corridor is
sub-pixel at ~13.5 m posts and therefore meaningless. That part holds — ±5 m moves `stelvio` by
1 m. But the reasoning did **not** extend to the ±15 m GPS-error corridor, where a lateral move
changes individual elevations by up to 14 m: the corridor is far from sub-pixel, and the idea
failed for a different and better reason than the one predicted. Recorded here because the wrong
argument for a right answer is the kind of thing that gets re-derived.

The design, if the tile source ever changes enough to reopen this: reuse `LocalFrame.project` for
the frame and `Corridor`'s self-proximity clamp (out-and-backs and switchbacks must not snap two
physically adjacent stations to the same offset), sample K ≈ 7 offsets per station (K×
interpolations but ~1× network — the offsets share tiles and `BatchCalculator` groups by tile key),
and choose by dynamic programming on a second-difference roughness term plus a `(k_i − k_{i−1})²`
smoothness term and a `k_i²` prior toward the recorded trace. It must live in `:engine`: `Corridor`
and `LocalFrame` are `internal` to the `trajectory` package. Not built — R25 is the precedent for
writing the measurement down instead.

### R30 — DEM zoom above 12 ❌ (the door shipped anyway)

`ElevationProvider` validates `zoomLevel in 0..15`; that is a validation bound, and
`map/.../MapSpace.kt:30`'s comment that the DEM source "has no deeper tiles" is unsourced. Both are
wrong about mapterhorn: `tiles.mapterhorn.com` serves 200s at z13, z14 **and** z15, and its
`tiles.json` declares neither `minzoom` nor `maxzoom`. Availability is not resolution, though —
mapterhorn is a fused global product (Copernicus 30 m worldwide, national high-resolution models
only where they exist), so above the source's native posting a deeper zoom is resampling.

**Measured** (CLI, `--fix-elevation --dem-zoom Z --no-simplify`):

| Fixture | z12 | z13 | z14 | z15 |
|---|---|---|---|---|
| `stelvio` (Alps) D+ | 131 m | 130 m | 130 m | 130 m |
| `stelvio` duration | 568 s | 568 s | 568 s | 568 s |
| `strava` (Pyrenees) D+ | 854 m | — | 851 m | — |

**0.8 % of spread on D+ and none at all on the clock.** Zoom 12 is at the source's native
resolution in both regions; going deeper costs four times the tiles for interpolation ripple. The
default stays at 12 and the row closes.

The door shipped regardless (`--dem-zoom`, `demZoom`, `vcSetElevationConfig`), because without it
this table could not have been produced from any surface — the CLI's `--zoom` is `ExportCommand`'s
*map* zoom and `Enhancer` built its provider with defaults.

**A finding worth more than the row.** With `--fix-elevation` on, `strava.gpx` reports **854 m**
where its own barometric stream measures 634 m — the DEM inflates mountain climbing by **35 %**.
That is the failure mode Strava's own documentation admits ("in some regions the underlying
elevation basemap data is poor, resulting in inflated elevation totals"), and it is a far larger
error than anything R27 or R30 addresses. R29 attacked it and failed: the recorded line is already
the smoothest place in its own corridor, so the gap is the model rather than where it is sampled.

## G. Cross-cutting notes

- **A unit inconsistency.** `PointField.ELAPSED` and `DT` declare **ms**, and
  `VirtualizeService` writes ms — but `Path.computeDerivedData` rewrites both in **seconds**
  (`(time − timeStart) / 1000`, `(Δtime) / 2000`), and it runs last, so a finished path carries
  seconds under a field labelled ms. CSV and JSON export inherit the wrong label. Providers are
  unaffected — they run *during* the simulation, where the millisecond values are still in place —
  which is precisely why it had gone unnoticed — **except** `WPrimeBalanceComputer`, which runs
  after the pipeline, read `dt`, and was silently 1000× out until R16 compared it against a
  provider's own state. Anything reading an interval outside the simulation must use `time`, which
  is milliseconds everywhere. Fixing the underlying convention remains open.
- **Robustness found on the way.** R12's test fixtures walked into two hangs/blowups in code paths
  the pipeline protects but public API does not: `PowerComputer.getDt` never converges on a
  zero-length segment (`dx == 0` makes its search tolerance 0), and a `Path` whose `speedMax` is
  still zero-initialised simulates to `Infinity`. The first is fixed and pinned by
  `VirtualizeZeroLengthTest`; the second is only reachable by calling `virtualizeTrack` without
  `MaxSpeedComputer` and is left as-is, recorded here. R18 then surfaced a third: `PowerComputer.getDx`
  let the kinetic-energy radicand go negative when the balance removes all the speed within `dt`
  (`sqrt` → `NaN` → every comparison in `getDt` reads false → a near-zero `dt` and a garbage speed).
  Clamped at zero, which is what the physics says: the rider has slowed to `MINIMAL_SPEED`.
- **Output movement.** R9, R10, R11, R16, R17, R18 all move pipeline output; R12 and R15 do not
  (they add fields without changing the trajectory — R15 shipped with a test pinning exactly
  that). Anything in the first group is a behavioural change to re-smoke through the CLI.
- **Validation ceiling.** [`06 §6.3`](../research/06-implementations-and-validation.md): Zwift's unexplained
  "performance-result gap" of **1–23 %** is a useful bound on how accurate *any* purely
  physics-driven solo simulation can be against real outcomes. Worth quoting in the README before
  claiming realism.
- **What the field cannot help with.** `computeRadiusWindowed` is **ahead of the published state of
  the art** — Zignoli generated courses from exact clothoids and had no GPS-polyline problem to
  solve. There is no literature to validate R9/R10/R11 against at the radius-estimation step, so
  their calibration is ours.
