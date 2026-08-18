package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reference values computed with the formula
 * `ρ = P0 · (1 − L·h/T0)^(g/(R·L)) / (R · (Tc + 273.15))`
 * using `P0=101325`, `T0=288.15`, `g=9.80665`, `L=0.0065`, `R=287.05` :
 *
 * | altitude | tempC | rho             |
 * |---------:|------:|----------------:|
 * |     0    |  15   | 1.2250122660    |
 * |  1000    |  15   | 1.0865759681    |
 * |  1500    |  15   | 1.0222742910    |
 * |  2000    |  15   | 0.9610891805    |
 * |  3000    |  15   | 0.8476041319    |
 * |     0    |   0   | 1.2922836699    |  (untruncated formula — sentinel falls back to 15 °C)
 * |     0    |  20   | 1.2041183164    |
 * |     0    |  30   | 1.1643981014    |
 * |   -50    |  15   | 1.2322916950    |
 */
class RhoProviderTest {
    private val tol = 1e-3
    private val tolTight = 1e-9

    /** Build a 1-point path with the given elevation and (optional) temperature in Celsius. */
    private fun pathWith(
        elevation: Double,
        temperatureC: Double? = null,
    ): Path {
        val path = Path(1)
        path.setElevation(0, elevation)
        if (temperatureC != null) path.setTemperature(0, temperatureC)
        return path
    }

    private fun course(path: Path) = Course(path)

    // ---- 1. Default provider -------------------------------------------------

    @Test
    fun default_provider_returns_1_225_for_any_point() {
        val path = pathWith(elevation = 1234.0, temperatureC = 7.5)
        val c = course(path)
        assertEquals(EngineConstants.DEFAULT_AIR_DENSITY, RhoProviderDefault.rho(c, path, 0), 0.0)
        assertEquals(1.225, RhoProviderDefault.rho(c, path, 0), 0.0)
    }

    // ---- 2-4. Sentinel values (altitude sweep at 15 °C) ---------------------

    @Test
    fun estimate_at_sea_level_15c_matches_sea_level_reference() {
        val path = pathWith(elevation = 0.0, temperatureC = 15.0)
        val rho = RhoProviderEstimate.rho(course(path), path, 0)
        // Sentinel : 1.225 ± 1e-3 — matches conventional sea-level density.
        assertEquals(1.225, rho, tol)
        assertEquals(1.2250122660, rho, tolTight)
    }

    @Test
    fun estimate_at_1500m_15c_matches_reference() {
        val path = pathWith(elevation = 1500.0, temperatureC = 15.0)
        val rho = RhoProviderEstimate.rho(course(path), path, 0)
        assertEquals(1.0222742910, rho, tol)
    }

    @Test
    fun estimate_at_3000m_15c_matches_reference() {
        val path = pathWith(elevation = 3000.0, temperatureC = 15.0)
        val rho = RhoProviderEstimate.rho(course(path), path, 0)
        assertEquals(0.8476041319, rho, tol)
    }

    // ---- 5-6. Temperature effects -------------------------------------------

    @Test
    fun estimate_density_is_higher_in_cold_than_at_15c() {
        // At 0 °C the sentinel triggers fallback to 15 °C ; we use 1 °C to genuinely test
        // "cold air → denser than 15 °C reference".
        val pCold = pathWith(elevation = 0.0, temperatureC = 1.0)
        val pRef = pathWith(elevation = 0.0, temperatureC = 15.0)
        val rhoCold = RhoProviderEstimate.rho(course(pCold), pCold, 0)
        val rhoRef = RhoProviderEstimate.rho(course(pRef), pRef, 0)
        assertTrue(rhoCold > rhoRef, "expected rhoCold ($rhoCold) > rhoRef ($rhoRef)")
    }

    @Test
    fun estimate_density_is_lower_in_heat_than_at_15c() {
        val pHot = pathWith(elevation = 0.0, temperatureC = 30.0)
        val pRef = pathWith(elevation = 0.0, temperatureC = 15.0)
        val rhoHot = RhoProviderEstimate.rho(course(pHot), pHot, 0)
        val rhoRef = RhoProviderEstimate.rho(course(pRef), pRef, 0)
        assertTrue(rhoHot < rhoRef, "expected rhoHot ($rhoHot) < rhoRef ($rhoRef)")
    }

    // ---- 7. NaN temperature → fallback 15 °C -------------------------------

    @Test
    fun estimate_falls_back_to_15c_when_temperature_is_nan() {
        val path = Path(1)
        path.setElevation(0, 1500.0)
        path.setTemperature(0, Double.NaN)
        val rhoNaN = RhoProviderEstimate.rho(course(path), path, 0)

        val ref = pathWith(elevation = 1500.0, temperatureC = 15.0)
        val rhoRef = RhoProviderEstimate.rho(course(ref), ref, 0)

        assertEquals(rhoRef, rhoNaN, tolTight)
    }

    // ---- 8. NaN elevation → fallback 0 m -----------------------------------

    @Test
    fun estimate_falls_back_to_0m_when_elevation_is_nan() {
        val path = Path(1)
        path.setElevation(0, Double.NaN)
        path.setTemperature(0, 20.0)
        val rhoNaN = RhoProviderEstimate.rho(course(path), path, 0)

        val ref = pathWith(elevation = 0.0, temperatureC = 20.0)
        val rhoRef = RhoProviderEstimate.rho(course(ref), ref, 0)

        assertEquals(rhoRef, rhoNaN, tolTight)
    }

