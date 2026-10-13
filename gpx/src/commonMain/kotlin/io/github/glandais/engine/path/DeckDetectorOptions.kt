package io.github.glandais.engine.path

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Thresholds of [DeckDetector], in metres on the along-track grid.
 *
 * Every default but [maxAbutmentM], the E2 ter options ([slopeOnCleanDeck], [minCleanFraction],
 * [minCleanStationsCount], [mntAboveMnsAsTall], [mntAboveMnsToleranceM]) and the E2 quinquies ones
 * ([cleanGapGroundMnhM], [splitSteepMerged]) — [PYTHON] turns them off — is
 * the value of the reference detector,
 * `docs/deck-strava-data/analysis/analyze.py` (bridges block) and `common.py`, measured on the four
 * Strava routes of
 * `docs/archive/tasks/elevation-bridge-decks.md` (*Input 5*): 21 bridges found, 14 km of canopy and
 * buildings rejected on `test4`, 2.2 km on `test2`. They were tuned on **MNS − MNT** sampled at
 * ~3 m, not on IGN's own MNH layer.
 *
 * Two kinds of window, as in Python, and the difference is where off-by-ones hide:
 * - a **run** or **window** counts *stations*: `minTallRunM = 15` is 3 stations, a 25 m median is
 *   5 stations centred on the one it answers for;
 * - a **span** threshold counts *intervals* between the first and the last station: `shortSpanM =
 *   25` means `last − first ≤ 5`, i.e. six stations.
 *
 * Every window must be an integral number of stations at [stepM], and the two rolling windows an
 * odd number: a 4 m grid against [DEFAULT] fails here rather than silently re-rounding a threshold.
 */
