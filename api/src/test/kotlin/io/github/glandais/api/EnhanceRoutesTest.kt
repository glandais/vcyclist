package io.github.glandais.api

import io.github.glandais.api.dto.EnhanceResponse
import io.github.glandais.api.http.ApiJson
import io.github.glandais.api.http.MediaTypes
import io.github.glandais.api.mapping.HttpDefaults
import io.github.glandais.api.service.ElevationGateway
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.TileFetchException
import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.RoadCondition
import io.github.glandais.engine.applyTo
import io.github.glandais.engine.gpx.GpxFixtures
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.tracksAsPaths
import io.github.glandais.engine.physics.AeroProviderConstant
import io.github.glandais.engine.physics.CyclistPowerSpec
import io.github.glandais.engine.physics.RhoProviderEstimate
import io.github.glandais.engine.physics.WindProviderNone
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnhanceRoutesTest {
    private suspend fun HttpClient.enhance(
        request: String? = null,
        query: String = "",
        gpx: String = GpxFixtures.SAMPLE_GPX,
        configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = postGpx("/api/v1/enhance$query", gpx, request, configure)

    private suspend fun enhanced(response: HttpResponse): EnhanceResponse {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertTrue(isJson(response), "expected JSON, was ${response.contentType()}")
        return ApiJson.decodeFromString(EnhanceResponse.serializer(), body)
    }

    /** The compute gate's admission count of the running application. */
    private fun Application.admitted(): Long = attributes[ApiServicesKey].computeGate.admitted

    /** Runs [block] with the application captured, so a test can read its compute gate. */
    private fun withApp(
        deps: ApiDependencies = offlineDependencies(),
        config: ApiConfig = testConfig(),
        block: suspend (Application, HttpClient) -> Unit,
    ) {
        lateinit var app: Application
        apiTest(config = config, deps = deps, extraRoutes = { app = this }) { client ->
            startApplication()
            block(app, client)
        }
    }

    /** The engine called directly on SAMPLE_GPX with the door's default physics and [cyclist]. */
    private suspend fun direct(cyclist: Cyclist = HttpDefaults.cyclist): io.github.glandais.engine.path.Path {
        val path = GpxParser.parse(GpxFixtures.SAMPLE_GPX).tracksAsPaths().single()
        val course =
            CoursePhysics(
                course = Course(path = path, cyclist = cyclist, bike = HttpDefaults.bike),
                rhoProvider = RhoProviderEstimate,
                aeroProvider = AeroProviderConstant,
                windProvider = WindProviderNone,
                cyclistPowerProvider = CyclistPowerSpec().toProvider(),
            )
        return Enhancer.enhanceCourse(course, HttpDefaults.enhance, null)
    }

    @Test
    fun `a raw GPX is enhanced with every default`() =
        apiTest { client ->
            val result = enhanced(client.enhance())
            val track = result.tracks.single()
            assertEquals("L'étape du Tour 2025", track.name)
            assertTrue(track.summary.totalDistance > 0.0)
            assertTrue((track.summary.durationMs ?: 0.0) > 0.0, "the simulation gave the ride a duration")
            assertNotNull(track.summary.elevationGainFiltered, "the elevation-gain pass ran")
            assertNull(track.points)
            assertEquals(emptyList(), result.warnings)
        }

    @Test
    fun `an unknown option key is a 400 listing it, and nothing is computed`() =
        withApp { app, client ->
            val before = app.admitted()
            val problem =
                assertProblem(
                    client.enhance("""{"options":{"simplifyTolerance":5,"virtualizeTrack":true},"bogus":1}"""),
                    HttpStatusCode.BadRequest,
                    "unknown-key",
                )
            assertEquals(setOf("/options/simplifyTolerance", "/bogus"), problem.unknownKeys?.toSet())
            assertEquals(before, app.admitted(), "the compute gate was taken")
        }

    @Test
    fun `a quoted number or boolean is invalid-body, before any computation`() =
        withApp { app, client ->
            val before = app.admitted()
            for (request in listOf(
                """{"options":{"simplifyEnabled":"true"}}""",
                """{"power":{"power":"150"}}""",
            )) {
                assertProblem(client.enhance(request), HttpStatusCode.BadRequest, "invalid-body")
            }
            assertEquals(before, app.admitted(), "the compute gate was taken")
        }

    @Test
    fun `an unknown enum value is invalid-option, before any computation`() =
        withApp { app, client ->
            val before = app.admitted()
            val problem =
                assertProblem(
                    client.enhance("""{"options":{"racingLineCorridor":"bogus"}}"""),
                    HttpStatusCode.BadRequest,
                    "invalid-option",
                )
            assertEquals(listOf("/options/racingLineCorridor"), problem.errors?.map { it.pointer })
            for (request in listOf(
                """{"power":{"type":"bogus"}}""",
                """{"cyclist":{"roadCondition":"icy"}}""",
                """{"options":{"elevationGainPreset":"bogus"}}""",
                """{"options":{"demSource":"MAPTERHORN"}}""",
                """{"options":{"demZoom":16}}""",
                """{"roadWidthOverrideM":0}""",
                // A `require` in the init of an engine options class.
                """{"options":{"elevationSmoothWindowM":-1}}""",
                """{"options":{"wPrimeBalanceWPrime":0}}""",
            )) {
                assertProblem(client.enhance(request), HttpStatusCode.BadRequest, "invalid-option")
            }
            assertEquals(before, app.admitted(), "the compute gate was taken")
        }

    @Test
    fun `a road-condition preset wins over explicit grip, with a warning`() =
        apiTest { client ->
            val result = enhanced(client.enhance("""{"cyclist":{"roadCondition":"WET","maxLeanAngleDeg":42}}"""))
            val warning = result.warnings.single()
            assertTrue("/cyclist/maxLeanAngleDeg" in warning, warning)

            val expected = direct(RoadCondition.WET.applyTo(Cyclist(maxLeanAngleDeg = 42.0)))
            val summary = result.tracks.single().summary
            assertEquals(expected.size, summary.size)
            assertClose(expected.totalDistance, summary.totalDistance, "totalDistance")
            assertClose(expected.durationMs, summary.durationMs!!, "durationMs")

            val dry = enhanced(client.enhance("""{"cyclist":{"maxLeanAngleDeg":42}}"""))
            assertEquals(emptyList(), dry.warnings, "no preset, no warning")
        }

    @Test
    fun `stateful power providers are built per request`() =
        apiTest { client ->
            val request = """{"power":{"type":"critical-power","power":400,"criticalPower":250,"wPrime":1000}}"""
            val (a, b) =
                coroutineScope {
                    val first = async { enhanced(client.enhance(request)) }
                    val second = async { enhanced(client.enhance(request)) }
                    first.await() to second.await()
                }
            val third = enhanced(client.enhance(request))
            assertEquals(a, b)
            assertEquals(a, third)
            val constant = enhanced(client.enhance("""{"power":{"power":400}}"""))
            // The model reached the engine: W' (1 kJ) is spent within seconds of a 43 s ride, then the
            // rider settles at CP, so it does not ride like a constant 400 W one. A provider shared
            // between requests would start the second with W' already spent: a and b would differ.
            assertNotEquals(constant.tracks.single().summary, a.tracks.single().summary)
        }

    @Test
    fun `fixElevation reads the DEM through the gateway`() =
        apiTest { client ->
            val before = FlatTerrariumTiles.fetched.get()
            val result = enhanced(client.enhance("""{"options":{"fixElevation":true}}""", query = "?points=true"))
            assertTrue(FlatTerrariumTiles.fetched.get() > before, "no tile was fetched")
            val points = result.tracks.single().points!!
            assertTrue(points.isNotEmpty())
            for (p in points) {
                assertEquals(FlatTerrariumTiles.ELEVATION_M, p.elevation!!, 1.0)
            }
        }

    @Test
    fun `without fixElevation both corrections report not-run and warn nothing`() =
        apiTest { client ->
            val result = enhanced(client.enhance("{}"))
            val corrections = result.tracks.single().elevationCorrections
            assertEquals("not-run", corrections.bridgeDecks)
            assertEquals("not-run", corrections.tunnels)
            assertEquals(emptyList(), corrections.warnings)
            assertEquals(emptyList(), result.warnings)
        }

    /** IGN's terrain served flat, its surface model (the MNS layer) unreachable. */
    private fun unreachableSurfaceModel(surfaceFetches: java.util.concurrent.atomic.AtomicInteger): ApiDependencies =
        ApiDependencies(
            elevation =
                ElevationGateway { config: ElevationProviderConfig ->
                    if ("MNS" in config.tileUrlTemplate) {
                        { url: String ->
                            surfaceFetches.incrementAndGet()
                            throw TileFetchException(url, 500, null)
                        }
                    } else {
                        FlatTerrariumTiles.fetcherFor(config)
                    }
                },
        )

    @Test
    fun `a failed surface-model read is reported on the track and relayed as a warning`() {
        val fetches =
            java.util.concurrent.atomic
                .AtomicInteger()
        apiTest(deps = unreachableSurfaceModel(fetches)) { client ->
            val result = enhanced(client.enhance("""{"options":{"fixElevation":true,"demSource":"ign-lidar-hd"}}"""))
            val corrections = result.tracks.single().elevationCorrections
            assertEquals("fetch-failed", corrections.bridgeDecks)
            assertEquals(1, corrections.warnings.size)
            assertTrue(fetches.get() > 0, "the surface model was never asked")
            val relayed = result.warnings.filter { "bridge decks" in it }
            assertEquals(1, relayed.size, result.warnings.toString())
            assertTrue(relayed.single().startsWith("track 1"), relayed.single())
            assertTrue(relayed.single().endsWith(corrections.warnings.single()), "relayed unreworded")
        }
    }

    @Test
    fun `bridgeDecksEnabled false never reads the surface model`() {
        val fetches =
            java.util.concurrent.atomic
                .AtomicInteger()
        apiTest(deps = unreachableSurfaceModel(fetches)) { client ->
            val result =
                enhanced(
                    client.enhance("""{"options":{"fixElevation":true,"demSource":"ign-lidar-hd","bridgeDecksEnabled":false}}"""),
                )
            val corrections = result.tracks.single().elevationCorrections
            assertEquals("not-run", corrections.bridgeDecks)
            assertEquals(0, fetches.get())
            assertEquals(emptyList(), result.warnings)
        }
    }

    @Test
    fun `a failing tile server is 502, a rate-limiting one 503 with Retry-After`() {
        fun failing(retryAfter: Long?): ApiDependencies =
            ApiDependencies(
                elevation =
                    ElevationGateway { _: ElevationProviderConfig ->
                        { url: String -> throw TileFetchException(url, if (retryAfter == null) 500 else 429, retryAfter) }
                    },
            )
        val request = """{"options":{"fixElevation":true}}"""
        apiTest(deps = failing(null)) { client ->
            assertProblem(client.enhance(request), HttpStatusCode.BadGateway, "dem-unavailable")
        }
        apiTest(deps = failing(7)) { client ->
            val response = client.enhance(request)
            assertProblem(response, HttpStatusCode.ServiceUnavailable, "dem-rate-limited")
            assertEquals("7", response.headers[HttpHeaders.RetryAfter])
        }
    }

    @Test
    fun `wind, bike, road width and points reach the result`() =
        apiTest { client ->
            val base = enhanced(client.enhance("{}")).tracks.single()
            // Toward 180° is a wind from the north: whatever the route, a strong one changes the ride.
            val windy = enhanced(client.enhance("""{"wind":{"windSpeed":10}}""")).tracks.single()
            assertTrue(windy.summary.durationMs != base.summary.durationMs, "wind ignored")
            val heavy = enhanced(client.enhance("""{"bike":{"crr":0.02}}""")).tracks.single()
            assertTrue(heavy.summary.durationMs!! > base.summary.durationMs!!, "crr ignored")
            val withPoints = enhanced(client.enhance(null, query = "?points=true")).tracks.single()
            assertEquals(withPoints.summary.size, withPoints.points?.size)
            assertNotNull(withPoints.dominantHeadwindAzimuth)
        }

    @Test
    fun `query and media type are checked like every GPX route`() =
        apiTest { client ->
            assertProblem(client.enhance(query = "?formt=gpx"), HttpStatusCode.BadRequest, "unknown-key")
            assertProblem(client.enhance(query = "?points=maybe"), HttpStatusCode.BadRequest, "invalid-option")
            val json =
                client.post("/api/v1/enhance") {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertProblem(json, HttpStatusCode.UnsupportedMediaType, "unsupported-media-type")
        }

    @Test
    fun `format=gpx writes the virtualized track, its power source as asked`() =
        apiTest { client ->
            val response = client.enhance(query = "?format=gpx")
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status, body)
            assertTrue(response.contentType()?.match(MediaTypes.Gpx) == true, "was ${response.contentType()}")
            assertEquals(
                "${HttpDefaults.TRACK_NAME}.gpx",
                ContentDisposition
                    .parse(
                        response.headers[HttpHeaders.ContentDisposition]!!,
                    ).parameter(ContentDisposition.Parameters.FileName),
            )
            val input = GpxParser.parse(body)
            assertEquals(listOf(HttpDefaults.TRACK_NAME), input.tracks.map { it.name })
            val inputPower =
                input.tracks
                    .single()
                    .points
                    .mapNotNull { it.powerW }

            val computed =
                GpxParser.parse(
                    client.enhance("""{"output":{"gpx":{"powerSource":"computed"}}}""", query = "?format=gpx").bodyAsText(),
                )
            val computedPower =
                computed.tracks
                    .single()
                    .points
                    .mapNotNull { it.powerW }
            assertTrue(computedPower.isNotEmpty(), "powerSource=computed writes the simulated power")
            assertNotEquals(inputPower, computedPower)
        }

    @Test
    fun `Accept negotiates the format when the query does not`() =
        apiTest {
            val plain = createClient {}
            val gpx = plain.enhance { header(HttpHeaders.Accept, MediaTypes.Gpx.toString()) }
            assertEquals(HttpStatusCode.OK, gpx.status, gpx.bodyAsText())
            assertTrue(gpx.contentType()?.match(MediaTypes.Gpx) == true)
            val summary = plain.enhance { header(HttpHeaders.Accept, "application/json") }
            enhanced(summary)
            val any = plain.enhance { header(HttpHeaders.Accept, "*/*") }
            enhanced(any)
            val none = plain.enhance()
            enhanced(none)
            assertProblem(plain.enhance { header(HttpHeaders.Accept, "image/png") }, HttpStatusCode.NotAcceptable, "not-acceptable")
        }

    @Test
    fun `FIT needs a start time, checked before any computation`() =
        withApp { app, client ->
            val before = app.admitted()
            assertProblem(client.enhance(query = "?format=fit"), HttpStatusCode.UnprocessableEntity, "missing-start-time")
            assertEquals(before, app.admitted(), "a missing start time computed nothing")
            val ok = client.enhance("""{"output":{"fit":{"startTimeEpochMs":1730643922000}}}""", query = "?format=fit")
            assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
            assertTrue(ok.contentType()?.match(MediaTypes.Fit) == true)
            assertEquals(".FIT", ok.bodyAsBytes().copyOfRange(8, 12).decodeToString())
        }

    @Test
    fun `CSV honours fields and separator, and carries a single path`() =
        withApp { app, client ->
            val response =
                client.enhance("""{"output":{"csv":{"fields":["distance","speed"],"separator":";"}}}""", query = "?format=csv")
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status, body)
            assertTrue(response.contentType()?.match(ContentType.Text.CSV) == true)
            assertEquals("distance (meters);speed (m/s)", body.lines().first())

            assertProblem(
                client.enhance("""{"output":{"csv":{"separator":";;"}}}""", query = "?format=csv"),
                HttpStatusCode.BadRequest,
                "invalid-option",
            )
            val before = app.admitted()
            val many = client.enhance(query = "?format=csv", gpx = GpxFixtures.MULTI_TRACK_GPX)
            assertProblem(many, HttpStatusCode.UnprocessableEntity, "multiple-tracks")
            assertEquals(before + 1, app.admitted(), "multiple-tracks costs the parse, never the simulation")
            val one = client.enhance(query = "?format=csv&trackIndex=0", gpx = GpxFixtures.MULTI_TRACK_GPX)
            assertEquals(HttpStatusCode.OK, one.status, one.bodyAsText())
        }

    @Test
    fun `column JSON without meta, and output members of another format refused`() =
        apiTest { client ->
            val response = client.enhance("""{"output":{"json":{"includeMeta":false}}}""", query = "?format=json")
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status, body)
            val json = ApiJson.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
            assertTrue("meta" !in json && "fields" in json, body)

            val mismatch = client.enhance("""{"output":{"csv":{"separator":";"}}}""", query = "?format=gpx")
            val problem = assertProblem(mismatch, HttpStatusCode.BadRequest, "invalid-option")
            assertTrue(problem.detail!!.contains("output.csv given but format is gpx"), problem.detail)
            // ?points only means something to the summary.
            assertProblem(client.enhance(query = "?format=gpx&points=true"), HttpStatusCode.BadRequest, "invalid-option")
            assertProblem(client.enhance(query = "?format=kml"), HttpStatusCode.BadRequest, "invalid-option")
        }

    @Test
    fun `a sparse GPX spanning a long distance is too-many-points before any simulation`() =
        withApp(config = testConfig(maxSimulatedPoints = 20_000)) { app, client ->
            // Two input points, one degree of longitude apart: ~78 km, ~42 000 points once densified.
            val before = app.admitted()
            val problem =
                assertProblem(client.enhance(gpx = gpxOf(45.0 to 0.0, 45.0 to 1.0)), HttpStatusCode.PayloadTooLarge, "too-many-points")
            assertTrue(problem.detail!!.contains("20000"), problem.detail)
            assertEquals(before + 1, app.admitted(), "too-many-points costs the parse, never the simulation")
            // A tenth of that fits, and the bound really is an upper bound of what was simulated.
            val fits = enhanced(client.enhance(gpx = gpxOf(45.0 to 0.0, 45.0 to 0.1)))
            val summary = fits.tracks.single().summary
            assertTrue(summary.size <= Enhancer.densifiedPointBound(2, summary.totalDistance), "$summary")
        }

    @Test
    fun `a one-point track has a summary that agrees with its point`() =
        apiTest { client ->
            val response = enhanced(client.enhance(gpx = gpxOf(45.0 to 6.0, elevation = 10.0), query = "?points=true"))
            val track = response.tracks.single()
            val point = track.points!!.single()
            assertEquals(45.0, point.latitudeDeg!!, 1e-9)
            val bounds = assertNotNull(track.summary.bounds)
            assertEquals(45.0, bounds.minLatitude, 1e-9)
            assertEquals(6.0, bounds.maxLongitude, 1e-9)
            assertEquals(10.0, track.summary.minElevation!!, 1e-9)
            assertEquals(10.0, track.summary.maxElevation!!, 1e-9)
        }

    /** A one-track GPX through [points] (latitude, longitude in degrees), every point at [elevation]. */
    private fun gpxOf(
        vararg points: Pair<Double, Double>,
        elevation: Double = 100.0,
    ): String =
        """<?xml version="1.0"?><gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">""" +
            "<trk><name>t</name><trkseg>" +
            points.joinToString("") { (lat, lon) -> """<trkpt lat="$lat" lon="$lon"><ele>$elevation</ele></trkpt>""" } +
            "</trkseg></trk></gpx>"

    private fun assertClose(
        expected: Double,
        actual: Double,
        what: String,
    ) {
        assertTrue(abs(expected - actual) <= 1e-9 * abs(expected), "$what: expected $expected, was $actual")
    }
}
