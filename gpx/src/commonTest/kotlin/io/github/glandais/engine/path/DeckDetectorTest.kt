package io.github.glandais.engine.path

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
