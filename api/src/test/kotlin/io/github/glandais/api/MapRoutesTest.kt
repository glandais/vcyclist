package io.github.glandais.api

import io.github.glandais.api.dto.DefaultsDto
import io.github.glandais.api.dto.MapDefaultsDto
import io.github.glandais.api.http.ApiJson
import io.github.glandais.api.mapping.HttpDefaults
import io.github.glandais.api.routes.MISSING_TILES_HEADER
import io.github.glandais.engine.gpx.GpxFixtures
import io.github.glandais.map.SrtmMapProducer
import io.github.glandais.map.TileFetcher
import io.github.glandais.map.TileMapProducer
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `POST /map/tiles` and `POST /map/terrain`, offline: fake tiles, flat fake DEM. */
class MapRoutesTest {
    /** A solid white 256×256 PNG, the background tile of every test. */
    private val whiteTile: ByteArray =
        ByteArrayOutputStream().use { out ->
            val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, 256, 256)
            g.dispose()
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }

    private val fetched = AtomicInteger()
    private val allTiles = TileFetcher { fetched.incrementAndGet().let { whiteTile } }

    /** Runs [block] with a fresh scratch directory, deleted afterwards. */
    private fun withCacheDir(block: (File) -> Unit) {
        val dir = createTempDirectory("vcyclist-map-test").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun mapConfig(
        cacheDir: File,
        tileUrl: String? = "https://tiles.test/{z}/{x}/{y}.png",
        maxMapPixels: Int = ApiConfig().maxMapPixels,
    ) = testConfig(cacheDir = cacheDir, mapTileUrlTemplate = tileUrl, maxMapPixels = maxMapPixels)

    private suspend fun png(response: HttpResponse): BufferedImage {
        if (response.status != HttpStatusCode.OK) {
            kotlin.test.fail("${response.status}: ${response.bodyAsText()}")
        }
        assertEquals(ContentType.Image.PNG, response.contentType()?.withoutParameters())
        val disposition = ContentDisposition.parse(assertNotNull(response.headers[HttpHeaders.ContentDisposition]))
        assertEquals(ContentDisposition.Inline.disposition, disposition.disposition)
        return assertNotNull(ImageIO.read(ByteArrayInputStream(response.readRawBytes())), "not a decodable image")
    }

    /** Files left under [cacheDir] outside the tile cache: a render must leave none. */
    private fun leftovers(cacheDir: File): List<File> =
        cacheDir
            .walkTopDown()
            .filter { it.isFile && !it.startsWith(File(cacheDir, "tiles")) }
            .toList()

    @Test
    fun `a tile map is a PNG within the requested maxSize, and leaves no temporary file`() =
        withCacheDir { dir ->
            apiTest(config = mapConfig(dir), deps = offlineDependencies(allTiles)) { client ->
                val response = client.postGpx("/api/v1/map/tiles", GpxFixtures.SAMPLE_GPX, """{"maxSize":300}""")
                val image = png(response)
                assertTrue(image.width <= 300 && image.height <= 300, "${image.width}x${image.height}")
                assertTrue(image.width > 0 && image.height > 0)
                assertEquals("map.png", ContentDisposition.parse(response.headers[HttpHeaders.ContentDisposition]!!).parameter("filename"))
                assertNull(response.headers[MISSING_TILES_HEADER], "every tile was given")
                assertTrue(fetched.get() > 0, "the fake tile server was never asked")
                assertEquals(emptyList(), leftovers(dir))
                assertTrue(File(dir, "tiles").walkTopDown().any { it.isFile }, "tiles are cached under cacheDir/tiles")
            }
        }

    @Test
    fun `no framing frames by the published maxSize, width and height give exactly that size`() =
        withCacheDir { dir ->
            apiTest(config = mapConfig(dir), deps = offlineDependencies(allTiles)) { client ->
                val default = png(client.postGpx("/api/v1/map/tiles", GpxFixtures.SAMPLE_GPX))
                assertTrue(default.width <= HttpDefaults.MAP_MAX_SIZE && default.height <= HttpDefaults.MAP_MAX_SIZE)
                val sized = png(client.postGpx("/api/v1/map/tiles", GpxFixtures.SAMPLE_GPX, """{"width":320,"height":200}"""))
                assertEquals(320 to 200, sized.width to sized.height)
            }
        }

    @Test
    fun `the track is drawn in the requested colour`() =
        withCacheDir { dir ->
            apiTest(config = mapConfig(dir), deps = offlineDependencies(allTiles)) { client ->
                val image = png(client.postGpx("/api/v1/map/tiles", GpxFixtures.SAMPLE_GPX, """{"colors":["#00ff00"]}"""))
                var greenish = 0
                for (y in 0 until image.height) {
                    for (x in 0 until image.width) {
                        val c = Color(image.getRGB(x, y))
                        if (c.green > 200 && c.red < 150 && c.blue < 150) greenish++
                    }
                }
                assertTrue(greenish > 0, "no green track pixel over a white background")
            }
        }

    @Test
    fun `without a tile source the operation is 503 and computes nothing`() =
        withCacheDir { dir ->
            apiTestWithApp(config = mapConfig(dir, tileUrl = null)) { app, client ->
                val before = app.gateAdmitted()
                assertProblem(
                    client.postGpx("/api/v1/map/tiles", GpxFixtures.SAMPLE_GPX),
                    HttpStatusCode.ServiceUnavailable,
                    "map-tiles-unconfigured",
                )
                assertEquals(before, app.gateAdmitted())
            }
        }

    @Test
    fun `two framing modes, a lone width, a bad colour, a negative margin are 400 before any computation`() =
        withCacheDir { dir ->
            apiTestWithApp(config = mapConfig(dir), deps = offlineDependencies(allTiles)) { app, client ->
                val before = app.gateAdmitted()

                suspend fun pointers(request: String): List<String> =
                    assertProblem(
                        client.postGpx("/api/v1/map/tiles", GpxFixtures.SAMPLE_GPX, request),
                        HttpStatusCode.BadRequest,
                        "invalid-option",
                    ).errors!!.map { it.pointer }
                assertEquals(listOf("/width", "/zoom"), pointers("""{"zoom":14,"width":300,"height":200}"""))
                assertEquals(listOf("/height"), pointers("""{"width":300}"""))
                assertEquals(listOf("/colors/1"), pointers("""{"colors":["#ff0000","red"]}"""))
                assertEquals(listOf("/colors"), pointers("""{"colors":[]}"""))
                assertEquals(listOf("/margin"), pointers("""{"margin":-0.1}"""))
                assertEquals(listOf("/maxSize"), pointers("""{"maxSize":0}"""))
                assertEquals(listOf("/maxSize"), pointers("""{"maxSize":${ApiConfig().maxMapPixels + 1}}"""))
                assertEquals(listOf("/zoom"), pointers("""{"zoom":23}"""))
                assertEquals(before, app.gateAdmitted())
                assertProblem(
                    client.postGpx("/api/v1/map/tiles", GpxFixtures.SAMPLE_GPX, """{"bogus":1}"""),
                    HttpStatusCode.BadRequest,
                    "unknown-key",
                ).also { assertEquals(listOf("/bogus"), it.unknownKeys) }
            }
        }

    @Test
    fun `a zoom framing larger than maxMapPixels is refused after the parse, before the render`() =
        withCacheDir { dir ->
            apiTest(config = mapConfig(dir, maxMapPixels = 256), deps = offlineDependencies(allTiles)) { client ->
                val problem =
                    assertProblem(
                        client.postGpx("/api/v1/map/tiles", GpxFixtures.SAMPLE_GPX, """{"zoom":20}"""),
                        HttpStatusCode.BadRequest,
                        "invalid-option",
                    )
                assertEquals(listOf("/zoom"), problem.errors!!.map { it.pointer })
                assertEquals(0, fetched.get(), "no tile is fetched for a refused frame")
            }
        }

    @Test
    fun `missing tiles are painted, counted and reported in a header`() =
        withCacheDir { dir ->
            val calls = AtomicInteger()
            // Every other tile is missing.
            val flaky = TileFetcher { if (calls.incrementAndGet() % 2 == 0) null else whiteTile }
            apiTest(config = mapConfig(dir), deps = offlineDependencies(flaky)) { client ->
                val response = client.postGpx("/api/v1/map/tiles", GpxFixtures.SAMPLE_GPX, """{"width":1024,"height":1024}""")
                png(response)
                val header = assertNotNull(response.headers[MISSING_TILES_HEADER], "missing tiles must be reported")
                val (missing, total) = header.split('/').map(String::toInt)
                assertTrue(missing in 1..total, header)
                assertEquals(calls.get(), total, header)
            }
        }

    @Test
    fun `a terrain map over the flat fake DEM is a PNG within maxSize`() =
        withCacheDir { dir ->
            apiTest(config = mapConfig(dir, tileUrl = null)) { client ->
                val response = client.postGpx("/api/v1/map/terrain", GpxFixtures.SAMPLE_GPX, """{"maxSize":200}""")
                val image = png(response)
                assertTrue(image.width <= 200 && image.height <= 200, "${image.width}x${image.height}")
                assertEquals(
                    "terrain.png",
                    ContentDisposition.parse(response.headers[HttpHeaders.ContentDisposition]!!).parameter("filename"),
                )
                // A flat DEM is painted the middle of the ramp (yellow), never the no-data grey.
                val corner = Color(image.getRGB(0, 0))
                assertTrue(corner != SrtmMapProducer.NO_DATA_COLOR, "corner $corner is no-data")
                assertEquals(emptyList(), leftovers(dir))
            }
        }

    @Test
    fun `a terrain frame covering too many DEM tiles is refused, and options are checked`() =
        withCacheDir { dir ->
            apiTestWithApp(config = mapConfig(dir)) { app, client ->
                val fetchedBefore = FlatTerrariumTiles.fetched.get()
                val wide =
                    assertProblem(
                        client.postGpx("/api/v1/map/terrain", GpxFixtures.SAMPLE_GPX, """{"margin":1000,"zoomLevel":15}"""),
                        HttpStatusCode.BadRequest,
                        "invalid-option",
                    )
                assertEquals(listOf("/zoomLevel"), wide.errors!!.map { it.pointer })
                assertEquals(fetchedBefore, FlatTerrariumTiles.fetched.get(), "no DEM tile read for a refused frame")
                val before = app.gateAdmitted()

                suspend fun pointers(request: String): List<String> =
                    assertProblem(
                        client.postGpx("/api/v1/map/terrain", GpxFixtures.SAMPLE_GPX, request),
                        HttpStatusCode.BadRequest,
                        "invalid-option",
                    ).errors!!.map { it.pointer }
                assertEquals(listOf("/source"), pointers("""{"source":"srtm"}"""))
                assertEquals(listOf("/zoomLevel"), pointers("""{"zoomLevel":16}"""))
                assertEquals(listOf("/maxSize"), pointers("""{"maxSize":-1}"""))
                assertEquals(listOf("/margin"), pointers("""{"margin":-1}"""))
                assertEquals(before, app.gateAdmitted())
                assertProblem(
                    client.postGpx("/api/v1/map/terrain", GpxFixtures.SAMPLE_GPX, """{"zoom":12}"""),
                    HttpStatusCode.BadRequest,
                    "unknown-key",
                )
            }
        }

    @Test
    fun `defaults publish the map margin and maxSize, read from the map module`() =
        apiTest { client ->
            // The two renderers restate the margin separately; the door publishes one.
            assertEquals(TileMapProducer.DEFAULT_MARGIN, SrtmMapProducer.DEFAULT_MARGIN, 1e-12)
            val body = client.get("/api/v1/defaults").bodyAsText()
            val published = ApiJson.decodeFromString(DefaultsDto.serializer(), body)
            assertEquals(MapDefaultsDto(SrtmMapProducer.DEFAULT_MARGIN, SrtmMapProducer.DEFAULT_MAX_SIZE), published.map)
        }
}
