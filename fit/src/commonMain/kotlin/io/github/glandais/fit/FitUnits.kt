package io.github.glandais.fit

import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Instant

/**
 * The FIT wire encoding, in one place.
 *
 * FIT stores most quantities as scaled integers with its own epoch, and getting any of it wrong
 * produces a file that *imports successfully* and then shows nonsense — a ride in 1989, or a
 * position in the Gulf of Guinea. Centralising the conversions here means the JVM and JS
 * encoders cannot drift apart, and [FitUnitsTest] pins the values on all four targets.
 *
 * **Not every constant is needed by every encoder.** The Java SDK's typed setters
 * (`setAltitude(Float)`, `setDistance(Float)`, `setSpeed(Float)`) already take real-world units
 * and apply scale and offset internally, so the JVM `actual` only calls [degreesToSemicircles].
 * The scales are still defined and tested here because the JavaScript SDK (task g09) works
 * closer to the wire, and because a reader of a FIT dump needs them to interpret raw fields.
 */
object FitUnits {
    /**
     * Milliseconds between the Unix epoch and the FIT epoch, 1989-12-31T00:00:00Z.
     * Matches `com.garmin.fit.DateTime.OFFSET`.
     */
    const val FIT_EPOCH_OFFSET_MS: Long = 631_065_600_000L

    /** Seconds between the Unix epoch and the FIT epoch. */
    const val FIT_EPOCH_OFFSET_S: Long = FIT_EPOCH_OFFSET_MS / 1000L

    /** Semicircles per degree: `2^31 / 180`. */
    const val SEMICIRCLES_PER_DEGREE: Double = 2147483648.0 / 180.0

    /** `altitude_raw = (meters + [ALTITUDE_OFFSET_M]) * [ALTITUDE_SCALE]`. */
    const val ALTITUDE_SCALE: Double = 5.0

    /** See [ALTITUDE_SCALE]. */
    const val ALTITUDE_OFFSET_M: Double = 500.0

    /** `distance_raw = meters * [DISTANCE_SCALE]`. */
    const val DISTANCE_SCALE: Double = 100.0

    /** `speed_raw = metersPerSecond * [SPEED_SCALE]`. */
    const val SPEED_SCALE: Double = 1000.0

    /**
     * Convert a latitude or longitude in degrees to FIT semicircles.
     *
     * This one is **not** applied by the Java SDK — `RecordMesg.setPositionLat` documents its
     * parameter as already being in semicircles — which is why gpx2web has its own
     * `SemiCirclesConverter`.
     */
    fun degreesToSemicircles(degrees: Double): Int = (degrees * SEMICIRCLES_PER_DEGREE).roundToInt()

    /** Inverse of [degreesToSemicircles], for decoding and for tests. */
    fun semicirclesToDegrees(semicircles: Int): Double = semicircles / SEMICIRCLES_PER_DEGREE

    /** Seconds since the FIT epoch, the value carried by every `timestamp` field. */
    fun toFitTimestamp(instant: Instant): Long = (instant.toEpochMilliseconds() - FIT_EPOCH_OFFSET_MS) / 1000L

    /** Inverse of [toFitTimestamp]. */
    fun fromFitTimestamp(fitSeconds: Long): Instant = Instant.fromEpochMilliseconds(fitSeconds * 1000L + FIT_EPOCH_OFFSET_MS)

    /** Raw `altitude` field value for an altitude in meters. */
    fun altitudeToRaw(meters: Double): Long = ((meters + ALTITUDE_OFFSET_M) * ALTITUDE_SCALE).roundToLong()

    /** Inverse of [altitudeToRaw]. */
    fun rawToAltitude(raw: Long): Double = raw / ALTITUDE_SCALE - ALTITUDE_OFFSET_M

    /** Raw `distance` field value for a distance in meters. */
    fun distanceToRaw(meters: Double): Long = (meters * DISTANCE_SCALE).roundToLong()

    /** Raw `speed` field value for a speed in m/s. */
    fun speedToRaw(metersPerSecond: Double): Long = (metersPerSecond * SPEED_SCALE).roundToLong()
}
