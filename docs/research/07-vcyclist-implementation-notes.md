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
| Cornering limit from radius and lean angle | ✅ `MaxSpeedComputer` — and the 35° lean cap (tan ≈ 0.70) is a *sub-optimal* limit, which §5.3's early/late-apex finding says is the realistic choice |
| Corner radius estimated from a GPS polyline | ✅ `computeRadiusWindowed` — **the literature offers no method for this at all**, so this is ahead of the published state of the art |
| Backward pass propagating braking limits from corner exit | ✅ `MaxSpeedComputer` single backward pass |

The mechanical layer is in good shape. The gaps are the physiological, mental and tactical layers,
which do not exist in the codebase at all.

## 7.2 Concrete findings to act on

### (a) `DEFAULT_WHEEL_RADIUS_M = 0.7` is a diameter, not a radius

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
Check `docs/parity.md` before touching it, and check whether the TS reference has the same bug (if
so, fixing it is a deliberate divergence to document, like the two `VirtualizeService` fixes
already documented in `CLAUDE.md`).

### (b) `DEFAULT_MAX_BRAKE_G = 0.6` is the theoretical limit, not realistic behaviour

Measured values from §5.2:

- Pitch-over (stoppie) ceiling: **0.56–0.63 g** — 0.6 is right at it.
- What real riders actually use: **0.41 ± 0.07 g** combined braking, i.e. **~60–65 % of the limit**.

For a simulator whose goal is a *believable* ride, **0.4 g** is the better default, with 0.6 g
exposed as an "expert descender" setting. This also composes with the early/late-apex finding: real
descenders leave margin everywhere, not just in lean angle.

Add brake actuation lag (~0.13 s) to the braking-point calculation if you want the extra realism —
`MaxSpeedComputer.computeBrakingLimit` currently assumes instantaneous onset.

### (c) `G = 9.8` vs 9.80665

A 0.07 % systematic error on both the gravity and rolling terms. Almost certainly deliberate for TS
parity — worth a one-line comment saying so, since it reads as a typo.

### (d) CdA is a constant; the literature makes it yaw-dependent

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

**3. A pacing heuristic — not an optimiser.** §4.4 is decisive here: the full DP takes **53 minutes
for an 18 km route**, which is a non-starter for a pipeline stage. But §4.4 also shows **±5 % errors
in CP/AWC cost only ~1 % in trial time** — the optimum is flat. Implement the qualitative rules
(harder uphill and into headwind, ease downhill and with tailwind; spend W′ *before* a descent
because it cannot be spent *during* one) and capture most of the available gain cheaply.

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
