package io.github.glandais.fit

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The counterpart of moving the encoder-backed cases to `src/encodingTest` (task w01): what
 * wasmWasi keeps is the whole `Path` → [FitCourse] conversion, and what it loses is exactly one
 * step — [FitEncoder.encode]. This pins both halves of that statement, because a stub nobody
 * exercises quietly turns into a stub nobody notices is still there.
 */
class FitEncoderStubTest {
    private val start = Instant.parse("2026-07-28T08:00:00Z")

    private fun path(): Path {
        val p = Path(3)
        for (i in 0 until 3) {
            p.setLatitude(i, (45.68 + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (6.39 + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 350.0 + i)
            p.setTime(i, i * 10_000.0)
        }
        p.computeDerivedData()
        return p
    }

    @Test
    fun `encode fails loudly rather than returning a truncated file`() {
        val message =
            assertFailsWith<UnsupportedOperationException> {
                FitEncoder.encode(path().toFitCourse("stub", start))
            }.message ?: ""

        assertTrue(message.contains("wasmWasi"), "must name the target, was: $message")
        assertTrue(message.contains("w12"), "must name the way out, was: $message")
    }

    @Test
    fun `toFitBytes fails the same way — no silent empty ByteArray`() {
        assertFailsWith<UnsupportedOperationException> { path().toFitBytes("stub", start) }
    }

    @Test
    fun `the conversion up to FitCourse works under WASI`() {
        val course = path().toFitCourse("stub", start)

        assertEquals(3, course.records.size)
        assertEquals(start, course.records.first().timestamp)
        assertEquals(FitSport.CYCLING, course.sport)
    }
}
