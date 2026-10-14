package io.github.glandais.engine.path

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.LatLon
import io.github.glandais.elevation.TerrainOrigin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor

/** What [BridgeDeckStep.apply] did. */
internal enum class BridgeDeckStatus {
    /** At least one deck was replaced. */
    APPLIED,

    /** The surface model covers the route, but no deck was accepted, or none had two anchors. */
    NO_DECK,

    /** Every surface sample is no-data: the route is outside the surface model's coverage. */
    NO_COVERAGE,

    /** A tile could not be read (offline, a transient server error): the path is left as it was. */
    FETCH_FAILED,
}

/**
 * Result of [BridgeDeckStep.apply].
 *
 * @property path the input instance itself unless [status] is [BridgeDeckStatus.APPLIED]
 * @property detection the detector's output, `null` when it did not run
 * @property replacements the stretches written back, station indices on the step's 5 m grid
 * @property terrain the terrain model read at the stations of [alongTrackStations], `null` when it was
 *   not read (a path of fewer than two points, a failed fetch). [TunnelStep] reuses it rather than
 *   read the same tiles twice: the deck write moves no coordinate, so the stations are the same.
 */
internal class BridgeDeckResult(
    val path: Path,
    val status: BridgeDeckStatus,
    val detection: DeckDetection?,
    val replacements: List<DeckReplacement>,
    val dropped: List<List<DeckCandidate>> = emptyList(),
    val terrain: DoubleArray? = null,
)

/**
 * Stations every [stepM] metres along [path]: station `k` lies at path distance `k × stepM`, for
 * `k = 0 .. floor(totalDistance / stepM)`, its position interpolated linearly between the two path
 * points around it. Distances are the path's own [Path.distance] (cumulative haversine). Shared with
 * [TunnelStep] (E4), which reads the terrain on the same grid.
 */
internal fun alongTrackStations(
    path: Path,
    stepM: Double,
): List<LatLon> {
    if (path.size == 0) return emptyList()
    val total = path.distance(path.size - 1)
    val n = floor(total / stepM).toInt() + 1
    val out = ArrayList<LatLon>(n)
    var j = 0
    for (k in 0 until n) {
        val s = k * stepM
        while (j < path.size - 2 && path.distance(j + 1) < s) j++
        if (path.size == 1) {
            out += LatLon(path.latitudeDeg(0), path.longitudeDeg(0))
            continue
        }
        val u = fraction(path, j, s)
        out +=
            LatLon(
                path.latitudeDeg(j) + (path.latitudeDeg(j + 1) - path.latitudeDeg(j)) * u,
                path.longitudeDeg(j) + (path.longitudeDeg(j + 1) - path.longitudeDeg(j)) * u,
            )
    }
    return out
}

/** Where [s] falls between path points [j] and `j + 1`, clamped to `0..1`. */
private fun fraction(
    path: Path,
    j: Int,
    s: Double,
): Double {
    val span = path.distance(j + 1) - path.distance(j)
    return if (span > 0) ((s - path.distance(j)) / span).coerceIn(0.0, 1.0) else 0.0
}

/**
 * The distances [BridgeDeckStep.write] rewrites for this stretch on a [stepM] grid: from its first
 * pin to its last, open-ended past a route end it is held flat to.
 */
internal fun DeckReplacement.writtenRange(stepM: Double): ClosedFloatingPointRange<Double> =
    (if (pinnedStart) first * stepM else Double.NEGATIVE_INFINITY)..(if (pinnedEnd) last * stepM else Double.POSITIVE_INFINITY)

/** The path's elevation interpolated at distance [s]. Shared with [TunnelStep]. */
internal fun elevationAt(
    path: Path,
    s: Double,
): Double {
    if (path.size == 1) return path.elevation(0)
    var j = 0
    while (j < path.size - 2 && path.distance(j + 1) < s) j++
    val u = fraction(path, j, s)
    return path.elevation(j) + (path.elevation(j + 1) - path.elevation(j)) * u
}

