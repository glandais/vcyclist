package io.github.glandais.engine.path

import io.github.glandais.elevation.Distance
import io.github.glandais.elevation.LatLon
import kotlin.math.abs

/**
 * Terrain-model samples along a route on the uniform grid `k × stepM`, the input of
 * [TunnelDetector]: station `k` is at path distance `k × stepM` and at ([latDeg], [lonDeg]). `NaN` is
 * no data.
 *
 * @property terrainM the terrain model (MNT) at each station, metres
 */
class TunnelProfile(
    val terrainM: DoubleArray,
    val latDeg: DoubleArray,
    val lonDeg: DoubleArray,
) {
    init {
        require(terrainM.size == latDeg.size && latDeg.size == lonDeg.size) {
            "terrain, latitude and longitude must have one sample per station: ${terrainM.size}, ${latDeg.size}, ${lonDeg.size}"
        }
    }

    /** Number of stations. */
    val size: Int get() = terrainM.size
}

/**
 * A tunnel [TunnelDetector] accepted: the road runs on the straight line between the terrain at its
 * two portals.
 *
 * @property first entry portal, station index (inclusive)
 * @property last exit portal, station index (inclusive)
 * @property chordGrade grade of the line between the portals, a fraction
 * @property heightM how far the terrain rises above that line, at most, metres
 * @property riseGrade steepest climb over `steepWindowM` just inside the entry, a fraction
 * @property fallGrade steepest fall over `steepWindowM` just inside the exit, a fraction (negative)
 * @property straightness straight-line distance between the portals over `lengthM`
 */
data class TunnelSpan(
    val first: Int,
    val last: Int,
    val chordGrade: Double,
    val heightM: Double,
    val riseGrade: Double,
    val fallGrade: Double,
    val straightness: Double,
    val stepM: Double,
) {
    /** Distance between the portals along the road, metres. */
    val lengthM: Double get() = (last - first) * stepM

    /** Distance of the entry portal, metres. */
    val startM: Double get() = first * stepM

    /** Distance of the exit portal, metres. */
    val endM: Double get() = last * stepM
}

/**
 * Finds road tunnels in a terrain model read along the route (E4 of
 * `docs/tasks/elevation-bridge-decks.md`). Neither LiDAR nor any IGN layer sees into a tunnel: the
 * terrain model there is the mountain above, and a route read on it climbs over the summit — up to
 * +220 m and 112 m of fictitious D+ at the Grands Goulets. The road inside is close to the straight
 * line between the portals, which is what the caller writes back.
 *
 * Pure and deterministic: arrays in, spans out. The signature, measured on the tuning set:
 *
 * 1. **Portals.** An *entry* is a station whose outer side is road — grade at most
 *    `maxRoadGrade` over both `footWindowM` and `steepWindowM` behind it — and whose inner side
 *    climbs at least `minPortalGrade` over `footWindowM`; an *exit* is the mirror image. Among
 *    portals of one kind closer than `footWindowM`, the sharpest change of grade is kept.
 * 2. **Candidates.** Every entry `a` is paired with every later exit `b` with
 *    `minLengthM ≤ (b − a)·step ≤ maxLengthM`. A pair is a tunnel when:
 *    - the route between the portals is straight enough: chord / length ≥ `minStraightness`
 *      (hairpins are not drilled);
 *    - the line between the terrain at `a` and at `b` is a road grade, `≤ maxChordGrade`;
 *    - the terrain rises at least `minHeightM` above that line, and never dips more than
 *      `maxBelowChordM` under it;
 *    - **both sides are impossible for a road**: within `sideSearchM` inside the entry the terrain
 *      climbs at least `minSideGrade` over `steepWindowM`, and falls as steeply inside the exit;
 *    - it is one tunnel, not two: no exit-like portal followed by an entry-like one inside it with
 *      the terrain between them less than `minValleyHeightM` above the line (open road between two
 *      tunnels).
 * 3. Among the tunnels of one entry, the one whose terrain rises highest above its line (the whole
 *    mountain, not a shoulder of it); then the next entry after its exit. Spans never overlap.
 *
 * Any `NaN` the decision reads keeps a portal from being one, and a candidate with a `NaN` between
 * its portals is no tunnel. The detector **does not see** a cut-and-cover tunnel under flat ground
 * (Tuileries: 3.7–5.6 m of bump, below `minHeightM`), tunnels shorter than `minLengthM`, nor a chain
 * of rock tunnels closer together than `steepWindowM` (Petits Goulets on IGN): the error left there
 * is small or local, and the precision is worth it.
 */
