package io.github.glandais.fit

import kotlin.time.Instant

/**
 * Neutral, SDK-agnostic representation of a FIT file of type **Course**, ready to encode.
 *
 * This model exists so that the conversion work — reading a
 * [io.github.glandais.engine.path.Path], deriving lap totals, picking timestamps — is written
 * **once in commonMain** and tested on all three targets. Each [FitEncoder] `actual` is then a
 * mechanical translation into its own SDK's message objects, with no logic of its own to get
 * wrong twice.
 *
 * Building one of these from a `Path` is task g10 ; g08 only defines the shape.
 */
data class FitCourse(
    /** Value of `CourseMesg.name`. */
    val name: String,
    /**
     * Instant of the first record. **Mandatory**: FIT has no notion of relative time, unlike the
     * engine's `Path`, whose clock is normalised to `time(0) == 0` by `VirtualizeService`. The
     * caller therefore has to decide on an absolute start — the same decision task g05 exposed
     * for GPX writing.
     */
    val startTime: Instant,
    val records: List<FitRecord>,
    val lap: FitLap,
    val sport: FitSport = FitSport.CYCLING,
) {
    init {
        require(records.isNotEmpty()) { "A FIT course needs at least one record" }
    }
}

/**
 * One `RecordMesg`. Every field is in **real-world units** (degrees, meters, m/s, watts, bpm,
 * rpm, Celsius) ; converting to the FIT wire encoding is the encoder's job — see [FitUnits].
 *
 * Nullable fields are omitted from the encoded message rather than written as zero: a FIT
 * consumer distinguishes "no power meter" from "0 W".
 */
data class FitRecord(
    val timestamp: Instant,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeM: Double? = null,
    /** Cumulative distance from the course start, in meters. */
    val distanceM: Double,
    val speedMs: Double? = null,
    val powerW: Int? = null,
    val heartRate: Int? = null,
    val cadence: Int? = null,
    val temperatureC: Double? = null,
)

/**
 * One `LapMesg` summarising the whole course. FIT players use it for the course overview, so
 * the totals must agree with the records — a lap distance that disagrees with the last record's
 * distance makes some head units reject the file.
 */
data class FitLap(
    val startTime: Instant,
    val totalElapsedTimeS: Double,
    val totalTimerTimeS: Double,
    val totalDistanceM: Double,
    val totalAscentM: Int,
    val totalDescentM: Int,
    val maxSpeedMs: Double? = null,
    val minAltitudeM: Double? = null,
    val maxAltitudeM: Double? = null,
    val startLatitudeDeg: Double? = null,
    val startLongitudeDeg: Double? = null,
    val endLatitudeDeg: Double? = null,
    val endLongitudeDeg: Double? = null,
)

/**
 * Subset of the FIT `Sport` enum that this port emits. The numeric [value] is the wire value
 * from the FIT profile, so both `actual` encoders can use it directly without a lookup table.
 */
enum class FitSport(
    val value: Int,
) {
    CYCLING(2),
    RUNNING(1),
    GENERIC(0),
}