/**
 * Step 3b of the enhancer (E3 of `docs/archive/tasks/elevation-bridge-decks.md`): replaces each bridge deck
 * the LiDAR sees by its profile, where the terrain model rides the valley floor under it.
 *
 * 1. Stations every [DeckDetectorOptions.stepM] along the path ([alongTrackStations]).
 * 2. The terrain there from [terrain] — in **reverse** order: the path was just corrected forward
 *    through the same 100-tile cache, so its last tiles are the warm ones; forward, a route over
 *    100 tiles thrashes the whole cache (132 extra reads on `test4`, 33 reversed). The surface from
 *    [surface], forward.
 * 3. [DeckDetector.detect], then [DeckProfileBuilder.build].
 * 4. On a copy of the path, every point whose distance lies between a stretch's two pins gets the
 *    stretch's station function ([DeckReplacement.withEnds], pinned to the path's own elevation at
 *    the pins) interpolated at its distance; every other point is left bit for bit, and derived
 *    data is recomputed.
 *
 * **A tile read never fails the ride.** When either provider throws — a tile the server refuses,
 * no network — the input path comes back with [BridgeDeckStatus.FETCH_FAILED], unless the calling
 * job is cancelled: then the cancellation propagates. Only the two reads are guarded: detection,
 * building and writing run outside the `catch`, so a defect in them throws instead of passing for
 * a failed fetch. All or nothing: one missing surface tile drops every deck of the route, not just
 * its own (IGN answered transient HTTP 400s during E2 quinquies). [Enhancer][io.github.glandais.engine.Enhancer]
 * maps the status onto `Path.elevationCorrections` ([ElevationCorrectionReport]); a FETCH_FAILED
 * becomes a warning every door relays. The same input instance comes back whenever nothing is
 * replaced.
 */
internal object BridgeDeckStep {
    /**
     * @param replace steps 3–4 on the samples read; a seam for the test that pins which exceptions
     *   the step guards, never anything but [replaceDecks] in production
     */
    suspend fun apply(
        path: Path,
        terrain: ElevationProvider,
        surface: ElevationProvider,
        options: DeckDetectorOptions = DeckDetectorOptions.DEFAULT,
        replace: (Path, DoubleArray, DoubleArray, DeckDetectorOptions) -> BridgeDeckResult = ::replaceDecks,
    ): BridgeDeckResult {
        if (path.size < 2) return BridgeDeckResult(path, BridgeDeckStatus.NO_DECK, null, emptyList())
        val stations = alongTrackStations(path, options.stepM)
        val samples =
            try {
                val read = terrain.setElevationsWithOrigin(stations.asReversed()).asReversed()
                val mnt = DoubleArray(read.size) { read[it].elevation }
                val mns = surface.setElevations(stations).map { it.elevation }.toDoubleArray()
                // An IGN surface over any other terrain (Mapterhorn at a coverage edge or after a
                // failed tile) is a mix of models the detector was never measured on: no deck there.
                for (k in mns.indices) if (read[k].origin != TerrainOrigin.IGN_LIDAR_HD) mns[k] = Double.NaN
                mnt to mns
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // ElevationCalculator wraps whatever a fetcher throws, a cancellation included, in an
                // IllegalStateException: ask the job itself, or a cancelled ride would run on to the end.
                currentCoroutineContext().ensureActive()
                return BridgeDeckResult(path, BridgeDeckStatus.FETCH_FAILED, null, emptyList())
            }
        val r = replace(path, samples.first, samples.second, options)
        return BridgeDeckResult(r.path, r.status, r.detection, r.replacements, r.dropped, samples.first)
    }

    /** Steps 3–4 on the terrain [mnt] and surface [mns] read at the stations of [path]. Never guarded. */
    internal fun replaceDecks(
        path: Path,
        mnt: DoubleArray,
        mns: DoubleArray,
        options: DeckDetectorOptions,
    ): BridgeDeckResult {
        if (mns.all { it.isNaN() }) return BridgeDeckResult(path, BridgeDeckStatus.NO_COVERAGE, null, emptyList())
        val profile = DeckProfile.fromSurface(mns, mnt)
        val detection = DeckDetector.detect(profile, options)
        val built = DeckProfileBuilder.build(profile, detection, options)
        if (built.replacements.isEmpty()) {
            return BridgeDeckResult(path, BridgeDeckStatus.NO_DECK, detection, emptyList(), built.dropped)
        }
        return BridgeDeckResult(
            write(path, built.replacements, options.stepM),
            BridgeDeckStatus.APPLIED,
            detection,
            built.replacements,
            built.dropped,
        )
    }

    /** Step 4: a copy of [path] with every stretch of [replacements] written in. */
    internal fun write(
        path: Path,
        replacements: List<DeckReplacement>,
        stepM: Double,
    ): Path {
        val out = path.copy()
        for (r in replacements) {
            val z = r.withEnds(elevationAt(path, r.first * stepM), elevationAt(path, r.last * stepM))
            // The same range step 3c protects: one definition for both.
            val range = r.writtenRange(stepM)
            for (i in 0 until path.size) {
                val d = path.distance(i)
                if (d !in range) continue
                val t = (d / stepM - r.first).coerceIn(0.0, (z.size - 1).toDouble())
                val k = minOf(floor(t).toInt(), z.size - 2).coerceAtLeast(0)
                val w = t - k
                out.setElevation(i, if (z.size == 1) z[0] else z[k] * (1 - w) + z[k + 1] * w)
            }
        }
        out.computeDerivedData()
        return out
    }
}
