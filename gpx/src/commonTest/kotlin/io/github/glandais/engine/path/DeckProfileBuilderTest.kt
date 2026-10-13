package io.github.glandais.engine.path

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic profiles on the 5 m grid, DEFAULT options, for [DeckProfileBuilder]. */
class DeckProfileBuilderTest {
    private val options = DeckDetectorOptions.DEFAULT

    /** A route of [n] stations on open flat ground at [ground]: MNS = MNT. */
    private class Route(
        n: Int,
        ground: Double = 10.0,
    ) {
        val mnt = DoubleArray(n) { ground }
        val mns = DoubleArray(n) { ground }

        fun deck(
            range: IntRange,
            under: Double,
            top: (Int) -> Double,
        ): Route {
            for (k in range) {
                mnt[k] = under
                mns[k] = top(k)
            }
            return this
        }

        fun profile(): DeckProfile = DeckProfile.fromSurface(mns, mnt)
    }

    private fun build(route: Route): Pair<DeckDetection, DeckProfiles> {
        val p = route.profile()
        val d = DeckDetector.detect(p, options)
        return d to DeckProfileBuilder.build(p, d, options)
    }

    /** An accepted candidate built by hand, for the rules the detector cannot be made to produce. */
    private fun accepted(
        range: IntRange,
        deck: List<IntRange> = listOf(range),
    ) = DeckCandidate(range.first, range.last, range.first * 5.0, range.last * 5.0, null, null, deck)

    /** The station function of [r], pinned to [start] / [end], as a map station → elevation. */
    private fun stations(
        r: DeckReplacement,
        start: Double = 10.0,
        end: Double = 10.0,
    ): Map<Int, Double> = r.withEnds(start, end).withIndex().associate { (j, z) -> r.first + j to z }

    private fun maxStep(
        z: Map<Int, Double>,
        range: IntRange,
    ): Double = (range.first until range.last).maxOf { abs(z.getValue(it + 1) - z.getValue(it)) }

    /** Deterministic noise in `[0, 1)` (Knuth's multiplicative hash), as in [DeckDetectorTest]. */
    private fun noise(k: Int): Double = (k.toLong() * 2654435761L % 4294967296L) / 4294967296.0

    @Test
    fun `a flat deck over a canyon is flat, and its ramps hit the pins`() {
        // Banks at 10 m, MNH 2 m abutments, the river 10 m under the banks, the deck 8 m above them.
        val r = Route(200).deck(55..124, under = 8.0) { 18.0 }.deck(60..119, under = 0.0) { 18.0 }
        val (d, built) = build(r)
        assertEquals(55..124, d.accepted.single().let { it.first..it.last })
        val rep = built.replacements.single()
        assertEquals(54, rep.first)
        assertEquals(125, rep.last)
        assertTrue(rep.pinnedStart && rep.pinnedEnd)
        for (k in rep.anchorFirst..rep.anchorLast) assertEquals(18.0, rep.elevationM[k - rep.first], 1e-9, "station $k")
        assertTrue(rep.elevationM.first().isNaN() && rep.elevationM.last().isNaN(), "pins are the caller's")
        val z = stations(rep, start = 10.0, end = 11.0)
        assertEquals(10.0, z.getValue(54), 1e-12)
        assertEquals(11.0, z.getValue(125), 1e-12)
        assertTrue(z.values.all { it.isFinite() })
        // A linear ramp from the pin to the first anchor.
        val u = rep.anchorFirst
        for (k in 55 until u) assertEquals(10.0 + 8.0 * (k - 54) / (u - 54), z.getValue(k), 1e-9)
        assertTrue(built.dropped.isEmpty())
    }

    @Test
    fun `a crowned deck keeps its crown`() {
        // +3 m over 300 m, a parabola: 4 % at its ends, under the cap.
        val r = Route(220).deck(50..169, under = 0.0) { k -> 18.0 + 3.0 * (1 - ((k - 109.5) / 60.0).let { it * it }) }
        val (_, built) = build(r)
        val rep = built.replacements.single()
        for (k in rep.anchorFirst..rep.anchorLast) {
            assertEquals(r.mns[k], rep.elevationM[k - rep.first], 0.1, "station $k")
        }
        assertTrue(r.mns[109] - r.mns[rep.anchorFirst] > 1.0, "the crown is in the anchored part")
    }

    @Test
    fun `a rough canopy has no deck, so nothing is built`() {
        val r = Route(220)
        for (k in 50..139) r.mns[k] = 25.0 + 4.0 * noise(k)
        val (d, built) = build(r)
        assertTrue(d.accepted.isEmpty())
        assertTrue(built.replacements.isEmpty() && built.dropped.isEmpty())
    }

