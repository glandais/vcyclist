# Research improvements ledger

One row per improvement suggested by (or derivable from) [`docs/research/`](README.md), scored
against **the code as it actually stands**. Each entry has an ID so a later task can reference it.

**This file records assessment only — no fixes, no implementation.** It is the tracking surface for
the research; [`07-vcyclist-implementation-notes.md`](07-vcyclist-implementation-notes.md) remains
the narrative synthesis, and where the two disagree the disagreement is stated explicitly in the
entry (R7, R12, R19, R21).

- Date of assessment: 2026-08-17
- Assessed against: `develop` @ `0f979af`
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
| R9 | Wet/dry µ condition preset | **High** | **Very low** | 🔵 |
| R10 | Pedal-strike power cut-off at high lean | Med-high | Low | 🔵 |
| R11 | Friction ellipse (combined braking + cornering) | Medium | Medium | ⚪ |
| R12 | **Brake power as a `PointField`** | **High** | **Very low** | 🔵 |
| R13 | Brake actuation lag (0.13 s) | Negligible | Low | ❌ |
| R14 | Posture-dependent CdA | Medium | Med (unbounded validation) | ⚪ |
| R15 | W′bal as an **output** field (ODE form) | **High** | **Very low** | 🔵 |
| R16 | W′bal as a **behaviour** driver (CP-aware provider) | High | High | ⚪ |
| R17 | Durability: decay on supra-CP work, not elapsed time | Med-high | Low | 🔵 |
| R18 | Power slew-rate limit (50 W/s) | Medium | Low | 🔵 |
| R19 | Pacing heuristic (ramp up slow / drop fast, anticipation) | Medium | Medium | ⚪ |
| R20 | RPE + Hazard Score | Low-med | Low | ⚪ |
| R21 | Fuelling / glycogen state variable | Low | High | ❌ (for now) |
| R22 | Optimal-pacing solver as rider behaviour | Negative | Very high | ❌ |

Recommended order if acted on: **R15 → R12 → R9 → R17 → R10 → R18**, then re-assess R16/R11.

## A. Mechanical layer — closed

The longitudinal power balance is **complete against Martin et al. (1998)**, not merely adequate.
Verified term by term against [`01`](01-physical-modeling.md):

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

[`01 §1.1`](01-physical-modeling.md) and [`04 §4.4`](04-behavioral-modeling.md) both flag
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

### R9 — Wet/dry µ condition preset 🔵 **recommended**

[`05 §5.1`](05-cornering-braking-descending.md): µ = **0.90 dry**, **0.36 wet**, i.e. wet grip is
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

Validation anchor available for free: [`05 §5.2`](05-cornering-braking-descending.md) gives the
dry→wet time penalty over 40 km as **1.8–3.4 % with technical sections, 0–0.5 % without**. A route
with no tight corners that shifts by more than ~0.5 % indicates the clamp artefact above.

Also worth adopting from the same section: re-express the parameter as µ directly. Every source uses
that form, `tanMaxLeanAngle` is already the value being consumed, and it makes the wet/dry mapping
self-evident.

### R10 — Pedal-strike power cut-off 🔵 **recommended, with a caveat**

Zignoli zeroes pedal power above a roll-angle threshold for pedal-ground clearance. vcyclist's rider
currently pedals at full power through a hairpin.

- **Cheap**: lean is derivable from state already on the path — `θ = atan(v² / (g·R))`, and `radius`
  is a `PointField`.
- **Caveat**: at any cornering-limited point the simulation rides *exactly* at `speedMax`, which is
  *exactly* `maxLeanAngleDeg` = 35° by construction. So a 20° threshold zeroes power in **every**
  grip-limited corner, not in some of them. That is defensible — nobody pedals at the limit of grip
  — and it produces the coast-in / accelerate-out signature of [`05 §5.4`](05-cornering-braking-descending.md)
  (600–700 W exits). But it is a larger behavioural change than "add a threshold" sounds, and it
  will move time on technical routes.
- **Source disagrees with itself**: 5° in the methods text, 20° in the appendix. Treat 20° as the
  value, expose it, and say in the KDoc that the source is inconsistent.

### R11 — Friction ellipse (combined braking + cornering) ⚪

`MaxSpeedComputer` takes `min(cornering, braking)` — the two constraints are independent, so the
simulated rider may brake at 0.4 g while already at full lean. The physical constraint
([`05 §5.1`](05-cornering-braking-descending.md), appendix) is
`(a_x/(µ_x·g))² + (a_y/(µ_y·g))² ≤ 1`.

**This is the mechanism behind the one measurable calibration gap in the layer**: at Zignoli's
R = 15 m flat hairpin, vcyclist gives **36.5 km/h** dry against his simulated **~29 km/h** — ~25 %
fast, *despite* the deliberate 78 %-of-dry-grip margin in the 35° default. Independent constraints
explain the direction of that gap better than the µ value does.

