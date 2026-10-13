package io.github.glandais.engine.path

/**
 * One stretch of road that [DeckProfileBuilder] replaces, on the station grid of the [DeckProfile]
 * it was built from (station `k` is distance `k × stepM`).
 *
 * The stretch covers `first..last`: the region's spans `spanFirst..spanLast`, plus one station on
 * each side where the route goes on (`first = spanFirst − 1` unless the region starts the route,
 * likewise at the end). Those outer stations are the **pins**: the road there keeps whatever
 * elevation the caller already has, and a linear ramp joins it to the deck. The deck itself is
 * [elevationM] on `anchorFirst..anchorLast`.
 *
 * @property first first station of the stretch, inclusive: the start pin, or `0`
 * @property last last station, inclusive: the end pin, or the route's last station
 * @property spanFirst first station of the region's first span
 * @property spanLast last station of the region's last span
 * @property anchorFirst first anchor: the deck profile starts here
 * @property anchorLast last anchor: the deck profile ends here
 * @property anchors the stations whose MNS the profile was read from, sorted
 * @property elevationM one value per station of `first..last` (`elevationM[k − first]`): the deck
 *   profile on `anchorFirst..anchorLast`; that profile's end value held flat to a route end the
 *   region touches; `NaN` on the pins and the ramp stations between a pin and an anchor, which
 *   [withEnds] fills
 * @property spans the accepted candidates the region is made of, one, or several joined
 */
class DeckReplacement(
    val first: Int,
    val last: Int,
    val spanFirst: Int,
    val spanLast: Int,
    val anchorFirst: Int,
    val anchorLast: Int,
    val anchors: IntArray,
    val elevationM: DoubleArray,
    val spans: List<DeckCandidate>,
) {
    /** Whether `first` is a pin: the region does not start the route. */
    val pinnedStart: Boolean get() = first < spanFirst

    /** Whether `last` is a pin: the region does not end the route. */
    val pinnedEnd: Boolean get() = last > spanLast

    /**
     * [elevationM] with the pins set to [startM] and [endM] (each ignored when its side is not
     * pinned) and every ramp station filled linearly between the pin and the nearest anchor: the
     * full station function of the stretch, finite everywhere.
     */
    fun withEnds(
        startM: Double,
        endM: Double,
    ): DoubleArray {
        val z = elevationM.copyOf()
        if (pinnedStart) z[0] = startM
        if (pinnedEnd) z[z.size - 1] = endM
        fillLinear(z)
        return z
    }

    override fun toString(): String =
        "DeckReplacement(first=$first, last=$last, span=$spanFirst..$spanLast, anchors=$anchorFirst..$anchorLast " +
            "(${anchors.size}), spans=${spans.map { it.first..it.last }})"
}

/**
 * What [DeckProfileBuilder.build] did with the accepted spans of a [DeckDetection].
 *
 * @property replacements the stretches to replace, sorted by [DeckReplacement.first]
 * @property dropped regions (one or several joined spans) with fewer than two anchors: left on the
 *   terrain model, since one MNS sample is no profile
 */
class DeckProfiles(
    val replacements: List<DeckReplacement>,
    val dropped: List<List<DeckCandidate>>,
)

/**
 * The road profile of each accepted deck (E3 of `docs/archive/tasks/elevation-bridge-decks.md`), built from
 * the LiDAR MNS that [DeckDetector] read — never the raw MNS itself, which on a deck mixes the
 * edge and the void below it, has holes over water and carries the pylons (E2 ter).
 *
 * Pure and deterministic, on the grid of [DeckDetectorOptions.stepM]; it adds no threshold: every
 * value is read from [DeckDetectorOptions].
 *
 * 1. **Regions.** Accepted spans, sorted, are joined when fewer than `mergeGapM` stations separate
 *    them **and** every station between them lies more than `minCanyonDropM` under the straight line
 *    from the first span's last anchor to the second span's first anchor (MNS at both): a canyon
 *    under one deck that the detector cut in two (Pont de Bellevue, `test2`). A gap on the ground
 *    (an island, Saumur; Normandie's two spans) keeps them apart.
 * 2. **Anchors.** The stations of the region that are *clean* (the detector's rule: max roughness
 *    over the `roughnessWindowM` window below `maxRoughnessM`), inside a deck range of one of its
 *    spans, with a finite MNS. Truss and stay stations are rough, so the superstructure never
 *    anchors. A region with fewer than two anchors is dropped ([DeckProfiles.dropped]).
 * 3. **Profile** on `anchorFirst..anchorLast`: the MNS at the anchors, linear between them, then the
 *    rolling nan-median over `surfaceMedianWindowM` (never reading past the anchors), then the
 *    **slope cap**: the smallest profile above it whose slope never exceeds `maxSlopeP95` between
 *    two stations.
 * 4. **Ramps.** Between each pin and the nearest anchor, linear ([DeckReplacement.withEnds]). A
 *    region touching a route end holds its profile flat to that end instead.
 *
 * **The cap only raises.** It is the cone closing-then-opening of the E3 plan, and with a cone the
 * closing's dilation is already `maxSlopeP95`-Lipschitz, which leaves its erosion and the whole
 * opening as identities: the result is the dilation, the least majorant of that slope. A sag (edge
 * mixing, Cheviré) is filled from the deck on each side; a spike is kept and widened into a tent of
 * that slope. A spike on clean anchors is rare — pylons and stays are rough, and anchors skip them —
 * but it is not filtered here (`known limit` test).
 *
 * **Known limits.**
 * - A smooth roof merged into a span within `mergeGapM` that leaves it under `maxSlopeP95` is in the
 *   span's deck mask ([DeckDetector], *known limits*), so it gets anchors and the road under it is
 *   lifted to its top. No worse than the detector's own mask; not cured here.
 * - A long *smooth* sag (Cheviré s 1095–1300, edge mixing in the z15 pixel) anchors like a deck. The
 *   cap reduces its slope to `maxSlopeP95` and fills it from both sides; how far the result still is
 *   from the road there is unmeasured, for lack of a truth.
 */
