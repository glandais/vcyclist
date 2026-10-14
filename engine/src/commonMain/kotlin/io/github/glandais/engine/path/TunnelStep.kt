package io.github.glandais.engine.path

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.TerrainOrigin
import io.github.glandais.engine.TunnelScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

/** What [TunnelStep.apply] did. */
internal enum class TunnelStatus {
    /** At least one tunnel was flattened onto the line between its portals. */
    APPLIED,

    /** The terrain was read and no tunnel found. */
    NO_TUNNEL,

    /** A tile could not be read (offline, a transient server error): the path is left as it was. */
    FETCH_FAILED,
}

/**
 * Result of [TunnelStep.apply].
 *
 * @property path the input instance itself unless [status] is [TunnelStatus.APPLIED]
 * @property spans the tunnels written back, station indices on the step's grid
 * @property dropped tunnels the detector found but the step refused: a deck of step 3b lies wholly
 *   between their portals, so the "mountain" is the pylons or piles of a bridge the terrain model kept
 * @property written the distance ranges whose points the step may have rewritten: each tunnel and its
 *   two refresh margins (see [TunnelStep]); every point outside them keeps its elevation bit for bit
 */
internal class TunnelResult(
    val path: Path,
    val status: TunnelStatus,
    val spans: List<TunnelSpan>,
    val written: List<ClosedFloatingPointRange<Double>> = emptyList(),
    val dropped: List<TunnelSpan> = emptyList(),
    /** Tunnels detected but left as read: some station around them is not a measured terrain. */
    val outOfScope: List<TunnelSpan> = emptyList(),
)

/**
 * Step 3c of the enhancer (E4 of `docs/archive/tasks/elevation-bridge-decks.md`): the road through a tunnel
 * rides the straight line between its portals, not the mountain above it.
 *
 * 1. Stations every [TunnelDetectorOptions.stepM] along the path ([alongTrackStations]), and the
 *    terrain model there — [terrain] read in **reverse** order (the path was just corrected forward
 *    through the same tile cache, so its last tiles are the warm ones), or the samples step 3b
 *    already read ([BridgeDeckResult.terrain]), passed as [apply]'s `samples`.
 * 2. [TunnelDetector.detect].
 * 3. On a copy of the path, every point between two portals gets the line between the **terrain at
 *    the portal stations** — not the path's own elevation there, which step 2 sampled every
 *    `refreshM` (30 m) and so mixes the cut face into the foot (+0.7 m on the synthetic portal of
 *    the tests, up to the face grade × the spacing on a real one). For the same reason the points
 *    within `refreshM` outside each portal get the terrain interpolated on the station grid, so no
 *    trace of the face survives just outside the tunnel. Points inside a [protected] range (a deck
 *    step 3b wrote) are never written, and a portal there is pinned to the path's own elevation,
 *    the deck (a tunnel opening onto a bridge). A tunnel with a deck **wholly between its portals**
 *    is dropped instead ([TunnelResult.dropped]): the terrain model kept the bridge's pylons and the
 *    detector took them for a mountain (Normandie, Millau); writing its line around the deck would
 *    leave a vertical step at each deck edge. Every other point is left bit for bit; derived data is
 *    recomputed.
 *
 * It runs on **any** terrain model, IGN LiDAR HD, Mapterhorn or a WASI host's tiles: it needs no
 * surface model. **A tile read never fails the ride**: when [terrain] throws, the input path comes
 * back with [TunnelStatus.FETCH_FAILED] (a cancellation propagates). Only the read is guarded:
 * detection and writing run outside the `catch`. The same input instance comes back whenever no
 * tunnel is written.
 */
internal object TunnelStep {
    /** How far beyond each portal the terrain must be measured for [TunnelScope.MEASURED_TERRAIN]. */
    const val SCOPE_MARGIN_M: Double = 100.0

