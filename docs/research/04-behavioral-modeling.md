# Layer 4 — Behavioural / tactical modeling: pacing as optimal control

> Evidence status: **mixed but usable.** The framing (optimal control with W′ as a state variable)
> and the headline magnitudes are verified 3-0. Some of the most implementable details were
> *voted down by the verification pass despite appearing verbatim in the source* — see §4.6, which
> reports that honestly rather than hiding it.

## 4.1 The formulation

Solo time-trial pacing is a **minimum-time optimal control problem**. The cyclist is a point mass:

```
u(t) = [ c₁·v(t)² + c₂ + c₃·(dv/dt) ] · v(t)        power required

states:   x  distance
          v  velocity
          a  anaerobic reserve,  da/dt = u(t) − CP
control:  u  power output,  0 ≤ u ≤ u_max
objective: minimise T subject to x(T) = L, a(t) ≥ 0
```

`c₁` collects the aero term, `c₂` the rolling + gravity terms, `c₃` the equivalent mass. **Variable
slope and wind are handled by making `c₂` distance-dependent — at no extra computational cost.**
That is the key structural insight for a GPX-driven simulator: terrain is not a special case, it
is a time-varying coefficient.

The Hamiltonian is **linear in the control `u`**, which is why the solution is
**bang-singular-bang**: the optimal power sits at CP, at a singular value, or at `u_max` — never
somewhere in between by continuous interpolation.

```
H = v + λ₁v + λ₂[uv/c₃ − c₁v²/c₃ − c₂/c₃] + λ₃(u − CP)

u* = CP           if λ₂v < γ
u* = u_singular   if λ₂v = γ
u* = u_max        if λ₂v > γ
```

The singular power **scales with trial duration** — it approaches `u_max` for short trials and
approaches CP for long ones — and **on a flat course the singular arc is constant *velocity*, not
constant power**. That last point is the mathematical statement of the folk rule "hold speed, let
watts vary".

Note this formulation uses a Monod–Scherrer reserve that **cannot be recharged below CP**
(`∫₀ᵀ[u−CP]dt = W`), which is a simplification relative to the W′bal recovery models in
[`02`](02-physiological-modeling.md). Later work (§4.3) fixes that.

## 4.2 How much time does optimal pacing actually save?

Two very different answers, and the difference is the finding.

**Sundström et al.** — optimizing pacing versus constant power:

| Course | No wind | With 5 m/s ambient wind |
|---|---|---|
| 2 km | **5.7 %** | **4.9 %** |
| 100 km | **2.0 %** | **1.4 %** |

**The benefit shrinks by roughly 3× from 2 km to 100 km.** For a long solo ride, pacing
optimisation is worth low single-digit percentages — real, but not transformative.

Their optimal strategies by course type:

- Flat 2 km, with or without wind → **all-out**.
- 100 km without wind → **positive pacing** (power declining over the ride).
- 100 km with variable wind → **positive pacing combined with power varied in parallel with the
  wind conditions**.

This is also the first study to optimise pacing for **continuously variable ambient wind
directions**, rather than piecewise head/tailwind segments.

**Ashtiani, Sreedhara et al. (arXiv:2007.11393)** — a much larger number, from a different
baseline. On the 2019 Duathlon National Championship course (Greenville SC, ~18 km):

- Self-paced: **34 min 8 s** (2048 s) at **212 W** average.
- Optimal: **25 min 56 s** (1556 s) at **240 W** average — a **24 % improvement**.
- The rider's CP was 242 W.

**The gain came from a 28 W increase in average power** — i.e. almost entirely from *consistency
and placement*, not from more total work. The two results are not in conflict: Sundström compares
optimal vs *constant power*; this compares optimal vs *a real human's self-pacing*, which was
substantially below CP and poorly distributed. **The headroom against a real rider is an order of
magnitude larger than the headroom against a well-executed constant-power ride.**

For vcyclist, that is the more relevant comparison — a simulated rider that paces like a human,
not like a controller.

