package io.github.glandais.api

import io.github.glandais.api.dto.ElevationPointResponse
import io.github.glandais.api.dto.ElevationPointsResponse
import io.github.glandais.api.dto.ElevationSourceDto
import io.github.glandais.api.http.ApiJson
import io.github.glandais.api.service.ElevationGateway
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.ElevationSource
import io.github.glandais.elevation.TileFetchException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The DEM routes, against [FlatTerrariumTiles]: every point is at 100 m, nothing reaches the network. */
class ElevationRoutesTest {
    private suspend fun <T> decoded(
        response: HttpResponse,
        serializer: KSerializer<T>,
    ): T {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        return ApiJson.decodeFromString(serializer, body)
    }

    private suspend fun HttpClient.postJson(
        url: String,
        json: String,
    ): HttpResponse =
        post(url) {
            contentType(ContentType.Application.Json)
            setBody(json)
        }

    private fun points(n: Int): String = (0 until n).joinToString(",", "[", "]") { """{"latitude":${45.0 + it * 0.001},"longitude":6.0}""" }

    @Test
    fun `the sources are the library's, with their fallback and attribution`() =
        apiTest { client ->
            val sources = decoded(client.get("/api/v1/elevation/sources"), ListSerializer(ElevationSourceDto.serializer()))
            assertEquals(ElevationSource.ids, sources.map { it.id })
            val lidar = sources.single { it.id == ElevationSource.IGN_LIDAR_HD.id }
            assertEquals(ElevationSource.MAPTERHORN.id, lidar.fallback)
            assertEquals(ElevationSource.IGN_LIDAR_HD.defaultZoomLevel, lidar.defaultZoomLevel)
            assertEquals(2, lidar.attribution.size)
            val problem = assertProblem(client.get("/api/v1/elevation/sources?foo=1"), HttpStatusCode.BadRequest, "unknown-key")
            assertEquals(listOf("?foo"), problem.unknownKeys)
        }

    @Test
    fun `one point reads the flat DEM, with its attribution`() =
        apiTest { client ->
            val answer = decoded(client.get("/api/v1/elevation/point?lat=45.1&lon=6.2"), ElevationPointResponse.serializer())
            assertEquals(FlatTerrariumTiles.ELEVATION_M, answer.elevation, 1.0)
            assertEquals(ElevationSource.DEFAULT.id, answer.source)
            assertEquals(ElevationSource.DEFAULT.defaultZoomLevel, answer.zoomLevel)
            assertTrue(answer.attribution.isNotEmpty())
            assertEquals(
                ElevationSource.DEFAULT
                    .config()
                    .attribution.text,
                answer.attribution.first().text,
            )
        }

    @Test
    fun `a LiDAR HD answer carries the Mapterhorn fallback's attribution too`() =
        apiTest { client ->
            val answer =
                decoded(
                    client.get("/api/v1/elevation/point?lat=45.1&lon=6.2&source=ign-lidar-hd&zoomLevel=14&interpolation=false"),
                    ElevationPointResponse.serializer(),
                )
            assertEquals(FlatTerrariumTiles.ELEVATION_M, answer.elevation, 1.0)
            assertEquals(14, answer.zoomLevel)
            assertEquals(
                listOf(
                    ElevationSource.IGN_LIDAR_HD
                        .config(14)
                        .attribution.text,
                    ElevationSource.MAPTERHORN
                        .config()
                        .attribution.text,
                ),
                answer.attribution.map { it.text },
            )
        }

    @Test
    fun `point query parameters are checked`() =
        apiTest { client ->
            assertProblem(client.get("/api/v1/elevation/point?lat=45"), HttpStatusCode.BadRequest, "invalid-option")
            assertProblem(client.get("/api/v1/elevation/point?lat=x&lon=6"), HttpStatusCode.BadRequest, "invalid-option")
            val polar = assertProblem(client.get("/api/v1/elevation/point?lat=89&lon=6"), HttpStatusCode.BadRequest, "invalid-option")
            assertEquals(listOf("?lat"), polar.errors?.map { it.pointer })
            assertProblem(client.get("/api/v1/elevation/point?lat=45&lon=181"), HttpStatusCode.BadRequest, "invalid-option")
            assertProblem(client.get("/api/v1/elevation/point?lat=45&lon=6&source=srtm"), HttpStatusCode.BadRequest, "invalid-option")
            assertProblem(client.get("/api/v1/elevation/point?lat=45&lon=6&zoomLevel=16"), HttpStatusCode.BadRequest, "invalid-option")
            val unknown = assertProblem(client.get("/api/v1/elevation/point?lat=45&lon=6&zoom=3"), HttpStatusCode.BadRequest, "unknown-key")
            assertEquals(listOf("?zoom"), unknown.unknownKeys)
        }

    @Test
    fun `several points read the DEM in order`() =
        apiTest { client ->
            val answer =
                decoded(
                    client.postJson("/api/v1/elevation/points", """{"points":${points(5)},"source":"mapterhorn","zoomLevel":10}"""),
                    ElevationPointsResponse.serializer(),
                )
            assertEquals(5, answer.points.size)
            assertEquals(10, answer.zoomLevel)
            assertEquals(45.003, answer.points[3].latitude, 1e-12)
            answer.points.forEach { assertEquals(FlatTerrariumTiles.ELEVATION_M, it.elevation, 1.0) }
            assertTrue(answer.attribution.isNotEmpty())
        }

