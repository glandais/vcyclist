package io.github.glandais.engine.path

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Along-track LiDAR samples on the uniform grid `k × stepM` (`k = 0` at path distance 0), the input
 * of [DeckDetector]. `NaN` is no data (a raw IGN layer outside its coverage).
 *
 * There is no distance array: the caller resamples the path to [DeckDetectorOptions.stepM] before
 * sampling, and station `k` *is* distance `k × stepM`. Every window and span threshold is counted in
 * stations of that step, so a profile sampled at any other spacing is misread, silently.
 *
 * @property mnhM height above ground (MNH), metres
 * @property mnsM surface model (MNS): decks, treetops, roofs, metres
 * @property mntM terrain model (MNT): the ground, metres
 */
class DeckProfile(
    val mnhM: DoubleArray,
    val mnsM: DoubleArray,
    val mntM: DoubleArray,
) {
    init {
        require(mnhM.size == mnsM.size && mnsM.size == mntM.size) {
            "MNH, MNS and MNT must have one sample per station: ${mnhM.size}, ${mnsM.size}, ${mntM.size}"
        }
    }

    /** Number of stations. */
    val size: Int get() = mnhM.size

    companion object {
        /**
         * MNH derived as `MNS − MNT`, the reference analysis's definition (`common.py`:10), which the
         * thresholds of [DeckDetectorOptions] were measured on.
         */
        fun fromSurface(
            mnsM: DoubleArray,
            mntM: DoubleArray,
        ): DeckProfile {
            require(mnsM.size == mntM.size) { "MNS and MNT sizes differ: ${mnsM.size}, ${mntM.size}" }
            return DeckProfile(DoubleArray(mnsM.size) { mnsM[it] - mntM[it] }, mnsM, mntM)
        }
    }
}

/** Why a candidate is not a deck, in the order [DeckDetector] tests them. */
enum class DeckRejection {
    /** Tall runs whose MNS is rough: canopy or buildings. Never extended nor measured. */
    ROUGH,

    /** The MNT under the span does not drop more than `minCanyonDropM` below its ends: a roof. */
    NO_CANYON,

    /**
     * Long span whose deck slope P95 exceeds `maxSlopeP95` (or shorter than `minLengthM`); the slope
     * is read on the bridged profile when `slopeOnCleanDeck` holds and enough stations are clean.
     */
    STEEP,

    /** Short span whose median roughness is not below `maxShortRoughnessM`. */
    SHORT_ROUGH,

    /**
     * A value the decision reads is `NaN`: the MNT on or just outside the span, the MNH that stopped
     * its extension, a metric — or, unmeasured, tall runs that would be smooth enough if their
     * no-data stations were (MNS no-data under a valid MNH).
     */
    NO_DATA,
}

/**
 * What [DeckDetector] measured on a candidate span. Slopes are fractions, not percents.
 *
 * @property lengthM `(last − first + 1) × step`, Python's `L`
 * @property slopeP95 P95 of |slope| over the inner stations of the profile the verdict read: the MNS
 *   median with its rough stations bridged when [slopeOnCleanDeck], the raw MNS median otherwise;
 *   `NaN` on a short span (Python prints 99)
 * @property slopeMax max of the same, `NaN` on a short span
 * @property rawSlopeP95 P95 of |slope| of the raw MNS median (analyze.py's statistic), always
 *   computed on a long span, `NaN` on a short one
 * @property cleanFraction share of clean stations (window-max roughness below `maxRoughnessM`) among
 *   the inner stations of a long span; `NaN` on a short span
 * @property slopeOnCleanDeck whether [slopeP95] was read on the bridged profile: `false` when the
 *   option is off, on a short span, or on the fallback (too few clean stations)
 * @property roughnessM median roughness over the span's tall stations
 * @property smoothFraction the smallest smooth share among the tall runs that make the candidate
 * @property canyonM max of `median MNS − MNT` on the span, `NaN` samples skipped (diagnostic)
 * @property mntDropM `min(MNT just outside) − min(MNT on the span)`; the canyon test is `> minCanyonDropM`
 * @property deckTopM max of the MNS median on the span, `NaN` samples skipped (diagnostic)
 */
