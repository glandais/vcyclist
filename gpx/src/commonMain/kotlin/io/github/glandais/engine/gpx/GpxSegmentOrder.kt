package io.github.glandais.engine.gpx

import io.github.glandais.elevation.Distance
import io.github.glandais.elevation.LatLon

/**
 * Tuning of [GpxTrack.orderedSegments] / [GpxDocument.withOrderedSegments].
 *
 * Both thresholds exist to keep the rewrite **conservative**: GPX 1.1 says a track's segments are
 * in order, so a file is rewritten only when its order is plainly wrong — not when a recorded
 * activity merely paused and resumed a few hundred metres on.
 *
 * @property minGainM the reordered chain must save **strictly more** than this many metres of
 *   summed gaps over the file's own order, or the track is returned unchanged.
 * @property maxSegments past this many non-empty segments the track is left alone. The planner is
 *   O(2ⁿ·n²) in time and O(2ⁿ·n) in memory (≈ 18 MiB at 16), and a track with that many segments
 *   is a recording with pauses far more often than a shuffled export. At most
 *   [MAX_PLANNABLE_PIECES].
 */
data class SegmentOrderOptions(
    val minGainM: Double = DEFAULT_MIN_GAIN_M,
    val maxSegments: Int = DEFAULT_MAX_SEGMENTS,
) {
    init {
        require(minGainM.isFinite() && minGainM >= 0.0) { "minGainM must be finite and ≥ 0, was $minGainM" }
        require(maxSegments in 1..MAX_PLANNABLE_PIECES) {
            "maxSegments must be in 1..$MAX_PLANNABLE_PIECES, was $maxSegments"
        }
    }

    companion object {
        const val DEFAULT_MIN_GAIN_M: Double = 200.0
        const val DEFAULT_MAX_SEGMENTS: Int = 16

        /**
         * Hard ceiling of [planSegmentOrder]: at 20 pieces the tables already weigh ≈ 380 MiB, and
         * one more doubles them.
         */
        const val MAX_PLANNABLE_PIECES: Int = 20

        val DEFAULT: SegmentOrderOptions = SegmentOrderOptions()
    }
}

/**
 * The order and directions chosen by [planSegmentOrder].
 *
 * @property order piece indexes, in riding order — a permutation of `0 until pieces.size`.
 * @property reversed for each entry of [order] (same index), whether that piece is ridden
 *   backwards. Piece 0 is pinned forward wherever it lands: `reversed[order.indexOf(0)]` is
 *   always `false`.
 * @property gapM summed straight-line (haversine) gaps between consecutive pieces, in metres.
 */
data class SegmentOrderPlan(
    val order: List<Int>,
    val reversed: List<Boolean>,
    val gapM: Double,
)

/** Two chains whose gaps differ by less than this cost the same (tie-break, rounding). */
private const val COST_EPSILON_M = 0.001

/**
 * The order and directions of [pieces] that minimise the summed gaps — the straight line from the
 * last point of a piece (its first, when ridden backwards) to the entry point of the next.
 *
 * **Exact**, unlike a nearest-neighbour heuristic: a Held-Karp dynamic programme over (set of
 * pieces used, last piece, its direction), O(2ⁿ·n²). Riding a whole chain backwards costs the
 * same, so piece 0 is pinned forward. Ties within a millimetre go to the lowest index and the
 * forward direction, so pieces already end to end come back as `0, 1, 2…`, all forward.
 *
 * Pure: the pieces are only read. Shared by [GpxTrack.orderedSegments] and by callers that chain
 * pieces of their own (merging several files, for instance).
 *
 * @param pieces every piece must hold at least one point.
 * @param maxPieces refuses more pieces than this, rather than allocating tables that grow as 2ⁿ.
 * @throws IllegalArgumentException when [pieces] is empty, a piece is empty, or there are more
 *   than [maxPieces] pieces (or [maxPieces] exceeds [SegmentOrderOptions.MAX_PLANNABLE_PIECES]).
 */
