# `:elevation`

Kotlin Multiplatform port of [`@glandais/elevation`](https://github.com/glandais/elevation):
fetches elevation data from Terrarium-encoded WebP tiles, with elevation profiling along a
path, distance-based smoothing, and Douglas-Peucker 3D simplification.

## Targets

| Target | Status | Tile decoding |
|---|---|---|
| JVM | ✅ supported | TwelveMonkeys ImageIO (`imageio-webp`) |
| Wasm (browser) | ✅ supported | `createImageBitmap` + canvas 2D |
| JS (browser) | ✅ supported | `createImageBitmap` + canvas 2D (same DOM pipeline) |
| JS (Node) | ✅ supported | `@jsquash/webp` WASM decoder (runtime dep, lazy `eval('require')`) |

See `../docs/PLAN.md` and `../docs/kotlin-wasm-jvm-webp.md` for the design rationale and
multi-target interop conventions.

## Build & test

From the `vcyclist/` root:

```bash
./gradlew :elevation:allTests          # all targets
./gradlew :elevation:jvmTest           # JVM only
./gradlew :elevation:wasmJsBrowserTest # Wasm in headless Chrome (Karma)
./gradlew :elevation:jsBrowserTest     # JS in headless Chrome (Karma)
./gradlew :elevation:jsNodeTest        # JS Node
```

## Browser demos

Two sibling browser demos are shipped — same UI, same API surface, two compile targets — for
side-by-side comparison of Kotlin/Wasm and Kotlin/JS. Both port the original TypeScript demo at
the root of the [elevation](https://github.com/glandais/elevation) repo: Leaflet map, Chart.js
elevation profile, GPX upload, hillshade overlay.

| Demo | Sources | Run | Distribution |
|---|---|---|---|
| **Kotlin/Wasm** | `src/wasmJsMain/resources/` | `:elevation:wasmJsBrowserDevelopmentRun` | `:elevation:wasmJsBrowserDistribution` → `build/dist/wasmJs/productionExecutable/` |
| **Kotlin/JS** | `src/jsMain/resources/` | `:elevation:jsBrowserDevelopmentRun` | `:elevation:jsBrowserDistribution` → `build/dist/js/productionExecutable/` |

Each `Distribution` task produces a self-contained folder with `elevation.js` (Wasm also adds
`*.wasm`) plus the demo HTML/CSS/JS and `sample.gpx`. Serve with any static HTTP server:

```bash
cd build/dist/wasmJs/productionExecutable && python3 -m http.server 8080  # or .../js/...
```

Dev server (Webpack hot-reload):

```bash
./gradlew :elevation:wasmJsBrowserDevelopmentRun  # Wasm demo
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

Two parallel façades expose the same free-function API:

- Kotlin/Wasm — [`src/wasmJsMain/.../ElevationJsApi.kt`](src/wasmJsMain/kotlin/io/github/glandais/elevation/ElevationJsApi.kt)
- Kotlin/JS — [`src/jsMain/.../ElevationJsApi.kt`](src/jsMain/kotlin/io/github/glandais/elevation/ElevationJsApi.kt)

Both expose `newElevationProvider(config?)`, `getElevation(handle, lat, lng, interpolation)`,
and `getElevationsAlong(handle, path, options)`. All async work returns `Promise` (per the
`kotlin-wasm-jvm-webp.md` §3 convention). The two backends differ on:

| | Kotlin/Wasm | Kotlin/JS |
|---|---|---|
| Handle type | `JsReference<ElevationProvider>` | `ElevationProvider` (passed opaquely) |
| Array type in API | `JsArray<T>` | `Array<T>` (native JS array) |
| Number wrapping | `JsNumber` | `Double` direct |
| DTO base | `external interface … : JsAny` | `external interface …` |
| `@JsExport` scope | top-level functions only (Wasm 2.3 limitation) | top-level functions and classes |
| Module load | `globalThis.elevation` is a `Promise` (Wasm async instantiation) | `globalThis.elevation` is the module synchronously |

Each demo's `index.html` loads the webpack UMD bundle as a regular `<script>` and wraps the
free functions in an `ElevationProvider` class shim. `await globalThis.elevation` works for
both (awaiting a non-Promise resolves to the value), so `demo.js` is byte-identical across the
two demos and consumes `window.Elevation.ElevationProvider` exactly like the original TS lib.

TypeScript definitions are emitted alongside both bundles (`generateTypeScriptDefinitions()` is
enabled on both `js(IR)` and `wasmJs` targets).

## Status

Phase 1 (port of TS algorithms) is complete — see `../docs/PLAN.md` for the full task list and
parity numbers. The two browser demos are **not** part of the formal task plan; they live here
as runnable smoke tests of the `wasmJs` / `js(IR)` targets and as the visual reference for the
upcoming Compose Multiplatform demo (Phase 9). End-to-end check against `tiles.mapterhorn.com`:
Mont Blanc (45.8326°N, 6.8652°E) returns ≈ 4757 m through the Wasm bridge; the Kotlin/JS demo
shares the algorithm code path, so the same value is expected.
