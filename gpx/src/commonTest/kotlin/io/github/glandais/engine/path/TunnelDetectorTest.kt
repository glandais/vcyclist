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

    /**
     * `sample.gpx` km 18.50–19.95 on Mapterhorn z12, every 5 m: the D 109 at Héry-sur-Ugine climbing a
     * real hill, a 14 m gully at km 18.80 where the road crosses a stream on a bridge the terrain model
     * drops. Mirrored, the hill has a bridged stream at each foot — a common shape anywhere.
     */
    private val d109: DoubleArray =
        """
            922.6 922.8 922.9 923.0 923.3 923.8 924.3 924.8 925.0 925.1 925.2 925.9 926.4 926.8 927.1
            927.0 926.9 927.1 927.2 927.7 927.9 928.0 928.2 928.3 928.4 928.8 929.1 929.5 929.9 930.3
            930.7 931.0 931.4 931.6 932.1 933.0 932.7 932.4 932.2 932.0 932.3 933.2 933.5 933.4 933.7
            934.7 935.2 935.9 936.7 937.5 937.7 937.9 938.1 938.6 938.9 939.2 938.7 938.0 937.2 934.1
            930.3 926.9 925.0 927.3 931.5 935.4 938.8 940.5 940.9 941.3 941.2 941.4 941.6 941.5 941.5
            941.4 941.0 940.4 940.7 941.1 941.2 941.1 941.5 942.5 942.6 942.4 942.7 943.1 943.6 943.7
            943.7 943.8 944.2 944.8 945.1 945.5 945.7 945.8 946.3 946.6 947.1 947.6 948.1 948.3 949.0
            949.3 948.6 947.3 945.1 944.1 944.8 946.9 950.1 951.8 953.2 954.3 954.7 954.9 955.2 955.7
            956.2 956.5 956.8 957.1 957.7 958.4 959.1 959.4 959.8 960.2 960.8 961.3 961.8 962.5 963.0
            963.3 963.7 964.4 964.9 965.7 966.8 967.2 967.5 968.1 968.7 969.3 969.8 970.1 970.1 970.5
            971.3 971.8 972.0 972.1 972.8 973.3 973.5 974.0 974.6 975.0 975.0 975.4 976.1 976.9 977.0
            977.3 977.7 978.1 978.4 978.7 979.2 980.0 980.6 980.7 981.1 981.9 982.5 982.7 983.1 983.7
            984.1 984.4 984.7 985.0 985.2 985.4 985.6 985.8 986.3 986.3 986.4 986.8 987.4 987.9 987.9
            988.0 987.9 987.6 987.6 987.8 987.3 987.6 988.1 987.7 987.3 987.0 987.2 987.4 987.6 987.8
            987.7 987.6 987.6 987.8 988.0 988.2 988.4 988.5 988.5 988.6 988.7 989.0 989.3 989.6 989.8
            990.1 990.2 990.3 990.7 991.0 991.3 991.7 992.0 992.3 992.3 992.5 992.9 993.5 993.3 992.9
            993.5 994.0 994.0 994.4 995.4 995.7 995.2 995.8 996.5 996.6 996.4 996.3 996.3 997.4 998.5
            999.2 999.7 999.7 999.0 999.5 1000.6 1000.6 1000.7 1000.3 1000.0 1000.3 1000.5 1000.1 999.9
            999.3 999.3 999.3 999.4 999.4 999.3 999.2 999.2 999.9 999.7 999.3 999.1 998.9 998.9 999.3
            999.4 999.5 1000.2 1000.8 1000.6 1000.4 1001.1
        """.trim()
            .split(Regex("\\s+"))
            .map { it.toDouble() }
            .toDoubleArray()

    @Test
    fun a_real_hill_between_two_bridged_gullies_is_no_tunnel() {
        val z = d109 + d109.reversedArray().copyOfRange(1, d109.size)
        assertEquals(emptyList(), detect(z), "the hill keeps its 70 m climb")
        // Without the face criterion (it reaches over the whole span) the gully walls pass for portals.
        val noFace = TunnelDetectorOptions(faceReachM = 3_000.0)
        val route = straight(z.size)
        val span = TunnelDetector.detect(TunnelProfile(z, route.first, route.second), noFace).single()
        assertTrue(span.heightM > 60.0 && span.lengthM > 2_000.0, "what the face criterion refuses: $span")
    }

    @Test
    fun a_covered_island_between_two_holes_read_as_zero_is_no_tunnel() {
        // A provider without a no-data fallback (a WASI host) reads a hole as 0 m.
        // Station 99 is the interpolated edge of the hole: the portal lands there, not on the hole.
        fun island(hole: Double) =
            DoubleArray(300) {
                if (it in 100..200) {
                    300.0
                } else if (it == 99 || it == 201) {
                    hole + 1.5
                } else {
                    hole
                }
            }
        assertEquals(emptyList(), detect(island(0.0)))
        assertEquals(1, detect(island(1.0)).size, "the same shape on real ground is a tunnel")
    }

    @Test
    fun windows_must_be_whole_stations() {
        assertFailsWith<IllegalArgumentException> { TunnelDetectorOptions(footWindowM = 22.0) }
        assertFailsWith<IllegalArgumentException> { TunnelDetectorOptions(faceReachM = 0.0) }
        assertFailsWith<IllegalArgumentException> { TunnelDetectorOptions(minStraightness = 1.5) }
        assertFailsWith<IllegalArgumentException> { TunnelProfile(DoubleArray(3), DoubleArray(2), DoubleArray(3)) }
    }
}
