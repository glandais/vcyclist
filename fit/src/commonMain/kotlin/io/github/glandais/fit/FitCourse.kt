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
    /**
     * One entry per source `Path` (task g25). A single-path course has exactly one, which is the
     * shape every caller had before g25 — see the [records] and [lap] accessors.
     */
    val segments: List<FitSegment>,
    val sport: FitSport = FitSport.CYCLING,
) {
    init {
        require(segments.isNotEmpty()) { "A FIT course needs at least one segment" }
        require(segments.all { it.records.isNotEmpty() }) { "A FIT course segment needs at least one record" }
    }

    /**
     * Every record of every segment, in order. Compatibility accessor for the pre-g25 shape —
     * same strategy as `GpxTrack.points` in g02.
     */
    val records: List<FitRecord> get() = segments.flatMap { it.records }

    /**
     * The single lap of a single-segment course. **Throws** on a multi-segment one, where the
     * question has no answer — use [segments] there.
     */
    val lap: FitLap
        get() =
            segments.singleOrNull()?.lap
                ?: error("This course has ${segments.size} segments; read `segments`, not `lap`")

    companion object {
        /** Build a single-segment course — the pre-g25 constructor, kept source-compatible. */
        operator fun invoke(
            name: String,
            startTime: Instant,
            records: List<FitRecord>,
            lap: FitLap,
            sport: FitSport = FitSport.CYCLING,
        ): FitCourse = FitCourse(name, startTime, listOf(FitSegment(records, lap)), sport)
    }
}

/**
 * One source `Path` inside a course: its records, and the [FitLap] summarising them.
 *
 * FIT expresses "several rides in one file" as several laps plus a `TIMER`/`START`…`STOP` event
 * pair around each record run. This type is the neutral model of one such run.
 */
data class FitSegment(
    val records: List<FitRecord>,
    val lap: FitLap,
)

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
