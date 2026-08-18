# `vcyclist` command-line tool

JVM-only.

```bash
./gradlew :cli:executableJar
java -jar cli/build/libs/vcyclist-cli-*-all.jar --help
```

During development: `./gradlew :cli:run -Pargs="enhance route.gpx --gpx out.gpx"`.

## Commands

### `enhance` — run the physics pipeline

```bash
vcyclist enhance route.gpx --gpx out.gpx --csv out.csv --start-time 2026-08-01T08:00:00Z
```

| Option | Meaning |
|---|---|
| `--gpx <file>` | enhanced GPX (a folder when several inputs are given) |
| `--csv` / `--json` | tabular exports — **one file per track** (see below) |
| `--fit <file>` | Garmin FIT course — **requires `--start-time`** |
| `--start-time <ISO-8601>` | absolute instant of the first point |
| `--no-extensions` | write a bare GPX: no `<extensions>`, so no power, heart rate, cadence or temperature |
| `--gpx-power-source <input\|computed\|computed-or-input>` | which power the written GPX carries (default `input`) |
| `--[no-]fix-elevation` | DEM elevation correction (off by default; downloads the missing tiles) |
| `--cache <folder>` | where DEM and map tiles persist between runs (default `~/.vcyclist/cache`) |
| `--[no-]virtualize` | physics simulation (on) |
| `--[no-]simplify`, `--simplify-tolerance <m>` | Douglas-Peucker (on, 10 m) |
| `--[no-]one-point-per-second` | 1 Hz resampling (on) |
| `--cyclist-*`, `--bike-*`, `--wind-*` | rider, bike and wind parameters |
| `-q, --quiet` | print nothing on success |

`--fit` requires `--start-time` because the FIT format has no relative clock — the engine's
timeline starts at zero, so the absolute start has to be supplied. The same applies to
`--start-time` for GPX, where it is optional.

`--fix-elevation` replaces the recorded elevations with values read from Terrarium DEM tiles.
Tiles land under `--cache` (`{host}/{z}/{x}/{y}.webp`, same layout as the map tile cache) and
never expire, so only the first run over an area touches the network. If a tile cannot be
fetched, the file **fails** — exit code 70, with the cause on stderr — rather than writing
plausible-looking output whose elevations were never corrected (task g34).

### `export` — derived outputs, no physics

```bash
vcyclist export route.gpx --elevation-map relief.png
vcyclist export route.gpx --map map.png --tile-url 'https://{s}.tile.example.org/{z}/{x}/{y}.png'
```

`--map` **requires `--tile-url`**: vcyclist ships no default tile source, because choosing one
means accepting its usage policy. See [`../map/README.md`](../map/README.md).

Framing: `--max-size` (default), or `--width`/`--height`, or `--zoom`; plus `--margin`.

`--no-extensions` applies to `--gpx` here too.

### Several tracks, several CSV/JSON files

A CSV or a JSON file describes one track, where a GPX or a FIT holds several. With a single-track
input — the usual case — `--csv out.csv` writes exactly `out.csv`. With several tracks it writes
`out-1.csv`, `out-2.csv`, … and names each one on stdout. Same for `--json`.

Before this, only the first track was exported, without a word.

### Which power ends up in the GPX (`--gpx-power-source`)

A `Path` carries two powers: the one read from the source file (`pInputPower`) and the one the
simulation reconstructs (`pComputedPower`). Only one fits in `<power>`.

The default, `input`, writes what the file said — nothing invented, and a trace without a power
meter comes back out without one. `computed` writes the simulation's output, which is what the
FIT export does. `computed-or-input` prefers the simulation and falls back point by point.

One thing worth knowing before choosing `computed`: `<power>` carries no provenance, so
re-reading the file turns the simulated value into a measured-looking one.

**Not to be confused with `--gpx-power`**, which is about the simulation's input: it replays the
recorded power instead of applying the rider model.

Only `enhance` has the option. `export` never simulates, so its `computed` would always be empty.
The same three spellings reach the JS façade (`writeGpx(path, writeExtensions, powerSource)`) and
the WASI door (`"powerSource"` in the `vcWriteGpx` options); all three parse through the one
`GpxPowerSource.fromWire` catalogue.

### Bare GPX (`--no-extensions`)

