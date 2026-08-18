package io.github.glandais.engine

import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.FilterOptions
import io.github.glandais.elevation.SmoothingOptions
import io.github.glandais.elevation.elevationProviderConfig
import io.github.glandais.elevation.filterOptions
import io.github.glandais.elevation.smoothingOptions
import io.github.glandais.engine.climb.ClimbOptions
import io.github.glandais.engine.climb.climbOptions
import io.github.glandais.engine.io.CsvOptions
import io.github.glandais.engine.io.JsonOptions
import io.github.glandais.engine.io.csvOptions
import io.github.glandais.engine.io.jsonOptions
import io.github.glandais.engine.physics.CyclistPowerSpec
import io.github.glandais.engine.trajectory.CurvatureOptions
import io.github.glandais.engine.trajectory.RacingLineOptions
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
 * `docs/ledgers/surface-coverage.md` grew a JVM/Java column in the August 2026 audit, precisely
 * because a capability can be ✅ on all four wire doors and out of Java's reach.
 *
 * ## What it checks
 *
 * Two things, and the second is new in step S5 of `docs/tasks/surface-alignment.md`:
 *
 * 1. **Arity** — the widest `@JvmOverloads` overload takes as many parameters as the data class's
 *    primary constructor. It does not check that they are the *same* parameters, and deliberately
 *    not that they are in the same **order**: `coursePhysics` reorders on purpose, because
 *    `@JvmOverloads` truncates from the right and the order therefore decides what a Java caller
 *    can omit. Arity is what actually drifts, since appending is the documented way to evolve
 *    these classes.
 * 2. **Values** — a no-argument call to the factory equals a no-argument construction of the data
 *    class. Arity alone cannot see a factory that restates `10.0` where the class now says `12.0`;
 *    that is the `250 W against the CLI's 280 W` drift, and it is invisible to every key check.
 *
 * The scope also widened: this covered `EngineModelJvm` alone, which left `ClimbDetectorJvm`,
 * `TabularWritersJvm`, `GpxModelJvm` and `ElevationProviderJvm` with no guard at all.
 */
class EngineModelJvmCoverageTest {
    private fun factoryArity(
        name: String,
        facade: String = "io.github.glandais.engine.EngineModelJvm",
    ): Int =
        Class
            .forName(facade)
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
        facade: String = "io.github.glandais.engine.EngineModelJvm",
    ) = assertEquals(
        constructorArity(type),
        factoryArity(factory, facade),
        "$facade.$factory does not expose every parameter of ${type.simpleName} — a field " +
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

    @Test
    fun cyclistPowerSpecFactoryCoversEveryField() = assertCovers("cyclistPowerSpec", CyclistPowerSpec::class.java)

    @Test
    fun courseFactoryCoversEveryField() = assertCovers("course", Course::class.java)

    @Test
    fun coursePhysicsFactoryCoversEveryField() = assertCovers("coursePhysics", CoursePhysics::class.java)

    @Test
    fun climbOptionsFactoryCoversEveryField() =
        assertCovers("climbOptions", ClimbOptions::class.java, "io.github.glandais.engine.climb.ClimbDetectorJvm")

    @Test
    fun csvOptionsFactoryCoversEveryField() =
        assertCovers("csvOptions", CsvOptions::class.java, "io.github.glandais.engine.io.TabularWritersJvm")

    @Test
    fun jsonOptionsFactoryCoversEveryField() =
        assertCovers("jsonOptions", JsonOptions::class.java, "io.github.glandais.engine.io.TabularWritersJvm")

    @Test
    fun smoothingOptionsFactoryCoversEveryField() =
        assertCovers("smoothingOptions", SmoothingOptions::class.java, "io.github.glandais.elevation.ElevationProviderJvm")

    @Test
    fun filterOptionsFactoryCoversEveryField() =
        assertCovers("filterOptions", FilterOptions::class.java, "io.github.glandais.elevation.ElevationProviderJvm")

    @Test
    fun elevationProviderConfigFactoryCoversEveryField() =
        assertCovers(
            "elevationProviderConfig",
            ElevationProviderConfig::class.java,
            "io.github.glandais.elevation.ElevationProviderJvm",
        )

    /**
     * Arity cannot see a restated default, and a restated default is the drift that produces a
     * *wrong answer* rather than an unreachable one. Every factory whose arguments all have
     * defaults is called with none and compared to the data class built the same way.
     *
     * `course` and `coursePhysics` are absent because they take a mandatory argument; their
     * default agreement is pinned from Java in `PowerSpecJavaTest`, where it also proves
     * callability.
     */
    @Test
    fun everyFactoryDefaultsToWhatItsDataClassDefaultsTo() {
        assertEquals(Bike(), bike(), "EngineModelJvm.bike")
        assertEquals(Cyclist(), cyclist(), "EngineModelJvm.cyclist")
        assertEquals(EnhanceOptions(), enhanceOptions(), "EngineModelJvm.enhanceOptions")
        assertEquals(SimplifyPathOptions(), simplifyPathOptions(), "EngineModelJvm.simplifyPathOptions")
        assertEquals(WPrimeBalanceOptions(), wPrimeBalanceOptions(), "EngineModelJvm.wPrimeBalanceOptions")
        assertEquals(CurvatureOptions(), curvatureOptions(), "EngineModelJvm.curvatureOptions")
        assertEquals(RacingLineOptions(), racingLineOptions(), "EngineModelJvm.racingLineOptions")
        assertEquals(CyclistPowerSpec(), cyclistPowerSpec(), "EngineModelJvm.cyclistPowerSpec")
        assertEquals(ClimbOptions(), climbOptions(), "ClimbDetectorJvm.climbOptions")
        assertEquals(CsvOptions(), csvOptions(), "TabularWritersJvm.csvOptions")
        assertEquals(JsonOptions(), jsonOptions(), "TabularWritersJvm.jsonOptions")
        assertEquals(SmoothingOptions(), smoothingOptions(), "ElevationProviderJvm.smoothingOptions")
        assertEquals(FilterOptions(), filterOptions(), "ElevationProviderJvm.filterOptions")
        assertEquals(
            ElevationProviderConfig(),
            elevationProviderConfig(),
            "ElevationProviderJvm.elevationProviderConfig",
        )
    }
}
