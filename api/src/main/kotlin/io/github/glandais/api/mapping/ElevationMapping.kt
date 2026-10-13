package io.github.glandais.api.mapping

import io.github.glandais.api.dto.AttributionDto
import io.github.glandais.api.dto.ElevationDefaultsDto
import io.github.glandais.api.dto.ElevationSourceDto
import io.github.glandais.api.dto.FilterOptionsDto
import io.github.glandais.api.dto.LatLonDto
import io.github.glandais.api.dto.SmoothingOptionsDto
import io.github.glandais.elevation.Attribution
import io.github.glandais.elevation.ElevationDefaults
import io.github.glandais.elevation.ElevationFunctions
import io.github.glandais.elevation.ElevationSource
import io.github.glandais.elevation.FilterOptions
import io.github.glandais.elevation.LatLon
import io.github.glandais.elevation.SmoothingOptions

/*
 * The DEM routes: request DTOs → elevation-library objects, and the inverse for `GET /defaults`.
 * Every fallback is read off [HttpDefaults.elevationSource], [ElevationDefaults], or a default
 * [SmoothingOptions] / [FilterOptions] instance — never written out.
 */

/** The DEM source [id] names (exact, case-sensitive), [HttpDefaults.elevationSource] when absent. */
fun elevationSource(
    id: String?,
    pointer: String,
): ElevationSource {
    if (id == null) return HttpDefaults.elevationSource
    return ElevationSource.entries.firstOrNull { it.id == id }
        ?: throw invalidOption(pointer, "unknown value '$id', expected one of ${ElevationSource.ids.joinToString(", ")}")
}

/** [zoom] when valid (0..15, the library's own check), `null` for the source's default. */
fun elevationZoom(
    zoom: Int?,
    pointer: String,
): Int? {
    if (zoom == null) return null
    if (!ElevationFunctions.isValidZoomLevel(zoom)) throw invalidOption(pointer, "invalid zoom level $zoom")
    return zoom
}

/**
 * A position the DEM can be asked about, checked with the library's own bounds — the Web Mercator
 * band for the latitude, not ±90: a tile pyramid has no row past about 85.05°.
 */
fun latLon(
    latitude: Double,
    longitude: Double,
    latitudePointer: String,
    longitudePointer: String,
): LatLon {
    if (!ElevationFunctions.isValidLatitude(latitude)) {
        throw invalidOption(latitudePointer, "latitude $latitude is outside the Web Mercator band")
    }
    if (!ElevationFunctions.isValidLongitude(longitude)) {
        throw invalidOption(longitudePointer, "longitude $longitude is outside -180..180")
    }
    return LatLon(latitude, longitude)
}

/** Every point of [points], checked; pointers name the offending index (`/points/3/latitude`). */
fun List<LatLonDto>.toLatLons(at: String = "/points"): List<LatLon> =
    mapIndexed { i, p -> latLon(p.latitude, p.longitude, "$at/$i/latitude", "$at/$i/longitude") }

/** The smoothing settings, every absent field read off a default [SmoothingOptions]. */
fun SmoothingOptionsDto?.toSmoothingOptions(): SmoothingOptions {
    val d = SmoothingOptions()
    if (this == null) return d
    return SmoothingOptions(windowSize = windowSize ?: d.windowSize, enabled = enabled ?: d.enabled)
}

/** The simplification settings, every absent field read off a default [FilterOptions]. */
fun FilterOptionsDto?.toFilterOptions(): FilterOptions {
    val d = FilterOptions()
    if (this == null) return d
    return FilterOptions(
        tolerance = tolerance ?: d.tolerance,
        zExaggeration = zExaggeration ?: d.zExaggeration,
        enabled = enabled ?: d.enabled,
    )
}

/**
 * What must be displayed with an elevation of [this] source: its own attribution, then its
 * fallback's — a LiDAR HD answer abroad *is* Mapterhorn data.
 */
fun ElevationSource.attributions(zoom: Int?): List<AttributionDto> =
    listOfNotNull(config(zoom).attribution, fallback?.config()?.attribution).map { it.toDto() }

/** The wire shape of an attribution. */
fun Attribution.toDto(): AttributionDto = AttributionDto(text = text, url = url)

/** The wire shape of a source, for `GET /elevation/sources`. */
fun ElevationSource.toDto(): ElevationSourceDto =
    ElevationSourceDto(
        id = id,
        defaultZoomLevel = defaultZoomLevel,
        fallback = fallback?.id,
        attribution = attributions(null),
    )

/** The DTO [toSmoothingOptions] reads back into these settings. */
fun SmoothingOptions.toDto(): SmoothingOptionsDto = SmoothingOptionsDto(enabled = enabled, windowSize = windowSize)

/** The DTO [toFilterOptions] reads back into these settings. */
fun FilterOptions.toDto(): FilterOptionsDto = FilterOptionsDto(enabled = enabled, tolerance = tolerance, zExaggeration = zExaggeration)

/** `GET /defaults` `elevation`: what the DEM routes apply when a field is absent. */
fun elevationDefaultsDto(): ElevationDefaultsDto =
    ElevationDefaultsDto(
        source = HttpDefaults.elevationSource.id,
        step = ElevationDefaults.STEP_M,
        minDistance = ElevationDefaults.MIN_DISTANCE_M,
        interpolation = ElevationDefaults.INTERPOLATION,
        smoothingOptions = SmoothingOptions().toDto(),
        filterOptions = FilterOptions().toDto(),
    )
