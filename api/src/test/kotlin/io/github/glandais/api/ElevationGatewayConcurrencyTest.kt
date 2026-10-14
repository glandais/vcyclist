package io.github.glandais.api

import io.github.glandais.api.dto.ElevationPointResponse
import io.github.glandais.api.http.ApiJson
import io.github.glandais.api.service.ElevationGateway
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.ElevationSource
import io.github.glandais.elevation.LatLon
import io.github.glandais.elevation.RawTile
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Requests share a DEM provider concurrently: they overlap, read the same values, fetch each tile once. */
class ElevationGatewayConcurrencyTest {
    /** `z`, `x`, `y` of an XYZ tile URL. */
    private fun xyz(url: String): Triple<Int, Int, Int> {
        val (z, x, y) = Regex("""/(\d+)/(\d+)/(\d+)\.""").find(url)!!.destructured
        return Triple(z.toInt(), x.toInt(), y.toInt())
    }

    /** A tile at one whole elevation everywhere, in Terrarium bytes. */
    private fun flat(
        size: Int,
        metres: Int,
    ): RawTile {
        val v = metres + 32768
        val rgba = ByteArray(size * size * 4)
        for (i in 0 until size * size) {
            rgba[i * 4] = (v / 256).toByte()
            rgba[i * 4 + 1] = (v % 256).toByte()
            rgba[i * 4 + 3] = 255.toByte()
        }
        return RawTile(size, size, rgba)
    }

    @Test
    fun `a request fetching tiles does not hold the next one on the same source`() {
        // Each fetch waits until both requests are fetching: had the provider been serialised, the
        // second would never start and the first would time out in its fetcher.
        val west = CompletableDeferred<Unit>()
        val east = CompletableDeferred<Unit>()
        val deps =
            ApiDependencies(
                elevation =
                    ElevationGateway { config: ElevationProviderConfig ->
                        { url: String ->
                            val (z, x, _) = xyz(url)
                            val lon = x * 360.0 / (1 shl z) - 180.0
                            (if (lon < 11.0) west else east).complete(Unit)
                            withTimeout(5_000) {
                                west.await()
                                east.await()
                            }
                            flat(config.tileSize, 100)
                        }
                    },
            )
        apiTest(deps = deps) { client ->
            coroutineScope {
                val answers =
                    listOf("lat=45.1&lon=6.2", "lat=47.1&lon=16.2").map { q ->
                        async { client.get("/api/v1/elevation/point?$q&source=${ElevationSource.MAPTERHORN.id}") }
                    }
                for (response in answers.awaitAll()) {
                    val body = response.bodyAsText()
                    assertEquals(HttpStatusCode.OK, response.status, body)
                    assertEquals(100.0, ApiJson.decodeFromString(ElevationPointResponse.serializer(), body).elevation, 1.0)
                }
            }
        }
    }

    @Test
    fun `concurrent reads of one provider match a sequential read and fetch each tile once`() {
        fun gateway(fetches: ConcurrentHashMap<String, AtomicInteger>) =
            ElevationGateway { config: ElevationProviderConfig ->
                { url: String ->
                    fetches.computeIfAbsent(url) { AtomicInteger() }.incrementAndGet()
                    delay(20) // widen the window in which two readers want the same tile
                    val (_, x, y) = xyz(url)
                    flat(config.tileSize, 100 + (x % 7) * 10 + y % 5)
                }
            }
        // ~20 tiles at Mapterhorn's zoom, well under its cache, read in 16 different orders.
        val points = (0 until 400).map { LatLon(45.0 + (it / 20) * 0.015, 6.0 + (it % 20) * 0.015) }
        val source = ElevationSource.MAPTERHORN

        val sequential = runBlocking { gateway(ConcurrentHashMap()).provider(source, null).setElevations(points) }

        val fetches = ConcurrentHashMap<String, AtomicInteger>()
        val provider = gateway(fetches).provider(source, null)
        val concurrent =
            runBlocking(Dispatchers.Default) {
                (0 until 16)
                    .map { k ->
                        async {
                            val order = points.indices.map { (it * 7 + k * 25) % points.size }
                            order.zip(provider.setElevations(order.map { points[it] }))
                        }
                    }.awaitAll()
            }
        assertTrue(fetches.size > 10, "the grid spans ${fetches.size} tile(s)")
        assertEquals(emptyMap(), fetches.filterValues { it.get() != 1 }.mapValues { it.value.get() }, "tiles fetched more than once")
        for (reader in concurrent) {
            for ((i, read) in reader) assertEquals(sequential[i].elevation, read.elevation, 1e-9, "point $i")
        }
    }
}
