# Layer 2b — Fuelling, hydration and thermal strain

> **Evidence grade: ⚠ EXTRACTED, NOT ADVERSARIALLY VERIFIED.**
> The second research pass hit a session rate limit during the verification phase. The claims
> below were extracted verbatim from primary sources with quotes, but the 3-vote refutation step
> did not complete for most of them. Treat everything here as *single-source with a supporting
> quote* — a lower bar than [`01`](01-physical-modeling.md), [`02`](02-physiological-modeling.md)
> and [`03`](03-mental-modeling.md). Re-verify before building anything load-bearing on it.

## 2b.1 Substrate use — the crossover concept

The mapping a simulator needs is *intensity → fuel mix*, not a fixed split.

**Brooks & Mercier crossover concept:**

- The crossover point is the power output at which CHO-derived energy overtakes lipid-derived
  energy. Above it, every further increment of power raises the CHO fraction and lowers the lipid
  fraction — a **monotonic intensity-to-substrate-mix mapping**.
- Intensity bands as anchored numerically in the paper: **mild ≤ 45 % VO₂max, moderate 50–55 %,
  hard ≥ 65 %**.
- **Absolute fat oxidation does not rise with intensity.** In Romijn et al.'s highly trained men at
  25 / 65 / 85 % VO₂max, a *threefold* increase in energy expenditure produced no difference in
  total fat oxidation between 85 % and 25 %. **All additional power above moderate intensity is
  fuelled by carbohydrate.**
- At competition intensity (**> 70–75 % of maximum aerobic power**) riders are essentially
  CHO-dependent regardless of training state. → For a race-pace simulator, **glycogen is the
  binding fuel constraint; fat is not.**

**Measured values (exercise-calorimetry database, PMID 35458167):**

| Quantity | Value |
|---|---|
| Fatmax (MFO / LIPOXmax) | **47.1 % VO₂max** (classically quoted 40–50 %) |
| Peak fat oxidation rate | **209.5 mg/min ≈ 0.21 g/min ≈ 12.6 g/h** |
| Crossover point | **55.1 % VO₂max** (wide inter-individual variability) |

**Directly implementable substrate computation** (Péronnet–Massicotte form):

```
CHO oxidation (g/min)   =  4.5850 · VCO₂ − 3.2255 · VO₂
Lipid oxidation (g/min) = −1.7012 · VCO₂ + 1.6946 · VO₂
```

This gives the mapping from metabolic rate and RER to gram-per-minute fuel depletion. In a
simulator, RER is the free parameter you infer from intensity relative to VO₂max.

## 2b.2 Glycogen stores and depletion

- **Post-exhaustion floor**: muscle glycogen falls to **~36 mmol/kg dry weight** immediately after
  exhaustive cycling — a usable numeric floor for a glycogen state variable.
- **Dose-response** (Impey/Areta et al., J Appl Physiol 2018, n = 8 trained men): pre-exercise
  glycogen of **88 / 185 / 278 mmol/kg dw** gave **18 ± 7 / 36 ± 3 / 44 ± 9 min** to exhaustion at
  80 % PPO. Below 300 mmol/kg dw, each further **−100 mmol/kg dw reduces capacity at 80 % PPO by
  20–50 %**.
  - ⚠ The protocol was *intermittent* (8 × 3 min at 80 % PPO, then 1-min efforts to exhaustion),
    not steady-state endurance pace. Don't apply the slope to a tempo ride uncritically.
- **Refuelling conversion**: 3.6 vs 7.6 g/kg CHO over a 6-hour window produced ~**93 mmol/kg dw**
  difference in next-morning muscle glycogen (185 vs 278) — an approximate ingested-CHO →
  stored-glycogen conversion.

**Mechanism of "bonking"** — relevant because it constrains what a naive model gets wrong:

- Glycogen is compartmentalized: intermyofibrillar ~75 % of total, intramyofibrillar and
  subsarcolemmal 5–15 % each.
- After ~1 h exhaustive exercise the **intramyofibrillar pool depletes disproportionately (−90 %)**
  vs −75 % intermyofibrillar and −83 % subsarcolemmal.
- Fatigue is mediated by **impaired excitation-contraction coupling** — the decline in SR Ca²⁺
  release rate correlates with intramyofibrillar glycogen, **not** with whole-cell ATP, and
  persists when global ATP is held constant.
