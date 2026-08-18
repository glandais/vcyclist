# vcyclist — Vue/Vite Demo

Interactive Vue 3 + Vite frontend that consumes the Kotlin/JS build of
`:engine` to demonstrate the physics-aware GPX virtualization pipeline.

Deployed at **<https://glandais.github.io/vcyclist>** by
[`.github/workflows/gh-pages.yml`](../.github/workflows/gh-pages.yml).

## Two views

Hash-routed (GitHub Pages serves a static `index.html`, so a path-based deep link would 404):

| Route | View | What it does |
|---|---|---|
| `#/` | `src/views/GpxAnalysisView.vue` | Upload a GPX, run the physics pipeline, inspect all 43 fields on a synchronized chart + map, with climb detection and the racing line, and export the result. |
| `#/elevation` | `src/views/ElevationExplorerView.vue` | Query DEM tiles at a point or along a path, with smoothing, Douglas-Peucker simplification and hillshade/slope relief. Folded in from the standalone `:elevation` demo. |

Both are lazy-imported and wrapped in `<KeepAlive>`: the GPX view parses and enhances
`stelvio.gpx` on mount and the elevation view holds a clicked path, so a plain `RouterView` would
throw both away on every tab switch. The price is that each view must re-measure its Leaflet map
(`invalidateSize`) and Chart.js canvas in `onActivated` — a map laid out while hidden renders grey
tiles otherwise.

## Quick start

```bash
# from vcyclist/ root
./gradlew :engine:jsBrowserProductionLibraryDistribution  # build the bundle
cd demo
npm install
npm run dev                                                 # http://localhost:3000
```

The `predev`/`prebuild` npm scripts automatically run the Gradle distribution
task, so subsequent runs only need `npm run dev`.

## Build a static site via Gradle

```bash
# from vcyclist/ root
./gradlew :demo:assemble
# → demo/dist/ ready to serve with any static host
python -m http.server -d demo/dist 8000
```

## Architecture

The Vue 3 app imports `@glandais/vcyclist-engine` via a Vite `resolve.alias` onto the
Gradle output (`../engine/build/dist/js/productionLibrary/`), mirrored by a `paths` entry
in `tsconfig.json`. It is deliberately **not** an npm `file:` dependency: that made
Dependabot fail the whole directory with "couldn't fetch all your path-based
dependencies", so the demo's own dependencies were never updated. The engine is the
Kotlin/JS compiled output of the `:engine` module — same physics, same GPX
parser, same DEM-fix pipeline as the JVM CLI (see [`../README.md`](../README.md)).

- `src/engine-shim.ts` — thin re-export of the engine's `@JsExport` symbols, plus hand-written
  TypeScript declarations for the DTOs. They have to be hand-written: Kotlin/JS emits no body in
  the generated `.d.ts` for an `external interface`, so there is nothing to import or check
  against. Keep this file in step with `EngineJsApi.kt` — a rename here is silent until runtime.
- `src/elevation-shim.ts` — same thing for the `:elevation` façade (`ElevationJsApi.kt`), with the
  same hand-written-types caveat. `:engine` declares `api(project(":elevation"))`, so the one
  aliased bundle carries both façades and no extra build wiring is needed.
- `src/composables/useGPXDemo.ts` — `parse → enhance → render` orchestration.
- `src/composables/useChart.ts` — Chart.js wrapper (zoom, crosshair, all 43 fields).
- `src/composables/useMap.ts` — Leaflet wrapper + hover sync.
- `src/composables/useElevation*.ts` — the elevation view's provider (a module-level singleton, so
  its DEM tile cache survives remounts), map, chart and state machine.
- `src/components/*.vue` — Nuxt UI v4 (tabs, sidebar, modals).

`leaflet-relief` is the only third-party Leaflet plugin, used for the hillshade/slope overlay. It
ships its own type definitions, so no module declaration is needed. GPX parsing goes through the
engine's own `parseGpx`, never a separate JS GPX library.

## Export

The `⬇️ Download` menu writes the current path as **GPX** (`writeGpxAt`) or as a **Garmin FIT
course** (`pathToFit`), through `src/utils/download.ts`.

**Start time.** FIT has no relative clock, and after `enhance` the path's own clock is relative
(`VirtualizeService` pins `time(0) = 0`). So the absolute instant is taken from the *source* file's
first timestamp, and falls back to "now" when the GPX carried no `<time>` — the same decision
`GpxDocument.startTime` records on the Kotlin side (task `g05`). Several bundled samples,
`stelvio.gpx` included, have no timestamps, so their exports are stamped with today's date.

**Power.** The GPX is written with `powerSource: 'computed-or-input'` — the simulated power,
falling back to the file's recorded one wherever the simulation produced none. The engine default
is `'input'` (write back only what the source said, never invent), which is right for a library
and wrong for this app: it would hand back a physics simulation with the physics removed. The FIT
export reads `pComputedPower` and needs no such flag. The track is named after the source file
rather than the writers' default `virtualized`.

Exporting before pressing `🚀 Enhance` is allowed, and warns: a merely-parsed path has no computed
power, and a timestamp-less one yields a zero-duration course.

## Rider models

The Power tab offers the engine's four `CyclistPowerProvider` strategies — constant, durability
(fades with work above CP), critical-power (spends a W′ reserve, then settles at CP) and replay
from the GPX file — plus two decorators that compose over any of them: terrain pacing and a
50 W/s slew limit. The Cyclist tab carries the dry/wet road preset, and the Bike tab the
pedal-strike clearance angle.

Each is one entry of [`../docs/ledgers/improvements-ledger.md`](../docs/ledgers/improvements-ledger.md),
which records what it is worth and what it is not: several of these change the *power trace*
without moving the finish time, and the ledger says which.

## License

Apache 2.0, same as the parent vcyclist project.
