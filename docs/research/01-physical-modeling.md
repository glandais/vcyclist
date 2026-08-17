# Layer 1 — Physical modeling of a solo rider

> Evidence status: **strong**. Every equation and number below comes from a peer-reviewed
> primary source and survived 3-vote adversarial verification, except where explicitly flagged.

## 1.1 The canonical power balance (Martin et al. 1998)

The reference formulation for road-cycling power is Martin, Milliken, Cobb, McFadden & Coggan,
*Validation of a Mathematical Model for Road Cycling Power*, J Appl Biomech 14(3):276–291 (1998).
Its Eq. 13/14:

```
P_NET = P_AT + P_RR + P_WB + P_PE + P_KE

P_TOT = [ V_a² · V_G · ½ρ · (CdA + F_w)                      ← aerodynamic
        + V_G · C_RR · m_T · g · cos(atan(G_R))              ← rolling resistance
        + V_G · (91 + 8.7 · V_G) · 10⁻³                      ← wheel bearings (W)
        + V_G · m_T · g · sin(atan(G_R))                     ← potential energy
        + ½ · (m_T + I/r²) · (V_f² − V_i²) / (t_f − t_i)     ← kinetic energy
        ] / E_C                                              ← drivetrain efficiency
```

- `V_G` = ground speed, `V_a` = air speed (ground speed + tangential wind, headwind positive).
- `F_w` = incremental drag *area* of the rotating spokes, a term most simplified models omit.
- `G_R` = road gradient as a ratio; the `cos(atan(G_R))` factor matters on steep climbs.
- `I/r²` is the equivalent translational mass of the rotating wheels — the same `m_eq` idea
  already used in `PowerComputer`.

Two independent later formulations reproduce the same structure:

- **Danek, Sławiński & Stanoev** (arXiv:2005.04229) —
  `P = [m g sinθ + m a + Crr m g cosθ + ½ η CdA ρ (V+w)²] · V / (1 − λ)`,
  where `η = sgn(V + w)` is the term that gets the **tailwind sign** right (a tailwind stronger
  than ground speed must *push*, not brake), and `λ` is the drivetrain loss fraction.
- **Dahmen, Byshko, Saupe, Röder & Mantler**, *Validation of a model and a simulator for road
  cycling on real tracks*, Sports Engineering 14:95–110 (2011) — force form:
  `F_pot + F_air + F_bear + F_roll + F_kin = (η · l_c)/(γ · r_w) · F_ped`
  with `F_kin = (m + I_w/r_w²) · a`.

