# Solo-rider cycling simulation — research report

State of the art in modeling a **single cyclist riding alone** (no drafting, no peloton, no group
dynamics), for a physics-based simulator that turns a GPX route into a realistic simulated ride.

Produced by two adversarially-verified deep-research passes (210 agents, 46 sources fetched,
208 claims extracted, 50 verified by 3-vote refutation). **Every chapter states its own evidence
grade.** Claims that were actively refuted are listed rather than silently dropped, so nobody
re-adds them from memory.

## Chapters

| # | Chapter | Evidence grade |
|---|---|---|
| 1 | [Physical modeling](01-physical-modeling.md) — power balance, parameters, air density, wind & yaw | **Strong** — peer-reviewed, 3-0 verified |
| 1b | [Cornering, braking, descending](05-cornering-braking-descending.md) | **Partial** — braking measured, µ disputed |
| 2 | [Physiological — fatigue](02-physiological-modeling.md) — CP/W′, durability | **Strong** |
| 2b | [Physiological — fuelling & thermal](02b-fuelling-and-thermal.md) — glycogen, CHO, hydration, heat | ⚠ **Extracted, not verified** |
| 3 | [Mental modeling](03-mental-modeling.md) — RPE, Hazard Score | **Thin but solid** — 2 constructs survived, 4 refuted |
| 4 | [Behavioural / tactical](04-behavioral-modeling.md) — pacing as optimal control | **Mixed** — framing verified, details disputed |
| 5 | [Implementations & validation gaps](06-implementations-and-validation.md) — GoldenCheetah, Best Bike Split, Zwift | **Mixed** — source code read directly, vendor claims flagged |
| 6 | [What this means for vcyclist](07-vcyclist-implementation-notes.md) | Actionable synthesis |

## Executive summary

**The mechanical layer is settled and directly implementable.** Martin et al. (1998) — aero (with a
spoke-rotation drag term and yaw-interpolated CdA), rolling, bearing, potential- and kinetic-energy
terms, divided by drivetrain efficiency — reproduces SRM power to **R² = 0.97, SEM 2.7 W** on flat
road. Dahmen et al. (2011) show that evaluating the *same* balance as an ODE **at every time step**
rather than at steady state accounts for **98.9–99.6 %** of measured variation on real courses.
Two complete, independent parameter sets are published. *This directly endorses vcyclist's existing
time-stepping architecture.*

**The physiological layer has a clear implementable core** in Critical Power / W′. Use the
**differential W′bal (ODE) form**, not the integral form — Skiba himself calls the integral form
"theoretically untenable" for continuous severe-intensity work, and the two diverge by ~300 s in
predicted time to exhaustion. Durability is **intensity-weighted, not kJ-weighted**: work above CP
drives 10–20 % power decline after only 2.5–15 kJ/kg, versus < 5 % for larger sub-CP volumes.

**The mental layer offers exactly two implementable constructs**: RPE rising linearly with
*fraction of route completed* (not with time — that version was refuted), and the **Hazard Score**
= RPE × fraction remaining, with pace-change bands < 1.5 / 1–3 / > 3. Everything else in the
psychobiological literature is descriptive.

**The tactical layer is real but oversold, and its central caveat is the report's most important
finding.** Optimal pacing beats *constant power* by only **2 % on a 100 km course** (5.7 % at
2 km); it beats *a real human's self-pacing* by **24 %**, achieved with just 28 W more average
power — the gain is placement, not effort. The optimal strategy is bang-singular-bang, and on
rolling terrain it is *anticipatory*: **spend W′ before a descent**, recover ~8 % through it, spend
that on the next climb. But the best optimal-control ITT model in the literature matches real
professional riders' velocity for only **18–32 % of course duration**. **These models are
prescriptive, not descriptive** — implementing one as a simulated rider's behaviour would produce a
ride no human would recognise.

**Where the field is weakest** is exactly where a GPX simulator needs it most: no verified friction
coefficients for road tyres, no published method for estimating corner radius from a GPS polyline,
and no validated descent-speed model. Real descenders ride a **late apex** where the physics-optimal
line is an **early apex** — so a simulator using optimal lines will overestimate descent speed.

## Refuted claims — do not re-introduce

These were killed by adversarial verification. They are listed because each is plausible enough to
be re-derived from memory.

| Claim | Vote |
|---|---|
| Road-gradient measurement error, not the physics model, dominates residual error | 1-2 |
| CP declines −0.06 W/kg (high-intensity) vs −0.007 W/kg (moderate); W′ −3.02 kJ after 2000 kJ | 0-3 |
| Summated Hazard Score correlates r = 0.88 with session RPE | 0-3 |
| RPE rate-of-rise predicts time to exhaustion | 1-2 |
| `RPE(t) = RPE₀ + k·t` — RPE linear in *elapsed time* with temperature-set slope | 0-3 |
| Cornering constrained by a friction ellipse with µ = 1.0 and 30° max steering | 0-2 |

Four further tactical-layer claims were voted down **despite verbatim quotes appearing in their
sources** — most likely paywall-driven verification failures rather than genuine refutations. They
are reported with their quotes in
[§4.6](04-behavioral-modeling.md#46-disputed-claims--reported-honestly) rather than being buried.

## Known gaps in this report

- **Gross mechanical efficiency** (~20–25 %) and the kJ → kcal conversion — the bridge between the
  mechanical and fuelling layers. Well-established textbook material; a gap in *this report*, not
  in the field.
- **Cardiac drift** — no magnitude obtained.
- Modern **90–120 g/h** carbohydrate protocols and gut training.
- Any complete **thermoregulation model** (two-node Gagge or equivalent) for cyclists.
- Realistic **µ for dry/wet asphalt** with road bicycle tyres.
- **MyWindsock, TrainerRoad, intervals.icu** and `aul12/BikeSimulation` — searched, no extractable
  claims.

The second research pass was **cut short by a session rate limit** during its verification phase,
which is why chapter 2b carries a lower evidence grade and why chapters 4–5 have unresolved votes.
Re-running it would close most of these.
