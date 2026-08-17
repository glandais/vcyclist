package io.github.glandais.engine.physics

import io.github.glandais.engine.EngineConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Task 43 — the shared power catalog.
 *
 * The real guard here is not a test: it is the exhaustive `when` inside
 * [CyclistPowerSpec.toProvider]. Adding a [PowerModel] entry stops the engine compiling until the
 * mapping is written, on every target at once, which no test can do. These cases pin the parts the
 * compiler cannot: the wire names, the defaults, and the decorator ordering.
 */
class CyclistPowerSpecTest {
    @Test
    fun `every model builds its provider`() {
        // Exhaustive by construction: iterating entries means a new model lands here for free.
        for (model in PowerModel.entries) {
            val provider = CyclistPowerSpec(model = model).toProvider()
            val expected: Any =
                when (model) {
                    PowerModel.CONSTANT -> PowerProviderConstant::class
                    PowerModel.DURABILITY -> PowerProviderDurability::class
                    PowerModel.CRITICAL_POWER -> PowerProviderCriticalPower::class
                    PowerModel.FROM_DATA -> PowerProviderFromData::class
                }
            assertEquals(expected, provider::class, "model ${model.id} built the wrong provider")
        }
    }

    @Test
    fun `wire names are unique, lowercase and stable`() {
        val ids = PowerModel.ids
        assertEquals(ids.size, ids.toSet().size, "duplicate wire name in $ids")
        assertTrue(ids.all { it == it.lowercase() }, "wire names must be lowercase: $ids")
        // Pinned literally: these strings are the CLI option value, the JS DTO `type` and the WASI
        // JSON ABI all at once. Renaming one is a breaking change on three surfaces — R17 renamed
        // `constant_tiring` and broke the demo for nine ledger entries.
        assertEquals(listOf("constant", "durability", "critical-power", "from_data"), ids)
    }

    @Test
    fun `parsing is case-insensitive and rejects the unknown`() {
        assertEquals(PowerModel.CRITICAL_POWER, PowerModel.fromIdOrNull("critical-power"))
        assertEquals(PowerModel.CRITICAL_POWER, PowerModel.fromIdOrNull("CRITICAL-POWER"))
        assertNull(PowerModel.fromIdOrNull("constant_tiring"))
        assertNull(PowerModel.fromIdOrNull(""))
    }

    @Test
    fun `defaults come from EngineConstants, not from literals`() {
        val spec = CyclistPowerSpec()
        assertEquals(EngineConstants.DEFAULT_CYCLIST_POWER_W, spec.powerW)
        assertEquals(EngineConstants.DEFAULT_CRITICAL_POWER_W, spec.criticalPowerW)
        assertEquals(EngineConstants.DEFAULT_W_PRIME_J, spec.wPrimeJ)
        assertEquals(PowerModel.CONSTANT, spec.model)
        assertEquals(false, spec.pacing)
        assertEquals(0.0, spec.maxSlewWPerS)
    }

    @Test
    fun `decorators compose base then pacing then slew`() {
        val plain = CyclistPowerSpec().toProvider()
        assertIs<PowerProviderConstant>(plain)

        val paced = CyclistPowerSpec(pacing = true).toProvider()
        assertIs<PowerProviderTerrainPacing>(paced)
        assertIs<PowerProviderConstant>(paced.delegate)

        val slewed = CyclistPowerSpec(maxSlewWPerS = 50.0).toProvider()
        assertIs<PowerProviderSlewLimited>(slewed)
        assertIs<PowerProviderConstant>(slewed.delegate)

        val both = CyclistPowerSpec(pacing = true, maxSlewWPerS = 50.0).toProvider()
        // Slew outermost: it must see the final signal, or it smooths something that is then
        // stepped again by pacing. This ordering used to be written out on three surfaces.
        assertIs<PowerProviderSlewLimited>(both)
        val inner = both.delegate
        assertIs<PowerProviderTerrainPacing>(inner)
        assertIs<PowerProviderConstant>(inner.delegate)
    }

    @Test
    fun `a zero or negative slew leaves the chain undecorated`() {
        // PowerProviderSlewLimited requires a positive rate, so "off" must not reach its
        // constructor — every surface spells "off" as 0.
        assertIs<PowerProviderConstant>(CyclistPowerSpec(maxSlewWPerS = 0.0).toProvider())
        assertIs<PowerProviderConstant>(CyclistPowerSpec(maxSlewWPerS = -1.0).toProvider())
    }

    @Test
    fun `from_data stays the singleton when undecorated`() {
        assertSame(PowerProviderFromData, CyclistPowerSpec(model = PowerModel.FROM_DATA).toProvider())
    }

    @Test
    fun `the spec carries its parameters into the providers`() {
        val durability =
            CyclistPowerSpec(model = PowerModel.DURABILITY, powerW = 310.0, criticalPowerW = 265.0)
                .toProvider()
        assertIs<PowerProviderDurability>(durability)
        assertEquals(310.0, durability.powerW)
        assertEquals(265.0, durability.criticalPowerW)

        val cp =
            CyclistPowerSpec(
                model = PowerModel.CRITICAL_POWER,
                powerW = 300.0,
                criticalPowerW = 240.0,
                wPrimeJ = 18_000.0,
            ).toProvider()
        assertIs<PowerProviderCriticalPower>(cp)
        assertEquals(300.0, cp.powerW)
        assertEquals(240.0, cp.criticalPowerW)
        assertEquals(18_000.0, cp.wPrimeJ)

        val slewed = CyclistPowerSpec(maxSlewWPerS = 30.0).toProvider()
        assertIs<PowerProviderSlewLimited>(slewed)
        assertEquals(30.0, slewed.maxSlewWPerS)
    }
}
