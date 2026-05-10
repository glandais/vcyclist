@file:OptIn(
    DelicateCoroutinesApi::class,
    kotlin.js.ExperimentalJsExport::class,
    kotlin.js.ExperimentalWasmJsInterop::class,
)

package io.github.glandais.elevation

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.JsExport
import kotlin.js.Promise

/** JS-side input shape `{ latitude, longitude }`. */
external interface CoordinatesDto : JsAny {
    val latitude: Double
    val longitude: Double
}

/** JS-side output shape `{ latitude, longitude, elevation }`. */
external interface CoordinatesElevationDto : JsAny {
    val latitude: Double
    val longitude: Double
    val elevation: Double
}

/** Mirrors TS `SmoothingOptions`. */
external interface SmoothingOptionsDto : JsAny {
    val enabled: Boolean
    val windowSize: Double
}

/** Mirrors TS `FilterOptions`. */
external interface FilterOptionsDto : JsAny {
    val enabled: Boolean
    val tolerance: Double
    val zExaggeration: Double
}

/** Mirrors TS `getElevationsAlong` options bag — every field is optional from JS. */
external interface GetElevationsAlongOptionsDto : JsAny {
    val step: Double?
    val minDistance: Double?
    val interpolation: Boolean?
    val smoothingOptions: SmoothingOptionsDto?
    val filterOptions: FilterOptionsDto?
}

/** Mirrors a subset of [ElevationProviderConfig] — every field is optional from JS. */
external interface ElevationProviderConfigDto : JsAny {
    val zoomLevel: Int?
    val cacheSize: Int?
    val tileUrlTemplate: String?
    val tileSize: Int?
}

@JsFun("(latitude, longitude, elevation) => ({ latitude, longitude, elevation })")
private external fun coordsEle(
    latitude: Double,
    longitude: Double,
    elevation: Double,
): CoordinatesElevationDto

// Kotlin/Wasm 2.3.x restricts @JsExport to top-level functions, so the public API is shaped as
// free functions taking a JsReference<ElevationProvider> handle. The browser demo wraps these
// in a small `ElevationProvider` class shim so call sites look identical to the TS library.

@JsExport
fun newElevationProvider(configDto: ElevationProviderConfigDto?): JsReference<ElevationProvider> {
    val defaults = ElevationProviderConfig()
    val cfg =
        ElevationProviderConfig(
            zoomLevel = configDto?.zoomLevel ?: defaults.zoomLevel,
            cacheSize = configDto?.cacheSize ?: defaults.cacheSize,
            tileUrlTemplate = configDto?.tileUrlTemplate ?: defaults.tileUrlTemplate,
            tileSize = configDto?.tileSize ?: defaults.tileSize,
            attribution = defaults.attribution,
        )
    return ElevationProvider(cfg).toJsReference()
}

@JsExport
fun getElevation(
    provider: JsReference<ElevationProvider>,
    latitude: Double,
    longitude: Double,
    interpolation: Boolean,
): Promise<JsNumber> =
    GlobalScope.promise {
        provider.get().getElevation(latitude, longitude, interpolation).toJsNumber()
    }

@JsExport
fun getElevationsAlong(
    provider: JsReference<ElevationProvider>,
    path: JsArray<CoordinatesDto>,
    options: GetElevationsAlongOptionsDto?,
): Promise<JsArray<CoordinatesElevationDto>> =
    GlobalScope.promise {
        val coords =
            List(path.length) { i ->
                val p = path[i] ?: error("Path point $i is null")
                LatLon(p.latitude, p.longitude)
            }
        val results =
            provider.get().getElevationsAlong(
                path = coords,
                step = options?.step ?: 10.0,
                minDistance = options?.minDistance ?: 1.0,
                interpolation = options?.interpolation ?: true,
                smoothingOptions = options?.smoothingOptions?.toKotlin(),
                filterOptions = options?.filterOptions?.toKotlin(),
            )
        JsArray<CoordinatesElevationDto>().also { arr ->
            results.forEachIndexed { i, c -> arr[i] = coordsEle(c.latitude, c.longitude, c.elevation) }
        }
    }

private fun SmoothingOptionsDto.toKotlin() = SmoothingOptions(windowSize = windowSize, enabled = enabled)

private fun FilterOptionsDto.toKotlin() = FilterOptions(tolerance = tolerance, zExaggeration = zExaggeration, enabled = enabled)