data class DeckMetrics(
    val lengthM: Double,
    val slopeP95: Double,
    val slopeMax: Double,
    val rawSlopeP95: Double,
    val cleanFraction: Double,
    val slopeOnCleanDeck: Boolean,
    val roughnessM: Double,
    val smoothFraction: Double,
    val canyonM: Double,
    val mntDropM: Double,
    val deckTopM: Double,
)

/**
 * One span [DeckDetector] considered.
 *
 * @property first first station, inclusive (Python `i0`)
 * @property last last station, inclusive (Python `i1`)
 * @property startM distance of the first station, `first × stepM`
 * @property endM distance of the last station, `last × stepM`. The span covers `[startM, endM]`, so
 *   `endM − startM = lengthM − stepM`: [DeckMetrics.lengthM] counts stations, not intervals
 * @property metrics `null` for a [DeckRejection.ROUGH] or unmeasured [DeckRejection.NO_DATA] group
 *   (Python never measures a rough group)
 * @property rejection `null` when accepted
 * @property deckRanges accepted only: the stations whose road is the deck (`analyze.py`:96-101),
 *   what E3 replaces with the MNS median; the rest of the span stays on the MNT. Empty otherwise.
 *   May include a second structure merged into the span within `mergeGapM` (a roof, a footbridge
 *   overhead) that borrows the bridge's canyon: see [DeckDetector], *known limits*.
 */
data class DeckCandidate(
    val first: Int,
    val last: Int,
    val startM: Double,
    val endM: Double,
    val metrics: DeckMetrics?,
    val rejection: DeckRejection?,
    val deckRanges: List<IntRange>,
) {
    val accepted: Boolean get() = rejection == null
}

/**
 * Result of [DeckDetector.detect], both lists sorted by [DeckCandidate.first].
 *
 * @property tallRunsTooShort tall runs shorter than `minTallRunM`, never candidates
 */
data class DeckDetection(
    val accepted: List<DeckCandidate>,
    val rejected: List<DeckCandidate>,
    val tallRunsTooShort: Int,
)