Both subcommands write `<extensions>` by default — power, heart rate, cadence and temperature,
which on a 1 Hz track are most of the file. `--no-extensions` drops them, along with the now
unused `gpxtpx` namespace declaration. `<ele>`, `<time>` and `<name>` are standard GPX 1.1
elements, not extensions, and are always written.

Use it for a strict import target, a readable diff between two traces, an old GPS unit, or
simply a smaller file.

## Other options

Beyond the ones documented above: `--json`, `--road-condition`, `--cyclist-model`, `--cyclist-cp`,
`--cyclist-wprime`, `--cyclist-slew`, `--cyclist-pacing`, `--bike-max-pedal-angle`,
`--[no-]virtualize`, `--[no-]simplify`, `--simplify-tolerance`, `--[no-]one-point-per-second`,
`--max-size`, `--zoom`, `--margin`, `--cache`, `--quiet`.

### `--road-condition`

`--road-condition=dry|wet` (default `dry`) sets the two grip-dependent rider limits together —
cornering friction and braking — because a wet road takes both away:

| | `dry` | `wet` |
|---|---|---|
| µ (cornering) | 0.70 (35° lean) | 0.28 (15.6° lean) |
| braking | 0.40 g | 0.23 g |

`dry` *is* the library default, so it changes nothing. `wet` cuts cornering speed by **1.58×**.

**The preset is the last word.** `--road-condition=wet --cyclist-max-angle 42` gives 15.6°, not 42 —
a road surface takes grip from cornering *and* braking together, which is the whole point, and
letting one of the two be overridden separately would model a rider who cannot corner but can still
stop like it is dry. `enhance` warns on stderr when you pass both, rather than ignoring your flag in
silence. Drop `--road-condition` to use your own values: with no preset asked for, explicit grip
options stand.

> **Changed.** Until the August 2026 surface-alignment work, an explicit `--cyclist-max-angle` beat
> the preset *here* while the preset beat it on the JS and WASI doors — the same configuration
> produced two different cornering physics depending on which door you came through. The doors agree
> now, on the CLI's cost.

Measured cost on the shipped fixtures (`--no-fix-elevation`, default rider):

| Route | dry | wet | penalty |
|---|---|---|---|
| `stelvio.gpx` (3.5 km, hairpins throughout) | 576 s | 617 s | **+7.1 %** |
| `strava.gpx` (20.8 km) | 2 882 s | 3 039 s | **+5.4 %** |
| `sample.gpx` (128.6 km) | 19 168 s | 19 763 s | **+3.1 %** |

The literature reports 1.8–3.4 % over 40 km on courses where technical sections are ~25 % of the
route, and 0–0.5 % without them. These sit at or above that band because the fixtures are more
technical than those courses — `stelvio.gpx` is essentially all corners.

### `--cyclist-model`

How the simulated rider chooses its power. `--cyclist-cp` (default 250 W) and `--cyclist-wprime`
(default 20 kJ) feed the two physiological models.

| Model | Behaviour |
|---|---|
| `constant` (default) | Holds `--cyclist-power` for the whole ride, however long it is |
| `durability` | Fades it with accumulated work **above CP** — intensity tires the rider, not time |
| `critical-power` | Spends a finite W′ reserve above CP, then settles at CP; recovers when easing off |

`durability` implements what the durability literature measures: 10–20 % power decline after only
2.5–15 kJ/kg of supra-CP work, versus < 5 % after comparable volumes below it. Its default fade
rate is deliberately at the **conservative** end of that band — those decrements were measured
mostly on short maximal efforts, and the same study found *no* effect on a 12-minute time trial.

`critical-power` is the one that changes how a ride *reads*: the rider goes hard while the reserve
lasts, drifts back to CP as it empties, and gets it back on descents. The taper itself is a
project-owned heuristic — the literature supplies the state, not a control law.

Measured against `constant` at the default 280 W and CP 250 W:

| Route | `constant` | `critical-power` | cost |
|---|---|---|---|
| `stelvio.gpx` (3.5 km, 10 min) | 578 s | 579 s | +0.2 % |
| `strava.gpx` (20.8 km) | 2 891 s | 3 060 s | +5.8 % |
| `sample.gpx` (128.6 km, 5.3 h) | 19 215 s | 20 810 s | **+8.3 %** |

