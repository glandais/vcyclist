package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Aerodynamic drag power. Two regimes :
 *
 * - **No wind** (`wind.speedMS == 0.0`) : `P = -aeroCoef × v³`.
 * - **With wind** : Isvan's lightweight-vehicle power model — accounts for cyclist bearing,
 *   wind direction, the combined velocity vector, and a turbulence factor `μ = 1.2`.
 *
 * Reference : Isvan, O. (2011) "Power Optimization for the Propulsion of Lightweight Vehicles."
 * https://www.sheldonbrown.com/isvan/Power%20Management%20for%20Lightweight%20Vehicles.pdf
 *
 * Side-effects on [Path] at `pointIndex` :
 * - Always : `aeroCoef`, `pAero`.
 * - With wind : also `windSpeed`, `windDirection`, `windBearing`, `windAlpha`.
 *
 * Note : the wind direction stored in [Wind.directionRad] follows the meteorological
 * convention (0 = North, π/2 = East). The path's `bearing` follows the math convention
 * (0 = East, π/2 = North). The Isvan formula uses both ; the conversion is
 * `windBearing = π/2 − wind.directionRad`.
 */
object AeroPowerProvider : PowerProvider {
    private const val MU = 1.2

    override fun powerAt(
        course: CoursePhysics,
        path: Path,
        pointIndex: Int,
    ): Double {
        val aeroCoef = course.aeroProvider.aeroCoef(course, path, pointIndex)
        path.setAeroCoef(pointIndex, aeroCoef)

        val wind = course.windProvider.wind(course.course, path, pointIndex)
        val pAir =
            if (wind.speedMS == 0.0) {
                val v = path.speed(pointIndex)
                -aeroCoef * v * v * v
            } else {
                computeWithWind(path, pointIndex, aeroCoef, wind)
            }
        path.setPAero(pointIndex, pAir)
        return pAir
    }

    private fun computeWithWind(
        path: Path,
        i: Int,
        aeroCoef: Double,
        wind: Wind,
    ): Double {
        val speed = path.speed(i)
        val bearing = path.bearing(i)

        // Store wind data for debugging/analysis.
        path.setWindSpeed(i, wind.speedMS)
        path.setWindDirection(i, wind.directionRad)

        // Wind direction (N=0, E=π/2) → bearing convention (E=0, N=π/2).
        val windDirectionAsBearing = PI / 2.0 - wind.directionRad
        path.setWindBearing(i, windDirectionAsBearing)

        // Angle between wind and cyclist direction.
        val alpha = windDirectionAsBearing - bearing
        path.setWindAlpha(i, alpha)

        val v = wind.speedMS

        // Isvan's power model for aerodynamic drag with wind — port verbatim of the TS source.
        // Component of combined velocity in direction of travel.
        val l1 = speed + v * cos(alpha)
        // Square of velocity component.
        val l2 = l1 * l1
        // Magnitude squared of the combined velocity vector.
        val l3 = speed * speed + v * v + 2.0 * speed * v * cos(alpha)
        // Ratio of directional component to total magnitude squared.
        val l4 = l2 / l3
        // Lambda factor: weighted combination of ideal flow and turbulent flow.
        val lambda = l4 + MU * (1.0 - l4)

        return -aeroCoef * lambda * sqrt(l3) * l1 * speed
    }
}
