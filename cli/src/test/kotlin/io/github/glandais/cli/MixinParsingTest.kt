package io.github.glandais.cli

import io.github.glandais.cli.mixin.BikeMixin
import io.github.glandais.cli.mixin.CyclistMixin
import io.github.glandais.cli.mixin.FilesMixin
import io.github.glandais.cli.mixin.WindMixin
import io.github.glandais.engine.Bike
import io.github.glandais.engine.Course
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.RoadCondition
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.physics.PowerProviderConstant
import io.github.glandais.engine.physics.PowerProviderCriticalPower
import io.github.glandais.engine.physics.PowerProviderDurability
import io.github.glandais.engine.physics.PowerProviderSlewLimited
import io.github.glandais.engine.physics.PowerProviderTerrainPacing
import io.github.glandais.engine.physics.WindProviderConstant
import io.github.glandais.engine.physics.WindProviderNone
import picocli.CommandLine
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Option parsing only — picocli can populate a command from an argument array without executing
 * anything, so none of this touches the filesystem or the network.
 */
class MixinParsingTest {
    /** A throwaway command carrying all four mixins, which is how g17's subcommands will use them. */
    @CommandLine.Command(name = "test")
    class Harness {
        @field:CommandLine.Mixin
        var cyclist = CyclistMixin()

        @field:CommandLine.Mixin
        var bike = BikeMixin()

        @field:CommandLine.Mixin
        var wind = WindMixin()

        @field:CommandLine.Mixin
        var files = FilesMixin()
    }

    private fun parse(vararg args: String): Harness {
        val harness = Harness()
        CommandLine(harness).parseArgs(*args)
        return harness
    }

    // ---- Root command --------------------------------------------------------

    @Test
    fun `case 01 — help prints usage and exits zero`() {
        val out = StringWriter()
        val cmd = CommandLine(RootCommand()).setOut(PrintWriter(out))
        val code = cmd.execute("--help")
        assertEquals(0, code)
        assertContains(out.toString(), "Usage: vcyclist")
    }

    @Test
    fun `case 02 — version reports the build version`() {
        val out = StringWriter()
        val code = CommandLine(RootCommand()).setOut(PrintWriter(out)).execute("--version")
        assertEquals(0, code)
        assertContains(out.toString(), "vcyclist")
        // The generated resource must actually have been found; "unknown" means the build wiring
        // broke, which would otherwise only show up in a release.
        assertTrue("unknown" !in out.toString(), "version resource not found: ${out.toString().trim()}")
    }

    // ---- Cyclist -------------------------------------------------------------

    @Test
    fun `case 03 — cyclist defaults equal the library defaults`() {
        val cyclist = parse().cyclist.toCyclist()
        assertEquals(Cyclist(), cyclist, "an unconfigured CLI must build exactly the library default")
    }

    @Test
    fun `case 04 — a single option is applied`() {
        assertEquals(75.0, parse("--cyclist-weight", "75").cyclist.toCyclist().massKg)
    }

    @Test
    fun `case 05 — every cyclist option is carried through`() {
        val mixin =
            parse(
                "--cyclist-weight",
                "72.5",
                "--cyclist-power",
                "310",
                "--cyclist-max-brake",
                "0.8",
                "--cyclist-cd",
                "0.62",
                "--cyclist-a",
                "0.42",
                "--cyclist-max-angle",
                "40",
                "--cyclist-max-speed",
                "85",
                "--cyclist-harmonics",
            ).cyclist

        val cyclist = mixin.toCyclist()
        assertEquals(72.5, cyclist.massKg)
        assertEquals(0.8, cyclist.maxBrakeG)
        assertEquals(0.62, cyclist.cd)
        assertEquals(0.42, cyclist.frontalAreaM2)
        assertEquals(40.0, cyclist.maxLeanAngleDeg)
        assertEquals(85.0, cyclist.maxSpeedKmH)
        // Power is a strategy in vcyclist, not a Cyclist field.
        val power = mixin.toPowerProvider()
        assertTrue(power is PowerProviderConstant, "expected a constant power provider, got $power")
        assertEquals(310.0, power.power)
        assertTrue(power.useHarmonics)
    }