**Known discrepancies between the three** (decide deliberately, don't average them):

| Point | Martin | Dahmen | Danek |
|---|---|---|---|
| `cos θ` on rolling resistance | yes | omitted (flat-road simplification) | yes |
| Spoke-rotation drag `F_w` | yes | neglected | neglected |
| Ambient wind | yes (yaw-resolved) | neglected | scalar `w` |
| Where efficiency is applied | whole bracket, incl. gravity & bearings | chain only | `1/(1−λ)` on the total |

Martin dividing the *entire* bracket by `E_C` is arguably wrong — bearing losses and gravity are
downstream of the chain — but it is what the validated model does, and the error is small.

## 1.2 Validation accuracy

- **Martin 1998, steady state**: 38 flat-road trials (3 subjects × 2 directions × 3 speeds),
  7–12 m/s, on a straight concrete taxiway at 0.3 % grade. Modeled = 1.00 × measured,
  **R² = 0.97, SEM 2.7 W** (172.0 ± 15.2 W modeled vs 172.8 ± 14.7 W SRM-measured, n.s.).
- **Dahmen 2011, time-stepped**: evaluating the *same* balance as an ODE at every sample of real
  uphill rides gives correlations **0.96–0.99, SNR 19.7–23.9 dB, and 98.9–99.6 % of variation
  accounted for**. The authors attribute the gain over Martin's 97 % precisely to solving at all
  time steps rather than at one prescribed constant speed.

**This is the single most important architectural endorsement for vcyclist**: the time-stepping
integration already implemented in `VirtualizeService` is not an approximation of the validated
model — it *is* the more accurate variant.

Scope limits to state honestly in any claim of realism:

- Martin's band is narrow: 7–12 m/s, ~172 ± 15 W, 0.3 % grade, steady state, ideal surface.
- Dahmen is **n = 4 rides**, low speeds, **no descents**, and its `p = 100(1 − MSE/MSP)` is not
  metric-identical to an R².
- A first-pass claim that *road-gradient measurement error, not the physics, dominates residual
  error* was **refuted (1–2)** and must not be repeated.

## 1.3 Parameter sets you can seed an implementation with

Two complete, independently published sets. Both are **defaults calibrated to one bike/rider/
surface**, not universal constants.

**Martin et al. 1998**

| Symbol | Value | Note |
|---|---|---|
| `E_C` | 0.97698 | 2.36 % chain loss |
| `C_RR` | 0.0032 | high-pressure clinchers, smooth asphalt |
| `C_RR` range | 0.0027–0.0040 (Kyle) | 0.0016 silk track tyre … 0.0066 touring tyre |
| `I` | ≈ 0.14 kg·m² | "both wheels" per the paper |
| `F_w` | 0.0044 m² | spoke drag area increment |
| `r` | 0.311 m | wheel radius |
| `ρ` | 1.2234 kg/m³ | worked example |
| bearing torque | `T = 0.015 + 0.00005·N` N·m | `N` in RPM, per bearing pair |

Worked appendix example: `P_NET` 208.2 W → `P_TOT` = 208.2 / 0.976 = **213.3 W** vs **218 W**
measured by SRM.

**Dahmen et al. 2011 (Table 1)**

| Symbol | Value | Provenance given in the paper |
|---|---|---|
| bike mass | 10.6 kg | measured |
| `I_w` | 0.28 kg·m² | pendulum experiment |
| `A` | 0.4 m², `c_d` = 0.7 → `c_d·A` = 0.28 m² | literature average |
| `µ` (Crr) | 0.004 | Cyclus 2 ergometer asphalt standard |
| `ρ` | 1.2 kg/m³ | fixed |
| `η` | 0.975 | from Martin et al. |
| `β₀`, `β₁` | 0.091 N, 0.0087 N·s/m | bearing terms, from Martin et al. |

> ⚠ **The two wheel-inertia figures differ by 2× (0.14 vs 0.28 kg·m²).** Almost certainly a
> per-wheel vs per-pair distinction. Check before use — it directly scales the kinetic-energy
> term on every acceleration.

Drivetrain efficiency is triangulated across all three sources: 0.977 (Martin), 0.975 (Dahmen),
0.964 (Danek's `λ = 0.0357`). **~2.5 % loss** is a safe default.

## 1.4 Air density

Verified closed form (Danek et al., arXiv:2005.04229):

```
ρ(h) = 1.225 · exp(−0.00011856 · h)      h in metres
```

The coefficient is internally consistent: `1/0.00011856 = 8434 m`, the isothermal scale height at
288.15 K, and the same constant appears in independent cycling-power calculators.

The paper **explicitly excludes humidity and varying atmospheric pressure**, and carries **no
temperature term at all**. For a simulator meant to span seasons that is a serious simplification
— a 0 °C vs 30 °C swing is roughly ±10 % on ρ, i.e. ±10 % on the entire aero term. Consider the
full ideal-gas form with a temperature input instead, and keep this as the fallback when only
altitude is known.

Real-segment fit from the same paper (m = 111 kg, 501 speed samples in 0.1 m/s bins, 10 000
Monte-Carlo perturbations): **CdA = 0.2607 ± 0.00298 m²**, Crr = 0.00231 ± 0.00545,
λ = 0.03574 ± 0.00044, retrodicting 255.3 W against a measured 258.8 W.

> ⚠ That **Crr has a standard deviation 2.4× its point estimate** — it is statistically
> unconstrained by the fit (CdA/Crr are inversely coupled) and **must not be cited as a validated
> Crr value**. Confidence in this source is *medium*: unrefereed arXiv preprint.

## 1.5 Wind, yaw and CdA

Martin's wind handling, verified verbatim including the worked example:

```
V_wtan = V_w · cos(D_w − D_b)        tangential component (headwind positive)
V_wnor = V_w · sin(D_w − D_b)        normal (cross) component
V_a    = V_G + V_wtan                air speed used in the drag term
yaw    = atan(V_wnor / V_a)
```

Worked example: `V_wtan = 2.94·cos(340−310) = 2.55`; `V_a = 8.36 + 2.55 = 10.91`;
`V_wnor = 1.47`; `yaw = atan(1.47/10.91) = 7.7°`; `CdA = 0.2565` by interpolation.

CdA is then **linearly interpolated** between wind-tunnel measurements at four yaw angles
(Table 1, group means):

| Yaw | CdA (m²) |
|---|---|
| 0° | 0.269 ± 0.006 |
| 5° | 0.265 ± 0.008 |
| 10° | 0.265 ± 0.009 |
| 15° | 0.255 ± 0.008 |

The differences are **not statistically significant** — for this setup, CdA is effectively flat in
yaw over 0–15°.

Three caveats that matter for implementation:

1. **The table stops at 15°.** Real crosswinds routinely produce larger yaw at low ground speed;
   anything beyond 15° is unsupported extrapolation.
2. This flat/slightly-decreasing trend is for an **aero TT position with a disc rear wheel**. It
   *contradicts* the deep-section "sail effect" literature where CdA rises then falls with yaw.
   Do not generalise it to a road bike on the hoods.
3. Values are group means. The paper's own interpolation example uses subject-specific 0.258/0.257.

## 1.6 What is NOT covered — cornering, braking, descents

**No verified source in the first research pass addresses lateral dynamics at all.** Every
physical claim above is longitudinal power balance only. Maximum cornering speed, braking
deceleration, line choice through bends and descent speed caps are covered in
[`04-behavioral-modeling.md`](04-behavioral-modeling.md) and
[`05-cornering-braking-descending.md`](05-cornering-braking-descending.md) from the second
research pass — treat their evidence grade separately from this document's.

## Sources

- Martin JC, Milliken DL, Cobb JE, McFadden KL, Coggan AR. *Validation of a Mathematical Model
  for Road Cycling Power.* J Appl Biomech 14(3):276–291, 1998.
  <https://collections.lib.utah.edu/dl_files/b4/8e/b48ef26086091662c561e673d7bd990d77868437.pdf>
- Dahmen T, Byshko R, Saupe D, Röder M, Mantler S. *Validation of a model and a simulator for road
  cycling on real tracks.* Sports Engineering 14:95–110, 2011.
  <https://kops.uni-konstanz.de/bitstream/123456789/18440/2/dahmen_validation.pdf>
- Danek, Sławiński & Stanoev. arXiv:2005.04229 (**unrefereed preprint**) — power model, air-density
  formula, and a Monte-Carlo CdA/Crr fit on real segment data. <https://arxiv.org/pdf/2005.04229>
