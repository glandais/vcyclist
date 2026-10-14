package io.github.glandais.api.routes

import io.github.glandais.api.apiServices
import io.github.glandais.api.dto.TerrainMapRequest
import io.github.glandais.api.dto.TileMapRequest
import io.github.glandais.api.http.ProblemKind
import io.github.glandais.api.http.receiveGpxUpload
import io.github.glandais.api.mapping.toTerrainMapPlan
import io.github.glandais.api.mapping.toTileMapPlan
import io.github.glandais.api.openapi.gpxQueryParameters
import io.github.glandais.api.openapi.gpxRequestBody
import io.github.glandais.api.openapi.problemResponses
import io.github.glandais.api.service.RenderedMap
import io.github.glandais.api.service.checkTerrainFrame
import io.github.glandais.api.service.checkTileFrame
import io.github.glandais.api.service.renderTerrainMap
import io.github.glandais.api.service.renderTileMap
import io.github.glandais.api.service.requireTileUrl
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.JsonType
import io.ktor.openapi.Responses
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post

private const val TAG = "map"

/**
 * Set on a `/map/tiles` answer when some background tiles could not be obtained:
 * `<missing>/<total>`. The map is still served — those squares are grey — as the CLI's export
 * still writes it, with a warning.
 */
const val MISSING_TILES_HEADER: String = "X-Vcyclist-Missing-Tiles"

/** Answers [map] as an inline PNG named [filename], with the missing-tiles header when due. */
private suspend fun ApplicationCall.respondMap(
    map: RenderedMap,
    filename: String,
) {
    if (map.missingTiles > 0) response.header(MISSING_TILES_HEADER, "${map.missingTiles}/${map.tileCount}")
    response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Inline.withParameter(ContentDisposition.Parameters.FileName, filename).toString(),
    )
    respondBytes(map.png, ContentType.Image.PNG)
}

/** The PNG success response of a map operation. */
private fun Responses.Builder.pngResponse(withMissingTiles: Boolean) {
    HttpStatusCode.OK {
        description = "The map, a PNG"
        headers {
            header(HttpHeaders.ContentDisposition) {
                description = "`inline; filename=\"<name>.png\"`"
                schema = JsonSchema(type = JsonType.STRING)
            }
            if (withMissingTiles) {
                header(MISSING_TILES_HEADER) {
                    description =
                        "`<missing>/<total>`: background tiles the tile server did not give, painted grey. " +
                        "Absent when every tile was drawn."
                    schema = JsonSchema(type = JsonType.STRING)
                }
            }
        }
        ContentType.Image.PNG {
            schema = JsonSchema(type = JsonType.STRING, format = "binary")
        }
    }
}

/**
 * `POST /map/tiles` and `POST /map/terrain` — static PNG maps of the uploaded tracks, rendered by
 * `:map`. Options are checked before the parse; a framing whose size depends on the tracks is
 * checked after it and before any render; the render runs under the compute gate.
 */
fun Route.mapRoutes() {
    post("map/tiles") {
        val services = call.apiServices
        // No tile source, no tile map — whatever the body says.
        services.config.requireTileUrl()
        val upload = call.receiveGpxUpload(TileMapRequest.serializer(), services.config)
        val plan = upload.request.toTileMapPlan(services.config.maxMapPixels)
        val input = upload.parse(services.computeGate, services.config)
        checkTileFrame(plan, input.paths, services.config)
        val map =
            services.computeGate.run {
                renderTileMap(services.config, services.deps.tileFetcher, input.paths, plan)
            }
        call.respondMap(map, "map.png")
    }.describe {
        operationId = "renderTileMap"
        summary = "Render the tracks over map tiles"
        description =
            "Draws the selected paths over raster tiles of **this server's** tile source " +
            "(`VCYCLIST_API_MAP_TILE_URL`); a request never names one. Without it the operation is 503 " +
            "`map-tiles-unconfigured`. Framing: at most one of `maxSize`, `width`+`height` or `zoom`; none " +
            "frames by the published `maxSize` (`GET /api/v1/defaults` `map`). Any dimension is bounded by " +
            "`limits.maxMapPixels`. A tile the source does not give is painted grey and counted in " +
            "`$MISSING_TILES_HEADER`, never fatal."
        tag(TAG)
        parameters { gpxQueryParameters() }
        gpxRequestBody(jsonSchema<TileMapRequest>(), "The framing and track colours.")
        responses {
            pngResponse(withMissingTiles = true)
            problemResponses(*GPX_INPUT_PROBLEMS, ProblemKind.MAP_TILES_UNCONFIGURED)
        }
    }

    post("map/terrain") {
        val services = call.apiServices
        val upload = call.receiveGpxUpload(TerrainMapRequest.serializer(), services.config)
        val plan = upload.request.toTerrainMapPlan(services.config.maxMapPixels)
        val input = upload.parse(services.computeGate, services.config)
        checkTerrainFrame(plan, input.paths, services.config)
        val dem = services.deps.elevation.provider(plan.source, plan.zoom)
        val map = services.computeGate.run { renderTerrainMap(services.config, dem, input.paths, plan) }
        call.respondMap(map, "terrain.png")
    }.describe {
        operationId = "renderTerrainMap"
        summary = "Render the tracks over the terrain"
        description =
            "A hypsometric map: the terrain coloured by altitude from the DEM (cyan, yellow, magenta over the " +
            "image's own range; grey where the DEM has no data), the paths on top coloured by their own " +
            "altitude (blue, green, red). Framed by `maxSize` only. A frame covering more DEM tiles than " +
            "`limits.maxMapDemTiles` at the requested `zoomLevel` is 400 `invalid-option`. The image carries " +
            "DEM data: display the attribution of its source (`GET /api/v1/elevation/sources`)."
        tag(TAG)
        parameters { gpxQueryParameters() }
        gpxRequestBody(jsonSchema<TerrainMapRequest>(), "The framing and the DEM source.")
        responses {
            pngResponse(withMissingTiles = false)
            problemResponses(*GPX_INPUT_PROBLEMS, ProblemKind.DEM_UNAVAILABLE, ProblemKind.DEM_RATE_LIMITED)
        }
    }
}
