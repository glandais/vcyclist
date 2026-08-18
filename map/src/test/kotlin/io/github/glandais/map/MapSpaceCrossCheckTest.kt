package io.github.glandais.map

import io.github.glandais.elevation.ElevationFunctions
import io.github.glandais.elevation.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the claim made in [MapSpace]'s KDoc: this module and `:elevation` implement **the same**
 * Web Mercator projection, and must not drift apart.
 *
 * They are separate implementations for reasons of convention — pixels versus tiles, zoom 0-22
 * versus 0-15, clamping versus throwing — but the underlying maths has to agree everywhere both
 * are defined. Without this test "same projection" would be a comment nobody could rely on, and
 * two Web Mercators in one repo is exactly the trap the g13 spec warns about.
 *
 * The algebra: `MapSpace` computes `0.5 − ln((1+sin φ)/(1−sin φ)) / 4π` and `:elevation` computes
 * `(1 − ln(tan φ + sec φ)/π) / 2`. Since `ln((1+sin φ)/(1−sin φ)) = 2·ln(tan φ + sec φ)` those
 * are the same expression.
 */
class MapSpaceCrossCheckTest {
    private val space = MapSpace.TILE_256

    @Test
    fun `the two projections agree wherever both are defined`() {
        val coordinates =
            listOf(
                0.0 to 0.0,
                45.680697 to 6.396115,
                46.531802 to 10.443940,
                -33.8688 to 151.2093,
                60.0 to -120.0,
                -60.0 to 30.0,
                84.9 to 170.0,
                -84.9 to -179.0,
            )
        // `:elevation` caps zoom at 15 (its DEM source has no deeper tiles), so the comparison
        // runs over the range they share. Longitudes stop at 170 deg: `:map` clamps the last
        // pixel column, which at zoom 0 is a full 1.4 deg wide, and that difference is
        // deliberate — the next test characterises it rather than hiding it here.
        for (zoom in 0..15) {
            for ((lat, lon) in coordinates) {
                val theirs = ElevationFunctions.toTileCoordinatesFloat(LatLon(lat, lon), zoom)
                val ourX = space.lonToTileX(lon, zoom)
                val ourY = space.latToTileY(lat, zoom)
                // Tolerance is relative to the world size in tiles, so it stays meaningful as
                // zoom grows: 1e-9 of a tile is well under a millimetre on the ground.
                assertEquals(theirs.xFloat, ourX, 1e-9, "x at zoom $zoom for ($lat, $lon)")
                assertEquals(theirs.yFloat, ourY, 1e-9, "y at zoom $zoom for ($lat, $lon)")
            }
        }
    }

    @Test
    fun `the clamped edge is the one documented difference`() {
        // Near +180 deg `:map` clamps to the last pixel while `:elevation` does not clamp at all,
        // so they part company inside the final pixel — and only there.
        val zoom = 10
        val theirs = ElevationFunctions.toTileCoordinatesFloat(LatLon(0.0, 180.0), zoom)
        val ours = space.lonToTileX(180.0, zoom)
        assertEquals(1024.0, theirs.xFloat, 1e-9, "elevation projects +180 to the world edge")
        assertEquals(1024.0 - 1.0 / 256.0, ours, 1e-9, "map clamps it one pixel inside")
    }
}
