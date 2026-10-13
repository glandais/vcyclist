package io.github.glandais.engine.path

import io.github.glandais.elevation.Distance
import io.github.glandais.elevation.LatLon
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Synthetic terrain profiles on the 5 m grid, DEFAULT options, for [TunnelDetector]. Each negative is
 * a road a tunnel corrector must leave alone; the shapes are the ones the E4 bench measured.
 */
class TunnelDetectorTest {
    private val step = 5.0
    private val lat0 = 45.0
    private val lon0 = 6.0
    private val metresPerDegreeLat = Distance.haversine(LatLon(lat0, lon0), LatLon(lat0 + 0.001, lon0)) * 1000

    /** A straight northward route of [n] stations. */
    private fun straight(n: Int): Pair<DoubleArray, DoubleArray> =
        DoubleArray(n) { lat0 + it * step / metresPerDegreeLat } to DoubleArray(n) { lon0 }

    private fun detect(
        z: DoubleArray,
        route: Pair<DoubleArray, DoubleArray> = straight(z.size),
    ): List<TunnelSpan> = TunnelDetector.detect(TunnelProfile(z, route.first, route.second))

    /** A road of grade [grade] from [z0], station by station. */
    private fun road(
        n: Int,
        grade: Double,
        z0: Double = 500.0,
    ) = DoubleArray(n) { z0 + grade * it * step }

    /**
     * A mountain over stations [a]..[b]: cut faces rising [face] over the first and last 40 m, then a
     * dome [height] m above the road.
     */
    private fun DoubleArray.mountain(
        a: Int,
        b: Int,
        height: Double,
        face: Double = 1.0,
    ): DoubleArray {
        for (k in a..b) {
            val fromEnd = min(k - a, b - k) * step
            val dome = height * sin(PI * (k - a) / (b - a))
            this[k] += max(0.0, min(face * fromEnd, dome))
        }
        return this
    }

    @Test
    fun a_tunnel_under_a_mountain_is_found_between_its_portals() {
        val z = road(400, 0.03).mountain(100, 220, 120.0)
        val spans = detect(z)
        assertEquals(1, spans.size, "spans: $spans")
        val t = spans.single()
        assertTrue(abs(t.first - 100) <= 2 && abs(t.last - 220) <= 2, "portals ${t.first}..${t.last}")
        assertEquals(0.03, t.chordGrade, 0.005)
        assertTrue(t.heightM > 100.0, "height ${t.heightM}")
        assertTrue(t.riseGrade >= 0.30 && t.fallGrade <= -0.30)
        assertEquals(1.0, t.straightness, 1e-3)
    }

    @Test
    fun a_long_tunnel_keeps_its_far_portal_past_a_terrace_on_the_mountain() {
        // Grands Goulets: the terrain falls to a terrace high above the line, then rises again.
        val z = road(600, 0.045).mountain(100, 450, 200.0)
        for (k in 250..270) z[k] = z[249] - 0.5 * (k - 249) * step
        for (k in 271..290) z[k] = z[270]
        val spans = detect(z)
        assertEquals(1, spans.size, "spans: $spans")
        assertTrue(abs(spans.single().first - 100) <= 2 && abs(spans.single().last - 450) <= 2, "${spans.single()}")
    }

    @Test
    fun two_tunnels_with_open_road_between_are_two() {
        val z = road(700, -0.06).mountain(100, 200, 100.0).mountain(300, 420, 100.0)
        val spans = detect(z)
        assertEquals(2, spans.size, "spans: $spans")
        val expected = listOf(100 to 200, 300 to 420)
        spans.zip(expected).forEach { (t, e) ->
            assertTrue(abs(t.first - e.first) <= 2 && abs(t.last - e.second) <= 2, "$t against $e")
        }
    }

    @Test
    fun a_hairpin_climb_is_no_tunnel() {
        // 10 % with an 18 % ramp, on a zigzag of 100 m legs.
        val n = 600
        val z = DoubleArray(n)
        for (k in 1 until n) z[k] = z[k - 1] + step * (if (k in 200..260) 0.18 else 0.10)
        val lat = DoubleArray(n)
        val lon = DoubleArray(n)
        val mPerDegLon = metresPerDegreeLat * cos(lat0 * PI / 180)
        var x = 0.0
        var y = 0.0
        for (k in 0 until n) {
            lat[k] = lat0 + y / metresPerDegreeLat
            lon[k] = lon0 + x / mPerDegLon
            val leg = (k / 20) % 2
            if (leg == 0) x += step else x -= step
            if (k % 20 == 19) y += 2.0
        }
        assertEquals(emptyList(), detect(z, lat to lon))
    }

