# Migrating from `gpxtools-cli`

**Frozen.** This table was written when the `:cli` module replaced the Java `gpxtools-cli` runner
(task g18) and has not been updated since. For the CLI as it is today, read
[`../../cli/README.md`](../../cli/README.md).

Every command and option of `gpxtools-cli` has a line here. This table is what justified
removing the old entry points, and it feeds the g20 correspondence matrix.

## Commands

| gpxtools-cli | vcyclist | Note |
|---|---|---|
| `process` | `enhance` | |
| `virtualize` | `enhance` | The two overlapped almost entirely and vcyclist has one `Enhancer` pipeline, so reproducing the split would invent a distinction the code does not make. |
| `export` | `export` | |

## Options

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
`--json`, `--road-condition`, `--cyclist-model`, `--cyclist-cp`, `--cyclist-wprime`,
`--cyclist-slew`, `--cyclist-pacing`, `--bike-max-pedal-angle`, `--[no-]virtualize`, `--[no-]simplify`, `--simplify-tolerance`,
`--[no-]one-point-per-second`, `--max-size`, `--zoom`, `--margin`, `--cache`, `--quiet`.

## Defaults that deliberately differ

Option names match, values follow vcyclist's library rather than gpxtools-cli, so the CLI and
the API can never disagree:

| Option | gpxtools-cli | vcyclist |
|---|---|---|
| `--cyclist-max-angle` | 45° | **35°** |
| `--cyclist-max-speed` | 90 km/h | **100 km/h** |

`--cyclist-power` also behaves differently under the hood: gpx2web stores power on its cyclist
model, while vcyclist treats it as a power strategy. The option is the same; only the internals
moved.
