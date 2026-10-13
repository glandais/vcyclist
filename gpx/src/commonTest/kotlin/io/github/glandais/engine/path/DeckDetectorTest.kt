package io.github.glandais.engine.path

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic profiles on the 5 m grid; every expected span is derived by hand from the rules of
 * `docs/deck-strava-data/analysis/analyze.py`, which [DeckDetector] ports.
 */
class DeckDetectorTest {
    /** A route of [n] stations on open flat ground at 10 m: MNS = MNT, MNH = 0. */
    private class Route(
        n: Int,
        ground: Double = 10.0,
    ) {
        val mnt = DoubleArray(n) { ground }
        val mns = DoubleArray(n) { ground }

        /** A deck over [range] at [top], with the ground under it at [under]. */
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

    private fun detect(
        route: Route,
        options: DeckDetectorOptions = DeckDetectorOptions.DEFAULT,
    ) = DeckDetector.detect(route.profile(), options)

    /** Bridge over a 2 m river, 10 m banks, flat 12 m deck from 55 to 124, 0.5–2 m MNH shoulders. */
    private fun canyonBridge(): Route =
        Route(200)
            .deck(55..124, under = 10.0) { 12.0 } // abutments: MNH 2
            .deck(60..119, under = 2.0) { 12.0 } // over the river: MNH 10

    /** A parabolic deck over 55..124 whose end grade is [endGrade], above a 2 m valley. */
    private fun camberedDeck(
        endGrade: Double,
        piles: Boolean = false,
    ): Route {
        val half = 35 * 5.0 // centre at 89.5, ends 175 m away
        val r = Route(200).deck(55..124, under = 2.0) { k -> 30.0 - endGrade / (2 * half) * ((k - 89.5) * 5.0).let { it * it } }
        if (piles) {
            // Pier tops classified as ground: MNH drops to 2 m on two stations every 20.
            for (k in listOf(74, 75, 94, 95, 114, 115)) r.mnt[k] = r.mns[k] - 2.0
        }
        return r
    }

    private fun only(detection: DeckDetection): DeckCandidate {
        val all = detection.accepted + detection.rejected
        assertEquals(1, all.size, "expected one candidate, got $all")
        return all.single()
    }

    // ── 1. Options ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the default windows are the reference detector's station counts`() {
        val o = DeckDetectorOptions.DEFAULT
        assertEquals(3, o.minTallRunStations)
        assertEquals(5, o.surfaceMedianWindowStations)
        assertEquals(7, o.roughnessWindowStations)
        assertEquals(60, o.mergeGapStations)
        assertEquals(2, o.slopeHalfStations)
        assertEquals(5, o.shortSpanIntervals)
        assertEquals(8, o.deckMinRunStations)
        assertEquals(12, o.smallSpanIntervals)
        assertEquals(20, o.maxAbutmentStations)
        assertEquals(2, o.maxDeckGapStations)
    }

    @Test
    fun `a window that is not a whole odd number of stations is refused`() {
        assertFailsWith<IllegalArgumentException> { DeckDetectorOptions.DEFAULT.copy(stepM = 4.0) }
        assertFailsWith<IllegalArgumentException> { DeckDetectorOptions.DEFAULT.copy(surfaceMedianWindowM = 20.0) }
        assertFailsWith<IllegalArgumentException> { DeckDetectorOptions.DEFAULT.copy(roughnessWindowM = 30.0) }
        assertFailsWith<IllegalArgumentException> { DeckDetectorOptions.DEFAULT.copy(stepM = 0.0) }
    }

