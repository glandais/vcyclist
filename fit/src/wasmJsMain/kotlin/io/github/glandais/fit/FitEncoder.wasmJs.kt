@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.glandais.fit

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.toByteArray
import kotlin.math.round
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Kotlin/Wasm `actual`, backed by the same `@garmin/fitsdk@21.205.0` as the Kotlin/JS target.
 *
 * This file is a **deliberate structural mirror** of `FitEncoder.js.kt`: same message order,
 * same field order, same values. Field order matters more than it looks — both SDKs derive a
 * FIT message *definition* from the order the caller sets keys, so reordering here would change
 * the bytes. `FitReferenceBytes` pins that down: Wasm and JS must produce byte-identical files,
 * so any drift between the two sources fails a test rather than shipping.
 *
 * Wasm cannot hold arbitrary JS objects, so instead of Kotlin/JS's `dynamic` the interop goes
 * through the `@JsFun` builders at the bottom of this file — the pattern documented in
 * `docs/kotlin-wasm-jvm-webp.md` §4 and already used by `TileFetcher.wasmJs.kt`.
 */
actual object FitEncoder {
    actual fun encode(course: FitCourse): ByteArray {
        val encoder = newEncoder(encoderClass())

        val fileId = jsObject()
        jsSetNumber(fileId, "mesgNum", FitMessageNumbers.FILE_ID.toDouble())
        jsSetNumber(fileId, "type", FitMessageNumbers.FILE_TYPE_COURSE.toDouble())
        jsSetNumber(fileId, "manufacturer", FitMessageNumbers.MANUFACTURER_DYNASTREAM.toDouble())
        jsSetNumber(fileId, "product", PRODUCT_ID)
        jsSetNumber(fileId, "serialNumber", SERIAL_NUMBER)
        jsSetNumber(fileId, "number", (course.name.hashCode() and 0xFFFF).toDouble())
        jsSetDate(fileId, "timeCreated", course.startTime.epochMillisDouble())
        writeMesg(encoder, fileId)

        val courseMesg = jsObject()
        jsSetNumber(courseMesg, "mesgNum", FitMessageNumbers.COURSE.toDouble())
        jsSetString(courseMesg, "name", course.name)
        jsSetNumber(courseMesg, "sport", course.sport.value.toDouble())
        writeMesg(encoder, courseMesg)

        writeMesg(encoder, lapMesg(course.lap))

        writeMesg(encoder, timerEvent(course.records.first().timestamp, FitMessageNumbers.EVENT_TYPE_START))
        for (record in course.records) {
            writeMesg(encoder, recordMesg(record))
        }
        writeMesg(encoder, timerEvent(course.records.last().timestamp, FitMessageNumbers.EVENT_TYPE_STOP_ALL))

        return closeEncoder(encoder).asKotlinBytes()
    }

    private fun lapMesg(lap: FitLap): JsAny {
        val m = jsObject()
        jsSetNumber(m, "mesgNum", FitMessageNumbers.LAP.toDouble())
        jsSetDate(m, "startTime", lap.startTime.epochMillisDouble())
        // A lap's `timestamp` is its END, not its start.
        jsSetDate(m, "timestamp", (lap.startTime + lap.totalElapsedTimeS.seconds()).epochMillisDouble())
        jsSetNumber(m, "totalElapsedTime", lap.totalElapsedTimeS)
        jsSetNumber(m, "totalTimerTime", lap.totalTimerTimeS)
        jsSetNumber(m, "totalDistance", lap.totalDistanceM)
        jsSetNumber(m, "totalAscent", lap.totalAscentM.toDouble())
        jsSetNumber(m, "totalDescent", lap.totalDescentM.toDouble())
        if (lap.totalTimerTimeS > 0.0) {
            jsSetNumber(m, "avgSpeed", lap.totalDistanceM / lap.totalTimerTimeS)
        }
        lap.maxSpeedMs?.let { jsSetNumber(m, "maxSpeed", it) }
        lap.minAltitudeM?.let { jsSetNumber(m, "minAltitude", it) }
        lap.maxAltitudeM?.let { jsSetNumber(m, "maxAltitude", it) }
        lap.startLatitudeDeg?.let { jsSetNumber(m, "startPositionLat", semi(it)) }
        lap.startLongitudeDeg?.let { jsSetNumber(m, "startPositionLong", semi(it)) }
        lap.endLatitudeDeg?.let { jsSetNumber(m, "endPositionLat", semi(it)) }
        lap.endLongitudeDeg?.let { jsSetNumber(m, "endPositionLong", semi(it)) }
        return m
    }

    private fun recordMesg(record: FitRecord): JsAny {
        val m = jsObject()
        jsSetNumber(m, "mesgNum", FitMessageNumbers.RECORD.toDouble())
        jsSetDate(m, "timestamp", record.timestamp.epochMillisDouble())
        jsSetNumber(m, "positionLat", semi(record.latitudeDeg))
        jsSetNumber(m, "positionLong", semi(record.longitudeDeg))
        jsSetNumber(m, "distance", record.distanceM)
        record.altitudeM?.let { jsSetNumber(m, "altitude", it) }
        record.speedMs?.let { jsSetNumber(m, "speed", it) }
        record.powerW?.let { jsSetNumber(m, "power", it.toDouble()) }
        record.heartRate?.let { jsSetNumber(m, "heartRate", it.toDouble()) }
        record.cadence?.let { jsSetNumber(m, "cadence", it.toDouble()) }
        record.temperatureC?.let { jsSetNumber(m, "temperature", round(it)) }
        return m
    }

    private fun timerEvent(
        at: Instant,
        eventType: Int,
    ): JsAny {
        val e = jsObject()
        jsSetNumber(e, "mesgNum", FitMessageNumbers.EVENT.toDouble())
        jsSetNumber(e, "event", FitMessageNumbers.EVENT_TIMER.toDouble())
        jsSetNumber(e, "eventType", eventType.toDouble())
        jsSetNumber(e, "eventGroup", 0.0)
        jsSetDate(e, "timestamp", at.epochMillisDouble())
        return e
    }

    private fun semi(degrees: Double): Double = FitUnits.degreesToSemicircles(degrees).toDouble()

    /**
     * Copy the encoder's `Uint8Array` into a Kotlin `ByteArray`.
     *
     * The buffer crossing is the one genuinely Wasm-specific cost here. It is reinterpreted as
     * a **signed** `Int8Array` — same memory, no copy on the JS side — and then handed to
     * kotlinx-browser's `toByteArray()`, which does the bulk transfer.
     *
     * Measured in headless Chrome on a 10 000-record course (230 193 bytes of FIT): **1.8 ms**
     * for the transfer against **739 ms** for the whole encode, i.e. 0.24 %. The SDK's own
     * message writing dominates completely, so there was nothing to gain from a smarter copy.
     */
    private fun Uint8Array.asKotlinBytes(): ByteArray = asInt8Array(this).toByteArray()

    private fun Instant.epochMillisDouble(): Double = toEpochMilliseconds().toDouble()

    private fun Double.seconds(): Duration = (this * 1000.0).toLong().milliseconds

    private const val PRODUCT_ID = 12345.0

    private const val SERIAL_NUMBER = 12345.0
}

