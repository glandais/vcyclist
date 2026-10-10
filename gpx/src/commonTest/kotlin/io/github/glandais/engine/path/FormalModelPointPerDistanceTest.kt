package io.github.glandais.engine.path

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Counterexample found by the Lean 4 executable model of [PointPerDistance]
 * (`formal/resample-simplify/PointPerDistance.lean`, `#eval antimeridian`).
 *
 * The source distances come from Haversine, which handles a longitude wrap correctly, but the
 * interpolated points are built by linear interpolation of the raw longitude in radians. Across
 * the ±180° meridian (Taveuni, Fiji; Chukotka; the Aleutians) `lon2 - lon1 ≈ -2π`, so every
 * interpolated point lands on the far side of the globe.
 */
class FormalModelPointPerDistanceTest {
    private companion object {
        const val EARTH_RADIUS_M: Double = 6_371_000.0
    }

    /** Two points 100 m apart on the same parallel, straddling the antimeridian. */
    private fun antimeridianSegment(latDeg: Double): Path {
        val lat = latDeg * PI / 180.0
        val dLon = 100.0 / (EARTH_RADIUS_M * cos(lat))
        val p = Path(2)
        p.setLatitude(0, lat)
        p.setLongitude(0, PI - dLon / 2.0)
        p.setElevation(0, 10.0)
        p.setLatitude(1, lat)
        p.setLongitude(1, -PI + dLon / 2.0)
        p.setElevation(1, 10.0)
        p.computeDerivedData()
        return p
    }

    @Test
    fun densifyAcrossAntimeridianPreservesDistanceAndGapBound() {
        // Taveuni island, Fiji, is crossed by the 180th meridian at ~16.8° S.
        val source = antimeridianSegment(-16.8)
        assertTrue(abs(source.totalDistance - 100.0) < 0.5, "fixture: source is ${source.totalDistance} m")

        // Enhancer step 1.
        val out = PointPerDistance.compute(source, -1.0, 30.0)

        for (i in 1 until out.size) {
            val gap = out.distance(i) - out.distance(i - 1)
            assertTrue(
                gap <= 30.0 + 1e-6,
                "gap $i = $gap m exceeds maxDistanceM=30 (lon ${out.longitudeDeg(i - 1)}° -> ${out.longitudeDeg(i)}°)",
            )
        }
        val rel = abs(out.totalDistance - source.totalDistance) / source.totalDistance
        assertTrue(
            rel < 0.005,
            "densified totalDistance ${out.totalDistance} m vs source ${source.totalDistance} m",
        )
    }
}
