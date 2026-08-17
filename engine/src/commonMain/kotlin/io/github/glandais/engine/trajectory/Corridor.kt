package io.github.glandais.engine.trajectory

import io.github.glandais.engine.path.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The per-point interval `[lo_i, hi_i]` the racing line may occupy, in metres of lateral offset
 * from the reference line, positive to the left.
 *
 * @property lo lower bound per station
 * @property hi upper bound per station
 * @property halfWidthM the road half-width the bounds were derived from, after margin and
 *   smoothing but before the clamps — kept for reporting, since a collapsed corridor is much
 *   easier to explain when you can see whether the road or a clamp collapsed it
 */
internal class CorridorBounds(
    val lo: DoubleArray,
    val hi: DoubleArray,
    val halfWidthM: DoubleArray,
)

/**
 * Builds the feasible lateral interval.
 *
 * Three things can narrow the road, and they are applied in this order because each is a harder
 * physical fact than the last: the road's own width, then the geometry of offsetting a curve, then
 * the possibility that two distant parts of the same route are physically adjacent.
 */
internal object Corridor {
    /** Largest half-width worth considering, whatever a file claims. */
    private const val MAX_HALF_WIDTH_M = 6.0

    /** Side of the uniform bucket grid used by the self-proximity search, metres. */
    private const val GRID_M = 12.0

    /** Clearance kept between the line and any other part of the route, metres. */
    private const val PROXIMITY_CLEARANCE_M = 0.5

    /**
     * How far two stations' headings may differ and still count as the same piece of road.
     *
     * A quarter turn. Beyond it the route has doubled back on itself, which is exactly the
     * switchback and out-and-back geometry this clamp exists for.
     */
    private const val SAME_ROAD_HEADING_RAD = PI / 2.0

    /**
     * Resolve the per-point road width: what the path carries, the default where it does not, then
     * smoothed over [RacingLineOptions.widthSmoothWindowM] of arclength.
     *
     * The smoothing matters more than it looks. Widths from any real source are step functions —
     * a way changes class and the width jumps a metre — and a step in the width is a step in the
     * *constraint set*, which a solver answers with a matching kink in the trajectory. Smoothing
     * the constraint is how the kink is avoided; smoothing the resulting line would not be, since
     * by then the corner has already been cut wrong.
     */
    fun resolveWidth(
        path: Path,
        frame: PlanarFrame,
        options: RacingLineOptions,
    ): DoubleArray {
        val raw = DoubleArray(frame.size)
        for (i in raw.indices) {
            val w = path.roadWidth(i)
            raw[i] = if (w.isNaN() || w <= 0.0) options.defaultRoadWidthM else w
        }
        return LocalFrame.smooth(raw, frame.s, options.widthSmoothWindowM)
    }

    fun build(
        frame: PlanarFrame,
        widthM: DoubleArray,
        options: RacingLineOptions,
    ): CorridorBounds {
        val n = frame.size
        val lo = DoubleArray(n)
        val hi = DoubleArray(n)
        val half = DoubleArray(n)

        for (i in 0 until n) {
            val h = (widthM[i] / 2.0 - options.edgeMarginM).coerceIn(0.0, MAX_HALF_WIDTH_M)
            half[i] = h
            when (options.corridor) {
                CorridorMode.LANE -> {
                    lo[i] = -h
                    hi[i] = 0.0
                }
                CorridorMode.LANE_LEFT -> {
                    lo[i] = 0.0
                    hi[i] = h
                }
                CorridorMode.FULL_ROAD -> {
                    lo[i] = -h
                    hi[i] = h
                }
            }
        }

        applyRegularityClamp(frame, options, lo, hi)
        applySelfProximityClamp(frame, options, lo, hi)
        applyPins(frame, options, lo, hi)

        // Whatever the clamps did, the interval must remain non-empty and must still contain a
        // point the solver can start from. Two clamps meeting from opposite sides can cross.
        for (i in 0 until n) {
            if (lo[i] > hi[i]) {
                val mid = (lo[i] + hi[i]) / 2.0
                lo[i] = mid
                hi[i] = mid
            }
        }
        return CorridorBounds(lo, hi, half)
    }

    /**
     * Offset-curve regularity: `|n| ≤ regularityFactor/|κ|`.
     *
     * Offsetting a curve by `n` scales its arclength by `1 − κn`. At `n = 1/κ` — the centre of
     * curvature — that factor reaches zero and the offset curve folds onto a point; past it the
     * curve reverses. None of the trajectory algebra survives that, so the corridor stops short of
     * it by a fixed fraction rather than relying on the solver to avoid it.
     *
     * On a tight bend this is the binding constraint, not the road: at `R = 3 m` it allows 2.55 m
     * of offset, less than a 6 m road would.
     */
    private fun applyRegularityClamp(
        frame: PlanarFrame,
        options: RacingLineOptions,
        lo: DoubleArray,
        hi: DoubleArray,
    ) {
        for (i in 0 until frame.size) {
            val limit = options.regularityFactor / max(abs(frame.kappa[i]), 1e-9)
            lo[i] = max(lo[i], -limit)
            hi[i] = min(hi[i], limit)
        }
    }

