package io.github.glandais.engine.climb

/**
 * A climb detected on a `Path` by [ClimbDetector].
 *
 * ## Grade units
 *
 * gpx2web mixes the two conventions — its `minGrade` is a percentage while `getGrade()` returns
 * a ratio. Here the rule is in the name: anything called `…Percent` is a percentage, anything
 * called `…Grade` is a dimensionless ratio (`0.08` = 8 %). Conversion happens only at the
 * boundary with [ClimbOptions].
 *
 * Distances are absolute along the path, so they line up with `Path.distance(i)`. Note that
 * gpx2web makes [ClimbPart] distances *relative to the climb start*; this port keeps them
 * absolute throughout so a part can be located on the path without knowing its climb.
 */
data class Climb(
    /** Index of the first path point of the climb. */
    val startIndex: Int,
    /** Index of the last path point of the climb, inclusive. */
    val endIndex: Int,
    val startDistanceM: Double,
    val endDistanceM: Double,
    val startElevationM: Double,
    val endElevationM: Double,
    /**
     * Sum of the positive elevation deltas inside the climb. Larger than [elevationGainM]
     * whenever the climb contains dips.
     */
    val positiveElevationM: Double,
    /** Sum of the negative elevation deltas inside the climb, as a **negative** number. */
    val negativeElevationM: Double,
    /** Homogeneous-grade segments the climb breaks down into, in order. */
    val parts: List<ClimbPart>,
) {
    val lengthM: Double get() = endDistanceM - startDistanceM

    /** Net elevation change, start to end. */
    val elevationGainM: Double get() = endElevationM - startElevationM

    /** Average grade over the whole climb, dimensionless (`0.08` = 8 %). */
    val averageGrade: Double get() = if (lengthM == 0.0) 0.0 else elevationGainM / lengthM

    /**
     * Average grade counting only the rising sections, dimensionless. This is what makes a climb
     * with dips feel steeper than [averageGrade] suggests, and it is the quantity
     * [ClimbOptions.maxDiffRealGradeRatio] bounds.
     */
    val climbingGrade: Double
        get() {
            val climbingLength = parts.filter { it.elevationGainM > 0 }.sumOf { it.lengthM }
            return if (climbingLength == 0.0) 0.0 else positiveElevationM / climbingLength
        }
}

/** A homogeneous-grade segment inside a [Climb]. */
data class ClimbPart(
    val startDistanceM: Double,
    val endDistanceM: Double,
    val startElevationM: Double,
    val endElevationM: Double,
) {
    val lengthM: Double get() = endDistanceM - startDistanceM

    val elevationGainM: Double get() = endElevationM - startElevationM

    /** Grade of this segment, dimensionless (`0.08` = 8 %). */
    val grade: Double get() = if (lengthM == 0.0) 0.0 else elevationGainM / lengthM
}

/**
 * Detector parameters. The defaults are exactly those of gpx2web's `getClimbs(gpxPath)`
 * one-argument overload, so the two implementations can be compared on the same trace.
 */
data class ClimbOptions(
    /** Floor for the dynamic elevation threshold, in meters. */
    val minMinClimbElevationM: Double = 10.0,
    /** Ceiling for the dynamic elevation threshold, in meters. */
    val maxMinClimbElevationM: Double = 35.0,
    /** The path's total ascent is divided by this to size the threshold between the two bounds. */
    val minClimbElevationRatio: Double = 100.0,
    /** Minimum average grade for a candidate to count, as a **percentage**. */
    val minGradePercent: Double = 3.0,
    /**
     * Upper bound on `climbingGrade / averageGrade`. It rejects candidates that only reach their
     * average grade by averaging steep ramps with descents — gpx2web's comment puts it as "7 % of
     * real climbing inside a 5 % average is not a single climb, it should be split in two".
     */
    val maxDiffRealGradeRatio: Double = 1.3,
    /**
     * Exponent applied to the grade when scoring a candidate: `score = length * grade^booster`.
     * Above 1 it biases selection towards steeper climbs rather than merely longer ones.
     */
    val booster: Double = 1.3,
) {
    companion object {
        val DEFAULT: ClimbOptions = ClimbOptions()
    }
}
