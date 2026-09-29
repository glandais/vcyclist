package io.github.glandais.map

/**
 * What [TileMapProducer] does with a tile it cannot obtain — the fetcher returned nothing, or the
 * bytes it returned do not decode as an image.
 */
enum class MissingTilePolicy {
    /**
     * Paint the tile's square [TileMapProducer.MISSING_TILE_COLOR], count it in
     * [MapImage.missingTileCount], and carry on. The default: a partly-drawn map beats no map.
     */
    SKIP,

    /**
     * Abort the render with an [java.io.IOException] naming the tile's URL and z/x/y, before
     * anything is written. All or nothing, for callers that would rather have no image than one
     * with holes in it.
     */
    FAIL,
}
