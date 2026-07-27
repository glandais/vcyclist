package io.github.glandais.engine.climb

import io.github.glandais.elevation.DouglasPeucker
import io.github.glandais.elevation.Vector3D
import io.github.glandais.engine.path.Path
import kotlin.math.pow

/**
 * Detects climbs on a [Path]. Port of gpx2web's `climb/ClimbDetector.java`.
 *
 * ## Algorithm
 *
 * 1. **Threshold.** A climb has to gain at least
 *    `max(minMinClimbElevationM, min(maxMinClimbElevationM, totalAscent / minClimbElevationRatio))`
 *    meters. Sizing it from the path's own total ascent is what lets the same parameters work on
 *    a flat 100 km ride and on an alpine stage: on the former the floor applies, on the latter
 *    the ceiling does.
 *
 * 2. **Best candidate per starting point.** For every index `i`, scan every `j > i` and keep the
 *    candidate maximising `score = length * grade^booster`, subject to four conditions: the span
 *    is longer than 10 m, its average grade reaches [ClimbOptions.minGradePercent], its net gain
 *    reaches the threshold, and `climbingGrade / grade` stays within
 *    [ClimbOptions.maxDiffRealGradeRatio]. That last one is the interesting one — it is what
 *    stops two real climbs either side of a descent from being reported as one long mediocre
 *    climb.
 *
 * 3. **Greedy de-overlap.** Sort all candidates by score descending, repeatedly take the best
 *    one and discard every candidate overlapping it, until none are left. So a climb is never
 *    contained in, nor straddles, another.
 *
 * 4. **Order.** Results are returned in path order, not score order.
 *
 * The `>=` in the score comparison is deliberate and copied from the reference: on a tie the
 * *later* `j` wins, which extends a climb to its true summit rather than stopping at the first
 * point that reaches the same score.
 *
 * ## Part splitting
 *
 * Each climb is broken into homogeneous-grade [ClimbPart]s by running Douglas-Peucker over its
 * `(distance, elevation)` profile, with a tolerance of `clamp(elevationGain / 50, 10, 50)`
 * meters — the reference's formula.
 *
 * gpx2web uses its own `Simplifier`/`Vector` pair for this. This port instead calls
 * [DouglasPeucker.simplifyIndices] from `:elevation`, which was generalised in task g11 for the
 * purpose. The two are equivalent: both measure the perpendicular distance to the segment and
 * both fall back to the endpoint distance when the projection falls outside it — gpx2web tests
 * the dot-product signs, `Vector3D` clamps the projection parameter, which is the same
 * predicate written differently. What is *not* equivalent is
 * [DouglasPeucker.simplify], the geographic entry point: it projects through ECEF and expects
 * latitude and longitude, so it cannot be fed a `(distance, elevation)` profile. Hence the
 * generalisation rather than a second implementation.
 */
object ClimbDetector {
    /** Below this span a candidate is not considered at all (meters). From the reference. */
    private const val MIN_CANDIDATE_LENGTH_M = 10.0

    /** Bounds for the Douglas-Peucker tolerance used to split a climb into parts (meters). */
    private const val MIN_PART_TOLERANCE_M = 10.0
    private const val MAX_PART_TOLERANCE_M = 50.0
    private const val PART_TOLERANCE_DIVISOR = 50.0

    fun detect(
        path: Path,
        options: ClimbOptions = ClimbOptions.DEFAULT,
    ): List<Climb> {
        if (path.size < 2) return emptyList()

        val minClimbElevation =
            maxOf(
                options.minMinClimbElevationM,
                minOf(options.maxMinClimbElevationM, path.elevationGain / options.minClimbElevationRatio),
            )

        val candidates =
            (0 until path.size).mapNotNull { i ->
                bestCandidateFrom(path, i, minClimbElevation, options)
            }

        return dedupeByScore(candidates)
            .sortedBy { it.startIndex }
            .map { it.toClimb(path) }
    }

