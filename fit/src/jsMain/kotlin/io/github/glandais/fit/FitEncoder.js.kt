package io.github.glandais.fit

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import kotlin.math.round
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Kotlin/JS `actual`, backed by the official Garmin JavaScript SDK `@garmin/fitsdk`, pinned to
 * 21.205.0 — the same profile revision as the `com.garmin:fit` artefact the JVM target uses.
 *
 * ## How this SDK differs from the Java one
 *
 * The Java SDK has a typed class per message (`RecordMesg`, `LapMesg`, …). The JavaScript SDK
 * has one `Encoder.writeMesg(mesg)` taking a plain object whose `mesgNum` selects the message
 * and whose remaining keys are **profile field names** (`positionLat`, `totalElapsedTime`, …).
 * That is exactly why the `expect` in [FitEncoder] is coarse: there is nothing thinner to
 * factor out. Message numbers and enum wire values live in [FitMessageNumbers] so this file
 * reads them from one shared source rather than inlining its own copy.
 *
 * ## Units
 *
 * Verified in the SDK source (`encoder.js`, `transformValues`): like the Java SDK, it applies
 * the profile's scale and offset itself, so altitude / distance / speed are passed in
 * **real-world units**. `dateTime` fields accept a JS `Date` and go through
 * `Utils.convertDateToDateTime`, which uses the same FIT epoch as [FitUnits] (checked:
 * 1989-12-31T00:00:00Z maps to 0). Position is the one field with no scale — the profile
 * documents it as `Units: semicircles` — so [FitUnits] converts it, on both targets.
 *
 * ## Bundling
 *
 * Unlike `@jsquash/webp`, this package is plain JavaScript with no native or Wasm payload, so
 * it is **bundled** into the browser distribution rather than declared external. Browser FIT
 * export is the point of task g09, so there is deliberately no `webpack.config.d/externals.js`
 * for `:fit`.
 */