/**
 * Finds bridge decks along a route from LiDAR HD samples: a port of the reference detector
 * `docs/deck-strava-data/analysis/analyze.py` (bridges block) and its helpers in `common.py`.
 *
 * Pure and deterministic: flat arrays in, spans out, no `Path`, no provider, no I/O. The steps, on
 * the grid of [DeckDetectorOptions.stepM]:
 *
 * 1. `mf` = rolling **nan-median** of the MNS over `surfaceMedianWindowM` (indices clamped, `NaN`
 *    ignored): the deck profile.
 * 2. `rough` = RMS of `MNS − mf` over `roughnessWindowM` (a plain mean: one `NaN` makes it `NaN`).
 * 3. **Tall** stations, `MNH > tallMnhM`; runs of at least `minTallRunM` are candidates (*Input 4*:
 *    the MNS is the top of anything — a deck, but also a canopy).
 * 4. A run is kept when at least `minSmoothFraction` of its stations have `rough < maxRoughnessM`;
 *    the others are canopy or buildings ([DeckRejection.ROUGH], merged by the same gap rule), or
 *    [DeckRejection.NO_DATA] when their `NaN` roughness alone keeps them under the fraction.
 * 5. Kept runs merge across fewer than `mergeGapM` of missing stations (MNS holes, piers), *after*
 *    the smoothness filter, so a rough run never joins a smooth one.
 * 6. Each candidate extends along `MNH > deckMnhM`, down to the abutments. It steps over a `NaN`
 *    island of at most `maxDeckGapStations` when the station past it is above the threshold; a wider `NaN` stops it and rejects the span ([DeckRejection.NO_DATA]).
 *    An extension longer than `maxAbutmentM` on one side is no abutment: the span ends at its tall
 *    core on that side.
 * 7. The **canyon** test: the lowest MNT on the span lies more than `minCanyonDropM` below the lower
 *    MNT just outside it — clamped at the ends of the route, so at an end the span's own first or
 *    last MNT stands in for the outside value (the drop is then read against the other end only
 *    when that one is lower).
 * 8. A long span is a deck when the P95 of its |slope| (MNS median, `slopeHalfWindowM`) is at most
 *    `maxSlopeP95`; a short one (`shortSpanM`) when its median roughness is below
 *    `maxShortRoughnessM`. With `slopeOnCleanDeck` (E2 ter, [cleanDeckSlopes]) the MNS is first
 *    bridged across its rough stations — a station is *clean* when the max roughness over the
 *    `roughnessWindowM` window centred on it is below `maxRoughnessM` — linearly between the clean
 *    stations around each gap, leading and trailing rough stations left raw; with fewer than
 *    `minCleanStationsCount` clean stations, or a clean share under `minCleanFraction`, the raw
 *    profile is read. A gap is bridged only when every station in it is tall (`MNH > tallMnhM`):
 *    one that reaches the ground keeps its raw MNS.
 * 9. Deck mask of an accepted span: its `MNH > deckMnhM` runs of at least `deckMinRunM`, or all of
 *    them on a span of at most `smallSpanM` — after closing holes of at most `maxDeckGapStations`
 *    (`NaN` or low MNH) where `mf` is finite, so one no-data pixel does not drop the road into the
 *    river.
 *
 * **Divergences from Python**, none changing anything on Python's own samples (pinned by
 * `ElevationBenchProbeTest.deck_detector_reproduces_python_on_its_own_samples`):
 * - candidates whose extended spans overlap are **unioned** before classification (Python can
 *   emit the same span twice when a continuous `MNH > 1` stretch joins two candidates);
 * - a `NaN` the decision reads always rejects ([DeckRejection.NO_DATA]), rather than following
 *   numpy's `min` and Python's builtin `min` argument-order quirks; a short `NaN` island is stepped
 *   over instead (step 6, step 9), since `fromSurface` turns one no-data MNS pixel into a `NaN` MNH;
 * - the extension is bounded by `maxAbutmentM` (step 6): Python's unbounded `MNH > 1` walk lifts a
 *   raised field or a hedge row touching a deck into its span and its deck mask.
 *
 * **E2 ter divergences**, off in [DeckDetectorOptions.PYTHON]: the bridged slope (step 8), and
 * `mntAboveMnsAsTall` — an MNT above the MNS (impossible, IGN data under Pont de Normandie) reads as
 * a tall MNH for steps 3, 6 and 9, for the median roughness of step 8 (`roughnessM`, which reads the
 * tall stations) and for the tall-gap rule of the bridged slope — never for the canyon test (step 7)
 * nor the profile `mf`.
 *
 * **Known limits, kept from Python.** Kept runs merge across up to `mergeGapM` *before* the canyon
 * and slope tests, so a smooth structure with no canyon of its own (a roof, a canopy over the road)
 * within 300 m of a bridge joins its span and its deck mask, and two bridges that close come out as
 * one candidate. A per-run canyon test would cure it and break the two longest bridges: Saint-Nazaire
 * and Mauves each hold a 51-station `MNH ≤ 1` stretch (MNS holes over the water) that reads exactly
 * like road on the ground. Likewise trees touching a deck share its tall run and its deck mask while
 * the run stays smooth enough on the whole: cutting rough stations off a run's ends, or stopping the
 * extension at them, loses two of Python's bridges (`test2` 285..309 and 4858..4881), whose
 * abutment stations read 0.86 m and 1.07 m of roughness — a real abutment is as rough as a tree
 * line. E3 owns both cases.
 *
 * **Known limit of the bridged slope.** It masks only rough stations: a sag that is long *and
 * smooth* (Cheviré s 1095–1300, left by edge mixing in the z15 pixel) stays in the slope, and a
 * step hidden between two clean stations of a fully smooth roof is not seen either. A bridged gap
 * has no length limit (385 m on Cheviré, 410 m on Normandie, 495 m on Millau): only the tall-gap
 * rule ties it to a structure, so a canopy that stays tall from one smooth structure to the next
 * (no clearing, within `mergeGapM`) is still read along the line between their tops.
 */
