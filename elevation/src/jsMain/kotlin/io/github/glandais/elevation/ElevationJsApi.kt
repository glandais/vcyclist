@file:OptIn(
    DelicateCoroutinesApi::class,
    kotlin.js.ExperimentalJsExport::class,
)

package io.github.glandais.elevation

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.JsExport
import kotlin.js.Promise

/** JS-side input shape `{ latitude, longitude }`. */
external interface CoordinatesDto {
    val latitude: Double
    val longitude: Double
}

/** JS-side output shape `{ latitude, longitude, elevation }`. */
external interface CoordinatesElevationDto {
    val latitude: Double
    val longitude: Double
    val elevation: Double
}

/** Mirrors TS `SmoothingOptions`. */
external interface SmoothingOptionsDto {
    val enabled: Boolean
    val windowSize: Double
}

/** Mirrors TS `FilterOptions`. */
external interface FilterOptionsDto {
    val enabled: Boolean
    val tolerance: Double
    val zExaggeration: Double
}

/** Mirrors TS `getElevationsAlong` options bag — every field is optional from JS. */
external interface GetElevationsAlongOptionsDto {
    val step: Double?
    val minDistance: Double?
    val interpolation: Boolean?
    val smoothingOptions: SmoothingOptionsDto?
    val filterOptions: FilterOptionsDto?
}

/** Mirrors a subset of [ElevationProviderConfig] — every field is optional from JS. */
external interface ElevationProviderConfigDto {
    val zoomLevel: Int?
    val cacheSize: Int?
    val tileUrlTemplate: String?
    val tileSize: Int?
}

private fun coordsEle(
    latitude: Double,
    longitude: Double,
    elevation: Double,
): CoordinatesElevationDto {
    val o = js("({})")
    o.latitude = latitude
    o.longitude = longitude
    o.elevation = elevation
    return o.unsafeCast<CoordinatesElevationDto>()
}

// Free-function shape so the demo's JS shim (`new ElevationProvider(config)` etc.) stays simple.
// The ElevationProvider instance is returned directly (no JsReference handle needed: Kotlin/JS
// classes are first-class JS objects and the demo treats them as opaque).

@JsExport
fun newElevationProvider(configDto: ElevationProviderConfigDto?): ElevationProvider {
    val defaults = ElevationProviderConfig()
    val cfg =
        ElevationProviderConfig(
            zoomLevel = configDto?.zoomLevel ?: defaults.zoomLevel,
            cacheSize = configDto?.cacheSize ?: defaults.cacheSize,
            tileUrlTemplate = configDto?.tileUrlTemplate ?: defaults.tileUrlTemplate,
            tileSize = configDto?.tileSize ?: defaults.tileSize,
            attribution = defaults.attribution,
        )
    return ElevationProvider(cfg)
}

@JsExport
fun getElevation(
    provider: ElevationProvider,
    latitude: Double,
    longitude: Double,
    interpolation: Boolean,
): Promise<Double> =
    GlobalScope.promise {
        provider.getElevation(latitude, longitude, interpolation)
    }

@JsExport
fun getElevationsAlong(
    provider: ElevationProvider,
    path: Array<CoordinatesDto>,
    options: GetElevationsAlongOptionsDto?,
): Promise<Array<CoordinatesElevationDto>> =
    GlobalScope.promise {
        val coords = path.map { LatLon(it.latitude, it.longitude) }
        val results =
            provider.getElevationsAlong(
                path = coords,
                step = options?.step ?: 10.0,
                minDistance = options?.minDistance ?: 1.0,
                interpolation = options?.interpolation ?: true,
                smoothingOptions = options?.smoothingOptions?.toKotlin(),
                filterOptions = options?.filterOptions?.toKotlin(),
            )
        Array(results.size) { i ->
            val c = results[i]
            coordsEle(c.latitude, c.longitude, c.elevation)
        }
    }

private fun SmoothingOptionsDto.toKotlin() = SmoothingOptions(windowSize = windowSize, enabled = enabled)

private fun FilterOptionsDto.toKotlin() = FilterOptions(tolerance = tolerance, zExaggeration = zExaggeration, enabled = enabled)
