package io.github.glandais.engine.climb

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Counterexamples found by an IEEE-754 Lean model of [ClimbDetector]'s scoring, decimation and
 * Douglas-Peucker part split. Each test asserts a documented contract.
 */
class FormalModelClimbDetectorTest {
    /** A profile with explicit cumulative distances (m) along a meridian. */
    private fun profileAt(
        distances: DoubleArray,
        elevations: DoubleArray,
    ): Path {
        val p = Path(elevations.size)
        for (i in elevations.indices) {
            p.setLatitude(i, (45.0 + distances[i] / 111_320.0) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, 6.0 * MathConstants.DEG_TO_RAD)
            p.setElevation(i, elevations[i])
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()
        return p
    }

    /** A profile with evenly spaced points [stepM] apart along a meridian. */
    private fun evenProfile(
        elevations: DoubleArray,
        stepM: Double = 100.0,
    ): Path {
        val p = Path(elevations.size)
        val latStep = stepM / 111_320.0
        for (i in elevations.indices) {
            p.setLatitude(i, (45.0 + i * latStep) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, 6.0 * MathConstants.DEG_TO_RAD)
            p.setElevation(i, elevations[i])
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()
        return p
    }

    /**
     * `Climb.climbingGrade` is documented as "the quantity [ClimbOptions.maxDiffRealGradeRatio]
     * bounds". The detector bounds `positive / Σ(rising segment lengths)`, while the reported
     * figure divides by the length of the rising Douglas-Peucker *parts*. A false flat that rises
     * gently after a small (< 10 m tolerance) dip is counted as climbing distance by the detector
     * but folded into a net-negative part by the split, so the reported ratio escapes the bound.
     */
    @Test
    fun `reported climbingGrade over averageGrade respects maxDiffRealGradeRatio`() {
        val d = ArrayList<Double>()
        val e = ArrayList<Double>()
        d += 0.0
        e += 0.0

        fun piece(
            len: Double,
            de: Double,
            k: Int,
        ) {
            val d0 = d.last()
            val e0 = e.last()
            for (t in 1..k) {
                d += d0 + len * t / k
                e += e0 + de * t / k
            }
        }
        piece(1000.0, 100.0, 10) // 10 % ramp
        piece(10.0, -9.0, 1) // a 9 m dip, inside the 10 m part tolerance
        piece(990.0, 8.0, 10) // gently rising false flat
        piece(1000.0, 100.0, 10) // 10 % ramp
        val path = profileAt(d.toDoubleArray(), e.toDoubleArray())

        val options = ClimbOptions.DEFAULT
        val climbs = ClimbDetector.detect(path, options)
        assertTrue(climbs.isNotEmpty(), "expected the climb to be detected")
        for (c in climbs) {
            val ratio = c.climbingGrade / c.averageGrade
            assertTrue(
                ratio <= options.maxDiffRealGradeRatio + 1e-9,
                "climb ${c.startIndex}..${c.endIndex}: climbingGrade=${c.climbingGrade} " +
                    "averageGrade=${c.averageGrade} ratio=$ratio > maxDiffRealGradeRatio=" +
                    "${options.maxDiffRealGradeRatio}",
            )
        }
    }

    /**
     * [ClimbOptions.maxAnalysisPoints] is documented as an *upper bound* on how many points the
     * candidate search looks at, and the decimation is documented to always keep the first and the
     * last point. With `maxAnalysisPoints = 2` the analysed set is therefore forced to be exactly
     * `{0, n - 1}`, so every reported climb index must be one of those two.
     */
    @Test
    fun `maxAnalysisPoints is an upper bound on the analysed points`() {
        // 5 points, 100 m apart: a 10 % climb over 0..3, then a drop back to the start height.
        val path = evenProfile(doubleArrayOf(0.0, 10.0, 20.0, 30.0, 0.0))
        val climbs = ClimbDetector.detect(path, ClimbOptions(maxAnalysisPoints = 2))
        val allowed = setOf(0, path.size - 1)
        val reported = climbs.flatMap { listOf(it.startIndex, it.endIndex) }.toSet()
        assertTrue(
            allowed.containsAll(reported),
            "maxAnalysisPoints = 2 must analyse only {0, ${path.size - 1}}, but climbs reference indices $reported",
        )
    }
}
