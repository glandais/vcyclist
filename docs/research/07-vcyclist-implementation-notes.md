# What this means for vcyclist

Mapping the research onto the current codebase. This is the actionable chapter.

## 7.1 What vcyclist already gets right

| Research finding | vcyclist status |
|---|---|
| Time-stepped ODE evaluation beats steady-state (98.9–99.6 % vs 97 % of variance, Dahmen 2011) | ✅ `VirtualizeService` already time-steps. **This is the more accurate variant, not an approximation.** |
| Drivetrain efficiency ~0.975–0.977 (triangulated across 3 sources) | ✅ `DEFAULT_DRIVETRAIN_EFFICIENCY = 0.976` — Martin's value exactly |
| Crr 0.004 asphalt (Dahmen), range 0.002–0.007 | ✅ `DEFAULT_CRR = 0.004` — Dahmen's value exactly |
| CdA ≈ 0.35 m² for a road/ITT rider (Zignoli pro ITT parameter set) | ✅ `0.7 × 0.5 = 0.35 m²` |
| Wheel bearing losses as a separate term | ✅ `WheelBearingsPowerProvider` |
| Rotational inertia in `m_eq` | ✅ `PowerComputer.equivalentMass` — but see §7.2 |
| Air density from altitude **and temperature** | ✅ `RhoProviderEstimate` (full ISA) is **better than the literature's** `ρ = 1.225·e^(−0.00011856·h)`, which has no temperature term at all |
| Cornering limit `v_max ≅ √(µgR)` | ✅ `MaxSpeedComputer` uses `√(g·R·tan θ_lean)`, which is the same equation with `µ ≡ tan θ`. See §7.2(d) — the default is well chosen |
| Corner radius estimated from a GPS polyline | ✅ `computeRadiusWindowed` — **the literature offers no method for this at all**, so this is ahead of the published state of the art |
| Backward pass propagating braking limits from corner exit | ✅ `MaxSpeedComputer` single backward pass |

The mechanical layer is in good shape. The gaps are the physiological, mental and tactical layers,
which do not exist in the codebase at all.

## 7.2 Concrete findings to act on

> **Status (2026-08-17)**: (a), (b), (c) are applied and (d) documented — in `EngineConstants`,
> the CLI mixins, the demo presets and the docs. The parity
> fixtures held inside the 0.5 % budget and were not regenerated. Still open from (b): brake
> actuation lag; from (d): a wet/dry µ switch and the pedal-strike power cut-off; from (e):
> yaw- and posture-dependent CdA.

### (a) `DEFAULT_WHEEL_RADIUS_M = 0.7` is a diameter, not a radius — ✅ **fixed**

`Bike.kt:11` documents it as *"Wheel radius in meters (default 0.7 = 700c with 25mm tire)"*. A 700c
wheel with a 25 mm tyre has a radius of **~0.35 m**; 0.7 m is its diameter. Martin et al. use
**r = 0.311 m** (20 mm tyre).

It feeds `equivalentMass = totalInertia / r²`:

| r | `m_eq` contribution from `I = 0.12 kg·m²` |
|---|---|
| 0.7 m (current) | 0.245 kg |
| 0.35 m (correct) | **0.980 kg** |

So the rotating mass is understated by ~0.73 kg on an ~80 kg system — under 1 % on the
kinetic-energy term only, and invisible at constant speed. Small, but it is a unit error, and it
will bite anyone who later uses `wheelCircumferenceM` for cadence or odometry, where it is a
**factor-of-2 error**.

⚠ Changing it will shift pipeline output on accelerations and may exceed the 0.5 % parity budget.
Re-measure before touching it, and document the change like the two `VirtualizeService` fixes
already documented in `CLAUDE.md`.

### (b) `DEFAULT_MAX_BRAKE_G = 0.6` is the theoretical limit, not realistic behaviour — ✅ **fixed**

Measured values from §5.2:

- Pitch-over (stoppie) ceiling: **0.56–0.63 g** — 0.6 is right at it.
- What real riders actually use: **0.41 ± 0.07 g** combined braking, i.e. **~60–65 % of the limit**.

For a simulator whose goal is a *believable* ride, **0.4 g** is the better default, with 0.6 g
exposed as an "expert descender" setting. This also composes with the early/late-apex finding: real
descenders leave margin everywhere, not just in lean angle.

Add brake actuation lag (~0.13 s) to the braking-point calculation if you want the extra realism —
`MaxSpeedComputer.computeBrakingLimit` currently assumes instantaneous onset.

### (c) `G = 9.8` vs 9.80665 — ✅ **fixed**

A 0.07 % systematic error on both the gravity and rolling terms — worth a one-line comment saying
where the rounded value came from, since it reads as a typo.

### (d) The 35° lean default is a good number — and now has a literature anchor — ✅ **documented**

`MaxSpeedComputer` computes `v_max = √(g · R · tan θ_lean)`. That is algebraically
`v_max = √(µ · g · R)` with **µ ≡ tan θ_lean**, i.e. vcyclist's lean-angle parameter *is* a friction
coefficient wearing a different hat. With `DEFAULT_MAX_LEAN_ANGLE_DEG = 35°`, **µ_effective = 0.70**.

Against Zignoli's measured values (§5.1):

| Condition | µ | Equivalent lean angle |
|---|---|---|
| Dry asphalt (physical limit) | 0.90 | 42.0° |
| **vcyclist default** | **0.70** | **35.0°** |
| Wet asphalt (physical limit) | 0.36 | 19.8° |