object DeckDetector {
    /** Detect decks on [profile]. */
    fun detect(
        profile: DeckProfile,
        options: DeckDetectorOptions = DeckDetectorOptions.DEFAULT,
    ): DeckDetection {
        val n = profile.size
        val mns = profile.mnsM
        val mnt = profile.mntM
        val step = options.stepM
        if (n == 0) return DeckDetection(emptyList(), emptyList(), 0)
        val mnh =
            if (options.mntAboveMnsAsTall) {
                val tol = options.mntAboveMnsToleranceM
                DoubleArray(n) { if (mnt[it] > mns[it] + tol) Double.POSITIVE_INFINITY else profile.mnhM[it] }
            } else {
                profile.mnhM
            }

        val mf = rollingNanMedian(mns, options.surfaceMedianWindowStations / 2)
        val rough = roughness(mns, mf, options.roughnessWindowStations / 2)

        val tall = BooleanArray(n) { mnh[it] > options.tallMnhM }
        val tallRuns = runs(tall)
        val raw = tallRuns.filter { it.last - it.first + 1 >= options.minTallRunStations }
        val tooShort = tallRuns.size - raw.size

        val kept = ArrayList<Pair<IntRange, Double>>()
        val roughRuns = ArrayList<IntRange>()
        val noDataRuns = ArrayList<IntRange>()
        for (r in raw) {
            val len = (r.last - r.first + 1).toDouble()
            val smooth = r.count { rough[it] < options.maxRoughnessM }
            val unknown = r.count { rough[it].isNaN() }
            val fraction = smooth / len
            when {
                fraction >= options.minSmoothFraction -> kept += r to fraction
                (smooth + unknown) / len >= options.minSmoothFraction -> noDataRuns += r
                else -> roughRuns += r
            }
        }

        // Merge kept runs (step 5), carrying the smallest smooth fraction of each group.
        val gap = options.mergeGapStations
        val merged = ArrayList<Pair<IntRange, Double>>()
        for ((r, f) in kept) {
            val prev = merged.lastOrNull()
            if (prev != null && r.first - prev.first.last - 1 < gap) {
                merged[merged.size - 1] = (prev.first.first..r.last) to minOf(prev.second, f)
            } else {
                merged += r to f
            }
        }

        // Extend (step 6), then union overlapping spans (divergence 1).
        val island = options.maxDeckGapStations
        val spans = ArrayList<Pair<IntRange, Double>>()
        for ((r, f) in merged) {
            val a = extend(mnh, r.first, -1, options.deckMnhM, island, options.maxAbutmentStations)
            val b = extend(mnh, r.last, +1, options.deckMnhM, island, options.maxAbutmentStations)
            val prev = spans.lastOrNull()
            if (prev != null && a <= prev.first.last) {
                spans[spans.size - 1] = (prev.first.first..maxOf(prev.first.last, b)) to minOf(prev.second, f)
            } else {
                spans += (a..b) to f
            }
        }

        val slope = centredSlope(mf, options.slopeHalfStations, step)
        val clean =
            rollingMax(rough, options.roughnessWindowStations / 2).let { m ->
                BooleanArray(n) { m[it] < options.maxRoughnessM }
            }

        val classified = ArrayList<Triple<IntRange, DeckMetrics, DeckRejection?>>()
        for ((span, fraction) in spans) {
            val a = span.first
            val b = span.last
            val short = b - a <= options.shortSpanIntervals
            val h = options.slopeHalfStations
            val rawSlopes =
                if (short) {
                    DoubleArray(0)
                } else {
                    DoubleArray(maxOf(0, (b - h) - (a + h) + 1)) { abs(slope[a + h + it]) }
                }
            val cleanFraction = if (short) Double.NaN else innerCleanFraction(clean, a, b, h)
            val cleanSlopes =
                if (!short && options.slopeOnCleanDeck) cleanDeckSlopes(mns, mnh, clean, cleanFraction, a, b, options) else null
            val slopes = cleanSlopes ?: rawSlopes
            val slopeP95 = p95OrNaN(slopes)
            val slopeMax = if (slopes.isEmpty()) Double.NaN else maxOrNaN(slopes)
            val roughOnTall = span.filter { mnh[it] > options.tallMnhM }.map { rough[it] }
            val roughness = if (roughOnTall.isEmpty() || roughOnTall.any { it.isNaN() }) Double.NaN else median(roughOnTall)
            val outerMin = minOf(mnt[maxOf(a - 1, 0)], mnt[minOf(b + 1, n - 1)])
            val spanMnt = span.map { mnt[it] }
            val spanMin = if (spanMnt.any { it.isNaN() }) Double.NaN else spanMnt.min()
            val mntDrop = outerMin - spanMin
            // The extension stopped on a NaN MNH it could not step over: the bank is unknown.
            val stoppedOnNaN = (a > 0 && mnh[a - 1].isNaN()) || (b < n - 1 && mnh[b + 1].isNaN())
            val metrics =
                DeckMetrics(
                    lengthM = (b - a + 1) * step,
                    slopeP95 = slopeP95,
                    slopeMax = slopeMax,
                    rawSlopeP95 = p95OrNaN(rawSlopes),
                    cleanFraction = cleanFraction,
                    slopeOnCleanDeck = cleanSlopes != null,
                    roughnessM = roughness,
                    smoothFraction = fraction,
                    canyonM = span.map { mf[it] - mnt[it] }.filter { !it.isNaN() }.maxOrNull() ?: Double.NaN,
                    mntDropM = mntDrop,
                    deckTopM = span.map { mf[it] }.filter { !it.isNaN() }.maxOrNull() ?: Double.NaN,
                )
            val rejection =
                when {
                    stoppedOnNaN || mntDrop.isNaN() -> DeckRejection.NO_DATA
                    !(mntDrop > options.minCanyonDropM) -> DeckRejection.NO_CANYON
                    short && roughness.isNaN() -> DeckRejection.NO_DATA
                    short -> if (roughness < options.maxShortRoughnessM) null else DeckRejection.SHORT_ROUGH
                    slopeP95.isNaN() -> DeckRejection.NO_DATA
                    slopeP95 <= options.maxSlopeP95 && (b - a) * step >= options.minLengthM -> null
                    else -> DeckRejection.STEEP
                }
            classified += Triple(span, metrics, rejection)
        }

        // Deck mask (step 9): MNH > deckMnhM, with short holes closed inside accepted spans.
        val deckMask = BooleanArray(n) { mnh[it] > options.deckMnhM }
        for ((span, _, rejection) in classified) {
            if (rejection == null) closeHoles(deckMask, span, island) { !mf[it].isNaN() }
        }
        val deckRuns = runs(deckMask)

        val accepted = ArrayList<DeckCandidate>()
        val rejected = ArrayList<DeckCandidate>()
        for ((span, metrics, rejection) in classified) {
            val a = span.first
            val b = span.last
            val deck =
                if (rejection != null) {
                    emptyList()
                } else {
                    deckRuns
                        .filter { r ->
                            (r.last - r.first + 1 >= options.deckMinRunStations || b - a <= options.smallSpanIntervals) &&
                                r.first <= b &&
                                r.last >= a
                        }.map { r -> maxOf(r.first, a)..minOf(r.last, b) }
                }
            val candidate = DeckCandidate(a, b, a * step, b * step, metrics, rejection, deck)
            if (rejection == null) accepted += candidate else rejected += candidate
        }

        for (g in mergeRuns(roughRuns, gap)) {
            rejected += DeckCandidate(g.first, g.last, g.first * step, g.last * step, null, DeckRejection.ROUGH, emptyList())
        }
        for (g in mergeRuns(noDataRuns, gap)) {
            rejected += DeckCandidate(g.first, g.last, g.first * step, g.last * step, null, DeckRejection.NO_DATA, emptyList())
        }
        rejected.sortWith(compareBy<DeckCandidate> { it.first }.thenBy { it.last })
        return DeckDetection(accepted, rejected, tooShort)
    }

