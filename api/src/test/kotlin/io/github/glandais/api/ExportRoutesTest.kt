package io.github.glandais.api

import io.github.glandais.api.http.MediaTypes
import io.github.glandais.api.mapping.HttpDefaults
import io.github.glandais.engine.gpx.GpxDocument
import io.github.glandais.engine.gpx.GpxFixtures
import io.github.glandais.engine.gpx.GpxParser
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A GPX whose second point is dated before its first: FIT refuses to encode it. */
private val BACKWARDS_TIME_GPX =
    """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>backwards</name>
    <trkseg>
      <trkpt lat="45.0" lon="6.0"><ele>100</ele><time>2024-11-03T14:00:10Z</time></trkpt>
      <trkpt lat="45.001" lon="6.001"><ele>110</ele><time>2024-11-03T14:00:00Z</time></trkpt>
    </trkseg>
  </trk>
</gpx>
"""

class ExportRoutesTest {
    private val url = "/api/v1/export"

    /** Asserts a 200 of [type] with an attachment named [filename], and returns the body text. */
    private suspend fun file(
        response: HttpResponse,
        type: ContentType,
        filename: String? = null,
    ): String {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertTrue(response.contentType()?.match(type) == true, "expected $type, was ${response.contentType()}")
        val disposition = ContentDisposition.parse(assertNotNull(response.headers[HttpHeaders.ContentDisposition]))
        assertEquals(ContentDisposition.Attachment.disposition, disposition.disposition)
        if (filename != null) assertEquals(filename, disposition.parameter(ContentDisposition.Parameters.FileName))
        return body
    }

    private suspend fun gpxOf(response: HttpResponse): GpxDocument = GpxParser.parse(file(response, MediaTypes.Gpx))

    /** A client with no content negotiation: it sends no `Accept` unless told to. */
    private fun io.ktor.server.testing.ApplicationTestBuilder.plain(): HttpClient = createClient {}

    @Test
    fun `GPX is the default, and keeps the input's names and waypoints`() =
        apiTest {
            val doc = gpxOf(plain().postGpx(url, GpxFixtures.WAYPOINTS_GPX))
            assertEquals("with waypoints", doc.name)
            assertEquals(listOf("ride"), doc.tracks.map { it.name })
            assertEquals(3, doc.waypoints.size)
            assertEquals("Col du Sommet", doc.waypoints[1].name)
            assertEquals(
                3,
                doc.tracks
                    .single()
                    .points.size,
            )
        }

    @Test
    fun `includeWaypoints=false drops them`() =
        apiTest {
            val doc =
                gpxOf(plain().postGpx(url, GpxFixtures.WAYPOINTS_GPX, """{"output":{"gpx":{"includeWaypoints":false}}}"""))
            assertEquals(emptyList(), doc.waypoints)
        }

    @Test
    fun `trackName numbers several tracks, trackNames must name each one`() =
        apiTest {
            val numbered = gpxOf(plain().postGpx(url, GpxFixtures.MULTI_TRACK_GPX, """{"output":{"gpx":{"trackName":"x"}}}"""))
            assertEquals(listOf("x", "x-2"), numbered.tracks.map { it.name })

            val named =
                gpxOf(plain().postGpx(url, GpxFixtures.MULTI_TRACK_GPX, """{"output":{"gpx":{"trackNames":["a","b"]}}}"""))
            assertEquals(listOf("a", "b"), named.tracks.map { it.name })

            val mismatch = plain().postGpx(url, GpxFixtures.MULTI_TRACK_GPX, """{"output":{"gpx":{"trackNames":["a"]}}}""")
            val problem = assertProblem(mismatch, HttpStatusCode.UnprocessableEntity, "track-names-mismatch")
            assertEquals(listOf("/output/gpx/trackNames"), problem.errors?.map { it.pointer })

            val both = plain().postGpx(url, GpxFixtures.SAMPLE_GPX, """{"output":{"gpx":{"trackName":"a","trackNames":["b"]}}}""")
            assertProblem(both, HttpStatusCode.BadRequest, "invalid-option")
        }

    @Test
    fun `an unknown key inside output_gpx is listed with its pointer`() =
        apiTest {
            val response = plain().postGpx(url, GpxFixtures.SAMPLE_GPX, """{"output":{"gpx":{"bogus":1,"trackName":"a"}}}""")
            val problem = assertProblem(response, HttpStatusCode.BadRequest, "unknown-key")
            assertEquals(listOf("/output/gpx/bogus"), problem.unknownKeys)
        }

