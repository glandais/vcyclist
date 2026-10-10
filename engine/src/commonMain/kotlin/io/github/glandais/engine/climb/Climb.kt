package io.github.glandais.engine.climb

/**
 * A climb detected on a `Path` by [ClimbDetector].
 *
 * ## Grade units
 *
 * Percentages and ratios are easy to mix up, so the rule is in the name: anything called
 * `…Percent` is a percentage, anything called `…Grade` is a dimensionless ratio (`0.08` = 8 %).
 * Conversion happens only at the boundary with [ClimbOptions].
 *
 * Distances are absolute along the path, so they line up with `Path.distance(i)` — [ClimbPart]
 * distances included, so a part can be located on the path without knowing its climb.
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
    /**
     * Summed length of the analysed profile segments that rise, in meters — the denominator of
     * [climbingGrade], accumulated by the detector alongside [positiveElevationM].
     */
    val climbingDistanceM: Double,
    /** Homogeneous-grade segments the climb breaks down into, in order. */
    val parts: List<ClimbPart>,
) {
    val lengthM: Double get() = endDistanceM - startDistanceM

    /** Net elevation change, start to end. */
    val elevationGainM: Double get() = endElevationM - startElevationM

    /** Average grade over the whole climb, dimensionless (`0.08` = 8 %). */
    val averageGrade: Double get() = if (lengthM == 0.0) 0.0 else elevationGainM / lengthM

    /**
     * Average grade counting only the rising sections, dimensionless:
     * [positiveElevationM] / [climbingDistanceM]. This is what makes a climb with dips feel
     * steeper than [averageGrade] suggests, and it is exactly the quantity
     * [ClimbOptions.maxDiffRealGradeRatio] bounds, so `climbingGrade / averageGrade` never exceeds
     * that option on a detected climb.
     *
     * The denominator is the rising *profile segments* the detector scored, not the rising
     * [parts]: the Douglas-Peucker split (10–50 m tolerance) can fold a gently rising false flat
     * into a net-negative part, and dividing by the parts' length then reported a ratio the
     * detector never accepted.
     */
    val climbingGrade: Double
        get() = if (climbingDistanceM == 0.0) 0.0 else positiveElevationM / climbingDistanceM
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
 * Detector parameters, with the defaults [ClimbDetector] uses when none are given.
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
     * average grade by averaging steep ramps with descents: 7 % of real climbing inside a 5 %
     * average is not a single climb, it should be split in two.
     */
    val maxDiffRealGradeRatio: Double = 1.3,
    /**
     * Exponent applied to the grade when scoring a candidate: `score = length * grade^booster`.
     * Above 1 it biases selection towards steeper climbs rather than merely longer ones.
     */
    val booster: Double = 1.3,
    /**
     * Upper bound on how many points the O(n²) candidate search looks at, first and last point
     * included — a hard bound, never exceeded by one. Above it the path is
     * uniformly decimated for the *analysis* only; reported indices still refer to the original
     * path.
     *
     * The bound exists because of how the search scales. The cost is quadratic in the point
     * count — measured in a browser: 259 points 104 ms, 621 points 345 ms. The enhancement
     * pipeline can hand over a path densified to 1–2 m spacing, which is ~25 000 points for a
     * 140 km route: several minutes, enough to freeze a browser tab.
     *
     * Decimating costs nothing in accuracy at this scale. The parts are already Douglas-Peucker
     * simplified with a 10–50 m tolerance, so resolving a climb's start to the nearest ~50 m
     * point is well inside the noise. Paths at or below the bound are analysed in full, so the
     * behaviour on ordinary traces is untouched.
     */
    val maxAnalysisPoints: Int = 3000,
) {
    init {
        require(maxAnalysisPoints >= 2) { "maxAnalysisPoints must be at least 2" }
    }

    companion object {
        val DEFAULT: ClimbOptions = ClimbOptions()
    }
}