So the default sits at **78 % of dry grip** — a sensible "confident rider leaving margin", and
consistent with the finding that real descenders ride below the physics-optimal line. Two cheap
improvements:

- Expose a **wet/dry condition** that swaps `µ` to ~0.36 (≈20° lean). This is the single largest
  realism lever on a twisty descent: wet cuts cornering speed by **1.58×**.
- Consider re-expressing the parameter as `µ` directly, since that is the form every source uses
  and it makes the wet/dry mapping obvious. `tanMaxLeanAngle` is already the value being consumed.

Also worth knowing: Zignoli's model **zeroes pedal power above a roll-angle threshold** (20° in his
appendix) for pedal-strike clearance. vcyclist has no such term — a rider currently pedals at full
power through a hairpin.

### (e) CdA is a constant; the literature makes it yaw-dependent

`AeroProvider` returns a single `aeroCoef`. Martin interpolates CdA across yaw
(0.269 / 0.265 / 0.265 / 0.255 m² at 0/5/10/15°) — though for his TT position the variation is
**not statistically significant**, so this is low-value until you model posture changes.

The higher-value version is **posture-dependent CdA**: hoods vs drops vs sitting up on a descent is
a far larger CdA swing than yaw, and it interacts with `MaxSpeedComputer` (a rider braking into a
corner is not in an aero tuck). No verified source covers this — it would be a project-owned model.

## 7.3 What to build next, ranked

**1. W′bal (highest value, lowest risk).** It is a single state variable, the equations are settled,
and it is the foundation of both the fatigue and the pacing layers.

- Implement the **differential (ODE) form** — see [`02`](02-physiological-modeling.md#21-critical-power-and-w).
- GoldenCheetah's 1 Hz recursion is a directly portable reference
  ([`06`](06-implementations-and-validation.md#61-goldencheetah--the-reference-open-source-implementation)):
  ```
  if (P < CP) W += (CP − P) · (WPRIME − W) / WPRIME
  else        W += (CP − P)
  ```
  vcyclist already resamples to 1 Hz in `PointPerSecond`, so this drops straight into the pipeline
  **after** step 7.
- Defaults: `CP = 250 W`, `W′ = 20 000 J` (GoldenCheetah's shipped values). W′ range 10–40 kJ.
- Expose τ as configuration so Bartram's elite form (`τ = 2287.2·D_CP^−0.688`) can be swapped in.
- Add `wPrimeBalance` as a new `PointField` (per the `CLAUDE.md` codegen workflow).

**2. A CP-aware `CyclistPowerProvider`.** Today `PowerProviderConstantWithTiring` decays power
open-loop. Replacing the decay with a W′bal-driven rule makes the simulated rider *behave* like a
rider: back off when W′ is low, spend it on climbs. The existing `ClimbDetector` gives you the
lookahead needed for the anticipation behaviour in §4.3.

**3. A pacing heuristic — not an optimiser.** §4.4 is decisive: the full DP takes **53 minutes for
an 18 km route**, a non-starter for a pipeline stage. And you lose almost nothing by approximating:
**±5 % errors in CP/AWC cost only ~1 % in trial time**, the whole optimum is worth **1–3 %** on
realistic courses, and a real professional already rides within **1.2 %** of it. Implement the
qualitative rules and capture most of the gain for none of the cost:

- Harder uphill and into headwind, easier downhill and with tailwind.
- **Ramp up gradually over several hundred metres** approaching a climb; **drop off quickly and
  locally** on a descent (the asymmetry from Bach et al. — a discontinuous power step at each
  gradient change is the specific thing to avoid).
- Spend W′ *before* a descent, because it cannot be spent usefully *during* one; expect to recover
  ~8 % of W′ through the descent and spend it on the next climb.
- Prioritise **climb-plus-descent** sequences: that shape is worth 2.84 % versus 1.41 % for a pure
  climb and 0.45 % for flat. Rolling terrain is where a pacing model earns its keep; flat routes
  barely repay the effort.
- Consider a **power slew-rate limit** (Zignoli & Biral use 50 W/s) so the simulated rider cannot
  step power instantaneously.

**4. Fuelling as a second state variable.** Glycogen depletion from
[`02b`](02b-fuelling-and-thermal.md), driven by mechanical work through gross efficiency. Note the
research **did not obtain a gross-efficiency figure** — you will need to source the ~20–25 %
conversion independently. This is what makes a 6-hour ride simulate differently from three 2-hour
rides.

**5. RPE / Hazard Score.** Cheap to add, and it is the layer that makes output *legible* to a human
("this is where the ride got hard"). See [`03`](03-mental-modeling.md#34-pragmatic-recommendation-for-vcyclist)
— label it explicitly as heuristic.

## 7.4 The one thing not to do

**Do not implement optimal pacing as the simulated rider's behaviour.** §4.5: the best
optimal-control ITT model in the literature matches real professional riders' velocity for
**18–32 % of course duration**. Those models are prescriptive — they say what a rider *should* do.
vcyclist's job is to say what a ride *would look like*. A simulated rider executing a
bang-singular-bang optimal strategy would produce a GPX no human would recognise as their own ride.

Optimal pacing is a legitimate *separate feature* ("here is how you could have ridden this"). It is
the wrong model for the default simulation.