    /**
     * Highest-scoring climb starting at [i], or `null` if no span from [i] qualifies.
     *
     * Walks forward accumulating positive and negative elevation exactly as the reference does —
     * the running totals are what make this O(n) per starting point rather than O(n²).
     */
    private fun bestCandidateFrom(
        path: Path,
        i: Int,
        minClimbElevation: Double,
        options: ClimbOptions,
    ): Candidate? {
        val startDist = path.distance(i)
        val startEle = path.elevation(i)

        var positiveElevation = 0.0
        var negativeElevation = 0.0
        var distClimbing = 0.0

        var bestScore = 0.0
        var best: Candidate? = null

        for (j in (i + 1) until path.size) {
            val endDist = path.distance(j)
            val endEle = path.elevation(j)

            val dEle = endEle - path.elevation(j - 1)
            if (dEle > 0) {
                positiveElevation += dEle
                distClimbing += endDist - path.distance(j - 1)
            } else {
                negativeElevation += dEle
            }

            val length = endDist - startDist
            if (length <= MIN_CANDIDATE_LENGTH_M) continue

            val netElevation = endEle - startEle
            // Percentages here, matching the reference, so `score` and the option thresholds are
            // directly comparable with gpx2web's.
            val gradePercent = 100.0 * netElevation / length
            val climbingGradePercent = if (distClimbing > 0) 100.0 * positiveElevation / distClimbing else 0.0
            val score = length * gradePercent.pow(options.booster)

            val qualifies =
                gradePercent >= options.minGradePercent &&
                    netElevation >= minClimbElevation &&
                    // `>=`, not `>`: on a tie the later j wins, extending the climb to the summit.
                    score >= bestScore &&
                    climbingGradePercent / gradePercent <= options.maxDiffRealGradeRatio

            if (qualifies) {
                bestScore = score
                best =
                    Candidate(
                        startIndex = i,
                        endIndex = j,
                        score = score,
                        positiveElevationM = positiveElevation,
                        negativeElevationM = negativeElevation,
                    )
            }
        }
        return best
    }

    /**
     * Greedily keep the best-scoring candidate and drop everything overlapping it, repeating
     * until nothing is left. Mirrors the reference's `removeIf`, including the fact that the
     * chosen candidate is itself removed by the overlap test.
     */
    private fun dedupeByScore(candidates: List<Candidate>): List<Candidate> {
        val remaining = candidates.sortedByDescending { it.score }.toMutableList()
        val kept = mutableListOf<Candidate>()
        while (remaining.isNotEmpty()) {
            val best = remaining.first()
            kept.add(best)
            remaining.removeAll { it.overlaps(best) }
        }
        return kept
    }

    private fun Candidate.toClimb(path: Path): Climb =
        Climb(
            startIndex = startIndex,
            endIndex = endIndex,
            startDistanceM = path.distance(startIndex),
            endDistanceM = path.distance(endIndex),
            startElevationM = path.elevation(startIndex),
            endElevationM = path.elevation(endIndex),
            positiveElevationM = positiveElevationM,
            negativeElevationM = negativeElevationM,
            parts = splitIntoParts(path, startIndex, endIndex),
        )

    private fun splitIntoParts(
        path: Path,
        startIndex: Int,
        endIndex: Int,
    ): List<ClimbPart> {
        val profile =
            (startIndex..endIndex).map { i ->
                // The third coordinate is zero: this is a 2D (distance, elevation) profile, and
                // Douglas-Peucker only needs a metric.
                Vector3D(path.distance(i), path.elevation(i), 0.0)
            }
        if (profile.size < 2) return emptyList()

        val elevationGain = path.elevation(endIndex) - path.elevation(startIndex)
        val tolerance =
            (elevationGain / PART_TOLERANCE_DIVISOR).coerceIn(MIN_PART_TOLERANCE_M, MAX_PART_TOLERANCE_M)

        val keptIndices = DouglasPeucker.simplifyIndices(profile, tolerance)
        return keptIndices.zipWithNext { a, b ->
            ClimbPart(
                startDistanceM = profile[a].x,
                startElevationM = profile[a].y,
                endDistanceM = profile[b].x,
                endElevationM = profile[b].y,
            )
        }
    }

    /** Internal candidate; the reference's `DetectedClimb`, reduced to what is actually used. */
    private data class Candidate(
        val startIndex: Int,
        val endIndex: Int,
        val score: Double,
        val positiveElevationM: Double,
        val negativeElevationM: Double,
    ) {
        /** True when this candidate shares any point with [other], or fully contains it. */
        fun overlaps(other: Candidate): Boolean =
            (other.startIndex <= startIndex && startIndex <= other.endIndex) ||
                (other.startIndex <= endIndex && endIndex <= other.endIndex) ||
                (startIndex <= other.startIndex && other.endIndex <= endIndex)
    }
}
