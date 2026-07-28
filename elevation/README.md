# `:elevation`

Kotlin Multiplatform port of [`@glandais/elevation`](https://github.com/glandais/elevation):
fetches elevation data from Terrarium-encoded WebP tiles, with elevation profiling along a
path, distance-based smoothing, and Douglas-Peucker 3D simplification.

## Targets

| Target | Status | Tile decoding |
|---|---|---|
| JVM | ✅ supported | TwelveMonkeys ImageIO (`imageio-webp`) |
| JS (browser) | ✅ supported | `createImageBitmap` + canvas 2D (same DOM pipeline) |
| JS (Node) | ✅ supported | `@jsquash/webp` WASM decoder (runtime dep, lazy `eval('require')`) |

See `../docs/PLAN.md` and `../docs/kotlin-js-jvm-webp.md` for the design rationale and
multi-target interop conventions.

## Build & test

From the `vcyclist/` root:

```bash
./gradlew :elevation:allTests          # all targets
./gradlew :elevation:jvmTest           # JVM only
./gradlew :elevation:jsBrowserTest     # JS in headless Chrome (Karma)
./gradlew :elevation:jsNodeTest        # JS Node
```

## Browser demo

A browser demo is shipped, porting the original TypeScript demo at the root of the
[elevation](https://github.com/glandais/elevation) repo: Leaflet map, Chart.js elevation
profile, GPX upload, hillshade overlay.

| Demo | Sources | Run | Distribution |
|---|---|---|---|
| **Kotlin/JS** | `src/jsMain/resources/` | `:elevation:jsBrowserDevelopmentRun` | `:elevation:jsBrowserDistribution` → `build/dist/js/productionExecutable/` |

The `Distribution` task produces a self-contained folder with `elevation.js` plus the demo
HTML/CSS/JS and `sample.gpx`. Serve with any static HTTP server:

```bash
cd build/dist/js/productionExecutable && python3 -m http.server 8080
```

Dev server (Webpack hot-reload):

```bash
./gradlew :elevation:jsBrowserDevelopmentRun      # Kotlin/JS demo
```

### What it shows

- **Point mode** — click anywhere on the map to get the elevation at that location.
- **Path mode** — click multiple points to build a path; the chart shows the elevation profile.
- **GPX upload** — load any GPX file, or the bundled `sample.gpx`.
- **Smoothing** — toggle distance-based triangular-kernel smoothing with adjustable window size.
- **Filtering** — toggle Douglas-Peucker 3D simplification with tolerance and Z-exaggeration sliders.
- **Relief overlay** — hillshade or slope visualization on the map (via `leaflet-relief`).

### How the JS bridge works

The Kotlin/JS façade — [`src/jsMain/.../ElevationJsApi.kt`](src/jsMain/kotlin/io/github/glandais/elevation/ElevationJsApi.kt) —
exposes a free-function API: `newElevationProvider(config?)`,
`getElevation(handle, lat, lng, interpolation)`, and `getElevationsAlong(handle, path, options)`.
All async work returns `Promise` (per the `kotlin-js-jvm-webp.md` §3 convention). The handle is
an `ElevationProvider` passed opaquely, arrays are native JS `Array<T>`, numbers are plain
`Double`, and `@JsExport` covers both top-level functions and classes.

The demo's `index.html` loads the webpack UMD bundle as a regular `<script>` and wraps the free
functions in an `ElevationProvider` class shim. `demo.js` consumes
`window.Elevation.ElevationProvider` exactly like the original TS lib.

TypeScript definitions are emitted alongside the bundle (`generateTypeScriptDefinitions()` is
enabled on the `js(IR)` target).

## Status

Phase 1 (port of TS algorithms) is complete — see `../docs/PLAN.md` for the full task list and
parity numbers. The browser demo is **not** part of the formal task plan; it lives here as a
runnable smoke test of the `js(IR)` target and as the visual reference for the upcoming Compose
Multiplatform demo (Phase 9). End-to-end check against `tiles.mapterhorn.com`: Mont Blanc
(45.8326°N, 6.8652°E) returns ≈ 4757 m through the Kotlin/JS demo.
