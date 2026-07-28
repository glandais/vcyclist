package io.github.glandais.elevation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Providers the Java bridge tests cannot build themselves.
 *
 * `ElevationProvider`'s fetcher is a `suspend (String) -> RawTile`, which has no Java literal —
 * that is exactly why the bridges of task g22 exist, and equally why the fixtures they are
 * tested with have to be written in Kotlin. Java and Kotlin sources of a JVM test compilation
 * see each other, so `@JvmStatic` factories here are directly callable from the `.java` tests.
 */
object JvmBridgeFixtures {
    const val TILE_SIZE: Int = 4

    /** Config pointing at a scheme nothing can fetch — any network access fails loudly. */
    @JvmStatic
    fun config(): ElevationProviderConfig =
        ElevationProviderConfig(
            zoomLevel = 0,
            tileSize = TILE_SIZE,
            cacheSize = 4,
            tileUrlTemplate = "test://{z}/{x}/{y}",
        )

    /** Elevation at pixel `(px, py)` is `px * 100 + py`, like `ElevationProviderTest`. */
    @JvmStatic
    fun syntheticProvider(): ElevationProvider = ElevationProvider(config()) { syntheticTile() }

    /** A provider whose every fetch throws — for the exception-propagation tests. */
    @JvmStatic
    fun failingProvider(message: String): ElevationProvider = ElevationProvider(config()) { error(message) }

    /**
     * A provider that never returns until cancelled, and records that its coroutine *was*
     * cancelled rather than left running. [started] completes as soon as the fetch is entered,
     * so a test can cancel at a deterministic point instead of sleeping and hoping.
     */
    class SlowProvider {
        val started: CompletableDeferred<Unit> = CompletableDeferred()
        private val cancelledFlag = AtomicBoolean(false)

        fun wasCancelled(): Boolean = cancelledFlag.get()

        val provider: ElevationProvider =
            ElevationProvider(config()) {
                started.complete(Unit)
                try {
                    delay(60_000)
                    syntheticTile()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cancelledFlag.set(true)
                    throw e
                }
            }
    }

    /** Blocks until the slow provider's fetch has actually started. */
    @JvmStatic
    fun awaitStarted(slow: SlowProvider) {
        kotlinx.coroutines.runBlocking { slow.started.await() }
    }

    private fun syntheticTile(): RawTile {
        val rgba = ByteArray(TILE_SIZE * TILE_SIZE * 4)
        for (py in 0 until TILE_SIZE) {
            for (px in 0 until TILE_SIZE) {
                val raw = px * 100 + py + 32768
                val idx = (py * TILE_SIZE + px) * 4
                rgba[idx] = ((raw shr 8) and 0xFF).toByte()
                rgba[idx + 1] = (raw and 0xFF).toByte()
                rgba[idx + 2] = 0
                rgba[idx + 3] = 255.toByte()
            }
        }
        return RawTile(TILE_SIZE, TILE_SIZE, rgba)
    }
}
