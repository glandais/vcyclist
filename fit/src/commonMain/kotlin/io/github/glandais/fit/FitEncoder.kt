package io.github.glandais.fit

import com.garmin.fit.CourseMesg
import com.garmin.fit.EventMesg
import com.garmin.fit.FileIdMesg
import com.garmin.fit.LapMesg
import com.garmin.fit.RecordMesg
import com.garmin.fit.encodeFit
import com.garmin.fit.types.Event
import com.garmin.fit.types.EventType
import com.garmin.fit.types.File
import com.garmin.fit.types.Manufacturer
import com.garmin.fit.types.Sport
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * FIT encoder: a [FitCourse] goes in, a complete FIT file comes out, header and CRC included.
 *
 * ## Why this is `commonMain` and not `expect`/`actual` (task w12)
 *
 * Until w12 this was an `expect object` with three `actual`s: `com.garmin:fit` on the JVM,
 * `@garmin/fitsdk` on JS, and a stub that threw on wasmWasi — because neither official SDK runs
 * under WASI. The coarse `expect` existed only because the Java and JavaScript SDKs share no
 * abstraction (typed message classes on one side, plain objects keyed by profile field name on
 * the other), so nothing thinner than *"course in, bytes out"* could be factored out.
 *
 * `io.github.glandais:fit-kotlin-sdk` is generated from the same FIT profile revision
 * (21.205.0) and is stdlib-only `commonMain` Kotlin, so it compiles on every target this
 * project has. That collapses the three implementations into this one, deletes the two vendor
 * SDK dependencies — one Maven artefact and, more visibly, the `@garmin/fitsdk` npm package
 * that every install of `@glandais/vcyclist-engine` used to pull in — and makes `pathToFit`
 * work under WASI.
 *
 * ## Message order
 *
 * Not free in a Course file, and unchanged since g08 (it follows
 * `gpx2web/.../io/write/FitFileWriter.java`): [FileIdMesg], then [CourseMesg], then one
 * [LapMesg] per segment, then per segment a `TIMER`/`START` event, its [RecordMesg] stream and
 * a `TIMER`/`STOP` — `STOP_ALL` on the last one, which is what tells a reader the file is over.
 *
 * ## Local message numbers
 *
 * No longer assigned by hand. The SDK's encoder reuses the local number already bound to an
 * identical layout and takes the next free slot otherwise, so writing in the order above still
 * lands on `file_id`=0, `course`=1, `lap`=2, `event`=3, `record`=4 — the allocation the
 * previous implementations spelled out — and a run of records still costs one definition.
 *
 * ## Units
 *
 * The SDK's typed properties take **real-world units** and apply the profile's scale and offset
 * themselves (`altitude` in m, `distance` in m, `speed` in m/s, `power` in W), and timestamps
 * are `kotlin.time.Instant`, converted to the FIT epoch internally. Position is the exception —
 * the profile documents `position_lat` / `position_long` as semicircles, with no scale — so
 * [FitUnits.degreesToSemicircles] does that conversion, exactly as gpx2web's
 * `SemiCirclesConverter` does.
 */
object FitEncoder {
    /** Encode [course] and return the complete FIT file, header and CRC included. */
    fun encode(course: FitCourse): ByteArray =
        encodeFit {
            write(fileIdMesg(course))
            write(courseMesg(course))
            // All laps first, then the record runs — gpx2web's order (`FitFileWriter.writeGPX`).
            for (segment in course.segments) {
                write(lapMesg(segment.lap))
            }

            for ((index, segment) in course.segments.withIndex()) {
                val isLast = index == course.segments.lastIndex
                write(timerEvent(segment.records.first().timestamp, EventType.START))
                for (record in segment.records) {
                    write(recordMesg(record))
                }
                // STOP_ALL closes the file; a plain STOP closes one run with more to come.
                write(timerEvent(segment.records.last().timestamp, if (isLast) EventType.STOP_ALL else EventType.STOP))
            }
        }

    private fun fileIdMesg(course: FitCourse): FileIdMesg =
        FileIdMesg().apply {
            type = File.COURSE
            manufacturer = Manufacturer.DYNASTREAM
            product = PRODUCT_ID
            serialNumber = SERIAL_NUMBER
            // Deterministic per course rather than clock-stamped : two encodes of the same
            // course produce identical bytes, which is what makes round-trip tests meaningful.
            number = (course.name.hashCode() and 0xFFFF).toUShort()
            timeCreated = course.startTime
        }

    private fun courseMesg(course: FitCourse): CourseMesg =
        CourseMesg().apply {
            name = course.name
            sport = course.sport.toSdkSport()
        }

    private fun lapMesg(lap: FitLap): LapMesg =
        LapMesg().apply {
            startTime = lap.startTime
            // `timestamp` on a lap is its END, not its start — a classic FIT trap.
            timestamp = lap.startTime + lap.totalElapsedTimeS.secondsAsDuration()
            totalElapsedTime = lap.totalElapsedTimeS
            totalTimerTime = lap.totalTimerTimeS
            totalDistance = lap.totalDistanceM
            totalAscent = lap.totalAscentM.toUShort()
            totalDescent = lap.totalDescentM.toUShort()
            if (lap.totalTimerTimeS > 0.0) {
                avgSpeed = lap.totalDistanceM / lap.totalTimerTimeS
            }
            lap.maxSpeedMs?.let { maxSpeed = it }
            lap.minAltitudeM?.let { minAltitude = it }
            lap.maxAltitudeM?.let { maxAltitude = it }
            lap.startLatitudeDeg?.let { startPositionLat = FitUnits.degreesToSemicircles(it) }
            lap.startLongitudeDeg?.let { startPositionLong = FitUnits.degreesToSemicircles(it) }
            lap.endLatitudeDeg?.let { endPositionLat = FitUnits.degreesToSemicircles(it) }
            lap.endLongitudeDeg?.let { endPositionLong = FitUnits.degreesToSemicircles(it) }
        }

    private fun recordMesg(record: FitRecord): RecordMesg =
        RecordMesg().apply {
            timestamp = record.timestamp
            positionLat = FitUnits.degreesToSemicircles(record.latitudeDeg)
            positionLong = FitUnits.degreesToSemicircles(record.longitudeDeg)
            distance = record.distanceM
            record.altitudeM?.let { altitude = it }
            record.speedMs?.let { speed = it }
            record.powerW?.let { power = it.toUShort() }
            record.heartRate?.let { heartRate = it.toUByte() }
            record.cadence?.let { cadence = it.toUByte() }
            record.temperatureC?.let { temperature = it.roundToInt().toByte() }
        }

    private fun timerEvent(
        at: Instant,
        type: EventType,
    ): EventMesg =
        EventMesg().apply {
            event = Event.TIMER
            eventType = type
            eventGroup = 0u
            timestamp = at
        }

    private fun FitSport.toSdkSport(): Sport =
        when (this) {
            FitSport.CYCLING -> Sport.CYCLING
            FitSport.RUNNING -> Sport.RUNNING
            FitSport.GENERIC -> Sport.GENERIC
        }

    private fun Double.secondsAsDuration(): Duration = (this * 1000.0).toLong().milliseconds

    /** Arbitrary but stable identifiers, mirroring gpx2web's own placeholder values. */
    private const val PRODUCT_ID: UShort = 12345u

    private const val SERIAL_NUMBER: UInt = 12345u
}
