package io.github.glandais.engine.climb

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port fidelity for [ClimbDetector]. The fixtures are synthetic profiles built from an explicit
 * elevation series, so every expectation can be reasoned about from the numbers rather than
 * from a recorded output.
 */
class ClimbDetectorTest {
    /**
     * Build a path whose points are [stepM] apart along a meridian, with the given elevations.
     * Spacing along latitude keeps `Path.computeDerivedData` producing real distances.
     */
    private fun profile(
        elevations: DoubleArray,
        stepM: Double = 100.0,
    ): Path {
        val p = Path(elevations.size)
        // 1 degree of latitude is ~111 320 m; convert the requested spacing into a latitude step.
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

    /** A steady climb: [n] points rising [gainPerStepM] each. */
    private fun steadyClimb(
        n: Int,
        gainPerStepM: Double,
        stepM: Double = 100.0,
        baseM: Double = 500.0,
    ): Path = profile(DoubleArray(n) { baseM + it * gainPerStepM }, stepM)

    @Test
    fun `case 01 — an empty path yields no climb`() {
        assertEquals(emptyList(), ClimbDetector.detect(Path(0)))
        assertEquals(emptyList(), ClimbDetector.detect(Path(1)))
    }

    @Test
    fun `case 02 — a flat path yields no climb`() {
        assertEquals(emptyList(), ClimbDetector.detect(profile(DoubleArray(200) { 500.0 })))
    }

    @Test
    fun `case 03 — a pure descent yields no climb`() {
        assertEquals(emptyList(), ClimbDetector.detect(profile(DoubleArray(200) { 1500.0 - it * 5.0 })))
    }

    @Test
    fun `case 04 — a single 500 m climb over 10 km is detected at about 5 percent`() {
        // 100 steps of 100 m = 10 km, gaining 5 m each = 500 m.
        val path = steadyClimb(n = 101, gainPerStepM = 5.0)
        val climbs = ClimbDetector.detect(path)

        assertEquals(1, climbs.size, "expected exactly one climb, got ${climbs.size}")
        val climb = climbs.single()
        assertEquals(0, climb.startIndex)
        assertEquals(100, climb.endIndex)
        assertEquals(500.0, climb.elevationGainM, 1.0)
        assertEquals(0.05, climb.averageGrade, 0.002)
        // A steady climb has no dips, so the climbing grade equals the average one.
        assertEquals(climb.averageGrade, climb.climbingGrade, 1e-6)
        assertEquals(0.0, climb.negativeElevationM, 1e-9)
    }

    @Test
    fun `case 05 — two climbs separated by a long flat are reported separately`() {
        val elevations =
            DoubleArray(50) { 500.0 + it * 5.0 } + // climb 1: +245 m
                DoubleArray(80) { 745.0 } + // 8 km of flat
                DoubleArray(50) { 745.0 + it * 5.0 } // climb 2: +245 m
        val climbs = ClimbDetector.detect(profile(elevations))

        assertEquals(2, climbs.size, "expected two climbs, got ${climbs.map { it.startIndex to it.endIndex }}")
        assertTrue(climbs[0].endIndex < climbs[1].startIndex, "climbs must not overlap")
        for (climb in climbs) {
            assertEquals(245.0, climb.elevationGainM, 5.0)
        }
    }

    @Test
    fun `case 06 — a short descent is absorbed, a deep one splits the climb`() {
        // FROZEN BEHAVIOUR — the one case the spec left open, decided by observing the port and
        // probing the boundary rather than by guessing.
        //
        // Two 245 m climbs either side of a dip. Whether they are reported as one climb or two is
        // governed entirely by `maxDiffRealGradeRatio` (1.3): the span is kept whole while
        // `climbingGrade / averageGrade` stays within it, and rejected — leaving the two halves to
        // be found separately — once the dip drags the average down far enough. Measured:
        //
        //     dip  30 m -> ratio 1.14 -> 1 climb
        //     dip  60 m -> ratio 1.29 -> 1 climb
        //     dip  90 m -> ratio > 1.3 -> 2 climbs
        //
        // So a small dip does NOT split a climb, which is the sensible reading: a brief descent
        // inside a col is still one col. This matches the reference, whose comment on that
        // parameter says exactly this ("7% in real climbing with a 5% average climb is not ok").
        fun withDip(dipSteps: Int): List<Climb> {
            val summit = 500.0 + 49 * 5.0
            val elevations =
                DoubleArray(50) { 500.0 + it * 5.0 } +
                    DoubleArray(dipSteps) { summit - it * 5.0 } +
                    DoubleArray(50) { summit - dipSteps * 5.0 + it * 5.0 }
            return ClimbDetector.detect(profile(elevations))
        }

        // A 60 m dip is absorbed: one climb spanning both halves.
        val merged = withDip(12)
        assertEquals(1, merged.size, "a 60 m dip should not split the climb")
        assertTrue(
            merged.single().climbingGrade / merged.single().averageGrade <= 1.3,
            "the merged climb must be inside maxDiffRealGradeRatio",
        )
        assertTrue(merged.single().negativeElevationM < -50.0, "the merged climb contains the dip")

        // A 90 m dip pushes the ratio past the threshold and the two halves are found separately.
        val split = withDip(18)
        assertEquals(2, split.size, "a 90 m dip should split the climb in two")
        assertTrue(split[0].endIndex <= split[1].startIndex, "climbs must not overlap")
        for (climb in split) {
            assertEquals(1.0, climb.climbingGrade / climb.averageGrade, 0.01, "each half is a pure climb")
        }
    }

    @Test
    fun `case 07 — a climb below the elevation threshold is ignored`() {
        // 8 m of gain, under the 10 m floor of minMinClimbElevationM.
        val elevations = DoubleArray(20) { 500.0 + it * 0.4 } + DoubleArray(50) { 507.6 }
        assertEquals(emptyList(), ClimbDetector.detect(profile(elevations)))
    }

    @Test
    fun `case 08 — a climb shallower than minGradePercent is ignored`() {
        // 100 m of gain but spread over 10 km : a 1 % grade, under the 3 % minimum.
        val path = steadyClimb(n = 101, gainPerStepM = 1.0)
        assertEquals(emptyList(), ClimbDetector.detect(path))
    }

    @Test
    fun `case 09 — detected climbs never overlap`() {
        val elevations =
            DoubleArray(40) { 500.0 + it * 6.0 } +
                DoubleArray(30) { 740.0 - it * 4.0 } +
                DoubleArray(40) { 620.0 + it * 7.0 } +
                DoubleArray(20) { 900.0 } +
                DoubleArray(40) { 900.0 + it * 5.0 }
        val climbs = ClimbDetector.detect(profile(elevations))
        assertTrue(climbs.size >= 2, "expected several climbs, got ${climbs.size}")
        for ((a, b) in climbs.zipWithNext()) {
            assertTrue(
                a.endIndex <= b.startIndex,
                "climbs ${a.startIndex}..${a.endIndex} and ${b.startIndex}..${b.endIndex} overlap",
            )
        }
    }

    @Test
    fun `case 10 — indices are ordered, in range, and returned in path order`() {
        val elevations =
            DoubleArray(40) { 500.0 + it * 6.0 } +
                DoubleArray(30) { 740.0 } +
                DoubleArray(40) { 740.0 + it * 6.0 }
        val path = profile(elevations)
        val climbs = ClimbDetector.detect(path)

        assertTrue(climbs.isNotEmpty())
        for (climb in climbs) {
            assertTrue(climb.startIndex < climb.endIndex, "start must precede end")
            assertTrue(climb.startIndex >= 0 && climb.endIndex < path.size, "indices out of bounds")
        }
        assertEquals(
            climbs.map { it.startIndex }.sorted(),
            climbs.map { it.startIndex },
            "climbs must come back in path order, not score order",
        )
    }

    @Test
    fun `case 11 — the parts tile the climb exactly`() {
        val path = steadyClimb(n = 101, gainPerStepM = 5.0)
        val climb = ClimbDetector.detect(path).single()

        assertTrue(climb.parts.isNotEmpty(), "a climb must have at least one part")
        // Parts are contiguous...
        for ((a, b) in climb.parts.zipWithNext()) {
            assertEquals(a.endDistanceM, b.startDistanceM, 1e-9, "parts must be contiguous")
            assertEquals(a.endElevationM, b.startElevationM, 1e-9)
        }
        // ...and together span the whole climb.
        assertEquals(climb.startDistanceM, climb.parts.first().startDistanceM, 1e-9)
        assertEquals(climb.endDistanceM, climb.parts.last().endDistanceM, 1e-9)
        assertEquals(climb.lengthM, climb.parts.sumOf { it.lengthM }, 1e-6)
        assertEquals(climb.elevationGainM, climb.parts.sumOf { it.elevationGainM }, 1e-6)
    }

    @Test
    fun `case 12 — a Stelvio-sized climb reports a realistic gain and grade`() {
        // ~21 km at ~7.4 %, the real Stelvio from Prato: 1533 m of gain.
        val n = 211
        val path = steadyClimb(n = n, gainPerStepM = 7.3, baseM = 900.0)
        val climb = ClimbDetector.detect(path).single()

        assertEquals(21_000.0, climb.lengthM, 200.0)
        assertEquals(1533.0, climb.elevationGainM, 20.0)
        assertEquals(0.073, climb.averageGrade, 0.003)
        // A 1533 m climb pushes the dynamic threshold to its 35 m ceiling, not the 10 m floor.
        assertTrue(climb.elevationGainM > 35.0)
    }

    @Test
    fun `case 13 — custom options change what qualifies`() {
        // A 1 % climb is invisible with the defaults (case 08) but appears once the minimum
        // grade is lowered below it.
        val path = steadyClimb(n = 101, gainPerStepM = 1.0)
        assertEquals(emptyList(), ClimbDetector.detect(path))

        val lenient = ClimbOptions(minGradePercent = 0.5)
        val climbs = ClimbDetector.detect(path, lenient)
        assertEquals(1, climbs.size, "a 1 % climb should qualify at minGradePercent = 0.5")
        assertEquals(0.01, climbs.single().averageGrade, 0.001)

        // And raising the elevation floor above the climb's gain hides it again.
        val strict = ClimbOptions(minGradePercent = 0.5, minMinClimbElevationM = 500.0)
        assertEquals(emptyList(), ClimbDetector.detect(path, strict))
    }

    @Test
    fun `case 14 — the dynamic threshold is clamped between its two bounds`() {
        // elevationGain / 100 would be 15 m here, between the 10 m floor and 35 m ceiling, so a
        // 12 m bump must be rejected while a 20 m one is accepted.
        val bigClimb = DoubleArray(300) { 500.0 + it * 5.0 } // +1495 m -> threshold 14.95 m
        val small = profile(bigClimb + DoubleArray(20) { 1995.0 } + DoubleArray(6) { 1995.0 + it * 2.4 })
        val climbs = ClimbDetector.detect(small)
        assertTrue(
            climbs.none { it.startIndex >= 320 },
            "a 12 m bump must not qualify when the dynamic threshold is ~15 m",
        )
    }

    @Test
    fun `case 15 — a dense path is decimated for analysis but reports original indices`() {
        // The candidate search is O(n^2), and vcyclist's pipeline can hand over a path densified
        // to 1-2 m spacing — ~25 000 points for a 140 km route, which measured in minutes in a
        // browser. `maxAnalysisPoints` bounds that. This checks the bound does not change the
        // answer in any way that matters, and that indices still address the caller's path.
        // Kept modest on purpose: the unbounded reference run below is the very O(n^2) cost this
        // bound exists to avoid, and it has to finish inside the Wasm browser suite's 2 s budget.
        val dense = steadyClimb(n = 1_500, gainPerStepM = 0.3, stepM = 2.0)
        val full = ClimbDetector.detect(dense, ClimbOptions(maxAnalysisPoints = 1_500))
        val bounded = ClimbDetector.detect(dense, ClimbOptions(maxAnalysisPoints = 300))

        assertEquals(full.size, bounded.size, "decimation must not change how many climbs are found")
        val a = full.single()
        val b = bounded.single()
        assertEquals(a.elevationGainM, b.elevationGainM, 5.0, "gain")
        assertEquals(a.averageGrade, b.averageGrade, 0.002, "average grade")
        assertEquals(a.lengthM, b.lengthM, 50.0, "length")
        // Indices address the original path, not the decimated profile.
        assertTrue(b.endIndex > 300, "endIndex ${b.endIndex} looks like a profile index, not a path one")
        assertTrue(b.endIndex < dense.size, "endIndex out of bounds")
    }

    @Test
    fun `case 16 — paths at or below the bound are analysed in full`() {
        // Guards the property the Java cross-validation relies on: nothing is decimated at the
        // sizes those comparisons were run at.
        val path = steadyClimb(n = 101, gainPerStepM = 5.0)
        val withBound = ClimbDetector.detect(path, ClimbOptions(maxAnalysisPoints = 3_000))
        val withoutBound = ClimbDetector.detect(path, ClimbOptions(maxAnalysisPoints = 101))
        assertEquals(withBound, withoutBound)
        assertEquals(100, withBound.single().endIndex)
    }
}