- → **A simulator cannot model bonking as a simple whole-muscle energy shortfall.** The honest
  simplification is a phenomenological one: Brooks & Mercier note glycogen depletion shifts the
  CHO curve leftward and the fat curve rightward, **accompanied by a decrease in sustainable power
  output**. Implement the power decrement directly as a function of the glycogen state variable,
  and label it as a fit, not a mechanism.

## 2b.3 Exogenous carbohydrate intake

Jentjens & Jeukendrup, trained cyclists (VO₂max 62 ± 3), 120 min at 50 % max power (63 ± 2 %
VO₂max):

| Ingested | Peak exogenous CHO oxidation | Sustained (60–120 min mean) |
|---|---|---|
| Glucose 1.2 g/min (72 g/h) | 0.80 ± 0.04 g/min | 0.75 ± 0.04 g/min |
| Glucose 1.8 g/min (108 g/h) | 0.83 ± 0.05 g/min | 0.75 ± 0.04 g/min |
| **Glucose 1.2 + fructose 0.6 g/min (2:1, 108 g/h)** | **1.26 ± 0.07 g/min** | **1.16 ± 0.06 g/min** |

Two hard results:

1. **Glucose alone saturates at ~0.8 g/min (≈48 g/h) oxidized**, regardless of whether 1.2 or
   1.8 g/min is ingested — the SGLT1 intestinal transport ceiling behind the "60 g/h glucose"
   rule. Ingesting more glucose does nothing.
2. **Glucose:fructose 2:1 raises it ~55 %**, to ~1.16–1.26 g/min (**≈70–76 g/h oxidized**).

Use the **sustained** figure (1.16 g/min), not the peak, for a simulator's exogenous fuel supply.

Caveats: measured at a **sub-threshold** intensity (63 % VO₂max) — not valid for hard riding. And
fructose co-ingestion did **not** significantly spare endogenous glycogen in this 2 h protocol
(trend only, p = 0.075), so don't model exogenous CHO as a straight glycogen-sparing term.

> The modern 90–120 g/h recommendations and gut-training literature were **not** reached by this
> research. Do not assume the 2:1 numbers above extrapolate to 120 g/h.

## 2b.4 Hydration — the "2 % dogma" is contested

This is the area where the naive implementation is most likely to be **wrong**.

**Goulet's meta-analysis** (5 studies, 13 effect estimates, 39 subjects):

- Exercise-induced dehydration averaging **2.2 ± 1.0 % body-weight loss produced no measurable
  decrement** in self-paced cycling TT performance: **+0.06 ± 2.72 %, p = 0.94**.
- Dehydration **up to 4 % BW loss does not alter** cycling performance under out-of-door
  (realistic airflow) conditions.
- **Drinking to thirst beat drinking below thirst by +5.2 ± 4.6 % (p = 0.01, 98 % probability of a
  real advantage)**; vs drinking *above* thirst the gain was only +2.4 ± 5.0 % (p = 0.40).
  → Underdrinking costs ~5 %; overdrinking is near-neutral.
- **Exercise intensity and duration explained substantially more variance than dehydration level.**
- Pooled conditions: 26.0 ± 6.7 °C, 61 ± 9 % RH, 68 ± 14 % VO₂max, **86 ± 34 min**. The null
  result is established for moderate heat and 1–2 h rides — **not** for long hot events.

**The counter-position** (a review of blinded studies):

- The classic "2 % impairs performance" literature is **methodologically confounded**: subjects
  were not blinded to hydration status (expectancy effects), and dehydration-induction protocols
  are uncomfortable and unfamiliar, so decrements may reflect discomfort rather than body water.
- But blinded studies still indicate **hypohydration of 2–3 % body mass does decrease endurance
  cycling performance in the heat, when little or no fluid is ingested.**

**Practical synthesis for a simulator**: apply **no power penalty below ~2–3 % body-mass loss**;
apply one above that **only in hot conditions**. Goulet's own recommendation is to program fluid
intake to hold loss in the 2–3 % band, and to treat pre-exercise (starting-state) hypohydration
≥ 3 % as a separate, real impairment. Below 1 h of exercise, dehydration does not impair
performance at all. Pre-exercise fluid loading: **5–10 mL/kg 2 h before** (350–700 mL for 70 kg).

## 2b.5 Heat strain

**The clean, directly implementable result** — 10 trained heat-acclimated cyclists, self-paced
20 km TT:

| Condition | Time | Mean speed | End rectal temp | Sweat rate |
|---|---|---|---|---|
| NEUTRAL 22 °C / 55 % RH | **40.7 ± 1.8 min** | 30.0 ± 4.8 km/h | 38.5 ± 0.4 °C | **1.7 ± 0.5 kg/h** |
| DRY 35 °C / 46 % RH | 43.9 ± 1.7 min (**+7.9 %**) | 28.5 ± 3.5 km/h | 39.1 ± 0.5 °C | 2.5 ± 0.9 kg/h |
| HUMID 30 °C / 90 % RH | 44.6 ± 2.5 min (**+9.6 %**) | 28.5 ± 4.2 km/h | 39.2 ± 0.5 °C | **2.6 ± 0.5 kg/h** |

Four things follow:

1. **A ~8–10 % elapsed-time penalty in heat** is a defensible first-order environmental factor.
2. **Performance loss occurs well below the ~40 °C "critical core temperature"** — this was a
   self-paced (anticipatory) protocol, not time-to-exhaustion. The rider slows *before* reaching
   any thermal limit.
3. Sweat rate needs **~1.7 L/h even in temperate conditions at TT intensity**, rising ~50 % in
   heat. Note this means the 2 % body-mass dehydration threshold is crossed **within a single
   45-minute hard effort** — which is another reason §2b.4's null result matters.
4. Mechanism is **central**: normalised integrated EMG fell progressively in HUMID (below NEUTRAL
   from km 11), i.e. heat reduces sustainable power via reduced muscle recruitment, not peripheral
   failure. HR was elevated and RPE highest in HUMID *despite equal or lower mechanical output* —
   the perceptual penalty a thermal submodel must impose.

**Starting-state sensitivity**: at 35 °C / 60 % RH, a pre-exercise core temperature elevation of
just **0.7 °C (37.69 vs 36.96 °C) shortened time to exhaustion by ~29 %** (538 vs 757 s,
p = 0.028, d = 1.12). Small sample (n = 7), so indicative rather than calibrated — but it shows a
thermal model's *initial condition* matters as much as its dynamics.

## 2b.6 Not covered by this research

- **Cardiac drift** — the source fetched returned no extractable claims. No magnitude for HR drift
  per hour at constant power was obtained.
- **Gross mechanical efficiency** (~20–25 %) and the kJ → kcal conversion feeding fuel depletion —
  no verified source. This is a *significant* gap, because it is the bridge between the mechanical
  layer's kJ output and this layer's gram-per-minute fuel consumption. It is also well-established
  textbook material, so it is a gap in *this report*, not in the field.
- Any complete **thermoregulation model** (two-node Gagge or equivalent) adapted to cyclists. Only
  input-output performance decrements were found, not a heat-balance model.

## Sources

- Brooks GA, Mercier J. *Balance of carbohydrate and lipid utilization during exercise: the
  "crossover" concept.* J Appl Physiol.
  <http://instituteofmotion.com/wp-content/uploads/2021/01/Balance-of-CHO-and-Fat-oxidation-in-exercise.pdf>
- Fat oxidation / crossover database. <https://pubmed.ncbi.nlm.nih.gov/35458167/>
- Jentjens R, Jeukendrup A. Glucose + fructose exogenous oxidation.
  <https://journals.physiology.org/doi/full/10.1152/japplphysiol.00974.2003>
- Impey SG, Areta JL, et al. Pre-exercise glycogen and exercise capacity. J Appl Physiol 2018.
  <https://journals.physiology.org/doi/full/10.1152/japplphysiol.00913.2018>
- Ørtenblad N, Nielsen J, et al. Subcellular glycogen compartments and E-C coupling. J Physiol.
  <https://physoc.onlinelibrary.wiley.com/doi/10.1113/jphysiol.2013.251629>
- Goulet EDB. Dehydration and endurance performance meta-analysis.
  <https://onlinelibrary.wiley.com/doi/10.1111/j.1753-4887.2012.00530.x> and
  <https://pubmed.ncbi.nlm.nih.gov/21454440/>
- Blinded hydration studies review. <https://pubmed.ncbi.nlm.nih.gov/31696453/>
- 20-km TT in neutral / dry / humid heat. <https://pubmed.ncbi.nlm.nih.gov/34833025/>
- Pre-exercise hyperthermia and time to exhaustion.
  <https://www.ncbi.nlm.nih.gov/pmc/articles/PMC11587623/>