    // ---- Bike ----------------------------------------------------------------

    @Test
    fun `case 06 — every bike option is carried through, and defaults match the library`() {
        assertEquals(Bike(), parse().bike.toBike(), "an unconfigured CLI must build the library default bike")

        val bike =
            parse(
                "--bike-crr",
                "0.0055",
                "--bike-inertia-front",
                "0.06",
                "--bike-inertia-rear",
                "0.08",
                "--bike-wheel-radius",
                "0.68",
                "--bike-efficiency",
                "0.95",
            ).bike.toBike()
        assertEquals(0.0055, bike.crr)
        assertEquals(0.06, bike.inertiaFront)
        assertEquals(0.08, bike.inertiaRear)
        assertEquals(0.68, bike.wheelRadiusM)
        assertEquals(0.95, bike.efficiency)
    }

    // ---- Wind ----------------------------------------------------------------

    @Test
    fun `case 07 — wind speed and direction build a constant provider`() {
        val provider = parse("--wind-speed", "5.5", "--wind-direction", "90").wind.toWindProvider()
        assertTrue(provider is WindProviderConstant, "expected a constant wind provider, got $provider")
        // Asserted through the public contract rather than the private field — that is what
        // callers actually see, and it exercises the conversion end to end.
        val wind = provider.wind(Course(path = Path(1)), Path(1), 0)
        assertEquals(5.5, wind.speedMS)
        // Degrees at the boundary, radians inside the engine.
        assertEquals(PI / 2, wind.directionRad, 1e-12)
    }

    @Test
    fun `case 08 — no wind option means no wind at all`() {
        assertEquals(WindProviderNone, parse().wind.toWindProvider())
        // A direction on its own is not enough — without a speed there is no wind.
        assertEquals(WindProviderNone, parse("--wind-direction", "180").wind.toWindProvider())
        val wind = WindProviderNone.wind(Course(path = Path(1)), Path(1), 0)
        assertEquals(0.0, wind.speedMS)
    }

    // ---- Errors --------------------------------------------------------------

    @Test
    fun `case 09 — an unknown option fails with a non-zero exit code`() {
        val err = StringWriter()
        val code = CommandLine(Harness()).setErr(PrintWriter(err)).execute("--not-an-option")
        assertNotEquals(0, code)
        assertContains(err.toString(), "Unknown option")
    }

    @Test
    fun `case 10 — a non-numeric value is reported clearly`() {
        val err = StringWriter()
        val code = CommandLine(Harness()).setErr(PrintWriter(err)).execute("--cyclist-weight", "abc")
        assertNotEquals(0, code)
        assertContains(err.toString(), "--cyclist-weight")
    }

    // ---- Road condition ------------------------------------------------------

    @Test
    fun `case 05b — road condition defaults to dry and changes both grip limits`() {
        assertEquals(RoadCondition.DRY, parse().cyclist.roadCondition)
        assertEquals(Cyclist(), parse().cyclist.toCyclist(), "dry must be the library default")

        val wet = parse("--road-condition", "wet").cyclist.toCyclist()
        assertEquals(RoadCondition.WET.leanAngleDeg, wet.maxLeanAngleDeg, 1e-12)
        assertEquals(RoadCondition.WET.maxBrakeG, wet.maxBrakeG, 1e-12)
        assertNotEquals(Cyclist().maxLeanAngleDeg, wet.maxLeanAngleDeg)
        assertNotEquals(Cyclist().maxBrakeG, wet.maxBrakeG)
    }

