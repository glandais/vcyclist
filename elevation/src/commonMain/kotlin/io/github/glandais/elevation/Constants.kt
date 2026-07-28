package io.github.glandais.elevation

import kotlin.math.PI

object EarthConstants {
    /** Semi-major axis in meters (WGS84 ellipsoid) */
    const val SEMI_MAJOR_AXIS: Double = 6_378_137.0

    /** Mean radius in meters (used for distance calculations) */
    const val MEAN_RADIUS: Double = 6_371_000.0

    /** First eccentricity squared (WGS84 ellipsoid) */
    const val FIRST_ECCENTRICITY_SQUARED: Double = 0.006_694_379_990_14

    /** Web Mercator latitude bound (north/south) in degrees */
    const val WEB_MERCATOR_MAX_LAT: Double = 85.051_128_779_806_59

    /** Looser validation bound used by [ElevationFunctions.isValidLatitude] (5-decimal truncation). */
    const val WEB_MERCATOR_MAX_LAT_TEST: Double = 85.0511
}

object MathConstants {
    /**
     * Degrees → radians factor.
     *
     * `const` rather than `val`, since task g27: a plain `val` in an `object` reaches Java only
     * as `MathConstants.INSTANCE.getDEG_TO_RAD()`, which is not something anyone writes. As a
     * compile-time constant it is a `public static final double`.
     */
    const val DEG_TO_RAD: Double = PI / 180.0

    /** Radians → degrees factor. `const` for the same reason as [DEG_TO_RAD]. */
    const val RAD_TO_DEG: Double = 180.0 / PI
}

object AlgorithmConstants {
    /** Minimum points needed for smoothing operations */
    const val MIN_SMOOTHING_POINTS: Int = 3
}
