# Existing implementations, defaults, and validation gaps

> Evidence status: **extracted, largely unverified** (the verification pass was cut short). Vendor
> claims are labelled as such. The GoldenCheetah source-code findings are the most reliable here
> because they were read directly from the repository.

## 6.1 GoldenCheetah — the reference open-source implementation

`src/Metrics/WPrime.cpp` — read directly, so these are facts about shipped code, not claims.

**Hard-coded defaults when athlete zone data is unavailable:**

```
CP     = 250   // W
WPRIME = 20000 // J
```

**Both W′bal forms are implemented and selectable at runtime** via the `GC_WBALFORM` setting:

- **Integral (Skiba)**: `WPrimeIntegrator`, threaded, evaluated as a running convolution
  ```c
  for (int t = 0; t <= end; t++) {
      I += exp(( (double)t / TAU )) * source[t];
      output[t] = exp(-( (double)t / TAU )) * I;
  }
  ```
  on data normalised to 1-second intervals by spline interpolation. Match detection uses 25 s
  smoothing with a 100 J minimum cost (2000 J to be reportable).

- **Differential (Froncioni/Clarke)** — forward iteration at 1 Hz:
  ```c
  if (smoothedValue < CP) W = W + (CP - smoothedValue) * (WPRIME - W) / WPRIME;   // recovery
  else                    W = W + (CP - smoothedValue);                            // depletion
  ```
  Note this recursion is subtly different from the closed-form exponential in
  [`02`](02-physiological-modeling.md#21-critical-power-and-w) — it is the first-order Euler
  discretisation of the same ODE, and it scales recovery by the *remaining deficit*.

**τ is computed per-ride** from the mean power below CP, rounded down to an integer:

```c
TAU = 546.00f * exp(-0.01 * (CP - (totalBelowCP / countBelowCP))) + 316.00f;
```

When all power is at or above CP the `DCP` term drops out, giving `TAU = 546·exp(−0.01·CP) + 316`
— an edge case worth reproducing if you want bit-comparable output.

**Performance engineering worth copying** (Liversedge, 2014): the naive integral form needs a
1200-point lookback per sample (~12 M iterations for a 10 000-sample ride). GoldenCheetah cut this
~20× by threading, integrating only from samples where W′ was actually depleted (~500 of 10 000),
bounding the loop at `3·τ`, and stopping when the W′ increment fell below 10 J. Result: W′bal for
**500 one-hour-plus rides in under 30 s on a dual-core 2009 MacBook Pro.** A later refinement by
Dave Waterworth (Oct 2014) made it single-pass, feasible for real-time head units — and it is a
**numerically negligible** approximation of the integral form (maximum deviation **5.46
nanojoules**).

Two useful cross-checks from the same lineage:

- The 546 / −0.01 / 316 coefficients are **person-independent constants** from Skiba's original
  paper.
- The differential form embeds an **implicit τ of roughly 60–100 s**, versus the **300–400 s**
  observed with the integral formulation — corroborating the divergence documented in
  [`02`](02-physiological-modeling.md) from an entirely independent direction.
- Typical **W′ range: 10–40 kJ**.
- Only the **integral** form is described in that lineage as scientifically validated — which sits
  awkwardly against Skiba & Clarke's own 2021 verdict that it is theoretically untenable for
  continuous work. The field genuinely disagrees with itself here.

Open-source Python implementations of all three algorithms exist in the `athletic_pandas` package.

## 6.2 Best Bike Split

Vendor material only — **no published validation study**.

- Vendor accuracy claim: *"time predictions within seconds of actual, and wattage predictions
  within a couple of watts"* — **when CdA has been calibrated against a past race with known
  finish metrics.** That conditional does most of the work.
- **CdA is not a shipped numeric default**: it is entered via coarse self-assessed position
  categories, direct wind-tunnel measurement, or field-fitting tools.
- The recommended calibration is **manual back-fitting**: pick a past race with full finish
  metrics, iterate CdA until the simulation matches, **adjusting by no more than 0.01 m² per
  step**. That increment is effectively the resolution limit of the method.
- **Crr default range: 0.002–0.007** for road bike tyres — consistent with Kyle's 0.0027–0.0040
  in [`01`](01-physical-modeling.md).
- Rolling resistance is modelled as `F_rr = Crr · m · g` — **speed-independent and without a
  `cos(slope)` term**, the same simplification most ride simulators make and one Martin's original
  does *not*.
- Sensitivity claims: aero drag = 70–90 % of total resistance (~80 % of it the rider); rolling
  resistance = 10–20 % of total power demand at race speeds. Per-tyre rolling wattages at 29 km/h
  under 42.5 kg span **~6.7 W** (Vittoria Corsa Pro Speed TLR tubeless) to **18–22 W+**
  (Continental Gatorskin clincher) — roughly a **3× spread**. In a simulated Ironman bike leg at
  200 W, tyre choice alone shifted predicted finish from **4:59:00 to 5:16:00 (~17 min)**.

That last number is the practical argument for exposing Crr as a first-class parameter.

## 6.3 Zwift

- Avatar speed comes from a physics model driven by **power, body mass and height** — height feeds
  the drag area. So mass and height are the user-supplied parameters that set CdA and the
  gravity/rolling terms.
- The model explicitly includes rolling resistance and aerodynamic drag, with a cubic power-speed
  relationship.
- **Validation via Virtual Tour de France 2020**: relative power (W/kg) explains **77–98 %
  (women) and 84–99 % (men)** of variance in stage results, third-order polynomial fit (e.g.
  mountain stage 5: R² = 0.983 women, 0.990 men).
- But a **"performance-result gap" of 1–23 % depending on stage** remains unexplained by power and
  mass alone — **a useful bound on how accurate any purely physics-driven solo simulation can be
  against real race outcomes.**
- Stated limitation: the power data came from decentralized, uncontrolled trainer setups, so
  calibration reliability is itself a confound.

## 6.4 Not reached by this research

**MyWindsock, TrainerRoad, intervals.icu** and the open-source
[`aul12/BikeSimulation`](https://github.com/aul12/BikeSimulation) were searched for but produced no
extractable claims. Nothing here characterises what they model or what defaults they ship.

The one open-source codebase found with a **validated non-longitudinal** model is
<https://github.com/andreazignoli/drone_footage> (see
[`05`](05-cornering-braking-descending.md)).

## 6.5 The validation gaps, stated plainly

Any claim of realism made by a simulator built on this literature should be qualified by all of
the following:

| Layer | Gap |
|---|---|
| Physical | Martin's validation spans only **7–12 m/s, ~172 ± 15 W, 0.3 % grade**, steady state, ideal surface. Dahmen's dynamic validation is **n = 4 rides, low speed, no descents**. CdA-vs-yaw data stop at **15°** and describe one aero TT position. |
| Physiological | W′bal is reported to be **outperformed by hydraulic (three-component) models** on intermittent recovery kinetics. τ constants are being actively revised (Bartram 2018, Caen, Chorley). Durability numbers come from **male professional cyclists**. |
| Fuelling / thermal | Exogenous CHO oxidation rates measured at **63 % VO₂max only**. The dehydration null result holds for **1–2 h rides in moderate heat**. No cardiac-drift or gross-efficiency figure obtained at all. |
| Mental | Two descriptive constructs, **zero calibrated control laws**. Four plausible quantitative claims were actively refuted. |
| Tactical | The best optimal-control ITT model matches real pro riders' velocity for **18–32 % of course duration**. The corner-conditions study is explicitly **not experimentally validated**. |
| Cornering | **No verified µ values**, no corner-radius-from-GPS method, no descent-speed model. |

The single most important one: **optimal-pacing models are prescriptive, not descriptive.** A
simulator whose goal is a *believable* ride should not implement them as the rider's behaviour.

## Sources

- GoldenCheetah `WPrime.cpp`.
  <https://github.com/GoldenCheetah/GoldenCheetah/blob/master/src/Metrics/WPrime.cpp>
- Liversedge M. *W'bal — its implementation and optimisation* (2014).
  <http://markliversedge.blogspot.com/2014/07/wbal-its-implementation-and-optimisation.html>
- *Comparison of W′balance algorithms.*
  <https://medium.com/critical-powers/comparison-of-wbalance-algorithms-8838173e2c15>
- Best Bike Split / TrainingPeaks — CdA validation procedure.
  <https://www.trainingpeaks.com/blog/how-to-validate-your-drag-coefficient-using-best-bike-split/>
- Best Bike Split — Crr case study. <https://www.bestbikesplit.com/case-study-crr>
- Virtual Tour de France 2020 performance analysis.
  <https://ncbi.nlm.nih.gov/pmc/articles/PMC9136089>
