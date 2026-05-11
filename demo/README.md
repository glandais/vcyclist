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

The Vue 3 app imports `@glandais/vcyclist-engine` via a `file:` link to the
Gradle output (`../engine/build/dist/js/productionLibrary/`). The engine is the
Kotlin/JS compiled output of the `:engine` module — same physics, same GPX
parser, same DEM-fix pipeline as the JVM CLI (see [`../README.md`](../README.md)).

- `src/engine-shim.ts` — thin re-export of the engine's `@JsExport` symbols.
- `src/composables/useGPXDemo.ts` — `parse → enhance → render` orchestration.
- `src/composables/useChart.ts` — Chart.js wrapper (zoom, crosshair, 36 fields).
- `src/composables/useMap.ts` — Leaflet wrapper + hover sync.
- `src/components/*.vue` — PrimeVue UI (tabs, sidebar, modals).

## License

Apache 2.0, same as the parent vcyclist project.
