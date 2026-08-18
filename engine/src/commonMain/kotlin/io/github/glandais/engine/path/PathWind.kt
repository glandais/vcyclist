package io.github.glandais.engine.path

import io.github.glandais.elevation.MathConstants
import io.github.glandais.elevation.Vector3D
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * The wind direction that would be **most unfavourable on average** over a course: the opposite
 * of its dominant orientation.
 *
 * This is not a weather lookup. It answers a single question: *"which constant wind makes this
 * ride as hard as it can be?"*, and its natural use is to configure a `WindProviderConstant`
 * before a simulation.
 *
 * ## What it computes
 *
 * For every point after the first, the **unit** vector from the start to that point ; the mean of
 * those vectors ; then its opposite, normalised. Because each vector is normalised before being
 * averaged, distance does not weigh in — a point 40 km out counts exactly as much as one 400 m
 * out. That is deliberate: it makes the result a mean *bearing*, which is the quantity a rider
 * intuits as "the direction of the day".
 *
 * ## Frame
 *
 * The returned vector is in a local **east-north** frame: `x` points east, `y` points north, `z`
 * is always `0`. Callers working in Web Mercator screen coordinates, where `y` points *south*,
 * will see the negation of these values on that axis for the same direction — see `PathWindTest`,
 * which cross-checks the azimuth against a Mercator implementation of the same formula.
 *
 * @return a unit vector, or `null` when the question has no answer: fewer than 4 points (the
 *   threshold), or an out-and-back so symmetric that the mean cancels out. Returning
 *   `Vector(0, 0, 0)` as if it were normalised would be a trap — a zero vector claiming to be a
 *   direction — so the answer is `null` instead.
 */
fun Path.dominantHeadwindDirection(): Vector3D? = listOf(this).dominantHeadwindDirection()

/**
 * The same over several paths — the tracks of a multi-track GPX, say. Each path contributes its
 * own mean direction, and the means are summed with **equal weight** whatever their point count,
 * then negated and normalised.
 *
 * Paths of fewer than 4 points contribute nothing rather than making the whole call fail.
 */
fun List<Path>.dominantHeadwindDirection(): Vector3D? {
    var sumX = 0.0
    var sumY = 0.0
    var contributing = 0

    for (path in this) {
        val mean = path.meanDirectionFromStart() ?: continue
        sumX += mean.first
        sumY += mean.second
        contributing++
    }
    if (contributing == 0) return null

    // Negate: the answer is the wind that opposes the dominant direction of travel.
    val magnitude = hypot(sumX, sumY)
    if (magnitude < DEGENERATE_EPSILON) return null
    return Vector3D(-sumX / magnitude, -sumY / magnitude, 0.0)
}

/**
 * Mean of the unit vectors start → point, in the local east-north frame, or `null` if the path is
 * too short to say anything.
 *
 * The projection is a local equirectangular one — `x = Δlon · cos(lat₀)`, `y = Δlat` — rather than
 * the Web Mercator a map renderer would use. The two agree on bearings here: Mercator is
 * conformal, so it preserves local angles, and the normalisation of each vector removes the scale
 * distortion that is Mercator's only other effect. `PathWindTest` verifies the agreement against a
 * literal Mercator implementation instead of taking the argument on trust.
 */
private fun Path.meanDirectionFromStart(): Pair<Double, Double>? {
    // Threshold: `size > 3`. Below it a "dominant direction" is noise, not a summary.
    if (size <= 3) return null

    val lat0 = latitude(0)
    val lon0 = longitude(0)
    val cosLat0 = cos(lat0)

    var sumX = 0.0
    var sumY = 0.0
    // Index 0 is skipped: its vector to itself is zero and carries no direction. Including it and
    // dividing by `size` rather than `size - 1` would come to the same thing anyway — a uniform
    // scale factor on a mean that is normalised afterwards cannot change the direction.
    for (i in 1 until size) {
        val dx = (longitude(i) - lon0) * cosLat0
        val dy = latitude(i) - lat0
        val length = hypot(dx, dy)
        if (length < DEGENERATE_EPSILON) continue
        sumX += dx / length
        sumY += dy / length
    }

    val n = size - 1
    return Pair(sumX / n, sumY / n)
}

/**
 * Below this, a vector's direction is numerical noise. In radians of arc, `1e-12` is about 6 µm
 * on the ground — far under any GPS resolution, so nothing real is discarded.
 */
private const val DEGENERATE_EPSILON = 1e-12

/**
 * The same answer as [dominantHeadwindDirection], as an azimuth in degrees **ready to hand to
 * `Wind(speedMS, directionRad)`** — convert with `MathConstants.DEG_TO_RAD` and nothing else
 * (task g31).
 *
 * ## The convention, established by experiment
 *
 * `AeroPowerProvider`'s KDoc calls `Wind.directionRad` meteorological — the direction the wind
 * blows *from*. It behaves as the opposite: the direction the wind blows **toward**, `0` = north.
 * The reason is `Path.computeBearing`, which returns `atan2(-dy, dx)` over a north-positive `y`,
 * so a northbound rider has a bearing of `-π/2` rather than `+π/2`. That sign is load-bearing
 * throughout the physics; it flips the meaning of the wind angle downstream.
 *
 * So this function returns the azimuth of [dominantHeadwindDirection] as is, with no 180° flip.
 * That is not deduced from reading the code — the first version of this function *did* flip it,
 * and the simulated ride came out 50 % **faster**. `PathWindAzimuthTest` runs the simulation both
 * ways round and asserts the answer slows the ride down while its opposite speeds it up, because
 * nothing in the types can catch this and both answers look equally plausible on paper.
 *
 * @return `NaN` when [dominantHeadwindDirection] has no answer: fewer than 4 points, or a course
 *   whose mean direction cancels out. `NaN` rather than `null` because a `Double?` crosses to
 *   JavaScript as `number | null`, forcing two checks on the caller, and because `0.0` is a valid
 *   answer that must not be confused with "no answer".
 */
fun Path.dominantHeadwindAzimuthDeg(): Double = listOf(this).dominantHeadwindAzimuthDeg()

/** Multi-path form of [dominantHeadwindAzimuthDeg]. */
fun List<Path>.dominantHeadwindAzimuthDeg(): Double {
    val direction = dominantHeadwindDirection() ?: return Double.NaN
    // atan2(x, y): x is east, y is north — a compass azimuth of where the wind goes, which is what
    // `Wind.directionRad` turns out to mean. See the KDoc above before "fixing" this by 180°.
    return (atan2(direction.x, direction.y) * MathConstants.RAD_TO_DEG).mod(360.0)
}
