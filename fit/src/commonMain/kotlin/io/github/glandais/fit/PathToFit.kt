package io.github.glandais.fit

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Convert a virtualized [Path] into a [FitCourse].
 *
 * Lives in commonMain deliberately: this is the only real logic in `:fit`, and writing it once
 * means the JVM, JS and Wasm encoders stay mechanical translations with nothing to disagree
 * about. See [FitEncoder] for why the `expect` was cut at this level.
 *
 * ## Field mapping
 *
 * | [FitRecord] | Source |
 * |---|---|
 * | `timestamp` | [startTime] + `TIME(i)` ms — the engine's clock is relative (`time(0) == 0`) |
 * | `latitudeDeg` / `longitudeDeg` | `LATITUDE` / `LONGITUDE`, radians → degrees |
 * | `altitudeM` | `ELEVATION` |
 * | `distanceM` | `DISTANCE` |
 * | `speedMs` | `SPEED` |
 * | `powerW` | `POWER` (`pComputedPower`) — the pipeline's *output* power |
 * | `heartRate` / `cadence` / `temperatureC` | the matching fields, when present |
 *
 * `POWER` is the right source among the 36 fields: it is what `VirtualizeService` computes for
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
 * @param name value for `CourseMesg.name`.
 * @param startTime absolute instant of the first point. **Mandatory**: FIT has no relative
 *   clock, so somebody has to make this decision — the same one task g05 surfaced for GPX.
 * @throws IllegalArgumentException if the path has no point. A FIT file with zero records is
 *   accepted by the SDK but rejected by the platforms that matter, so failing here is kinder
 *   than emitting one.
 */
fun Path.toFitCourse(
    name: String,
    startTime: Instant,
    sport: FitSport = FitSport.CYCLING,
): FitCourse {
    require(size > 0) { "Cannot build a FIT course from an empty Path" }

    val records =
        List(size) { i ->
            FitRecord(
                timestamp = startTime + time(i).toLong().milliseconds,
                latitudeDeg = latitude(i) * MathConstants.RAD_TO_DEG,
                longitudeDeg = longitude(i) * MathConstants.RAD_TO_DEG,
                altitudeM = elevation(i).takeUnless { it.isNaN() },
                distanceM = distance(i),
                speedMs = speed(i).takeUnless { it.isNaN() },
                powerW = pComputedPower(i).optionalComputed()?.roundToInt(),
                heartRate = heartRate(i).optionalSensor()?.roundToInt(),
                cadence = cadence(i).optionalSensor()?.roundToInt(),
                temperatureC = temperature(i).optionalSensor(),
            )
        }

    return FitCourse(
        name = name,
        startTime = startTime,
        records = records,
        sport = sport,
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
                // therefore negative, while FIT's `total_descent` is an unsigned magnitude. gpx2web
                // negates it too (`setTotalDescent((int) -path.getTotalElevationNegative())`).
                totalAscentM = elevationGain.roundToIntOrZero(),
                totalDescentM = (-elevationLoss).roundToIntOrZero(),
                maxSpeedMs = (0 until size).maxOfOrNull { speed(it) }?.takeUnless { it.isNaN() },
                minAltitudeM = (0 until size).minOfOrNull { elevation(it) }?.takeUnless { it.isNaN() },
                maxAltitudeM = (0 until size).maxOfOrNull { elevation(it) }?.takeUnless { it.isNaN() },
                startLatitudeDeg = latitude(0) * MathConstants.RAD_TO_DEG,
                startLongitudeDeg = longitude(0) * MathConstants.RAD_TO_DEG,
                endLatitudeDeg = latitude(size - 1) * MathConstants.RAD_TO_DEG,
                endLongitudeDeg = longitude(size - 1) * MathConstants.RAD_TO_DEG,
            ),
    )
}

/** Shortcut: convert and encode in one step. */
fun Path.toFitBytes(
    name: String,
    startTime: Instant,
    sport: FitSport = FitSport.CYCLING,
): ByteArray = FitEncoder.encode(toFitCourse(name, startTime, sport))

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
