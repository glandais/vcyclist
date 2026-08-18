# Solo-rider cycling simulation — research report

State of the art in modeling a **single cyclist riding alone** (no drafting, no peloton, no group
dynamics), for a physics-based simulator that turns a GPX route into a realistic simulated ride.

Produced by two adversarially-verified deep-research passes (210 agents, 46 sources fetched,
208 claims extracted, 50 verified by 3-vote refutation). **Every chapter states its own evidence
grade.** Claims that were actively refuted are listed rather than silently dropped, so nobody
re-adds them from memory.

## Local source PDFs

Three full-text papers sit alongside this report and were **read directly**, which is why chapters
4 and 5 carry a higher evidence grade than the automated passes alone could support:

| File | Paper |
|---|---|
| [`dejong2017.pdf`](dejong2017.pdf) | de Jong, Fokkink, Olsder & Schwab (2017), *The individual time trial as an optimal control problem* |
| [`zignoli2020.pdf`](zignoli2020.pdf) | Zignoli (2020), *Influence of corners and road conditions on cycling ITT performance and 'optimal' pacing strategy* |
| [`10.1177@1754337117700550.pdf`](10.1177@1754337117700550.pdf) | Sundström & Bäckström (2017), *Optimization of pacing strategies for variable wind conditions in road cycling* |

⚠ These are publisher PDFs (SAGE / IMechE). Consider whether they should be committed to a public
repository, or kept locally and git-ignored, before pushing.

## Chapters

| # | Chapter | Evidence grade |
|---|---|---|
| 1 | [Physical modeling](01-physical-modeling.md) — power balance, parameters, air density, wind & yaw | **Strong** — peer-reviewed, 3-0 verified |
| 1b | [Cornering, braking, descending](05-cornering-braking-descending.md) | **Strong** — upgraded after reading `zignoli2020.pdf` |
| 2 | [Physiological — fatigue](02-physiological-modeling.md) — CP/W′, durability | **Strong** |
| 2b | [Physiological — fuelling & thermal](02b-fuelling-and-thermal.md) — glycogen, CHO, hydration, heat | ⚠ **Extracted, not verified** |
| 3 | [Mental modeling](03-mental-modeling.md) — RPE, Hazard Score | **Thin but solid** — 2 constructs survived, 4 refuted |
| 4 | [Behavioural / tactical](04-behavioral-modeling.md) — pacing as optimal control | **Strong** — all disputed claims resolved from PDFs |
| 5 | [Implementations & validation gaps](06-implementations-and-validation.md) — GoldenCheetah, Best Bike Split, Zwift | **Mixed** — source code read directly, vendor claims flagged |
| 6 | [What this means for vcyclist](07-vcyclist-implementation-notes.md) | Actionable synthesis |

Tracking surface: [`improvements-ledger.md`](../ledgers/improvements-ledger.md) — one ID'd row per suggested
improvement, scored against the code as it stands (applied / recommended / deferred / rejected, with
the numbers behind each verdict).

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

**The tactical layer is real but much smaller than it is usually sold as.** On realistic course
lengths, optimal pacing is worth **1–3 %**: on a 21.3 km Norwegian ITT it beat constant power by
**1.4 %** and beat *an actual professional's ride* by **1.2 %**. Decomposed on 2 km test courses,
the gain is **0.45 % flat, 1.41 % on a pure climb, and 2.84 % on a climb-plus-descent** — the
asymmetric descent, where watts buy almost nothing, is where modulation pays most. That is the
quantitative answer to the valley-crossing question. The modulation rule is asymmetric in time too:
**ramp power up gradually over several hundred metres into a climb, but drop it quickly and locally
on a descent.** On rolling terrain the strategy is *anticipatory* — spend W′ before a descent,
recover ~8 % through it, spend that on the next climb.

The optimal solution is **bang-singular-bang**: the Hamiltonian is linear in power, so optimal power
takes only four values (`u_max`, the constant-speed singular power, CP, and coasting). But the best
optimal-control ITT model in the literature matches real professional riders' velocity for only
**18–32 % of course duration**, every published time saving is **model-internal rather than a
measured field gain**, and the one real-world trial attempted returned 3 % and was **invalidated by
a programming error**. **These models are prescriptive, not descriptive.**

**Cornering is now well specified too**, after direct reading of Zignoli (2020):
`v_max ≅ √(µgR)` with **µ = 0.9 dry and 0.36 wet** for road tyres — wet grip is only 40 % of dry,
a **1.58× cut in cornering speed**. `R` is the radius of the *rider's trajectory*, not the road's.
Wet conditions cost **1.8–3.4 % over 40 km when technical sections are present and 0–0.5 % when
they are not**, and road conditions change performance time and peak power but **not** the pacing
strategy — so corner modelling can be decoupled from the pacing layer.

