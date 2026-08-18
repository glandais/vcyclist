package io.github.glandais.engine.path

import io.github.glandais.elevation.ElevationSmoother

/**
 * What [ElevationGain.compute] measured.
 *
 * [rawGainM] / [rawLossM] are the unfiltered sums **on the same (optionally smoothed) profile**, so
 * they isolate what the dead band did from what the smoothing did. Neither is
 * [Path.elevationGain], which is the unfiltered sum on the *unsmoothed* profile.
 */
data class ElevationGainResult(
    /** Cumulative ascent in meters. Always `>= 0`. */
    val gainM: Double,
    /** Cumulative descent in meters. Always `<= 0` — the same sign convention as [Path.elevationLoss]. */
    val lossM: Double,
    val rawGainM: Double,
    val rawLossM: Double,
    val thresholdM: Double,
    val smoothWindowM: Double,
    /** Number of legs banked — climbs plus descents. A diagnostic: a route with 3 real climbs that
     *  reports 400 legs is telling you the threshold is too small for the noise. */
    val legCount: Int,
) {
    companion object {
        fun empty(options: ElevationGainOptions): ElevationGainResult =
            ElevationGainResult(0.0, 0.0, 0.0, 0.0, options.thresholdM, options.smoothWindowM, 0)
    }
}

/**
 * Cumulative ascent and descent with a hysteresis dead band, on a profile smoothed at its own scale.
 *
 * ## Why this is not [Path.computeDerivedData]'s sum
 *
 * A plain sum of positive deltas counts every wiggle, so it grows without bound as the sampling
 * gets finer — the coastline problem. On `strava.gpx` it reports 1066 m for a ride that measures
 * 632 m at any reasonable scale. This reports a figure that is stable under resampling.
 *
 * ## Why it is not a per-delta filter either
 *
 * The tempting one-liner is `if (dEle > threshold) gain += dEle`. It is wrong: on a smooth 500 m
 * climb sampled at 2 m spacing, no single delta ever exceeds 3 m, so it reports **zero**. The
 * accumulator below tracks *turning points* instead — a leg is banked once, in full, when the
 * profile reverses by the threshold — which makes it depend only on local extrema and therefore
 * invariant to how densely the ground between them is sampled.
 *
 * ## Why it smooths its own copy
 *
 * The pipeline's 150 m kernel exists to give the physics stable gradients, and it has an effective
 * averaging length of `150/√6 ≈ 61 m` — inside the band where real terrain lives. Reading D+ off
 * that profile runs systematically low (632 m against 661 m on `strava.gpx`, 132 m against 213 m on
 * `stelvio.gpx`). So this stage smooths a private copy at [ElevationGainOptions.smoothWindowM],
 * ~30 m by default, and never mutates the path.
 *
 * See `docs/guides/elevation.md` and ledger rows R27–R28.
 */
object ElevationGain {
    /**
     * Measure [path]. Reads `distance` and `elevation`; writes nothing.
     *
     * `distance` must already be populated, which [Path.computeDerivedData] does — every path
     * leaving a resampler, a smoother or the parser has it.
     */
    fun compute(
        path: Path,
        options: ElevationGainOptions = ElevationGainOptions.DEFAULT,
    ): ElevationGainResult {
        if (path.size < 2) return ElevationGainResult.empty(options)
        val distanceM = DoubleArray(path.size) { path.distance(it) }
        val elevationM =
            DoubleArray(path.size) {
                // `sourceElevation` is the altitude before the pipeline's 150 m kernel, and NaN on
                // any path that was never smoothed. Preferring it is what keeps this measurement
                // independent of a window chosen for the physics.
                val source = path.sourceElevation(it)
                if (source.isNaN()) path.elevation(it) else source
            }
        return compute(distanceM, elevationM, options)
    }

    /**
     * [compute] the path, then cache the result on it as [Path.elevationGainFiltered] /
     * [Path.elevationLossFiltered], and return what was measured.
     *
     * The setters are `internal` to `:gpx`, so this is the only way another module writes them —
     * which is the point: the two scalars must never be set to anything but the output of this
     * accumulator, and `resetStats` must remain the only thing that clears them.
     */
    fun annotate(
        path: Path,
        options: ElevationGainOptions = ElevationGainOptions.DEFAULT,
    ): ElevationGainResult {
        val result = compute(path, options)
        path.elevationGainFiltered = result.gainM
        path.elevationLossFiltered = result.lossM
        return result
    }

