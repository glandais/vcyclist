# Layer 1b — Cornering, braking and descending

> Evidence status: **upgraded to strong** after direct reading of
> [`zignoli2020.pdf`](zignoli2020.pdf) (Zignoli A, *Influence of corners and road conditions on
> cycling individual time trial performance and 'optimal' pacing strategy*, Proc IMechE Part P,
> 2020, DOI 10.1177/1754337120974872). That paper closes the friction-coefficient gap this report
> previously flagged as open, and confirms two claims the automated verification pass had wrongly
> killed.
>
> This layer matters disproportionately: **no amount of accuracy in the longitudinal power balance
> produces a realistic descent** if the simulated rider takes a 40 km/h hairpin at terminal
> velocity.

## 5.1 Cornering speed limit

Verbatim, Zignoli 2020 equation (1):

```
v_max ≅ √(µ · g · R)
```

Two details from the surrounding text that matter for implementation:

- **`R` is the radius of the *cyclist's trajectory*, not the road's.** *"The trajectory of the
  cyclist is the path followed by the centre of mass of the system and at every time-instant, this
  path can be approximated locally by a circle with a radius R."* The paper explicitly criticises
  earlier models for *"taking into consideration the radius of the road corners, not the radius of
  the trajectory of the cyclist"*. A rider who straightens a bend rides a larger `R` and a higher
  `v_max`.
- `v_max` rises with µ (dry vs wet) **or** with a larger chosen trajectory radius — those are the
  two levers a rider actually has.

### Friction coefficients — the gap is now closed

**µ = 0.9 (dry) and µ = 0.36 (wet)** for road bicycle tyres. Verbatim: *"Road conditions were
simulated by changing the friction coefficient µ from 0.9 to 0.36."* Sourced by Zignoli to Muller,
Uchanski & Hedrick, *Estimation of the maximum tire-road friction coefficient*, J Dyn Syst Meas
Control 2003; 125:607–617.

> This supersedes the earlier "no verified µ values" gap. **Wet asphalt is only 40 % of dry
> grip** — a factor-of-2.5 reduction, which translates to a **1.58× reduction in cornering speed**
> (√2.5). That is by far the largest single lever on descent realism.

### Roll angle and the friction ellipse

The combined-acceleration constraint (Appendix):

```
(a_x / (µ_x · g))² + (a_y / (µ_y · g))² ≤ 1

a_xmax = a_ymax = 9.81 m/s²      longitudinal and lateral maximal accelerations
δ_max  = 0.52 rad (~30°)         maximal steering angle
```

This confirms the friction-ellipse claim that automated verification had voted down 0-2. Note the
9.81 m/s² figures are the *maximal acceleration* parameters, with µ entering separately — so
reading them as "µ = 1.0" (as the original extraction did) was an over-reading.

**Pedalling is disabled at high lean.** This is an implementable detail most simulators miss —
though the paper states it inconsistently:

| Location | Threshold |
|---|---|
| Methods text | *"the maximal power output was reduced to zero for roll angles greater than 5°, because a rider needs some clearance between the pedals and the ground"* |
| Appendix | `W_max = 0 if |φ| ≥ 20°` |

5° is implausibly low for a real pedal-strike limit and 20° is plausible; treat 20° as the value
and the 5° as likely a typo, but be aware the source disagrees with itself.

## 5.2 What corners actually cost

Zignoli simulated eight 40 km ITT courses, dry and wet (16 simulations), with hairpin turns of
**radius 15 m interspersed by 400 m straights**, road width 8 m on straights and 6 m in technical
sections, 5 % slope, 3 m/s wind.

**Speed reduction through turns:**

| Situation | Cruise | Dry | Wet |
|---|---|---|---|
| Downhill technical section | ~60 km/h | ~36 km/h | ~28 km/h |
| Flat hairpin (R = 15 m) | ~46 km/h | ~29 km/h | ~25 km/h |

The paper proposes the first formal definition of a **"technical section"**: *"a series of turns
that force the riders to reduce their speed by more than 40 % with reference to their cruise
speed."*

**Time cost of wet conditions over 40 km** (Table 2, smallest worthwhile difference = 1 %):

| Course type | Dry → wet penalty |
|---|---|
| With technical sections (Races 1–4) | **1.8 % – 3.4 %** (55–110 s) |
| Without technical sections (Races 5–8) | 0 % – 0.5 % (1–17 s) — **not meaningful** |

So road surface only matters when there are actually corners to be limited by — which is the
correct intuition, now quantified.

## 5.3 Two confirmed claims that verification had killed

