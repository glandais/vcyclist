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
    /**
     * `course_point` messages, written after the last record run and before the final
     * `TIMER`/`STOP_ALL` event, `message_index` 0..n-1 in list order. Must be sorted by
     * [FitCoursePoint.distanceM]. Empty by default, and an empty list encodes to exactly the
     * bytes this course encoded to before course points existed.
     *
     * [io.github.glandais.fit.toFitCourse] builds them aligned on a record; a hand-built course
     * may place them anywhere — alignment is a guarantee of the conversion, not an invariant of
     * this model.
     */
    val coursePoints: List<FitCoursePoint> = emptyList(),
) {
    init {
        require(segments.isNotEmpty()) { "A FIT course needs at least one segment" }
        require(segments.all { it.records.isNotEmpty() }) { "A FIT course segment needs at least one record" }
        require(coursePoints.size <= MAX_COURSE_POINTS) {
            "A FIT course holds at most $MAX_COURSE_POINTS course points " +
                "(message_index is a uint16, 0xFFFF is invalid), got ${coursePoints.size}"
        }
        require(coursePoints.zipWithNext().all { (a, b) -> a.distanceM <= b.distanceM }) {
            "Course points must be sorted by distance"
        }
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
        /** `message_index` is a uint16 whose 0xFFFF is FIT's invalid sentinel. */
        const val MAX_COURSE_POINTS: Int = 65_535

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
 * One `LapMesg` summarising one [FitSegment] — the whole course when it has a single segment.
 * FIT players use it for the course overview, so the totals must agree with the segment's own
 * records — a lap distance that disagrees with the distance its records cover makes some head
 * units reject the file. [FitRecord.distanceM] is cumulative from the course start, so on a
 * single-segment course that is the last record's distance; on a multi-segment one it is the last
 * record's distance minus the first's, since each lap's [totalDistanceM] is its own segment's.
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

/**
 * One `course_point` message, in real-world units, encoded **as is**. [toFitCourse] builds them
 * aligned on a record (same position, distance and timestamp); a hand-built course may place them
 * anywhere — alignment is a guarantee of the conversion, not an invariant of this model.
 */
data class FitCoursePoint(
    val timestamp: Instant,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    /** Cumulative distance from the course start, the axis of [FitRecord.distanceM]. */
    val distanceM: Double,
    val type: FitCoursePointType,
    /**
     * `null` or empty: the `name` field is omitted. No truncation here — display limits are the
     * caller's policy.
     */
    val name: String? = null,
) {
    init {
        require(latitudeDeg.isFinite() && longitudeDeg.isFinite()) {
            "Course point position must be finite, got ($latitudeDeg, $longitudeDeg)"
        }
        // uint32 on the wire (scaled by 100).
        require(distanceM.isFinite() && distanceM >= 0.0) {
            "Course point distance must be finite and non-negative, got $distanceM"
        }
        name?.let {
            require('\u0000' !in it) { "A FIT string cannot contain NUL: readers stop at the first one" }
            require(it.encodeToByteArray().size <= MAX_NAME_BYTES) {
                "A course point name holds at most $MAX_NAME_BYTES UTF-8 bytes (255-byte FIT field limit, NUL included)"
            }
        }
    }

    companion object {
        /** 255-byte FIT field limit, minus the NUL terminator. */
        const val MAX_NAME_BYTES: Int = 254
    }
}

/**
 * The FIT profile's `course_point` type, every value of profile 21.217.0 (no `INVALID`; 26 is
 * unassigned in the profile). [value] is the wire value, as in [FitSport]. Entry names are those
 * of the SDK's `com.garmin.fit.types.CoursePoint`, which a test pins.
 */
enum class FitCoursePointType(
    val value: Int,
) {
    GENERIC(0),
    SUMMIT(1),
    VALLEY(2),
    WATER(3),
    FOOD(4),
    DANGER(5),
    LEFT(6),
    RIGHT(7),
    STRAIGHT(8),
    FIRST_AID(9),
    FOURTH_CATEGORY(10),
    THIRD_CATEGORY(11),
    SECOND_CATEGORY(12),
    FIRST_CATEGORY(13),
    HORS_CATEGORY(14),
    SPRINT(15),
    LEFT_FORK(16),
    RIGHT_FORK(17),
    MIDDLE_FORK(18),
    SLIGHT_LEFT(19),
    SHARP_LEFT(20),
    SLIGHT_RIGHT(21),
    SHARP_RIGHT(22),
    U_TURN(23),
    SEGMENT_START(24),
    SEGMENT_END(25),
    CAMPSITE(27),
    AID_STATION(28),
    REST_AREA(29),
    GENERAL_DISTANCE(30),
    SERVICE(31),
    ENERGY_GEL(32),
    SPORTS_DRINK(33),
    MILE_MARKER(34),
    CHECKPOINT(35),
    SHELTER(36),
    MEETING_SPOT(37),
    OVERLOOK(38),
    TOILET(39),
    SHOWER(40),
    GEAR(41),
    SHARP_CURVE(42),
    STEEP_INCLINE(43),
    TUNNEL(44),
    BRIDGE(45),
    OBSTACLE(46),
    CROSSING(47),
    STORE(48),
    TRANSITION(49),
    NAVAID(50),
    TRANSPORT(51),
    ALERT(52),
    INFO(53),
}