    /**
     * **Diagnostic, not for E3.** The stations whose MNS and MNT [detect] can read, given only the
     * MNH: tall runs of at least `minTallRunM`, merged by the gap rule, extended along
     * `MNH > deckMnhM` (unbounded by `maxAbutmentM`: a superset of [detect]'s extension),
     * then padded by the median and roughness half-windows. MNS and MNT set to `NaN` outside these
     * ranges leave [detect]'s result unchanged.
     *
     * It exists for the bench's two-phase variants (MNH at z13/z14, then MNS only here), which the E2
     * measurements reject: the windows cover 43–70 % of the route and the scheme costs more tiles
     * than the MNS alone (`docs/tasks/elevation-bridge-decks.md`, *Mesures (E2)*).
     */
    fun surfaceWindows(
        mnhM: DoubleArray,
        options: DeckDetectorOptions = DeckDetectorOptions.DEFAULT,
    ): List<IntRange> {
        val n = mnhM.size
        val raw =
            runs(BooleanArray(n) { mnhM[it] > options.tallMnhM })
                .filter { it.last - it.first + 1 >= options.minTallRunStations }
        val pad = options.surfaceMedianWindowStations / 2 + options.roughnessWindowStations / 2
        val island = options.maxDeckGapStations
        val padded =
            mergeRuns(raw, options.mergeGapStations).map { r ->
                val a = extend(mnhM, r.first, -1, options.deckMnhM, island, Int.MAX_VALUE)
                val b = extend(mnhM, r.last, +1, options.deckMnhM, island, Int.MAX_VALUE)
                maxOf(0, a - pad)..minOf(n - 1, b + pad)
            }
        // Union overlapping or touching windows.
        return mergeRuns(padded, 1)
    }

