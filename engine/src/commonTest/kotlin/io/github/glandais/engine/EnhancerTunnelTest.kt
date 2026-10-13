package io.github.glandais.engine

import io.github.glandais.engine.path.DeckWorld
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointField
import io.github.glandais.engine.path.TunnelWorld
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step 3c of the pipeline: tunnels are flattened after a DEM correction, on any source (no surface
 * model needed), before the smoother; nothing else moves, and a ride without a tunnel comes out bit
 * for bit as without the step.
 */
class EnhancerTunnelTest {
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
        tunnels: Boolean,
        provider: io.github.glandais.elevation.ElevationProvider = TunnelWorld.terrain(),
    ): Path = Enhancer.enhanceCourse(Enhancer.getDefaultCourse(path), options, provider, tunnels)

    /** Every slot of every point, as raw bits. */
    private fun dump(p: Path): List<Long> = (0 until p.size).flatMap { i -> PointField.entries.map { p.get(i, it).toRawBits() } }

    @Test
    fun the_ride_goes_through_the_mountain_not_over_it() =
        runTest {
            val input = TunnelWorld.path(stepM = 25.0)
            val with = run(input, noPhysics, tunnels = true)
            val without = run(input, noPhysics, tunnels = false)
            assertEquals(without.size, with.size)
            for (i in 0 until with.size) {
                assertEquals(without.latitude(i), with.latitude(i), "latitude at $i")
                assertEquals(without.distance(i), with.distance(i), "distance at $i")
            }
            val mid = (0 until with.size).minBy { abs(with.distance(it) - 1_200.0) }
            // Within a metre: the portal station reads the terrain bilinearly, a pixel of the face included.
            assertEquals(TunnelWorld.GROUND_M, with.sourceElevation(mid), 1.0, "the tunnel is in sourceElevation")
            assertTrue(without.sourceElevation(mid) > 100.0, "without the step the mountain stays: ${without.sourceElevation(mid)}")
            assertTrue(with.reportedElevationGain + 100.0 < without.reportedElevationGain, "and the fictitious climb leaves the D+")
            // Far from the mountain, the 150 m kernel cannot see it: identical.
            val far = (0 until with.size).minBy { abs(with.distance(it) - 2_800.0) }
            assertEquals(without.sourceElevation(far), with.sourceElevation(far))
            assertEquals(without.elevation(far), with.elevation(far))
        }

    @Test
    fun the_public_entry_point_runs_the_step() =
        runTest {
            val input = TunnelWorld.path(stepM = 25.0)
            val public = Enhancer.enhanceCourse(Enhancer.getDefaultCourse(input), noPhysics, TunnelWorld.terrain())
            assertContentEquals(dump(run(input, noPhysics, tunnels = true)), dump(public))
        }

    @Test
    fun without_a_dem_correction_the_terrain_is_never_read() =
        runTest {
            val input = TunnelWorld.path(stepM = 25.0)
            val options = noPhysics.copy(fixElevation = false)
            assertContentEquals(dump(run(input, options, tunnels = false)), dump(run(input, options, tunnels = true)))
        }

    @Test
    fun a_ride_without_a_tunnel_is_bit_for_bit_the_same() =
        runTest {
            // DeckWorld's canyon, with its surface model: step 3b replaces the deck, 3c finds nothing.
            val input = DeckWorld.path(stepM = 25.0)
            val provider = DeckWorld.terrain().withSurfaceModel(DeckWorld.surface())
            val full = EnhanceOptions.DEFAULT
            assertContentEquals(
                dump(run(input, full, tunnels = false, provider = provider)),
                dump(run(input, full, tunnels = true, provider = provider)),
            )
        }
}
