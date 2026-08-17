# Layer 4 — Behavioural / tactical modeling: pacing as optimal control

> Evidence status: **strong.** The framing (optimal control with W′ as a state variable) and the
> headline magnitudes are verified 3-0. Four details that the automated pass voted down have since
> been **confirmed verbatim by reading the source PDFs directly** — see §4.6.
>
> ⚠ **Two corrections were made after reading the PDFs** — a wrong author attribution, and a
> materially wrong scope claim about Sundström's courses. Both are marked inline.

## 4.1 The formulation

Solo time-trial pacing is a **minimum-time optimal control problem**. The cyclist is a point mass
(de Jong, Fokkink, Olsder & Schwab, *The individual time trial as an optimal control problem*,
Proc IMechE Part P, 2017; 231:200–206 — [`dejong2017.pdf`](dejong2017.pdf)):

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

The three power levels map onto **three stages of velocity** (paper's own wording):

- *initial stage of peak power*, when `v` increases above `v_CP`;
- *middle stage of singular power*, when `v` is **constant**;
- *final stage of critical power*, when `v` decreases but remains above `v_CP`.

The authors note the human consequence dryly: *"this is entirely counter to human psychology. Any
athlete will go all out once the finish line gets close. However, cold mathematical logic dictates
that this is excess power, which should have been used earlier."*

**Stated limitations of this model** (all from the paper's own conclusions):

- The Monod–Scherrer reserve **cannot be recharged** below CP: *"In our model, we do not allow for
  recovery."* A simplification relative to the W′bal recovery models in
  [`02`](02-physiological-modeling.md); §4.3 fixes it.
- *"Our analysis only applies to relatively short time trials."*
- **Cornering is not solved** — bend speeds enter as fixed boundary conditions: *"The optimal way to
  round a bend in an individual time trial is important and deserves further study."*
- Wind and slope were held constant in their computations, but *"It is possible to use variable
  wind velocity and slope. The computational effort remains the same."*

## 4.2 How much time does optimal pacing actually save?

Two very different answers, and the difference is the finding.

**Sundström & Bäckström** — optimizing pacing versus constant power:

| Course | No wind | With 5 m/s ambient wind |
|---|---|---|
| 2 km | **5.7 %** | **4.9 %** |
| 100 km | **2.0 %** | **1.4 %** |

**The benefit shrinks by roughly 3× from 2 km to 100 km.** For a long solo ride, pacing
optimisation is worth low single-digit percentages — real, but not transformative.

> ⚠ **Correction after reading the PDF.** This report previously presented these numbers as gains
> from pacing over *variable terrain and wind*. They are not. Verbatim from the methods:
> **"All simulated courses were entirely flat… No course included hills so the vertical course
> profile was completely flat."** The courses varied only in **heading** (straight sections at
> γ = 0° → 180° in 45° increments), so the ambient wind's *relative* direction changed. These are
> **wind-only** pacing gains on flat ground. The terrain result comes from §4.3, not from here.

Their optimal strategies by course type:

- Flat 2 km, with or without wind → **all-out**.
- 100 km without wind → **positive pacing** (power declining over the ride).
- 100 km with variable wind → **positive pacing combined with power varied in parallel with the
  wind conditions**.

This is also the first study to optimise pacing for **continuously variable ambient wind
directions**, rather than piecewise head/tailwind segments.

**Bach, Alexandersen & Lundgaard (2025)**, Sports Engineering 28(1):12 — **the best real-course
numbers in the literature**, and the ones to quote. A 21.3 km real Norwegian ITT:

| Strategy | Time |
|---|---|
| Constant-power benchmark | 1814 s |
| **Pro rider Martin Toft Madsen's actual ride** | **1810 s** |
| Optimised | **1788 s** |

So optimal pacing beat constant power by **26 s (1.4 %)** and beat *an actual professional's
pacing* by **22 s (1.2 %)**. Note what that second number says: **a real pro paces within 0.2 % of
constant power, and within 1.2 % of mathematical optimality.** Elite riders are already very good
at this.

Their **elementary 2 km course** breakdown is the cleanest decomposition available of *where* the
gain comes from:

| Course | Gain |
|---|---|
| Flat | 0.45 % |
| 1 km at constant +10 % | 1.41 % |
| **0.5 km +10 % then 0.5 km −10 %** | **2.84 %** ← largest |
| Flat, 0.5 km 5 m/s tailwind then 0.5 km 5 m/s headwind | 0.44 % |

**The climb-plus-descent case beats the pure climb case by 2×.** The asymmetric descent — where
extra watts buy almost nothing — is precisely where power modulation pays most. This is the
quantitative core of the valley-crossing question.

**Ashtiani, Sreedhara et al. (arXiv:2007.11393)** report a far larger **24 %** on the 2019 Duathlon
National Championship course (~18 km): self-paced 34 min 8 s (2048 s) at 212 W → optimal
25 min 56 s (1556 s) at 240 W, against a CP of 242 W. Only **28 W more average power**.

> ⚠ **Heavily qualify this number.** It is a *simulation* compared against **a single experimental
> ride** (Subject 14, CompuTrainer), not a head-to-head trial. The verification pass found that
> **the authors' own real-world follow-up produced 3 %, and was invalidated by a programming
> error.** The 24 % is inflated by ideal simulated conditions and by the subject finishing with W′
> unspent. Treat **1–3 % as the honest range** for realistic courses, per Bach et al.

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
On the **two-hill** profile all four control modes appear; on a **single ramp** they do not.

⚠ Confidence on the 2 % / 8 % figures is **medium**: single source, single subject's identified
parameters (Subject 14: CP 242 W, AWC 7841 J). They are instances of a qualitative pattern, not
universal constants.

### The modulation rule, with its timing asymmetry

Bach et al. (2025) give the implementable version, and it is not symmetric:

> *"Power outputs are increased on uphill and headwind segments and decreased on downhill and
> tailwind segments… The increase in power output due to a positive change in height gradient may
> be slow and dispersed over several hundred metres. A negative change in height gradient can
> result in a quicker and relatively local drop in power output."*

**Ramp up gradually over several hundred metres; drop off quickly and locally.** A simulator that
steps power discontinuously at gradient changes will be wrong in a specific, visible way.

Confidence **medium**: single 2025 paper, the source hedges ("may", "can"), and **no magnitude in
watts or metres is quantified**. Note also that reading this as "the power increase begins *before*
the climb" is an interpretive step beyond the quoted text — the paper says dispersed, not
anticipatory.

Sundström & Bäckström add, for long courses with wind: the optimum is *"a compromise of positive
pacing and variable power distribution in parallel with the variable ambient wind conditions"* — a
superposition, not an either/or.

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
| Sundström & Bäckström | MATLAB, three coupled sub-models (mechanical locomotion + Margaria–Morton–Sundström bioenergetics), Runge–Kutta–Fehlberg integration, optimised with the **Method of Moving Asymptotes (MMA)** — gradient-based NLP, 325 simulations per iteration |
| Ashtiani/Sreedhara et al. | **Dynamic programming on a 2-state grid** (velocity `v`, anaerobic energy `w`) |
| de Jong et al. | **Analytical** via Pontryagin's maximum principle; singular power computed numerically |
| Zignoli 2020 | **Indirect** method via the PINS solver, **0.5 m node spacing**, cost = weighted sum of race time + steering-rate + power-rate; Maple for symbolic manipulation |

**Two architectural details worth stealing**, both from Sundström & Bäckström:

- They transform the motion ODE to make **distance the independent variable** rather than time,
  *"so that the numerical solver can stop at a predetermined distance"*. For a GPX-driven simulator
  whose route is defined by distance, this is the more natural integration domain.
- Their rolling-resistance term includes a **vertical-curvature** contribution,
  `F_RR = C_RR · m · (g·cos α + v²/R_v)` where `R_v = [1+(y′)²]^{3/2}/y″` — the extra normal force
  in a compression. Absent from Martin et al., and a genuine refinement on undulating terrain.
  Their bearing term `F_BR = b₁ + b₂·v` matches Dahmen's `β₀ + β₁·v`.

Sundström's bioenergetic model is the **four-compartment Margaria–Morton–Sundström hydraulic
model** (FAT / CHO / anaerobic-alactic / lactate compartments, Hagen–Poiseuille flow between them),
with a Hill force–velocity constraint applied at the very start. Note that this is precisely the
*hydraulic* model class that [`02`](02-physiological-modeling.md) reports as **outperforming W′bal**
on intermittent recovery kinetics — the two findings corroborate each other from opposite
directions.

**DP grid, if you ever implement one** (Ashtiani et al.): distance `Δs = 10 m`; velocity quantized
to **300 nodes** over [1, 20] m/s; anaerobic energy `w` to **600 nodes** over [0, AWC]; control
quantized to just the four PMP modes; plus a control-change regularization term to suppress
chattering.

**Runtime and robustness**: an 18 km route took **53 min 45 s on a 3.2 GHz Core i5 / 12 GB RAM** —
*with* the four-mode reduction; without it, the authors estimate "several hours" (an unmeasured
estimate). This is emphatically **not** a real-time computation.

**An alternative to carrying W′ at all**: Bach et al. impose an upper bound on **Normalized Power
as a 4-norm**, `NP = (1/T ∫₀ᵀ P⁴ dt)^{1/4}`, with **no W′ state whatsoever**. Cheaper and simpler
— but note two things: it omits Coggan's 30 s rolling average so it will *not* reproduce head-unit
NP values, and an aggregate smoothness bound **cannot forbid a physiologically impossible
instantaneous spike** the way a W′bal state can. No source compares the two formulations
head-to-head; which better matches real riders on rolling terrain is an open question.

**No open-source code was released by any of these papers** in the verified material.

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

Note the 32 % → 18 % and 50 % → 25 % drop between the calibration set and the independent set: that
is itself an **overfitting signal**.

Two further validation facts that should temper any claim built on this layer:

- **Every quantitative time-saving figure in §4.2 is model-internal** — an optimizer's output
  against its own forward model, compared to a constant-power or self-paced baseline. **None is a
  measured field gain.**
- **The one real-world optimal-pacing trial that was attempted returned 3 % and was invalidated by
  a programming error.**

The honest reading: **optimal-pacing models are prescriptive tools for what a rider *should* do,
not descriptive models of what riders *do*.** For vcyclist — whose goal is a *realistic simulated
ride*, not an optimal one — this is the central caveat of the whole layer. Simulating the optimal
strategy would produce a ride no human would recognise. And per Bach et al., a real professional is
already within **1.2 %** of the optimum, so the "realistic" and "optimal" traces are much closer
together than the 24 % headline suggests.

Parameter set for a pro male ITT rider from that paper (verified only 2-1): total mass 77 kg,
frontal area 0.35 m², c_rr 0.0035, **CP 440 W, P_max 1870 W, W′ 22 000 J**.

Both sets are now confirmed, and they legitimately **differ because they are tuned to different
populations** — not because either is wrong:

| Parameter | Zignoli & Biral 2020 (tuned to 15 Giro d'Italia pros) | Zignoli 2020 corners paper (generic "average professional") |
|---|---|---|
| Total mass | 77 kg | 79 kg (69 body + 10 bike) |
| Frontal area | 0.35 m² | 0.35 m² |
| C_rr | 0.0035 | 0.0035 (Burke) |
| **CP** | **440 W** | **386 W** |
| P_max | 1870 W | 1870 W |
| **Anaerobic capacity** | **22 000 J** | **27 000 J** |

Zignoli & Biral additionally impose a **power slew-rate limit of 50 W/s** — a detail worth copying,
since it prevents instantaneous power steps. (Caveat flagged by verification: that bound is applied
to *normalised* power ∈ [−1,1], so the stated unit is internally inconsistent. The authors also
note that expenditure and recovery share the same rate, *"an assumption that might limit the
applicability of the model"*.)

## 4.6 Disputed claims — ALL RESOLVED after reading the source PDFs

Four claims from this layer were **voted down by the adversarial verification pass**, despite their
supporting quotes appearing verbatim in the sources. Each was then re-checked by fetching the
source directly. **The refutations turned out to be an artefact of source access, not evidence.**

**Diagnosed mechanism**: `journals.sagepub.com` returns only the site navigation shell to automated
fetch — no title, no abstract, no body. The verifier agents received an empty page and, following
the harness instruction to *"default to refuted if uncertain"*, killed the claims. The vote was
about reachability, not about truth.

| Claim | Original vote | Status |
|---|---|---|
| The PMP solution is restricted to **exactly four power modes**: `u_max`, coasting, CP, and the singular constant-velocity mode (bang-singular-bang) | 0-3 | ✅ **CONFIRMED** — verbatim in the open-access abstract of [arXiv:2007.11393](https://arxiv.org/abs/2007.11393): *"we show that the cyclist's optimal power in a time-trial is limited to only four modes of all-out, coasting, pedaling at a critical power, or constant speed (bang-singular-bang)."* |
| The switching law is a threshold γ = −c₃·λ₃ **crossable only once**, giving a monotone three-phase strategy | 0-3 | ✅ **CONFIRMED** — de Jong p.2: *"We will show that it is optimal to switch back from peak to critical power and to cross the critical level at γ only once."* Proved as Theorem 1, p.5: *"The critical level γ can only be crossed once… Therefore, the power crosses the critical level exactly once."* |
| Worked 5 km example with CP 300 W, u_max 800 W, W′ 20 kJ, CdA 0.217, Cr 0.005, c₁ = 0.128, c₂ = 3.924, c₃ = 78 | 0-3 | ✅ **CONFIRMED** — de Jong p.5, verbatim, including *"c₁ = 0.5·C_dA·ρ, where we set the product of the drag coefficient and the frontal area equal to C_dA = 0.217"* and *"c₂ = mg(s + C_R), where we take slope s = 0, C_R = 0.005 and c₃ = m = 78."* But see the arithmetic note below. |
| Varying tyre-road friction on a 40 km ITT with 25 % technical sections changes performance time and peak power **but not the optimal pacing strategy** | 0-3 | ✅ **CONFIRMED** — Zignoli 2020 abstract, verbatim. Table 3 confirms numerically: average power 392–394 W in every race, dry or wet. |

Also confirmed: the claim that reached only one valid vote — *time lost in slow technical sections
cannot be regained on subsequent fast straights, even though they restore anaerobic stores* — is
verbatim in the Zignoli 2020 abstract. **Corners are not free recovery.**

**Every single disputed claim in this layer survived.** The automated verification pass was wrong
on all five, in the same direction, for the same reason: unreachable sources scored as refutations.
That is a systematic failure mode of the harness, not a property of the evidence — see
[`README`](README.md#a-note-on-the-verification-method).

### Correction to the 5 km example

The paper's own numbers are **not internally consistent**:

| Coefficient | Paper | Recomputed from the paper's stated inputs |
|---|---|---|
| `c₁ = ½ρ·CdA` | 0.128 | ½ · 1.18 · 0.217 = **0.1280** ✓ (implies ρ = 1.18) |
| `c₃ = m` | 78 | — |
| `c₂ = m·g·(s + C_R)` | 3.924 | 78 · 9.81 · 0.005 = **3.826** ✗ |

`c₂ = 3.924` requires either **m = 80 kg** (80 · 9.81 · 0.005 = 3.924 exactly) or g = 10.06 m/s².
Since the paper states `c₃ = m = 78`, the example mixes two masses. This is an error **in the
published paper**, not in the extraction. Don't lift these as a coherent parameter set.

I also previously reported *"~50 s at singular power"* — **that was wrong**. The paper says the
rider *"sustains the maximum power level for 10 s, reaching v_sing of 13 m/s… In the final minute,
he switches back to critical power and the velocity decreases to v_CP of 12 m/s."* On a ~400 s
5 km trial that is roughly **10 s peak → ~330 s singular → final ~60 s at CP**, not 50 s of
singular. Figure 3's caption adds that mathematically the rider ends by coasting, *"In reality, a
rider will of course never do this, but will speed up when approaching the finish. The
psychological effect of reaching the finish is not included in our power model."*

## Sources

- Sundström D, Bäckström M. *Optimization of pacing strategies for variable wind conditions in road
  cycling.* Proc IMechE Part P: J Sports Engineering and Technology, 2017.
  DOI 10.1177/1754337117700550. → [`10.1177@1754337117700550.pdf`](10.1177@1754337117700550.pdf)
- de Jong J, Fokkink R, Olsder GJ, Schwab AL. *The individual time trial as an optimal control
  problem.* Proc IMechE Part P, 2017; 231:200–206. DOI 10.1177/1754337117705057.
  → [`dejong2017.pdf`](dejong2017.pdf)
- Ashtiani F, Sreedhara VSM, et al. *Optimal pacing of a cyclist in a time trial based on
  experimentally calibrated models of fatigue and recovery.* arXiv:2007.11393.
  <https://arxiv.org/pdf/2007.11393>
- Bach S, Alexandersen J, Lundgaard S. Sports Engineering 28(1):12, 2025 — **the best real-course
  numbers** (21.3 km Norwegian ITT vs a pro's actual ride; elementary-course decomposition;
  FEM/adjoint/MMA; CC-BY). <https://link.springer.com/article/10.1007/s12283-025-00493-9>
- Zignoli A, Biral F. *Prediction of pacing and cornering strategies during cycling individual time
  trials with optimal control.* Sports Engineering 23:13, 2020.
  <https://link.springer.com/article/10.1007/s12283-020-00326-x>

> ⚠ **Do not use** Sundström, Carlsson & Tinnsten (2014), *Comparing bioenergetic models for the
> optimisation of pacing strategy in road cycling*, Sports Eng 17(4):207–215
> (DOI 10.1007/s12283-014-0156-0) for time-saving figures. Three quantitative claims drawn from it
> — the 6.6 s / 3.1 s savings, matched mean power (469.7 / 469.7 / 469.1 W), and the speed-variance
> figures — were **refuted in verification** (votes 1-2, 0-3, 0-3). Its 2 km breakaway scenario
> with no wind and no turns is in any case largely irrelevant to variable terrain. (It is also
> sometimes mis-attributed to Dahmen & Saupe; it is not theirs.)
- Zignoli A. *Influence of corners and road conditions on cycling individual time trial performance
  and 'optimal' pacing strategy: A simulation study.*
  <https://www.researchgate.net/publication/346523215_Influence_of_corners_and_road_conditions_on_cycling_individual_time_trial_performance_and_'optimal'_pacing_strategy_A_simulation_study>
