package io.github.glandais.api

import io.github.glandais.api.dto.BikeDto
import io.github.glandais.api.dto.CyclistDto
import io.github.glandais.api.dto.DefaultsDto
import io.github.glandais.api.dto.EnhanceOptionsDto
import io.github.glandais.api.dto.LimitsDto
import io.github.glandais.api.dto.PowerProviderDto
import io.github.glandais.api.http.ApiJson
import io.github.glandais.api.mapping.HttpDefaults
import io.github.glandais.api.mapping.demSource
import io.github.glandais.api.mapping.demZoom
import io.github.glandais.api.mapping.toBike
import io.github.glandais.api.mapping.toCyclist
import io.github.glandais.api.mapping.toEnhanceOptions
import io.github.glandais.api.mapping.toPowerSpec
import io.github.glandais.engine.Bike
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.physics.CyclistPowerSpec
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** What the door applies when nothing is said, and that `GET /defaults` publishes exactly that. */
class HttpDefaultsTest {
    @Test
    fun `an absent or empty options object maps to HttpDefaults`() {
        val absent: EnhanceOptionsDto? = null
        assertEquals(HttpDefaults.enhance, absent.toEnhanceOptions())
        assertEquals(HttpDefaults.enhance, EnhanceOptionsDto().toEnhanceOptions())
        assertEquals(HttpDefaults.elevationSource, absent.demSource())
        assertNull(EnhanceOptionsDto().demZoom())
    }

    @Test
    fun `HttpDefaults differs from the engine default on exactly the three JS and WASI fields`() {
        val engine = EnhanceOptions.DEFAULT
        assertEquals(
            engine.copy(
                fixElevation = false,
                computeOnePointPerSecond = false,
                simplifyPath = engine.simplifyPath.copy(enabled = false),
            ),
            HttpDefaults.enhance,
        )
        // …and each of the three really is a difference, not a restatement of the engine's value.
        assertNotEquals(engine.fixElevation, HttpDefaults.enhance.fixElevation)
        assertNotEquals(engine.computeOnePointPerSecond, HttpDefaults.enhance.computeOnePointPerSecond)
        assertNotEquals(engine.simplifyPath.enabled, HttpDefaults.enhance.simplifyPath.enabled)
    }

    @Test
    fun `absent rider, bike and power map to the engine's own defaults`() {
        val cyclist: CyclistDto? = null
        val bike: BikeDto? = null
        val power: PowerProviderDto? = null
        assertEquals(Cyclist() to null, cyclist.toCyclist())
        assertEquals(Cyclist() to null, CyclistDto().toCyclist())
        assertEquals(Bike(), bike.toBike())
        assertEquals(Bike(), BikeDto().toBike())
        assertEquals(CyclistPowerSpec(), power.toPowerSpec())
        assertEquals(CyclistPowerSpec(), PowerProviderDto().toPowerSpec())
    }

    @Test
    fun `the published defaults, read back, are the applied ones`() {
        val config = testConfig()
        apiTest(config = config) { client ->
            val response = client.get("/api/v1/defaults")
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status, body)
            val published = ApiJson.decodeFromString(DefaultsDto.serializer(), body)

            // Non-null first: a null member would map to the defaults by itself and prove nothing.
            val enhance = assertNotNull(published.enhance)
            val cyclist = assertNotNull(published.cyclist)
            val bike = assertNotNull(published.bike)
            val power = assertNotNull(published.power)
            assertEquals(HttpDefaults.enhance, enhance.toEnhanceOptions())
            assertEquals(HttpDefaults.elevationSource, enhance.demSource())
            assertNull(enhance.demZoom(), "each DEM source keeps its own default zoom")
            assertEquals(HttpDefaults.cyclist to null, cyclist.toCyclist())
            assertEquals(HttpDefaults.bike, bike.toBike())
            assertEquals(HttpDefaults.power, power.toPowerSpec())
            assertEquals(
                LimitsDto(
                    maxBodyBytes = config.maxBodyBytes,
                    maxTrackPoints = config.maxTrackPoints,
                    maxElevationPoints = config.maxElevationPoints,
                    requestTimeoutMs = config.requestTimeoutMs,
                    computeConcurrency = config.computeConcurrency,
                ),
                published.limits,
            )
            // Every field is published: a null would mean "absent", not a value.
            val nulls =
                ApiJson
                    .parseToJsonElement(body)
                    .let { it as kotlinx.serialization.json.JsonObject }
                    .filterKeys { it != "limits" }
                    .flatMap { (group, obj) ->
                        (obj as kotlinx.serialization.json.JsonObject)
                            .filterValues { it is kotlinx.serialization.json.JsonNull }
                            .keys
                            .map { "$group.$it" }
                    }.toSet()
            assertEquals(setOf("enhance.demZoom", "cyclist.roadCondition"), nulls)
        }
    }
}
