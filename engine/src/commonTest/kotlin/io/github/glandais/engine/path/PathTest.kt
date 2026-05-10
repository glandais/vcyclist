package io.github.glandais.engine.path

import io.github.glandais.elevation.LatLonElevation
import io.github.glandais.elevation.MathConstants
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class PathTest {
    private fun pathFromLatLonEle(vararg triples: Triple<Double, Double, Double>): Path {
        val p = Path(triples.size)
        for ((i, t) in triples.withIndex()) {
            p.setLatitude(i, t.first * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, t.second * MathConstants.DEG_TO_RAD)
            p.setElevation(i, t.third)
        }
        return p
    }

    // ---- 1. empty path -------------------------------------------------------

    @Test
    fun empty_path_computes_zero_stats() {
        val p = Path(0)
        p.computeDerivedData()
        assertEquals(0, p.size)
        assertEquals(0.0, p.totalDistance)
        assertEquals(Path.BoundsRad.EMPTY, p.boundsRad)
        assertEquals(0.0, p.durationMs)
        assertEquals(0.0, p.elevationGain)
        assertEquals(0.0, p.elevationLoss)
        assertEquals(0.0, p.minElevation)
        assertEquals(0.0, p.maxElevation)
    }

    // ---- 2. single point path ------------------------------------------------

    @Test
    fun single_point_path_is_safe() {
        val p = pathFromLatLonEle(Triple(0.0, 0.0, 100.0))
        p.computeDerivedData()
        assertEquals(1, p.size)
        assertEquals(0.0, p.totalDistance)
        assertEquals(0.0, p.durationMs)
        // bounds are non-EMPTY because we visited 1 point (lat=0, lon=0)
        assertEquals(Path.BoundsRad(0.0, 0.0, 0.0, 0.0), p.boundsRad)
        assertEquals(100.0, p.minElevation)
        assertEquals(100.0, p.maxElevation)
    }

    // ---- 3. three points ~1km apart on equator -------------------------------

    @Test
    fun three_points_about_1km_apart_on_equator() {
        // Equator, longitude 0/0.00898315/0.01796630 ≈ 1km steps
        val p =
            pathFromLatLonEle(
                Triple(0.0, 0.0, 50.0),
                Triple(0.0, 0.00898315, 50.0),
                Triple(0.0, 0.01796630, 50.0),
            )
        p.computeDerivedData()
        assertTrue(abs(p.totalDistance - 2000.0) < 5.0, "got ${p.totalDistance}")
        // boundsRad correct (lat=0 stays 0, lon ranges from 0 to 0.01796630° in radians)
        assertEquals(0.0, p.boundsRad.minLat)
        assertEquals(0.0, p.boundsRad.maxLat)
        assertEquals(0.0, p.boundsRad.minLon)
        assertEquals(0.01796630 * MathConstants.DEG_TO_RAD, p.boundsRad.maxLon, 1e-12)
    }

    // ---- 4. elevation gain and loss ------------------------------------------

    @Test
    fun elevation_gain_and_loss_are_correct_on_a_hill_profile() {
        val p =
            pathFromLatLonEle(
                Triple(0.0, 0.0, 100.0),
                Triple(0.0, 0.0009, 300.0),
                Triple(0.0, 0.0018, 200.0),
            )
        p.computeDerivedData()
        assertEquals(200.0, p.elevationGain)
        assertEquals(-100.0, p.elevationLoss)
        assertEquals(100.0, p.minElevation)
        assertEquals(300.0, p.maxElevation)
    }

    // ---- 5. grade ------------------------------------------------------------

    @Test
    fun grade_is_about_5_percent_on_5_percent_climb() {
        // 100 m horizontal between consecutive points (~0.000898315° at equator),
        // elevation rising 5 m per segment → 5% grade
        val p =
            pathFromLatLonEle(
                Triple(0.0, 0.0, 100.0),
                Triple(0.0, 0.000898315, 105.0),
                Triple(0.0, 0.001796630, 110.0),
            )
        p.computeDerivedData()
        // Middle point: dDist = (dist(2) - dist(0)) / 2 ≈ 100, dEle = (110 - 100) / 2 = 5
        assertTrue(abs(p.grade(1) - 0.05) < 1e-3, "got grade=${p.grade(1)}")
    }

    // ---- 6. speed ------------------------------------------------------------

    @Test
    fun speed_is_10_m_per_s_when_100m_in_10s() {
        // Make distance between point 0 and point 2 = 200m (so dx=100m), and dt = 10s
        val p =
            pathFromLatLonEle(
                Triple(0.0, 0.0, 50.0),
                Triple(0.0, 0.000898315, 50.0),
                Triple(0.0, 0.001796630, 50.0),
            )
        p.setTime(0, 0.0)
        p.setTime(1, 10_000.0)
        p.setTime(2, 20_000.0)
        p.computeDerivedData()
        // dDist(1) = (dist(2) - dist(0)) / 2 ≈ 100, dt(1) = (20000 - 0) / 2000 = 10s
        // speed = 100/10 ≈ 10 m/s
        // tolérance choisie car 0.000898315° ↔ ~99.89 m via Haversine (et non 100.000),
        // donc l'écart attendu est d'environ 1.1e-2 m/s — 0.05 reste très conservateur.
        assertTrue(abs(p.speed(1) - 10.0) < 0.05, "got speed=${p.speed(1)}")
    }

    // ---- 7. coordinatesAt returns degrees ------------------------------------

    @Test
    fun coordinatesAt_returns_degrees() {
        val p = pathFromLatLonEle(Triple(45.0, 6.0, 1000.0))
        val c = p.coordinatesAt(0)
        assertEquals(45.0, c.latitude, 1e-12)
        assertEquals(6.0, c.longitude, 1e-12)
        assertEquals(1000.0, c.elevation!!, 1e-12)
        // round-trip check
        assertEquals(45.0, p.latitudeDeg(0), 1e-12)
        assertEquals(6.0, p.longitudeDeg(0), 1e-12)
    }

    // ---- 8. forEachPoint iterates over all indices ---------------------------

    @Test
    fun forEachPoint_iterates_over_all_indices() {
        val p =
            pathFromLatLonEle(
                Triple(0.0, 0.0, 1.0),
                Triple(1.0, 1.0, 2.0),
                Triple(2.0, 2.0, 3.0),
                Triple(3.0, 3.0, 4.0),
            )
        var counter = 0
        val seen = mutableListOf<Int>()
        p.forEachPoint { i ->
            counter++
            seen.add(i)
        }
        assertEquals(p.size, counter)
        assertEquals(listOf(0, 1, 2, 3), seen)
    }

    // ---- 9. indices is 0 until size ------------------------------------------

    @Test
    fun indices_is_zero_until_size() {
        val p = Path(5)
        assertEquals(0 until 5, p.indices)
        val empty = Path(0)
        assertEquals(0 until 0, empty.indices)
    }

    // ---- 10. copy produces a distinct instance with same data ----------------

    @Test
    fun copy_produces_distinct_instance_with_same_data_and_stats() {
        val p =
            pathFromLatLonEle(
                Triple(0.0, 0.0, 50.0),
                Triple(0.0, 0.00898315, 50.0),
                Triple(0.0, 0.01796630, 50.0),
            )
        p.computeDerivedData()
        val other = p.copy()
        assertNotSame(p, other)
        assertEquals(p.size, other.size)
        assertEquals(p.totalDistance, other.totalDistance)
        assertEquals(p.boundsRad, other.boundsRad)
        // All slots match
        for (i in 0 until p.size) {
            assertEquals(p.latitude(i), other.latitude(i))
            assertEquals(p.longitude(i), other.longitude(i))
            assertEquals(p.elevation(i), other.elevation(i))
            assertEquals(p.distance(i), other.distance(i))
        }
        // Mutation on the copy does not affect the original
        val originalLat = p.latitude(0)
        other.setLatitude(0, 99.9 * MathConstants.DEG_TO_RAD)
        assertEquals(originalLat, p.latitude(0))
        assertNotEquals(other.latitude(0), p.latitude(0))
    }

    // ---- 11. subPath extracts a slice ----------------------------------------

    @Test
    fun subPath_extracts_three_points_with_correct_latitudes() {
        val p =
            pathFromLatLonEle(
                Triple(0.0, 0.0, 1.0),
                Triple(1.0, 1.0, 2.0),
                Triple(2.0, 2.0, 3.0),
                Triple(3.0, 3.0, 4.0),
                Triple(4.0, 4.0, 5.0),
            )
        val sub = p.subPath(1, 4)
        assertEquals(3, sub.size)
        assertEquals(p.latitude(1), sub.latitude(0))
        assertEquals(p.latitude(2), sub.latitude(1))
        assertEquals(p.latitude(3), sub.latitude(2))
        assertEquals(p.elevation(1), sub.elevation(0))
        assertEquals(p.elevation(3), sub.elevation(2))
    }

    // ---- 12. subPath empty range ---------------------------------------------

    @Test
    fun subPath_zero_zero_returns_empty_path() {
        val p =
            pathFromLatLonEle(
                Triple(0.0, 0.0, 1.0),
                Triple(1.0, 1.0, 2.0),
            )
        val sub = p.subPath(0, 0)
        assertEquals(0, sub.size)
    }

    // ---- 13. subPath rejects out-of-bounds indices ---------------------------

    @Test
    fun subPath_rejects_out_of_bounds_indices() {
        val p =
            pathFromLatLonEle(
                Triple(0.0, 0.0, 1.0),
                Triple(1.0, 1.0, 2.0),
                Triple(2.0, 2.0, 3.0),
            )
        assertFailsWith<IllegalArgumentException> { p.subPath(-1, 2) }
        assertFailsWith<IllegalArgumentException> { p.subPath(0, 99) }
        assertFailsWith<IllegalArgumentException> { p.subPath(2, 1) } // until < from
        assertFailsWith<IllegalArgumentException> { p.subPath(4, 5) } // from > size
    }

    // ---- 14. fromCoordinates populates lat/lon/elevation ---------------------

    @Test
    fun fromCoordinates_populates_lat_lon_elevation_correctly() {
        val coords =
            listOf(
                LatLonElevation(45.0, 6.0, 1000.0),
                LatLonElevation(45.001, 6.001, 1010.0),
                LatLonElevation(45.002, 6.002, 1020.0),
            )
        val p = Path.fromCoordinates(coords)
        assertEquals(3, p.size)
        assertEquals(45.0 * MathConstants.DEG_TO_RAD, p.latitude(0), 1e-12)
        assertEquals(6.0 * MathConstants.DEG_TO_RAD, p.longitude(0), 1e-12)
        assertEquals(1000.0, p.elevation(0))
        assertEquals(45.002 * MathConstants.DEG_TO_RAD, p.latitude(2), 1e-12)
        assertEquals(1020.0, p.elevation(2))
        // Reverse via degree accessor
        assertEquals(45.0, p.latitudeDeg(0), 1e-12)
        assertEquals(6.0, p.longitudeDeg(0), 1e-12)
    }

    // ---- 15. coordinatesElevationSequence ------------------------------------

    @Test
    fun coordinatesElevationSequence_has_the_same_size_as_the_path() {
        val coords =
            listOf(
                LatLonElevation(45.0, 6.0, 1000.0),
                LatLonElevation(45.001, 6.001, 1010.0),
                LatLonElevation(45.002, 6.002, 1020.0),
                LatLonElevation(45.003, 6.003, 1030.0),
            )
        val p = Path.fromCoordinates(coords)
        val list = p.coordinatesElevationSequence().toList()
        assertEquals(p.size, list.size)
        assertEquals(45.0, list[0].latitude, 1e-12)
        assertEquals(1030.0, list[3].elevation)
    }

    // ---- 16. durationMs sentinel ---------------------------------------------

    @Test
    fun durationMs_is_consistent_with_time_endpoints() {
        val p =
            pathFromLatLonEle(
                Triple(0.0, 0.0, 50.0),
                Triple(0.0, 0.001, 50.0),
                Triple(0.0, 0.002, 50.0),
            )
        p.setTime(0, 1_000.0)
        p.setTime(1, 6_000.0)
        p.setTime(2, 11_000.0)
        p.computeDerivedData()
        assertEquals(10_000.0, p.durationMs)
        // elapsed(0) == 0, elapsed(N-1) == durationMs / 1000
        assertEquals(0.0, p.elapsed(0))
        assertEquals(10.0, p.elapsed(2))
    }

    // ---- 17. boundsRad is non-EMPTY after computeDerivedData with >= 1 point -

    @Test
    fun boundsRad_is_not_empty_after_computeDerivedData_with_points() {
        val p =
            pathFromLatLonEle(
                Triple(10.0, 20.0, 100.0),
                Triple(11.0, 21.0, 200.0),
                Triple(12.0, 22.0, 150.0),
            )
        p.computeDerivedData()
        assertNotEquals(Path.BoundsRad.EMPTY, p.boundsRad)
        // Latitudes in radians range [10°, 12°]
        assertEquals(10.0 * MathConstants.DEG_TO_RAD, p.boundsRad.minLat, 1e-12)
        assertEquals(12.0 * MathConstants.DEG_TO_RAD, p.boundsRad.maxLat, 1e-12)
        assertEquals(20.0 * MathConstants.DEG_TO_RAD, p.boundsRad.minLon, 1e-12)
        assertEquals(22.0 * MathConstants.DEG_TO_RAD, p.boundsRad.maxLon, 1e-12)
        // Sanity: max lat in radians is < PI/2
        assertTrue(p.boundsRad.maxLat < PI / 2.0)
    }

    // ---- 18. subPath then copy is identical to subPath then copy -------------

    @Test
    fun subPath_then_copy_produces_equivalent_path() {
        val p =
            pathFromLatLonEle(
                Triple(0.0, 0.0, 1.0),
                Triple(1.0, 1.0, 2.0),
                Triple(2.0, 2.0, 3.0),
                Triple(3.0, 3.0, 4.0),
                Triple(4.0, 4.0, 5.0),
                Triple(5.0, 5.0, 6.0),
            )
        val a = p.subPath(2, 5)
        val b = a.copy()
        assertNotSame(a, b)
        assertEquals(a.size, b.size)
        for (i in 0 until a.size) {
            for (field in PointField.entries) {
                assertEquals(a.get(i, field), b.get(i, field), "field=$field i=$i")
            }
        }
        // Order-of-ops alternative: copy from p first, then subPath
        val c = p.copy().subPath(2, 5)
        assertEquals(a.size, c.size)
        for (i in 0 until a.size) {
            for (field in PointField.entries) {
                assertEquals(a.get(i, field), c.get(i, field), "field=$field i=$i (copy-then-sub)")
            }
        }
    }
}
