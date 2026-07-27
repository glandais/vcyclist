@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.glandais.fit

import org.khronos.webgl.Int8Array
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Decoder half of `@garmin/fitsdk`, reached through `@JsFun` since Wasm has no `dynamic`. */
@JsModule("@garmin/fitsdk")
private external object FitSdkTest {
    val Decoder: JsAny
    val Stream: JsAny
}

@JsFun(
    "(Stream, Decoder, signed) => { const u8 = new Uint8Array(signed.buffer, signed.byteOffset, signed.byteLength); const d = new Decoder(Stream.fromByteArray(u8)); return { integrity: d.checkIntegrity(), result: d.read() }; }",
)
private external fun decodeWith(
    stream: JsAny,
    decoder: JsAny,
    signed: Int8Array,
): JsAny

@JsFun("(r) => r.integrity")
private external fun integrityOf(r: JsAny): Boolean

@JsFun("(r) => r.result.errors.length")
private external fun errorCountOf(r: JsAny): Int

@JsFun("(r) => r.result.messages.recordMesgs.length")
private external fun recordCountOf(r: JsAny): Int

@JsFun("(r, i, field) => r.result.messages.recordMesgs[i][field]")
private external fun recordNumber(
    r: JsAny,
    i: Int,
    field: String,
): Double

@JsFun("(r) => r.result.messages.courseMesgs[0].name")
private external fun courseNameOf(r: JsAny): String

@JsFun("(r) => r.result.messages.courseMesgs[0].sport")
private external fun courseSportOf(r: JsAny): String

@JsFun("(arr) => new Int8Array(arr)")
private external fun toInt8(arr: JsArray<JsNumber>): Int8Array

/**
 * Kotlin/Wasm encoder tests, running in headless Chrome under Karma — the configuration the
 * plan flagged as the main risk of this task. `@garmin/fitsdk` is bundled by webpack rather
 * than declared external, and it loads and runs there.
 */
class FitEncoderWasmTest {
    private fun ByteArray.toJsInt8(): Int8Array {
        val arr = JsArray<JsNumber>()
        for ((i, b) in withIndex()) arr[i] = b.toInt().toJsNumber()
        return toInt8(arr)
    }

    private fun decode(bytes: ByteArray): JsAny {
        val r = decodeWith(FitSdkTest.Stream, FitSdkTest.Decoder, bytes.toJsInt8())
        assertTrue(integrityOf(r), "the SDK decoder rejected the file's integrity check")
        return r
    }

    @Test
    fun `encodes a course the SDK decoder accepts`() {
        val bytes = FitEncoder.encode(FitReferenceCourse.build())
        assertTrue(bytes.size > 100, "suspiciously small FIT file: ${bytes.size} bytes")
        assertEquals(".FIT", bytes.copyOfRange(8, 12).decodeToString())
        val r = decode(bytes)
        assertEquals(0, errorCountOf(r), "decoder reported errors")
    }

    @Test
    fun `records round-trip with their positions and sensor fields`() {
        val src = FitReferenceCourse.build()
        val r = decode(FitEncoder.encode(src))
        assertEquals(src.records.size, recordCountOf(r))
        src.records.forEachIndexed { i, expected ->
            assertEquals(
                expected.latitudeDeg,
                FitUnits.semicirclesToDegrees(recordNumber(r, i, "positionLat").toInt()),
                1e-6,
                "record $i latitude",
            )
            assertEquals(
                expected.longitudeDeg,
                FitUnits.semicirclesToDegrees(recordNumber(r, i, "positionLong").toInt()),
                1e-6,
                "record $i longitude",
            )
            assertEquals(expected.altitudeM!!, recordNumber(r, i, "altitude"), 0.2, "record $i altitude")
            assertEquals(expected.distanceM, recordNumber(r, i, "distance"), 0.01, "record $i distance")
            assertEquals(expected.speedMs!!, recordNumber(r, i, "speed"), 0.001, "record $i speed")
            assertEquals(expected.powerW!!.toDouble(), recordNumber(r, i, "power"), 0.5, "record $i power")
        }
    }

    @Test
    fun `the course message carries the name and sport`() {
        val r = decode(FitEncoder.encode(FitReferenceCourse.build()))
        assertEquals(FitReferenceCourse.NAME, courseNameOf(r))
        assertEquals("cycling", courseSportOf(r))
    }

    @Test
    fun `Kotlin_Wasm reproduces the committed web reference bytes`() {
        // Byte-identical to Kotlin/JS. This is the guard that keeps the two hand-written
        // `actual`s aligned: they build FIT messages independently, and both SDKs derive the
        // message definition from key insertion order, so any reordering would show up here.
        assertTrue(
            FitEncoder.encode(FitReferenceCourse.build()).contentEquals(FitReferenceBytes.WEB),
            "Kotlin/Wasm output diverged from the committed reference bytes",
        )
    }

    @Test
    fun `the JS SDK running under Wasm decodes what the JVM encoder produces`() {
        val r = decode(FitReferenceBytes.JVM)
        assertEquals(0, errorCountOf(r))
        assertEquals(FitReferenceCourse.NAME, courseNameOf(r))
        assertEquals(FitReferenceCourse.build().records.size, recordCountOf(r))
    }

    @Test
    fun `bytes above 0x7F survive the Uint8Array to ByteArray conversion`() {
        val bytes = FitEncoder.encode(FitReferenceCourse.build())
        assertTrue(bytes.any { it < 0 }, "expected at least one byte above 0x7F in a FIT file")
        decode(bytes)
    }
}
