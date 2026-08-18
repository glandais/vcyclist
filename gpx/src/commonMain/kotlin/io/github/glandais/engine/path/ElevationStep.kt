package io.github.glandais.engine.path

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.ElevationSmoother
import io.github.glandais.elevation.LatLon
import io.github.glandais.elevation.MathConstants

/**
 * Two helpers that bridge the `:elevation` module to the engine's [Path] :
 *
 * - [fixElevation] : pulls corrected altitudes from an [ElevationProvider] (Terrarium tiles).
 *   Async — needs network if a real provider is used.
 * - [smoothElevation] : runs the triangular-kernel smoother over the path with a 150 m window.
 *   Synchronous.
 *
 * Both return a fresh [Path] preserving every other slot and call [Path.computeDerivedData] at
 * the end.
 */
object ElevationStep {
    /** Default smoothing window (meters). */
    const val DEFAULT_SMOOTH_WINDOW_M: Double = 150.0

    /** Fetch corrected elevations for every point of [source] and return a fresh path. */
    suspend fun fixElevation(
        source: Path,
        provider: ElevationProvider,
    ): Path {
        if (source.size == 0) return Path(0)
        val coords =
            List(source.size) {
                LatLon(
                    latitude = source.latitude(it) * MathConstants.RAD_TO_DEG,
                    longitude = source.longitude(it) * MathConstants.RAD_TO_DEG,
                )
            }
        val corrected = provider.setElevations(coords)
        val out = copyAllSlots(source)
        for (i in 0 until out.size) {
            out.setElevation(i, corrected[i].elevation)
        }
        out.computeDerivedData()
        return out
    }

    /** Apply the triangular-kernel smoother (window [windowM]) and return a fresh path. */
    fun smoothElevation(
        source: Path,
        windowM: Double = DEFAULT_SMOOTH_WINDOW_M,
    ): Path {
        if (source.size == 0) return Path(0)
        val coords = List(source.size) { i -> source.coordinatesElevationAt(i) }
        val smoothed = ElevationSmoother.smooth(coords, windowM)
        val out = copyAllSlots(source)
        for (i in 0 until out.size) {
            out.setElevation(i, smoothed[i].elevation)
        }
        out.computeDerivedData()
        return out
    }

    private fun copyAllSlots(source: Path): Path {
        val out = Path(source.size)
        for (i in 0 until source.size) {
            for (field in PointField.entries) {
                out.set(i, field, source.get(i, field))
            }
        }
        return out
    }
}