object TunnelDetector {
    /** The tunnels of [profile], sorted, never overlapping. */
    fun detect(
        profile: TunnelProfile,
        options: TunnelDetectorOptions = TunnelDetectorOptions.DEFAULT,
    ): List<TunnelSpan> {
        val z = profile.terrainM
        if (z.size < 2) return emptyList()
        val entries = portals(z, options, entry = true)
        val exits = portals(z, options, entry = false)
        val out = ArrayList<TunnelSpan>()
        var next = -1
        for (a in entries) {
            if (a < next) continue
            var best: TunnelSpan? = null
            for (b in exits) {
                if (b <= a) continue
                if ((b - a) * options.stepM > options.maxLengthM + 1e-9) break
                val span = judge(profile, a, b, entries, exits, options) ?: continue
                if (best == null || span.heightM > best.heightM + 1e-9) best = span
            }
            if (best != null) {
                out += best
                next = best.last
            }
        }
        return out
    }

    /** Grade from station [i] to station [j] (`j > i`), `NaN` outside the profile. */
    private fun grade(
        z: DoubleArray,
        i: Int,
        j: Int,
        stepM: Double,
    ): Double = if (i < 0 || j >= z.size) Double.NaN else (z[j] - z[i]) / ((j - i) * stepM)

    /**
     * Entry ([entry]) or exit portals: road on the outer side, a cut face on the inner one; among
     * those within `footWindowM` of each other, the sharpest change of grade (ties all kept).
     */
    internal fun portals(
        z: DoubleArray,
        options: TunnelDetectorOptions,
        entry: Boolean,
    ): List<Int> {
        val n = z.size
        val m = options.footWindowStations
        val w = options.steepWindowStations
        val step = options.stepM
        val score = DoubleArray(n) { Double.NEGATIVE_INFINITY }
        for (k in 0 until n) {
            val back = grade(z, k - m, k, step)
            val ahead = grade(z, k, k + m, step)
            if (back.isNaN() || ahead.isNaN()) continue
            val ok =
                if (entry) {
                    val outer = grade(z, k - w, k, step)
                    abs(back) <= options.maxRoadGrade && abs(outer) <= options.maxRoadGrade && ahead >= options.minPortalGrade
                } else {
                    val outer = grade(z, k, k + w, step)
                    abs(ahead) <= options.maxRoadGrade && abs(outer) <= options.maxRoadGrade && back <= -options.minPortalGrade
                }
            if (ok) score[k] = ahead - back
        }
        return (0 until n).filter { k ->
            score[k] > Double.NEGATIVE_INFINITY &&
                (maxOf(0, k - m)..minOf(n - 1, k + m)).all { score[it] <= score[k] }
        }
    }

    private fun judge(
        profile: TunnelProfile,
        a: Int,
        b: Int,
        entries: List<Int>,
        exits: List<Int>,
        options: TunnelDetectorOptions,
    ): TunnelSpan? {
        val w = options.steepWindowStations
        val z = profile.terrainM
        val n = z.size
        val step = options.stepM
        val length = (b - a) * step
        if (length < options.minLengthM - 1e-9) return null
        val chord =
            Distance.haversine(
                LatLon(profile.latDeg[a], profile.lonDeg[a]),
                LatLon(profile.latDeg[b], profile.lonDeg[b]),
            )
        val straightness = chord / length
        if (!(straightness >= options.minStraightness)) return null
        val c = (z[b] - z[a]) / length
        if (!(abs(c) <= options.maxChordGrade)) return null
        var height = Double.NEGATIVE_INFINITY
        for (k in a..b) {
            val h = z[k] - (z[a] + c * (k - a) * step)
            if (h.isNaN() || h < -options.maxBelowChordM) return null
            if (h > height) height = h
        }
        if (height < options.minHeightM) return null
        // Both sides impossible for a road: a climb just inside the entry, a fall just inside the exit.
        val s = options.sideSearchStations
        var rise = Double.NEGATIVE_INFINITY
        for (k in maxOf(0, a - w / 2)..minOf(a + s, n - 1)) {
            val g = grade(z, k, k + w, step)
            if (!g.isNaN() && g > rise) rise = g
        }
        var fall = Double.POSITIVE_INFINITY
        for (k in maxOf(0, b - w - s)..(b - w / 2)) {
            val g = grade(z, k, k + w, step)
            if (!g.isNaN() && g < fall) fall = g
        }
        if (rise < options.minSideGrade || fall > -options.minSideGrade) return null
        // One tunnel, not two: an exit-like then an entry-like portal inside, with open road between.
        val innerExits = exits.filter { it in (a + 1) until b }
        val innerEntries = entries.filter { it in (a + 1) until b }
        for (x in innerExits) {
            for (y in innerEntries) {
                if (y <= x) continue
                for (k in x..y) {
                    if (z[k] - (z[a] + c * (k - a) * step) < options.minValleyHeightM) return null
                }
            }
        }
        return TunnelSpan(a, b, c, height, rise, fall, straightness, step)
    }
}