    // ── Helpers, each pinned by DeckDetectorTest ────────────────────────────────────────────────

    /**
     * Share of [clean] stations among the inner stations `a + h .. b − h` of the span `[a, b]`, `NaN`
     * when there is none. The single site of [DeckMetrics.cleanFraction] and of the
     * `minCleanFraction` gate of [cleanDeckSlopes].
     */
    internal fun innerCleanFraction(
        clean: BooleanArray,
        a: Int,
        b: Int,
        h: Int,
    ): Double = if (b - h < a + h) Double.NaN else (a + h..b - h).count { clean[it] }.toDouble() / ((b - h) - (a + h) + 1)

    /**
     * |slope| over the inner stations `a + h .. b − h` of the span `[a, b]`, read on the MNS with its
     * non-[clean] stations bridged (step 8, E2 ter), or `null` when the span has fewer than
     * `minCleanStationsCount` clean stations or a [cleanFraction] (from [innerCleanFraction]) below
     * `minCleanFraction` — the caller then reads the raw profile.
     *
     * A gap — the non-clean stations strictly between two consecutive clean stations of the span — is
     * replaced by the linear interpolation between those two clean stations **only when it is tall
     * throughout**: no station of the gap has an [mnh] (the effective one, `mntAboveMnsAsTall`
     * applied) at or below `tallMnhM`; a no-data MNH does not veto, its MNS is `NaN` and the
     * nan-median skips it anyway. A gap that touches the ground — a clearing at a forest edge, a
     * road between two structures — keeps its raw MNS, so the slope test still sees a canopy that
     * follows a ravine between two flat roofs. Leading and trailing non-clean stations keep their
     * raw MNS, so a rough tail (a tree line past an abutment) is still measured. The bridged MNS then
     * goes through the same nan-median and centred slope as the raw one. A clean station has a
     * finite MNS: its roughness, which reads it, is finite.
     */
    internal fun cleanDeckSlopes(
        mns: DoubleArray,
        mnh: DoubleArray,
        clean: BooleanArray,
        cleanFraction: Double,
        a: Int,
        b: Int,
        options: DeckDetectorOptions,
    ): DoubleArray? {
        val n = mns.size
        val h = options.slopeHalfStations
        if (b - h < a + h) return null
        val inSpan = (a..b).filter { clean[it] }
        if (inSpan.size < options.minCleanStationsCount) return null
        if (!(cleanFraction >= options.minCleanFraction)) return null
        val medianHalf = options.surfaceMedianWindowStations / 2
        // mf on [a, b] reads x on [a − medianHalf, b + medianHalf] (clamped like the full array).
        val lo = maxOf(0, a - medianHalf)
        val hi = minOf(n - 1, b + medianHalf)
        val x = mns.copyOfRange(lo, hi + 1)
        for (j in 0 until inSpan.size - 1) {
            val c0 = inSpan[j]
            val c1 = inSpan[j + 1]
            if ((c0 + 1 until c1).any { mnh[it] <= options.tallMnhM }) continue
            for (k in c0 + 1 until c1) {
                x[k - lo] = mns[c0] + (mns[c1] - mns[c0]) * (k - c0) / (c1 - c0)
            }
        }
        // Clamping at lo / hi only differs from the full array's where the window leaves [lo, hi],
        // which no station of [a, b] does unless lo = 0 or hi = n − 1, the array's own clamp.
        val mfClean = rollingNanMedian(x, medianHalf)
        return DoubleArray((b - h) - (a + h) + 1) {
            val k = a + h + it
            abs((mfClean[k + h - lo] - mfClean[k - h - lo]) / (2 * h * options.stepM))
        }
    }

