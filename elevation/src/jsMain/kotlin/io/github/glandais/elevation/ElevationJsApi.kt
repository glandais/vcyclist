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

/** JS shape of [SmoothingOptions]. */
external interface SmoothingOptionsDto {
    val enabled: Boolean
    val windowSize: Double
}

/** JS shape of [FilterOptions]. */
external interface FilterOptionsDto {
    val enabled: Boolean
    val tolerance: Double
    val zExaggeration: Double
}

/** Options bag for `getElevationsAlong` — every field is optional from JS. */
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

// Free-function shape so the generated TypeScript facade stays a flat list of functions, with no
// class to construct. The ElevationProvider instance is returned directly (no JsReference handle
// needed: Kotlin/JS classes are first-class JS objects), and consumers treat it as opaque — which
// `index.d.ts` now enforces with a branded type rather than the `any` the compiler emits.

@JsExport
fun newElevationProvider(configDto: ElevationProviderConfigDto?): ElevationProvider {
    configDto?.requireOnlyKeys("ElevationProviderConfigDto", CONFIG_KEYS)
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
): Promise<Array<CoordinatesElevationDto>> {
    // Validated OUTSIDE the promise: a misspelled key is a programming error at the call site, and
    // throwing there is what a caller can act on. Inside `GlobalScope.promise` it would become a
    // rejected promise, which an `await`-less caller drops on the floor as an unhandled rejection.
    options?.requireOnlyKeys("GetElevationsAlongOptionsDto", ALONG_KEYS)
    options?.smoothingOptions?.requireOnlyKeys("SmoothingOptionsDto", SMOOTHING_KEYS)
    options?.filterOptions?.requireOnlyKeys("FilterOptionsDto", FILTER_KEYS)
    return GlobalScope.promise {
        val coords = path.map { LatLon(it.latitude, it.longitude) }
        val results =
            provider.getElevationsAlong(
                path = coords,
                step = options?.step ?: ElevationDefaults.STEP_M,
                minDistance = options?.minDistance ?: ElevationDefaults.MIN_DISTANCE_M,
                interpolation = options?.interpolation ?: ElevationDefaults.INTERPOLATION,
                smoothingOptions = options?.smoothingOptions?.toKotlin(),
                filterOptions = options?.filterOptions?.toKotlin(),
            )
        Array(results.size) { i ->
            val c = results[i]
            coordsEle(c.latitude, c.longitude, c.elevation)
        }
    }
}

private fun SmoothingOptionsDto.toKotlin() = SmoothingOptions(windowSize = windowSize, enabled = enabled)

private fun FilterOptionsDto.toKotlin() = FilterOptions(tolerance = tolerance, zExaggeration = zExaggeration, enabled = enabled)

private val CONFIG_KEYS = setOf("zoomLevel", "cacheSize", "tileUrlTemplate", "tileSize")

private val ALONG_KEYS = setOf("step", "minDistance", "interpolation", "smoothingOptions", "filterOptions")

private val SMOOTHING_KEYS = setOf("enabled", "windowSize")

private val FILTER_KEYS = setOf("enabled", "tolerance", "zExaggeration")

/**
 * Reject a DTO carrying a key this façade does not read.
 *
 * `EngineJsApi` has had this since task 43 and this façade had **nothing**: a misspelled `step` in
 * a `GetElevationsAlongOptionsDto` was silently ignored and the caller got the default, while the
 * identical typo was a hard error on every guarded engine DTO and on every WASI reader — WASI even
 * validates the provider config this door did not. An `external interface` ignores unknown
 * properties in silence, so nothing but this check can tell a caller they misspelled something.
 *
 * Duplicated from `EngineJsApi` rather than shared: `:elevation` does not depend on `:engine` (the
 * dependency runs the other way), and a public utility existing only to be shared across that line
 * would be worse than twelve lines twice — the same call `jvmFuture` makes in three modules.
 */
private fun Any.requireOnlyKeys(
    dtoName: String,
    allowed: Set<String>,
) {
    val keys = js("Object.keys")(this).unsafeCast<Array<String>>()
    val unknown = keys.filterNot { it in allowed }
    if (unknown.isNotEmpty()) {
        error(
            "Unknown $dtoName key(s): ${unknown.joinToString()} — expected one of " +
                allowed.sorted().joinToString(),
        )
    }
}
