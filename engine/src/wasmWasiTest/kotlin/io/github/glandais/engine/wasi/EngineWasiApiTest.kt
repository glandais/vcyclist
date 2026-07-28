package io.github.glandais.engine.wasi

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for the half of the ABI that does not touch a host import.
 *
 * The other half — the exports themselves — cannot be tested here: they call `read_input` /
 * `write_output`, and the KGP test runner does not supply the custom `vcyclist` imports (see
 * `docs/kotlin-wasm-wasi.md` §5). Reaching one from a test would fail at instantiation, so the
 * end-to-end coverage is a real host's job, which is task w09. That constraint is precisely why
 * [WasiAbi] is a separate object from `EngineWasiApi`.
 */
class EngineWasiApiTest {
    private fun path(points: Int = 3): Path {
        val p = Path(points)
        for (i in 0 until points) {
            p.setLatitude(i, (45.68 + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (6.39 + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 350.0 + i)
            p.setTime(i, i * 10_000.0)
        }
        p.computeDerivedData()
        return p
    }

    @BeforeTest
    fun reset() = WasiAbi.resetForTests()

    @AfterTest
    fun clean() = WasiAbi.resetForTests()

    @Test
    fun `the ABI version is 1 and is reachable without any host import`() {
        assertEquals(1, WasiAbi.VERSION)
        assertEquals(WasiAbi.VERSION, vcAbiVersion(), "the export must not reinterpret the constant")
    }

    @Test
    fun `handles are positive, distinct, and hand back the very same path`() {
        val first = path()
        val second = path(5)

        val h1 = WasiAbi.register(first)
        val h2 = WasiAbi.register(second)

        assertTrue(h1 > 0 && h2 > 0, "handles must be positive: $h1, $h2")
        assertNotEquals(h1, h2)
        assertSame(first, WasiAbi.pathOrNull(h1))
        assertSame(second, WasiAbi.pathOrNull(h2))
        assertEquals(2, WasiAbi.liveHandles)
    }

    @Test
    fun `a released handle is gone, and releasing it twice is not an error`() {
        val handle = WasiAbi.register(path())

        assertEquals(1, WasiAbi.release(handle), "first release reports it existed")
        assertEquals(0, WasiAbi.release(handle), "second release reports it did not")
        assertNull(WasiAbi.pathOrNull(handle))
        assertEquals(0, WasiAbi.liveHandles)
    }

    @Test
    fun `a released handle is never reissued`() {
        val first = WasiAbi.register(path())
        WasiAbi.release(first)

        val second = WasiAbi.register(path())

        assertNotEquals(first, second, "reusing a key would silently resurrect a stale host handle")
    }

    @Test
    fun `releaseAll empties the table and reports what it dropped`() {
        repeat(3) { WasiAbi.register(path()) }

        assertEquals(3, WasiAbi.releaseAll())
        assertEquals(0, WasiAbi.liveHandles)
        assertEquals(0, WasiAbi.releaseAll(), "a second sweep has nothing to drop")
    }

    @Test
    fun `an unknown handle yields the typed code, not the generic one`() {
        assertEquals(WasiAbi.ERR_UNKNOWN_HANDLE, vcPathSize(404))
        assertEquals(WasiAbi.ERR_UNKNOWN_HANDLE.toDouble(), vcPathTotalDistance(404))
        assertTrue(WasiAbi.lastError.contains("404"), "the message must name the handle: ${WasiAbi.lastError}")
    }

    @Test
    fun `the three error codes are distinct, negative, and stable`() {
        assertEquals(-1, WasiAbi.ERR_GENERIC)
        assertEquals(-2, WasiAbi.ERR_UNKNOWN_HANDLE)
        assertEquals(-3, WasiAbi.ERR_INVALID_ARGUMENT)
    }

    @Test
    fun `a non-positive byteLen is refused, with the offending value in the message`() {
        assertNull(WasiAbi.invalidLengthOrNull(1), "a usable length must not be an error")
        assertEquals(WasiAbi.ERR_INVALID_ARGUMENT, WasiAbi.invalidLengthOrNull(0))
        assertEquals(WasiAbi.ERR_INVALID_ARGUMENT, WasiAbi.invalidLengthOrNull(-7))
        assertTrue(WasiAbi.lastError.contains("-7"), "the message must name the offending value")
    }

    @Test
    fun `fail records the message and returns the code it was given`() {
        assertEquals(WasiAbi.ERR_GENERIC, WasiAbi.fail(IllegalStateException("boom")))
        assertEquals("boom", WasiAbi.lastError)

        assertEquals(WasiAbi.ERR_INVALID_ARGUMENT, WasiAbi.fail(WasiAbi.ERR_INVALID_ARGUMENT, "nope"))
        assertEquals("nope", WasiAbi.lastError)
    }

    @Test
    fun `an exception with no message still yields something a host can print`() {
        assertEquals(WasiAbi.ERR_GENERIC, WasiAbi.fail(IllegalStateException()))
        assertTrue(WasiAbi.lastError.isNotEmpty(), "an empty lastError tells a host nothing")
    }
}
