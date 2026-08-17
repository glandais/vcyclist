# Layer 3 — Mental / psychological modeling

> Evidence status: **thin, and deliberately so.** This is the layer where the literature is
> richest in theory and poorest in implementable numbers. Two constructs survived adversarial
> verification. Four plausible-sounding claims were **refuted** — they are listed at the bottom
> precisely so nobody re-adds them from memory.

## 3.1 What survived: the Hazard Score

de Koning, Foster, Bakkum et al., *Regulation of Pacing Strategy during Athletic Competition*,
PLoS ONE 6(1):e15863 (2011).

```
HS(t) = RPE(t) × fraction_of_distance_remaining
```

with published behavioural bands:

| Hazard Score | Observed rider response |
|---|---|
| < ~1.5 | significant likelihood of **acceleration** |
| 1 – 3 | velocity **unchanged** |
| > 3 | **deceleration** |

Vitali, Foster et al., IJERPH 18(4):1984 (2021) reuse the identical definition and add a
**summated HS** (sum of per-kilometre values over the trial).

The intuition is exactly the one a rider has: *"how bad do I feel, times how much is left"*. Early
in a ride, even a high RPE yields a high HS → back off. Near the finish, the same RPE yields a low
HS → it is safe to empty the tank. This is the most natural way to make a simulated rider behave
like a human rather than a controller holding constant watts.

Three implementation traps:

1. **The published bands overlap** between 1 and 1.5 (both "acceleration likely" and "unchanged").
   The implementer must resolve this — the paper does not.
2. **RPE must be on the Borg CR10 (1–10) scale.** Feeding a Borg 6–20 value into these thresholds
   is wrong by roughly 2×.
3. These are **probabilistic associations from small self-paced samples, not a calibrated control
   law**. The literature gives three coarse zones; a simulator has to supply its own
   HS → power-delta mapping and own that as a design choice, not cite it as science.

## 3.2 What survived: RPE rises linearly with *fraction completed*

Verified form (a teleoanticipation template):

```
RPE(x) ≈ RPE₀ + (RPE_max − RPE₀) · x        x = fraction of the route completed
```

Corroboration:

- de Koning et al. (2011) assert it as a citation-backed premise and build the Hazard Score on it.
- Faulkner, Parfitt & Eston, Psychophysiology 45:977–985 (2008): RPE trajectories differed in
  absolute rate across race lengths but were **indistinguishable against percentage of time
  completed**.
- Crewe, Tucker & Noakes, Eur J Appl Physiol (2008): RPE rose linearly in every fixed-power trial
  at both 15 °C and 35 °C, with an inverse linear relation (r = 0.83) between trial duration and
  RPE slope.
- A 2022 replication reports the growth is scalar and independent of exercise mode.

Adversarial search found **no refutation** for healthy adults in whole-body endurance exercise.
(The non-linear counterexamples — tetraplegic arm cycling, prescribed-RPE isometric force — are
out of scope.)

Two hard constraints on how you use it:

- Model RPE against **fraction of route completed**, never against wall-clock time.
- The slope is **scalar to intensity / expected duration**, not universal. And the template
  describes *self-regulated* effort — imposing an arbitrary power profile breaks it unless the
  slope is conditioned on intensity.

## 3.3 What did NOT survive

Everything else in this layer. The psychobiological model of endurance performance (Marcora),
effort-based decision making, mental fatigue effects on endurance, self-efficacy, and "hitting the
wall" as a psychological construct produced **no claim with a quantitative, implementable form**
that survived verification.

Explicitly refuted — **do not implement these**:

| Refuted claim | Vote |
|---|---|
| Summated Hazard Score correlates r = 0.88 with session RPE, so accumulated HS is a proxy for global perceived exertion | 0–3 |
| The rate of increase in Borg RPE during constant-power cycling predicts time to exhaustion (r = 0.83 inverse linear) | 1–2 |
| `RPE(t) = RPE₀ + k·t` with `k` set by intensity and ambient temperature (i.e. RPE linear in *time*) | 0–3 |

Note the third: the linear relation is real against **fraction completed** (§3.2) and *not* real
against elapsed time. That distinction is the entire difference between a model that works on a
GPX route of known length and one that doesn't.

## 3.4 Pragmatic recommendation for vcyclist

Implement this layer as an **explicitly heuristic module**, clearly labelled as such, with:

- `rpe(i)` driven by `distance(i) / totalDistance`, scaled by the ride's intensity relative to CP.
- `hazardScore(i) = rpe(i) × (1 − distance(i)/totalDistance)`.
- A configurable, **project-owned** mapping from HS to a multiplier on target power.

Do not present the HS → power mapping as literature-derived. The score is; the mapping is ours.

## Sources

- de Koning JJ, Foster C, Bakkum A, et al. *Regulation of Pacing Strategy during Athletic
  Competition.* PLoS ONE 6(1):e15863, 2011.
  <https://journals.plos.org/plosone/article?id=10.1371%2Fjournal.pone.0015863>
- Vitali F, Foster C, et al. IJERPH 18(4):1984, 2021.
  <https://pmc.ncbi.nlm.nih.gov/articles/PMC7922978/>
- Faulkner J, Parfitt G, Eston R. Psychophysiology 45:977–985, 2008.
  <https://pubmed.ncbi.nlm.nih.gov/18801015/>
- Crewe H, Tucker R, Noakes TD. Eur J Appl Physiol, 2008.
  <https://pubmed.ncbi.nlm.nih.gov/18461352/>
