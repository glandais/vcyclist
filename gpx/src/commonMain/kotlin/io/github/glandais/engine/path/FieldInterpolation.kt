package io.github.glandais.engine.path

import kotlin.math.PI

/**
 * Point interpolation shared by the resamplers ([PointPerDistance], [PointPerSecond]).
 *
 * Every field is interpolated linearly, except the **circular** ones in [CIRCULAR_FIELDS], which
 * take the shortest arc. Raw linear interpolation of a longitude across the ±180° meridian
 * (`lon2 - lon1 ≈ -2π`) puts every inserted point on the far side of the globe: a 100 m segment
 * at Taveuni, Fiji, became a 40 000 km ride.
 *
 * When the shortest arc does cross a seam and both ends lie in `[-π, π]` (longitudes, `atan2`
 * bearings), the result is folded back into that range. Fields with no fixed range (the wind
 * angles) keep the value continuous with the first end, which is equivalent for every
 * trigonometric consumer.
 */
internal object FieldInterpolation {
    private const val TWO_PI = 2.0 * PI

    /** Angles in radians whose value wraps around at ±π (or 2π). Latitudes do not. */
    val CIRCULAR_FIELDS: Set<PointField> =
        setOf(
            PointField.LONGITUDE,
            PointField.SOURCE_LONGITUDE,
            PointField.BEARING,
            PointField.WIND_BEARING,
            PointField.WIND_DIRECTION,
            PointField.WIND_ALPHA,
        )

    /** Writes into `dst[dstIdx]` every field of `src` interpolated between [i1] and [i2] at [coef]. */
    fun interpolateFields(
        src: Path,
        i1: Int,
        i2: Int,
        coef: Double,
        dst: Path,
        dstIdx: Int,
    ) {
        for (field in PointField.entries) {
            dst.set(dstIdx, field, interpolate(field, src.get(i1, field), src.get(i2, field), coef))
        }
    }

    /** Interpolates one value of [field]. Strict NaN handling: either side NaN → NaN. */
    fun interpolate(
        field: PointField,
        v1: Double,
        v2: Double,
        coef: Double,
    ): Double {
        if (v1.isNaN() || v2.isNaN()) return Double.NaN
        val delta = v2 - v1
        if (field !in CIRCULAR_FIELDS || (delta >= -PI && delta <= PI)) return v1 + delta * coef
        // The shortest arc crosses a seam.
        val arc = if (delta > PI) delta - TWO_PI else delta + TWO_PI
        val v = v1 + arc * coef
        val bothInSignedRange = v1 >= -PI && v1 <= PI && v2 >= -PI && v2 <= PI
        return when {
            !bothInSignedRange -> v
            v > PI -> v - TWO_PI
            v < -PI -> v + TWO_PI
            else -> v
        }
    }
}
