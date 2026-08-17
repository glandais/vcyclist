# The racing line

vcyclist can ride the *optimal line* through corners instead of the line your GPX records — going
wide on entry, clipping the apex, drifting wide again on exit. This document is what it does, what
it costs, and what it assumes.

It is **off by default**, and the rest of this page is largely about why.

> Design and history live elsewhere: [`docs/design/racing-line.md`](design/racing-line.md) is the
> original design (now carrying a long list of measured corrections), and ledger entries
> [**R23–R26**](research/improvements-ledger.md) record what each piece was actually worth.

## Using it

```bash
# Analyse without changing anything — what would it do?
vcyclist enhance route.gpx --racing-line-report --gpx /dev/null

# Apply it, staying in your own lane (the default, and legal)
vcyclist enhance route.gpx --racing-line --gpx out.gpx

# Use the whole carriageway. Closed roads and time trials only.
vcyclist enhance route.gpx --racing-line --corridor full-road --gpx out.gpx

# Tell it how wide the road really is; the gain is proportional to this
vcyclist enhance route.gpx --racing-line --road-width 4 --gpx out.gpx
```

From Kotlin: `EnhanceOptions(racingLine = RacingLineOptions(enabled = true))`, or
`RacingLine.analyze(path)` for the read-only report. From JavaScript: `racingLineEnabled` on the
enhance options, and `analyzeRacingLine(path, options)`.

## What it is worth

Measured on real routes, full pipeline, `FULL_ROAD` — the *most* favourable corridor:

| route | duration change |
|---|---|
| `strava`, 21 km | **−0.54 %** |
| `sample`, 128 km | **−0.81 %** |
| `stelvio`, 3.5 km of hairpins | **+0.07 %** |

Half a percent. The reason is structural rather than a shortcoming of the implementation: **a rider
spends only 1–5 % of a ride against the speed envelope at all** — measured at 1.2 % on a 128 km
route — and cornering geometry can only move the clock where the envelope binds. The same ceiling
explains ledger R11's friction ellipse (+0.03 % to +0.17 %) and R25's rejected time weighting.

`stelvio` coming out *slower* is not a bug. It is a climb, where speed is power-limited rather than
corner-limited, and the racing line is about 2 % **longer** — the objective minimises curvature, not
distance, so it buys corner speed with metres. On a climb that trade is a loss. **Do not enable
this for a hill climb.**

Counter-intuitively, `LANE` often beats `FULL_ROAD` (19 312 s against 19 326 s on a 128 km route):
the wider corridor lets the line weave more, and the extra distance costs more than the extra corner
speed returns. The legal default is also usually the faster one.

## What it assumes, and where that hurts

**The road width is a guess.** The corridor half-width is `h = width/2 − margin`, and the gain is
*linear* in it — so a width wrong by 2× makes the result wrong by 2×, in an unknown direction.

Widths come from, in order: an explicit `<roadwidth>` extension on the point, a track-level one, the
OSM `highway` class if a router tagged one, and finally `--road-width` (6 m). Real router exports
carry **no `width` and no `lanes` tag at all** — only a road class, which is near-constant along any
one route. Inferring width from that class is measurably worth 0.005 % (ledger R26). If you know
your road, `--road-width` is the only honest way to say so.

**A recorded GPX is not the centreline.** It is where the rider actually rode — already off-centre,
possibly already cutting apexes. The stage cannot tell a recorded trace from a routed one, and
treats both as a centreline. On a recording, part of the "gain" may be re-riding a line you already
rode.

## What it does to your file

**Every coordinate is replaced** by a smoothed reference plus the solved offset. Even at zero offset
that is a small move, because the reference is smoothed. Your original positions are preserved in
the `sourceLatitude` / `sourceLongitude` fields, so the edit is reversible — but anything that
map-matches, detects segments, or compares against the original will see different coordinates.

Douglas-Peucker simplification is capped at 2 m whenever the stage runs. The entire line lives
inside about 2.5 m of the centreline, so the usual 10 m tolerance would discard it wholesale.

## Reading the report

```
  racing line: 24 corner(s), corridor up to 5,00 m wide, 6 iterations, converged=true
    at m   kind     turn deg   R road   R line   offset
    1299   HAIRPIN    -157,4      7,8     10,3     2,50
    1901   HAIRPIN    -196,2      5,0      7,5     2,50
```

`R road` is the 20th-percentile radius of the road through the corner; `R line` is the tightest
radius of the line chosen. `offset` is the largest lateral displacement used, in metres, positive to
the left. Here the hairpins open from 7.8 m to 10.3 m and from 5.0 m to 7.5 m, with the offset
saturating at the corridor edge — which is the expected `R_line ≈ R_road + h`.

A corner where `R line` is *smaller* than `R road` is one the stage made worse; they exist, and the
report is how you find them.

`converged=false` means the solver hit its iteration cap. The line is still corridor-feasible — that
is enforced at every step — but it is not the optimum.

## What it will not do

- **Roundabouts.** A route drawn through a roundabout is ridden through it. Detection exists in the
  design and was not built: measured, tight-radius artefacts account for 0.03 % of ride time, so
  there is nothing there to recover.
- **Junctions.** Nothing reconstructs road geometry the file does not contain.
- **Slow-in / fast-out.** The objective is geometric and velocity-blind, so it cannot trade corner
  entry speed against exit acceleration. Time weighting was implemented to approximate this and
  measured *worse* (ledger R25).
