package io.github.glandais.engine.path

import io.github.glandais.elevation.MathConstants
import io.github.glandais.elevation.Vector3D
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task g26: [dominantHeadwindDirection], ported from `GPXDataComputer.getWind`.
 *
 * Directions are asserted as **azimuths in degrees** rather than raw components: that is the
 * quantity the function is about, and it makes a failure readable ("expected 180°, got 173°")
 * instead of a pair of unit-vector components nobody can picture.
 */
class PathWindTest {
    /** Azimuth in degrees, clockwise from north — 0 = north, 90 = east, 180 = south. */
    private fun Vector3D.azimuthDeg(): Double {
        val deg = atan2(x, y) * MathConstants.RAD_TO_DEG
        return if (deg < 0) deg + 360.0 else deg
    }

    private fun assertAzimuth(
        expected: Double,
        actual: Vector3D,
        toleranceDeg: Double = 1.0,
        message: String = "",
    ) {
        val got = actual.azimuthDeg()
        val diff = abs(((got - expected + 540.0) % 360.0) - 180.0)
        assertTrue(diff <= toleranceDeg, "$message expected $expected° ± $toleranceDeg, got $got°")
    }

    /** A straight line of [n] points from (45°, 6°), stepping by ([dLat], [dLon]) degrees. */
    private fun line(
        n: Int = 10,
        dLat: Double = 0.001,
        dLon: Double = 0.0,
        lat0: Double = 45.0,
        lon0: Double = 6.0,
    ): Path {
        val p = Path(n)
        for (i in 0 until n) {
            p.setLatitude(i, (lat0 + i * dLat) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (lon0 + i * dLon) * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 1000.0)
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()
        return p
    }

    @Test
    fun `case 01 — riding north, the worst wind blows toward the south`() {
        val wind = line(dLat = 0.001, dLon = 0.0).dominantHeadwindDirection()!!

        assertAzimuth(180.0, wind, message = "the vector points where the wind goes, i.e. south:")
        assertEquals(1.0, hypot(wind.x, wind.y), 1e-9, "must be a unit vector")
    }

    @Test
    fun `case 02 — a line due east gives a wind toward the west`() {
        val wind = line(dLat = 0.0, dLon = 0.001).dominantHeadwindDirection()!!

        assertAzimuth(270.0, wind)
    }

    @Test
    fun `case 03 — a line due south-west`() {
        // A degree of longitude is shorter than a degree of latitude by cos(lat): riding exactly
        // south-west means moving 1/cos(45°) as many degrees of longitude as of latitude. Getting
        // this wrong is precisely what the cos(lat0) factor in the projection is there to fix, so
        // the test states it explicitly rather than assuming a square grid.
        val dLon = -0.001 / cos(45.0 * MathConstants.DEG_TO_RAD)
        val wind = line(dLat = -0.001, dLon = dLon).dominantHeadwindDirection()!!

        // Riding south-west (225°), the opposing direction is north-east (45°).
        assertAzimuth(45.0, wind)
    }

    @Test
    fun `case 03b — a naive square grid would not be south-west`() {
        // Same numbers without the cos(lat) correction: the direction is measurably different,
        // which is what makes case 03 an assertion about the projection and not about arithmetic.
        val wind = line(dLat = -0.001, dLon = -0.001).dominantHeadwindDirection()!!

        assertAzimuth(35.26, wind, toleranceDeg = 0.1)
    }

    @Test
    fun `case 04 — a perfect out-and-back has no dominant direction`() {
        val p = Path(9)
        val lats = doubleArrayOf(45.0, 45.001, 45.002, 45.003, 45.004, 45.003, 45.002, 45.001, 45.0)
        for (i in lats.indices) {
            p.setLatitude(i, lats[i] * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, 6.0 * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 1000.0)
        }
        p.computeDerivedData()

        // Every outbound point is revisited on the way back, so the mean of the unit vectors is
        // the same vector twice — it does *not* cancel. What matters is that the answer is still
        // the outbound direction's opposite, not a silent zero.
        assertAzimuth(180.0, p.dominantHeadwindDirection()!!)
    }

    @Test
    fun `case 05 — a path of 3 points is refused`() {
        assertNull(line(n = 3).dominantHeadwindDirection(), "gpx2web's threshold is size > 3")
    }

    @Test
    fun `case 06 — a path of 4 points answers`() {
        assertTrue(line(n = 4).dominantHeadwindDirection() != null)
    }

    @Test
    fun `case 07 — an empty path is refused, without crashing`() {
        assertNull(Path(0).dominantHeadwindDirection())
    }

    @Test
    fun `case 08 — the z component is exactly zero`() {
        assertEquals(0.0, line().dominantHeadwindDirection()!!.z)
    }

    @Test
    fun `case 09 — the result is a unit vector`() {
        val wind = line(dLat = 0.0007, dLon = 0.0013).dominantHeadwindDirection()!!
        assertEquals(1.0, wind.magnitude(), 1e-9)
    }

    @Test
    fun `case 10 — two opposite paths cancel to no answer`() {
        val north = line(dLat = 0.001, dLon = 0.0)
        val south = line(dLat = -0.001, dLon = 0.0)

        assertNull(listOf(north, south).dominantHeadwindDirection(), "the two means cancel exactly")
    }

    @Test
    fun `case 11 — a short path contributes nothing but does not veto the others`() {
        val long = line(n = 10, dLat = 0.001, dLon = 0.0)
        val tooShort = line(n = 3, dLat = 0.0, dLon = 0.001)

        val combined = listOf(long, tooShort).dominantHeadwindDirection()!!
        assertAzimuth(180.0, combined, message = "the 3-point path must be ignored, not blended in:")
    }

    /**
     * The projection cross-check the task sheet asks for: the port uses a local equirectangular
     * frame, gpx2web uses Web Mercator at zoom 12. Both are compared on the same real-ish trace,
     * with the reference formula reimplemented literally here.
     */
    @Test
    fun `case 12 — the azimuth matches a literal Mercator implementation`() {
        val path = alpinePath()

        val mine = path.dominantHeadwindDirection()!!.azimuthDeg()
        val reference = mercatorReferenceAzimuthDeg(path)

        val diff = abs(((mine - reference + 540.0) % 360.0) - 180.0)
        assertTrue(diff < 1.0, "equirectangular $mine° vs Mercator $reference°, diff $diff°")
    }

    @Test
    fun `case 13 — the cross-check holds on a long, high-latitude trace too`() {
        // Mercator's scale distortion grows with latitude and with north-south extent; this is
        // where the two projections would part company if they were going to.
        val path = line(n = 200, dLat = 0.01, dLon = 0.004, lat0 = 60.0)

        val mine = path.dominantHeadwindDirection()!!.azimuthDeg()
        val reference = mercatorReferenceAzimuthDeg(path)

        val diff = abs(((mine - reference + 540.0) % 360.0) - 180.0)
        assertTrue(diff < 1.0, "equirectangular $mine° vs Mercator $reference°, diff $diff° over 2° of latitude")
    }

    /** A climb that turns: 60 points, north-east then north, around 45°N. */
    private fun alpinePath(): Path {
        val p = Path(60)
        var lat = 45.68
        var lon = 6.39
        for (i in 0 until 60) {
            p.setLatitude(i, lat * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, lon * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 350.0 + i * 12.0)
            lat += 0.0008
            lon += if (i < 30) 0.0011 else 0.0002
        }
        p.computeDerivedData()
        return p
    }

    /**
     * `GPXDataComputer.getWindUnscaled` transcribed literally: project every point through Web
     * Mercator (`MagicPower2MapSpace.INSTANCE_256`, zoom 12), take the normalised vector from the
     * first point to each, average, negate.
     *
     * Mercator screen `y` grows **southward**, so the azimuth is read with `-y` to bring it back
     * into the east-north frame the port returns.
     */
    private fun mercatorReferenceAzimuthDeg(path: Path): Double {
        val tileSize = 256.0
        val zoom = 12
        val worldSize = tileSize * (1 shl zoom)

        fun lonToX(lonDeg: Double) = (lonDeg + 180.0) / 360.0 * worldSize

        fun latToY(latDeg: Double): Double {
            val latRad = latDeg * MathConstants.DEG_TO_RAD
            val mercator = ln(tan(PI / 4.0 + latRad / 2.0))
            return (1.0 - mercator / PI) / 2.0 * worldSize
        }

        val x0 = lonToX(path.latitudeDeg(0).let { path.longitudeDeg(0) })
        val y0 = latToY(path.latitudeDeg(0))
        var sumX = 0.0
        var sumY = 0.0
        for (i in 1 until path.size) {
            val dx = lonToX(path.longitudeDeg(i)) - x0
            val dy = latToY(path.latitudeDeg(i)) - y0
            val length = hypot(dx, dy)
            if (length == 0.0) continue
            sumX += dx / length
            sumY += dy / length
        }
        // Negate for the headwind, and flip y from screen-down to north-up.
        val windX = -sumX
        val windY = sumY
        val deg = atan2(windX, windY) * MathConstants.RAD_TO_DEG
        return if (deg < 0) deg + 360.0 else deg
    }
}
