package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.path.Path

/**
 * Wind conditions at a point.
 *
 * @param speedMS wind speed in m/s
 * @param directionRad direction in radians (0 = North, π/2 = East, π = South, 3π/2 = West)
 */
data class Wind(
    val speedMS: Double,
    val directionRad: Double,
) {
    companion object {
        /** Zero-allocation sentinel for "no wind". Equal to `Wind(0.0, 0.0)`. */
        val NONE = Wind(0.0, 0.0)
    }
}

/** Returns wind conditions [Wind] at a given point on the course. */
fun interface WindProvider {
    fun wind(
        course: Course,
        path: Path,
        pointIndex: Int,
    ): Wind
}

/** No wind anywhere ; equivalent to perfectly calm conditions. */
object WindProviderNone : WindProvider {
    override fun wind(
        course: Course,
        path: Path,
        pointIndex: Int,
    ): Wind = Wind.NONE
}

/** Same [Wind] returned for every point. The wind instance is stored as-is (no defensive copy). */
class WindProviderConstant(
    private val wind: Wind,
) : WindProvider {
    override fun wind(
        course: Course,
        path: Path,
        pointIndex: Int,
    ): Wind = wind
}