data class DeckDetectorOptions(
    /** Grid spacing the profile is sampled at, in metres (Python's `STEP`). */
    val stepM: Double = 5.0,
    /** A station is *tall* when its MNH is above this (analyze.py:54, the study's 5 m). */
    val tallMnhM: Double = 5.0,
    /** A tall run shorter than this (station count × step) is no candidate: 3 stations (:55). */
    val minTallRunM: Double = 15.0,
    /**
     * Rolling nan-median of the MNS, the deck profile every metric reads (:47, `rmed(mns, 5)`).
     * Station count × step, odd: 5 stations.
     */
    val surfaceMedianWindowM: Double = 25.0,
    /**
     * Roughness: RMS of `MNS − median` over this many stations × step, odd (:48-49, 7 stations).
     * A deck is a continuous surface; treetops and roofs are not.
     */
    val roughnessWindowM: Double = 35.0,
    /**
     * A tall station is *smooth* when its roughness is below this (`SMOOTH_TH`, :13/:56). Raised
     * from 0.35 to 0.8 for Pont de Bellevue, whose cycle lane runs along the deck edge so the MNS
     * alternates deck and void (roughness 0.71 there).
     */
    val maxRoughnessM: Double = 0.8,
    /** A tall run is kept when at least this share of its stations is smooth (:58). */
    val minSmoothFraction: Double = 0.6,
    /**
     * Kept runs merge when fewer than this many metres of stations are missing between them —
     * MNS holes and piers (:58, `merge(rs, 60)`, strict `<`).
     */
    val mergeGapM: Double = 300.0,
    /** Extension of a candidate along `MNH > this`, and the deck mask's threshold (:61-63, :98). */
    val deckMnhM: Double = 1.0,
    /**
     * Half-window of the deck slope, `(mf[k+h] − mf[k−h]) / (2h·step)`: h = 2 stations, a 20 m
     * window. analyze.py writes `slope(mf, 25)`, but Python rounds 2.5 half to even, so its window
     * is 20 m (:67); a `roundToInt` of 25 m would give 30 m and change the P95.
     */
    val slopeHalfWindowM: Double = 10.0,
    /**
     * A long span is a deck when the P95 of its |slope| is at most this, as a fraction (:74, 10 %).
     * Measured decks: Saint-Nazaire 5.9 %, Nantes 1.0–8.6 %; canopy is anywhere, often above 15 %.
     */
    val maxSlopeP95: Double = 0.10,
    /** A long span must also measure at least this, `(last − first) × step` (:74). */
    val minLengthM: Double = 30.0,
    /**
     * A span with `(last − first) × step` at most this is *short*: too few stations for a slope
     * percentile, judged on its roughness instead (:73). Échevis (25 m) is one.
     */
    val shortSpanM: Double = 25.0,
    /** A short span is a deck when its median roughness is below this (:74). Échevis: 0.15. */
    val maxShortRoughnessM: Double = 0.4,
    /**
     * The canyon test: the lowest MNT on the span must lie more than this below the lower of the
     * two MNT values just outside it (:71, `under_low`, strict). A roof over a road has no canyon.
     */
    val minCanyonDropM: Double = 3.0,
    /** Deck mask: an `MNH > deckMnhM` run of at least this (station count × step) is deck (:99, 8). */
    val deckMinRunM: Double = 40.0,
    /** Deck mask: on a span with `(last − first) × step` at most this, every MNH run is deck (:99, 12). */
    val smallSpanM: Double = 60.0,
    /**
     * Longest abutment: the extension along `MNH > deckMnhM` past the tall core, per side, in metres.
     * A longer stretch is no abutment but a raised field, a hedge or an embankment, and the span then
     * ends at its tall core on that side. Not in analyze.py, whose extension is unbounded. Measured on
     * Python's 21 bridges: 7 stations (35 m) at most, Saint-Nazaire's south end; 100 m leaves room.
     */
    val maxAbutmentM: Double = 100.0,
    /**
     * E2 ter: a long span's slope test reads the deck profile with its *rough* stations bridged —
     * linear across every non-clean gap between the first and the last clean station of the span
     * whose stations are all tall (`MNH > tallMnhM`), leading and trailing ones left raw. A station
     * is clean when the max roughness over the [roughnessWindowM] window centred on it is below
     * [maxRoughnessM]. Edge mixing (Cheviré), a hole band (Millau) and pylons (Normandie) put their
     * error on rough stations; the step across a bridged gap still carries the real grade. `false`: the raw MNS median, as analyze.py.
     */
    val slopeOnCleanDeck: Boolean = true,
    /**
     * Below this share of clean stations among a long span's inner ones (`first + h .. last − h`),
     * the span is judged on its raw profile, as before E2 ter: a canopy whose few clean stations
     * would bridge into a flat line (n05, clean share 0.13) is not read as a deck.
     */
    val minCleanFraction: Double = 0.25,
    /**
     * Below this many clean stations in the span, same fallback to the raw profile (p08 has 2).
     * At least 2: interpolation needs two clean stations.
     */
    val minCleanStationsCount: Int = 5,
    /**
     * E2 ter: `MNT > MNS + mntAboveMnsToleranceM` is physically impossible (the surface model is
     * the top of everything); IGN's data does it under Pont de Normandie (164 stations), where it
     * reads as a negative MNH and splits the deck. Such a station's MNH is taken as tall (`+∞`) for
     * the tall runs, the extension, the deck mask, the span's median roughness (`roughnessM`, the
     * short-span verdict) and the tall-gap rule of [slopeOnCleanDeck] — never for the canyon test,
     * which reads the raw MNT. `false`: the MNH as given.
     */
    val mntAboveMnsAsTall: Boolean = true,
    /** See [mntAboveMnsAsTall]. */
    val mntAboveMnsToleranceM: Double = 0.5,
    /**
     * E2 quinquies: the tall-gap rule of [slopeOnCleanDeck] — a gap is bridged only when no station
     * of it has an MNH at or below this, i.e. when it does not reach the **ground**. 1 m is
     * [deckMnhM]'s value, the ground as the extension and the deck mask read it: bridging stays
     * inside one continuous `MNH > 1 m` structure, never across a clearing or a road between two
     * structures. E2 ter read `tallMnhM` (5 m) here, and lost Pont de Normandie at pixel centres to
     * a single station of the corrupt-MNT zone under its deck (MNH 4.38 m, every other station of
     * the 82-station gap tall or `MNT > MNS`). Only read when [slopeOnCleanDeck] holds.
     */
    val cleanGapGroundMnhM: Double = 1.0,
    /**
     * E2 quinquies: a span made of several kept tall runs (merged across fewer than [mergeGapM]
     * stations, or unioned by their extensions) that is [DeckRejection.STEEP] is judged again as its
     * **parts** — each kept run extended on its own, overlapping parts unioned, i.e. the spans a zero
     * merge gap gives — and the parts replace it, each with its own canyon, slope and verdict. The
     * merge exists to carry a deck across its own MNS holes (Saint-Nazaire, Mauves: those spans pass
     * whole and are never split); a smooth neighbour off the bridge (a roof, a hedge, a second
     * structure) merged into it puts its own MNS steps into the slope and made the whole span
     * steep: Willy-Brandt, way 362043805, Bellevue and Haudaudine at pixel centres. `false`: the
     * merged span's verdict stands, as analyze.py.
     */
    val splitSteepMerged: Boolean = true,
) {
    init {
        require(stepM > 0.0 && stepM.isFinite()) { "stepM must be positive, got $stepM" }
        val thresholds =
            mapOf(
                "tallMnhM" to tallMnhM,
                "minTallRunM" to minTallRunM,
                "surfaceMedianWindowM" to surfaceMedianWindowM,
                "roughnessWindowM" to roughnessWindowM,
                "maxRoughnessM" to maxRoughnessM,
                "minSmoothFraction" to minSmoothFraction,
                "mergeGapM" to mergeGapM,
                "deckMnhM" to deckMnhM,
                "slopeHalfWindowM" to slopeHalfWindowM,
                "maxSlopeP95" to maxSlopeP95,
                "minLengthM" to minLengthM,
                "shortSpanM" to shortSpanM,
                "maxShortRoughnessM" to maxShortRoughnessM,
                "minCanyonDropM" to minCanyonDropM,
                "deckMinRunM" to deckMinRunM,
                "smallSpanM" to smallSpanM,
                "maxAbutmentM" to maxAbutmentM,
                "minCleanFraction" to minCleanFraction,
                "mntAboveMnsToleranceM" to mntAboveMnsToleranceM,
                "cleanGapGroundMnhM" to cleanGapGroundMnhM,
            )
        // A NaN threshold fails every comparison silently: maxSlopeP95 = NaN makes every span STEEP.
        thresholds.forEach { (name, v) -> require(v.isFinite()) { "$name must be finite, got $v" } }
        require(surfaceMedianWindowStations % 2 == 1) { "surfaceMedianWindowM must be an odd number of stations" }
        require(roughnessWindowStations % 2 == 1) { "roughnessWindowM must be an odd number of stations" }
        require(minTallRunStations >= 1) { "minTallRunM must cover at least one station" }
        require(slopeHalfStations >= 1) { "slopeHalfWindowM must cover at least one station" }
        require(mergeGapStations >= 0 && deckMinRunStations >= 1) { "negative window" }
        // The span thresholds are read in intervals; they must be integral too.
        require(shortSpanIntervals >= 0 && smallSpanIntervals >= 0) { "negative span threshold" }
        require(maxAbutmentStations >= 0) { "negative maxAbutmentM" }
        require(minCleanFraction in 0.0..1.0) { "minCleanFraction must be in 0..1, got $minCleanFraction" }
        require(mntAboveMnsToleranceM >= 0.0) { "mntAboveMnsToleranceM must be non-negative, got $mntAboveMnsToleranceM" }
        require(minCleanStationsCount >= 2) { "minCleanStationsCount must be at least 2, got $minCleanStationsCount" }
        // A long span (last − first > shortSpanIntervals) needs at least one inner slope sample,
        // last − first ≥ 2h; below this a valid long span would read NO_DATA.
        require(shortSpanIntervals >= 2 * slopeHalfStations - 1) {
            "shortSpanM must be at least 2·slopeHalfWindowM − stepM, or a long span has no slope sample"
        }
    }

    private fun stations(
        metres: Double,
        name: String,
    ): Int {
        val r = metres / stepM
        val k = r.roundToInt()
        require(abs(r - k) < 1e-9) { "$name = $metres m is not a whole number of $stepM m stations" }
        return k
    }

    /** [minTallRunM] in stations (3). */
    internal val minTallRunStations: Int get() = stations(minTallRunM, "minTallRunM")

    /** [surfaceMedianWindowM] in stations (5). */
    internal val surfaceMedianWindowStations: Int get() = stations(surfaceMedianWindowM, "surfaceMedianWindowM")

    /** [roughnessWindowM] in stations (7). */
    internal val roughnessWindowStations: Int get() = stations(roughnessWindowM, "roughnessWindowM")

    /** [mergeGapM] in stations (60): merge when fewer stations than this are missing. */
    internal val mergeGapStations: Int get() = stations(mergeGapM, "mergeGapM")

    /** [slopeHalfWindowM] in stations (2). */
    internal val slopeHalfStations: Int get() = stations(slopeHalfWindowM, "slopeHalfWindowM")

    /** [shortSpanM] in intervals (5): short iff `last − first ≤` this. */
    internal val shortSpanIntervals: Int get() = stations(shortSpanM, "shortSpanM")

    /** [deckMinRunM] in stations (8). */
    internal val deckMinRunStations: Int get() = stations(deckMinRunM, "deckMinRunM")

    /** [maxAbutmentM] in stations (20). */
    internal val maxAbutmentStations: Int get() = stations(maxAbutmentM, "maxAbutmentM")

    /**
     * Widest gap, in stations, that a deck spans without breaking: a `NaN` island in the extension,
     * a `NaN` or low MNH hole in the deck mask (2). Half the surface median: the median over the
     * gap still holds a majority of deck samples, so `mf` there is the deck.
     */
    internal val maxDeckGapStations: Int get() = surfaceMedianWindowStations / 2

    /** [smallSpanM] in intervals (12). */
    internal val smallSpanIntervals: Int get() = stations(smallSpanM, "smallSpanM")

    companion object {
        /**
         * The reference detector's thresholds on a 5 m grid, with the E2 ter slope estimator
         * ([slopeOnCleanDeck]) and [mntAboveMnsAsTall], recalibrated at pixel centres in E2
         * quinquies ([cleanGapGroundMnhM], [splitSteepMerged]; no threshold of Python moved).
         */
        val DEFAULT: DeckDetectorOptions = DeckDetectorOptions()

        /**
         * `analyze.py` plus the bounded abutment and the NaN-island bridging: the E2 bis DEFAULT,
         * without [slopeOnCleanDeck], [mntAboveMnsAsTall] nor [splitSteepMerged] ([cleanGapGroundMnhM]
         * is only read with [slopeOnCleanDeck]). The exact parity with Python's own
         * samples is pinned on this set — E2 ter changes one of their P95s (`test2` 125..202), no
         * verdict.
         */
        val PYTHON: DeckDetectorOptions =
            DeckDetectorOptions(slopeOnCleanDeck = false, mntAboveMnsAsTall = false, splitSteepMerged = false)
    }
}
