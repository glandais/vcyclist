package io.github.glandais.fit

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Convert a virtualized [Path] into a [FitCourse].
 *
 * Lives in commonMain deliberately: this is the only real logic in `:fit`, and writing it once
 * means the JVM and JS encoders stay mechanical translations with nothing to disagree about.
 * See [FitEncoder] for why the `expect` was cut at this level.
 *
 * ## Field mapping
 *
 * | [FitRecord] | Source |
 * |---|---|
 * | `timestamp` | [startTime] + (`TIME(i)` − `TIME(0)`) ms — see *Rebasing* below |
 * | `latitudeDeg` / `longitudeDeg` | `LATITUDE` / `LONGITUDE`, radians → degrees |
 * | `altitudeM` | `ELEVATION` |
 * | `distanceM` | `DISTANCE`, plus the total distance of the paths before it — see [List.toFitCourse] |
 * | `speedMs` | `SPEED` |
 * | `powerW` | `POWER` (`pComputedPower`) — the pipeline's *output* power |
 * | `heartRate` / `cadence` / `temperatureC` | the matching fields, when present |
 *
 * ## Rebasing (task g25)
 *
 * Timestamps are computed **relative to the path's own first point**, not from the raw `TIME`
 * value. For a virtualized path this changes nothing — `VirtualizeService` guarantees
 * `time(0) == 0` — but `GpxToPath` copies `timeEpochMs` through verbatim, so a path that was
 * merely parsed from a timestamped GPX carries epoch milliseconds. Adding [startTime] to those
 * produced a file dated some 57 years in the future. Rebasing makes [startTime] mean what its
 * name says on both kinds of path, and makes the conversion idempotent.
 *
 * `POWER` is the right source among the 43 fields: it is what `VirtualizeService` computes for
 * the simulated ride. `P_INPUT_POWER` is the power read from the *input* GPX, which for a
 * virtualized course is either absent or describes a different ride entirely.
 *
 * ## Absent values
 *
 * [Path] is a flat `DoubleArray` initialised to `0.0`, so "no heart-rate monitor" and "0 bpm"
 * are indistinguishable by value alone. Both `NaN` **and** exact `0.0` are therefore treated as
 * absent for the optional sensor fields and omitted from the record, matching the convention
 * `GpxFromPath` already uses when writing GPX. Writing them as zero would draw a flat line at
 * the bottom of every chart instead of showing no data. Position, altitude and distance are not
 * optional — a record without them is meaningless — so only `NaN` drops those.
 *
 * ## Course points
 *
 * The overloads taking `coursePoints: List<FitCoursePointSpec>` write `course_point` messages.
 * A spec's `distanceM` is on the axis of [FitRecord.distanceM]: cumulative over every path, the
 * 2D haversine of `Path.distance` (plus the total of the paths before). Project the points on the
 * same paths you pass, not on another geometry.
 *
 * Each point is **snapped to the record whose distance is nearest** and takes that record's
 * position, distance and timestamp. A tie between the record below and the one above goes to the
 * one below; among records sharing the chosen distance (a stop, a segment junction) the first
 * wins, so a point exactly on a junction lands on the last record of the previous segment. A
 * distance outside the course is **clamped** to the first record, or to the first record of the
 * final distance, rather than rejected; a non-finite distance throws. Points are sorted by
 * distance (stable), indexed 0..n-1 and written after the last record run, before the final
 * `STOP_ALL`. Filtering off-route points, truncating names and capping the count are the
 * caller's policy; the library only enforces FIT's own limits (254 UTF-8 bytes per name, 65 535
 * points). Without course points the output is byte-identical to the overloads that do not take
 * them.
 *
 * @param name value for `CourseMesg.name`.
 * @param startTime absolute instant of the first point. **Mandatory**: FIT has no relative
 *   clock, so somebody has to make this decision — the same one task g05 surfaced for GPX.
 * @throws IllegalArgumentException if the path has no point. A FIT file with zero records is
 *   accepted by the SDK but rejected by the platforms that matter, so failing here is kinder
 *   than emitting one.
 * @throws IllegalArgumentException if `TIME` is not monotonic. FIT accepts records going
 *   backwards in time; the platforms that read them do not, so failing loudly beats shipping a
 *   file that Garmin Connect silently rejects.
 */
fun Path.toFitCourse(
    name: String,
    startTime: Instant,
    sport: FitSport = FitSport.CYCLING,
): FitCourse = toFitCourse(name, startTime, emptyList(), sport)

/** [toFitCourse] with course points, see *Course points* above. */
fun Path.toFitCourse(
    name: String,
    startTime: Instant,
    coursePoints: List<FitCoursePointSpec>,
    sport: FitSport = FitSport.CYCLING,
): FitCourse = listOf(this).toFitCourse(name, startTime, coursePoints, sport)

/** Shortcut: convert and encode in one step. */
fun Path.toFitBytes(
    name: String,
    startTime: Instant,
    sport: FitSport = FitSport.CYCLING,
): ByteArray = toFitBytes(name, startTime, emptyList(), sport)

