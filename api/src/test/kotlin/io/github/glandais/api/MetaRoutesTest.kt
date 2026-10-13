package io.github.glandais.api

import io.github.glandais.api.dto.CatalogDto
import io.github.glandais.api.dto.FieldDefinitionDto
import io.github.glandais.api.dto.HealthDto
import io.github.glandais.api.dto.VersionDto
import io.github.glandais.api.http.OutputFormat
import io.github.glandais.elevation.ElevationSource
import io.github.glandais.engine.RoadCondition
import io.github.glandais.engine.gpx.GpxPowerSource
import io.github.glandais.engine.path.ElevationGainPreset
import io.github.glandais.engine.path.PointField
import io.github.glandais.engine.physics.PowerModel
import io.github.glandais.engine.trajectory.CornerKind
import io.github.glandais.engine.trajectory.CorridorMode
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MetaRoutesTest {
    @Test
    fun `health answers UP`() =
        apiTest { client ->
            val response = client.get("/api/v1/health")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(isJson(response))
            assertEquals("""{"status":"UP"}""", response.bodyAsText())
            assertEquals(HealthDto("UP"), response.body<HealthDto>())
        }

    @Test
    fun `version reports the contract constant and the engine build`() =
        apiTest { client ->
            val version = client.get("/api/v1/version").body<VersionDto>()
            assertEquals(API_VERSION, version.apiVersion)
            assertEquals(engineVersion, version.engineVersion)
            assertTrue(version.engineVersion != "unknown", "the version resource was not generated")
        }

    @Test
    fun `catalog lists the engine catalogues`() =
        apiTest { client ->
            val catalog = client.get("/api/v1/catalog").body<CatalogDto>()
            assertEquals(PowerModel.ids, catalog.powerModels)
            assertEquals(RoadCondition.wireNames, catalog.roadConditions)
            assertEquals(CorridorMode.entries.map { it.id }, catalog.corridorModes)
            assertEquals(ElevationGainPreset.entries.map { it.id }, catalog.elevationGainPresets)
            assertEquals(GpxPowerSource.wireNames, catalog.gpxPowerSources)
            assertEquals(ElevationSource.ids, catalog.elevationSources)
            assertEquals(CornerKind.entries.map { it.name }, catalog.cornerKinds)
            assertEquals(OutputFormat.ids, catalog.outputFormats)
            // Every list resolves back through the engine's own parser.
            catalog.powerModels.forEach { assertNotNull(PowerModel.fromIdOrNull(it), it) }
            catalog.roadConditions.forEach { assertNotNull(RoadCondition.fromWire(it), it) }
            catalog.corridorModes.forEach { CorridorMode.byId(it) }
            catalog.elevationGainPresets.forEach { ElevationGainPreset.byId(it) }
            catalog.gpxPowerSources.forEach { assertNotNull(GpxPowerSource.fromWire(it), it) }
            catalog.elevationSources.forEach { ElevationSource.byId(it) }
        }

    @Test
    fun `fields lists every PointField in ordinal order`() =
        apiTest { client ->
            val fields = client.get("/api/v1/fields").body<List<FieldDefinitionDto>>()
            assertEquals(PointField.COUNT, fields.size)
            assertEquals(PointField.entries.map { it.prop }, fields.map { it.prop })
            assertEquals(PointField.entries.indices.toList(), fields.map { it.index })
        }

    @Test
    fun `an unknown query parameter is a 400 unknown-key problem`() =
        apiTest { client ->
            for (path in listOf("health", "version", "catalog", "fields", "defaults")) {
                val problem = assertProblem(client.get("/api/v1/$path?foo=1&bar=2"), HttpStatusCode.BadRequest, "unknown-key")
                assertEquals(listOf("?bar", "?foo"), problem.unknownKeys, path)
                assertEquals("/api/v1/$path", problem.instance)
            }
        }

    @Test
    fun `a request id is echoed back`() =
        apiTest { client ->
            val response = client.get("/api/v1/health") { header(HttpHeaders.XRequestId, "abc-123") }
            assertEquals("abc-123", response.headers[HttpHeaders.XRequestId])
            assertNotNull(client.get("/api/v1/health").headers[HttpHeaders.XRequestId])
        }
}