actual object FitEncoder {
    actual fun encode(course: FitCourse): ByteArray {
        val encoder = FitSdk.Encoder()

        encoder.writeMesg(
            mesg(FitMessageNumbers.FILE_ID) { o ->
                o["type"] = FitMessageNumbers.FILE_TYPE_COURSE
                o["manufacturer"] = FitMessageNumbers.MANUFACTURER_DYNASTREAM
                o["product"] = PRODUCT_ID
                o["serialNumber"] = SERIAL_NUMBER
                o["number"] = course.name.hashCode() and 0xFFFF
                o["timeCreated"] = course.startTime.toJsDate()
            },
        )

        encoder.writeMesg(
            mesg(FitMessageNumbers.COURSE) { o ->
                o["name"] = course.name
                o["sport"] = course.sport.value
            },
        )

        for (lap in course.segments.map { it.lap }) {
            encoder.writeMesg(lapMesg(lap))
        }

        for ((index, segment) in course.segments.withIndex()) {
            val isLast = index == course.segments.lastIndex
            encoder.writeMesg(timerEvent(segment.records.first().timestamp, FitMessageNumbers.EVENT_TYPE_START))
            for (record in segment.records) {
                encoder.writeMesg(recordMesg(record))
            }
            encoder.writeMesg(
                timerEvent(
                    segment.records.last().timestamp,
                    if (isLast) FitMessageNumbers.EVENT_TYPE_STOP_ALL else FitMessageNumbers.EVENT_TYPE_STOP,
                ),
            )
        }

        return encoder.close().toByteArray()
    }

    private fun lapMesg(lap: FitLap): dynamic =
        mesg(FitMessageNumbers.LAP) { o ->
            o["startTime"] = lap.startTime.toJsDate()
            // A lap's `timestamp` is its END, not its start — same trap as on the JVM side.
            o["timestamp"] = (lap.startTime + lap.totalElapsedTimeS.seconds()).toJsDate()
            o["totalElapsedTime"] = lap.totalElapsedTimeS
            o["totalTimerTime"] = lap.totalTimerTimeS
            o["totalDistance"] = lap.totalDistanceM
            o["totalAscent"] = lap.totalAscentM
            o["totalDescent"] = lap.totalDescentM
            if (lap.totalTimerTimeS > 0.0) {
                o["avgSpeed"] = lap.totalDistanceM / lap.totalTimerTimeS
            }
            lap.maxSpeedMs?.let { o["maxSpeed"] = it }
            lap.minAltitudeM?.let { o["minAltitude"] = it }
            lap.maxAltitudeM?.let { o["maxAltitude"] = it }
            lap.startLatitudeDeg?.let { o["startPositionLat"] = FitUnits.degreesToSemicircles(it) }
            lap.startLongitudeDeg?.let { o["startPositionLong"] = FitUnits.degreesToSemicircles(it) }
            lap.endLatitudeDeg?.let { o["endPositionLat"] = FitUnits.degreesToSemicircles(it) }
            lap.endLongitudeDeg?.let { o["endPositionLong"] = FitUnits.degreesToSemicircles(it) }
        }

    private fun recordMesg(record: FitRecord): dynamic =
        mesg(FitMessageNumbers.RECORD) { o ->
            o["timestamp"] = record.timestamp.toJsDate()
            o["positionLat"] = FitUnits.degreesToSemicircles(record.latitudeDeg)
            o["positionLong"] = FitUnits.degreesToSemicircles(record.longitudeDeg)
            o["distance"] = record.distanceM
            record.altitudeM?.let { o["altitude"] = it }
            record.speedMs?.let { o["speed"] = it }
            record.powerW?.let { o["power"] = it }
            record.heartRate?.let { o["heartRate"] = it }
            record.cadence?.let { o["cadence"] = it }
            record.temperatureC?.let { o["temperature"] = round(it).toInt() }
        }

    private fun timerEvent(
        at: Instant,
        eventType: Int,
    ): dynamic =
        mesg(FitMessageNumbers.EVENT) { o ->
            o["event"] = FitMessageNumbers.EVENT_TIMER
            o["eventType"] = eventType
            o["eventGroup"] = 0
            o["timestamp"] = at.toJsDate()
        }

    private inline fun mesg(
        mesgNum: Int,
        fill: (dynamic) -> Unit,
    ): dynamic {
        val o = js("({})")
        o["mesgNum"] = mesgNum
        fill(o)
        return o
    }

    /**
     * `Uint8Array` holds unsigned bytes, Kotlin's `Byte` is signed. The indexed `get` already
     * hands back a `Byte` carrying the right bit pattern, so no masking is involved — but the
     * reinterpretation is worth naming: getting it wrong corrupts every byte above 0x7F, which
     * in a binary format means a file that fails its CRC rather than one that merely looks odd.
     */
    private fun Uint8Array.toByteArray(): ByteArray = ByteArray(length) { i -> this[i] }

    private fun Instant.toJsDate(): kotlin.js.Date = kotlin.js.Date(toEpochMilliseconds().toDouble())

    private fun Double.seconds(): Duration = (this * 1000.0).toLong().milliseconds

    private const val PRODUCT_ID = 12345

    private const val SERIAL_NUMBER = 12345.0
}

/**
 * Minimal `external` view of `@garmin/fitsdk`. Only the encoder is needed in production code ;
 * the decoder the tests replay through is declared in the test source set.
 *
 * Both annotations are required by the Kotlin/JS compiler because this module is emitted as
 * UMD. `@JsNonModule` is the compiler's "also reachable as a global" escape hatch ; in practice
 * every consumer path here goes through a module loader (webpack for the browser bundle,
 * Node's resolver for the Node tests), so the global branch is never taken.
 */
@JsModule("@garmin/fitsdk")
@JsNonModule
external object FitSdk {
    class Encoder {
        fun writeMesg(mesg: dynamic)

        fun close(): Uint8Array
    }
}
