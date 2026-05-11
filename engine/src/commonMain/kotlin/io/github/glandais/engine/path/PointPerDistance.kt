package io.github.glandais.engine.path

import kotlin.math.ceil

/**
 * Distance-based path resampler. Enforces a `[minDistanceM, maxDistanceM]` gap between
 * consecutive points :
 * - Source points closer than [minDistanceM] from the last kept point are dropped.
 * - Source points within `(minDistanceM, maxDistanceM]` are copied verbatim.
 * - Gaps larger than [maxDistanceM] are filled with linearly interpolated points at regular
 *   intervals so that no resulting gap exceeds [maxDistanceM].
 *
 * The first point is always kept. [minDistanceM] may be negative (e.g. `-1`) to disable the
 * lower bound (densify-only mode).
 *
 * Port of `processing/PointPerDistance.ts`. Returns a fresh [Path] ; the source is unchanged.
 */
object PointPerDistance {
    /** Same as [computeOnePointPerDistance] (TS-compatible alias). */
    fun compute(
        source: Path,
        minDistanceM: Double,
        maxDistanceM: Double,
    ): Path = computeOnePointPerDistance(source, minDistanceM, maxDistanceM)

    fun computeOnePointPerDistance(
        source: Path,
        minDistanceM: Double,
        maxDistanceM: Double,
    ): Path {
        require(maxDistanceM > 0.0) { "maxDistanceM must be > 0, got $maxDistanceM" }
        if (source.size == 0) return Path(0)
        if (source.size == 1) {
            val out = Path(1)
            copyFields(source, 0, out, 0)
            out.computeDerivedData()
            return out
        }

        val plan = buildPlan(source, minDistanceM, maxDistanceM)
        return materialize(source, plan)
    }

    private sealed interface Op {
        data class Copy(
            val sourceIndex: Int,
        ) : Op

        data class Interpolate(
            val from: Int,
            val to: Int,
            val coef: Double,
        ) : Op
    }

    private fun buildPlan(
        source: Path,
        minDistanceM: Double,
        maxDistanceM: Double,
    ): List<Op> {
        val n = source.size
        val plan = ArrayList<Op>(n)
        // Always keep the first point.
        plan += Op.Copy(0)
        var lastAddedDistance = source.distance(0)
        var lastAddedIndex = 0

        for (i in 1 until n) {
            val curDist = source.distance(i)
            val gap = curDist - lastAddedDistance

            when {
                gap < minDistanceM -> continue
                gap <= maxDistanceM -> {
                    plan += Op.Copy(i)
                    lastAddedDistance = curDist
                    lastAddedIndex = i
                }
                else -> {
                    val numSegments = ceil(gap / maxDistanceM).toInt()
                    val spacing = gap / numSegments
                    var index1 = lastAddedIndex
                    for (j in 1 until numSegments) {
                        val targetDistance = lastAddedDistance + j * spacing
                        // Find segment [index1, index1+1] containing targetDistance.
                        while (index1 < i - 1 && source.distance(index1 + 1) < targetDistance) {
                            index1++
                        }
                        val dist1 = source.distance(index1)
                        val dist2 = source.distance(index1 + 1)
                        val coef = (targetDistance - dist1) / (dist2 - dist1)
                        plan += Op.Interpolate(index1, index1 + 1, coef)
                    }
                    plan += Op.Copy(i)
                    lastAddedDistance = curDist
                    lastAddedIndex = i
                }
            }
        }
        return plan
    }

    private fun materialize(
        source: Path,
        plan: List<Op>,
    ): Path {
        val out = Path(plan.size)
        for ((dstIdx, op) in plan.withIndex()) {
            when (op) {
                is Op.Copy -> copyFields(source, op.sourceIndex, out, dstIdx)
                is Op.Interpolate -> interpolateFields(source, op.from, op.to, op.coef, out, dstIdx)
            }
        }
        out.computeDerivedData()
        return out
    }

    private fun copyFields(
        src: Path,
        srcIdx: Int,
        dst: Path,
        dstIdx: Int,
    ) {
        for (field in PointField.entries) {
            dst.set(dstIdx, field, src.get(srcIdx, field))
        }
    }

    private fun interpolateFields(
        src: Path,
        i1: Int,
        i2: Int,
        coef: Double,
        dst: Path,
        dstIdx: Int,
    ) {
        for (field in PointField.entries) {
            val v1 = src.get(i1, field)
            val v2 = src.get(i2, field)
            // Strict NaN handling : either side NaN → result NaN (mirrors TS).
            val v = if (v1.isNaN() || v2.isNaN()) Double.NaN else v1 + (v2 - v1) * coef
            dst.set(dstIdx, field, v)
        }
    }
}
