package io.github.glandais.engine.wasi

import io.github.glandais.engine.Bike
import io.github.glandais.engine.Course
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.climb.ClimbOptions
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.physics.PowerProviderConstant
import io.github.glandais.engine.physics.PowerProviderConstantWithTiring
import io.github.glandais.engine.physics.PowerProviderFromData
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
    fun `the three power provider types are recognised`() {
        assertIs<PowerProviderConstant>(json("""{"type":"constant","power":220}""").toCyclistPowerProvider())
        assertIs<PowerProviderConstantWithTiring>(
            json("""{"type":"constant_tiring","power":220,"tiringDuration":3600}""").toCyclistPowerProvider(),
        )
        assertSame(PowerProviderFromData, json("""{"type":"from_data"}""").toCyclistPowerProvider())
    }

    @Test
    fun `an unknown power type names the three that exist`() {
        val thrown =
            assertFailsWith<IllegalArgumentException> {
                json("""{"type":"quadratic"}""").toCyclistPowerProvider()
            }

        assertTrue(thrown.message!!.contains("quadratic"), thrown.message!!)
        assertTrue(thrown.message!!.contains("from_data"), thrown.message!!)
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