    @Test
    fun `a long span without a slope sample, or a NaN threshold, is refused`() {
        // shortSpanM = 0: a 3-station span would be long, with no inner slope sample, hence NO_DATA.
        assertFailsWith<IllegalArgumentException> { DeckDetectorOptions.DEFAULT.copy(shortSpanM = 0.0) }
        assertFailsWith<IllegalArgumentException> { DeckDetectorOptions.DEFAULT.copy(shortSpanM = 10.0) }
        DeckDetectorOptions.DEFAULT.copy(shortSpanM = 15.0) // 3 intervals = 2h − 1: the smallest legal value
        assertFailsWith<IllegalArgumentException> { DeckDetectorOptions.DEFAULT.copy(maxSlopeP95 = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { DeckDetectorOptions.DEFAULT.copy(tallMnhM = Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { DeckDetectorOptions.DEFAULT.copy(maxAbutmentM = -5.0) }
    }

    // ── 2–3. Open road, canyon bridge ──────────────────────────────────────────────────────────

    @Test
    fun `a flat open road has nothing to say`() {
        val d = detect(Route(300))
        assertEquals(DeckDetection(emptyList(), emptyList(), 0), d)
    }

    @Test
    fun `a flat bridge over a canyon is accepted, out to its abutments`() {
        val c = only(detect(canyonBridge()))
        assertNull(c.rejection)
        assertEquals(55, c.first)
        assertEquals(124, c.last)
        assertEquals(275.0, c.startM, 1e-12)
        assertEquals(620.0, c.endM, 1e-12)
        val m = assertNotNull(c.metrics)
        assertEquals(350.0, m.lengthM, 1e-12)
        assertEquals(m.lengthM - 5.0, c.endM - c.startM, 1e-12, "endM − startM counts intervals, lengthM stations")
        assertEquals(0.0, m.slopeP95, 1e-12)
        assertEquals(0.0, m.roughnessM, 1e-12)
        assertEquals(1.0, m.smoothFraction, 1e-12)
        assertEquals(10.0, m.canyonM, 1e-12)
        assertEquals(8.0, m.mntDropM, 1e-12)
        assertEquals(12.0, m.deckTopM, 1e-12)
        assertEquals(listOf(55..124), c.deckRanges)
    }

    // ── 4. Slope ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a cambered deck with a 5_9 percent end grade and piles in the MNT is accepted`() {
        val d = detect(camberedDeck(0.059, piles = true))
        val c = d.accepted.single()
        assertEquals(55..124, c.first..c.last)
        val m = assertNotNull(c.metrics)
        assertTrue(m.slopeP95 in 0.045..0.059, "P95 ${m.slopeP95}")
        assertTrue(m.slopeMax < 0.059, "max ${m.slopeMax}")
        assertTrue(d.rejected.isEmpty(), "${d.rejected}")
    }

    @Test
    fun `the same deck at a 12 percent end grade is too steep`() {
        val c = only(detect(camberedDeck(0.12)))
        assertEquals(DeckRejection.STEEP, c.rejection)
        assertTrue(assertNotNull(c.metrics).slopeP95 > 0.10)
        assertTrue(c.deckRanges.isEmpty())
    }

    @Test
    fun `a 10 percent deck sits on the inclusive boundary`() {
        fun ramp(perStation: Double) = Route(200).deck(55..124, under = 2.0) { k -> 30.0 + perStation * (k - 55) }
        val at = only(detect(ramp(0.5)))
        val p95 = assertNotNull(at.metrics).slopeP95
        assertEquals(0.10, p95, 1e-12)
        assertTrue(p95 <= DeckDetectorOptions.DEFAULT.maxSlopeP95, "the boundary is inclusive: $p95")
        assertNull(at.rejection)
        assertEquals(DeckRejection.STEEP, only(detect(ramp(0.505))).rejection)
    }

    /**
     * Treetops: ±3 m of deterministic noise without a period of 2 — an alternating ±3 m passes a
     * 5-station median untouched and would read as a smooth deck.
     */
    private fun crown(k: Int): Double = ((k * 7919) % 13 - 6) * 0.5

    // ── 5. Canopy ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a rough canopy over a road is rejected as one merged group, never measured`() {
        val r = Route(220)
        for (k in r.mnt.indices) r.mnt[k] = 100.0 + 0.1 * k
        for (k in r.mns.indices) r.mns[k] = r.mnt[k]
        for (k in (50..89) + (100..139)) r.mns[k] = r.mnt[k] + 15.0 + crown(k)
        val d = detect(r)
        assertTrue(d.accepted.isEmpty())
        val c = d.rejected.single()
        assertEquals(DeckRejection.ROUGH, c.rejection)
        assertEquals(50..139, c.first..c.last)
        assertNull(c.metrics)
    }

    // ── 6. Canyon test ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `an elevated span with no drop under its ends is no bridge, and the margin is strict`() {
        fun roof(under: Double) = Route(200, ground = 50.0).deck(50..89, under = under) { 60.0 }
        val flat = only(detect(roof(50.0)))
        assertEquals(DeckRejection.NO_CANYON, flat.rejection)
        assertEquals(0.0, assertNotNull(flat.metrics).mntDropM, 1e-12)
        assertEquals(DeckRejection.NO_CANYON, only(detect(roof(47.0))).rejection)
        assertNull(only(detect(roof(46.99))).rejection)
    }

    // ── 7–8. Run length, smooth fraction ──────────────────────────────────────────────────────

    @Test
    fun `a tall run needs three stations`() {
        val two = detect(Route(100).deck(50..51, under = 0.0) { 20.0 })
        assertTrue(two.accepted.isEmpty() && two.rejected.isEmpty())
        assertEquals(1, two.tallRunsTooShort)
        val three = detect(Route(100).deck(50..52, under = 0.0) { 20.0 })
        assertEquals(0, three.tallRunsTooShort)
        assertEquals(50..52, only(three).let { it.first..it.last })
    }

    @Test
    fun `a run is kept at exactly 60 percent smooth stations`() {
        // Run 100..104 on flat ground; a -20 m MNS dip (MNH < 0, never tall) at [dip] makes the
        // stations within the 7-station roughness window of it rough.
        fun withDip(dip: Int): DeckCandidate {
            val r = Route(200).deck(100..104, under = 10.0) { 20.0 }
            r.mns[dip] = -10.0
            return only(detect(r))
        }
        val kept = withDip(106) // rough at 103, 104: 3 of 5 smooth
        assertEquals(DeckRejection.NO_CANYON, kept.rejection)
        assertEquals(0.6, assertNotNull(kept.metrics).smoothFraction, 1e-12)
        val rough = withDip(105) // rough at 102..104: 2 of 5
        assertEquals(DeckRejection.ROUGH, rough.rejection)
    }

    // ── 9. Merge ───────────────────────────────────────────────────────────────────────────────

    /**
     * Two decks over a river with an island between them: on the island the MNS stays at deck
     * height but the MNH is 2 m (not tall), except one station at 0.5 m where the extension stops.
     */
    private fun twoSpans(missing: Int): Route {
        val firstEnd = 69
        val secondStart = firstEnd + missing + 1
        val r = Route(300).deck(50..secondStart + 19, under = 2.0) { 12.0 }
        for (k in firstEnd + 1 until secondStart) r.mnt[k] = 10.0
        r.mnt[(firstEnd + secondStart) / 2] = 11.5
        return r
    }

    /** The island's MNH 2 m is a 30-station abutment: past [DeckDetectorOptions.maxAbutmentM]. */
    private val longAbutments = DeckDetectorOptions.DEFAULT.copy(maxAbutmentM = 1000.0)

    @Test
    fun `smooth runs merge across fewer than 60 missing stations, and the span covers the gap`() {
        val merged = detect(twoSpans(59), longAbutments)
        assertEquals(listOf(50..148), merged.accepted.map { it.first..it.last })
        val apart = detect(twoSpans(60), longAbutments)
        assertEquals(listOf(50..98, 100..149), apart.accepted.map { it.first..it.last })
    }

    // ── 10. Short spans ────────────────────────────────────────────────────────────────────────

    /** A 5-station deck (Échevis' shape) whose MNS alternates by ±[noise] on its stations. */
    private fun shortDeck(
        noise: Double,
        under: Double = 2.0,
    ): Route = Route(100).deck(50..54, under = under) { k -> 20.0 + if (k % 2 == 0) noise else -noise }

    @Test
    fun `a short span is judged on its roughness`() {
        val smooth = only(detect(shortDeck(0.1)))
        assertNull(smooth.rejection, "${smooth.metrics}")
        val m = assertNotNull(smooth.metrics)
        assertTrue(m.roughnessM < 0.4, "${m.roughnessM}")
        assertTrue(m.slopeP95.isNaN() && m.slopeMax.isNaN())
        val rough = only(detect(shortDeck(0.45)))
        assertEquals(DeckRejection.SHORT_ROUGH, rough.rejection, "${rough.metrics}")
        assertTrue(assertNotNull(rough.metrics).roughnessM in 0.4..0.8)
        assertEquals(DeckRejection.NO_CANYON, only(detect(shortDeck(0.1, under = 10.0))).rejection)
    }

    // ── Bellevue ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a deck whose edge alternates deck and void passes at 0_8 m, not at the old 0_35 m`() {
        // Pont de Bellevue: the cycle lane follows the deck edge, so one station in three reads
        // the void beside it, a metre lower. Roughness lands between the two thresholds.
        val r = Route(200).deck(55..124, under = 2.0) { k -> if (k % 3 == 0) 11.0 else 12.0 }
        val c = only(detect(r))
        assertNull(c.rejection)
        val rough = assertNotNull(c.metrics).roughnessM
        assertTrue(rough > 0.35 && rough < 0.8, "roughness $rough")
        assertEquals(DeckRejection.ROUGH, only(detect(r, DeckDetectorOptions.DEFAULT.copy(maxRoughnessM = 0.35))).rejection)
    }

