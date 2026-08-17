package io.github.glandais.engine.trajectory

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max

/**
 * One detected bend.
 *
 * @property fromIndex first station of the span, inclusive
 * @property untilIndex one past the last station
 * @property apexIndex station of tightest curvature
 * @property kind classification, for reporting
 * @property turnRad total heading change across the span, signed
 * @property direction `+1` turning left, `−1` turning right
 * @property radiusQ20M the **20th percentile** of `1/|κ|` over the span, not the mean. Robust to
 *   the clothoid entry and exit that bias a mean upward — by around 40 % on a real road — and it
 *   does not require the span's ends to have been located precisely.
 * @property radiusMinM tightest radius seen
 * @property lengthM arclength of the span
 */
data class CornerSpan(
    val fromIndex: Int,
    val untilIndex: Int,
    val apexIndex: Int,
    val kind: CornerKind,
    val turnRad: Double,
    val direction: Int,
    val radiusQ20M: Double,
    val radiusMinM: Double,
    val lengthM: Double,
)

/**
 * Finds bends in a [PlanarFrame]'s curvature.
 *
 * **This is not the producer of the racing line.** It seeds, masks and reports; the solver decides
 * where the line goes and enforces the corridor jointly across all corners. That demotion is
 * deliberate: earlier designs made a per-corner mean radius load-bearing, and any such statistic is
 * biased by the clothoid transitions at each end. A mis-detected corner here costs accuracy in a
 * report, not correctness in a trajectory.
 */
internal object CornerDetector {
    fun detect(
        frame: PlanarFrame,
        options: RacingLineOptions,
        widthM: DoubleArray,
    ): List<CornerSpan> {
        val n = frame.size
        if (n < 3) return emptyList()

        val enter = 1.0 / options.cornerEnterRadiusM
        val exit = 1.0 / options.cornerExitRadiusM
        val kappa = frame.kappa

        // Pass 1 — raw spans, opened on `enter` and closed on `exit` or a sign flip.
        //
        // The two thresholds are what stop a corner from flickering: a single threshold would
        // reopen and reclose the same bend wherever the curvature grazes it, splitting one corner
        // into a dozen and making every derived statistic meaningless.
        val raw = mutableListOf<IntArray>()
        var start = -1
        var sign = 0
        for (i in 0 until n) {
            val k = kappa[i]
            val a = abs(k)
            val s =
                if (k > 0) {
                    1
                } else if (k < 0) {
                    -1
                } else {
                    0
                }
            if (start < 0) {
                if (a > enter && s != 0) {
                    start = i
                    sign = s
                }
            } else {
                val sameSign = s == sign || s == 0
                if (a < exit || !sameSign) {
                    raw.add(intArrayOf(start, i, sign))
                    start = if (a > enter && s != 0 && !sameSign) i else -1
                    if (start >= 0) sign = s
                }
            }
        }
        if (start >= 0) raw.add(intArrayOf(start, n, sign))

        // Pass 2 — merge same-sign neighbours separated by a short gap. A bend interrupted by a
        // brief straightening is one bend, not two; `3w` scales the tolerance with the road.
        val merged = mutableListOf<IntArray>()
        for (span in raw) {
            val last = merged.lastOrNull()
            if (last != null && last[2] == span[2]) {
                val gap = frame.s[minOf(span[0], n - 1)] - frame.s[minOf(last[1], n - 1)]
                val w = widthM[minOf(span[0], widthM.size - 1)]
                if (gap < max(15.0, 3.0 * w)) {
                    last[1] = span[1]
                    continue
                }
            }
            merged.add(span)
        }

        // Pass 3 — measure, reject, classify.
        val minTurn = options.minCornerTurnDeg * PI / 180.0
        val hairpinTurn = options.hairpinTurnDeg * PI / 180.0
        val out = mutableListOf<CornerSpan>()
        for (span in merged) {
            val from = span[0]
            val until = minOf(span[1], n)
            if (until - from < 2) continue
            val lengthM = frame.s[until - 1] - frame.s[from]
            if (lengthM < options.minCornerLengthM) continue

            val turn = frame.theta[until - 1] - frame.theta[from]
            if (abs(turn) < minTurn) continue

            var apex = from
            var peak = 0.0
            var minRadius = Double.MAX_VALUE
            for (i in from until until) {
                val a = abs(kappa[i])
                if (a > peak) {
                    peak = a
                    apex = i
                }
                val r = CurvatureEstimator.radiusAt(kappa[i])
                if (r < minRadius) minRadius = r
            }

            val q20 = radiusPercentile(kappa, from, until, 0.20)
            val kind =
                when {
                    abs(turn) >= hairpinTurn -> CornerKind.HAIRPIN
                    q20 >= options.gentleRadiusM -> CornerKind.GENTLE
                    else -> CornerKind.CORNER
                }
            out.add(
                CornerSpan(
                    fromIndex = from,
                    untilIndex = until,
                    apexIndex = apex,
                    kind = kind,
                    turnRad = turn,
                    direction = span[2],
                    radiusQ20M = q20,
                    radiusMinM = if (minRadius == Double.MAX_VALUE) Double.POSITIVE_INFINITY else minRadius,
                    lengthM = lengthM,
                ),
            )
        }
        return out
    }

    /**
     * The [fraction]-quantile of `1/|κ|` over `[from, until)`.
     *
     * Sorting a `DoubleArray` is a total order with no tie-break to disagree about, so this stays
     * deterministic across targets. Infinities are kept rather than dropped: a straight stretch
     * inside a detected span is real information about that span, and discarding it would bias the
     * quantile toward the tight end.
     */
    private fun radiusPercentile(
        kappa: DoubleArray,
        from: Int,
        until: Int,
        fraction: Double,
    ): Double {
        val count = until - from
        if (count <= 0) return Double.POSITIVE_INFINITY
        val radii = DoubleArray(count) { CurvatureEstimator.radiusAt(kappa[from + it]) }
        radii.sort()
        val idx = ((count - 1) * fraction).toInt().coerceIn(0, count - 1)
        return radii[idx]
    }
}