**Where the field is still weakest** is exactly where a GPX simulator needs it most: no published
method for estimating corner radius from a **GPS polyline** (Zignoli generated courses from exact
clothoids), and no rider-skill parameterisation of how far below `√(µgR)` real riders corner. Real
descenders ride a **late apex** where the physics-optimal line is an **early apex** — so a simulator
using optimal lines will overestimate descent speed.

## A note on the verification method

The adversarial harness instructs verifiers to *default to refuted when uncertain*. Publishers that
block automated fetch (`journals.sagepub.com` returns only its navigation shell) therefore produce
**false refutations indistinguishable from real ones**. When the three source PDFs were supplied
and read directly, **all five disputed tactical/cornering claims turned out to be correct**,
verbatim — a 5-for-5 systematic failure in one direction.

Two lessons carried into this report: *unreachable* is now recorded separately from *refuted*, and
no claim is discarded on a vote alone when its source is inaccessible. The refuted list below is
limited to claims whose sources **were** successfully read.

## Refuted claims — do not re-introduce

Killed by adversarial verification, with sources that were readable. Listed because each is
plausible enough to be re-derived from memory.

| Claim | Vote |
|---|---|
| Road-gradient measurement error, not the physics model, dominates residual error | 1-2 |
| CP declines −0.06 W/kg (high-intensity) vs −0.007 W/kg (moderate); W′ −3.02 kJ after 2000 kJ | 0-3 |
| Summated Hazard Score correlates r = 0.88 with session RPE | 0-3 |
| RPE rate-of-rise predicts time to exhaustion | 1-2 |
| `RPE(t) = RPE₀ + k·t` — RPE linear in *elapsed time* with temperature-set slope | 0-3 |

## Corrections made after reading the PDFs

| What | Correction |
|---|---|
| Attribution | The optimal-control ITT paper is **de Jong, Fokkink, Olsder & Schwab (2017)** — earlier drafts credited "Wolpert / Boswell" |
| Scope | Sundström & Bäckström's 1.4–5.7 % time gains are on **entirely flat courses** — they are wind-only gains, not terrain gains |
| Detail | The 5 km worked example holds singular power for **~330 s**, not ~50 s |
| Source error | That example is internally inconsistent **in the published paper**: `c₂ = 3.924` requires m = 80 kg while `c₃ = m = 78` |
| Parameters | Two Zignoli papers give **different** "average pro" parameter sets (CP 386 vs 440 W, W′ 27 vs 22 kJ) |
| Over-reading | The friction-ellipse `a_max = 9.81 m/s²` figures are *maximal accelerations*, not "µ = 1.0" |

## Known gaps in this report

- **Gross mechanical efficiency** (~20–25 %) and the kJ → kcal conversion — the bridge between the
  mechanical and fuelling layers. Well-established textbook material; a gap in *this report*, not
  in the field.
- **Cardiac drift** — no magnitude obtained.
- Modern **90–120 g/h** carbohydrate protocols and gut training.
- Any complete **thermoregulation model** (two-node Gagge or equivalent) for cyclists.
- **MyWindsock, TrainerRoad, intervals.icu** and `aul12/BikeSimulation` — searched, no extractable
  claims.
- Corner radius from a **GPS polyline**, and rider-skill margin below `√(µgR)`.

*(µ for dry/wet asphalt was listed here and is now closed — see
[`05 §5.1`](05-cornering-braking-descending.md#friction-coefficients--the-gap-is-now-closed).)*

**Status of the two original caveats:**

| Caveat | Status |
|---|---|
| Chapters 4–5 had claims refuted despite verbatim quotes | ✅ **Closed.** All five resolved — see the note above. |
| Chapter 2b (fuelling/thermal) unverified after a rate limit | ⚠ **Still open.** The resumed pass completed cleanly (107/107 agents, 0 errors) but its top-25 claim-selection prioritised the tactical layer, so **GAP C (fuelling, thermoregulation, hydration, efficiency) and GAP D (existing implementations) again produced zero surviving claims.** Not a negative finding — those gaps simply were not researched to completion, and need a dedicated pass of their own. |

One process limit to record: by the resumed run, the session's **WebSearch budget was exhausted
(200/200)**, so most verifications rested on direct primary-source retrieval (Crossref, arXiv,
DiVA, Europe PMC) **without an independent adversarial counter-literature sweep**. In that run,
*"no contradicting source found"* means *"no counter-search was run"* — not *"a counter-search came
back clean"*.