Deferred rather than recommended because it changes the backward pass's structure (the braking limit
becomes lean-dependent, so the pass has to iterate or approximate), where R9/R10 are local.

### R12 — Brake power as a `PointField` 🔵 **recommended — not proposed by the research**

`VirtualizeService.kt:77` enforces `speedMax` by clipping: `speedNew = speedMax`, then
`dt = 2·dx/(v+v′)`. The kinetic energy removed is **silently discarded** — no provider records it,
no field carries it.

[`05 §5.4`](05-cornering-braking-descending.md) says a corner-aware simulator should show
**−200 to −460 W of braking** alongside the 600–700 W re-accelerations. vcyclist currently produces
the second and not the first: its power trace has no braking in it at all.

- **Cost**: record the discarded energy (`½·m_eq·(v_uncapped² − v_max²)/dt`) into a new field. No
  physics changes, no pipeline stage, no parity movement — the trajectory is identical either way.
- **Value**: it is the cheapest change that makes the output *look* like a real ride file, and it
  makes R10 and R11 observable when they land. It also gives `:map` and the demo something to draw.
- Follows the `CLAUDE.md` codegen workflow (edit `PointField`, run `:codegen:run`).

### R13 — Brake actuation lag ❌

[`05 §5.5`](05-cornering-braking-descending.md) measures 124–129 ms and proposes
`v² − v_c² ≥ 2a(d − v·t_lag)`.

- **Magnitude**: 0.13 s at 40 km/h (11.1 m/s) moves the braking point **1.44 m** — less than one
  sample of the 2 m-resampled path the physics runs on (`PointPerDistance(1, 2)`).
- **Verdict**: below the pipeline's own spatial resolution. Not measurable in the output.

### R14 — Posture-dependent CdA ⚪

Hoods vs drops vs sitting-up is a far larger CdA swing than yaw (R6), and it interacts with
`MaxSpeedComputer` — a rider braking into a corner is not in a tuck. `AeroProvider` is already a
`fun interface` taking `(course, path, pointIndex)`, so the extension point exists at zero
structural cost (`AeroProvider.kt:13`).

Deferred on evidence, not on value: [`07 §7.2(e)`](07-vcyclist-implementation-notes.md) states
plainly that **no verified source covers this** — it would be a project-owned model with no
literature to calibrate against, and its parameters would be invented. Worth doing eventually,
worth labelling as ours when it lands.

## C. Physiological

### R15 — W′bal as an output field 🔵 **recommended first**

[`02 §2.1`](02-physiological-modeling.md) + [`06 §6.1`](06-implementations-and-validation.md).

**Split from ch. 07's ranking.** Ch. 07 ranks "W′bal" #1 as a single item; it is two items with very
different risk, and only the first is low-risk:

| | R15 — metric | R16 — behaviour |
|---|---|---|
| Reads | `pComputedPower` after step 7 | drives `optimalPower` inside the sim |
| Touches | one new field, one post-pass | `VirtualizeService`, `Cyclist`, every provider |
| Parity fixtures | **unmoved** — trajectory unchanged | every value shifts |
| New required input | none (defaults CP 250 W / W′ 20 kJ) | CP and W′ become load-bearing |

R15 is essentially free: `PointPerSecond` already delivers the 1 Hz grid the recursion assumes.

- Use the **differential (ODE)** form. Skiba himself calls the integral form *"theoretically
  untenable"* for continuous severe-intensity work, and the two diverge by ~300 s in predicted time
  to exhaustion — this is a real fork, not a rounding choice.
- GoldenCheetah's shipped recursion is directly portable:
  `if (P < CP) W += (CP − P)·(W′ − W)/W′ else W += (CP − P)`.
- **Implementation trap**: that recursion is Euler at an *implicit* `dt = 1 s`. It is only correct
  where the path is 1 Hz — scale by `dt` if the field is ever computed before `PointPerSecond`, or
  document the stage ordering as a precondition.
- Expose τ so Bartram's elite form (`τ = 2287.2·D_CP^−0.688`) can be swapped in.
- Label the output as an approximation of a mechanism the field is actively replacing: [`02`](02-physiological-modeling.md)
  reports hydraulic three-component models **outperforming** W′bal on intermittent recovery.

### R16 — CP-aware `CyclistPowerProvider` ⚪

The step that makes the simulated rider *behave* like a rider: back off when W′ is low, spend it on
climbs, using `ClimbDetector` for lookahead. High value, and correctly identified by ch. 07.

Deferred behind R15/R17 because the literature supplies **no control law** — CP/W′ are descriptive
state, and the mapping from state to power is ours to invent and own. Building it after R15 means
the state variable is already validated and observable in output before anything depends on it.

