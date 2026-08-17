package io.github.glandais.engine.wasi

import io.github.glandais.engine.Bike
import io.github.glandais.engine.Course
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.RoadCondition
import io.github.glandais.engine.climb.ClimbOptions
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.physics.PowerModel
import io.github.glandais.engine.physics.PowerProviderConstant
import io.github.glandais.engine.physics.PowerProviderCriticalPower
import io.github.glandais.engine.physics.PowerProviderDurability
import io.github.glandais.engine.physics.PowerProviderFromData
import io.github.glandais.engine.physics.PowerProviderSlewLimited
import io.github.glandais.engine.physics.PowerProviderTerrainPacing
import io.github.glandais.engine.physics.WindProviderConstant
import io.github.glandais.engine.physics.WindProviderNone
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The options readers: names shared with the JS DTOs, defaults taken from the engine, unknown
 * keys refused.
 *
 * These run here rather than through the exports for the reason task w03 established the hard
 * way: anything that reaches an export touching `read_input` cannot be instantiated by the KGP
 * test runner.
 */
class WasiOptionsTest {
    private fun json(text: String) = parseJsonObject(text)

    @Test
    fun `no options means the WASI defaults, which are the JS ones`() {
        val o = null.toEnhanceOptions()

        assertEquals(false, o.fixElevation, "no network on this target")
        assertEquals(true, o.computeMaxSpeeds)
        assertEquals(true, o.virtualizeTrack)
        assertEquals(false, o.computeOnePointPerSecond)
        assertEquals(false, o.simplifyPath.enabled)
    }

    @Test
    fun `a partial options object overrides only what it names`() {
        val o = json("""{"computeOnePointPerSecond":true,"simplifyEnabled":true}""").toEnhanceOptions()

        assertEquals(true, o.computeOnePointPerSecond)
        assertEquals(true, o.simplifyPath.enabled)
        assertEquals(true, o.virtualizeTrack, "untouched fields keep their default")
        assertEquals(
            simplifyToleranceDefault,
            o.simplifyPath.toleranceM,
            "the tolerance default must come from SimplifyPathOptions, not from this reader",
        )
    }

    @Test
    fun `cyclist and bike defaults are the engine's, field by field`() {
        val cyclist = json("""{"massKg":72}""").toCyclist()
        val bike = json("""{"crr":0.005}""").toBike()

        assertEquals(72.0, cyclist.massKg)
        assertEquals(Cyclist().cd, cyclist.cd)
        assertEquals(Cyclist().maxSpeedKmH, cyclist.maxSpeedKmH)
        assertEquals(0.005, bike.crr)
        assertEquals(Bike().efficiency, bike.efficiency)
    }

    @Test
    fun `wind is degrees in, radians out, and null means no wind`() {
        assertSame(WindProviderNone, null.toWindProvider())

        val provider = json("""{"windSpeed":5,"windDirection":270}""").toWindProvider()

        // `WindProviderConstant.wind` is private, so ask it the way the physics does.
        val constant = assertIs<WindProviderConstant>(provider)
        val wind = constant.wind(course = Course(path = Path(1)), path = Path(1), pointIndex = 0)
        assertEquals(5.0, wind.speedMS)
        assertEquals(270.0 * PI / 180.0, wind.directionRad, 1e-12)
    }

    @Test
    fun `every power model in the catalog is reachable over the ABI`() {
        // The catalog is the source of the list: a model added to the engine must be reachable
        // here without editing this test, which is the whole point of task 43. Before it, this
        // file carried its own `when` and had been stuck at three models for two ledger entries.
        for (model in PowerModel.entries) {
            val spec = json("""{"type":"${model.id}"}""").toPowerSpec()
            assertEquals(model, spec.model, "model ${model.id} did not round-trip")
        }
    }

    @Test
    fun `the power models build the expected providers`() {
        assertIs<PowerProviderConstant>(json("""{"type":"constant","power":220}""").toCyclistPowerProvider())
        val durability =
            json("""{"type":"durability","power":220,"criticalPower":240}""").toCyclistPowerProvider()
        assertIs<PowerProviderDurability>(durability)
        assertEquals(240.0, durability.criticalPowerW)
        val cp =
            json("""{"type":"critical-power","power":300,"criticalPower":240,"wPrime":15000}""")
                .toCyclistPowerProvider()
        assertIs<PowerProviderCriticalPower>(cp)
        assertEquals(15000.0, cp.wPrimeJ)
        assertSame(PowerProviderFromData, json("""{"type":"from_data"}""").toCyclistPowerProvider())
    }

    @Test
    fun `the decorators compose in the shared order`() {
        val slewOnly = json("""{"type":"constant","maxSlewWPerS":50}""").toCyclistPowerProvider()
        assertIs<PowerProviderSlewLimited>(slewOnly)

        val both = json("""{"type":"constant","pacing":true,"maxSlewWPerS":50}""").toCyclistPowerProvider()
        // Slew outermost, pacing inside it — the order CyclistPowerSpec defines for every surface.
        assertIs<PowerProviderSlewLimited>(both)
        assertIs<PowerProviderTerrainPacing>(both.delegate)

        assertIs<PowerProviderConstant>(json("""{"type":"constant","maxSlewWPerS":0}""").toCyclistPowerProvider())
    }

