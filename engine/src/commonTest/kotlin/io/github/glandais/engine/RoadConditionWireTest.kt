package io.github.glandais.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The one place the road-surface rule is decided, tested once for every door.
 *
 * Until step S8 of `docs/tasks/surface-alignment.md`, `RoadCondition` was the only cross-door enum
 * with no wire catalogue in `commonMain`: each door spelled it *and resolved it* itself, and they
 * disagreed. `--cyclist-max-angle 42 --road-condition wet` gave 42° from the CLI and 15.6° from JS
 * and WASI — the same configuration, two different cornering physics, and nothing failing.
 *
 * These assertions are in `commonTest`, so they run on JVM, JS Node, JS browser and wasmWasi. That
 * is the point: the rule cannot hold on one target and not another.
 */
class RoadConditionWireTest {
    @Test
    fun `every constant has a wire spelling and parses back from it`() {
        for (condition in RoadCondition.entries) {
            assertSame(
                condition,
                RoadCondition.fromWire(condition.wireName),
                "${condition.wireName} must round-trip — adding a constant breaks `wireName` in " +
                    "commonMain, on every target at once, which is what this catalogue is for",
            )
        }
        assertEquals(listOf("dry", "wet"), RoadCondition.wireNames)
    }

    @Test
    fun `parsing is case-insensitive, because that is what people type`() {
        assertSame(RoadCondition.WET, RoadCondition.fromWire("WET"))
        assertSame(RoadCondition.WET, RoadCondition.fromWire("Wet"))
    }

    @Test
    fun `an unknown spelling is null, never a silent fallback to the default`() {
        assertNull(RoadCondition.fromWire("snow"))
        assertNull(RoadCondition.fromWire(""))
    }

    @Test
    fun `no preset asked for leaves the rider exactly as configured`() {
        val configured = Cyclist(maxLeanAngleDeg = 42.0, maxBrakeG = 0.8)

        assertEquals(configured, null.applyTo(configured))
    }

    /**
     * The rule, stated once. A preset overwrites **both** grip limits, whatever the caller set.
     *
     * Both, always, is the substance of ledger R9: a wet road takes grip from cornering *and* from
     * braking, and moving only one would model a rider who cannot corner but can still stop like it
     * is dry.
     */
    @Test
    fun `a preset is the last word, and moves both grip limits together`() {
        val configured = Cyclist(maxLeanAngleDeg = 42.0, maxBrakeG = 0.8)

        val wet = RoadCondition.WET.applyTo(configured)

        assertEquals(RoadCondition.WET.leanAngleDeg, wet.maxLeanAngleDeg, 1e-12)
        assertEquals(RoadCondition.WET.maxBrakeG, wet.maxBrakeG, 1e-12)
        assertEquals(configured.massKg, wet.massKg, "and touches nothing else")
        assertEquals(configured.cd, wet.cd)
    }

    @Test
    fun `the dry preset reproduces the shipped defaults exactly`() {
        // Which is why an unconfigured door and a `dry`-configured one agree, on every surface.
        assertEquals(Cyclist(), RoadCondition.DRY.applyTo(Cyclist()))
        assertEquals(Cyclist().maxLeanAngleDeg, RoadCondition.DRY.leanAngleDeg, 1e-12)
        assertEquals(Cyclist().maxBrakeG, RoadCondition.DRY.maxBrakeG, 1e-12)
    }

    @Test
    fun `the default constant is DRY, and doors read it rather than restating it`() {
        assertSame(RoadCondition.DRY, RoadCondition.DEFAULT)
    }
}
