package io.github.glandais.engine.path

import kotlin.math.floor

/**
 * Resamples a [Path] to one point per epoch second (1 Hz uniform sampling).
 *
 * Linear interpolation between consecutive source points whenever they straddle a second
 * boundary. Source paths whose first/last points fall mid-second get a "copy" point at the
 * surrounding epoch boundary so the resampled path covers `[floor(start), ceil(end))` seconds.
 *
 * Port of `processing/PointPerSecond.ts`. Returns a fresh [Path] ; the source is unchanged.
 */
object PointPerSecond {
    /** Resample [source] to 1 Hz. Empty source → empty path. */
    fun computeOnePointPerSecond(source: Path): Path {
        if (source.size == 0) return Path(0)
        val plan = buildPlan(source)
        return materialize(source, plan)
    }

    private sealed interface InterpolationData {
        data class Copy(
            val sourceIndex: Int,
        ) : InterpolationData

        data class Interpolate(
            val from: Int,
            val to: Int,
            val coef: Double,
        ) : InterpolationData
    }

    private fun buildPlan(source: Path): Map<Long, InterpolationData> {
        // LinkedHashMap keeps insertion order, then we sort by epoch at materialization time.
        val plan = LinkedHashMap<Long, InterpolationData>()
        val n = source.size

        for (i in 0 until n) {
            val time1 = source.time(i)
            val epoch1 = floor(time1 / 1000.0).toLong()
            val msInSec1 = time1.toLong() - epoch1 * 1000L

            if (i == 0 && msInSec1 != 0L) {
                plan[epoch1] = InterpolationData.Copy(i)
            }
            if (i == n - 1) {
                if (msInSec1 != 0L) {
                    plan[epoch1 + 1L] = InterpolationData.Copy(i)
                }
                continue
            }

            val time2 = source.time(i + 1)
            val epoch2 = floor(time2 / 1000.0).toLong()
            if (epoch1 == epoch2) continue

            val duration12 = time2 - time1
            val epochStart = if (msInSec1 == 0L) epoch1 else epoch1 + 1L
            val epochEnd = epoch2
            var e = epochStart
            while (e <= epochEnd) {
                val epochTime = e * 1000.0
                val coef = (epochTime - time1) / duration12
                plan[e] = InterpolationData.Interpolate(i, i + 1, coef)
                e++
            }
        }
        return plan
    }

    private fun materialize(
        source: Path,
        plan: Map<Long, InterpolationData>,
    ): Path {
        val sortedEpochs = plan.keys.sorted()
        val out = Path(sortedEpochs.size)
        for ((idx, epoch) in sortedEpochs.withIndex()) {
            val data = plan[epoch] ?: continue
            when (data) {
                is InterpolationData.Copy -> copyFields(source, data.sourceIndex, out, idx)
                is InterpolationData.Interpolate ->
                    interpolateFields(source, data.from, data.to, data.coef, out, idx)
            }
            // Time slot is always set to the epoch boundary (overwrites copied/interpolated time).
            out.setTime(idx, (epoch * 1000L).toDouble())
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
