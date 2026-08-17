# Layer 1b — Cornering, braking and descending

> Evidence status: **partial.** The braking numbers come from an accident-reconstruction source
> (blog-quality, but reporting controlled measurements). The cornering model is verified in
> structure but its concrete friction parameter was voted down. Everything here is weaker than
> [`01-physical-modeling.md`](01-physical-modeling.md).
>
> This layer matters disproportionately: **no amount of accuracy in the longitudinal power balance
> produces a realistic descent** if the simulated rider takes a 40 km/h hairpin at terminal
> velocity.

## 5.1 Cornering speed limits

The verified structural result (Zignoli, ITT-with-corners simulation study): a bike-rider model
with **longitudinal *and* lateral dynamics**, where **maximum velocity is constrained by road
geometry (corner radius) and the tyre-road friction coefficient**. That is exactly the
`v_max = f(µ, R)` limit, embedded inside a time-minimising dynamic optimisation.

The textbook form, which the above endorses in structure:

```
v_max = √(µ · g · R)
lean angle θ = atan(v² / (g · R))
```

**The specific friction parameter is disputed.** The extraction found a friction-ellipse
constraint with `a_y,max = a_x,max = 9.81 m/s²` (i.e. **µ = 1.0**) plus a maximum steering angle
of 0.52 rad (~30°) — but this was **voted down 0-2** in verification. µ = 1.0 is at the optimistic
end for road tyres on dry asphalt; a simulator wanting plausible rather than heroic cornering
should treat it as an upper bound and expose µ as configuration.

> ⚠ **No verified source in either research pass gives realistic µ values for dry vs wet asphalt
> with road bicycle tyres.** This is a genuine open gap. Likewise, **no source addresses estimating
> corner radius from a GPS polyline** — which is the actual engineering problem for a GPX-driven
> simulator, and which you will have to solve without literature support (circumscribed-circle
> through consecutive points, or curvature from a smoothed spline, are the obvious approaches).

## 5.2 Braking

From bicycle accident-reconstruction measurements (Nathan Rose) — the most concrete numbers found:

| Braking mode | Deceleration (dry pavement) |
|---|---|
| Combined front + rear | **0.40 – 0.71 g** |
| Front only | 0.40 – 0.53 g |
| Rear only | 0.22 – 0.37 g |

**The limit is pitch-over (stoppie), not tyre friction.** Computed at **~0.63 g** for a
bicycle+rider of 185 lb with a 42-inch wheelbase, CG 40 inches high and 25 inches behind the front
axle (a second worked example gives 0.56 g). This is the correct physical ceiling to implement for
an upright cyclist — it is *lower* than what the tyres could deliver, which is why front-brake
capacity dominates the total and rear-only braking is so weak.

**Real riders use only ~60–65 % of the theoretical limit.** A controlled study measured
**0.41 ± 0.07 g** with combined braking and 0.24 ± 0.02 g rear-only. For a realistic (not optimal)
simulator, **0.4 g is the number to use**, not 0.63 g.

**Actuation lag**: 124 ms ± 22 ms (rear only), 129 ms ± 33 ms (combined), with some tests as long
as 400–600 ms. Add this to the reaction budget when choosing a braking point.

A practical braking-point rule follows directly: given a corner at distance `d` with limit speed
`v_c`, brake when `v² − v_c² ≥ 2·a·(d − v·t_lag)` with `a ≈ 0.4·g` and `t_lag ≈ 0.13 s`.

## 5.3 Descending and line choice

The one source found (Zignoli & Fruet 2022, drone-tracked real descents, ~220 m course, 10
repetitions) is **unverified — all three verifier votes errored out** — but is the most directly
relevant work in existence and ships open code:

- The paper operationalises descent analysis around **three discrete, detectable events per
  corner: braking point, turn-in point, apex.** That is precisely the decision structure a
  simulator needs.
- The bicycle-rider model is a **9-state ODE system** (heading angle, lateral displacement, roll
  angle, normalised steering angle, time, speed, power…), i.e. **lean angle is an explicit state**,
  not a static `v_max = √(µgR)` constraint.
- **The critical finding**: the model-optimal line was an **early apex**, while the real cyclist
  consistently rode a **late apex**. → *A simulator using physics-optimal lines will overestimate
  descent speed.* Real descenders sacrifice theoretical speed for sight lines and safety margin.
- **Code and drone-derived trajectory data are publicly released**:
  <https://github.com/andreazignoli/drone_footage>

For vcyclist this suggests a deliberately **sub-optimal** descent model: cap cornering speed at
some fraction of `√(µgR)` representing rider skill/risk appetite, rather than modelling lines at
all. That is a design decision the literature supports in direction if not in magnitude.

**Terminal velocity on a descent** falls straight out of the power balance in
[`01`](01-physical-modeling.md) with `P = 0` — solve `½ρCdA·v² + Crr·m·g·cosθ = m·g·sinθ` for `v`.
No source was found comparing real riders' descent speed to this physics limit, but the early/late
apex result implies real speeds sit **below** it on anything twisty and at it only on straight
descents.

## 5.4 What is missing

- Realistic µ for dry/wet asphalt with road tyres.
- Corner-radius estimation from GPS polylines.
- Any validated descent-speed model or rider-skill parameterisation.
- Effect of line choice on distance travelled (a real, if small, term on a twisty course).

## Sources

- Rose N. *Bicycle accident reconstruction: bicyclist braking capabilities and limits* (blog,
  reporting controlled measurements).
  <https://www.nathanarose.com/blog/bicycle-accident-reconstruction-bicyclist-braking-capabilities-and-limits>
- Zignoli A, et al. *Prediction of pacing and cornering strategies during cycling individual time
  trials with optimal control.* Sports Engineering, 2020.
  <https://link.springer.com/article/10.1007/s12283-020-00326-x>
- Zignoli A, Fruet D. Sports Engineering, 2022 — drone-tracked descents vs optimal control.
  <https://link.springer.com/article/10.1007/s12283-022-00386-1> ·
  code: <https://github.com/andreazignoli/drone_footage>
