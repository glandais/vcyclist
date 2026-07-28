package io.github.glandais.engine.path

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.physics.Wind
import io.github.glandais.engine.physics.WindProviderConstant
import io.github.glandais.engine.physics.WindProviderNone
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task g31: [dominantHeadwindAzimuthDeg], the form the physics engine can consume.
 *
 * The conversion this function performs is one `+ 180°`, and getting it backwards would hand the
 * caller a perfect **tailwind** while they asked for the worst case — a mistake no type can catch
 * and no unit test on angles would notice either, since both answers look equally plausible. So
 * the load-bearing test here runs the simulation and checks the ride actually gets slower.
 */
class PathWindAzimuthTest {
    /** A straight ~4 km line of 40 points, due north from (45°, 6°). */
    private fun northboundPath(): Path {
        val p = Path(40)
        for (i in 0 until 40) {
            p.setLatitude(i, (45.0 + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, 6.0 * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 200.0)
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()
        return p
    }

    private fun assertAzimuthEquals(
        expected: Double,
        actual: Double,
        toleranceDeg: Double = 1.0,
    ) {
        val diff = abs(((actual - expected + 540.0) % 360.0) - 180.0)
        assertTrue(diff <= toleranceDeg, "expected $expected° ± $toleranceDeg, got $actual°")
    }

    @Test
    fun `case 01 — riding north, the worst wind is 180 in the engine's convention`() {
        // Not 0: `Wind.directionRad` means the direction the wind blows *toward* — see the KDoc,
        // and cases 06 and 07, which settle it by simulation rather than by reading the code.
        assertAzimuthEquals(180.0, northboundPath().dominantHeadwindAzimuthDeg())
    }

    @Test
    fun `case 02 — riding east, the worst wind is 270`() {
        val p = Path(40)
        for (i in 0 until 40) {
            p.setLatitude(i, 45.0 * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (6.0 + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 200.0)
        }
        p.computeDerivedData()

        assertAzimuthEquals(270.0, p.dominantHeadwindAzimuthDeg())
    }

    @Test
    fun `case 03 — it is exactly the vector form, as an azimuth`() {
        val path = northboundPath()

        val vector = path.dominantHeadwindDirection()!!
        val expected = (kotlin.math.atan2(vector.x, vector.y) * MathConstants.RAD_TO_DEG).mod(360.0)

        assertEquals(expected, path.dominantHeadwindAzimuthDeg(), 1e-9)
    }

    @Test
    fun `case 04 — no answer is NaN, not zero`() {
        val tooShort = Path(3)
        for (i in 0 until 3) {
            tooShort.setLatitude(i, (45.0 + i * 0.001) * MathConstants.DEG_TO_RAD)
            tooShort.setLongitude(i, 6.0 * MathConstants.DEG_TO_RAD)
        }
        tooShort.computeDerivedData()

        assertTrue(tooShort.dominantHeadwindAzimuthDeg().isNaN(), "0.0 is a valid answer, so it cannot mean 'none'")
    }

    @Test
    fun `case 05 — the multi-path form agrees with the single one`() {
        val path = northboundPath()

        assertEquals(path.dominantHeadwindAzimuthDeg(), listOf(path).dominantHeadwindAzimuthDeg(), 1e-12)
    }

    /**
     * The one that matters: feed the answer straight into `WindProviderConstant` and check the
     * simulated ride is **slower** than with no wind. A 180° error would make it faster.
     */
    @Test
    fun `case 06 — the wind it names actually slows the ride down`() =
        runTest {
            val path = northboundPath()
            val azimuthDeg = path.dominantHeadwindAzimuthDeg()
            val options = EnhanceOptions.DEFAULT.copy(fixElevation = false)

            val calm =
                Enhancer.enhanceCourse(
                    CoursePhysics(course = Course(path = path.copy()), windProvider = WindProviderNone),
                    options,
                )
            val windy =
                Enhancer.enhanceCourse(
                    CoursePhysics(
                        course = Course(path = path.copy()),
                        windProvider =
                            WindProviderConstant(Wind(speedMS = 8.0, directionRad = azimuthDeg * MathConstants.DEG_TO_RAD)),
                    ),
                    options,
                )

            val calmSpeed = calm.totalDistance / (calm.durationMs / 1000.0)
            val windySpeed = windy.totalDistance / (windy.durationMs / 1000.0)
            assertTrue(
                windySpeed < calmSpeed,
                "the worst-case wind must slow the ride: calm $calmSpeed m/s vs windy $windySpeed m/s",
            )
        }

    /** And the opposite direction must speed it up — otherwise case 06 proves nothing. */
    @Test
    fun `case 07 — the reciprocal direction is a tailwind`() =
        runTest {
            val path = northboundPath()
            val worst = path.dominantHeadwindAzimuthDeg()
            val best = (worst + 180.0).mod(360.0)
            val options = EnhanceOptions.DEFAULT.copy(fixElevation = false)

            suspend fun rideWith(azimuthDeg: Double): Double {
                val result =
                    Enhancer.enhanceCourse(
                        CoursePhysics(
                            course = Course(path = path.copy()),
                            windProvider =
                                WindProviderConstant(
                                    Wind(speedMS = 8.0, directionRad = azimuthDeg * MathConstants.DEG_TO_RAD),
                                ),
                        ),
                        options,
                    )
                return result.totalDistance / (result.durationMs / 1000.0)
            }

            assertTrue(rideWith(best) > rideWith(worst), "the opposite wind must help, or case 06 means nothing")
        }
}
