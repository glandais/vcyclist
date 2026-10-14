package io.github.glandais.engine

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.TerrainSample
import io.github.glandais.elevation.TileFailureLog
import io.github.glandais.engine.path.BridgeDeckResult
import io.github.glandais.engine.path.BridgeDeckStatus
import io.github.glandais.engine.path.BridgeDeckStep
import io.github.glandais.engine.path.DeckDetectorOptions
import io.github.glandais.engine.path.ElevationCorrectionReport
import io.github.glandais.engine.path.ElevationStep
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointPerDistance
import io.github.glandais.engine.path.TunnelDetectorOptions
import io.github.glandais.engine.path.TunnelResult
import io.github.glandais.engine.path.TunnelStep
import io.github.glandais.engine.path.writtenRange
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Steps 1–3c of the [Enhancer] on their own: the DEM correction with its bridge decks and tunnels,
 * for a caller that runs no physics and chains the rest itself (smoothing, simplification, climbs).
 *
 * The enhancer calls the same code, so the two cannot drift. The steps bring their own grid — the
 * 30 m densification before the DEM read, a refinement after it — rather than trust the caller's:
 * detection reads its own stations every 5 m along the track, but the profile it builds is written
 * at the path's points, so the path has to be dense enough to carry a ramp.
 */
object ElevationCorrection {
    /**
     * Densifies [path] to [Enhancer.PRE_FIX_SPACING_M], reads its elevations from [provider],
     * refines it to between `maxSpacingM / 2` and [maxSpacingM], then rides each bridge deck and
     * flattens each tunnel that [options] allows. The outcome of both is on the returned path's
     * [Path.elevationCorrections]; a tile the two corrections cannot read is reported there, not
     * thrown. A terrain tile that cannot be read is read from the provider's fallback when it has one
     * (`ign-lidar-hd` → Mapterhorn), with a warning in the report and no bridge deck on that run;
     * without a fallback it throws, as [ElevationStep.fixElevation] does.
     *
     * @param maxSpacingM the widest gap between two points of the returned path. The enhancer's 2 m
     *   by default. The cost is 352 bytes a point, so a server bounding its memory may pass more:
     *   5 m is the detectors' own station step and loses nothing they found; 10 m keeps every other
     *   station, which the 150 m smoothing that follows does not tell apart. Past 10 m, ramps start
     *   to fall between two points.
     */
    suspend fun fixAndCorrect(
        path: Path,
        provider: ElevationProvider,
        options: ElevationCorrectionOptions = ElevationCorrectionOptions.DEFAULT,
        maxSpacingM: Double = Enhancer.REFINE_MAX_DISTANCE_M,
    ): Path {
        require(maxSpacingM.isFinite() && maxSpacingM > 0.0) { "maxSpacingM must be positive, got $maxSpacingM" }
        return run(path, provider, options, maxSpacingM / 2.0, maxSpacingM)
    }

    /**
     * Steps 1–3c, the enhancer's entry: with no [provider], steps 1 and 3 only, and a report saying
     * neither correction ran. Always returns a fresh path, stamped.
     */
    internal suspend fun run(
        source: Path,
        provider: ElevationProvider?,
        options: ElevationCorrectionOptions,
        minSpacingM: Double,
        maxSpacingM: Double,
    ): Path {
        // Every tile the provider could not read and answered from its fallback, for this run alone:
        // the provider may be shared with concurrent runs.
        val failures = TileFailureLog()
        return withContext(failures) { runLogged(source, provider, options, minSpacingM, maxSpacingM, failures) }
    }

    private suspend fun runLogged(
        source: Path,
        provider: ElevationProvider?,
        options: ElevationCorrectionOptions,
        minSpacingM: Double,
        maxSpacingM: Double,
        failures: TileFailureLog,
    ): Path {
        // Step 1 : densify before fixElevation so DEM lookups have ~30 m granularity.
        var path = PointPerDistance.compute(source, minDistanceM = -1.0, maxDistanceM = Enhancer.PRE_FIX_SPACING_M)

        // Step 2 : fix elevation, noting where each point was read from.
        var samples: List<TerrainSample>? = null
        if (provider != null) {
            val (fixed, read) = ElevationStep.fixElevationWithOrigins(path, provider)
            path = fixed
            samples = read
        }
        val readPath = path

        // Step 3 : refine before the corrections write their profiles, and before downstream physics.
        path = PointPerDistance.compute(path, minDistanceM = minSpacingM, maxDistanceM = maxSpacingM)

        // Step 3b : bridge decks — only after a DEM correction (a GPX's own <ele>, a barometer's,
        // is never overwritten), and only when the provider carries a surface model. On the dense
        // path, before the smoother, so `sourceElevation` and the reported D+ carry the deck.
        // Switched off, the surface model is never read.
        val surface = provider?.surfaceModel
        val decksAllowed = provider != null && options.bridgeDecks
        var decks: BridgeDeckResult? = null
        if (decksAllowed && provider != null && surface != null) {
            decks = BridgeDeckStep.apply(path, provider, surface)
            // A terrain tile read from the fallback after an error would put a Mapterhorn floor under
            // an IGN surface: no deck is detected on a mix of models, the step reports a failed read.
            if (failures.failedTiles.isNotEmpty() && decks.status != BridgeDeckStatus.FETCH_FAILED) {
                decks = BridgeDeckResult(path, BridgeDeckStatus.FETCH_FAILED, null, emptyList())
            }
            path = decks.path
        }

        // Step 3c : tunnels — after a DEM correction only, on any source (no surface model needed).
        // The deck write moves no coordinate, so 3b's terrain samples are this step's stations when
        // both grids agree; otherwise it reads them itself. It never writes over a deck.
        var tunnels: TunnelResult? = null
        if (options.tunnels && provider != null) {
            val tunnelOptions = TunnelDetectorOptions.DEFAULT
            val deckStep = DeckDetectorOptions.DEFAULT.stepM
            val sameGrid = abs(tunnelOptions.stepM - deckStep) < 1e-9
            val protected = decks?.replacements?.map { it.writtenRange(deckStep) } ?: emptyList()
            tunnels =
                TunnelStep.apply(
                    path,
                    provider,
                    tunnelOptions,
                    samples = decks?.terrain?.takeIf { sameGrid },
                    protected = protected,
                    refreshM = Enhancer.PRE_FIX_SPACING_M,
                )
            path = tunnels.path
        }
        // A step that wrote nothing hands back its input instance: copy before stamping, so the
        // caller's path is never the one stamped (step 1 always copies, but say it here too).
        if (path === source) path = path.copy()
        val terrain = samples?.let { ElevationCorrectionReport.provenance(readPath, it, failures.failedTiles.size) }
        path.elevationCorrections = ElevationCorrectionReport.of(decks, decksAllowed && surface == null, tunnels, terrain)
        return path
    }
}
