package io.github.glandais.fit

import com.garmin.fit.BufferEncoder
import com.garmin.fit.CourseMesg
import com.garmin.fit.DateTime
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.LapMesg
import com.garmin.fit.Manufacturer
import com.garmin.fit.RecordMesg
import com.garmin.fit.Sport
import java.util.Date
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import com.garmin.fit.File as FitFileType

/**
 * JVM `actual`, backed by the official Garmin Java SDK (`com.garmin:fit`), the same dependency
 * and version gpx2web uses.
 *
 * Message order is **not free** in a Course file and follows
 * `gpx2web/.../io/write/FitFileWriter.java`: `FileIdMesg`, then `CourseMesg`, then one `LapMesg`
 * per segment, then per segment a `TIMER`/`START` event, its `RecordMesg` stream and a
 * `TIMER`/`STOP` — `STOP_ALL` on the last one, which is what tells a reader the file is over.
 *
 * The g08 spec anticipated having to encode through a temporary file because `FileEncoder`
 * writes to a `java.io.File`. That turned out to be unnecessary: the SDK also ships
 * [BufferEncoder], whose `close()` returns the finished `byte[]` directly. No file system
 * access, so the `expect` signature returning a `ByteArray` is honest on this target.
 *
 * Unit handling: the SDK's typed setters take **real-world units** and apply the FIT scale and
 * offset themselves (`setAltitude` in m, `setDistance` in m, `setSpeed` in m/s, `setPower` in
 * W). The one exception is position, documented as `Units: semicircles`, so [FitUnits] does
 * that conversion — which is exactly why gpx2web needs its own `SemiCirclesConverter`.
 */
actual object FitEncoder {
    actual fun encode(course: FitCourse): ByteArray {
        val encoder = BufferEncoder(Fit.ProtocolVersion.V2_0)

        encoder.write(fileIdMesg(course))
        encoder.write(courseMesg(course))
        // All laps first, then the record runs — gpx2web's order (`FitFileWriter.writeGPX`).
        for (segment in course.segments) {
            encoder.write(lapMesg(segment.lap))
        }

        for ((index, segment) in course.segments.withIndex()) {
            val isLast = index == course.segments.lastIndex
            encoder.write(timerEvent(segment.records.first().timestamp, EventType.START))
            for (record in segment.records) {
                encoder.write(recordMesg(record))
            }
            // STOP_ALL closes the file; a plain STOP closes one run with more to come.
            encoder.write(timerEvent(segment.records.last().timestamp, if (isLast) EventType.STOP_ALL else EventType.STOP))
        }

        return encoder.close()
    }

    private fun fileIdMesg(course: FitCourse): FileIdMesg =
        FileIdMesg().apply {
            localNum = LOCAL_FILE_ID
            // `type` before `manufacturer` : both SDKs emit definition fields in the order the
            // caller sets them, so keeping the same order here makes the JVM and JS message
            // definitions structurally identical (see FitReferenceBytes for what still differs).
            type = FitFileType.COURSE
            manufacturer = Manufacturer.DYNASTREAM
            product = PRODUCT_ID
            serialNumber = SERIAL_NUMBER
            // Deterministic per course rather than `Date()`-stamped : two encodes of the same
            // course produce identical bytes, which is what makes round-trip tests meaningful.
            number = course.name.hashCode() and 0xFFFF
            timeCreated = course.startTime.toFitDateTime()
        }

    private fun courseMesg(course: FitCourse): CourseMesg =
        CourseMesg().apply {
            localNum = LOCAL_COURSE
            name = course.name
            sport = course.sport.toSdkSport()
        }

    private fun lapMesg(lap: FitLap): LapMesg =
        LapMesg().apply {
            localNum = LOCAL_LAP
            startTime = lap.startTime.toFitDateTime()
            // `timestamp` on a lap is its END, not its start — a classic FIT trap.
            timestamp = (lap.startTime + lap.totalElapsedTimeS.secondsAsDuration()).toFitDateTime()
            totalElapsedTime = lap.totalElapsedTimeS.toFloat()
            totalTimerTime = lap.totalTimerTimeS.toFloat()
            totalDistance = lap.totalDistanceM.toFloat()
            totalAscent = lap.totalAscentM
            totalDescent = lap.totalDescentM
            if (lap.totalTimerTimeS > 0.0) {
                avgSpeed = (lap.totalDistanceM / lap.totalTimerTimeS).toFloat()
            }
            lap.maxSpeedMs?.let { maxSpeed = it.toFloat() }
            lap.minAltitudeM?.let { minAltitude = it.toFloat() }
            lap.maxAltitudeM?.let { maxAltitude = it.toFloat() }
            lap.startLatitudeDeg?.let { startPositionLat = FitUnits.degreesToSemicircles(it) }
            lap.startLongitudeDeg?.let { startPositionLong = FitUnits.degreesToSemicircles(it) }
            lap.endLatitudeDeg?.let { endPositionLat = FitUnits.degreesToSemicircles(it) }
            lap.endLongitudeDeg?.let { endPositionLong = FitUnits.degreesToSemicircles(it) }
        }

    private fun recordMesg(record: FitRecord): RecordMesg =
        RecordMesg().apply {
            localNum = LOCAL_RECORD
            timestamp = record.timestamp.toFitDateTime()
            positionLat = FitUnits.degreesToSemicircles(record.latitudeDeg)
            positionLong = FitUnits.degreesToSemicircles(record.longitudeDeg)
            distance = record.distanceM.toFloat()
            record.altitudeM?.let { altitude = it.toFloat() }
            record.speedMs?.let { speed = it.toFloat() }
            record.powerW?.let { power = it }
            record.heartRate?.let { heartRate = it.toShort() }
            record.cadence?.let { cadence = it.toShort() }
            record.temperatureC?.let { temperature = it.roundToInt().toByte() }
        }

    private fun timerEvent(
        at: Instant,
        type: EventType,
    ): EventMesg =
        EventMesg().apply {
            localNum = LOCAL_EVENT
            event = Event.TIMER
            eventType = type
            eventGroup = 0
            timestamp = at.toFitDateTime()
        }

    private fun FitSport.toSdkSport(): Sport =
        when (this) {
            FitSport.CYCLING -> Sport.CYCLING
            FitSport.RUNNING -> Sport.RUNNING
            FitSport.GENERIC -> Sport.GENERIC
        }

    /**
     * `com.garmin.fit.DateTime` applies the FIT epoch offset itself when constructed from a
     * `java.util.Date`, so we hand it Unix milliseconds rather than pre-converting with
     * [FitUnits.toFitTimestamp]. `FitUnitsTest` asserts the two agree.
     */
    private fun Instant.toFitDateTime(): DateTime = DateTime(Date(toEpochMilliseconds()))

    private fun Double.secondsAsDuration(): Duration = (this * 1000.0).toLong().milliseconds

    // One local message number per message type, assigned in first-use order — the same
    // allocation the JavaScript SDK makes automatically. gpx2web puts everything on local 0,
    // which forces FIT to re-emit a definition every time the message type changes: harmless,
    // but it cost a redundant 18-byte `event` definition and made the JVM file longer than the
    // JS one for no reason. With these, both are 277 bytes with identical message definitions.
    private const val LOCAL_FILE_ID = 0
    private const val LOCAL_COURSE = 1
    private const val LOCAL_LAP = 2
    private const val LOCAL_EVENT = 3
    private const val LOCAL_RECORD = 4

    /** Arbitrary but stable identifiers, mirroring gpx2web's own placeholder values. */
    private const val PRODUCT_ID = 12345

    private const val SERIAL_NUMBER = 12345L
}
