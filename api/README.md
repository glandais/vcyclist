# `vcyclist` HTTP API (`:api`)

JVM only. A [Ktor](https://ktor.io) server that puts the engine behind HTTP: parse a GPX, simulate a
ride on it, export it as GPX / FIT / CSV / JSON, detect climbs, analyse the racing line, query the
DEM and render static maps. Seventeen operations under `/api/v1`, described by an OpenAPI 3.1
contract **generated from the code** and committed as [`contract/openapi.yaml`](contract/openapi.yaml).

It is the seventh **door** of the engine, next to the CLI, JS, WASI, Java and the demo (see
[`docs/ledgers/surface-coverage.md`](../docs/ledgers/surface-coverage.md)), and follows the same
rules: the same JSON key names as the JS DTOs and the WASI readers, defaults read and never
restated, an unknown key is an error before any computation.

The API is **stateless**: every request carries its GPX, nothing is stored on the server. It is an
application, like `:cli`: nothing may depend on it, and it is not published to Maven Central.
Its design and the reasons behind each choice are in
[`docs/archive/tasks/api.md`](../docs/archive/tasks/api.md).

## Run

```bash
./gradlew :api:run -Pargs="--port 8081"          # development
./gradlew :api:executableJar                      # self-contained jar
java -jar api/build/libs/vcyclist-api-<version>-all.jar --port 8081
```

Then `curl -s localhost:8081/api/v1/health` answers `{"status":"UP"}`, and
<http://localhost:8081/swagger> serves Swagger UI over the contract.

## Configuration

Server settings, not engine defaults. Environment variables, plus `--host` / `--port` on the
command line (which win).

| Setting | Environment / argument | Default |
|---|---|---|
| host | `VCYCLIST_API_HOST` / `--host` | `0.0.0.0` |
| port | `VCYCLIST_API_PORT` / `--port` | `8080` |
| max body size | `VCYCLIST_API_MAX_BODY_BYTES` | 20 MiB |
| max track points (all parsed tracks together) | `VCYCLIST_API_MAX_TRACK_POINTS` | 200 000 |
| max points `/enhance` simulates (estimated from the length, see below) | `VCYCLIST_API_MAX_SIMULATED_POINTS` | 500 000 |
| max points of one elevation request (densified, for `/elevation/along`) | `VCYCLIST_API_MAX_ELEVATION_POINTS` | 10 000 |
| response timeout of CPU work | `VCYCLIST_API_REQUEST_TIMEOUT_MS` | 120 000 |
| requests computed at once | `VCYCLIST_API_COMPUTE_CONCURRENCY` | number of processors |
| CORS origins (comma-separated, `*` allowed) | `VCYCLIST_API_CORS_ORIGINS` | none — CORS off |
| max width or height of a map, pixels | `VCYCLIST_API_MAX_MAP_PIXELS` | 4 096 |
| max DEM tiles a terrain map may read | `VCYCLIST_API_MAX_MAP_DEM_TILES` | 1 024 |
| tile server of `/map/tiles` (`{z}/{x}/{y}` template) | `VCYCLIST_API_MAP_TILE_URL` | none — `/map/tiles` answers 503 |
| scratch directory (map tile cache, temporary PNGs) | `VCYCLIST_API_CACHE_DIR` | `${java.io.tmpdir}/vcyclist-api` |

Every limit is published by `GET /api/v1/defaults` under `limits`.

## Endpoints

All under `/api/v1`. "GPX" in the input column means the [GPX input](#gpx-input) below.

| Method | Path | Input | Output | `operationId` |
|---|---|---|---|---|
| GET | `/health` | — | `{"status":"UP"}` | `getHealth` |
| GET | `/version` | — | contract and engine versions | `getVersion` |
| GET | `/catalog` | — | wire values of every enumeration (power models, road conditions, corridors, presets, DEM sources, output formats…) | `getCatalog` |
| GET | `/fields` | — | the 44 per-point fields, in order — the names `fields` accepts | `listFields` |
| GET | `/defaults` | — | every default this door applies, and the limits | `getDefaults` |
| POST | `/gpx/parse` | GPX | tracks / routes, summaries, waypoints, `?points=true` for the points | `parseGpx` |
| POST | `/enhance` | GPX + `EnhanceRequest` | summary JSON, or GPX / FIT / CSV / JSON columns | `enhance` |
| POST | `/export` | GPX + `ExportRequest` | GPX / FIT / CSV / JSON columns, no simulation | `export` |
| POST | `/analysis/climbs` | GPX + `ClimbRequest` | climbs per track | `detectClimbs` |
| POST | `/analysis/racing-line` | GPX + `RacingLineRequest` | racing-line report per track (`null` below 8 points) | `analyzeRacingLine` |
| POST | `/analysis/headwind` | GPX | dominant headwind azimuth, overall and per track | `dominantHeadwind` |
| GET | `/elevation/sources` | — | DEM sources, their zoom and attribution | `listElevationSources` |
| GET | `/elevation/point` | `?lat=&lon=` (+ `source`, `zoomLevel`, `interpolation`) | one elevation | `getElevation` |
| POST | `/elevation/points` | JSON `{points:[{latitude,longitude}…]}` | elevations | `getElevations` |
| POST | `/elevation/along` | JSON polyline + `step`, smoothing, filter | a resampled profile | `getElevationsAlong` |
| POST | `/map/tiles` | GPX + `TileMapRequest` | `image/png` over the configured tiles | `renderTileMap` |
| POST | `/map/terrain` | GPX + `TerrainMapRequest` | `image/png` over a DEM hillshade | `renderTerrainMap` |

Outside the contract: `GET /api/v1/openapi.yaml`, `GET /api/v1/openapi.json`, `GET /swagger`.

Every DEM answer carries the attribution of its source (and of its fallback: `ign-lidar-hd` falls
back to Mapterhorn outside LiDAR coverage).

## GPX input

Two forms, chosen by `Content-Type`:

1. **`multipart/form-data`** — a `gpx` part (file or field, required) and an optional `request`
   part (`application/json`, the route's request object). Any other part is 400 `unknown-key`.
2. **`application/gpx+xml`**, `application/xml` or `text/xml` — the body is the GPX and the
   request takes every default. Options need the multipart form: one way to say each thing.

Anything else is 415. Query parameters common to every GPX route (and the only ones allowed, with
the route's own `format` / `points`):

| Parameter | Values | Default | Effect |
|---|---|---|---|
| `kinds` | `all`, `tracks`, `routes` | `all` | which `<trk>` / `<rte>` to read |
| `split` | `tracks`, `segments` | `tracks` | one path per track, or per `<trkseg>` |
| `trackIndex` | integer ≥ 0 | all | keep one path; past the end is 422 |
| `repair` | `true`, `false` | `true` | repair malformed XML (bare `&`…) and report each repair in `warnings` |

No path with a point is 422 `empty-gpx`; more than the track-point limit is 413 `too-many-points`. `/enhance` also refuses, with the same
problem, paths that the pipeline would densify past `MAX_SIMULATED_POINTS` (see below).

## Output format

`/enhance` and `/export` resolve their format from `?format=` first, then from `Accept`:

| `format` | `Accept` | Response |
|---|---|---|
| `summary` (default of `/enhance`, not served by `/export`) | `application/json` | `EnhanceResponse` |
| `gpx` (default of `/export`) | `application/gpx+xml` | GPX 1.1 |
| `fit` | `application/vnd.ant.fit` | FIT course — **needs `output.fit.startTimeEpochMs`** (else 422) |
| `csv` | `text/csv` | one row per point |
| `json` | `application/vnd.vcyclist.columns+json` | `application/json`, one array per field |

An `Accept` the route cannot serve is 406. Note that `Accept: application/json` on `/export` is
406 (the summary is not served there): a client that sends it by default must pass `?format=`.
Files carry `Content-Disposition: attachment; filename="…"`. **CSV and JSON carry one track**:
several tracks without `?trackIndex=` is 422 `multiple-tracks`, never the first one silently.
Only the `output` member matching the format is read; another one is 400 (`output.csv given but
format is gpx`). `fields` (CSV and JSON) is a list of names from `GET /fields`.

The warnings (XML repairs, a road-condition preset overriding explicit grip values, an elevation
correction that failed or was dropped) are only carried by the `summary` format and the analysis
responses; a file has no room for them.

### Elevation corrections

After `fixElevation`, two corrections run unless `options.bridgeDecksEnabled` /
`options.tunnelsEnabled` turn them off (both default `true`, published by `GET /defaults`):
bridge decks ridden from IGN's surface model, which only `demSource: "ign-lidar-hd"` at its
default zoom provides, and tunnels flattened between their portals on any source. Each summary
track says what they did:

```json
"elevationCorrections": {"bridgeDecks": "fetch-failed", "bridgeDecksApplied": 0, "bridgeDecksDropped": 0,
                         "tunnels": "none-found", "tunnelsApplied": 0, "tunnelsDropped": 0,
                         "warnings": ["bridge decks: a terrain- or surface-model tile could not be read; …"],
                         "terrain": {"ignLidarHdKm": 41.3, "mapterhornMeasuredKm": 0.4, "mapterhornKm": 0.0,
                                     "otherKm": 0.0, "noDataKm": 0.0, "fallbackAfterErrorKm": 0.0, "failedTiles": 0}}
```

`terrain` says where the elevations were read from, in km per origin (`null` without
`fixElevation`); see `docs/guides/elevation.md`, *Where the terrain came from*.

The statuses are listed by `GET /catalog` (`elevationCorrectionStatuses`). Each warning is also
appended to the response's `warnings`, prefixed `track <n> (<name>): `. A failed surface-model read
is not an error: the bridges keep the valley floor and the answer is 200.

## Errors

Every error is `application/problem+json` (RFC 9457), with `type` = `urn:vcyclist:problem:<slug>`.
The slugs below are the documentation of those URNs.

```json
{ "type": "urn:vcyclist:problem:unknown-key", "title": "Unknown key", "status": 400,
  "detail": "Unknown key(s) in request body: /options/simplifyTolerance",
  "instance": "/api/v1/enhance", "unknownKeys": ["/options/simplifyTolerance"] }
```

| Slug | Status | When |
|---|---|---|
| <a id="invalid-body"></a>`invalid-body` | 400 | malformed JSON, wrong JSON type, missing `gpx` part |
| <a id="unknown-key"></a>`unknown-key` | 400 | unknown JSON key, multipart part or query parameter — **all** listed in `unknownKeys` as JSON pointers (`part:<name>` for a part), before any computation |
| <a id="invalid-option"></a>`invalid-option` | 400 | a value out of domain: unknown enum value, an engine `require`, `separator` not one character, an `output` member for another format, two map framings… — the pointer is in `errors` |
| <a id="invalid-gpx"></a>`invalid-gpx` | 400 | the GPX does not parse (even after repair, when on) |
| <a id="not-found"></a>`not-found` | 404 | no such route |
| <a id="method-not-allowed"></a>`method-not-allowed` | 405 | |
| <a id="not-acceptable"></a>`not-acceptable` | 406 | `Accept` names no format the route serves |
| <a id="payload-too-large"></a>`payload-too-large` | 413 | body over the size limit (by `Content-Length` or while reading a chunked body) |
| <a id="too-many-points"></a>`too-many-points` | 413 | over the track-point, simulated-point or elevation-point limit |
| <a id="unsupported-media-type"></a>`unsupported-media-type` | 415 | |
| <a id="empty-gpx"></a>`empty-gpx` | 422 | no selected path has a point |
| <a id="multiple-tracks"></a>`multiple-tracks` | 422 | CSV / JSON asked for several tracks |
| <a id="missing-start-time"></a>`missing-start-time` | 422 | FIT without `startTimeEpochMs` |
| <a id="track-index-out-of-range"></a>`track-index-out-of-range` | 422 | `trackIndex` past the selection |
| <a id="track-names-mismatch"></a>`track-names-mismatch` | 422 | `trackNames` not the size of the track list |
| <a id="not-encodable"></a>`not-encodable` | 422 | FIT refuses a path whose time goes backwards |
| <a id="dem-unavailable"></a>`dem-unavailable` | 502 | the DEM tile server failed |
| <a id="dem-rate-limited"></a>`dem-rate-limited` | 503 | the DEM tile server asked to retry later; `Retry-After` is set |
| <a id="busy"></a>`busy` | 503 | reserved — not raised today, requests queue instead (see below) |
| <a id="timeout"></a>`timeout` | 503 | the computation did not answer within the timeout |
| <a id="map-tiles-unconfigured"></a>`map-tiles-unconfigured` | 503 | `/map/tiles` without `VCYCLIST_API_MAP_TILE_URL` |
| <a id="internal"></a>`internal` | 500 | anything else; the message is logged, never returned |

Every response echoes `X-Request-Id` (or generates one), which the logs carry.

## Limits and timeouts — what they really do

- The body size is checked on `Content-Length` and again while reading, so a chunked body does not
  slip past it.
- CPU work runs in a **compute gate**: at most `COMPUTE_CONCURRENCY` at once, the others queue for a
  permit. The timeout bounds the **response**, not the computation: the engine's loops never
  check for cancellation, so a computation that started finishes on its thread and keeps its
  permit. A request still waiting for a permit is really cancelled. The real protection is upstream, on the
  size of what is computed.
- The pipeline's cost follows the track's **length**, not its input point count: it densifies to a
  point every 30 m, then every 2 m, before anything else, so two points 1 000 km apart become half a
  million points of 44 doubles. `/enhance` therefore bounds the densified size, estimated before
  simulating as `points + length/30 m + length/2 m` (`Enhancer.densifiedPointBound`, which reads the
  pipeline's own spacings) against `MAX_SIMULATED_POINTS` — about 1 000 km by default.
  `/elevation/along` bounds its densified polyline the same way against `MAX_ELEVATION_POINTS`.
- The DEM provider of each `(source, zoom)` is shared **concurrently**, with no lock: its tile cache
  guards itself and downloads a tile once however many requests want it, and the per-host pacer
  keeps the tile server's rate whatever the concurrency. A cold IGN ride (~20 s of fetching per
  130 km) no longer holds the next request on the same source; only `COMPUTE_CONCURRENCY` bounds
  how many run at once.
- A map is rendered in memory, up to `4 · MAX_MAP_PIXELS²` bytes per concurrent render.

## Defaults

`GET /api/v1/defaults` publishes what the door applies when a key is absent, and it is the applied
value — the tests read it back through the same mappers. The single default site is
`HttpDefaults` (`src/main/kotlin/…/mapping/HttpDefaults.kt`): every engine value is read from the
engine's own options objects, never written as a literal. The pipeline defaults are those of the
JS and WASI doors, which differ from the engine's on three fields: **no DEM fetch, no 1 Hz
resampling, no simplification** (`fixElevation`, `computeOnePointPerSecond`, `simplifyEnabled` all
`false`). A caller moving from the JS façade gets the same trace for the same JSON.

## OpenAPI contract

```bash
./gradlew :api:generateOpenApi     # rewrites api/contract/openapi.yaml from the routing tree
```

Never edit `contract/openapi.yaml` by hand: `OpenApiContractTest` fails when it differs from what
the routes render, and the server serves the same rendering at `/api/v1/openapi.yaml` and
`/api/v1/openapi.json`. The contract version (`info.version`) is the API's, not the engine's
(that one is in `GET /version`). Field defaults are not in the schemas — the fields are nullable so
that "absent" can be told from "given"; they are published by `GET /defaults`.

## Deliberate gaps

- **No client-supplied tile URL** (`tileUrlTemplate`, `tileSize`, `cacheSize`) for the DEM or the
  map: a server fetching a URL its client names is an SSRF. The server picks its DEM among the
  catalogued sources and its map tiles from its configuration.
- **No disk DEM cache**: `diskCachedElevationProvider` is internal to `:cli`; the API keeps the
  in-memory cache of each provider.
- **Stateless**: no `POST /tracks` + `GET /tracks/{id}`. Re-uploading a GPX costs little next to a
  simulation, and storage would need eviction, quotas and identity nobody asked for.
- What no door exposes stays Kotlin here too: the curvature and racing-line tuning windows, the
  reported elevation-gain smoothing window, the FIT sport.

## Examples

```bash
# Summary of a simulated ride, with the defaults
curl -s -X POST -H 'Content-Type: application/gpx+xml' --data-binary @ride.gpx \
  'localhost:8081/api/v1/enhance'

# Simulated GPX with the computed power, a heavier rider on a wet road
curl -s -X POST 'localhost:8081/api/v1/enhance?format=gpx' \
  -F 'gpx=@ride.gpx' \
  -F 'request={"cyclist":{"massKg":85,"roadCondition":"wet"},"power":{"type":"constant","power":250},
               "output":{"gpx":{"powerSource":"computed"}}};type=application/json' \
  -o virtualized.gpx

# FIT course of the second track
curl -s -X POST 'localhost:8081/api/v1/export?format=fit&trackIndex=1' \
  -F 'gpx=@two-tracks.gpx' \
  -F 'request={"output":{"fit":{"startTimeEpochMs":1767254400000}}};type=application/json' \
  -o course.fit

# Two CSV columns
curl -s -X POST 'localhost:8081/api/v1/export?format=csv' -F 'gpx=@ride.gpx' \
  -F 'request={"output":{"csv":{"fields":["distance","elevation"],"separator":";"}}};type=application/json'

# Climbs, and a DEM point
curl -s -X POST -H 'Content-Type: application/gpx+xml' --data-binary @ride.gpx \
  'localhost:8081/api/v1/analysis/climbs'
curl -s 'localhost:8081/api/v1/elevation/point?lat=45.83&lon=6.86'
```

## Tests

`./gradlew :api:test` runs offline: a flat fake DEM (every point at 100 m) and a fake tile
fetcher replace the network. Besides one success and one error test per operation, four suites
keep the door aligned with the others:

- `HttpDoorParityTest` — each DTO's keys equal what `OptionCatalog` (in `:codegen`) gives
  `Door.HTTP`, and HTTP accepts every JS and WASI key;
- `HttpForwardingTest` — every HTTP option, sent alone, moves the core object off its default
  (an accepted key is a used key);
- `HttpDefaultsTest` — `{}` maps to `HttpDefaults`, and `GET /defaults` is what is applied;
- `OpenApiContractTest` — the committed contract is the rendered one.