    @Test
    fun `more points than the limit is 413`() =
        apiTest(config = testConfig(maxElevationPoints = 3)) { client ->
            assertProblem(
                client.postJson("/api/v1/elevation/points", """{"points":${points(4)}}"""),
                HttpStatusCode.PayloadTooLarge,
                "too-many-points",
            )
            // Along: the densified polyline is bounded too, not only the given points.
            assertProblem(
                client.postJson("/api/v1/elevation/along", """{"points":${points(2)},"step":10}"""),
                HttpStatusCode.PayloadTooLarge,
                "too-many-points",
            )
        }

    @Test
    fun `a bad point is named by its pointer`() =
        apiTest { client ->
            val problem =
                assertProblem(
                    client.postJson(
                        "/api/v1/elevation/points",
                        """{"points":[{"latitude":45,"longitude":6},{"latitude":45,"longitude":200}]}""",
                    ),
                    HttpStatusCode.BadRequest,
                    "invalid-option",
                )
            assertEquals(listOf("/points/1/longitude"), problem.errors?.map { it.pointer })
        }

    @Test
    fun `along densifies, smooths and returns the flat profile`() =
        apiTest { client ->
            val answer =
                decoded(
                    client.postJson(
                        "/api/v1/elevation/along",
                        """{"points":${points(2)},"step":20,"smoothingOptions":{"enabled":true},"filterOptions":{"enabled":false}}""",
                    ),
                    ElevationPointsResponse.serializer(),
                )
            // 111 m at a 20 m step: the two ends and the points between them.
            assertTrue(answer.points.size > 2, "not densified: ${answer.points.size}")
            answer.points.forEach { assertEquals(FlatTerrariumTiles.ELEVATION_M, it.elevation, 1.0) }
        }

    @Test
    fun `along needs two points and a step above one metre`() =
        apiTest { client ->
            assertProblem(
                client.postJson("/api/v1/elevation/along", """{"points":${points(1)}}"""),
                HttpStatusCode.BadRequest,
                "invalid-option",
            )
            assertProblem(
                client.postJson("/api/v1/elevation/along", """{"points":${points(2)},"step":0.5}"""),
                HttpStatusCode.BadRequest,
                "invalid-option",
            )
        }

    @Test
    fun `no tile server can be named - SSRF keys are unknown keys`() =
        apiTest { client ->
            val problem =
                assertProblem(
                    client.postJson(
                        "/api/v1/elevation/along",
                        """{"points":${points(2)},"tileUrlTemplate":"http://169.254.169.254/{z}/{x}/{y}","tileSize":256,"cacheSize":9}""",
                    ),
                    HttpStatusCode.BadRequest,
                    "unknown-key",
                )
            assertEquals(listOf("/tileUrlTemplate", "/tileSize", "/cacheSize"), problem.unknownKeys)
            assertProblem(
                client.postJson("/api/v1/elevation/points", """{"points":${points(1)},"tileUrlTemplate":"http://x"}"""),
                HttpStatusCode.BadRequest,
                "unknown-key",
            )
            assertProblem(
                client.get("/api/v1/elevation/point?lat=45&lon=6&tileUrlTemplate=http://x"),
                HttpStatusCode.BadRequest,
                "unknown-key",
            )
        }

    @Test
    fun `an unknown source is invalid-option`() =
        apiTest { client ->
            val problem =
                assertProblem(
                    client.postJson("/api/v1/elevation/points", """{"points":${points(1)},"source":"srtm"}"""),
                    HttpStatusCode.BadRequest,
                    "invalid-option",
                )
            assertEquals(listOf("/source"), problem.errors?.map { it.pointer })
        }

    @Test
    fun `a JSON body is required`() =
        apiTest { client ->
            val response =
                client.post("/api/v1/elevation/points") {
                    contentType(ContentType.Text.Plain)
                    setBody("""{"points":[]}""")
                }
            assertProblem(response, HttpStatusCode.UnsupportedMediaType, "unsupported-media-type")
        }

    @Test
    fun `a rate-limiting tile server is 503 with Retry-After, a failing one 502`() {
        fun failing(retryAfter: Long?): ApiDependencies =
            ApiDependencies(
                elevation =
                    ElevationGateway { _: ElevationProviderConfig ->
                        { url: String -> throw TileFetchException(url, if (retryAfter == null) 500 else 429, retryAfter) }
                    },
            )
        apiTest(deps = failing(11)) { client ->
            val response = client.get("/api/v1/elevation/point?lat=45&lon=6")
            assertProblem(response, HttpStatusCode.ServiceUnavailable, "dem-rate-limited")
            assertEquals("11", response.headers[HttpHeaders.RetryAfter])
        }
        apiTest(deps = failing(null)) { client ->
            assertProblem(
                client.postJson("/api/v1/elevation/points", """{"points":${points(2)}}"""),
                HttpStatusCode.BadGateway,
                "dem-unavailable",
            )
        }
    }
}