    /**
     * Walk from [from] in direction [dir] (±1) while the next station's MNH is above [threshold]
     * (analyze.py:59-65). A `NaN` island of at most [maxIsland]
     * stations is stepped over when the station past it qualifies; a wider one stops the walk. A
     * walk longer than [maxSteps] is no abutment: the result is then [from] itself.
     */
    internal fun extend(
        mnh: DoubleArray,
        from: Int,
        dir: Int,
        threshold: Double,
        maxIsland: Int,
        maxSteps: Int,
    ): Int {
        val n = mnh.size
        val ok = { k: Int -> k in 0 until n && mnh[k] > threshold }
        var k = from
        while (true) {
            val next = k + dir
            if (ok(next)) {
                k = next
                continue
            }
            if (next !in 0 until n || !mnh[next].isNaN()) break
            var end = next
            while (end + dir in 0 until n && mnh[end + dir].isNaN()) end += dir
            val width = abs(end - next) + 1
            if (width <= maxIsland && ok(end + dir)) k = end + dir else break
        }
        return if (abs(k - from) > maxSteps) from else k
    }

    /**
     * Set [mask] on every hole of at most [maxHole] stations inside [span] that lies between two set
     * stations of the span and where [fill] holds on every station of the hole.
     */
    internal fun closeHoles(
        mask: BooleanArray,
        span: IntRange,
        maxHole: Int,
        fill: (Int) -> Boolean,
    ) {
        var k = span.first
        while (k <= span.last) {
            if (mask[k]) {
                k++
                continue
            }
            var end = k
            while (end + 1 <= span.last && !mask[end + 1]) end++
            val bounded = k > span.first && end < span.last
            if (bounded && end - k + 1 <= maxHole && (k..end).all(fill)) for (j in k..end) mask[j] = true
            k = end + 1
        }
    }

    private fun roughness(
        mns: DoubleArray,
        mf: DoubleArray,
        half: Int,
    ): DoubleArray {
        val sq = DoubleArray(mns.size) { (mns[it] - mf[it]).let { d -> d * d } }
        val mean = rollingMean(sq, half)
        return DoubleArray(mns.size) { sqrt(mean[it]) }
    }