    /**
     * @param samples the terrain at the stations of [path], when step 3b already read it
     * @param protected distance ranges the step must not write (decks of step 3b)
     * @param refreshM the margin outside each portal re-read from the station terrain: the widest
     *   spacing step 2 sampled the terrain at, `Enhancer.PRE_FIX_SPACING_M` — no default, so a
     *   caller replaying the pipeline cannot drift from step 1
     */
    suspend fun apply(
        path: Path,
        terrain: ElevationProvider,
        options: TunnelDetectorOptions = TunnelDetectorOptions.DEFAULT,
        samples: DoubleArray? = null,
        protected: List<ClosedFloatingPointRange<Double>> = emptyList(),
        refreshM: Double,
        scope: TunnelScope = TunnelScope.MEASURED_TERRAIN,
        origins: List<TerrainOrigin>? = null,
    ): TunnelResult {
        if (path.size < 2) return TunnelResult(path, TunnelStatus.NO_TUNNEL, emptyList())
        val stations = alongTrackStations(path, options.stepM)
        val mnt: DoubleArray
        val read: List<TerrainOrigin>
        if (samples != null) {
            require(samples.size == stations.size) {
                "terrain samples: ${samples.size} for ${stations.size} stations at ${options.stepM} m"
            }
            requireNotNull(origins) { "terrain samples come with their origins" }
            mnt = samples
            read = origins
        } else {
            try {
                val sampled = terrain.setElevationsWithOrigin(stations.asReversed()).asReversed()
                mnt = DoubleArray(sampled.size) { sampled[it].elevation }
                read = sampled.map { it.origin }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // ElevationCalculator wraps a cancellation in an IllegalStateException too (see
                // BridgeDeckStep): ask the job itself before reporting a failed fetch.
                currentCoroutineContext().ensureActive()
                return TunnelResult(path, TunnelStatus.FETCH_FAILED, emptyList())
            }
        }
        val measured = if (scope == TunnelScope.EVERYWHERE) null else BooleanArray(read.size) { read[it].measured }
        return flatten(path, mnt, stations.map { it.latitude }, stations.map { it.longitude }, options, protected, refreshM, measured)
    }

    /** Steps 2–3 on the terrain [mnt] read at the stations ([lat], [lon]) of [path]. Never guarded. */
    internal fun flatten(
        path: Path,
        mnt: DoubleArray,
        lat: List<Double>,
        lon: List<Double>,
        options: TunnelDetectorOptions,
        protected: List<ClosedFloatingPointRange<Double>> = emptyList(),
        refreshM: Double,
        measured: BooleanArray? = null,
    ): TunnelResult {
        val found = TunnelDetector.detect(TunnelProfile(mnt, lat.toDoubleArray(), lon.toDoubleArray()), options)
        val (kept, dropped) =
            found.partition { t -> protected.none { r -> r.start > t.startM && r.endInclusive < t.endM } }
        // The scope: every station from SCOPE_MARGIN_M before a portal to as far after the other
        // (the reach of the face test) must be a measured terrain, or the tunnel is left as read.
        val margin = (SCOPE_MARGIN_M / options.stepM).toInt()
        val (spans, outOfScope) =
            kept.partition { t ->
                measured == null ||
                    ((t.first - margin).coerceAtLeast(0)..(t.last + margin).coerceAtMost(measured.size - 1)).all { measured[it] }
            }
        if (spans.isEmpty()) {
            return TunnelResult(path, TunnelStatus.NO_TUNNEL, emptyList(), dropped = dropped, outOfScope = outOfScope)
        }
        val written = spans.map { (it.startM - refreshM)..(it.endM + refreshM) }
        return TunnelResult(
            write(path, spans, mnt, options.stepM, protected, refreshM),
            TunnelStatus.APPLIED,
            spans,
            written,
            dropped,
            outOfScope,
        )
    }

    /** Step 3: a copy of [path] with each tunnel of [spans] on the line between its portals. */
    internal fun write(
        path: Path,
        spans: List<TunnelSpan>,
        mnt: DoubleArray,
        stepM: Double,
        protected: List<ClosedFloatingPointRange<Double>> = emptyList(),
        refreshM: Double,
    ): Path {
        fun isProtected(d: Double) = protected.any { d in it }

        fun inTunnel(d: Double) = spans.any { d >= it.startM && d <= it.endM }

        /** The station terrain, linear between stations, at distance [d]. */
        fun terrainAt(d: Double): Double {
            val t = (d / stepM).coerceIn(0.0, (mnt.size - 1).toDouble())
            val k = minOf(t.toInt(), mnt.size - 2).coerceAtLeast(0)
            if (mnt.size == 1) return mnt[0]
            val w = t - k
            return mnt[k] * (1 - w) + mnt[k + 1] * w
        }
        val out = path.copy()
        for (t in spans) {
            val s0 = t.startM
            val s1 = t.endM
            val e0 = if (isProtected(s0)) elevationAt(path, s0) else mnt[t.first]
            val e1 = if (isProtected(s1)) elevationAt(path, s1) else mnt[t.last]
            for (i in 0 until path.size) {
                val d = path.distance(i)
                if (d < s0 - refreshM || d > s1 + refreshM || isProtected(d)) continue
                if (d >= s0 && d <= s1) {
                    out.setElevation(i, e0 + (e1 - e0) * (d - s0) / (s1 - s0))
                } else if (!inTunnel(d)) {
                    out.setElevation(i, terrainAt(d))
                }
            }
        }
        out.computeDerivedData()
        return out
    }
}
