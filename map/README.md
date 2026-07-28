# `:map` — static map rendering

JVM-only. Renders a `Path` (or several) over a raster tile background and writes a PNG, using
`java.awt` and `ImageIO`.

| Class | Role |
|---|---|
| `MapSpace` | Web Mercator projection: latitude/longitude ↔ pixels, tile indices, tile bounds |
| `MapImage` | Framing: bounds, zoom choice, image size, coordinate ↔ pixel accessors |
| `TileFetcher` / `HttpTileFetcher` | Retrieves one tile; injectable so rendering can be tested offline |
| `TileMapProducer` | Downloads and assembles the background, draws the tracks, writes the PNG |
| `SrtmMapProducer` | Generates a hypsometric background from DEM data instead of downloading imagery |

## Choosing a tile source is your decision — and your obligation

**There is no default tile URL, deliberately.** Every public tile server publishes a usage
policy, and shipping a default is what leads applications to hammer OpenStreetMap's servers
without their authors ever noticing. `TileMapProducer.createTileMap` therefore requires an
explicit `urlPattern`.

Before pointing this at a server, read its policy. For the OSM Foundation's tiles that is the
[Tile Usage Policy](https://operations.osmfoundation.org/policies/tiles/), which among other
things requires:

- **a valid, identifying `User-Agent`** — a generic one gets your IP blocked. `HttpTileFetcher`
  sends `vcyclist (https://github.com/glandais/vcyclist)` by default; change it to identify
  *your* application if you are not vcyclist itself;
- **no bulk downloading** — this module fetches only the tiles a render needs, and caches them,
  but rendering many large tracks in a loop is bulk downloading no matter how it is spelled;
- **caching** — see below.

Commercial and self-hosted sources exist precisely so heavy use has somewhere to go.

## Two kinds of map

`TileMapProducer` downloads raster imagery and draws the track over it. `SrtmMapProducer`
downloads no imagery at all: it colours the terrain by altitude from the elevation model that
`:elevation` provides, then draws the track on top, coloured by its own altitude range. The
second still fetches DEM tiles, so the usage-policy reasoning above applies to that source too.

## Cache

Tiles are cached at `{cacheFolder}/{host}/{z}/{x}/{y}.png` and **never expire**. Tiles are
effectively immutable, and a render that changes because the background was updated between two
runs makes regression testing impossible. To refresh, delete the folder.

Failed fetches are *not* cached — a transient error should not blank a tile permanently. (The
gpx2web original writes a zero-byte marker, making the failure stick.)

## Tests

Unit tests never touch the network: they inject a fake `TileFetcher`, and the HTTP layer is
exercised against a local `com.sun.net.httpserver` instance. The one test that downloads real
tiles is gated:

```bash
INTEGRATION=1 VCYCLIST_TILE_URL='https://…/{z}/{x}/{y}.png' ./gradlew :map:test --tests '*Integration*'
```

It has no default URL either.
