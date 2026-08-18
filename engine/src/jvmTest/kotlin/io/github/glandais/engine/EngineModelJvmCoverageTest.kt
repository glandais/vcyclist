package io.github.glandais.engine

import io.github.glandais.engine.trajectory.CurvatureOptions
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fails when a factory of `EngineModelJvm` falls behind the data class it exists to build.
 *
 * ## Why this is needed
 *
 * The facade calls its data-class constructors **positionally**, so appending a parameter to
 * `Bike` or `EnhanceOptions` leaves this file compiling and quietly taking the new field's
 * default. That resilience is deliberate — it is what keeps existing Java call sites source
 * compatible — but it means nothing at all fails when a capability stops being reachable from
 * Java. It had already happened four times when this test was written: `wPrimeBalance`,
 * `curvature` and `racingLine` on `EnhanceOptions`, and `maxPedalingLeanAngleDeg` on `Bike`.
 *
 * `docs/ledgers/surface-coverage.md` cannot catch it either: its columns are core / CLI / JS /
 * WASI / demo, and Java is not one of them.
 *
 * ## What it checks
 *
 * That the widest `@JvmOverloads` overload takes as many parameters as the data class's primary
 * constructor. It does not check that they are the *same* parameters — a rename would slip
 * through — but arity is what actually drifts, because appending is the documented way to evolve
 * these classes.
 */
class EngineModelJvmCoverageTest {
    private fun factoryArity(name: String): Int =
        Class
            .forName("io.github.glandais.engine.EngineModelJvm")
            .declaredMethods
            .filter { it.name == name && !it.isSynthetic }
            .maxOf { it.parameterCount }

    /** The full-arity constructor, skipping the synthetic one `@JvmOverloads` adds for defaults. */
    private fun constructorArity(type: Class<*>): Int =
        type.declaredConstructors
            .filter { !it.isSynthetic }
            .maxOf { it.parameterCount }

    private fun assertCovers(
        factory: String,
        type: Class<*>,
    ) = assertEquals(
        constructorArity(type),
        factoryArity(factory),
        "EngineModelJvm.$factory does not expose every parameter of ${type.simpleName} — a field " +
            "was appended without widening the factory, so it is unreachable from Java",
    )

    @Test
    fun bikeFactoryCoversEveryField() = assertCovers("bike", Bike::class.java)

    @Test
    fun cyclistFactoryCoversEveryField() = assertCovers("cyclist", Cyclist::class.java)

    @Test
    fun enhanceOptionsFactoryCoversEveryField() = assertCovers("enhanceOptions", EnhanceOptions::class.java)

    @Test
    fun simplifyPathOptionsFactoryCoversEveryField() = assertCovers("simplifyPathOptions", SimplifyPathOptions::class.java)

    @Test
    fun wPrimeBalanceOptionsFactoryCoversEveryField() = assertCovers("wPrimeBalanceOptions", WPrimeBalanceOptions::class.java)

    @Test
    fun curvatureOptionsFactoryCoversEveryField() = assertCovers("curvatureOptions", CurvatureOptions::class.java)

    /**
     * `racingLineOptions` is **deliberately** partial: it exposes the three knobs the CLI, JS and
     * WASI doors expose, not all 23 of `RacingLineOptions`. Pinned so that widening it becomes a
     * decision rather than an accident.
     */
    @Test
    fun racingLineFactoryExposesTheThreeCrossDoorKnobs() = assertEquals(3, factoryArity("racingLineOptions"))
}
