package io.github.glandais.engine.path

import kotlin.math.floor

/**
 * Resamples a [Path] to one point per epoch second (1 Hz uniform sampling).
 *
 * Linear interpolation between consecutive source points whenever they straddle a second
 * boundary. The first source point is always copied onto `floor(start)` (a later interpolation
 * at `coef = 0` may overwrite it with the same values), and a last point that falls mid-second
 * gets a copy at the next boundary, so the resampled path covers `[floor(start), ceil(end)]`
 * seconds and a non-empty source never resamples to an empty path.
 *
 * "On a boundary" is tested on the `Double` time (`time == epoch * 1000.0`), not on a truncated
 * millisecond: `VirtualizeService` writes fractional-ms times, and a point at `1000.4` ms is
 * *past* the 1000 ms boundary, so the segment it starts must not reach back to it (which would
 * extrapolate with a negative coefficient).
 * Circular fields (longitude, bearings) take the shortest arc — see [FieldInterpolation].
 *
 * Returns a fresh [Path] ; the source is unchanged.
 */
object PointPerSecond {
    /** Resample [source] to 1 Hz. Empty source → empty path; any other source → ≥ 1 point. */
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
            val aligned1 = time1 == epoch1 * 1000.0

            if (i == 0) {
                // Always seed floor(start): when the start is aligned and the next point lies in
                // the same second, no interpolation loop would ever reach this epoch.
                plan[epoch1] = InterpolationData.Copy(i)
            }
            if (i == n - 1) {
                if (!aligned1) {
                    plan[epoch1 + 1L] = InterpolationData.Copy(i)
                }
                continue
            }

            val time2 = source.time(i + 1)
            val epoch2 = floor(time2 / 1000.0).toLong()
            if (epoch1 == epoch2) continue

            val duration12 = time2 - time1
            val epochStart = if (aligned1) epoch1 else epoch1 + 1L
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
                    FieldInterpolation.interpolateFields(source, data.from, data.to, data.coef, out, idx)
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
}