Both are verbatim in the abstract, and both were wrongly refuted by the automated pass (see
[`04 §4.6`](04-behavioral-modeling.md#46-disputed-claims--status-after-direct-re-checking)):

1. **Road conditions change performance time and peak power, but *not* the pacing strategy** — when
   technical sections make up ~25 % of the course. *"road conditions can meaningfully affect the
   final performance time and peak power required, but not the pacing strategy."* Table 3 confirms
   it numerically: average power is 392–394 W in **every** race, dry or wet.
   → **A simulator can decouple corner/surface modelling from the pacing layer.**
2. **Corners are not free recovery.** *"the time lost in slow technical sections cannot be regained
   during fast straight sections, even if technical sections are used to restore anaerobic energy
   stores."*

## 5.4 Cornering trajectory: two rider archetypes

Directly implementable as a rider-style parameter:

| Type | When | Behaviour |
|---|---|---|
| **Type I** | high µ (dry) | Keep a high steady velocity through the corner; little power needed to re-establish cruise speed afterwards |
| **Type II** | low µ (wet) | Slow to a low apex speed, then need *significant* power to propel out with a high exit speed |

The paper also finds **slope affects trajectory choice more than road conditions do**: on downhill
technical sections, riders generate larger trajectories (greater lateral displacement at the exit
point); on flat hairpins the trajectory radius stays very small. *"All the riders cut the turn at
the entrance, but the trajectory stays close to the internal side of the road only in dry and flat
conditions."*

Power-distribution consequence (Figure 2): technical sections produce **high power spikes of
600–700+ W** re-accelerating out of corners and **hard braking of −200 to −460 W**. A simulator
that models corners will produce a visibly different power histogram from one that doesn't.

## 5.5 Braking

From bicycle accident-reconstruction measurements (Nathan Rose) — **not** peer-reviewed, but
reporting controlled tests:

| Braking mode | Deceleration (dry pavement) |
|---|---|
| Combined front + rear | **0.40 – 0.71 g** |
| Front only | 0.40 – 0.53 g |
| Rear only | 0.22 – 0.37 g |

**The limit is pitch-over (stoppie), not tyre friction.** Computed at **~0.63 g** for a
bicycle+rider of 185 lb with a 42-inch wheelbase, CG 40 inches high and 25 inches behind the front
axle (a second worked example gives 0.56 g). This is the correct physical ceiling for an upright
cyclist — it is *lower* than what the tyres could deliver, which is why front-brake capacity
dominates and rear-only braking is so weak.

**Real riders use only ~60–65 % of the theoretical limit**: **0.41 ± 0.07 g** combined,
0.24 ± 0.02 g rear-only. For a *believable* simulator, **0.4 g** is the number, not 0.63 g.

**Actuation lag**: 124 ms ± 22 ms (rear only), 129 ms ± 33 ms (combined), some tests 400–600 ms.

A practical braking-point rule: given a corner at distance `d` with limit speed `v_c`, brake when
`v² − v_c² ≥ 2·a·(d − v·t_lag)` with `a ≈ 0.4·g` and `t_lag ≈ 0.13 s`.

## 5.6 Descending and line choice

Zignoli & Fruet 2022 (drone-tracked real descents, ~220 m course, 10 repetitions) — **unverified**,
all three verifier votes errored, and the PDF was not supplied:

- Analysis is structured around **three detectable events per corner: braking point, turn-in point,
  apex** — exactly the decision structure a simulator needs.
- The model is a **9-state ODE system** with **lean angle as an explicit state**, not a static
  `v_max = √(µgR)` constraint.
- **The critical finding**: the model-optimal line was an **early apex**, while the real cyclist
  consistently rode a **late apex**. → *A simulator using physics-optimal lines will overestimate
  descent speed.*
- Code and drone trajectory data: <https://github.com/andreazignoli/drone_footage>

This is consistent with Zignoli 2020's independent observation that trajectory choice is dominated
by slope and rider preference rather than by pure optimality.

**Terminal velocity on a descent** falls out of the power balance in
[`01`](01-physical-modeling.md) with `P = 0`: solve `½ρCdA·v² + Crr·m·g·cosθ = m·g·sinθ`. Zignoli's
~60 km/h cruise on a 5 % descent is a plausible sanity check.

## 5.7 What is still missing

- Corner-radius estimation from a **GPS polyline**. Zignoli generated courses from *interpolating
  clothoids* — he had exact geometry, not noisy GPS. vcyclist's `computeRadiusWindowed` remains
  ahead of the published state of the art here, with no literature to validate against.
- Any **rider-skill parameterisation** of how far below `√(µgR)` real riders actually corner.
- Effect of line choice on **distance travelled**.

## Sources

- Zignoli A. *Influence of corners and road conditions on cycling individual time trial performance
  and 'optimal' pacing strategy: A simulation study.* Proc IMechE Part P: J Sports Engineering and
  Technology, 2020. DOI 10.1177/1754337120974872. → [`zignoli2020.pdf`](zignoli2020.pdf)
- Muller S, Uchanski M, Hedrick K. *Estimation of the maximum tire-road friction coefficient.*
  J Dyn Syst Meas Control 2003; 125:607–617. (µ source, cited by Zignoli)
- Rose N. *Bicycle accident reconstruction: bicyclist braking capabilities and limits* (blog,
  reporting controlled measurements).
  <https://www.nathanarose.com/blog/bicycle-accident-reconstruction-bicyclist-braking-capabilities-and-limits>
- Zignoli A, Fruet D. Sports Engineering, 2022 — drone-tracked descents vs optimal control.
  <https://link.springer.com/article/10.1007/s12283-022-00386-1> ·
  code: <https://github.com/andreazignoli/drone_footage>