    @Test
    fun a_real_col_is_no_tunnel() {
        // 12 % up for 1 km, 12 % down: a road grade on both sides, however high the col.
        val z = DoubleArray(500) { k -> 1_000.0 + 0.12 * step * (if (k <= 200) k else 400 - k) }
        assertEquals(emptyList(), detect(z))
    }

    @Test
    fun a_steep_urban_hump_under_the_side_grade_is_no_tunnel() {
        // 25 % up for 100 m, 25 % down: steeper than any col, still not both sides impossible.
        val z = road(300, 0.0).also { for (k in 100..140) it[k] += 0.25 * step * min(k - 100, 140 - k) }
        assertEquals(emptyList(), detect(z))
    }

    /** A road at −5.5 % whose terrain reads the cliff (+22 m) and the gorge (−19 m) over [width] stations. */
    private fun balcony(width: Int): DoubleArray {
        val z = road(400, -0.055)
        for (c in listOf(100, 230)) for (k in c until c + width) z[k] += 22.0
        for (c in listOf(160, 300)) for (k in c until c + width) z[k] -= 19.0
        return z
    }

    @Test
    fun a_balcony_reading_the_cliff_in_narrow_spikes_is_left_alone() {
        // Gorges de la Bourne on IGN: spikes of one to three stations, narrower than any portal pair.
        for (width in 1..3) assertEquals(emptyList(), detect(balcony(width)), "spikes $width stations wide")
    }

    @Test
    fun a_wider_cliff_spike_is_at_most_put_back_on_the_road() {
        // From 20 m wide, a cliff read above the road has a tunnel's signature; its portals sit on the
        // road on both sides, so the correction is the road itself. The gorge, below it, is never touched.
        val z = balcony(5)
        val spans = detect(z)
        assertEquals(2, spans.size, "spans: $spans")
        for (t in spans) {
            assertEquals(-0.055, t.chordGrade, 1e-9)
            assertEquals(500.0 - 0.055 * t.first * step, z[t.first], 1e-9)
            assertTrue(t.first in 95..100 || t.first in 225..230, "$t")
        }
    }

    @Test
    fun a_gorge_glimpsed_between_two_tunnels_splits_them() {
        // Petits Goulets: open air between two rock tunnels, the terrain there reading the gorge below
        // the road. Two tunnels, never one line drawn above the gorge.
        val z = road(400, 0.0).mountain(100, 260, 120.0)
        for (k in 170 until 190) z[k] = 492.0
        val spans = detect(z)
        assertEquals(2, spans.size, "spans: $spans")
        assertTrue(spans[0].last <= 175 && spans[1].first >= 185, "$spans")
    }

    @Test
    fun a_steep_chord_is_no_road_through_a_tunnel() {
        val z = road(400, 0.15).mountain(100, 220, 120.0)
        assertEquals(emptyList(), detect(z))
    }

    @Test
    fun known_limit_a_cut_and_cover_tunnel_under_flat_ground_is_not_seen() {
        // Tuileries: 3.7–5.6 m of bump over 855 m. Below minHeightM, and the error left is small.
        val z = road(400, 0.0).mountain(100, 270, 5.0)
        assertEquals(emptyList(), detect(z))
    }

    @Test
    fun known_limit_a_curved_tunnel_is_not_seen() {
        // A half-turn under the mountain (a spiral tunnel): straightness 2/π < 0.8.
        val n = 400
        val z = road(n, 0.02).mountain(100, 220, 120.0)
        val lat = DoubleArray(n)
        val lon = DoubleArray(n)
        val mPerDegLon = metresPerDegreeLat * cos(lat0 * PI / 180)
        val r = 120 * step / PI
        for (k in 0 until n) {
            val (x, y) =
                when {
                    k < 100 -> 0.0 to k * step
                    k <= 220 -> {
                        val a = PI * (k - 100) / 120
                        r - r * cos(a) to 100 * step + r * sin(a)
                    }
                    else -> 2 * r to 100 * step - (k - 220) * step
                }
            lat[k] = lat0 + y / metresPerDegreeLat
            lon[k] = lon0 + x / mPerDegLon
        }
        assertEquals(emptyList(), detect(z, lat to lon))
        assertEquals(1, detect(z).size, "the same profile on a straight route is a tunnel")
    }

    @Test
    fun no_data_inside_a_candidate_is_no_tunnel() {
        val z = road(400, 0.03).mountain(100, 220, 120.0)
        z[160] = Double.NaN
        assertEquals(emptyList(), detect(z))
    }

    @Test
    fun windows_must_be_whole_stations() {
        assertFailsWith<IllegalArgumentException> { TunnelDetectorOptions(footWindowM = 22.0) }
        assertFailsWith<IllegalArgumentException> { TunnelDetectorOptions(minStraightness = 1.5) }
        assertFailsWith<IllegalArgumentException> { TunnelProfile(DoubleArray(3), DoubleArray(2), DoubleArray(3)) }
    }
}