object DeckProfileBuilder {
    /** The replacements for the accepted spans of [detection], which [profile] produced. */
    fun build(
        profile: DeckProfile,
        detection: DeckDetection,
        options: DeckDetectorOptions = DeckDetectorOptions.DEFAULT,
    ): DeckProfiles {
        val n = profile.size
        if (n == 0 || detection.accepted.isEmpty()) return DeckProfiles(emptyList(), emptyList())
        val state = DeckDetector.surfaceState(profile, options)
        val mns = profile.mnsM
        val mnt = profile.mntM

        fun anchorsOf(span: DeckCandidate): List<Int> =
            span.deckRanges
                .flatMap { it.toList() }
                .filter { it in span.first..span.last && state.clean[it] && mns[it].isFinite() }
                .distinct()
                .sorted()

        val spans = detection.accepted.sortedBy { it.first }
        val regions = ArrayList<MutableList<Pair<DeckCandidate, List<Int>>>>()
        for (span in spans) {
            val anchors = anchorsOf(span)
            val region = regions.lastOrNull()
            if (region != null && joins(region.last(), span to anchors, mns, mnt, options)) {
                region += span to anchors
            } else {
                regions += mutableListOf(span to anchors)
            }
        }

        val replacements = ArrayList<DeckReplacement>()
        val dropped = ArrayList<List<DeckCandidate>>()
        for (region in regions) {
            val anchors = region.flatMap { it.second }.distinct().sorted()
            val regionSpans = region.map { it.first }
            if (anchors.size < 2) {
                dropped += regionSpans
                continue
            }
            replacements += replacement(regionSpans, anchors.toIntArray(), mns, n, options)
        }
        return DeckProfiles(replacements, dropped)
    }

    /** Step 1: does [next] continue the region whose last span is [previous]? */
    private fun joins(
        previous: Pair<DeckCandidate, List<Int>>,
        next: Pair<DeckCandidate, List<Int>>,
        mns: DoubleArray,
        mnt: DoubleArray,
        options: DeckDetectorOptions,
    ): Boolean {
        val (a, aAnchors) = previous
        val (b, bAnchors) = next
        if (b.first - a.last - 1 >= options.mergeGapStations) return false
        if (aAnchors.isEmpty() || bAnchors.isEmpty()) return false
        val c0 = aAnchors.last()
        val c1 = bAnchors.first()
        // `!(x > t)` rather than `x <= t`: a NaN MNT in the gap is no canyon.
        return (a.last + 1 until b.first).none { k ->
            val line = mns[c0] + (mns[c1] - mns[c0]) * (k - c0) / (c1 - c0)
            !(line - mnt[k] > options.minCanyonDropM)
        }
    }

    /** Steps 2–4 for one region with at least two [anchors]. */
    private fun replacement(
        spans: List<DeckCandidate>,
        anchors: IntArray,
        mns: DoubleArray,
        n: Int,
        options: DeckDetectorOptions,
    ): DeckReplacement {
        val u = anchors.first()
        val v = anchors.last()
        val half = options.surfaceMedianWindowStations / 2
        // x on [u − half, v + half]: the anchors' MNS, linear between them, NaN past them, so the
        // median window shrinks at the ends instead of reading the road beyond the deck.
        val x = DoubleArray(v - u + 1 + 2 * half) { Double.NaN }
        for (j in 0 until anchors.size - 1) {
            val c0 = anchors[j]
            val c1 = anchors[j + 1]
            for (k in c0..c1) x[k - u + half] = mns[c0] + (mns[c1] - mns[c0]) * (k - c0) / (c1 - c0)
        }
        val median = DeckDetector.rollingNanMedian(x, half)
        val deck = DoubleArray(v - u + 1) { median[it + half] }
        capSlope(deck, options.maxSlopeP95 * options.stepM)

        val spanFirst = spans.first().first
        val spanLast = spans.last().last
        val first = maxOf(0, spanFirst - 1)
        val last = minOf(n - 1, spanLast + 1)
        val z =
            DoubleArray(last - first + 1) { j ->
                val k = first + j
                when {
                    k in u..v -> deck[k - u]
                    k < u && first == spanFirst -> deck.first() // the region starts the route: flat
                    k > v && last == spanLast -> deck.last() // the region ends it: flat
                    else -> Double.NaN // a pin or a ramp station
                }
            }
        return DeckReplacement(first, last, spanFirst, spanLast, u, v, anchors, z, spans)
    }

    /**
     * Raise [z] to the least profile above it whose slope between two stations never exceeds
     * [maxRise] in either direction — the dilation by a cone of that slope, two linear passes. The
     * closing-then-opening of the E3 plan reduces to it (see [DeckProfileBuilder]).
     */
    internal fun capSlope(
        z: DoubleArray,
        maxRise: Double,
    ) {
        for (k in 1 until z.size) z[k] = maxOf(z[k], z[k - 1] - maxRise)
        for (k in z.size - 2 downTo 0) z[k] = maxOf(z[k], z[k + 1] - maxRise)
    }
}

/** Fill every `NaN` of [z] linearly between its finite neighbours; leading and trailing `NaN` stay. */
internal fun fillLinear(z: DoubleArray) {
    var prev = -1
    for (k in z.indices) {
        if (z[k].isNaN()) continue
        if (prev >= 0 && k - prev > 1) {
            for (j in prev + 1 until k) z[j] = z[prev] + (z[k] - z[prev]) * (j - prev) / (k - prev)
        }
        prev = k
    }
}