// ── `@garmin/fitsdk` interop ────────────────────────────────────────────────────────────────

/** The `Encoder` class itself, imported as a value so [newEncoder] can `new` it. */
@JsModule("@garmin/fitsdk")
private external object FitSdk {
    val Encoder: JsAny
}

private fun encoderClass(): JsAny = FitSdk.Encoder

@JsFun("(EncoderClass) => new EncoderClass()")
private external fun newEncoder(encoderClass: JsAny): JsAny

@JsFun("(encoder, mesg) => encoder.writeMesg(mesg)")
private external fun writeMesg(
    encoder: JsAny,
    mesg: JsAny,
)

@JsFun("(encoder) => encoder.close()")
private external fun closeEncoder(encoder: JsAny): Uint8Array

@JsFun("() => ({})")
private external fun jsObject(): JsAny

@JsFun("(o, key, value) => { o[key] = value; }")
private external fun jsSetNumber(
    o: JsAny,
    key: String,
    value: Double,
)

@JsFun("(o, key, value) => { o[key] = value; }")
private external fun jsSetString(
    o: JsAny,
    key: String,
    value: String,
)

@JsFun("(o, key, epochMs) => { o[key] = new Date(epochMs); }")
private external fun jsSetDate(
    o: JsAny,
    key: String,
    epochMs: Double,
)

/** Same bytes, signed view — mirrors the trick already used in `TileFetcher.wasmJs.kt`. */
@JsFun("(arr) => new Int8Array(arr.buffer, arr.byteOffset, arr.byteLength)")
private external fun asInt8Array(arr: Uint8Array): org.khronos.webgl.Int8Array