    // ---- 9. Monotonicity vs altitude ---------------------------------------

    @Test
    fun estimate_density_decreases_strictly_with_altitude() {
        // Single Path with 4 distinct elevations ; same temperature (set explicitly to avoid
        // the `0.0` sentinel fallback).
        val path = Path(4)
        path.setElevation(0, 0.0)
        path.setElevation(1, 1000.0)
        path.setElevation(2, 2000.0)
        path.setElevation(3, 3000.0)
        for (i in 0..3) path.setTemperature(i, 15.0)
        val c = course(path)

        val rhos = (0..3).map { RhoProviderEstimate.rho(c, path, it) }
        for (i in 1..3) {
            assertTrue(
                rhos[i] < rhos[i - 1],
                "expected strict decrease : rho[$i] (${rhos[i]}) < rho[${i - 1}] (${rhos[i - 1]})",
            )
        }
    }

    // ---- 10. Bit-level parity on the sentinel point -------------------------

    @Test
    fun estimate_at_0m_15c_matches_ts_value_within_1e9() {
        val path = pathWith(elevation = 0.0, temperatureC = 15.0)
        val rho = RhoProviderEstimate.rho(course(path), path, 0)
        // Pre-computed (Java double, same formula) : 1.2250122660…
        assertEquals(1.2250122660, rho, 1e-9)
    }

    // ---- 11. Negative altitude (below sea level) ----------------------------

    @Test
    fun estimate_below_sea_level_is_denser_than_at_sea_level() {
        val pBelow = pathWith(elevation = -50.0, temperatureC = 15.0)
        val pSea = pathWith(elevation = 0.0, temperatureC = 15.0)
        val rhoBelow = RhoProviderEstimate.rho(course(pBelow), pBelow, 0)
        val rhoSea = RhoProviderEstimate.rho(course(pSea), pSea, 0)
        assertTrue(rhoBelow > rhoSea, "expected rhoBelow ($rhoBelow) > rhoSea ($rhoSea)")
        assertEquals(1.2322916950, rhoBelow, tol)
    }

    // ---- 12. temperature: genuine 0 °C vs unset slot ------------------------

    /**
     * A genuine 0 °C reading is honoured — it is *not* a "missing value" sentinel.
     * `RhoProviderEstimate` used to treat `0.0` as absent and silently substitute 15 °C, giving
     * a winter ride a ~5.5 % air density error ; absence is now signalled by the `NaN` that
     * `GpxToPath` writes. See `docs/parity.md` divergence 3.
     */
    @Test
    fun estimate_honours_a_genuine_zero_temperature() {
        val pZero = pathWith(elevation = 0.0, temperatureC = 0.0)
        val pRef = pathWith(elevation = 0.0, temperatureC = 15.0)

        val rhoZero = RhoProviderEstimate.rho(course(pZero), pZero, 0)
        val rhoRef = RhoProviderEstimate.rho(course(pRef), pRef, 0)
        assertEquals(1.2922836699, rhoZero, tol)
        assertTrue(rhoZero > rhoRef, "0 °C air must be denser than 15 °C air")
    }

    @Test
    fun estimate_falls_back_to_15c_when_temperature_slot_is_unset() {
        val pUnset = Path(1)
        pUnset.setElevation(0, 0.0)
        pUnset.setTemperature(0, Double.NaN) // what `GpxToPath` writes for a GPX with no <atemp>
        val pRef = pathWith(elevation = 0.0, temperatureC = 15.0)

        val rhoUnset = RhoProviderEstimate.rho(course(pUnset), pUnset, 0)
        val rhoRef = RhoProviderEstimate.rho(course(pRef), pRef, 0)
        assertEquals(rhoRef, rhoUnset, tolTight)
    }

    // ---- 13. 20 °C < 15 °C density -----------------------------------------

    @Test
    fun estimate_at_20c_is_less_dense_than_at_15c() {
        val pHot = pathWith(elevation = 0.0, temperatureC = 20.0)
        val pRef = pathWith(elevation = 0.0, temperatureC = 15.0)
        val rhoHot = RhoProviderEstimate.rho(course(pHot), pHot, 0)
        val rhoRef = RhoProviderEstimate.rho(course(pRef), pRef, 0)
        assertTrue(rhoHot < rhoRef)
        assertEquals(1.2041183164, rhoHot, tol)
    }

    // ---- 14. SAM lambda usage validates the `fun interface` shape ----------

    @Test
    fun rho_provider_can_be_built_from_a_sam_lambda() {
        val constProvider = RhoProvider { _, _, _ -> 1.0 }
        val path = pathWith(elevation = 0.0, temperatureC = 15.0)
        assertEquals(1.0, constProvider.rho(course(path), path, 0), 0.0)
    }

    // ---- Bonus : direct field access vs typed getter --------------------------

    @Test
    fun estimate_generic_field_access_matches_typed_getter_for_temperature() {
        val path = pathWith(elevation = 0.0, temperatureC = 7.5)
        // Sanity : both reads return the same Double, so the provider's
        // `path.get(i, PointField.TEMPERATURE)` is interchangeable with `path.temperature(i)`.
        assertEquals(path.temperature(0), path.get(0, PointField.TEMPERATURE), 0.0)
        val rho = RhoProviderEstimate.rho(course(path), path, 0)
        assertTrue(rho > 0.0)
    }
}
