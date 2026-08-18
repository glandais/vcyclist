# vcyclist — Vue/Vite Demo

Interactive Vue 3 + Vite frontend that consumes the Kotlin/JS build of
`:engine` to demonstrate the physics-aware GPX virtualization pipeline.

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
- `src/composables/useGPXDemo.ts` — `parse → enhance → render` orchestration.
- `src/composables/useChart.ts` — Chart.js wrapper (zoom, crosshair, all 43 fields).
- `src/composables/useMap.ts` — Leaflet wrapper + hover sync.
- `src/components/*.vue` — Nuxt UI v4 (tabs, sidebar, modals).

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
