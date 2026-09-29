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

Tiles are cached at `{cacheFolder}/{source}/{z}/{x}/{y}.png` and **never expire**. Tiles are
effectively immutable, and a render that changes because the background was updated between two
runs makes regression testing impossible. To refresh, delete the folder.

`{source}` is derived from the URL pattern: the host, `_{port}` if the port is explicit, then the
path segments before the first `{z}`/`{x}`/`{y}`. For example:

| URL pattern | Cache directory |
|---|---|
| `https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png` | `_s_.tile.openstreetmap.org` |
| `http://tileserver:8080/styles/colorful/256/{z}/{x}/{y}.png` | `tileserver_8080/styles/colorful/256` |
| `http://tileserver:8080/styles/eclipse/256/{z}/{x}/{y}.png` | `tileserver_8080/styles/eclipse/256` |
| `https://tiles.example.com/dark/{z}/{x}/{y}.png?key=…` | `tiles.example.com/dark/h_3f1c…` |

So two styles of one server, or two servers on one host, never share tiles, while the `a`/`b`/`c`
subdomains of `{s}` do. Anything the readable part cannot carry faithfully (a query string, an
extension other than `.png`, a placeholder in the middle of a segment, characters that had to be
replaced) adds a trailing `h_…` segment, a short hash of the whole pattern. Directory names are
restricted to `[A-Za-z0-9._-]`, so no pattern can write outside the cache folder.

> **Changed in 5.1.1.** Up to 5.1.0 the directory was the host alone, which mixed sources sharing a
> host — a dark render could be drawn with light tiles cached earlier. The old cache is **not
> migrated**: it does not record which source a tile came from, so it cannot be split correctly.
> Each source is downloaded once more into its new directory; the old `{host}/` directories can be
> deleted. A query string is part of the hash, so rotating an API key carried in it also starts a
> fresh cache for that source.

Failed fetches are *not* cached — a transient error should not blank a tile permanently. (Caching
a zero-byte marker instead would make the failure stick.) Only bytes that decode as an image are
written, through a temp file moved into place, and a cached tile that no longer decodes is deleted
and fetched again.

## Missing tiles

A tile that cannot be obtained — the fetcher returned nothing, or bytes that do not decode — is
handled by the producer's `MissingTilePolicy`. `TileFetcher.fetch` only ever says "no tile" with
`null`; failing the render is the producer's call.

- `SKIP` (the default) does not fail the render, but it is not silent either: the square is
  painted `TileMapProducer.MISSING_TILE_COLOR` (the neutral grey `SrtmMapProducer` uses for
  missing elevation, never black) and counted in the returned `MapImage.missingTileCount`, out of
  `MapImage.tileCount`.
- `FAIL` makes `createTileMap` throw an `IOException` naming the tile's URL and z/x/y, and write
  no output file — all or nothing:

```java
TileMapProducer producer = new TileMapProducer(cacheDir, fetcher, MissingTilePolicy.FAIL);
// throws IOException on a missing tile, and out is not written
producer.createTileMap(out, paths, urlPattern, TileMapProducer.DEFAULT_MARGIN, 512);
```

Either way the cache rules above hold: a corrupt entry is re-fetched before the tile counts as
missing, and bytes that fail to decode are never cached.

## Tests

Unit tests never touch the network: they inject a fake `TileFetcher`, and the HTTP layer is
exercised against a local `com.sun.net.httpserver` instance. The one test that downloads real
tiles is gated:

```bash
INTEGRATION=1 VCYCLIST_TILE_URL='https://…/{z}/{x}/{y}.png' ./gradlew :map:test --tests '*Integration*'
```

It has no default URL either.
