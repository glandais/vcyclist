package io.github.glandais.api

import io.github.glandais.api.dto.ClimbsResponse
import io.github.glandais.api.dto.HeadwindResponse
import io.github.glandais.api.dto.RacingLineResponse
import io.github.glandais.api.http.ApiJson
import io.github.glandais.api.mapping.HttpDefaults
import io.github.glandais.engine.climb.ClimbDetector
import io.github.glandais.engine.climb.ClimbOptions
import io.github.glandais.engine.gpx.GpxFixtures
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.tracksAsPaths
import io.github.glandais.engine.path.dominantHeadwindAzimuthDeg
import io.github.glandais.engine.trajectory.CorridorMode
import io.github.glandais.engine.trajectory.RacingLine
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `POST /analysis/climbs`, `/analysis/racing-line`, `/analysis/headwind`. */
class AnalysisRoutesTest {
    private suspend fun <T> decoded(
        response: HttpResponse,
        serializer: KSerializer<T>,
    ): T {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        return ApiJson.decodeFromString(serializer, body)
    }

    private suspend fun HttpClient.climbs(
        gpx: String = CLIMB_GPX,
        request: String? = null,
    ) = postGpx("/api/v1/analysis/climbs", gpx, request)

    private suspend fun HttpClient.racingLine(
        gpx: String = GpxFixtures.SAMPLE_GPX,
        request: String? = null,
    ) = postGpx("/api/v1/analysis/racing-line", gpx, request)

    @Test
    fun `a steady 3 km climb is found, with a positive grade`() =
        apiTest { client ->
            val result = decoded(client.climbs(), ClimbsResponse.serializer())
            val climbs = result.tracks.single()
            assertTrue(climbs.isNotEmpty(), "no climb on a 5 % ramp")
            val climb = climbs.first()
            assertTrue(climb.averageGrade > 0.0, "averageGrade ${climb.averageGrade}")
            assertTrue(climb.elevationGainM > 100.0, "elevationGainM ${climb.elevationGainM}")
            assertTrue(climb.parts.isNotEmpty())
            // The same answer as the engine called directly.
            val direct = ClimbDetector.detect(GpxParser.parse(CLIMB_GPX).tracksAsPaths().single(), HttpDefaults.climb)
            assertEquals(direct.size, climbs.size)
            assertEquals(direct.first().endIndex, climb.endIndex)
        }

    @Test
    fun `climb options reach the detector`() =
        apiTest { client ->
            // A 10 % floor rejects the 5 % ramp: the option was read.
            val strict = decoded(client.climbs(request = """{"options":{"minGradePercent":10}}"""), ClimbsResponse.serializer())
            assertEquals(emptyList(), strict.tracks.single())
            assertNotEquals(10.0, ClimbOptions.DEFAULT.minGradePercent, "the sentinel must differ from the default")
        }

    @Test
    fun `an out-of-domain climb option is invalid-option, before any computation`() =
        apiTestWithApp { app, client ->
            val before = app.gateAdmitted()
            assertProblem(
                client.climbs(request = """{"options":{"maxAnalysisPoints":1}}"""),
                HttpStatusCode.BadRequest,
                "invalid-option",
            )
            assertEquals(before, app.gateAdmitted(), "the compute gate was taken")
        }

    @Test
    fun `an unknown climb key is listed`() =
        apiTest { client ->
            val problem =
                assertProblem(
                    client.climbs(request = """{"options":{"maxDiffRealGradeRatio":1.5}}"""),
                    HttpStatusCode.BadRequest,
                    "unknown-key",
                )
            assertEquals(listOf("/options/maxDiffRealGradeRatio"), problem.unknownKeys)
        }

    @Test
    fun `the racing line is reported per track, as the engine analyses it`() =
        apiTest { client ->
            val result = decoded(client.racingLine(BEND_GPX), RacingLineResponse.serializer())
            val path = GpxParser.parse(BEND_GPX).tracksAsPaths().single()
            val expected = assertNotNull(RacingLine.analyze(path, HttpDefaults.enhance.racingLine), "BEND_GPX no longer analyses")
            val report = assertNotNull(result.tracks.single())
            assertEquals(expected.size, report.size)
            assertEquals(expected.size, report.lateralOffsetM.size)
            assertEquals(expected.corners.size, report.corners.size)
            assertTrue(report.corners.isNotEmpty(), "a 90° bend of 30 m radius is a corner")
            assertEquals(expected.converged, report.converged)
            assertEquals(expected.newtonIterations, report.newtonIterations)
        }

    @Test
    fun `a track too short to analyse gets null`() =
        apiTest { client ->
            // SAMPLE_GPX has 7 points, below what the racing line projects.
            val result = decoded(client.racingLine(GpxFixtures.SAMPLE_GPX), RacingLineResponse.serializer())
            assertEquals(listOf(null), result.tracks)
        }

