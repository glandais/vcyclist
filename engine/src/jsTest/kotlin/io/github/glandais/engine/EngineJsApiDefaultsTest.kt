package io.github.glandais.engine

import io.github.glandais.engine.trajectory.CurvatureOptions
import io.github.glandais.engine.trajectory.RacingLineOptions
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The JS façade must take its defaults from the engine, never restate them.
 *
 * This is the drift class that once had the façades defending 250 W while the CLI defended 280 W,
 * and no key check can see it: every door lists the same keys, every door parses them, and they
 * quietly disagree about what happens when the caller says nothing.
 *
 * The façade had **two** default sites — `toEnhanceOptions` and `defaultJsOptions()` — that spelled
 * the same values out separately, so a default changed in one did not reach `enhance(path, null)`.
 * They are one site now, and these assertions pin it from the outside: pass a partial DTO, and the
 * fields you did not name must equal the engine's own values.
 *
 * The WASI door has had this pin since w09 (`WasiOptionsTest`); JS did not, which is exactly how it
 * drifted.
 */
class EngineJsApiDefaultsTest {
    @Test
    fun `an unnamed simplify tolerance is SimplifyPathOptions', not a literal`() {
        // Enabling simplify without naming its tolerance has to pick up the engine's tolerance.
        // Before S6 this read `simplifyToleranceM ?: 10.0`, so moving SimplifyPathOptions would
        // have moved the CLI and WASI and left this door behind.
        val options = optionsOf(simplifyEnabled = true)

        assertEquals(SimplifyPathOptions().toleranceM, options.simplifyPath.toleranceM)
        assertEquals(SimplifyPathOptions().zExaggeration, options.simplifyPath.zExaggeration)
    }

    @Test
    fun `an unnamed W prime balance calibration is WPrimeBalanceOptions'`() {
        val options = optionsOf(wPrimeBalanceEnabled = true)

        assertEquals(WPrimeBalanceOptions().criticalPowerW, options.wPrimeBalance.criticalPowerW)
        assertEquals(WPrimeBalanceOptions().wPrimeJ, options.wPrimeBalance.wPrimeJ)
    }

    @Test
    fun `an unnamed racing-line road width is RacingLineOptions'`() {
        val options = optionsOf(racingLineEnabled = true)

        assertEquals(RacingLineOptions.DEFAULT.defaultRoadWidthM, options.racingLine.defaultRoadWidthM)
        assertEquals(RacingLineOptions.DEFAULT.corridor, options.racingLine.corridor)
    }

    @Test
    fun `curvature is on by default here because CurvatureOptions says so`() {
        assertEquals(CurvatureOptions().enabled, optionsOf().curvature.enabled)
    }

    /**
     * The four flags this door decides for itself, and the reason it is allowed to: a browser must
     * not fetch DEM tiles unasked, and the 1 Hz resample and simplify are off so smoke results stay
     * deterministic (task 27). Pinned so that changing one is a decision.
     *
     * The point of pinning them here is that `enhance(path, null)` and `enhance(path, {})` must
     * agree — they read the same site now, and did not before.
     */
    @Test
    fun `the door's own defaults are the same whether options are absent or empty`() {
        val absent = null.toEnhanceOptions()
        val empty = optionsOf()

        assertEquals(absent.fixElevation, empty.fixElevation)
        assertEquals(absent.computeOnePointPerSecond, empty.computeOnePointPerSecond)
        assertEquals(absent.simplifyPath.enabled, empty.simplifyPath.enabled)
        assertEquals(false, absent.fixElevation, "a browser must not fetch DEM tiles unasked")
    }

    private fun optionsOf(
        simplifyEnabled: Boolean? = null,
        wPrimeBalanceEnabled: Boolean? = null,
        racingLineEnabled: Boolean? = null,
    ): EnhanceOptions {
        val dto = js("({})")
        if (simplifyEnabled != null) dto.simplifyEnabled = simplifyEnabled
        if (wPrimeBalanceEnabled != null) dto.wPrimeBalanceEnabled = wPrimeBalanceEnabled
        if (racingLineEnabled != null) dto.racingLineEnabled = racingLineEnabled
        return dto.unsafeCast<EnhanceOptionsDto>().toEnhanceOptions()
    }
}