    @Test
    fun `an unknown powerSource is invalid-option, and the wire name is case-insensitive`() =
        apiTest {
            val bad = plain().postGpx(url, GpxFixtures.SAMPLE_GPX, """{"output":{"gpx":{"powerSource":"pedals"}}}""")
            val problem = assertProblem(bad, HttpStatusCode.BadRequest, "invalid-option")
            assertEquals(listOf("/output/gpx/powerSource"), problem.errors?.map { it.pointer })
            val upper = plain().postGpx(url, GpxFixtures.SAMPLE_GPX, """{"output":{"gpx":{"powerSource":"INPUT"}}}""")
            gpxOf(upper)
        }

    @Test
    fun `format negotiation - query, Accept, wildcard, 406`() =
        apiTest {
            // Accept alone picks the format.
            val columns = plain().postGpx(url, GpxFixtures.SAMPLE_GPX) { header(HttpHeaders.Accept, MediaTypes.Columns.toString()) }
            val json = ApiJsonText.parse(file(columns, ContentType.Application.Json, "L__tape_du_Tour_2025.json"))
            assertTrue("fields" in json)

            val csv = plain().postGpx(url, GpxFixtures.SAMPLE_GPX) { header(HttpHeaders.Accept, "text/csv") }
            file(csv, ContentType.Text.CSV)

            // A wildcard is the default.
            gpxOf(plain().postGpx(url, GpxFixtures.SAMPLE_GPX) { header(HttpHeaders.Accept, "*/*") })

            // The query wins over Accept.
            val queried = plain().postGpx("$url?format=gpx", GpxFixtures.SAMPLE_GPX) { header(HttpHeaders.Accept, "text/csv") }
            gpxOf(queried)

            // Nothing servable.
            val png = plain().postGpx(url, GpxFixtures.SAMPLE_GPX) { header(HttpHeaders.Accept, "image/png") }
            assertProblem(png, HttpStatusCode.NotAcceptable, "not-acceptable")
            // application/json is the summary, which /export does not serve.
            val summaryAccept = plain().postGpx(url, GpxFixtures.SAMPLE_GPX) { header(HttpHeaders.Accept, "application/json") }
            assertProblem(summaryAccept, HttpStatusCode.NotAcceptable, "not-acceptable")

            // summary is not an export format; an unknown one is not a format.
            assertProblem(plain().postGpx("$url?format=summary", GpxFixtures.SAMPLE_GPX), HttpStatusCode.BadRequest, "invalid-option")
            assertProblem(plain().postGpx("$url?format=kml", GpxFixtures.SAMPLE_GPX), HttpStatusCode.BadRequest, "invalid-option")
        }

    @Test
    fun `CSV and column JSON carry one path - multiple-tracks unless trackIndex picks one`() =
        apiTest {
            for (format in listOf("csv", "json")) {
                val many = plain().postGpx("$url?format=$format", GpxFixtures.MULTI_TRACK_GPX)
                val problem = assertProblem(many, HttpStatusCode.UnprocessableEntity, "multiple-tracks")
                assertTrue(problem.detail!!.contains("trackIndex"), problem.detail)
            }
            val second = plain().postGpx("$url?format=csv&trackIndex=1", GpxFixtures.MULTI_TRACK_GPX)
            val rows = file(second, ContentType.Text.CSV, "second.csv").lines()
            assertEquals(1 + 2, rows.size, "a header and the second track's two points")
        }

    @Test
    fun `CSV fields, separator and units are honoured`() =
        apiTest {
            val response =
                plain().postGpx(
                    "$url?format=csv",
                    GpxFixtures.SAMPLE_GPX,
                    """{"output":{"csv":{"fields":["distance","elevation"],"separator":";","unitsInHeader":false,"decimals":1}}}""",
                )
            val rows = file(response, ContentType.Text.CSV).lines()
            assertEquals("distance;elevation", rows.first())
            assertEquals("0.0;350.1", rows[1])
        }

