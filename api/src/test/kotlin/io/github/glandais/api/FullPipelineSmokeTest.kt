package io.github.glandais.api

import io.github.glandais.api.dto.EnhanceResponse
import io.github.glandais.api.http.ApiJson
import io.github.glandais.api.http.MediaTypes
import io.github.glandais.api.mapping.HttpDefaults
import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.gpx.GpxFixtures
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.tracksAsPaths
import io.github.glandais.engine.physics.AeroProviderConstant
import io.github.glandais.engine.physics.RhoProviderEstimate
import io.github.glandais.engine.physics.WindProviderNone
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole chain — HTTP, multipart-free raw upload, parsing, mapping, physics wiring, pipeline,
 * summary — against [Enhancer.enhanceCourse] called directly with the door's defaults. Within the
 * pipeline-metric tolerance of CLAUDE.md (0.5 % relative).
 */
class FullPipelineSmokeTest {
    @Test
    fun `the API rides SAMPLE_GPX like a direct engine call with HttpDefaults`() =
        apiTest { client ->
            val response =
                client.post("/api/v1/enhance") {
                    contentType(MediaTypes.Gpx)
                    setBody(GpxFixtures.SAMPLE_GPX)
                }
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status, body)
            val viaApi =
                ApiJson
                    .decodeFromString(EnhanceResponse.serializer(), body)
                    .tracks
                    .single()
                    .summary

            val path = GpxParser.parse(GpxFixtures.SAMPLE_GPX).tracksAsPaths().single()
            val direct =
                Enhancer.enhanceCourse(
                    CoursePhysics(
                        course = Course(path = path, cyclist = HttpDefaults.cyclist, bike = HttpDefaults.bike),
                        rhoProvider = RhoProviderEstimate,
                        aeroProvider = AeroProviderConstant,
                        windProvider = WindProviderNone,
                        cyclistPowerProvider = HttpDefaults.power.toProvider(),
                    ),
                    HttpDefaults.enhance,
                    null,
                )

            assertEquals(direct.size, viaApi.size)
            assertWithinHalfPercent(direct.totalDistance, viaApi.totalDistance, "totalDistance")
            assertWithinHalfPercent(direct.durationMs, viaApi.durationMs!!, "durationMs")
        }

    private fun assertWithinHalfPercent(
        expected: Double,
        actual: Double,
        what: String,
    ) {
        assertTrue(expected > 0.0, "$what: the direct call gave $expected")
        assertTrue(abs(actual - expected) / expected <= 0.005, "$what: API $actual vs direct $expected")
    }
}
