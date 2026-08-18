package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointField
import kotlin.math.pow

/**
 * Air density provider — returns ρ (kg/m³) at a given point on the course.
 *
 * Two impls : [RhoProviderDefault] (constant 1.225) and [RhoProviderEstimate] (ISA barometric model).
 */
fun interface RhoProvider {
    fun rho(
        course: Course,
        path: Path,
        pointIndex: Int,
    ): Double
}

/** Constant `EngineConstants.DEFAULT_AIR_DENSITY = 1.225` regardless of point. */
object RhoProviderDefault : RhoProvider {
    override fun rho(
        course: Course,
        path: Path,
        pointIndex: Int,
    ): Double = EngineConstants.DEFAULT_AIR_DENSITY
}

/**
 * ISA (International Standard Atmosphere) troposphere model. Reads `elevation(i)` and
 * `temperature(i)` from the path ; falls back to 15 °C and 0 m if either is `NaN`.
 *
 * A genuine `0.0` reading is honoured (a 0 °C winter ride keeps its air density) :
 * `GeneratedPath` NaN-initialises every slot, so "absent" and "actually 0 °C" are distinct.
 *
 * Formula :
 * ```
 * T        = temperatureC + 273.15
 * pressure = P0 · (1 − L·h / T0)^(g / (R·L))
 * ρ        = pressure / (R · T)
 * ```
 * with `P0 = 101325 Pa`, `T0 = 288.15 K`, `g = 9.80665 m/s²`, `L = 0.0065 K/m`,
 * `R = 287.05 J/(kg·K)`. Note : the provided temperature is used both in the pressure formula's
 * exponent argument and in the final division — slightly non-standard versus strict ISA (which
 * would use `T0` for pressure and `T0 − L·h` for the density division). The expression is kept as
 * is: changing it moves every simulated ride.
 *
 * Reference values (computed with `g/(R·L) = 5.2559323624`) :
 * - rho(0 m, 15 °C) ≈ 1.22501  → matches the conventional sea-level density 1.225 within 1e-3.
 * - rho(1500 m, 15 °C) ≈ 1.02227
 * - rho(3000 m, 15 °C) ≈ 0.84760
 */
object RhoProviderEstimate : RhoProvider {
    // ISA constants (source : ICAO Standard Atmosphere).
    private const val P0 = 101325.0 // sea level pressure (Pa)
    private const val T0 = 288.15 // sea level temperature (K)
    private const val G_ISA = 9.80665 // gravity (m/s²)
    private const val L = 0.0065 // temperature lapse rate (K/m)
    private const val R = 287.05 // specific gas constant for dry air (J/(kg·K))

    override fun rho(
        course: Course,
        path: Path,
        pointIndex: Int,
    ): Double {
        val providedTemp = path.get(pointIndex, PointField.TEMPERATURE)
        val temperatureC = if (providedTemp.isNaN()) 15.0 else providedTemp

        val providedElevation = path.elevation(pointIndex)
        val altitude = if (providedElevation.isNaN()) 0.0 else providedElevation

        val tKelvin = temperatureC + 273.15
        val pressure = P0 * (1.0 - L * altitude / T0).pow(G_ISA / (R * L))
        return pressure / (R * tKelvin)
    }
}