/** Shortcut: convert and encode in one step, with course points. */
fun Path.toFitBytes(
    name: String,
    startTime: Instant,
    coursePoints: List<FitCoursePointSpec>,
    sport: FitSport = FitSport.CYCLING,
): ByteArray = FitEncoder.encode(toFitCourse(name, startTime, coursePoints, sport))

/**
 * Convert several [Path]s — typically the tracks of one multi-track GPX — into a **single**
 * [FitCourse] with one [FitSegment], and therefore one `LapMesg` and one `TIMER`/`START`…`STOP`
 * event pair, per path. That is how FIT expresses several rides inside one file.
 *
 * Each path is rebased on its **own** first point (see *Rebasing* above), then laid down after
 * the previous one, separated by [interPathGap]. The default of zero makes the paths run
 * straight on from one another, which matches the dominant case: one ride cut into several
 * traces. A non-zero gap is **not** a pause — FIT expresses those with `TIMER`/`PAUSE` events,
 * which this port does not emit — it simply shifts the following path's clock.
 *
 * Record distance is carried across paths the same way: `Path.computeDerivedData` restarts
 * `DISTANCE` at 0 on every path, so each path's distances are offset by the total distance of
 * the paths before it, keeping [FitRecord.distanceM] cumulative from the course start — the axis
 * a head unit navigates on. Each lap's `totalDistanceM` stays that path's own distance.
 */
fun List<Path>.toFitCourse(
    name: String,
    startTime: Instant,
    sport: FitSport = FitSport.CYCLING,
    interPathGap: Duration = Duration.ZERO,
): FitCourse = toFitCourse(name, startTime, emptyList(), sport, interPathGap)

/** [toFitCourse] over several paths with course points, see *Course points* above. */
fun List<Path>.toFitCourse(
    name: String,
    startTime: Instant,
    coursePoints: List<FitCoursePointSpec>,
    sport: FitSport = FitSport.CYCLING,
    interPathGap: Duration = Duration.ZERO,
): FitCourse {
    require(isNotEmpty()) { "Cannot build a FIT course from an empty list of paths" }
    require(all { it.size > 0 }) { "Cannot build a FIT course from an empty Path" }

    var segmentStart = startTime
    var segmentDistanceStart = 0.0
    val segments =
        map { path ->
            val segment = path.toFitSegment(segmentStart, segmentDistanceStart)
            segmentStart = segment.records.last().timestamp + interPathGap
            segmentDistanceStart = segment.records.last().distanceM
            segment
        }
    return FitCourse(name, startTime, segments, sport, alignCoursePoints(segments, coursePoints))
}

/** Shortcut: convert and encode several paths in one step. */
fun List<Path>.toFitBytes(
    name: String,
    startTime: Instant,
    sport: FitSport = FitSport.CYCLING,
    interPathGap: Duration = Duration.ZERO,
): ByteArray = toFitBytes(name, startTime, emptyList(), sport, interPathGap)

/** Shortcut: convert and encode several paths with course points in one step. */
fun List<Path>.toFitBytes(
    name: String,
    startTime: Instant,
    coursePoints: List<FitCoursePointSpec>,
    sport: FitSport = FitSport.CYCLING,
    interPathGap: Duration = Duration.ZERO,
): ByteArray = FitEncoder.encode(toFitCourse(name, startTime, coursePoints, sport, interPathGap))

/**
 * A course point to place on a course: where along it ([distanceM], meters on the axis of
 * [FitRecord.distanceM] — cumulative over every path, 2D haversine of `Path.distance`), what it is,
 * and its label. [toFitCourse] snaps it onto the nearest record. Filtering points that are off the
 * route, truncating labels and capping the count are the caller's policy.
 *
 * From Java, call the three-argument constructor, with `null` for no name.
 */
data class FitCoursePointSpec(
    val distanceM: Double,
    val type: FitCoursePointType,
    val name: String? = null,
) {
    init {
        require(distanceM.isFinite()) { "Course point distance must be finite, got $distanceM" }
    }
}

private fun alignCoursePoints(
    segments: List<FitSegment>,
    coursePoints: List<FitCoursePointSpec>,
): List<FitCoursePoint> {
    if (coursePoints.isEmpty()) return emptyList()
    val records = segments.flatMap { it.records }
    require(records.all { it.distanceM.isFinite() }) {
        "Cannot place course points on a path whose distance is not finite (NaN position?)"
    }
    return coursePoints
        .sortedBy { it.distanceM }
        .map { spec ->
            val r = records[records.alignedIndex(spec.distanceM)]
            FitCoursePoint(
                r.timestamp,
                r.latitudeDeg,
                r.longitudeDeg,
                r.distanceM,
                spec.type,
                spec.name?.takeIf { it.isNotEmpty() },
            )
        }
}

/** First index i with records[i].distanceM >= d, or size. */
private fun List<FitRecord>.lowerBound(d: Double): Int {
    var lo = 0
    var hi = size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].distanceM < d) lo = mid + 1 else hi = mid
    }
    return lo
}

