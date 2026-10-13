package io.github.glandais.api

import io.github.glandais.api.dto.ParsedGpxDto
import io.github.glandais.api.http.ApiJson
import io.github.glandais.api.http.MediaTypes
import io.github.glandais.engine.gpx.GpxFixtures
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpxRoutesTest {
    private suspend fun HttpClient.raw(
        gpx: String,
        query: String = "",
        type: ContentType = MediaTypes.Gpx,
    ): HttpResponse =
        post("/api/v1/gpx/parse$query") {
            contentType(type)
            setBody(gpx)
        }

    private suspend fun HttpClient.multipart(
        gpx: String,
        query: String = "",
        extraParts: Map<String, String> = emptyMap(),
    ): HttpResponse =
        submitFormWithBinaryData(
            url = "/api/v1/gpx/parse$query",
            formData =
                formData {
                    append(
                        "gpx",
                        gpx.encodeToByteArray(),
                        Headers.build {
                            append(HttpHeaders.ContentType, MediaTypes.Gpx.toString())
                            append(HttpHeaders.ContentDisposition, "filename=\"in.gpx\"")
                        },
                    )
                    extraParts.forEach { (name, value) -> append(name, value) }
                },
        )

    private suspend fun parsed(response: HttpResponse): ParsedGpxDto {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertTrue(isJson(response), "expected JSON, was ${response.contentType()}")
        return ApiJson.decodeFromString(ParsedGpxDto.serializer(), body)
    }

    @Test
    fun `a raw upload parses into one summarised path`() =
        apiTest { client ->
            val result = parsed(client.raw(GpxFixtures.SAMPLE_GPX))
            assertEquals("sample", result.name)
            assertEquals(1, result.paths.size)
            val path = result.paths.single()
            assertEquals("L'étape du Tour 2025", path.name)
            assertEquals("track", path.kind)
            assertTrue(path.summary.size > 0)
            assertTrue(path.summary.totalDistance > 0.0)
            assertNotNull(path.summary.bounds)
            assertNull(path.summary.elevationGainFiltered, "the pipeline did not run")
            assertNull(path.points, "points only on request")
            assertTrue(result.warnings.isEmpty())
        }

    @Test
    fun `every raw XML media type is accepted`() =
        apiTest { client ->
            for (type in listOf(ContentType.Application.Xml, ContentType.Text.Xml)) {
                assertEquals(1, parsed(client.raw(GpxFixtures.SAMPLE_GPX, type = type)).paths.size)
            }
        }

    @Test
    fun `a multipart upload gives the same answer as a raw one`() =
        apiTest { client ->
            assertEquals(parsed(client.raw(GpxFixtures.SAMPLE_GPX)), parsed(client.multipart(GpxFixtures.SAMPLE_GPX)))
        }

    @Test
    fun `a multipart gpx part may be a plain form field`() =
        apiTest { client ->
            val response =
                client.submitFormWithBinaryData(
                    url = "/api/v1/gpx/parse",
                    formData = formData { append("gpx", GpxFixtures.SAMPLE_GPX) },
                )
            assertEquals(1, parsed(response).paths.size)
        }

    @Test
    fun `several tracks give several paths, trackIndex narrows to one`() =
        apiTest { client ->
            val all = parsed(client.raw(GpxFixtures.MULTI_TRACK_GPX))
            assertEquals(listOf("first", "second"), all.paths.map { it.name })
            assertEquals(listOf(3, 2), all.paths.map { it.summary.size })

            val second = parsed(client.raw(GpxFixtures.MULTI_TRACK_GPX, "?trackIndex=1"))
            assertEquals(listOf("second"), second.paths.map { it.name })
            assertEquals(all.paths[1], second.paths.single())

            val problem =
                assertProblem(
                    client.raw(GpxFixtures.MULTI_TRACK_GPX, "?trackIndex=99"),
                    HttpStatusCode.UnprocessableEntity,
                    "track-index-out-of-range",
                )
            assertEquals("/api/v1/gpx/parse", problem.instance)
            assertProblem(client.raw(GpxFixtures.MULTI_TRACK_GPX, "?trackIndex=-1"), HttpStatusCode.BadRequest, "invalid-option")
            assertProblem(client.raw(GpxFixtures.MULTI_TRACK_GPX, "?trackIndex=x"), HttpStatusCode.BadRequest, "invalid-option")
        }

    @Test
    fun `split=segments gives one path per segment`() =
        apiTest { client ->
            val tracks = parsed(client.raw(GpxFixtures.MULTI_SEGMENT_GPX, "?split=tracks"))
            val segments = parsed(client.raw(GpxFixtures.MULTI_SEGMENT_GPX, "?split=segments"))
            assertEquals(1, tracks.paths.size)
            assertEquals(3, segments.paths.size)
            assertEquals(listOf(3, 2, 4), segments.paths.map { it.summary.size })
            assertTrue(segments.paths.all { it.name == "paused ride" })
            // The teleport between segments counts as distance only when they are concatenated.
            assertTrue(
                tracks.paths
                    .single()
                    .summary.totalDistance > segments.paths.sumOf { it.summary.totalDistance } + 1000.0,
            )
            assertProblem(client.raw(GpxFixtures.MULTI_SEGMENT_GPX, "?split=points"), HttpStatusCode.BadRequest, "invalid-option")
        }

    @Test
    fun `empty tracks and segments are dropped`() =
        apiTest { client ->
            // EMPTY_PARTS_GPX: "with holes" (2 + empty + 3 points) and "ghost" (no point at all).
            val tracks = parsed(client.raw(GpxFixtures.EMPTY_PARTS_GPX))
            assertEquals(listOf("with holes"), tracks.paths.map { it.name })
            assertEquals(
                5,
                tracks.paths
                    .single()
                    .summary.size,
            )

            val segments = parsed(client.raw(GpxFixtures.EMPTY_PARTS_GPX, "?split=segments"))
            assertEquals(listOf(2, 3), segments.paths.map { it.summary.size })
        }

    @Test
    fun `a GPX without any point is empty-gpx`() =
        apiTest { client ->
            val ghost =
                """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>ghost</name><trkseg/></trk></gpx>"""
            assertProblem(client.raw(ghost), HttpStatusCode.UnprocessableEntity, "empty-gpx")
            // A file of tracks only, asked for its routes, is empty too.
            assertProblem(client.raw(GpxFixtures.SAMPLE_GPX, "?kinds=routes"), HttpStatusCode.UnprocessableEntity, "empty-gpx")
            assertEquals(1, parsed(client.raw(GpxFixtures.SAMPLE_GPX, "?kinds=tracks")).paths.size)
        }

    @Test
    fun `waypoints are returned`() =
        apiTest { client ->
            val result = parsed(client.raw(GpxFixtures.WAYPOINTS_GPX))
            assertEquals("with waypoints", result.name)
            assertEquals(3, result.waypoints.size)
            val summit = result.waypoints[1]
            assertEquals("Col du Sommet", summit.name)
            assertEquals("Summit", summit.symbol)
            assertEquals(1200.5, summit.elevationM!!, 1e-12)
            assertNotNull(summit.timeEpochMs)
            assertNull(result.waypoints[0].name)
        }

    @Test
    fun `repair turns a broken file into warnings, or into invalid-gpx when off`() =
        apiTest { client ->
            val repaired = parsed(client.raw(GpxFixtures.BARE_AMPERSAND_GPX, "?repair=true"))
            assertEquals("Café & Croissant", repaired.paths.single().name)
            assertTrue(repaired.warnings.isNotEmpty())
            assertTrue(repaired.warnings.any { "&" in it }, repaired.warnings.toString())

            // Default is on.
            assertEquals(repaired, parsed(client.raw(GpxFixtures.BARE_AMPERSAND_GPX)))

            assertProblem(client.raw(GpxFixtures.BARE_AMPERSAND_GPX, "?repair=false"), HttpStatusCode.BadRequest, "invalid-gpx")
        }

    @Test
    fun `what is not XML is invalid-gpx`() =
        apiTest { client ->
            assertProblem(client.raw(GpxFixtures.NOT_XML_AT_ALL), HttpStatusCode.BadRequest, "invalid-gpx")
            assertProblem(client.multipart(GpxFixtures.NOT_XML_AT_ALL), HttpStatusCode.BadRequest, "invalid-gpx")
        }

    @Test
    fun `an unknown multipart part is unknown-key, all of them listed`() =
        apiTest { client ->
            val problem =
                assertProblem(
                    client.multipart(GpxFixtures.SAMPLE_GPX, extraParts = mapOf("options" to "{}", "zzz" to "1")),
                    HttpStatusCode.BadRequest,
                    "unknown-key",
                )
            assertEquals(listOf("part:options", "part:zzz"), problem.unknownKeys)
        }

    @Test
    fun `a request part is unknown on an operation that takes none`() =
        apiTest { client ->
            val problem =
                assertProblem(
                    client.multipart(GpxFixtures.SAMPLE_GPX, extraParts = mapOf("request" to "{}")),
                    HttpStatusCode.BadRequest,
                    "unknown-key",
                )
            assertEquals(listOf("part:request"), problem.unknownKeys)
        }

    @Test
    fun `a multipart body without a gpx part is invalid-body`() =
        apiTest { client ->
            val response =
                client.submitFormWithBinaryData(url = "/api/v1/gpx/parse", formData = formData {})
            assertProblem(response, HttpStatusCode.BadRequest, "invalid-body")
        }

    @Test
    fun `an unknown query parameter is unknown-key`() =
        apiTest { client ->
            val problem = assertProblem(client.raw(GpxFixtures.SAMPLE_GPX, "?track=1&kinds=all"), HttpStatusCode.BadRequest, "unknown-key")
            assertEquals(listOf("?track"), problem.unknownKeys)
            assertProblem(client.raw(GpxFixtures.SAMPLE_GPX, "?kinds=everything"), HttpStatusCode.BadRequest, "invalid-option")
        }

    @Test
    fun `a body that is not GPX nor multipart is unsupported-media-type`() =
        apiTest { client ->
            assertProblem(
                client.raw(GpxFixtures.SAMPLE_GPX, type = ContentType.Text.Plain),
                HttpStatusCode.UnsupportedMediaType,
                "unsupported-media-type",
            )
            assertProblem(
                client.raw("{}", type = ContentType.Application.Json),
                HttpStatusCode.UnsupportedMediaType,
                "unsupported-media-type",
            )
        }

    @Test
    fun `a body over maxBodyBytes is payload-too-large, raw or multipart`() =
        apiTest(config = testConfig(maxBodyBytes = 512)) { client ->
            assertProblem(client.raw(GpxFixtures.SAMPLE_GPX), HttpStatusCode.PayloadTooLarge, "payload-too-large")
            assertProblem(client.multipart(GpxFixtures.SAMPLE_GPX), HttpStatusCode.PayloadTooLarge, "payload-too-large")
        }

    @Test
    fun `a chunked multipart body over maxBodyBytes is payload-too-large too`() =
        apiTest(config = testConfig(maxBodyBytes = 512)) { client ->
            val boundary = "vcyclist-test-boundary"
            val body =
                "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"gpx\"; filename=\"in.gpx\"\r\n" +
                    "Content-Type: application/gpx+xml\r\n\r\n" +
                    GpxFixtures.SAMPLE_GPX + "\r\n" +
                    "--$boundary--\r\n"
            val response =
                client.post("/api/v1/gpx/parse") {
                    // No Content-Length: the limit must hold on the stream itself.
                    setBody(
                        object : OutgoingContent.WriteChannelContent() {
                            override val contentType = ContentType.MultiPart.FormData.withParameter("boundary", boundary)

                            override suspend fun writeTo(channel: ByteWriteChannel) {
                                channel.writeFully(body.encodeToByteArray())
                            }
                        },
                    )
                }
            assertProblem(response, HttpStatusCode.PayloadTooLarge, "payload-too-large")
        }

    @Test
    fun `more points than maxTrackPoints is too-many-points`() =
        apiTest(config = testConfig(maxTrackPoints = 1)) { client ->
            assertProblem(client.raw(GpxFixtures.SAMPLE_GPX), HttpStatusCode.PayloadTooLarge, "too-many-points")
        }

    @Test
    fun `the limit applies to the selection, not to the file`() =
        apiTest(config = testConfig(maxTrackPoints = 2)) { client ->
            assertProblem(client.raw(GpxFixtures.MULTI_TRACK_GPX), HttpStatusCode.PayloadTooLarge, "too-many-points")
            assertEquals(1, parsed(client.raw(GpxFixtures.MULTI_TRACK_GPX, "?trackIndex=1")).paths.size)
        }

    @Test
    fun `points=true returns every point, in degrees`() =
        apiTest { client ->
            val result = parsed(client.raw(GpxFixtures.SAMPLE_GPX, "?points=true"))
            val path = result.paths.single()
            val points = assertNotNull(path.points)
            assertEquals(path.summary.size, points.size)
            for (p in points) {
                val lat = assertNotNull(p.latitudeDeg)
                val lon = assertNotNull(p.longitudeDeg)
                assertTrue(lat in -90.0..90.0 && lon in -180.0..180.0, "$lat, $lon")
            }
            val bounds = assertNotNull(path.summary.bounds)
            assertEquals(points.minOf { it.latitudeDeg!! }, bounds.minLatitude, 1e-9)
            assertEquals(points.maxOf { it.longitudeDeg!! }, bounds.maxLongitude, 1e-9)
            // Degrees, not radians: the fixture sits in the Alps.
            assertTrue(bounds.minLatitude > 40.0, bounds.toString())
            assertProblem(client.raw(GpxFixtures.SAMPLE_GPX, "?points=yes"), HttpStatusCode.BadRequest, "invalid-option")
        }
}