    /**
     * The kernel, on flat arrays. [distanceM] must be non-decreasing and the same length as
     * [elevationM].
     */
    fun compute(
        distanceM: DoubleArray,
        elevationM: DoubleArray,
        options: ElevationGainOptions = ElevationGainOptions.DEFAULT,
    ): ElevationGainResult {
        require(distanceM.size == elevationM.size) {
            "distanceM (${distanceM.size}) and elevationM (${elevationM.size}) must have the same length"
        }
        if (elevationM.size < 2) return ElevationGainResult.empty(options)

        val profile = ElevationSmoother.smoothProfile(distanceM, elevationM, options.smoothWindowM)

        var rawGain = 0.0
        var rawLoss = 0.0
        for (i in 1 until profile.size) {
            val d = profile[i] - profile[i - 1]
            if (d > 0.0) rawGain += d else rawLoss += d
        }

        if (options.thresholdM <= 0.0) {
            return ElevationGainResult(
                gainM = rawGain,
                lossM = rawLoss,
                rawGainM = rawGain,
                rawLossM = rawLoss,
                thresholdM = 0.0,
                smoothWindowM = options.smoothWindowM,
                legCount = 0,
            )
        }

        val banked = accumulate(profile, options.thresholdM)
        return ElevationGainResult(
            gainM = banked.gainM,
            lossM = banked.lossM,
            rawGainM = rawGain,
            rawLossM = rawLoss,
            thresholdM = options.thresholdM,
            smoothWindowM = options.smoothWindowM,
            legCount = banked.legCount,
        )
    }

    private class Banked(
        val gainM: Double,
        val lossM: Double,
        val legCount: Int,
    )

    /**
     * The turning-point accumulator.
     *
     * State is a confirmed turning point `ref`, a running extremum `ext` since `ref`, and a
     * direction. A leg `[ref, ext]` is banked when the profile retraces by `threshold` from `ext`,
     * which is what makes the count all-or-nothing: a bump of exactly `threshold` is counted in
     * full, one of `threshold - ε` is dropped entirely *including its matching descent*, so gain
     * and loss stay balanced.
     *
     * Banked legs are intervals between consecutive confirmed turning points, so they tile the
     * profile disjointly — a climb with twenty 1 m sub-summits banks one leg, not twenty, and
     * nothing is ever counted twice. Consequently `gain + loss` telescopes to
     * `last - first`, to within one unconfirmed final wiggle (`threshold`).
     *
     * The `dir == 0` prologue exists because the first leg's direction is unknown until the profile
     * has moved `threshold` in *some* direction; tracking both extrema and their indices until then
     * is what stops a route that opens with a dip from booking that dip as a climb.
     */
    private fun accumulate(
        h: DoubleArray,
        threshold: Double,
    ): Banked {
        var gain = 0.0
        var loss = 0.0
        var legCount = 0

        var hi = h[0]
        var lo = h[0]
        var iHi = 0
        var iLo = 0
        var dir = 0
        var ref = h[0]
        var ext = h[0]

        for (i in 1 until h.size) {
            val e = h[i]
            when {
                dir == 0 -> {
                    if (e > hi) {
                        hi = e
                        iHi = i
                    }
                    if (e < lo) {
                        lo = e
                        iLo = i
                    }
                    if (hi - lo >= threshold) {
                        if (iHi > iLo) {
                            dir = 1
                            ref = lo
                            ext = if (e > hi) e else hi
                        } else {
                            dir = -1
                            ref = hi
                            ext = if (e < lo) e else lo
                        }
                    }
                }

                dir > 0 -> {
                    if (e > ext) {
                        ext = e
                    } else if (ext - e >= threshold) {
                        gain += ext - ref
                        legCount++
                        ref = ext
                        ext = e
                        dir = -1
                    }
                }

                else -> {
                    if (e < ext) {
                        ext = e
                    } else if (e - ext >= threshold) {
                        loss += ext - ref
                        legCount++
                        ref = ext
                        ext = e
                        dir = 1
                    }
                }
            }
        }

        // Flush the leg still open at the end of the profile. Without this a route that finishes
        // at the top of its final climb loses that whole climb.
        if (dir > 0 && ext > ref) {
            gain += ext - ref
            legCount++
        } else if (dir < 0 && ext < ref) {
            loss += ext - ref
            legCount++
        }

        return Banked(gain, loss, legCount)
    }
}