/**
 * Index of the record a course point at [target] snaps to: the record whose distance is nearest
 * to [target]; on a tie between the record below and the record above, the one below; and among
 * records sharing that distance (a stop, a segment junction), the FIRST one.
 */
private fun List<FitRecord>.alignedIndex(target: Double): Int {
    val k = lowerBound(target)
    val best =
        when {
            k == 0 -> 0
            k == size -> lastIndex
            else -> if (this[k].distanceM - target < target - this[k - 1].distanceM) k else k - 1
        }
    return lowerBound(this[best].distanceM)
}

private fun Path.toFitSegment(
    startTime: Instant,
    distanceStart: Double,
): FitSegment {
    val t0 = time(0)
    for (i in 1 until size) {
        require(time(i) >= time(i - 1)) {
            "Path time must be monotonic to encode a FIT file, but time($i) = ${time(i)} < time(${i - 1}) = ${time(i - 1)}"
        }
    }

    val records =
        List(size) { i ->
            FitRecord(
                timestamp = startTime + (time(i) - t0).toLong().milliseconds,
                latitudeDeg = latitude(i) * MathConstants.RAD_TO_DEG,
                longitudeDeg = longitude(i) * MathConstants.RAD_TO_DEG,
                altitudeM = elevation(i).takeUnless { it.isNaN() },
                distanceM = distanceStart + distance(i),
                speedMs = speed(i).takeUnless { it.isNaN() },
                powerW = pComputedPower(i).optionalComputed()?.roundToInt(),
                heartRate = heartRate(i).optionalSensor()?.roundToInt(),
                cadence = cadence(i).optionalSensor()?.roundToInt(),
                temperatureC = temperature(i).optionalSensor(),
            )
        }

    return FitSegment(
        records = records,
        lap =
            FitLap(
                startTime = startTime,
                // `elapsed` is wall-clock, `timer` excludes pauses. A virtualized course never
                // pauses — the simulation advances time continuously — so the two are equal by
                // construction. They are kept as separate fields because FIT consumers read one
                // or the other, and a real recorded activity would differ.
                totalElapsedTimeS = durationMs / 1000.0,
                totalTimerTimeS = durationMs / 1000.0,
                totalDistanceM = totalDistance,
                // FIT stores ascent and descent as whole meters. Rounding rather than truncating
                // keeps a 1499.6 m climb from being reported as 1499 — the sub-meter part is well
                // inside DEM noise either way.
                //
                // Note the sign flip: `Path.elevationLoss` accumulates the negative deltas and is
                // therefore negative, while FIT's `total_descent` is an unsigned magnitude, so it
                // is negated here.
                //
                // `reported*` rather than the raw sums: a head unit shows this number to a human
                // beside the one their own device computed, and every device applies a dead band.
                // It falls back to the raw sum on a path the gain stage never ran on.
                totalAscentM = reportedElevationGain.roundToIntOrZero(),
                totalDescentM = (-reportedElevationLoss).roundToIntOrZero(),
                // Over the values that are present only: `minOf`/`maxOf` propagate `NaN`, so one
                // `NaN` point used to null the whole lap's range while every other record kept
                // its value. A `NaN` drops that record's field, never the lap summary.
                maxSpeedMs = presentValues { speed(it) }.maxOrNull(),
                minAltitudeM = presentValues { elevation(it) }.minOrNull(),
                maxAltitudeM = presentValues { elevation(it) }.maxOrNull(),
                startLatitudeDeg = latitude(0) * MathConstants.RAD_TO_DEG,
                startLongitudeDeg = longitude(0) * MathConstants.RAD_TO_DEG,
                endLatitudeDeg = latitude(size - 1) * MathConstants.RAD_TO_DEG,
                endLongitudeDeg = longitude(size - 1) * MathConstants.RAD_TO_DEG,
            ),
    )
}

/** The values of [field] over every point, `NaN` (absent) ones left out. */
private inline fun Path.presentValues(field: Path.(Int) -> Double): List<Double> = (0 until size).map { field(it) }.filterNot { it.isNaN() }

/**
 * `NaN` means "this sensor produced nothing" ; a genuine `0.0` reading is kept — 0 rpm while
 * freewheeling and 0 °C on a winter ride are measurements, not absences. [GpxToPath] writes the
 * `NaN` when the source GPX carried no such extension.
 */
private fun Double.optionalSensor(): Double? = takeUnless { it.isNaN() }

/**
 * Same, for a **computed** field. `pComputedPower` is only meaningful once `VirtualizeService`
 * has run ; on a path that never went through the pipeline its slot is still the backing array's
 * `0.0`, and no `NaN` marks it as absent. So `0.0` stays the "nothing here" signal for it —
 * encoding a flat 0 W line for an un-simulated ride is worse than omitting the field.
 */
private fun Double.optionalComputed(): Double? = takeUnless { it.isNaN() || it == 0.0 }

private fun Double.roundToIntOrZero(): Int = if (isNaN()) 0 else roundToInt()