    @Test
    fun `case 05c — an explicit angle or brake value overrides the preset`() {
        val cyclist =
            parse("--road-condition", "wet", "--cyclist-max-angle", "40").cyclist.toCyclist()
        assertEquals(40.0, cyclist.maxLeanAngleDeg, "the explicit option must win")
        assertEquals(RoadCondition.WET.maxBrakeG, cyclist.maxBrakeG, 1e-12, "…and only for that value")
    }

    @Test
    fun `case 05d — an unknown road condition is rejected`() {
        val err = StringWriter()
        val code =
            CommandLine(Harness())
                .setErr(PrintWriter(err))
                .execute("--road-condition", "snow")
        assertNotEquals(0, code)
        assertContains(err.toString(), "--road-condition")
    }

    // ---- Durability ----------------------------------------------------------

    @Test
    fun `case 05e — the power model selects the provider`() {
        assertTrue(parse().cyclist.toPowerProvider() is PowerProviderConstant)
        assertEquals(EngineConstants.DEFAULT_CRITICAL_POWER_W, parse().cyclist.criticalPowerW)
        assertEquals(EngineConstants.DEFAULT_W_PRIME_J, parse().cyclist.wPrimeJ)

        val durability =
            parse("--cyclist-model", "durability", "--cyclist-cp", "300").cyclist.toPowerProvider()
        assertTrue(durability is PowerProviderDurability)
        assertEquals(300.0, durability.criticalPowerW)
        assertEquals(EngineConstants.DEFAULT_CYCLIST_POWER_W, durability.powerW)

        val cp =
            parse("--cyclist-model", "critical-power", "--cyclist-wprime", "25000")
                .cyclist
                .toPowerProvider()
        assertTrue(cp is PowerProviderCriticalPower)
        assertEquals(25_000.0, cp.wPrimeJ)
    }

    @Test
    fun `case 05g — an unknown power model is rejected`() {
        val err = StringWriter()
        val code =
            CommandLine(Harness()).setErr(PrintWriter(err)).execute("--cyclist-model", "sprint")
        assertNotEquals(0, code)
        assertContains(err.toString(), "--cyclist-model")
    }

    @Test
    fun `case 05f — the slew limiter wraps whatever provider was chosen`() {
        assertEquals(0.0, parse().cyclist.maxSlewWPerS, "off unless asked for")
        assertTrue(parse().cyclist.toPowerProvider() is PowerProviderConstant)

        val limited = parse("--cyclist-slew", "40").cyclist.toPowerProvider()
        assertTrue(limited is PowerProviderSlewLimited)
        assertEquals(40.0, limited.maxSlewWPerS)
        assertTrue(limited.delegate is PowerProviderConstant)

        val both =
            parse("--cyclist-slew", "40", "--cyclist-model", "durability").cyclist.toPowerProvider()
        assertTrue(both is PowerProviderSlewLimited)
        assertTrue(both.delegate is PowerProviderDurability, "it must wrap, not replace")
    }

    @Test
    fun `case 05h — pacing and the slew limit wrap the model in order`() {
        assertTrue(parse().cyclist.toPowerProvider() is PowerProviderConstant, "off by default")

        val paced = parse("--cyclist-pacing").cyclist.toPowerProvider()
        assertTrue(paced is PowerProviderTerrainPacing)
        assertTrue(paced.delegate is PowerProviderConstant)

        val all =
            parse("--cyclist-pacing", "--cyclist-slew", "50", "--cyclist-model", "critical-power")
                .cyclist
                .toPowerProvider()
        assertTrue(all is PowerProviderSlewLimited, "the rate limit must have the last word")
        val inner = all.delegate
        assertTrue(inner is PowerProviderTerrainPacing)
        assertTrue(inner.delegate is PowerProviderCriticalPower)
    }

    // ---- Drift guard ---------------------------------------------------------