    @Test
    fun `racing-line keys reach the analysis`() =
        apiTest { client ->
            // full-road is twice a lane: the one corridor whose width differs from the default's.
            val other =
                if (HttpDefaults.enhance.racingLine.corridor ==
                    CorridorMode.FULL_ROAD
                ) {
                    CorridorMode.LANE
                } else {
                    CorridorMode.FULL_ROAD
                }
            val result =
                decoded(
                    client.racingLine(BEND_GPX, """{"options":{"racingLineCorridor":"${other.id}","racingLineEnabled":true}}"""),
                    RacingLineResponse.serializer(),
                )
            val path = GpxParser.parse(BEND_GPX).tracksAsPaths().single()
            val default = assertNotNull(RacingLine.analyze(path, HttpDefaults.enhance.racingLine))
            val chosen = assertNotNull(RacingLine.analyze(path, HttpDefaults.enhance.racingLine.copy(corridor = other)))
            assertNotEquals(default.maxCorridorWidthM, chosen.maxCorridorWidthM, "the sentinel corridor must change the answer")
            assertEquals(chosen.maxCorridorWidthM, assertNotNull(result.tracks.single()).maxCorridorWidthM)
            val wide =
                decoded(client.racingLine(BEND_GPX, """{"options":{"racingLineRoadWidthM":20}}"""), RacingLineResponse.serializer())
            assertNotEquals(default.maxCorridorWidthM, assertNotNull(wide.tracks.single()).maxCorridorWidthM, "road width ignored")
        }

    @Test
    fun `a key the racing line does not read is refused, before any computation`() =
        apiTestWithApp { app, client ->
            val before = app.gateAdmitted()
            val problem =
                assertProblem(
                    client.racingLine(request = """{"options":{"fixElevation":true,"racingLineRoadWidthM":6}}"""),
                    HttpStatusCode.BadRequest,
                    "invalid-option",
                )
            assertEquals(listOf("/options/fixElevation"), problem.errors?.map { it.pointer })
            assertProblem(
                client.racingLine(request = """{"options":{"racingLineEnabled":false}}"""),
                HttpStatusCode.BadRequest,
                "invalid-option",
            )
            assertEquals(before, app.gateAdmitted(), "the compute gate was taken")
        }

    @Test
    fun `the headwind is null on a track too short, and the engine's azimuth otherwise`() =
        apiTest { client ->
            val short = decoded(client.postGpx("/api/v1/analysis/headwind", SHORT_GPX), HeadwindResponse.serializer())
            assertNull(short.overall)
            assertEquals(listOf<Double?>(null), short.tracks)

            val sample = decoded(client.postGpx("/api/v1/analysis/headwind", GpxFixtures.SAMPLE_GPX), HeadwindResponse.serializer())
            val expected =
                GpxParser
                    .parse(GpxFixtures.SAMPLE_GPX)
                    .tracksAsPaths()
                    .single()
                    .dominantHeadwindAzimuthDeg()
            assertEquals(expected, assertNotNull(sample.overall), 1e-9)
            assertEquals(expected, assertNotNull(sample.tracks.single()), 1e-9)
        }

    @Test
    fun `headwind takes no request part`() =
        apiTest { client ->
            val problem =
                assertProblem(
                    client.postGpx("/api/v1/analysis/headwind", GpxFixtures.SAMPLE_GPX, request = "{}"),
                    HttpStatusCode.BadRequest,
                    "unknown-key",
                )
            assertEquals(listOf("part:request"), problem.unknownKeys)
        }

    companion object {
        /** A straight line due north, 3 km long, rising steadily from 100 m to 250 m (5 %). */
        val CLIMB_GPX: String =
            buildString {
                append("""<?xml version="1.0" encoding="UTF-8"?>""")
                append("""<gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test"><trk><name>ramp</name><trkseg>""")
                val n = 101
                // 3 km of latitude at 111.2 km per degree.
                val spanDeg = 3000.0 / 111_195.0
                for (i in 0 until n) {
                    val f = i.toDouble() / (n - 1)
                    append("""<trkpt lat="${45.0 + f * spanDeg}" lon="6.0"><ele>${100.0 + f * 150.0}</ele></trkpt>""")
                }
                append("</trkseg></trk></gpx>")
            }

        /**
         * 200 m due north, a 90° right-hand bend of 30 m radius, 200 m due east — a point every
         * ~5 m: dense and curved enough for the racing line to analyse.
         */
        val BEND_GPX: String =
            buildString {
                append("""<?xml version="1.0" encoding="UTF-8"?>""")
                append("""<gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test"><trk><name>bend</name><trkseg>""")
                val mPerDegLat = 111_195.0
                val mPerDegLon = mPerDegLat * kotlin.math.cos(Math.toRadians(45.0))
                val xy = mutableListOf<Pair<Double, Double>>()
                for (i in 0..40) xy += 0.0 to i * 5.0
                val r = 30.0
                // Centre of the bend at (r, 200): from heading north to heading east, turning right.
                for (k in 1..9) {
                    val a = Math.PI - k * (Math.PI / 2) / 10
                    xy += (r + r * kotlin.math.cos(a)) to (200.0 + r * kotlin.math.sin(a))
                }
                for (i in 0..40) xy += (r + i * 5.0) to (200.0 + r)
                for ((x, y) in xy) {
                    append("""<trkpt lat="${45.0 + y / mPerDegLat}" lon="${6.0 + x / mPerDegLon}"><ele>100</ele></trkpt>""")
                }
                append("</trkseg></trk></gpx>")
            }

        /** Three points: too few for a dominant direction. */
        val SHORT_GPX: String =
            """<?xml version="1.0" encoding="UTF-8"?>
<gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test"><trk><trkseg>
<trkpt lat="45.0" lon="6.0"><ele>100</ele></trkpt>
<trkpt lat="45.001" lon="6.0"><ele>100</ele></trkpt>
<trkpt lat="45.002" lon="6.0"><ele>100</ele></trkpt>
</trkseg></trk></gpx>"""
    }
}