    @Test
    fun `MNS holes inside a deck are filled between the anchors`() {
        // A 2 km deck at 18 m; a NaN island of two stations, and 51 stations where the MNS reads the
        // water (MNH 0: Saint-Nazaire and Mauves hold such a stretch over the river).
        val r = Route(520).deck(50..449, under = 0.0) { 18.0 }
        for (k in 120..121) r.mns[k] = Double.NaN
        for (k in 200..250) r.mns[k] = 0.0
        val (d, built) = build(r)
        assertEquals(50..449, d.accepted.single().let { it.first..it.last })
        val rep = built.replacements.single()
        assertFalse(120 in rep.anchors || 121 in rep.anchors || 225 in rep.anchors)
        for (k in (115..125) + (195..255)) assertEquals(18.0, rep.elevationM[k - rep.first], 1e-9, "station $k")
    }

    @Test
    fun `the slope cap fills a steep sag and bounds the slope`() {
        // A flat 18 m deck with a clean cosine sag of 10 m over 100 m (15.7 % at its steepest).
        val r =
            Route(520).deck(50..449, under = 0.0) { k ->
                if (k in
                    300..320
                ) {
                    18.0 - 5.0 * (1 - cos(2 * PI * (k - 300) / 20.0))
                } else {
                    18.0
                }
            }
        val (d, built) = build(r)
        assertEquals(1, d.accepted.size)
        val rep = built.replacements.single()
        assertTrue(300 in rep.anchors && 310 in rep.anchors, "the sag is clean, so it anchors")
        val z = stations(rep)
        val cap = options.maxSlopeP95 * options.stepM
        assertTrue(maxStep(z, rep.anchorFirst..rep.anchorLast) <= cap + 1e-9, "max step ${maxStep(z, rep.anchorFirst..rep.anchorLast)}")
        assertTrue(z.getValue(310) > r.mns[310] + 1.0, "raised: ${z.getValue(310)}")
        // The cap only raises: the deck around the sag is untouched.
        for (k in (100..280) + (340..440)) assertEquals(18.0, z.getValue(k), 1e-9, "station $k")
    }

    @Test
    fun `known limit - a clean pylon spike is widened into a tent, not removed`() {
        // A +20 m spike on 3 stations survives a 5-station median and reads perfectly smooth, so it
        // anchors; the cap only raises, so it stays at its height with 10 % flanks.
        val r = Route(520).deck(50..449, under = 0.0) { k -> if (k in 249..251) 38.0 else 18.0 }
        val (_, built) = build(r)
        val rep = built.replacements.single()
        assertTrue(250 in rep.anchors)
        val z = stations(rep)
        val cap = options.maxSlopeP95 * options.stepM
        assertTrue(maxStep(z, rep.anchorFirst..rep.anchorLast) <= cap + 1e-9)
        assertEquals(38.0, z.getValue(250), 1e-9)
        assertEquals(38.0 - 10 * cap, z.getValue(261), 1e-9)
        // Flat again once the tent reaches the deck: 20 m / 0.5 m per station = 40 stations out.
        for (k in (100..208) + (292..440)) assertEquals(18.0, z.getValue(k), 1e-9, "station $k")
    }

    @Test
    fun `two spans over one canyon are joined, two over the ground are not`() {
        // Pont de Bellevue: the detector returns two parts 5 stations apart, the MNT of the gap
        // 6 m under the deck. Saumur: the gap is an island, on the line's level.
        fun route(gapMnt: Double): Route {
            val r = Route(260).deck(50..85, under = 0.0) { 18.0 }.deck(91..130, under = 0.0) { 18.0 }
            for (k in 86..90) {
                r.mnt[k] = gapMnt
                r.mns[k] = gapMnt
            }
            return r
        }
        val spans = DeckDetection(listOf(accepted(50..85), accepted(91..130)), emptyList(), 0)

        val canyon = route(12.0).profile()
        val joined = DeckProfileBuilder.build(canyon, spans, options).replacements.single()
        assertEquals(49..131, joined.first..joined.last)
        assertEquals(2, joined.spans.size)
        for (k in 80..95) assertEquals(18.0, joined.elevationM[k - joined.first], 1e-9, "station $k")

        val ground = route(17.5).profile()
        val apart = DeckProfileBuilder.build(ground, spans, options).replacements
        assertEquals(listOf(49..86, 90..131), apart.map { it.first..it.last })

        // A gap of mergeGapM or more is never joined, canyon or not.
        val far = Route(400).deck(50..85, under = 0.0) { 18.0 }.deck(146..180, under = 0.0) { 18.0 }
        for (k in 86..145) far.mnt[k] = 0.0
        val farSpans = DeckDetection(listOf(accepted(50..85), accepted(146..180)), emptyList(), 0)
        assertEquals(60, 146 - 85 - 1)
        assertEquals(2, DeckProfileBuilder.build(far.profile(), farSpans, options).replacements.size)
        val near = DeckDetection(listOf(accepted(50..85), accepted(145..180)), emptyList(), 0)
        far.mns[145] = 18.0
        assertEquals(1, DeckProfileBuilder.build(far.profile(), near, options).replacements.size, "59 stations apart: joined")
    }