### R17 — Durability: decay on supra-CP work, not elapsed time 🔵 **recommended**

**Under-weighted by ch. 07**, which folds durability into the fuelling item (R21) and inherits its
weak evidence grade. It deserves its own row, because it is a fix to code that already exists.

`PowerProviderConstantWithTiring.kt:37` decays power as
`c = max(0.5, 1 − 0.6·elapsed/durationSeconds)` — open-loop on **elapsed time**, which is precisely
the formulation [`02 §2.2`](02-physiological-modeling.md) says is wrong. The systematic review is
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

### R21 — Fuelling / glycogen ❌ (for now)

Ranked #4 by ch. 07. Recommended **against** for the current cycle:

- [`02b`](02b-fuelling-and-thermal.md) is the report's only chapter graded **⚠ extracted, not
  verified** — the 3-vote refutation never ran on it.
- It depends on a **gross mechanical efficiency** figure the research explicitly **did not obtain**
  ([`README` known gaps](README.md#known-gaps-in-this-report)); the kJ → fuel bridge would be
  sourced outside the report.
- The chapter concedes its own power decrement is **phenomenological, not mechanistic** (*"implement
  the power decrement directly as a function of the glycogen state variable, and label it as a fit,
  not a mechanism"*) — i.e. it would rebuild `PowerProviderConstantWithTiring` with more parameters
  and no more validation. **R17 gets most of that value with one expression and better evidence.**
- It only bites past ~3 h.

Revisit when 2b gets its dedicated verification pass — that pass is already recorded as owed in
[`README`](README.md) (GAP C produced zero surviving claims twice).

## D. Behavioural / tactical

**Framing constraint for this whole section.** [`04 §4.2`](04-behavioral-modeling.md): optimal pacing
is worth **1–3 %** on realistic courses, and a real professional rides within **1.2 %** of the
optimum. So R18/R19 must be justified as *realism of the power trace*, never as accuracy of
predicted time. If a pacing heuristic moves finish time by more than the 0.5 % parity budget, that
is a **bug signal, not a feature**.

### R18 — Power slew-rate limit 🔵 **recommended**

Zignoli & Biral use **50 W/s**. Nothing currently stops `CyclistPowerProviderBase` from stepping
power discontinuously, and [`04 §4.3`](04-behavioral-modeling.md) names a discontinuous step at each
gradient change as *the* specific artefact to avoid. Cheap, local to one class, and it is the part
of R19 that needs no anticipation machinery.

### R19 — Pacing heuristic ⚪

The qualitative rules from [`04 §4.3`](04-behavioral-modeling.md), worth capturing eventually:

- Harder uphill / into headwind, easier downhill / with tailwind.
- **Asymmetric in time**: ramp up gradually over several hundred metres into a climb; drop off
  quickly and locally on a descent.
- Anticipatory on rolling terrain: spend W′ *before* a descent, recover ~8 % through it, spend that
  on the next climb.
- Prioritise **climb-plus-descent** sequences — 2.84 % versus 1.41 % for a pure climb and 0.45 %
  flat. Flat routes barely repay the effort.

Deferred because it needs R16's W′ state to be meaningful, and because the source hedges: Bach et al.
quantify **no magnitude in watts or metres**, and reading "dispersed over several hundred metres" as
*anticipatory* is an interpretive step beyond the quoted text. The 2 %/8 % figures are single-source,
single-subject.

### R22 — Optimal-pacing solver as rider behaviour ❌

[`07 §7.4`](07-vcyclist-implementation-notes.md) is correct and should stay load-bearing:

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

## F. Cross-cutting notes

- **Parity budget.** R9, R10, R11, R16, R17, R18 all move pipeline output; R12 and R15 do not (they
  add fields without changing the trajectory). Anything in the first group needs the
  [`docs/parity.md`](../parity.md) checklist, and — per `CLAUDE.md` — a deliberate decision about
  whether the TS reference should follow.
- **Three projects, not one.** R1–R4 were applied in vcyclist, virtual-cyclist *and* gpx2web. Any
  constant-level change below (R9's µ re-expression in particular) inherits that obligation.
- **Validation ceiling.** [`06 §6.3`](06-implementations-and-validation.md): Zwift's unexplained
  "performance-result gap" of **1–23 %** is a useful bound on how accurate *any* purely
  physics-driven solo simulation can be against real outcomes. Worth quoting in the README before
  claiming realism.
- **What the field cannot help with.** `computeRadiusWindowed` is **ahead of the published state of
  the art** — Zignoli generated courses from exact clothoids and had no GPS-polyline problem to
  solve. There is no literature to validate R9/R10/R11 against at the radius-estimation step, so
  their calibration is ours.
