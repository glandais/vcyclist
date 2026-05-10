# `:elevation`

Kotlin Multiplatform port of [`@glandais/elevation`](https://github.com/glandais/elevation):
fetches elevation data from Terrarium-encoded WebP tiles, with elevation profiling along a
path, distance-based smoothing, and Douglas-Peucker 3D simplification.

## Targets

| Target | Status | Tile decoding |
|---|---|---|
| JVM | ✅ supported | TwelveMonkeys ImageIO (`imageio-webp`) |
| Wasm (browser) | ✅ supported | `createImageBitmap` + canvas 2D |
| JS (Node) | ⚠️ smoke-tested only | none built-in — caller injects an `ElevationProvider` fetcher |

See `../docs/PLAN.md` and `../docs/kotlin-wasm-jvm-webp.md` for the design rationale and
multi-target interop conventions.

## Build & test

From the `vcyclist/` root:

```bash
./gradlew :elevation:allTests          # all targets
./gradlew :elevation:jvmTest           # JVM only
./gradlew :elevation:wasmJsBrowserTest # Wasm in headless Chrome (Karma)
./gradlew :elevation:jsNodeTest        # JS Node
```

## Browser demo

A WASM browser demo lives in `src/wasmJsMain/resources/`. It is a port of the original
TypeScript demo at the root of the [elevation](https://github.com/glandais/elevation) repo —
same UI (Leaflet map, Chart.js elevation profile, GPX upload, hillshade overlay), but the
elevation calculations run inside the Kotlin/Wasm bundle.

### Run

```bash
./gradlew :elevation:wasmJsBrowserDevelopmentRun
```

Then open <http://localhost:8080>. Webpack-dev-server hot-reloads on Kotlin changes.

### Build a static dist

```bash
./gradlew :elevation:wasmJsBrowserDistribution
```

Output: `build/dist/wasmJs/productionExecutable/` — `elevation.js` + `*.wasm` + the demo
HTML/CSS/JS + `sample.gpx`. Serve with any static HTTP server:

```bash
cd build/dist/wasmJs/productionExecutable && python3 -m http.server 8080
```

### What it shows

- **Point mode** — click anywhere on the map to get the elevation at that location.
- **Path mode** — click multiple points to build a path; the chart shows the elevation profile.
- **GPX upload** — load any GPX file, or the bundled `sample.gpx`.
- **Smoothing** — toggle distance-based triangular-kernel smoothing with adjustable window size.
- **Filtering** — toggle Douglas-Peucker 3D simplification with tolerance and Z-exaggeration sliders.
- **Relief overlay** — hillshade or slope visualization on the map (via `leaflet-relief`).

### How the JS bridge works

The Kotlin → JS façade lives in
[`ElevationJsApi.kt`](src/wasmJsMain/kotlin/io/github/glandais/elevation/ElevationJsApi.kt):

- Kotlin/Wasm 2.3 currently restricts `@JsExport` to **top-level functions**, so the API is
  shaped as free functions taking a `JsReference<ElevationProvider>` opaque handle:
  `newElevationProvider(config?)`, `getElevation(handle, lat, lng, interpolation)`,
  `getElevationsAlong(handle, path, options)`. All async work returns `Promise` per the
  `kotlin-wasm-jvm-webp.md` §3 convention.
- DTOs are declared as `external interface … : JsAny` (per §4.B) so JS callers can pass plain
  object literals (`{ latitude, longitude }`, `{ step: 25, smoothingOptions: {...} }`).
- Output `CoordinatesElevationDto` instances are built as plain JS objects via a `@JsFun`
  literal helper, so consumers like Chart.js can read `.elevation` / `.latitude` directly.

The demo's `index.html` loads the webpack UMD bundle as a regular `<script>`, awaits
`globalThis.elevation` (a Promise — wasm instantiation is async), then defines a small
`ElevationProvider` class shim that holds the handle and forwards calls to the exported
functions. That exposes `window.Elevation.ElevationProvider`, which `demo.js` consumes with
exactly the same call sites as the original TS library.

TypeScript definitions are emitted alongside the bundle as `vcyclist-elevation.d.mts`
(`generateTypeScriptDefinitions()` is enabled in `build.gradle.kts`).

## Status

Phase 1 (port of TS algorithms) is complete — see `../docs/PLAN.md` for the full task list and
parity numbers. The WASM browser demo is **not** part of the formal task plan; it lives here as
a runnable smoke test of the `wasmJs` target and as the visual reference for the upcoming
Compose Multiplatform demo (Phase 9). End-to-end check against `tiles.mapterhorn.com`: Mont
Blanc (45.8326°N, 6.8652°E) returns ≈ 4757 m through the WASM bridge.