    /**
     * Stop the line from being pushed into another part of the same route.
     *
     * The hazard every candidate design shared: on an alpine switchback stack, or an out-and-back,
     * two stretches of road can be metres apart in space while being hundreds of metres apart
     * along the path. Nothing in the corridor or the energy knows they are neighbours, so a solver
     * will happily widen one leg across the other.
     *
     * For each station, find the nearest point that belongs to a *different* piece of road, and
     * allow at most half that distance minus a clearance. Half, because both stations may move
     * toward each other.
     *
     * "Different piece of road" is not simply "far along the path", which is how this was first
     * written and which fails on the one case that matters. Around a hairpin the opposite leg is
     * only tens of metres away *along the path*, so a pure along-path test skips it — and skips it
     * precisely where the two legs are closest and the clamp is most needed.
     *
     * The extra test is on **heading**. Two stations on one piece of road point roughly the same
     * way; the two legs of a switchback point opposite ways. A detour ratio — straight-line
     * distance over along-path distance — was tried first and is too blunt: it reads 0.59 across a
     * hairpin entry and 0.72 along a legitimate 5 m bend, which is not enough separation to
     * threshold. Heading reads π against nearly zero.
     *
     * Where the heading test over-triggers, on a genuinely tight single bend, the offset it allows
     * is larger than the regularity clamp already permits, so nothing changes.
     *
     * The search is a uniform grid hash keyed on integer bucket indices — integers, not hashed
     * floats, so it is bit-reproducible across targets — probing the nine neighbouring buckets.
     * Expected `O(n)`, with the grid side chosen so a bucket holds few points at 1–2 m spacing.
     */
    private fun applySelfProximityClamp(
        frame: PlanarFrame,
        options: RacingLineOptions,
        lo: DoubleArray,
        hi: DoubleArray,
    ) {
        val n = frame.size
        val buckets = HashMap<Long, MutableList<Int>>()
        for (i in 0 until n) {
            buckets.getOrPut(cellKey(bucketOf(frame.x[i]), bucketOf(frame.y[i]))) { mutableListOf() }.add(i)
        }

        for (i in 0 until n) {
            val bx = bucketOf(frame.x[i])
            val by = bucketOf(frame.y[i])
            var nearest = Double.MAX_VALUE
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val cell = buckets[cellKey(bx + dx, by + dy)] ?: continue
                    for (j in cell) {
                        val alongPath = abs(frame.s[j] - frame.s[i])
                        val ddx = frame.x[j] - frame.x[i]
                        val ddy = frame.y[j] - frame.y[i]
                        val d = sqrt(ddx * ddx + ddy * ddy)
                        val headingGap = abs(LocalFrame.unwrap(frame.theta[j] - frame.theta[i]))
                        val sameRoad =
                            alongPath <= options.selfProximityGapM && headingGap < SAME_ROAD_HEADING_RAD
                        if (sameRoad) continue
                        if (d < nearest) nearest = d
                    }
                }
            }
            if (nearest == Double.MAX_VALUE) continue
            val allowed = max(0.0, nearest / 2.0 - PROXIMITY_CLEARANCE_M)
            lo[i] = max(lo[i], -allowed)
            hi[i] = min(hi[i], allowed)
        }
    }

    private fun bucketOf(v: Double): Long = floor(v / GRID_M).toLong()

    /** Pack two bucket indices into one key. Integers only — no float is ever hashed. */
    private fun cellKey(
        bx: Long,
        by: Long,
    ): Long = (bx shl 32) xor (by and 0xFFFFFFFFL)

    /**
     * Pin the offset to zero at both ends and in the middle of every long straight.
     *
     * Pins come in **adjacent pairs**, and that is not cosmetic. The trajectory energy couples
     * `i ± 2` through its curvature term, so its Hessian has bandwidth 2, and a bandwidth-2 system
     * needs two consecutive fixed nodes to actually decouple either side of them. Pinning a single
     * node leaves the two halves coupled through it, which defeats the point of pinning.
     *
     * The interior pins are what keep the problem local: a route is then a sequence of independent
     * blocks between straights rather than one solve whose ends can influence each other.
     */
    private fun applyPins(
        frame: PlanarFrame,
        options: RacingLineOptions,
        lo: DoubleArray,
        hi: DoubleArray,
    ) {
        val n = frame.size
        for (i in intArrayOf(0, 1, n - 2, n - 1)) {
            if (i in 0 until n) {
                lo[i] = 0.0
                hi[i] = 0.0
            }
        }

        val straightKappa = 1.0 / options.straightRadiusM
        var runStart = -1
        for (i in 0 until n) {
            val straight = abs(frame.kappa[i]) < straightKappa
            if (straight) {
                if (runStart < 0) runStart = i
            } else {
                pinRunMidpoint(frame, options, runStart, i, lo, hi)
                runStart = -1
            }
        }
        pinRunMidpoint(frame, options, runStart, n, lo, hi)
    }

    private fun pinRunMidpoint(
        frame: PlanarFrame,
        options: RacingLineOptions,
        from: Int,
        until: Int,
        lo: DoubleArray,
        hi: DoubleArray,
    ) {
        if (from < 0 || until - from < 2) return
        if (frame.s[until - 1] - frame.s[from] < options.straightRunM) return
        val mid = (from + until) / 2
        for (i in intArrayOf(mid, mid + 1)) {
            if (i in 0 until frame.size) {
                lo[i] = 0.0
                hi[i] = 0.0
            }
        }
    }
}