Short rides barely move — the reserve is never exhausted. Long ones move a lot, because `constant`
was letting the rider hold 280 W against a 250 W CP for five hours, which the `wPrimeBalance`
column shows bottoming out at zero and staying there.

Providers compose: `--cyclist-slew` wraps whichever model is selected.

### `--bike-max-pedal-angle`

Past this lean angle (default **20°**) the inside pedal would strike the road, so the simulated
rider stops pedalling and coasts through the corner. `90` disables the cut-off.

It fires often but costs little, which is the interesting part:

| Route | pedals up | time cost |
|---|---|---|
| `stelvio.gpx` | 38 % of points | +0.35 % |
| `strava.gpx` | 21 % | +0.31 % |
| `sample.gpx` | 19 % | +0.25 % |

The reason is that it fires exactly where the rider is already limited by cornering or braking —
power applied there was being thrown away by the speed cap anyway (and showing up as `pBrake`).
So the change is mostly to the *power trace*, which stops depicting a rider pedalling into a
corner they are simultaneously braking for.

### `--cyclist-pacing`

Rides harder uphill and into a headwind, easier downhill and with a tailwind — with the one shape
the sources are specific about: an **increase is dispersed over ~300 m, a decrease is immediate**.

It is a heuristic, not an optimiser, and deliberately so: the best optimal-control model in the
literature matches real professional riders' velocity for 18–32 % of course duration, and the only
real-world trial of one was invalidated by a programming error. Every magnitude in the rule is
ours; only the asymmetry is sourced.

It also carries a **causal energy account** so that redistributing power does not become spending
more of it. That is not a nicety — measured without one, the rule came out 10 % faster on 11 % more
power, because climbs are slow and a boosted multiplier therefore applies for much more *time* than
the descent discount does.

| Route | time | mean power |
|---|---|---|
| `strava.gpx` (20.8 km, rolling) | 2 892 → 2 806 s (**−3.0 %**) | 252.6 → 247.7 W (−2.0 %) |
| `sample.gpx` (128.6 km) | 19 220 → 18 710 s (**−2.7 %**) | 261.5 → 266.3 W (+1.8 %) |
| `stelvio.gpx` (3.5 km, all climb) | 579 → 534 s (−7.8 %) | 232.1 → 239.1 W (+3.0 %) |

The first two are in the 1–3 % band the literature reports for the whole pacing optimum, at roughly
matched power — which is the comparison that means something. **`stelvio.gpx` is not**: it is a
9-minute pure climb, so the rule raises power nearly everywhere and the account has no descent to
claw it back on before the ride ends. Read that row as "rode harder", not "paced better".

### `--cyclist-slew`

Limits how fast the rider's power may change, in W/s (`0`, the default, is off). 50 W/s is
Zignoli & Biral's figure — a hard constraint in their optimal-control formulation, *not* a
measurement of what a rider can do.

With a constant power target there is little to smooth, so the cost is small (`stelvio.gpx`
578 → 581 s, `sample.gpx` 19 215 → 19 218 s) and it shows up in two places only: the start of a
ride, and the re-acceleration out of every corner where `--bike-max-pedal-angle` cut power. That
second one is worth noting — the cut-off is deliberately *not* rate-limited, so power drops
instantly at the lean threshold and ramps back gradually, which is the "drop quickly and locally,
rise gradually" asymmetry the pacing literature describes, arrived at without modelling it.

It becomes load-bearing when a provider reacts to terrain, which is what it is really for.

## Coming from `EngineCli`

`:engine` used to ship a minimal `EngineCli` (`enhance <in> [-o <out>] [--start-time …]`),
removed in task g18. `vcyclist enhance <in> --gpx <out> --start-time …` is the direct
replacement, with the same exit codes.

## Exit codes

Stable, and the same ones the previous `EngineCli` used, so existing scripts keep working.

| Code | Meaning |
|---|---|
| `0` | success |
| `64` | bad arguments (`EX_USAGE`) |
| `66` | no readable input file (`EX_NOINPUT`) |
| `70` | at least one file failed (`EX_SOFTWARE`) |

With several inputs, a failure on one file does **not** stop the others: everything processable
is processed, failures are listed at the end, and the exit code is `70`.

## Distribution

`:cli` is an application, not a library, so it is **not** published to Maven Central. The
distributable is the executable jar from `:cli:executableJar`, intended for attachment to a
GitHub release.
