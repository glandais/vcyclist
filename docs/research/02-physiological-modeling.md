# Layer 2 — Physiological modeling: fatigue

> Evidence status: **strong for the CP/W′ core and for durability.** The fuelling and thermal
> half of this layer is covered in [`02b-fuelling-and-thermal.md`](02b-fuelling-and-thermal.md).

## 2.1 Critical Power and W′

The two-parameter hyperbolic model — `P = W′/t + CP` — splits a rider into a sustainable power
(`CP`, W) and a finite above-CP work reserve (`W′`, J). For a simulator this is the natural state
variable: `W′bal` is a fuel gauge that depletes above CP and refills below it.

### The differential form (W′BAL-ODE) — **use this one**

```
P ≥ CP :  dW′bal/dt = −(P − CP)

P < CP :  W′bal(t_b) = W′₀ − (W′₀ − W′bal(t_a)) · exp( −(CP − P)(t_b − t_a) / W′₀ )
```

No fitted time constant is required — the apparent τ falls out as `W′₀ / D_CP`, where
`D_CP = CP − P` during recovery. The exponential expression is exact only over an interval of
constant `P`, which is precisely how it is applied per discrete time step. That makes it a
drop-in for a 1 Hz simulation loop.

### The integral form (W′BAL-INT) — legacy, and its own author says so

```
τ_W′ = 546 · exp(−0.01 · D_CP) + 316          seconds
```

(Skiba, Chidnok, Vanhatalo & Jones, MSSE 44(8):1526–32, 2012; `D_CP` = CP minus recovery power.)
Reproduced with identical constants in independent implementations, including the GoldenCheetah
lineage.

**Skiba & Clarke themselves (IJSPP 16(11):1561–1572, 2021) — a methodological review by the
originator — argue the integral form is unsuitable for continuous work:**

- Its ongoing-recovery (convolution) assumption makes it predict a **longer** time to exhaustion
  than the plain 2-parameter CP model with identical CP and W′ — described as
  **"theoretically untenable"**.
- It should be **restricted to short bursts above CP** (time-trial, triathlon).

### The two forms genuinely diverge

On Ferguson et al.'s data (CP = 213 W, W′ = 21.6 kJ):

| | τ |
|---|---|
| ODE (implied) | **112 s** |
| Integral (exponential fit) | **336 s** |

Threefold faster recovery. In a 60 s work / 30 s recovery protocol the ODE predicts exhaustion
**~300 s sooner**. This is not a rounding difference — it changes what a simulated ride looks
like.

> For a continuous, variable-terrain GPX ride, **implement W′BAL-ODE**.

### Successors — expose τ as configuration, don't hard-code Skiba's constants

The field is actively revising this:

- **Bartram et al.**, IJSPP 13(6):724, 2018 — SKIBA2 *underestimates* W′ recovery rate in elite
  cyclists; substitute `τ_W′ = 2287.2 · D_CP^(−0.688)`.
- **Caen et al.** and **Chorley et al.** — bi-exponential recovery kinetics.
- **Sreedhara et al.**, arXiv:2108.04510 — a **hydraulic (three-component) model outperforms
  work-balance models** on intermittent recovery kinetics.

Practical guidance: implement W′BAL-ODE, expose τ so Bartram's elite-calibrated form can be
swapped in, and treat any `W′bal` output as an approximation of a mechanism the field is in the
middle of replacing.

## 2.2 Durability — intensity-weighted, not kJ-weighted

The intuitive metric ("power drops after N kJ") is **wrong**, and the systematic review says so
explicitly.

Systematic review of 21 studies, Eur J Appl Physiol 2025 (doi 10.1007/s00421-025-05885-0):

| Prior work | Work required | Power decline |
|---|---|---|
| **above CP** | only **2.5–15 kJ/kg** | **10–20 %** |
| **below CP** | comparable or larger volumes | **< 5 %** |

Primary study behind the contrast — Spragg et al., Eur J Sport Sci 2024 (doi 10.1002/ejsc.12077),
n = 14 professional cyclists, CP 5.3 ± 0.21 W/kg. **Work-matched at ~2000 kJ**, 1-second peak
power fell:

- **−1.57 W/kg** after high-intensity prior work (105–110 % CP)
- **−0.56 W/kg** after moderate prior work (~70 % CP)

So a durability model should accumulate a **fatigue dose weighted by intensity relative to CP**,
not raw kJ. The natural implementation is to integrate work performed above CP separately, and
decay the power-duration curve (or CP itself, or `W′₀`) against that quantity.

Qualifications, all of them real:

- The 2.5–15 kJ/kg is **work above CP**, not total ride kJ.
- The "< 5 %" is a narrative generalisation over three cited studies, with **no pooled effect size**.
- Decrements are strongly **duration-dependent**: largest at the shortest efforts (e.g. −53.8 % at
  5 s after 60 kJ/kg), while Spragg found **no effect on a 12-min TT**. A durability model that
  degrades sustained power as much as sprint power is overfitting the headline.
- Population is **male professional cyclists**.
- Evans et al. 2025 (doi 10.1002/ejsc.70039) found no moderate-vs-heavy difference at 15 kJ/kg —
  but both arms are sub-CP, so it does not contradict the sub-CP/supra-CP contrast.

> ⚠ **Refuted (0–3), do not implement**: the specific figures "CP declines −0.06 W/kg after
> high-intensity vs −0.007 W/kg after moderate protocols; W′ declined 3.02 kJ after 2000 kJ".
> The *direction* of the durability effect is well supported; these particular numbers are not.

## Sources

- Skiba PF, Clarke DC. *The W′ Balance Model: Mathematical and Methodological Considerations.*
  IJSPP 16(11):1561–1572, 2021.
  <https://journals.humankinetics.com/view/journals/ijspp/16/11/article-p1561.xml>
  (HTTP 403 to automated fetch; verified via search-index reproduction of verbatim text plus
  independent corroboration.)
- Skiba PF, Chidnok W, Vanhatalo A, Jones AM. MSSE 44(8):1526–32, 2012 — origin of the τ formula.
- Bartram JC, Thewlis D, Martin DT, Norton KI. IJSPP 13(6):724, 2018.
  <https://journals.humankinetics.com/view/journals/ijspp/13/6/article-p724.xml>
- Sreedhara VSM, et al. arXiv:2108.04510 — hydraulic vs work-balance models.
  <https://arxiv.org/abs/2108.04510>
- Systematic review of durability, Eur J Appl Physiol 2025.
  <https://pmc.ncbi.nlm.nih.gov/articles/PMC12881052/>
- Spragg J, et al. Eur J Sport Sci 2024. <https://pmc.ncbi.nlm.nih.gov/articles/PMC11235642/>
- Comparison of W′balance algorithms (blog, corroborating implementation constants).
  <https://medium.com/critical-powers/comparison-of-wbalance-algorithms-8838173e2c15>