    @Test
    fun `a deck at either end of the route is held flat to that end`() {
        val start = Route(200).deck(0..39, under = 0.0) { 18.0 }
        val atStart = DeckDetection(listOf(accepted(0..39)), emptyList(), 0)
        val s = DeckProfileBuilder.build(start.profile(), atStart, options).replacements.single()
        assertEquals(0, s.first)
        assertFalse(s.pinnedStart)
        assertTrue(s.pinnedEnd)
        val zs = stations(s, start = 999.0)
        for (k in 0..s.anchorLast) assertEquals(18.0, zs.getValue(k), 1e-9, "station $k")

        val end = Route(200).deck(160..199, under = 0.0) { 18.0 }
        val atEnd = DeckDetection(listOf(accepted(160..199)), emptyList(), 0)
        val e = DeckProfileBuilder.build(end.profile(), atEnd, options).replacements.single()
        assertEquals(199, e.last)
        assertFalse(e.pinnedEnd)
        val ze = stations(e, end = 999.0)
        for (k in e.anchorFirst..199) assertEquals(18.0, ze.getValue(k), 1e-9, "station $k")
    }

    @Test
    fun `a region with fewer than two anchors is dropped`() {
        val r = Route(200).deck(50..89, under = 0.0) { 18.0 }
        for (k in 50..89) if (k != 70) r.mns[k] = Double.NaN
        val one = DeckDetection(listOf(accepted(50..89)), emptyList(), 0)
        val built = DeckProfileBuilder.build(r.profile(), one, options)
        assertTrue(built.replacements.isEmpty())
        assertEquals(listOf(listOf(50..89)), built.dropped.map { reg -> reg.map { it.first..it.last } })
    }

    @Test
    fun `known limit - a flat roof merged within the gap is lifted to its top`() {
        // DeckDetectorTest's known limit: a smooth roof 40 stations past a bridge joins its span and
        // its deck mask. It anchors, so the road under it rides the roof.
        val r = Route(700).deck(100..299, under = 2.0) { 12.0 }
        for (k in 340..351) r.mns[k] = 16.0
        val (d, built) = build(r)
        assertEquals(listOf(100..299, 340..351), d.accepted.single().deckRanges)
        val rep = built.replacements.single()
        assertTrue(rep.anchors.any { it in 340..351 })
        val z = stations(rep)
        assertEquals(16.0, z.getValue(345), 1e-9, "the road under the roof is lifted from 10 m")
    }

    @Test
    fun `replaced stretches never overlap - adjacent spans join, a one-station gap shares its pin`() {
        // Two decks over the river, `gap` stations of bank between them, the bank on the line's
        // level so the canyon rule never joins them: only adjacency can.
        for (gap in 0..3) {
            val r = Route(300).deck(50..99, under = 0.0) { 18.0 }
            val second = 100 + gap
            r.deck(second until second + 50, under = 0.0) { 18.0 }
            for (k in 100 until second) {
                r.mnt[k] = 18.0
                r.mns[k] = 18.0
            }
            val spans = DeckDetection(listOf(accepted(50..99), accepted(second until second + 50)), emptyList(), 0)
            val reps = DeckProfileBuilder.build(r.profile(), spans, options).replacements
            assertEquals(if (gap == 0) 1 else 2, reps.size, "gap $gap")
            for (i in 0 until reps.size - 1) {
                assertTrue(reps[i].last <= reps[i + 1].first, "gap $gap: ${reps[i]} overlaps ${reps[i + 1]}")
            }
            if (gap == 1) assertEquals(reps[0].last, reps[1].first, "one station apart: the pin is shared")
        }
    }

    @Test
    fun `capSlope is the least majorant of the slope, and fillLinear fills between finite values`() {
        val z = doubleArrayOf(0.0, 0.0, 5.0, 0.0, 0.0, -9.0, 0.0)
        DeckProfileBuilder.capSlope(z, 1.0)
        assertEquals(listOf(3.0, 4.0, 5.0, 4.0, 3.0, 2.0, 1.0), z.toList())
        val f = doubleArrayOf(Double.NaN, 1.0, Double.NaN, Double.NaN, 4.0, Double.NaN)
        fillLinear(f)
        assertTrue(f[0].isNaN() && f[5].isNaN())
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), f.slice(1..4))
    }
}
