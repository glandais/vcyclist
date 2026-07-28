# `vcyclist` command-line tool

JVM-only. Replaces gpx2web's `gpxtools-cli`.

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
| `--[no-]fix-elevation` | DEM elevation correction (off by default; downloads tiles) |
| `--[no-]virtualize` | physics simulation (on) |
| `--[no-]simplify`, `--simplify-tolerance <m>` | Douglas-Peucker (on, 10 m) |
| `--[no-]one-point-per-second` | 1 Hz resampling (on) |
| `--cyclist-*`, `--bike-*`, `--wind-*` | rider, bike and wind parameters |
| `-q, --quiet` | print nothing on success |

`--fit` requires `--start-time` because the FIT format has no relative clock — the engine's
timeline starts at zero, so the absolute start has to be supplied. The same applies to
`--start-time` for GPX, where it is optional.

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

Before this, only the first track was exported, without a word. gpx2web's `CSVFileWriter` also
produces one file per path.

### Which power ends up in the GPX (`--gpx-power-source`)

A `Path` carries two powers: the one read from the source file (`pInputPower`) and the one the
simulation reconstructs (`pComputedPower`). Only one fits in `<power>`.

The default, `input`, writes what the file said — nothing invented, and a trace without a power
meter comes back out without one. `computed` writes the simulation's output, which is what the
FIT export does. `computed-or-input` prefers the simulation and falls back point by point.

Two things worth knowing before choosing `computed`: `<power>` carries no provenance, so
re-reading the file turns the simulated value into a measured-looking one; and the two reference
implementations disagree — the TypeScript one writes the input power, gpx2web ends up writing the
simulated one.

**Not to be confused with `--gpx-power`**, which is about the simulation's input: it replays the
recorded power instead of applying the rider model.

### Bare GPX (`--no-extensions`)

Both subcommands write `<extensions>` by default — power, heart rate, cadence and temperature,
which on a 1 Hz track are most of the file. `--no-extensions` drops them, along with the now
unused `gpxtpx` namespace declaration. `<ele>`, `<time>` and `<name>` are standard GPX 1.1
elements, not extensions, and are always written.

Use it for a strict import target, a readable diff between two traces, an old GPS unit, or
simply a smaller file. gpx2web had the same switch on its writer.

## Coming from gpxtools-cli

Every command and option of `gpxtools-cli` has a line here. This table is what justified
removing the old entry points, and it feeds the g20 correspondence matrix.

### Commands

| gpxtools-cli | vcyclist | Note |
|---|---|---|
| `process` | `enhance` | |
| `virtualize` | `enhance` | The two overlapped almost entirely and vcyclist has one `Enhancer` pipeline, so reproducing the split would invent a distinction the code does not make. |
| `export` | `export` | |

### Options

| gpxtools-cli | vcyclist | Note |
|---|---|---|
| `process --csv` | `enhance --csv` | |
| `process --xlsx` | *(dropped)* | Not ported — see `PLAN-GPX2WEB.md`. Passing it prints a message pointing at `--csv`, not "unknown option". |
| `process --gpx-elevation` | `enhance --no-fix-elevation` | Inverted sense. gpx2web's flag asserts the GPX elevation is already good; vcyclist expresses the same thing by *not* correcting, which is the default. |
| `process --gpx-power` | `enhance --gpx-power` | Same name. Replays the recorded power instead of the constant `--cyclist-power`. |
| `process --cyclist-power` | `--cyclist-power` | Same name; internally a power strategy rather than a field of the cyclist. |
| `process/virtualize --wind-speed` | `--wind-speed` | Same name. gpx2web's help says "km/s", which is a typo for m/s. |
| `process/virtualize --wind-direction` | `--wind-direction` | Same name and convention (degrees, clockwise, 0 = N). |
| `virtualize --start` | `enhance --start-time` | Renamed for symmetry with `export --start-time`; same ISO-8601 meaning. |
| `export --map` | `export --map` | Now **requires** `--tile-url`. |
| `export --map-tile-url` | `export --tile-url` | Shortened; no default, deliberately. |
| `export --map-srtm` | `export --elevation-map` | Renamed: it renders a hypsometric map, and "srtm" named a data source rather than an output. |
| `export --map-width` / `--map-height` | `export --width` / `--height` | Shortened; unambiguous inside `export`. |
| `export --gpx` | `export --gpx` | Same name. |
| `export --fit` | `export --fit` | Now requires `--start-time` — FIT has no relative clock. |
| `-o` / `--output` | `-o` / `--output` | Same. |
| `--cyclist-weight`, `--cyclist-cd`, `--cyclist-a`, `--cyclist-max-brake` | same names | |
| `--cyclist-max-angle` | same name, **different default** | 45° → 35°, see below. |
| `--cyclist-max-speed` | same name, **different default** | 90 → 100 km/h, see below. |
| `--bike-crr`, `--bike-inertia-front`, `--bike-inertia-rear`, `--bike-wheel-radius`, `--bike-efficiency` | same names | |

Options with no gpx2web equivalent, added because vcyclist's pipeline exposes them:
`--json`, `--[no-]virtualize`, `--[no-]simplify`, `--simplify-tolerance`,
`--[no-]one-point-per-second`, `--max-size`, `--zoom`, `--margin`, `--cache`, `--quiet`.

### Coming from `EngineCli`

`:engine` used to ship a minimal `EngineCli` (`enhance <in> [-o <out>] [--start-time …]`),
removed in task g18. `vcyclist enhance <in> --gpx <out> --start-time …` is the direct
replacement, with the same exit codes.

### Defaults that deliberately differ

Option names match, values follow vcyclist's library rather than gpxtools-cli, so the CLI and
the API can never disagree:

| Option | gpxtools-cli | vcyclist |
|---|---|---|
| `--cyclist-max-angle` | 45° | **35°** |
| `--cyclist-max-speed` | 90 km/h | **100 km/h** |

`--cyclist-power` also behaves differently under the hood: gpx2web stores power on its cyclist
model, while vcyclist treats it as a power strategy. The option is the same; only the internals
moved.

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
