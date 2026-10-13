package io.github.glandais.engine

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.engine.path.DeckWorld
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.TunnelWorld

/**
 * What the Java pin tests cannot express themselves.
 *
 * A Java test cannot call a Kotlin constructor that relies on default arguments — which is the
 * very gap the `*Jvm.kt` facades exist to close, so a test asserting "the factory agrees with the
 * data class" has to get the data-class side from Kotlin. Keeping that here rather than widening
 * the facade avoids adding public API whose only caller is a test.
 */
object JvmBridgeFixtures {
    /** `CoursePhysics(course)` with every provider left at its default. */
    @JvmStatic
    fun defaultCoursePhysics(course: Course): CoursePhysics = CoursePhysics(course)

    /** `Course(path)` with the default cyclist and bike. */
    @JvmStatic
    fun defaultCourse(path: Path): Course = Course(path)

    /** [TunnelWorld]'s route through a mountain (its default members are out of Java's reach). */
    @JvmStatic
    fun tunnelWorldPath(): Path = TunnelWorld.path(stepM = 25.0)

    /** [TunnelWorld]'s terrain model, no surface model: a tunnel, and bridge decks unavailable. */
    @JvmStatic
    fun tunnelWorldTerrain(): ElevationProvider = TunnelWorld.terrain()

    /** [DeckWorld]'s route over a bridge. */
    @JvmStatic
    fun deckWorldPath(): Path = DeckWorld.path(stepM = 25.0)

    /** [DeckWorld]'s terrain carrying a surface model whose every tile read fails. */
    @JvmStatic
    fun deckWorldTerrainWithFailingSurface(): ElevationProvider =
        DeckWorld.terrain().withSurfaceModel(DeckWorld.surface { error("HTTP 429") })
}