fun planSegmentOrder(
    pieces: List<List<GpxTrackPoint>>,
    maxPieces: Int = SegmentOrderOptions.DEFAULT_MAX_SEGMENTS,
): SegmentOrderPlan {
    require(maxPieces in 1..SegmentOrderOptions.MAX_PLANNABLE_PIECES) {
        "maxPieces must be in 1..${SegmentOrderOptions.MAX_PLANNABLE_PIECES}, was $maxPieces"
    }
    val n = pieces.size
    require(n > 0) { "no piece to order" }
    require(n <= maxPieces) { "$n pieces, more than the $maxPieces allowed" }
    require(pieces.all { it.isNotEmpty() }) { "every piece must hold at least one point" }
    if (n == 1) return SegmentOrderPlan(listOf(0), listOf(false), 0.0)

    // Node 2j+o is piece j in direction o (0 forward, 1 backward). Its entry point is its start
    // when forward, its end when backward; its exit point the other one.
    val nodes = 2 * n
    val gap = Array(nodes) { a -> DoubleArray(nodes) { b -> distanceM(exitPoint(pieces, a), entryPoint(pieces, b)) } }

    val states = 1 shl n
    val cost = DoubleArray(states * nodes) { Double.POSITIVE_INFINITY }
    // A node index is < 2 × MAX_PLANNABLE_PIECES = 40: a byte holds it, and halves the tables.
    val parent = ByteArray(states * nodes) { -1 }
    for (j in 0 until n) {
        for (o in 0..1) {
            if (j == 0 && o == 1) continue // A chain and its reverse cost the same: piece 0 forward.
            cost[(1 shl j) * nodes + 2 * j + o] = 0.0
        }
    }
    for (mask in 1 until states) {
        for (last in 0 until nodes) {
            val base = cost[mask * nodes + last]
            if (base == Double.POSITIVE_INFINITY) continue
            for (k in 0 until n) {
                if (mask and (1 shl k) != 0) continue
                val nextMask = mask or (1 shl k)
                for (p in 0..1) {
                    if (k == 0 && p == 1) continue
                    val next = 2 * k + p
                    val candidate = base + gap[last][next]
                    val index = nextMask * nodes + next
                    // Strictly better only: masks and nodes are visited in increasing order, so a
                    // tie keeps the lowest predecessor.
                    if (candidate < cost[index] - COST_EPSILON_M) {
                        cost[index] = candidate
                        parent[index] = last.toByte()
                    }
                }
            }
        }
    }

    val full = states - 1
    var best = -1
    var bestCost = Double.POSITIVE_INFINITY
    for (last in 0 until nodes) {
        val c = cost[full * nodes + last]
        if (c < bestCost - COST_EPSILON_M) {
            bestCost = c
            best = last
        }
    }

    val order = IntArray(n)
    val reversed = BooleanArray(n)
    var mask = full
    var node = best
    for (i in n - 1 downTo 0) {
        order[i] = node / 2
        reversed[i] = node % 2 == 1
        val previous = parent[mask * nodes + node].toInt()
        mask = mask and (1 shl (node / 2)).inv()
        node = previous
    }
    return SegmentOrderPlan(order.toList(), reversed.toList(), bestCost)
}

/**
 * Summed gaps, in metres, of riding [pieces] in [order] with directions [reversed] (same indexing
 * as [SegmentOrderPlan]). The identity order, all forward, measures the file as written.
 */
fun segmentOrderGapM(
    pieces: List<List<GpxTrackPoint>>,
    order: List<Int>,
    reversed: List<Boolean>,
): Double {
    require(order.size == reversed.size) { "order and reversed differ in size" }
    var total = 0.0
    for (i in 1 until order.size) {
        val from = 2 * order[i - 1] + if (reversed[i - 1]) 1 else 0
        val to = 2 * order[i] + if (reversed[i]) 1 else 0
        total += distanceM(exitPoint(pieces, from), entryPoint(pieces, to))
    }
    return total
}

/**
 * This track with its segments put back end to end, when the file wrote them out of order or
 * backwards — `this` itself otherwise.
 *
 * Each segment stays a segment of its own; one ridden backwards has its points reversed
 * (timestamps included, so they then decrease — a file whose order was plainly wrong had no
 * trustworthy clock across segments anyway). The order is [planSegmentOrder]'s.
 *
 * **Conservative**, because GPX 1.1 says segments are in order: the track is rewritten only when
 * the new chain saves **more than** [SegmentOrderOptions.minGainM] of summed gaps over the file's
 * own order. A ride recorded with pauses, a route, a track of one segment, or one with more than
 * [SegmentOrderOptions.maxSegments] non-empty segments comes back as the same instance.
 *
 * Empty segments do not take part in the order. When the track is rewritten they are dropped —
 * they hold nothing to place — and kept as they were when it is not. Idempotent: a rewritten
 * track is already in its best order, so a second pass saves nothing and returns it unchanged.
 */
fun GpxTrack.orderedSegments(options: SegmentOrderOptions = SegmentOrderOptions.DEFAULT): GpxTrack {
    val pieces = segments.filter { it.points.isNotEmpty() }
    if (pieces.size < 2 || pieces.size > options.maxSegments) return this
    val points = pieces.map { it.points }
    val plan = planSegmentOrder(points, options.maxSegments)
    val fileGapM = segmentOrderGapM(points, points.indices.toList(), List(points.size) { false })
    if (fileGapM - plan.gapM <= options.minGainM) return this
    val reordered =
        plan.order.indices.map { i ->
            val segment = pieces[plan.order[i]]
            if (plan.reversed[i]) GpxSegment(segment.points.asReversed().toList()) else segment
        }
    return copy(segments = reordered)
}

/**
 * This document with [GpxTrack.orderedSegments] applied to every track — `this` itself when no
 * track changed. Name, waypoints and every track field but the segments are kept.
 */
fun GpxDocument.withOrderedSegments(options: SegmentOrderOptions = SegmentOrderOptions.DEFAULT): GpxDocument {
    val ordered = tracks.map { it.orderedSegments(options) }
    if (ordered.indices.all { ordered[it] === tracks[it] }) return this
    return copy(tracks = ordered)
}

private fun entryPoint(
    pieces: List<List<GpxTrackPoint>>,
    node: Int,
): GpxTrackPoint {
    val piece = pieces[node / 2]
    return if (node % 2 == 0) piece.first() else piece.last()
}

private fun exitPoint(
    pieces: List<List<GpxTrackPoint>>,
    node: Int,
): GpxTrackPoint {
    val piece = pieces[node / 2]
    return if (node % 2 == 0) piece.last() else piece.first()
}

private fun distanceM(
    a: GpxTrackPoint,
    b: GpxTrackPoint,
): Double = Distance.haversine(LatLon(a.latitudeDeg, a.longitudeDeg), LatLon(b.latitudeDeg, b.longitudeDeg))