    @Test
    fun `case 11 — every CLI default is the EngineConstants value, field by field`() {
        // THE test that stops the CLI and the library drifting apart. Anyone changing a constant
        // in one place without the other fails here rather than shipping two different physics.
        val cyclist = parse().cyclist
        assertEquals(EngineConstants.DEFAULT_CYCLIST_MASS_KG, cyclist.massKg)
        assertEquals(EngineConstants.DEFAULT_CYCLIST_POWER_W, cyclist.powerW)
        assertEquals(EngineConstants.DEFAULT_DRAG_COEFFICIENT, cyclist.cd)
        assertEquals(EngineConstants.DEFAULT_FRONTAL_AREA_M2, cyclist.frontalAreaM2)
        assertEquals(EngineConstants.DEFAULT_MAX_SPEED_KMH, cyclist.maxSpeedKmH)
        // Braking and lean angle are `null` until given, so the road-condition preset can supply
        // them — they are asserted on the built Cyclist, where the resolution has happened.
        assertEquals(EngineConstants.DEFAULT_MAX_BRAKE_G, cyclist.toCyclist().maxBrakeG)
        assertEquals(EngineConstants.DEFAULT_MAX_LEAN_ANGLE_DEG, cyclist.toCyclist().maxLeanAngleDeg)

        val bike = parse().bike
        assertEquals(EngineConstants.DEFAULT_CRR, bike.crr)
        assertEquals(EngineConstants.DEFAULT_INERTIA_FRONT, bike.inertiaFront)
        assertEquals(EngineConstants.DEFAULT_INERTIA_REAR, bike.inertiaRear)
        assertEquals(EngineConstants.DEFAULT_WHEEL_RADIUS_M, bike.wheelRadiusM)
        assertEquals(EngineConstants.DEFAULT_DRIVETRAIN_EFFICIENCY, bike.efficiency)
    }

    @Test
    fun `case 12 — the two lean and speed defaults come from the library, not the CLI`() {
        // These two are the ones most likely to be re-typed as a literal here. Pinned so they
        // stay a decision rather than a copy that can drift.
        val cyclist = parse().cyclist
        assertEquals(35.0, cyclist.toCyclist().maxLeanAngleDeg, "the library value must win")
        assertEquals(100.0, cyclist.maxSpeedKmH, "the library value must win")
    }

    // ---- Files ---------------------------------------------------------------

    @Test
    fun `case 13 — GPX files are collected recursively, sorted, and non-GPX skipped`() {
        val root =
            File.createTempFile("vcyclist-cli", "").let {
                it.delete()
                it.mkdirs()
                it
            }
        try {
            File(root, "b.gpx").writeText("<gpx/>")
            File(root, "a.gpx").writeText("<gpx/>")
            File(root, "notes.txt").writeText("ignore me")
            val nested = File(root, "nested").also { it.mkdirs() }
            File(nested, "c.gpx").writeText("<gpx/>")

            val skipped = mutableListOf<String>()
            val files = parse(root.absolutePath).files.collectGpxFiles { f, why -> skipped.add("${f.name}: $why") }

            assertEquals(listOf("a.gpx", "b.gpx", "c.gpx"), files.map { it.name }, "sorted, recursive, GPX only")
            assertEquals(listOf("notes.txt: is not a GPX file"), skipped)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `case 14 — a missing input path is reported, not fatal`() {
        val skipped = mutableListOf<String>()
        val files = parse("/definitely/not/here.gpx").files.collectGpxFiles { f, why -> skipped.add("${f.name}: $why") }
        assertEquals(emptyList(), files)
        assertEquals(listOf("here.gpx: does not exist"), skipped)
    }

    @Test
    fun `case 15 — output and cache folders have defaults and are overridable`() {
        assertEquals(File("output"), parse().files.output)
        assertEquals(File("/tmp/out"), parse("-o", "/tmp/out").files.output)
        assertEquals(File("/tmp/cache"), parse("--cache", "/tmp/cache").files.cache)
    }
}
