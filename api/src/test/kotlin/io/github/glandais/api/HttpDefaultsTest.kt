package io.github.glandais.api

import io.github.glandais.api.dto.BikeDto
import io.github.glandais.api.dto.ClimbOptionsDto
import io.github.glandais.api.dto.CsvOptionsDto
import io.github.glandais.api.dto.CyclistDto
import io.github.glandais.api.dto.DefaultsDto
import io.github.glandais.api.dto.EnhanceOptionsDto
import io.github.glandais.api.dto.FilterOptionsDto
import io.github.glandais.api.dto.FitOptionsDto
import io.github.glandais.api.dto.GpxWriteOptionsDto
import io.github.glandais.api.dto.JsonOptionsDto
import io.github.glandais.api.dto.LimitsDto
import io.github.glandais.api.dto.PowerProviderDto
import io.github.glandais.api.dto.SmoothingOptionsDto
import io.github.glandais.api.http.ApiJson
import io.github.glandais.api.mapping.HttpDefaults
import io.github.glandais.api.mapping.demSource
import io.github.glandais.api.mapping.demZoom
import io.github.glandais.api.mapping.elevationSource
import io.github.glandais.api.mapping.toBike
import io.github.glandais.api.mapping.toClimbOptions
import io.github.glandais.api.mapping.toCsvOptions
import io.github.glandais.api.mapping.toCyclist
import io.github.glandais.api.mapping.toEnhanceOptions
import io.github.glandais.api.mapping.toFilterOptions
import io.github.glandais.api.mapping.toFit
import io.github.glandais.api.mapping.toGpxWrite
import io.github.glandais.api.mapping.toJsonOptions
import io.github.glandais.api.mapping.toPowerSpec
import io.github.glandais.api.mapping.toSmoothingOptions
import io.github.glandais.elevation.ElevationDefaults
import io.github.glandais.elevation.FilterOptions
import io.github.glandais.elevation.SmoothingOptions
import io.github.glandais.engine.Bike
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.climb.ClimbOptions
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
    fun `absent writer settings map to HttpDefaults`() {
        val csv: CsvOptionsDto? = null
        val json: JsonOptionsDto? = null
        assertEquals(HttpDefaults.csv, csv.toCsvOptions())
        assertEquals(HttpDefaults.csv, CsvOptionsDto().toCsvOptions())
        assertEquals(HttpDefaults.json, json.toJsonOptions())
        assertEquals(HttpDefaults.json, JsonOptionsDto().toJsonOptions())
        val gpx: GpxWriteOptionsDto? = null
        val settings = gpx.toGpxWrite()
        assertEquals(HttpDefaults.gpxPowerSource, settings.powerSource)
        assertNull(settings.trackName, "the route picks the name")
        assertNull(settings.startTime)
    }

    @Test
    fun `absent climb and DEM settings map to the engine's own defaults`() {
        val climb: ClimbOptionsDto? = null
        assertEquals(HttpDefaults.climb, climb.toClimbOptions())
        assertEquals(ClimbOptions.DEFAULT, ClimbOptionsDto().toClimbOptions())
        val smoothing: SmoothingOptionsDto? = null
        val filter: FilterOptionsDto? = null
        assertEquals(SmoothingOptions(), smoothing.toSmoothingOptions())
        assertEquals(SmoothingOptions(), SmoothingOptionsDto().toSmoothingOptions())
        assertEquals(FilterOptions(), filter.toFilterOptions())
        assertEquals(FilterOptions(), FilterOptionsDto().toFilterOptions())
        assertEquals(HttpDefaults.elevationSource, elevationSource(null, "/source"))
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
            assertEquals(HttpDefaults.csv, assertNotNull(published.csv).toCsvOptions())
            assertEquals(HttpDefaults.json, assertNotNull(published.json).toJsonOptions())
            val gpx = assertNotNull(published.gpx).toGpxWrite()
            assertEquals(HttpDefaults.GPX_WRITE_EXTENSIONS, gpx.writeExtensions)
            assertEquals(HttpDefaults.gpxPowerSource, gpx.powerSource)
            assertEquals(HttpDefaults.GPX_INCLUDE_WAYPOINTS, gpx.includeWaypoints)
            assertEquals(HttpDefaults.TRACK_NAME, gpx.trackName)
            assertEquals(GpxWriteOptionsDto(trackName = HttpDefaults.TRACK_NAME).toGpxWrite(), gpx)
            // The FIT defaults with the one value that has none, the start time.
            val fit = assertNotNull(published.fit).copy(startTimeEpochMs = 0L).toFit()
            assertEquals(FitOptionsDto(startTimeEpochMs = 0L).toFit(), fit)
            assertEquals(HttpDefaults.TRACK_NAME, fit.name)
            assertEquals(HttpDefaults.FIT_INTER_PATH_GAP_MS, fit.interPathGap.inWholeMilliseconds)
            assertEquals(HttpDefaults.climb, assertNotNull(published.climb).toClimbOptions())
            val elevation = published.elevation
            assertEquals(HttpDefaults.elevationSource, elevationSource(elevation.source, "/source"))
            assertEquals(ElevationDefaults.STEP_M, elevation.step)
            assertEquals(ElevationDefaults.MIN_DISTANCE_M, elevation.minDistance)
            assertEquals(ElevationDefaults.INTERPOLATION, elevation.interpolation)
            assertEquals(SmoothingOptions(), assertNotNull(elevation.smoothingOptions).toSmoothingOptions())
            assertEquals(FilterOptions(), assertNotNull(elevation.filterOptions).toFilterOptions())
            assertEquals(
                LimitsDto(
                    maxBodyBytes = config.maxBodyBytes,
                    maxTrackPoints = config.maxTrackPoints,
                    maxElevationPoints = config.maxElevationPoints,
                    requestTimeoutMs = config.requestTimeoutMs,
                    computeConcurrency = config.computeConcurrency,
                    maxMapPixels = config.maxMapPixels,
                    maxMapDemTiles = config.maxMapDemTiles,
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
            assertEquals(
                setOf(
                    "enhance.demZoom",
                    "cyclist.roadCondition",
                    // `null` is the engine's own default for these: every field, the shortest exact form…
                    "csv.decimals",
                    "csv.fields",
                    "json.decimals",
                    "json.fields",
                    // …relative times, the single trackName…
                    "gpx.startTimeEpochMs",
                    "gpx.trackNames",
                    // …and the one value with no default: FIT requires it.
                    "fit.startTimeEpochMs",
                ),
                nulls,
            )
        }
    }
}
