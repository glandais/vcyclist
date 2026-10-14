package io.github.glandais.engine.wasi

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.ElevationSource
import io.github.glandais.elevation.HostTileSource
import io.github.glandais.elevation.TileEncoding
import io.github.glandais.elevation.provider
import io.github.glandais.engine.path.DeckWorld
import io.github.glandais.engine.path.ElevationCorrectionStatus
import io.github.glandais.engine.path.ElevationCorrections
import io.github.glandais.engine.path.TunnelWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * E7 on the WASI door, through the bodies of `vcEnhance` / `vcEnhanceWithCourse` extracted for
 * the purpose (the w03 idiom): an accepted key is not a used key, so the pipeline has to run.
 *
 * The fixture DEM replaces the host-served provider through `providerFor` — under the KGP test
 * runner there is no `fetch_tile` import to serve it, and nothing here may reach `hostTileFetcher`
 * (the binary would then fail to instantiate). Why decks are `unavailable` on this door: the
 * exports build their provider through `wasiElevationProvider`, a bare provider that never gets a
 * surface model because `fetch_tile` names no layer (decision of 2026-10-13) — pinned below with
 * IGN at z15, the one configuration that carries one on every other door. The fixture terrain
 * has no surface model either, which is the same situation.
 */
class ElevationCorrectionsWasiTest {
    private val base = """"fixElevation":true,"computeMaxSpeeds":false,"virtualizeTrack":false"""

    private fun report(
        options: String,
        course: Boolean = false,
    ): ElevationCorrections {
        val path = TunnelWorld.path(stepM = 25.0)
        val enhanced =
            if (course) {
                enhanceCourseFromJson(path, parseJsonObject("""{"options":{$options}}""")) { TunnelWorld.terrain() }
            } else {
                enhanceFromJson(path, parseJsonObject("{$options}")) { TunnelWorld.terrain() }
            }
        return assertNotNull(enhanced.elevationCorrections, "the enhancer stamps a report")
    }

    @Test
    fun `tunnels run on WASI, since step 3c reads the terrain model only`() {
        val r = report(base)
        assertEquals(ElevationCorrectionStatus.APPLIED, r.tunnels)
        assertEquals(1, r.tunnelsApplied)
    }

    @Test
    fun `tunnelsEnabled false reaches the enhancer`() {
        assertEquals(ElevationCorrectionStatus.NOT_RUN, report("""$base,"tunnelsEnabled":false""").tunnels)
        assertEquals(ElevationCorrectionStatus.NOT_RUN, report("""$base,"tunnelsEnabled":false""", course = true).tunnels)
        assertEquals(ElevationCorrectionStatus.APPLIED, report(base, course = true).tunnels)
    }

    @Test
    fun `bridgeDecksEnabled is accepted and reported unavailable`() {
        assertEquals(ElevationCorrectionStatus.UNAVAILABLE, report("""$base,"bridgeDecksEnabled":true""").bridgeDecks)
        assertEquals(ElevationCorrectionStatus.NOT_RUN, report("""$base,"bridgeDecksEnabled":false""").bridgeDecks)
    }

    @Test
    fun `without a DEM correction the report says neither step ran`() {
        val enhanced = enhanceFromJson(TunnelWorld.path(stepM = 25.0), null) { null }
        val r = assertNotNull(enhanced.elevationCorrections)
        assertEquals(ElevationCorrectionStatus.NOT_RUN, r.bridgeDecks)
        assertEquals(ElevationCorrectionStatus.NOT_RUN, r.tunnels)
        assertNull(TunnelWorld.path(stepM = 25.0).elevationCorrections, "a parsed path has no report: the export writes null")
    }

    @Test
    fun `the provider this door builds has no surface model, even IGN at z15`() {
        val ign = ElevationSource.IGN_LIDAR_HD
        assertNotNull(ign.provider().surfaceModel, "precondition: elsewhere IGN at its own zoom carries the MNS")
        val config = ign.config().copy(tileUrlTemplate = HostTileSource.URL_TEMPLATE)
        assertNull(wasiElevationProvider(config, { error("no tile is read") }, ElevationSource.IGN_LIDAR_HD).surfaceModel)
    }

    /** TunnelWorld's terrain from a template the catalogue does not know, declared as [source]. */
    private fun hostTerrain(source: ElevationSource?) =
        ElevationProvider(
            ElevationProviderConfig(
                zoomLevel = 15,
                tileSize = 256,
                tileUrlTemplate = "test://wms?BBOX={bbox}&a=1",
                encoding = TileEncoding.BIL32,
            ),
        ) { url -> DeckWorld.tile(url, TunnelWorld::mnt) }.servingSource(source)

    @Test
    fun `tunnelScope reaches the enhancer, and a declared source is measured terrain`() {
        fun run(
            options: String,
            terrain: ElevationProvider,
        ): ElevationCorrections =
            assertNotNull(enhanceFromJson(TunnelWorld.path(stepM = 25.0), parseJsonObject("{$options}")) { terrain }.elevationCorrections)

        val unknown = run(base, hostTerrain(null))
        assertEquals(ElevationCorrectionStatus.NONE_FOUND, unknown.tunnels, "an undeclared host is unmeasured terrain")
        assertEquals(1, unknown.tunnelsOutOfScope)
        assertEquals(ElevationCorrectionStatus.APPLIED, run("""$base,"tunnelScope":"everywhere"""", hostTerrain(null)).tunnels)
        assertEquals(ElevationCorrectionStatus.APPLIED, run(base, hostTerrain(ElevationSource.MAPTERHORN)).tunnels, "Mapterhorn in France")
    }
}