    /**
     * Centred rolling median over `2·half + 1` stations, indices clamped (numpy's edge padding),
     * `NaN` ignored; all-`NaN` gives `NaN`, an even count of finite values the mean of the middle
     * pair (`np.nanmedian`, `common.py` `rmed`).
     */
    internal fun rollingNanMedian(
        x: DoubleArray,
        half: Int,
    ): DoubleArray {
        val n = x.size
        val window = DoubleArray(2 * half + 1)
        return DoubleArray(n) { k ->
            var m = 0
            for (j in k - half..k + half) {
                val v = x[j.coerceIn(0, n - 1)]
                if (!v.isNaN()) window[m++] = v
            }
            if (m == 0) {
                Double.NaN
            } else {
                window.sort(0, m)
                if (m % 2 == 1) window[m / 2] else (window[m / 2 - 1] + window[m / 2]) / 2
            }
        }
    }

    /** Centred rolling mean over `2·half + 1` stations, indices clamped, `NaN` propagates (`rmean`). */
    internal fun rollingMean(
        x: DoubleArray,
        half: Int,
    ): DoubleArray {
        val n = x.size
        return DoubleArray(n) { k ->
            var s = 0.0
            for (j in k - half..k + half) s += x[j.coerceIn(0, n - 1)]
            s / (2 * half + 1)
        }
    }

    /** Centred rolling max over `2·half + 1` stations, indices clamped, `NaN` propagates. */
    internal fun rollingMax(
        x: DoubleArray,
        half: Int,
    ): DoubleArray {
        val n = x.size
        return DoubleArray(n) { k ->
            var m = Double.NEGATIVE_INFINITY
            for (j in k - half..k + half) {
                val v = x[j.coerceIn(0, n - 1)]
                if (v.isNaN()) return@DoubleArray Double.NaN
                if (v > m) m = v
            }
            m
        }
    }

    /** `(x[k+h] − x[k−h]) / (2h·step)`, indices clamped, as a fraction (`common.py` `slope`). */
    internal fun centredSlope(
        x: DoubleArray,
        h: Int,
        stepM: Double,
    ): DoubleArray {
        val n = x.size
        return DoubleArray(n) { k -> (x[minOf(n - 1, k + h)] - x[maxOf(0, k - h)]) / (2 * h * stepM) }
    }

    /** Inclusive runs of `true` (`common.py` `runs`). */
    internal fun runs(mask: BooleanArray): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = -1
        for (i in mask.indices) {
            if (mask[i] && start < 0) start = i
            if (!mask[i] && start >= 0) {
                out += start until i
                start = -1
            }
        }
        if (start >= 0) out += start until mask.size
        return out
    }

    /**
     * Join each run to the previous one when fewer than [gapStations] stations lie between them;
     * the result spans the gap (`common.py` `merge`). Runs must arrive in order.
     */
    internal fun mergeRuns(
        rs: List<IntRange>,
        gapStations: Int,
    ): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (r in rs) {
            val prev = out.lastOrNull()
            if (prev != null && r.first - prev.last - 1 < gapStations) {
                out[out.size - 1] = prev.first..maxOf(prev.last, r.last)
            } else {
                out += r
            }
        }
        return out
    }

    /** numpy's default (linear) percentile, [q] in 0..1. [values] need not be sorted. */
    internal fun percentileLinear(
        values: DoubleArray,
        q: Double,
    ): Double {
        val s = values.sortedArray()
        val rank = (s.size - 1) * q
        val lo = floor(rank).toInt()
        val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (rank - lo)
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val m = s.size
        return if (m % 2 == 1) s[m / 2] else (s[m / 2 - 1] + s[m / 2]) / 2
    }

    private fun p95OrNaN(values: DoubleArray): Double =
        if (values.isEmpty() || values.any { it.isNaN() }) Double.NaN else percentileLinear(values, 0.95)

    private fun maxOrNaN(values: DoubleArray): Double = if (values.any { it.isNaN() }) Double.NaN else values.max()
}
