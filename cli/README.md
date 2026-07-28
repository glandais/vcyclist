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
| `--csv` / `--json` | tabular exports |
| `--fit <file>` | Garmin FIT course — **requires `--start-time`** |
| `--start-time <ISO-8601>` | absolute instant of the first point |
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

## Coming from gpxtools-cli

| gpxtools-cli | vcyclist | Note |
|---|---|---|
| `process` | `enhance` | |
| `virtualize` | `enhance` | The two overlapped almost entirely and vcyclist has one `Enhancer` pipeline, so reproducing the split would invent a distinction the code does not make. |
| `export` | `export` | |
| `--csv` | `--csv` | |
| `--xlsx` | *(dropped)* | Not ported — see `PLAN-GPX2WEB.md`. Passing it prints a message pointing at `--csv` rather than "unknown option". |
| `--start-date` | `--start-time` | ISO-8601, unchanged in meaning. |
| `--cyclist-*`, `--bike-*` | same names | Two **defaults** differ, see below. |

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