    @Test
    fun `invalid CSV and JSON options are 400 with their pointers`() =
        apiTest {
            suspend fun problemOf(
                format: String,
                request: String,
            ) = assertProblem(
                plain().postGpx("$url?format=$format", GpxFixtures.SAMPLE_GPX, request),
                HttpStatusCode.BadRequest,
                "invalid-option",
            )

            assertEquals(
                listOf("/output/csv/separator"),
                problemOf("csv", """{"output":{"csv":{"separator":";;"}}}""").errors?.map { it.pointer },
            )
            assertEquals(
                listOf("/output/csv/fields/1", "/output/csv/fields/2"),
                problemOf("csv", """{"output":{"csv":{"fields":["distance","bogus","nope"]}}}""").errors?.map { it.pointer },
            )
            assertEquals(listOf("/output/csv/fields"), problemOf("csv", """{"output":{"csv":{"fields":[]}}}""").errors?.map { it.pointer })
            assertEquals(
                listOf("/output/csv/lineSeparator"),
                problemOf("csv", """{"output":{"csv":{"lineSeparator":""}}}""").errors?.map {
                    it.pointer
                },
            )
            assertEquals(
                listOf("/output/json/decimals"),
                problemOf("json", """{"output":{"json":{"decimals":99}}}""").errors?.map { it.pointer },
            )
            assertEquals(
                listOf("/output/csv/decimals"),
                problemOf("csv", """{"output":{"csv":{"decimals":-1}}}""").errors?.map { it.pointer },
            )
            // A member of another format: accepted and unused, so refused.
            assertEquals(listOf("/output/csv"), problemOf("gpx", """{"output":{"csv":{"separator":";"}}}""").errors?.map { it.pointer })
        }

    @Test
    fun `column JSON honours fields and includeMeta`() =
        apiTest {
            val response =
                plain().postGpx(
                    "$url?format=json",
                    GpxFixtures.SAMPLE_GPX,
                    """{"output":{"json":{"includeMeta":false,"fields":["elevation"]}}}""",
                )
            val json = ApiJsonText.parse(file(response, ContentType.Application.Json))
            assertTrue("meta" !in json, "includeMeta=false")
            assertEquals(setOf("elevation"), json.getValue("fields").jsonObject.keys)
            val withMeta =
                ApiJsonText.parse(
                    file(plain().postGpx("$url?format=json", GpxFixtures.SAMPLE_GPX), ContentType.Application.Json),
                )
            assertTrue("meta" in withMeta)
        }

    @Test
    fun `FIT needs a start time, and refuses time going backwards`() =
        apiTest {
            val missing = plain().postGpx("$url?format=fit", GpxFixtures.SAMPLE_GPX)
            assertProblem(missing, HttpStatusCode.UnprocessableEntity, "missing-start-time")

            val ok =
                plain().postGpx(
                    "$url?format=fit",
                    GpxFixtures.SAMPLE_GPX,
                    """{"output":{"fit":{"startTimeEpochMs":1730643922000,"name":"course"}}}""",
                )
            assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
            assertTrue(ok.contentType()?.match(MediaTypes.Fit) == true)
            val bytes = ok.bodyAsBytes()
            assertEquals(".FIT", bytes.copyOfRange(8, 12).decodeToString())
            assertEquals(
                "course.fit",
                ContentDisposition.parse(ok.headers[HttpHeaders.ContentDisposition]!!).parameter(ContentDisposition.Parameters.FileName),
            )

            val gap =
                plain().postGpx(
                    "$url?format=fit",
                    GpxFixtures.SAMPLE_GPX,
                    """{"output":{"fit":{"startTimeEpochMs":0,"interPathGapMs":-1}}}""",
                )
            assertProblem(gap, HttpStatusCode.BadRequest, "invalid-option")

            val backwards = plain().postGpx("$url?format=fit", BACKWARDS_TIME_GPX, """{"output":{"fit":{"startTimeEpochMs":0}}}""")
            assertProblem(backwards, HttpStatusCode.UnprocessableEntity, "not-encodable")
        }

    @Test
    fun `the request part is the ExportRequest, nothing else`() =
        apiTest {
            val response = plain().postGpx(url, GpxFixtures.SAMPLE_GPX, """{"options":{}}""")
            val problem = assertProblem(response, HttpStatusCode.BadRequest, "unknown-key")
            assertEquals(listOf("/options"), problem.unknownKeys)
            // The track name of /export is the input's, not the door's.
            assertEquals(listOf("L'étape du Tour 2025"), gpxOf(plain().postGpx(url, GpxFixtures.SAMPLE_GPX)).tracks.map { it.name })
            assertTrue(HttpDefaults.TRACK_NAME != "L'étape du Tour 2025")
        }
}

/** Parses a JSON object body. */
internal object ApiJsonText {
    fun parse(text: String): JsonObject =
        io.github.glandais.api.http.ApiJson
            .parseToJsonElement(text) as JsonObject
}