    @Test
    fun `the default power is the library default, not a local copy`() {
        // This façade hardcoded 250 W while the CLI used 280 W, for the same unconfigured rider.
        assertEquals(EngineConstants.DEFAULT_CYCLIST_POWER_W, json("{}").toPowerSpec().powerW)
        assertEquals(EngineConstants.DEFAULT_CYCLIST_POWER_W, (null as JsonObj?).toPowerSpec().powerW)
    }

    @Test
    fun `an unknown power type names the ones that exist`() {
        val thrown =
            assertFailsWith<IllegalArgumentException> {
                json("""{"type":"quadratic"}""").toCyclistPowerProvider()
            }

        assertTrue(thrown.message!!.contains("quadratic"), thrown.message!!)
        assertTrue(thrown.message!!.contains("critical-power"), thrown.message!!)
        assertTrue(thrown.message!!.contains("from_data"), thrown.message!!)
    }

    @Test
    fun `the road condition preset overrides the raw grip limits`() {
        val dry = json("""{"roadCondition":"dry"}""").toCyclist()
        assertEquals(Cyclist().maxLeanAngleDeg, dry.maxLeanAngleDeg, 1e-12)
        assertEquals(Cyclist().maxBrakeG, dry.maxBrakeG, 1e-12)

        val wet = json("""{"maxLeanAngleDeg":42,"maxBrakeG":0.5,"roadCondition":"WET"}""").toCyclist()
        assertEquals(RoadCondition.WET.leanAngleDeg, wet.maxLeanAngleDeg, 1e-12)
        assertEquals(RoadCondition.WET.maxBrakeG, wet.maxBrakeG, 1e-12)

        // Absent: the raw values stand, which is the pre-R9 behaviour.
        val raw = json("""{"maxLeanAngleDeg":42}""").toCyclist()
        assertEquals(42.0, raw.maxLeanAngleDeg, 1e-12)

        assertFailsWith<IllegalArgumentException> { json("""{"roadCondition":"damp"}""").toCyclist() }
    }

    @Test
    fun `the W prime balance options are readable`() {
        val o =
            json("""{"wPrimeBalanceEnabled":false,"wPrimeBalanceCriticalPower":300,"wPrimeBalanceWPrime":25000}""")
                .toEnhanceOptions()
        assertEquals(false, o.wPrimeBalance.enabled)
        assertEquals(300.0, o.wPrimeBalance.criticalPowerW)
        assertEquals(25000.0, o.wPrimeBalance.wPrimeJ)
    }

    @Test
    fun `climb options keep the JS parameter name for maxDiffRealGrade`() {
        val o = json("""{"maxDiffRealGrade":1.7,"booster":2.0}""").toClimbOptions()

        assertEquals(1.7, o.maxDiffRealGradeRatio)
        assertEquals(2.0, o.booster)
        assertEquals(ClimbOptions().minGradePercent, o.minGradePercent)
    }

    @Test
    fun `csv separator takes one character and an empty string keeps the default`() {
        assertEquals(';', json("""{"separator":";"}""").toCsvOptions().separator)
        assertEquals(',', json("""{"separator":""}""").toCsvOptions().separator)
    }

    @Test
    fun `write-gpx options carry the writeGpxAt behaviour through startTimeEpochMs`() {
        val relative = json("""{"writeExtensions":false}""").toWriteGpxOptions()
        val absolute = json("""{"startTimeEpochMs":1714550400000}""").toWriteGpxOptions()

        assertEquals(false, relative.writeExtensions)
        assertEquals(null, relative.startTimeEpochMs, "absent means relative times, as written")
        assertEquals(1714550400000.0, absolute.startTimeEpochMs)
        assertEquals(true, absolute.writeExtensions, "default stays true")
    }

    @Test
    fun `a typo in an option is an error, on every reader`() {
        assertFailsWith<IllegalArgumentException> { json("""{"fixElevations":true}""").toEnhanceOptions() }
        assertFailsWith<IllegalArgumentException> { json("""{"cyclistWeight":70}""").toCyclist() }
        assertFailsWith<IllegalArgumentException> { json("""{"rollingResistance":0.004}""").toBike() }
        assertFailsWith<IllegalArgumentException> { json("""{"windDirectionDeg":90}""").toWindProvider() }
        assertFailsWith<IllegalArgumentException> { json("""{"minGrade":3}""").toClimbOptions() }
        assertFailsWith<IllegalArgumentException> { json("""{"delimiter":";"}""").toCsvOptions() }
        assertFailsWith<IllegalArgumentException> { json("""{"indent":2}""").toJsonOptions() }
        assertFailsWith<IllegalArgumentException> { json("""{"startTime":0}""").toWriteGpxOptions() }
    }

    /** Read from the engine, so this test fails if the default moves rather than freezing it. */
    private val simplifyToleranceDefault =
        io.github.glandais.engine
            .SimplifyPathOptions()
            .toleranceM
}
