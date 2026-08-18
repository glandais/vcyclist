# `:elevation`

Fetches elevation data from Terrarium-encoded WebP tiles, with elevation profiling along a
path, distance-based smoothing, and Douglas-Peucker 3D simplification.

## Targets

| Target | Status | Tile decoding |
|---|---|---|
| JVM | ✅ supported | TwelveMonkeys ImageIO (`imageio-webp`) |
| JS (browser) | ✅ supported | `createImageBitmap` + canvas 2D (same DOM pipeline) |
| JS (Node) | ✅ supported | `@jsquash/webp` WASM decoder (runtime dep, lazy `eval('require')`) |

See `../docs/archive/plans/PLAN.md` and `../docs/guides/kotlin-js-jvm-webp.md` for the design rationale and
multi-target interop conventions.

[`../docs/guides/elevation.md`](../docs/guides/elevation.md) covers the other half: what the engine
does with these altitudes, why cumulative ascent depends on the measurement scale, and how Strava's
numbers are produced.

## Bringing your own tile transport

`TileManager` and `ElevationProvider` take a `fetcher: suspend (String) -> RawTile`, defaulting
to `fetchAndDecodeTile`. That hook exists so you can own the *transport* — a disk or object-store
cache, an HTTP client with your own retry and `Cache-Control` policy, tiles shipped inside the
application — without also having to own the *decoder*, which differs per target and has to be
byte-exact (see `ReferenceTileDigestTest`).

The two halves are public:

```kotlin
suspend fun fetchTileBytes(url: String): ByteArray
suspend fun decodeTileBytes(bytes: ByteArray, sourceUrl: String = ""): RawTile
```

A caller-owned cache is then the obvious three lines:

```kotlin
val provider =
    ElevationProvider(config) { url ->
        val bytes = myCache.get(url) ?: fetchTileBytes(url).also { myCache.put(url, it) }
        decodeTileBytes(bytes, url)
    }
```

`decodeTileBytes` suspends because the browser decodes through `createImageBitmap`, which is
asynchronous — not for symmetry with the fetch half.

### From Java

`ElevationProviderJvm.newElevationProvider(config, fetcher)` takes a plain
`Function<String, RawTile>` (task g32), so the same cache is a lambda:

```java
Path cacheRoot = Path.of("/var/cache/tiles");

ElevationProvider provider =
    ElevationProviderJvm.newElevationProvider(
        ElevationProviderJvm.elevationProviderConfig(),
        url -> {
            Path cached = cacheRoot.resolve(URLEncoder.encode(url, UTF_8));
            try {
                if (Files.exists(cached)) {
                    return TileFetcherJvm.decodeTileBytesBlocking(Files.readAllBytes(cached), url);
                }
                byte[] bytes = TileFetcherJvm.fetchTileBytesBlocking(url);
                // Atomic: several tiles are fetched at once, and a half-written file read by
                // another thread would decode into a corrupt tile.
                Path tmp = Files.createTempFile(cacheRoot, "tile", ".part");
                Files.write(tmp, bytes);
                Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return TileFetcherJvm.decodeTileBytesBlocking(bytes, url);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
```

The fetcher **may block** — it is invoked on `Dispatchers.IO`, never on the caller's thread — and
**must be thread-safe**: `BatchCalculator` fetches up to ten tiles concurrently. That is why the
example writes to a temporary file and moves it into place rather than writing the destination
directly.

Two cache levels coexist and do not overlap: yours holds compressed bytes, `TileManager` keeps
an LRU of decoded `Tile`s (`ElevationProviderConfig.cacheSize`).

## Build & test

From the `vcyclist/` root:

```bash
./gradlew :elevation:allTests          # all targets
./gradlew :elevation:jvmTest           # JVM only
./gradlew :elevation:jsBrowserTest     # JS in headless Chrome (Karma)
./gradlew :elevation:jsNodeTest        # JS Node
```

## Browser demo

The browser demo lives in the repo's Vue app, at route `#/elevation`:

```bash
cd demo && npm run dev        # http://localhost:3000/#/elevation
```

It used to be a standalone page under `src/jsMain/resources/`, served by
`:elevation:jsBrowserDevelopmentRun`. That page duplicated a Leaflet map, a Chart.js profile, a
GPX parser and a build that `demo/` already had, and was deployed nowhere; the module no longer
produces an executable bundle, only the npm library.

### What it shows

- **Point mode** — click anywhere on the map to get the elevation at that location.
- **Path mode** — click multiple points to build a path; the chart shows the elevation profile.
- **GPX** — load any of the bundled sample tracks, or upload your own.
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

The demo consumes it through [`demo/src/elevation-shim.ts`](../demo/src/elevation-shim.ts), which
re-exports those three functions under flat names and hand-writes the DTO types — Kotlin/JS emits
no body for an `external interface`, so there is nothing to import. `:engine` declares
`api(project(":elevation"))`, so the single `@glandais/vcyclist-engine` bundle the demo already
aliases carries this façade too, under `io.github.glandais.elevation`.

TypeScript definitions are emitted alongside the library (`generateTypeScriptDefinitions()` is
enabled on the `js(IR)` target).

## Live HTTP integration tests

`ElevationProviderIntegrationTest` makes **real HTTP calls** to `tiles.mapterhorn.com`. It is
skipped unless `INTEGRATION=1` is set in the environment (or `-Dintegration=true` as a system
property):

```bash
INTEGRATION=1 ./gradlew :elevation:jvmTest --tests '*ElevationProviderIntegrationTest*' --rerun-tasks
```

The six tests cover: Mont Blanc (~4805 m) and the Dead Sea (~−430 m, below sea level) and Badwater
Basin (~−85 m), each to ±50 m; that the LRU cache works (a second call on the same coordinates
re-fetches nothing); that the default attribution points at mapterhorn; and that
`getElevationsAlong` over four alpine waypoints returns a densified profile of ≥ 10 points with no
outliers.

**Cost**: ~6 WebP tiles × ~30–50 kB ≈ 200 kB per full run. `java.net.http.HttpClient` does not use
the JDK's HTTP cache, so every run pays the round trip.

**Why it does not run in CI**: it depends on a third-party service, the
[attribution terms](https://mapterhorn.com/attribution/) discourage heavy automated use, and
runner latency makes it flaky. The `INTEGRATION=1` gate keeps it available as a deliberate manual
check — before a module release, or after reworking the pipeline.

## Status

Phase 1 is complete — see `../docs/archive/plans/PLAN.md` for the full task list and
parity numbers. The browser demo is **not** part of the formal task plan; it lives here as a
runnable smoke test of the `js(IR)` target and as the visual reference for the upcoming Compose
Multiplatform demo (Phase 9). End-to-end check against `tiles.mapterhorn.com`: Mont Blanc
(45.8326°N, 6.8652°E) returns ≈ 4757 m through the Kotlin/JS demo.