## 4.3 Rolling terrain and valley crossing — the anticipation result

This is the direct answer to the "optimisation of effort when crossing a valley" question.

From the arXiv:2007.11393 dynamic-programming solution:

- **Before a downhill, the controller burns most of the remaining anaerobic energy W′.** The logic:
  W′ spent at low speed uphill buys more time than W′ spent at high speed downhill (where the
  aero term makes watts expensive), and W′ held into a descent is W′ you cannot spend usefully.
- **During the downhill the rider recovers 8 % of AWC**, which is then spent on the following
  climb.
- On a **single ramp** course, only **2 % of AWC is spent accelerating off the start line**, the
  rest reserved for the ascent, with **AWC fully depleted at the top** and CP held to the finish.

So the valley-crossing strategy is: **spend down before the descent, coast/recover through it, and
arrive at the next climb with a partly refilled reserve you then spend on the climb.** The classic
"go hard uphill, ease off downhill" rule is recovered — but the *anticipatory* part (spending
*before* the descent because the reserve would otherwise be wasted) is the non-obvious addition.

**The fatigue/recovery model used** — a switching ODE, more implementable than Skiba's τ because
recovery depends only on instantaneous power:

```
dw/dt = −(P − CP)          when P ≥ CP
dw/dt = −(P_adj − CP)      when P < CP,   with P_adj = a·P + b
```

`P_adj` is a **per-rider linear function of instantaneous power only** — causal and
duration-independent, which makes it trivially streamable in a 1 Hz simulation loop. Identified
parameters for their subject 6: m = 79 kg, CP = 269 W, AWC = 12 030 J, **a = 0.11, b = 237.5 W**,
α = 0.037 1/s, α_c = 0.017 rpm/J, c_max,f = 139 rpm.

## 4.4 Solvers actually used

Three different numerical approaches in the verified literature — note that **none** of them is
GPOPS-II or CasADi/IPOPT collocation:

| Study | Method |
|---|---|
| Sundström et al. | MATLAB, three coupled sub-models (mechanical locomotion + Margaria–Morton–Sundström bioenergetics), optimised with the **Method of Moving Asymptotes (MMA)** — gradient-based NLP |
| Ashtiani/Sreedhara et al. | **Dynamic programming on a 2-state grid** (velocity `v`, anaerobic energy `w`) |
| Zignoli (Sports Eng 2020) | **Indirect (Pontryagin-type)** approach, not direct collocation |

**Runtime and robustness, from the DP implementation**: an 18 km route took **53 min 45 s on a
3.2 GHz Core i5** — and that was *with* the PMP insight restricting the control quantization to
four modes; without it, "several hours". This is emphatically **not** a real-time computation.

Reassuringly for parameter uncertainty: **±5 % errors in CP or AWC increase trial time by only
about 1 %.** The optimum is flat — which is exactly why a heuristic pacer can capture most of the
available gain without solving the full problem.

Sundström's contribution over earlier models was adding **aerobic substrate utilization dynamics,
finite carbohydrate stores, force–velocity relationships and proper efficiency modelling** — the
authors attribute their more detailed long-course pacing solutions specifically to that
bioenergetic detail. That connects layer 4 directly back to [`02b`](02b-fuelling-and-thermal.md):
**on long courses, the pacing optimum is shaped by fuel, not just by W′.**

## 4.5 Validation — this is where it gets uncomfortable

Zignoli's optimal-control ITT model was validated against **professional Giro d'Italia ITT data**:

| Stage | Simulated velocity inside experimental 95 % CI | Power inside 95 % CI |
|---|---|---|
| Rovereto (n = 15, tuning set) | **32 %** of course duration | 50 % |
| Verona (n = 13, test set) | **18 %** of course duration | 25 % |

**A state-of-the-art optimal-control ITT simulator matches real professional riders' velocity for
under a fifth of the course.** The author of the companion corner-conditions paper states plainly
that the predictions are **not experimentally validated** and "more experimental research is
needed".