    // ── 11. Edges ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a deck touching either end of the route reads its own end MNT as the outer bank`() {
        val start = only(detect(Route(100).deck(0..29, under = 2.0) { 12.0 }))
        assertEquals(0..29, start.first..start.last)
        assertEquals(DeckRejection.NO_CANYON, start.rejection)
        val end = only(detect(Route(100).deck(70..99, under = 2.0) { 12.0 }))
        assertEquals(70..99, end.first..end.last)
        assertEquals(DeckRejection.NO_CANYON, end.rejection)
        // Not "no canyon by construction": the clamped value is one of two, and the span's minimum
        // can still lie more than 3 m below both. Here mnt[0] = mnt[19] = 100, the span bottoms at 90.
        val mns = DoubleArray(30) { if (it < 19) 106.0 else 100.0 }
        val mnt = DoubleArray(30) { if (it in 1..18) 90.0 else 100.0 }
        val clamped = only(DeckDetector.detect(DeckProfile.fromSurface(mns, mnt)))
        assertEquals(0..18, clamped.first..clamped.last)
        assertNull(clamped.rejection)
        assertEquals(10.0, assertNotNull(clamped.metrics).mntDropM, 1e-12)
    }

    // ── 12. No data ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `no data outside the coverage is no detection`() {
        val nan = DoubleArray(100) { Double.NaN }
        assertEquals(DeckDetection(emptyList(), emptyList(), 0), DeckDetector.detect(DeckProfile(nan, nan, nan)))
        assertEquals(
            DeckDetection(emptyList(), emptyList(), 0),
            DeckDetector.detect(DeckProfile(DoubleArray(0), DoubleArray(0), DoubleArray(0))),
        )
    }

    @Test
    fun `an MNS hole inside a deck is bridged by the nan-median`() {
        val r = canyonBridge()
        val mnh = DoubleArray(r.mns.size) { r.mns[it] - r.mnt[it] }
        for (k in 80..82) r.mns[k] = Double.NaN
        val c = only(DeckDetector.detect(DeckProfile(mnh, r.mns, r.mnt)))
        assertNull(c.rejection)
        assertEquals(55..124, c.first..c.last)
        val m = assertNotNull(c.metrics)
        assertEquals(0.0, m.slopeP95, 1e-12)
        assertTrue(m.roughnessM.isNaN(), "roughness is NaN within 3 stations of the hole")
        assertEquals(17.0 / 20.0, m.smoothFraction, 1e-12)
    }

    @Test
    fun `an MNT hole on the span is no data`() {
        val r = canyonBridge()
        r.mnt[90] = Double.NaN
        val mnh = DoubleArray(r.mns.size) { 10.0.takeIf { k -> it in 60..119 } ?: (r.mns[it] - r.mnt[it]) }
        assertEquals(DeckRejection.NO_DATA, only(DeckDetector.detect(DeckProfile(mnh, r.mns, r.mnt))).rejection)
    }

    @Test
    fun `a NaN island of two stations in the extension is stepped over`() {
        val r = canyonBridge()
        val mnh = DoubleArray(r.mns.size) { r.mns[it] - r.mnt[it] }
        mnh[57] = Double.NaN
        mnh[56] = Double.NaN
        val c = only(DeckDetector.detect(DeckProfile(mnh, r.mns, r.mnt)))
        assertEquals(55..124, c.first..c.last)
        assertNull(c.rejection)
    }

    @Test
    fun `a wider NaN stops the extension, and the span is no data rather than no canyon`() {
        val r = canyonBridge()
        for (k in 56..58) r.mns[k] = Double.NaN
        val c = only(detect(r))
        assertEquals(59..124, c.first..c.last)
        assertEquals(DeckRejection.NO_DATA, c.rejection)
    }

    /** fromSurface: one no-data MNS pixel is a NaN MNH, the normal case on IGN's raw layers. */
    @Test
    fun `one no-data MNS station over the river keeps the bridge`() {
        for (hole in listOf(62, 70, 117)) {
            val r = canyonBridge()
            r.mns[hole] = Double.NaN
            val d = detect(r)
            val c = d.accepted.single()
            assertEquals(55..124, c.first..c.last, "hole at $hole")
            assertEquals(listOf(55..124), c.deckRanges, "hole at $hole: the deck mask closes it")
            assertTrue(d.rejected.isEmpty(), "hole at $hole: ${d.rejected}")
        }
    }

    @Test
    fun `a low MNS outlier inside a deck is closed in the deck mask, a three-station hole is not`() {
        val low = canyonBridge()
        low.mns[90] = low.mnt[90] - 1e6
        assertEquals(
            listOf(55..124),
            low
                .let { detect(it) }
                .accepted
                .single()
                .deckRanges,
        )
        val wide = canyonBridge()
        for (k in 70..72) wide.mns[k] = Double.NaN
        assertEquals(listOf(55..69, 73..124), detect(wide).accepted.single().deckRanges)
    }

    @Test
    fun `tall runs with a valid MNH but no MNS are no data, not canopy`() {
        val nan = DoubleArray(3) { Double.NaN }
        val c = only(DeckDetector.detect(DeckProfile(DoubleArray(3) { 10.0 }, nan, nan)))
        assertEquals(DeckRejection.NO_DATA, c.rejection)
        assertNull(c.metrics)
    }

    // ── 13. Overlap ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `candidates whose extensions overlap become one span`() {
        // 80 missing stations: two candidates, but the island's MNH 2 joins their extensions.
        val r = Route(300).deck(50..169, under = 2.0) { 12.0 }
        for (k in 70..149) r.mnt[k] = 10.0
        val d = detect(r, longAbutments)
        assertEquals(listOf(50..169), d.accepted.map { it.first..it.last })
        assertTrue(d.rejected.isEmpty())
    }

    // ── 14. Deck mask ──────────────────────────────────────────────────────────────────────────

    /** Tall runs [a] and [b] over a 2 m river, a flat 20 m deck between them over 19.5 m ground, and a 2 m MNH blip. */
    private fun deckWithBlip(
        a: IntRange,
        blip: IntRange,
        b: IntRange,
    ): Route {
        val r = Route(200).deck(a.first..b.last, under = 19.5) { 20.0 }
        for (k in a + b) r.mnt[k] = 2.0
        for (k in blip) r.mnt[k] = 18.0
        return r
    }

    @Test
    fun `the deck mask keeps runs of 8 stations, or every run on a span of 12 intervals or less`() {
        val long = only(detect(deckWithBlip(100..107, 115..117, 125..132)))
        assertNull(long.rejection)
        assertEquals(100..132, long.first..long.last)
        assertEquals(listOf(100..107, 125..132), long.deckRanges)
        val small = only(detect(deckWithBlip(100..102, 106..106, 110..112)))
        assertNull(small.rejection)
        assertEquals(listOf(100..102, 106..106, 110..112), small.deckRanges)
        // Holes of two stations close (maxDeckGapStations): the median over them is still the deck.
        assertEquals(listOf(100..110), only(detect(deckWithBlip(100..102, 105..105, 108..110))).deckRanges)
    }

    // ── Abutments ──────────────────────────────────────────────────────────────────────────────

    /** A 40-station bridge over a canyon, then [field] stations of MNH 2 m (crops, an embankment). */
    private fun bridgeThenField(field: Int): Route {
        val r = Route(800).deck(100..139, under = 2.0) { 12.0 }
        for (k in 140 until 140 + field) r.mns[k] = r.mnt[k] + 2.0
        return r
    }

    @Test
    fun `an abutment is at most 100 m, a longer MNH stretch is left out of the span`() {
        val abutment = detect(bridgeThenField(20)).accepted.single()
        assertEquals(100..159, abutment.first..abutment.last)
        val field = detect(bridgeThenField(21)).accepted.single()
        assertEquals(100..139, field.first..field.last)
        assertEquals(listOf(100..139), field.deckRanges)
        val crops = detect(bridgeThenField(400)).accepted.single()
        assertEquals(listOf(100..139), crops.deckRanges)
    }

    // ── Known limits (kept from analyze.py, for E3) ───────────────────────────────────────────

    @Test
    fun `known limit - a roof within the merge gap borrows a bridge's canyon`() {
        val r = Route(700).deck(100..299, under = 2.0) { 12.0 }
        for (k in 340..351) r.mns[k] = 16.0
        val c = detect(r).accepted.single()
        assertEquals(100..351, c.first..c.last)
        assertEquals(listOf(100..299, 340..351), c.deckRanges)
    }

    @Test
    fun `known limit - trees touching a deck share its tall run`() {
        val r = Route(500).deck(100..299, under = 2.0) { 12.0 }
        for (k in 300..315) r.mns[k] = 22.0 + crown(k)
        assertEquals(100..315, detect(r).accepted.single().let { it.first..it.last })
    }

    // ── E2 ter: steep bridges ──────────────────────────────────────────────────────────────────

    /**
     * Ground at 0 m over 150 m on each side, a deck over [deckStations] stations from station 30,
     * the ground under it at [under]: E2 ter's synthetic bridge. [top] gives the deck MNS by station
     * offset from the deck's first station; [right] is the ground level past the deck.
     */
    private fun steepBridge(
        deckStations: Int = 300,
        under: (Int) -> Double = { -8.0 },
        right: Double = 0.0,
        top: (Int) -> Double = { j -> 45.0 - 0.3 * abs(j - 150) },
    ): Route {
        val r = Route(deckStations + 60, ground = 0.0)
        for (k in 30 + deckStations until r.mns.size) {
            r.mns[k] = right
            r.mnt[k] = right
        }
        return r.deck(30 until 30 + deckStations, under = 0.0) { k -> top(k - 30) }.also { d ->
            for (k in 30 until 30 + deckStations) d.mnt[k] = under(k - 30)
        }
    }

    /**
     * Deterministic noise in `[0, 1)`, Knuth's multiplicative hash: neighbours are uncorrelated, so
     * the nan-median does not follow it and the MNS is rough ([crown]'s `2k mod 13` is a sawtooth).
     */
    private fun noise(k: Int): Double = (k.toLong() * 2654435761L % 4294967296L) / 4294967296.0

    @Test
    fun `edge mixing sag is bridged`() {
        // Cheviré: three runs where the MNS alternates the deck and 5–15 m below it.
        val r = steepBridge()
        for (run in listOf(80..99, 140..164, 210..234)) {
            for (k in run) if (k % 2 == 1) r.mns[k] -= 5.0 + 10.0 * noise(k)
        }
        val python = detect(r, DeckDetectorOptions.PYTHON)
        assertEquals(DeckRejection.STEEP, python.rejected.single().rejection)
        val c =
            detect(r).let {
                assertTrue(it.rejected.isEmpty(), "${it.rejected}")
                it.accepted.single()
            }
        assertEquals(30..329, c.first..c.last)
        val m = assertNotNull(c.metrics)
        assertTrue(m.slopeOnCleanDeck)
        assertTrue(m.slopeP95 <= 0.07, "bridged P95 ${m.slopeP95}")
        assertTrue(m.rawSlopeP95 > 0.10, "raw P95 ${m.rawSlopeP95}")
        assertTrue(m.cleanFraction in 0.25..1.0, "clean ${m.cleanFraction}")
    }

    @Test
    fun `one-sided hole band is bridged`() {
        // Millau: a straight 3 % deck 80 m above the valley, every other MNS 10–60 m low over 100 stations.
        val r = steepBridge(under = { -80.0 }, right = 44.85) { j -> 0.15 * j }
        for (k in 150..249) if (k % 2 == 0) r.mns[k] -= 10.0 + 50.0 * noise(k)
        val c = detect(r).accepted.single()
        val m = assertNotNull(c.metrics)
        assertTrue(m.slopeOnCleanDeck)
        assertEquals(0.03, m.slopeP95, 0.005)
        assertTrue(m.rawSlopeP95 > 0.10, "raw P95 ${m.rawSlopeP95}")
    }

    @Test
    fun `pylon spikes and an impossible MNT`() {
        // Normandie: a 2 km deck at ±3 %, two 40-station pylon zones 10–45 m above it, a 6-station
        // hole 25 m below it, and 80 stations where the MNT lies 40 m above the MNS.
        val r = steepBridge(deckStations = 400, right = 0.0) { j -> 30.0 - 0.15 * abs(j - 200) }
        for (k in (100..139) + (160..199)) r.mns[k] += 10.0 + 35.0 * noise(k)
        for (k in 225..230) r.mns[k] -= 25.0
        for (k in 330..409) r.mnt[k] = r.mns[k] + 40.0
        val python = detect(r, DeckDetectorOptions.PYTHON)
        assertEquals(listOf(DeckRejection.STEEP), python.rejected.map { it.rejection }, "PYTHON: the pylon side is steep")
        assertEquals(410..429, python.accepted.single().let { it.first..it.last }, "PYTHON: the far end is a separate span")
        val split = detect(r, DeckDetectorOptions.DEFAULT.copy(mntAboveMnsAsTall = false))
        assertEquals(listOf(30..329, 410..429), split.accepted.map { it.first..it.last }, "bridged, but split")
        val d = detect(r)
        assertTrue(d.rejected.isEmpty(), "${d.rejected}")
        val c = d.accepted.single()
        assertEquals(30..429, c.first..c.last)
        assertEquals(listOf(30..429), c.deckRanges)
        assertTrue(assertNotNull(c.metrics).slopeP95 <= 0.035, "P95 ${c.metrics?.slopeP95}")
    }

    @Test
    fun `bridging measures the step across a rough gap`() {
        // Two smooth plateaus at 10 m and 30 m, 10 rough stations between: the bridged profile keeps
        // the 20 m step, so the span is steep — not a percentile over the clean subset.
        val r =
            steepBridge(deckStations = 130, right = 30.0) { j ->
                when {
                    j < 60 -> 10.0
                    j < 70 -> 15.0 + 10.0 * noise(j)
                    else -> 30.0
                }
            }
        val c = only(detect(r))
        assertEquals(DeckRejection.STEEP, c.rejection)
        val m = assertNotNull(c.metrics)
        assertTrue(m.slopeOnCleanDeck)
        assertTrue(m.slopeP95 > 0.10, "P95 ${m.slopeP95}")
    }

    @Test
    fun `a rough tail is not bridged`() {
        // Haudaudine: a flat deck whose last 40 stations, up to the end of the span, are canopy.
        val r = steepBridge(deckStations = 240) { j -> if (j < 200) 10.0 else 18.0 + 15.0 * noise(j) }
        val c = only(detect(r))
        assertEquals(DeckRejection.STEEP, c.rejection, "$c")
        val m = assertNotNull(c.metrics)
        assertTrue(m.slopeOnCleanDeck, "the bridged estimator ran, and kept the tail raw")
        assertTrue(m.slopeP95 > 0.10, "P95 ${m.slopeP95}")
    }

    @Test
    fun `too few clean stations falls back to the raw profile`() {
        // Canopy: two 10-station smooth tall runs at 0 m merged across 50 rough stations under 5 m
        // MNH. Bridged, the span would be flat; it has too few clean stations to be read that way.
        // The merged span is judged whole here (splitSteepMerged off): DEFAULT splits it, below.
        val r = steepBridge(deckStations = 70) { j -> if (j < 10 || j >= 60) 0.0 else -7.5 + 4.0 * noise(j) }
        val whole = DeckDetectorOptions.DEFAULT.copy(splitSteepMerged = false)
        val c = only(detect(r, whole))
        val m = assertNotNull(c.metrics)
        assertTrue(m.cleanFraction < 0.25, "clean ${m.cleanFraction}")
        assertFalse(m.slopeOnCleanDeck)
        assertEquals(m.rawSlopeP95, m.slopeP95, 1e-12)
        assertEquals(DeckRejection.STEEP, c.rejection)
        val floorless = only(detect(r, whole.copy(minCleanFraction = 0.0)))
        // Without the floor the estimator runs, but the gap reaches the ground (MNH at or under
        // cleanGapGroundMnhM): it stays raw (the tall-gap rule), so the canopy is still steep.
        // Before that rule it passed, flat.
        val fm = assertNotNull(floorless.metrics)
        assertTrue(fm.slopeOnCleanDeck)
        assertEquals(fm.rawSlopeP95, fm.slopeP95, 1e-12)
        assertEquals(DeckRejection.STEEP, floorless.rejection)
        // DEFAULT judges the steep merged span as its two parts, the two smooth runs: no canyon of
        // their own (the ground under the canopy is as low as theirs), nothing accepted.
        val split = detect(r)
        assertTrue(split.accepted.isEmpty(), "${split.accepted}")
        assertEquals(listOf(DeckRejection.NO_CANYON, DeckRejection.NO_CANYON), split.rejected.map { it.rejection })

        // p08: a tall 8-station core and an 8-station abutment whose MNS cycles 10 ± 1.8 m (rough,
        // but flat under the median): 4 clean stations, judged on the raw profile, and it passes.
        val cycle = doubleArrayOf(1.8, 0.0, -1.8)
        val s =
            steepBridge(deckStations = 16, under = { j -> if (j < 8) -8.0 else 7.0 }, right = 10.0) { j ->
                if (j < 8) 10.0 else 10.0 + cycle[(j - 8) % 3]
            }
        val p = only(detect(s))
        val pm = assertNotNull(p.metrics)
        assertFalse(pm.slopeOnCleanDeck, "$p")
        assertNull(p.rejection, "$pm")
    }

    @Test
    fun `known limit - a smooth long sag stays in the slope`() {
        // Cheviré's plateau: a smooth 10 m sag over 200 m is no rough station, so nothing is bridged.
        val r = steepBridge { j -> if (j in 100 until 140) 30.0 - 5.0 * (1 - cos(PI * (j - 100) / 20.0)) else 30.0 }
        val m = assertNotNull(only(detect(r)).metrics)
        assertEquals(1.0, m.cleanFraction, 1e-12)
        assertEquals(m.rawSlopeP95, m.slopeP95, 1e-12)
    }

    /**
     * A wooded ravine road between two flat 8 m structures 275 m apart (within `mergeGapM`): the
     * canopy follows a 20 m MNT dip (grade up to ~23 %), the canyon test passes, and the bridged
     * line between the two flat roofs would be flat. A low-MNH clearing at each forest edge splits
     * the roofs and the canopy into three tall runs, the canopy one rough.
     */
    private fun woodedRavine(): Route {
        val r = Route(260, ground = 10.0)
        for (k in 60..89) r.deck(k..k, under = 10.0) { 18.0 }
        for (k in 145..174) r.deck(k..k, under = 10.0) { 18.0 }
        for (k in 90..144) {
            val t = 10.0 - 20.0 * sin(PI * (k - 90) / 55.0)
            r.mnt[k] = t
            r.mns[k] = t + 15.0 + 4.0 * noise(k)
        }
        for (k in (91..93) + (141..143)) r.mns[k] = r.mnt[k]
        return r
    }

    @Test
    fun `a canopy reaching the ground between two flat roofs is not bridged`() {
        val r = woodedRavine()

        fun span(d: DeckDetection) = (d.accepted + d.rejected).single { it.metrics != null }
        assertEquals(DeckRejection.STEEP, span(detect(r, DeckDetectorOptions.PYTHON)).rejection)
        // The merged span, judged whole (DEFAULT splits it, at the end).
        val whole = DeckDetectorOptions.DEFAULT.copy(splitSteepMerged = false)
        val c = span(detect(r, whole))
        assertEquals(60..174, c.first..c.last)
        val m = assertNotNull(c.metrics)
        assertTrue(m.slopeOnCleanDeck, "$m")
        assertTrue(m.cleanFraction >= 0.25, "clean ${m.cleanFraction}")
        assertEquals(DeckRejection.STEEP, c.rejection, "the gap holds the clearings: read raw, $m")
        assertTrue(m.slopeP95 > 0.10, "P95 ${m.slopeP95}")
        // The clearings reach the ground (MNH 0, at or under cleanGapGroundMnhM), so the rule
        // confines bridging to one continuous structure (or across no-data only): the two roofs at
        // the same height would otherwise bridge into a flat line.
        assertEquals(m.rawSlopeP95, m.slopeP95, 1e-12)
        // DEFAULT: the steep merged span is judged as its parts, the two roofs (the canopy run is
        // rough, no part): neither has a canyon of its own.
        val d = detect(r)
        assertTrue(d.accepted.isEmpty(), "${d.accepted}")
        assertEquals(listOf(60..90, 144..174), d.rejected.filter { it.metrics != null }.map { it.first..it.last })
        assertTrue(d.rejected.filter { it.metrics != null }.all { it.rejection == DeckRejection.NO_CANYON }, "${d.rejected}")
    }

    @Test
    fun `a gap is bridged unless it reaches the ground`() {
        // Normandie at pixel centres: one station of a pylon gap reads MNH 4.4 m (corrupt MNT under
        // the deck). Above cleanGapGroundMnhM (1 m) it no longer vetoes the bridging; at the ground
        // (MNH 0.5 m) it still does. E2 ter's threshold, tallMnhM (5 m), vetoed both.
        fun withGapStation(mnh: Double): Route {
            val r = steepBridge(deckStations = 300, under = { -60.0 }) { j -> 0.05 * j }
            for (k in 150..189) r.mns[k] += 10.0 + 35.0 * noise(k)
            r.mnt[170] = r.mns[170] - mnh
            return r
        }
        // Judged whole: DEFAULT would split the steep span at the low station (two tall runs).
        val whole = DeckDetectorOptions.DEFAULT.copy(splitSteepMerged = false)
        for ((mnh, bridged) in listOf(4.4 to true, 0.5 to false)) {
            val c = only(detect(withGapStation(mnh), whole))
            val m = assertNotNull(c.metrics)
            assertTrue(m.slopeOnCleanDeck, "$m")
            if (bridged) {
                assertNull(c.rejection, "MNH $mnh: $m")
                assertTrue(m.slopeP95 < 0.06, "MNH $mnh: P95 ${m.slopeP95}")
            } else {
                assertEquals(DeckRejection.STEEP, c.rejection, "MNH $mnh: $m")
            }
            val e2ter = only(detect(withGapStation(mnh), whole.copy(cleanGapGroundMnhM = 5.0)))
            assertEquals(DeckRejection.STEEP, e2ter.rejection, "MNH $mnh at E2 ter's 5 m")
        }
    }

    @Test
    fun `a steep merged span is judged as its parts`() {
        // Willy-Brandt at pixel centres: a 40-station flat deck over a 10 m drop, and 175 m before
        // it, across bare ground, a smooth object on flat ground (a building, 6 m then 12 m high)
        // whose MNS steps. Merged, the steps make the span steep; as parts, the deck passes and the
        // object has no canyon.
        val r = Route(260, ground = 10.0)
        for (k in 60..69) r.mns[k] = 16.0
        for (k in 70..79) r.mns[k] = 22.0
        r.deck(115..154, under = 0.0) { 11.0 }
        val python = detect(r, DeckDetectorOptions.PYTHON)
        assertEquals(listOf(60..154), python.rejected.map { it.first..it.last })
        assertEquals(DeckRejection.STEEP, python.rejected.single().rejection)
        val d = detect(r)
        assertEquals(listOf(115..154), d.accepted.map { it.first..it.last })
        assertEquals(listOf(60..79 to DeckRejection.NO_CANYON), d.rejected.map { (it.first..it.last) to it.rejection })
        assertEquals(listOf(115..154), d.accepted.single().deckRanges)
        // A merged span that passes whole is never split: the roof of the known limit stays in.
        val r2 = Route(700).deck(100..299, under = 2.0) { 12.0 }
        for (k in 340..351) r2.mns[k] = 16.0
        assertEquals(listOf(100..351), detect(r2).accepted.map { it.first..it.last })
    }

    @Test
    fun `a split part can stay steep, and the parts lie inside the merged span`() {
        // Pont-Saint-Esprit in the judge: the split detaches the neighbour, and the deck itself is
        // still too steep. The candidates are then exactly the parts, the deck rejected STEEP.
        val r = Route(260, ground = 10.0)
        for (k in 60..69) r.mns[k] = 16.0
        for (k in 70..79) r.mns[k] = 22.0
        r.deck(115..154, under = 0.0) { k -> 11.0 + 0.75 * (k - 115) } // 15 % grade
        val merged = detect(r, DeckDetectorOptions.PYTHON).rejected.single()
        assertEquals(60..154, merged.first..merged.last)
        assertEquals(DeckRejection.STEEP, merged.rejection)
        val d = detect(r)
        assertTrue(d.accepted.isEmpty(), "${d.accepted}")
        val parts = d.rejected.map { (it.first..it.last) to it.rejection }
        assertEquals(listOf(60..79 to DeckRejection.NO_CANYON, 115..154 to DeckRejection.STEEP), parts)
        // Every part is inside the span it was split from (extend gives up past maxAbutmentM by
        // returning its own start, never a station outside the walk).
        assertTrue(parts.all { (p, _) -> p.first >= merged.first && p.last <= merged.last }, "$parts")
    }

    @Test
    fun `a steep merged span whose part extensions overlap stays whole`() {
        // Two tall runs (100..139, 145..154) apart by five stations of MNH 3 m: merged as one span,
        // and the short run's own extension walks back over the gap into the long one (15 stations,
        // within maxAbutmentM), so the parts union into one: nothing to split, the span is judged
        // whole, as with splitSteepMerged off.
        val r = Route(300, ground = 10.0).deck(100..154, under = 0.0) { k -> 13.0 + 0.75 * (k - 100) }
        for (k in 140..144) r.mnt[k] = r.mns[k] - 3.0
        val whole = DeckDetectorOptions.DEFAULT.copy(splitSteepMerged = false)
        val c = only(detect(r, whole))
        assertEquals(100..154, c.first..c.last)
        assertEquals(DeckRejection.STEEP, c.rejection)
        val d = detect(r)
        assertEquals(listOf(100..154 to DeckRejection.STEEP), (d.accepted + d.rejected).map { (it.first..it.last) to it.rejection })
        // Contrast: the same gap at the ground stops both extensions, and the short run is a part
        // of its own (so it is kept, and the case above did hold two kept runs).
        for (k in 140..144) r.mnt[k] = r.mns[k]
        val apart = detect(r)
        assertEquals(listOf(100..139, 145..154), (apart.accepted + apart.rejected).map { it.first..it.last }.sortedBy { it.first })
    }

    @Test
    fun `PYTHON is DEFAULT without the E2 ter and E2 quinquies options, which are validated`() {
        assertEquals(
            DeckDetectorOptions.DEFAULT.copy(slopeOnCleanDeck = false, mntAboveMnsAsTall = false, splitSteepMerged = false),
            DeckDetectorOptions.PYTHON,
        )
        assertFailsWith<IllegalArgumentException> { DeckDetectorOptions.DEFAULT.copy(cleanGapGroundMnhM = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { DeckDetectorOptions.DEFAULT.copy(minCleanFraction = 1.5) }
        assertFailsWith<IllegalArgumentException> { DeckDetectorOptions.DEFAULT.copy(minCleanFraction = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { DeckDetectorOptions.DEFAULT.copy(minCleanStationsCount = 1) }
        assertFailsWith<IllegalArgumentException> { DeckDetectorOptions.DEFAULT.copy(mntAboveMnsToleranceM = Double.NaN) }
        // A negative tolerance would read every MNH under |tol| as an impossible MNT, i.e. tall.
        assertFailsWith<IllegalArgumentException> { DeckDetectorOptions.DEFAULT.copy(mntAboveMnsToleranceM = -5.0) }
    }

    @Test
    fun `the rolling max clamps and propagates NaN`() {
        val m = DeckDetector.rollingMax(doubleArrayOf(1.0, 3.0, 2.0, 0.0, 0.0, Double.NaN), 1)
        assertEquals(listOf(3.0, 3.0, 3.0, 2.0), m.take(4))
        assertTrue(m[4].isNaN())
        assertTrue(m[5].isNaN())
    }

    @Test
    fun `cleanDeckSlopes interpolates between clean stations and keeps raw ends`() {
        // Clean stations 2..5 and 12..17 on a 0.5 m/station line; everything else is garbage (100 m).
        val clean = BooleanArray(20) { it in 2..5 || it in 12..17 }
        val mns = DoubleArray(20) { if (clean[it]) 0.5 * it else 100.0 }
        val o = DeckDetectorOptions.DEFAULT
        val tall = DoubleArray(20) { 10.0 }
        val fraction = DeckDetector.innerCleanFraction(clean, 0, 19, o.slopeHalfStations)
        assertEquals(10.0 / 16.0, fraction, 1e-12)

        fun slopes(
            opts: DeckDetectorOptions = o,
            mnh: DoubleArray = tall,
        ) = DeckDetector.cleanDeckSlopes(mns, mnh, clean, fraction, 0, 19, opts)
        val s = assertNotNull(slopes())
        assertEquals(16, s.size) // inner stations 2..17
        // Station 8 reads mf over 4..12: all clean or bridged, so the line's 10 %.
        for (k in 6..13) assertEquals(0.1, s[k - 2], 1e-12, "station $k")
        // Station 2 reads mf[0], the median of the raw garbage at 0 and 1: the leading end stays raw.
        assertTrue(s[0] > 1.0, "raw leading end: ${s[0]}")
        assertTrue(s[15] > 1.0, "raw trailing end: ${s[15]}")
        // Too few clean stations, or too small a share of them: null, the caller reads raw.
        assertNull(slopes(o.copy(minCleanStationsCount = 11)))
        assertNull(slopes(o.copy(minCleanFraction = 0.7)))
        // One station of the gap at ground level (MNH 0): the gap is not tall, it stays raw.
        val grounded = assertNotNull(slopes(mnh = tall.copyOf().also { it[9] = 0.0 }))
        assertTrue(grounded[6 - 2] > 1.0, "raw gap: ${grounded[6 - 2]}")
        // A no-data MNH does not veto.
        val unknown = assertNotNull(slopes(mnh = tall.copyOf().also { it[9] = Double.NaN }))
        for (k in 6..13) assertEquals(0.1, unknown[k - 2], 1e-12, "station $k")
    }

    // ── 15–16. Windows, determinism ────────────────────────────────────────────────────────────

    private fun allProfiles(): List<DeckProfile> {
        val canopy = Route(220)
        for (k in (50..89) + (100..139)) canopy.mns[k] = canopy.mnt[k] + 15.0 + crown(k)
        return listOf(
            canyonBridge(),
            camberedDeck(0.059, piles = true),
            camberedDeck(0.12),
            canopy,
            Route(200, ground = 50.0).deck(50..89, under = 47.0) { 60.0 },
            twoSpans(59),
            twoSpans(60),
            shortDeck(0.1),
            shortDeck(0.45),
            Route(100).deck(0..29, under = 2.0) { 12.0 },
            Route(100).deck(70..99, under = 2.0) { 12.0 },
            deckWithBlip(100..107, 115..117, 125..132),
            deckWithBlip(100..102, 105..105, 108..110),
            deckWithBlip(100..102, 106..106, 110..112),
            Route(200).deck(55..124, under = 2.0) { k -> if (k % 3 == 0) 11.0 else 12.0 },
            canyonBridge().also { it.mns[62] = Double.NaN },
            canyonBridge().also { for (k in 56..58) it.mns[k] = Double.NaN },
            bridgeThenField(21),
        ).map { it.profile() }
    }

    @Test
    fun `surface windows hold everything detect reads`() {
        for ((i, p) in allProfiles().withIndex()) {
            val windows = DeckDetector.surfaceWindows(p.mnhM)
            val inside = BooleanArray(p.size)
            windows.forEach { w -> w.forEach { inside[it] = true } }
            val masked =
                DeckProfile(
                    p.mnhM,
                    DoubleArray(p.size) { if (inside[it]) p.mnsM[it] else Double.NaN },
                    DoubleArray(p.size) { if (inside[it]) p.mntM[it] else Double.NaN },
                )
            assertEquals(DeckDetector.detect(p).toString(), DeckDetector.detect(masked).toString(), "profile $i, windows $windows")
            assertTrue(inside.count { it } < p.size, "profile $i: the windows should not cover the whole route")
        }
    }

    @Test
    fun `detection is deterministic, and fromSurface is MNS minus MNT`() {
        for (p in allProfiles()) {
            assertEquals(DeckDetector.detect(p).toString(), DeckDetector.detect(p).toString())
            val explicit = DeckProfile(DoubleArray(p.size) { p.mnsM[it] - p.mntM[it] }, p.mnsM, p.mntM)
            assertEquals(DeckDetector.detect(p).toString(), DeckDetector.detect(explicit).toString())
        }
    }

    // ── 17. Helpers ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the rolling nan-median clamps, skips NaN and averages an even middle pair`() {
        val x = doubleArrayOf(1.0, Double.NaN, 3.0, 4.0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        val m = DeckDetector.rollingNanMedian(x, 2)
        // k = 0: window [1, 1, 1, NaN, 3] → 1; k = 2: [1, NaN, 3, 4, NaN] → 3; k = 3: [NaN, 3, 4, NaN, NaN] → 3.5.
        assertEquals(1.0, m[0], 1e-12)
        assertEquals(3.0, m[2], 1e-12)
        assertEquals(3.5, m[3], 1e-12)
        assertTrue(m[8].isNaN())
    }

    @Test
    fun `the percentile is numpy's linear one`() {
        assertEquals(3.85, DeckDetector.percentileLinear(doubleArrayOf(4.0, 1.0, 3.0, 2.0), 0.95), 1e-12)
        assertEquals(7.0, DeckDetector.percentileLinear(doubleArrayOf(7.0), 0.95), 1e-12)
    }

    @Test
    fun `the centred slope and mean clamp at the edges`() {
        val s = DeckDetector.centredSlope(doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0), 2, 5.0)
        assertEquals(2.0 / 20.0, s[0], 1e-12) // (x[2] - x[0]) / 20
        assertEquals(4.0 / 20.0, s[2], 1e-12)
        assertEquals(2.0 / 20.0, s[4], 1e-12)
        val mean = DeckDetector.rollingMean(doubleArrayOf(0.0, 3.0, 6.0), 1)
        assertEquals(1.0, mean[0], 1e-12) // [0, 0, 3]
        assertTrue(DeckDetector.rollingMean(doubleArrayOf(0.0, Double.NaN, 6.0), 1)[0].isNaN())
    }

    @Test
    fun `extend steps over short NaN islands and gives up past maxSteps`() {
        val nan = Double.NaN
        val mnh = doubleArrayOf(0.0, 2.0, nan, nan, 2.0, 9.0, 9.0, 2.0, nan, nan, nan, 2.0, 0.0)
        assertEquals(1, DeckDetector.extend(mnh, 5, -1, 1.0, 2, 10))
        assertEquals(7, DeckDetector.extend(mnh, 6, +1, 1.0, 2, 10))
        assertEquals(5, DeckDetector.extend(mnh, 5, -1, 1.0, 2, 3), "4 steps > 3: back to the core")
        assertEquals(1, DeckDetector.extend(mnh, 5, -1, 1.0, 2, 4))
    }

    @Test
    fun `closeHoles fills bounded holes only`() {
        val m = booleanArrayOf(false, true, false, true, false, false, false, true, false)
        DeckDetector.closeHoles(m, 0..8, 2) { true }
        assertEquals(listOf(false, true, true, true, false, false, false, true, false), m.toList())
        val n = booleanArrayOf(true, false, true)
        DeckDetector.closeHoles(n, 0..2, 2) { false }
        assertEquals(listOf(true, false, true), n.toList())
    }

    @Test
    fun `runs are inclusive and merge strictly below the gap`() {
        val r = DeckDetector.runs(booleanArrayOf(true, true, false, false, true, false, true))
        assertEquals(listOf(0..1, 4..4, 6..6), r)
        assertEquals(listOf(0..1, 4..6), DeckDetector.mergeRuns(r, 2)) // 2 missing: apart; 1 missing: merged
        assertEquals(listOf(0..6), DeckDetector.mergeRuns(r, 3))
    }
}
