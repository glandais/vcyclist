package io.github.glandais.engine

import io.github.glandais.engine.path.DeckWorld
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointField
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step 3b of the pipeline: the bridge-deck step runs only after a DEM correction, and only with a
 * provider that carries a surface model; it moves elevations, never positions.
 */
class EnhancerBridgeDeckTest {
    private val noPhysics =
        EnhanceOptions(
            computeMaxSpeeds = false,
            virtualizeTrack = false,
            computeOnePointPerSecond = false,
            simplifyPath = SimplifyPathOptions(enabled = false),
        )

    private suspend fun run(
        path: Path,
        options: EnhanceOptions,
        withSurface: Boolean,
    ): Path {
        val terrain = DeckWorld.terrain()
        val provider = if (withSurface) terrain.withSurfaceModel(DeckWorld.surface()) else terrain
        return Enhancer.enhanceCourse(Enhancer.getDefaultCourse(path), options, provider)
    }

    /** Every slot of every point, as raw bits. */
    private fun dump(p: Path): List<Long> = (0 until p.size).flatMap { i -> PointField.entries.map { p.get(i, it).toRawBits() } }

    @Test
    fun a_surface_model_lifts_the_deck_and_moves_only_elevations() =
        runTest {
            val input = DeckWorld.path(stepM = 25.0)
            val with = run(input, noPhysics, withSurface = true)
            val without = run(input, noPhysics, withSurface = false)
            assertEquals(without.size, with.size)
            for (i in 0 until with.size) {
                assertEquals(without.latitude(i), with.latitude(i), "latitude at $i")
                assertEquals(without.longitude(i), with.longitude(i), "longitude at $i")
                assertEquals(without.distance(i), with.distance(i), "distance at $i")
                assertEquals(without.time(i), with.time(i), "time at $i")
            }
            val mid = (0 until with.size).minBy { kotlin.math.abs(with.distance(it) - 1_200.0) }
            assertEquals(DeckWorld.DECK_M, with.sourceElevation(mid), 0.05, "the deck is in sourceElevation")
            assertTrue(without.sourceElevation(mid) < 1.0, "without a surface the valley stays: ${without.sourceElevation(mid)}")
            assertTrue(with.elevation(mid) > without.elevation(mid) + 10.0, "and in the smoothed profile")
            // Far from the bridge, the 150 m kernel cannot see it: identical.
            val far = (0 until with.size).minBy { kotlin.math.abs(with.distance(it) - 2_800.0) }
            assertEquals(without.sourceElevation(far), with.sourceElevation(far))
            assertEquals(without.elevation(far), with.elevation(far))
        }

    @Test
    fun without_a_dem_correction_the_surface_is_never_read() =
        runTest {
            val input = DeckWorld.path(stepM = 25.0)
            val options = noPhysics.copy(fixElevation = false)
            val with = run(input, options, withSurface = true)
            val without = run(input, options, withSurface = false)
            assertContentEquals(dump(without), dump(with))
        }

    @Test
    fun the_one_argument_call_still_skips_every_elevation_lookup() =
        runTest {
            val input = DeckWorld.path(stepM = 25.0)
            val default = Enhancer.enhanceCourseDefault(input, null, noPhysics)
            val explicit = Enhancer.enhanceCourse(Enhancer.getDefaultCourse(input), noPhysics.copy(fixElevation = false))
            assertContentEquals(dump(explicit), dump(default))
        }

    @Test
    fun a_surface_tile_that_cannot_be_read_leaves_the_valley_bit_for_bit() =
        runTest {
            // FETCH_FAILED is all or nothing, and the enhancer does not report it (E7's to surface):
            // the ride must at least be exactly the one without a surface model.
            val input = DeckWorld.path(stepM = 25.0)
            val broken = DeckWorld.terrain().withSurfaceModel(DeckWorld.surface { error("HTTP 429") })
            val failed = Enhancer.enhanceCourse(Enhancer.getDefaultCourse(input), noPhysics, broken)
            val without = run(input, noPhysics, withSurface = false)
            assertContentEquals(dump(without), dump(failed))
        }
}
