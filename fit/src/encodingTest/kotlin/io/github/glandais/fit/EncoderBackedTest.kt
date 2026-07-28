package io.github.glandais.fit

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The three cases of `PathToFitTest` / `PathToFitMultiTest` / `PathToFitTimestampTest` that
 * actually call [FitEncoder] — everything else in those files stops at [FitCourse] and stays in
 * `commonTest`.
 *
 * ## Why they are not in `commonTest`
 *
 * Task w01 added the `wasmWasi` target, where [FitEncoder] is a stub that throws: both real
 * `actual`s wrap an official Garmin SDK and neither runs under WASI (see
 * `FitEncoder.wasmWasi.kt`, and task w12 for the pure-Kotlin encoder that would lift the
 * restriction). So these three cases can only fail there, while the conversion logic they sit
 * next to is perfectly portable.
 *
 * `src/encodingTest/kotlin` is added as an extra source directory to the `jvmTest` and `jsTest`
 * compilations by `fit/build.gradle.kts` — the same single-file-two-compilations trick as
 * `commonTestFixtures` in `gpx/build.gradle.kts`, and as `:elevation`'s `decodingTest`. The JVM
 * and JS coverage is unchanged ; only wasmWasi stops running what it cannot run.
 *
 * The names keep their original `case NN` numbering so a failure still points back to the file
 * the case came from.
 */
class EncoderBackedTest {
    private val start = Instant.parse("2026-07-28T08:00:00Z")

    /** Three points, ~70 m apart, 10 s apart — `PathToFitTest.samplePath`, minus the sensors. */
    private fun samplePath(): Path {
        val p = Path(3)
        val lat = doubleArrayOf(45.680697, 45.681335, 45.681565)
        val lon = doubleArrayOf(6.396115, 6.396195, 6.396291)
        val ele = doubleArrayOf(350.1, 349.7, 349.5)
        for (i in 0 until 3) {
            p.setLatitude(i, lat[i] * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, lon[i] * MathConstants.DEG_TO_RAD)
            p.setElevation(i, ele[i])
            p.setTime(i, i * 10_000.0)
        }
        p.computeDerivedData()
        return p
    }

    /** `PathToFitMultiTest.path` / `PathToFitTimestampTest.path`, with a settable clock origin. */
    private fun path(
        points: Int = 3,
        latBase: Double = 45.68,
        t0: Double = 0.0,
        stepMs: Double = 10_000.0,
    ): Path {
        val p = Path(points)
        for (i in 0 until points) {
            p.setLatitude(i, (latBase + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (6.39 + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 350.0 + i)
            p.setTime(i, t0 + i * stepMs)
        }
        p.computeDerivedData()
        return p
    }

    /** Was `PathToFitTest` case 12. */
    @Test
    fun `case 12 — toFitBytes produces a FIT file`() {
        val bytes = samplePath().toFitBytes("shortcut", start)
        assertEquals(".FIT", bytes.copyOfRange(8, 12).decodeToString())
    }

    /** Was `PathToFitMultiTest` case 09. */
    @Test
    fun `case 09 — the encoded multi-path file is bigger and still deterministic`() {
        val one = FitEncoder.encode(path().toFitCourse("multi", start))
        val three = FitEncoder.encode(listOf(path(), path(latBase = 46.0), path(latBase = 47.0)).toFitCourse("multi", start))

        assertTrue(three.size > one.size, "three paths cannot fit in the bytes of one")
        val again = FitEncoder.encode(listOf(path(), path(latBase = 46.0), path(latBase = 47.0)).toFitCourse("multi", start))
        assertEquals(three.toList(), again.toList(), "encoding must stay deterministic")
    }

    /** Was `PathToFitTimestampTest` case 03 — the byte-level half of it. */
    @Test
    fun `case 03 — the two clocks produce the same file`() {
        val relative = path(t0 = 0.0).toFitCourse("test", start)
        val absolute = path(t0 = 1_714_550_400_000.0).toFitCourse("test", start)

        val expected = FitEncoder.encode(relative)
        val actual = FitEncoder.encode(absolute)
        assertEquals(expected.size, actual.size, "encoded size")
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i], "byte $i")
        }
    }
}