The honest reading: **optimal-pacing models are prescriptive tools for what a rider *should* do,
not descriptive models of what riders *do*.** For vcyclist — whose goal is a *realistic simulated
ride*, not an optimal one — this is the central caveat of the whole layer. Simulating the optimal
strategy would produce a ride no human would recognise.

Parameter set for a pro male ITT rider from that paper (verified 2-1): total mass 77 kg, frontal
area 0.35 m², c_rr 0.0035, **CP 440 W, P_max 1870 W, W′ 22 000 J**.

## 4.6 Disputed claims — reported honestly

Four claims from this layer were **voted down by the adversarial verification pass**, but their
supporting quotes appear verbatim in the sources. The likely cause is that the verifiers could not
re-access paywalled SAGE/Springer content and defaulted to "refuted" under uncertainty, as the
harness instructs them to. **Treat these as unresolved, not as false:**

| Claim | Vote | Source quote |
|---|---|---|
| The switching law is a single threshold γ = −c₃·λ₃ **crossable only once**, giving a monotone three-phase strategy (all-out start → long singular phase → final phase at CP) with no oscillation | 0-3 | *"The critical level γ can only be crossed once… The rider needs to go all out at the beginning until he reaches a velocity that can be maintained for almost the entire course."* |
| The PMP solution is restricted to **exactly four power modes**: `u_max`, coasting (zero power), CP, and the singular constant-velocity mode | 0-3 | *"we show that the cyclist's optimal power in a time-trial is limited to only four modes of all-out, coasting, pedaling at a critical power, or constant speed (bang-singular-bang)"* |
| Worked 5 km example: CP 300 W, u_max 800 W, W′ 20 kJ, m 78 kg, CdA 0.217, Cr 0.005, flat → c₁ = 0.128, c₂ = 3.924, c₃ = 78; ~10 s at peak reaching 13 m/s, ~50 s singular, final minute at CP finishing at 12 m/s | 0-3 | numeric example reproduced in the extraction |
| Varying tyre-road friction on a 40 km ITT with 25 % technical sections changes performance time and peak power **but not the optimal pacing strategy** — so corner modelling can be decoupled from the pacing layer | 0-3 | *"road conditions can meaningfully affect the final performance time and peak power required, but not the pacing strategy"* |

The "four modes" claim in particular is load-bearing if you ever want to implement a pacing
optimiser, since it is what makes the DP tractable. Verify it against the source directly before
relying on it.

One further claim reached only **1 valid vote** (2 verifiers errored): *time lost in slow technical
sections cannot be regained on subsequent fast straights, even though the slow sections partially
restore W′* — i.e. **corners are not "free recovery"**. Plausible and useful; unconfirmed.

## Sources

- Sundström D, Carlsson P, Tinnsten M. *Comparing bioenergetic models for the optimisation of
  pacing strategy in road cycling.* Sports Engineering / Proc IMechE Part P.
  <https://journals.sagepub.com/doi/abs/10.1177/1754337117700550>
- Wolpert / Boswell et al., optimal control of the individual time trial.
  <https://journals.sagepub.com/doi/10.1177/1754337117705057>
- Ashtiani F, Sreedhara VSM, et al. *Optimal pacing of a cyclist in a time trial based on
  experimentally calibrated models of fatigue and recovery.* arXiv:2007.11393.
  <https://arxiv.org/pdf/2007.11393>
- Zignoli A, et al. *Prediction of pacing and cornering strategies during cycling individual time
  trials with optimal control.* Sports Engineering, 2020.
  <https://link.springer.com/article/10.1007/s12283-020-00326-x>
- Zignoli A. *Influence of corners and road conditions on cycling individual time trial performance
  and 'optimal' pacing strategy: A simulation study.*
  <https://www.researchgate.net/publication/346523215_Influence_of_corners_and_road_conditions_on_cycling_individual_time_trial_performance_and_'optimal'_pacing_strategy_A_simulation_study>
