package io.github.glandais.engine.path

import io.github.glandais.elevation.Coordinates
import io.github.glandais.elevation.CoordinatesElevation
import io.github.glandais.elevation.Distance
import io.github.glandais.elevation.LatLon
import io.github.glandais.elevation.LatLonElevation
import io.github.glandais.elevation.MathConstants
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Concrete [GeneratedPath] augmented with computed statistics and ergonomic helpers.
 *
 * - Storage is **fixed-size** : the `size` is captured at construction and the underlying
 *   `DoubleArray` is never resized. Pipelines that produce paths of unknown length build a
 *   `MutableList<LatLonElevation>` first and pass it to [Path.fromCoordinates] at the end.
 * - **Latitude/longitude are stored in radians** (matches [PointField.LATITUDE].unit ==
 *   "radians"). Use [latitudeDeg]/[longitudeDeg] or [coordinatesAt] for degree-based access.
 */
class Path(
    size: Int,
) : GeneratedPath(size) {
    // ---------- Cached statistics (computed by [computeDerivedData]) -----------

    var totalDistance: Double = 0.0
        private set

    var minElevation: Double = 0.0
        private set

    var maxElevation: Double = 0.0
        private set

    /**
     * Sum of every positive elevation delta, over whatever profile this path currently holds.
     *
     * Deliberately unfiltered, and therefore scale-dependent: it grows without bound as the
     * sampling gets finer (1066 m on `strava.gpx` unsmoothed against 632 m smoothed). It is kept
     * as-is because it is the *control* the filtered figure is measured against, and because
     * `ClimbDetector` sizes its adaptive threshold from it. For a number to show a human, use
     * [reportedElevationGain]. See `docs/guides/elevation.md`.
     */
    var elevationGain: Double = 0.0
        private set

    /** Always <= 0 (sum of negative deltas). Unfiltered, like [elevationGain]. */
    var elevationLoss: Double = 0.0
        private set

    /**
     * Cumulative ascent with a hysteresis dead band, or `NaN` if [ElevationGain] has not run.
     *
     * Cached rather than computed here: [computeDerivedData] runs after every pipeline stage, and
     * the accumulator smooths a copy of the profile first, which is O(n·k). It is written by the
     * `elevationGain` stage of the enhancer and invalidated by [resetStats].
     */
    var elevationGainFiltered: Double = Double.NaN
        internal set

    /** Counterpart of [elevationGainFiltered]. Always `<= 0`, or `NaN`. */
    var elevationLossFiltered: Double = Double.NaN
        internal set

    /** [elevationGainFiltered] when it has been computed, the raw sum otherwise. */
    val reportedElevationGain: Double
        get() = if (elevationGainFiltered.isNaN()) elevationGain else elevationGainFiltered

    /** [elevationLossFiltered] when it has been computed, the raw sum otherwise. Always `<= 0`. */
    val reportedElevationLoss: Double
        get() = if (elevationLossFiltered.isNaN()) elevationLoss else elevationLossFiltered

    /** Duration of the track in milliseconds (`time(size-1) - time(0)`), or 0 if size < 2. */
    var durationMs: Double = 0.0
        private set

    /** Bounding box in **radians** (matches storage units). */
    var boundsRad: BoundsRad = BoundsRad.EMPTY
        private set

    /** Bounding box in radians ; empty for size 0. */
    data class BoundsRad(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double,
    ) {
        companion object {
            val EMPTY = BoundsRad(0.0, 0.0, 0.0, 0.0)
        }
    }

    // ---------- Iteration helpers ---------------------------------------------

    val indices: IntRange get() = 0 until size

    inline fun forEachPoint(action: (Int) -> Unit) {
        for (i in 0 until size) action(i)
    }

    // ---------- Coordinate helpers --------------------------------------------

    fun latitudeDeg(i: Int): Double = latitude(i) * MathConstants.RAD_TO_DEG

    fun longitudeDeg(i: Int): Double = longitude(i) * MathConstants.RAD_TO_DEG

    fun coordinatesAt(i: Int): Coordinates = LatLon(latitudeDeg(i), longitudeDeg(i), elevation(i))

    fun coordinatesElevationAt(i: Int): CoordinatesElevation = LatLonElevation(latitudeDeg(i), longitudeDeg(i), elevation(i))

    fun coordinatesElevationSequence(): Sequence<CoordinatesElevation> = (0 until size).asSequence().map { coordinatesElevationAt(it) }

    // ---------- Bulk operations -----------------------------------------------

    /** Deep copy of the underlying storage. Stats are re-derived (not copied). */
    fun copy(): Path {
        val out = Path(size)
        data.copyInto(out.data, destinationOffset = 0, startIndex = 0, endIndex = data.size)
        out.copyStatsFrom(this)
        return out
    }

    /** Slice `[from, until)` into a new [Path]. Stats are recomputed lazily — call
     *  [computeDerivedData] on the result if needed. */
    fun subPath(
        from: Int,
        until: Int,
    ): Path {
        require(from in 0..size) { "from=$from out of [0, $size]" }
        require(until in from..size) { "until=$until out of [$from, $size]" }
        val newSize = until - from
        val out = Path(newSize)
        data.copyInto(
            destination = out.data,
            destinationOffset = 0,
            startIndex = from * PointField.COUNT,
            endIndex = until * PointField.COUNT,
        )
        return out
    }

    /**
     * A copy with every timestamp cleared, stats re-derived.
     *
     * The FIT writer requires monotonic time ([toFitSegment] checks it point by point), which a
     * real recording does not always have: a head unit that resyncs its clock mid-ride, or two
     * traces concatenated out of order, both step backwards. A consumer facing such a file has
     * only bad options — reject the import, or hand-rebuild the path through
     * [Path.fromCoordinates] and lose everything that is not a coordinate. This is the third one.
     *
     * ## What survives, and what does not
     *
     * [PointField.TIME] is set to `0.0` on every point, then [computeDerivedData] runs. That
     * recomputes the whole time-derived family, so **`speed`, `dt`, `elapsed` and `durationMs` all
     * come back as zero** — `computeDerivedData` reads speed off the clock, and there is no longer
     * a clock. Geometry (position, elevation, `distance`, `grade`, `bearing`) is preserved exactly,
     * and fields no derivation touches — power, heart rate, cadence, temperature — are carried
     * through untouched.
     *
     * ## What the result is
     *
     * A **route**, not a recording. Encoded to FIT, every record carries the same timestamp
     * ([startTime][io.github.glandais.fit.toFitCourse]), the lap's elapsed time is `0` and its
     * max speed is `0`; the positions and cumulative distances are intact. That is what an un-timed
     * GPX produces too, and it is the honest outcome: a path whose clock was rejected has no
     * speeds to report. If the ride's speeds matter more than encodability, fix the timestamps
     * upstream instead of calling this.
     */
    fun withoutTime(): Path {
        val out = copy()
        for (i in 0 until size) {
            out.setTime(i, 0.0)
        }
        out.computeDerivedData()
        return out
    }

    private fun copyStatsFrom(other: Path) {
        totalDistance = other.totalDistance
        minElevation = other.minElevation
        maxElevation = other.maxElevation
        elevationGain = other.elevationGain
        elevationLoss = other.elevationLoss
        elevationGainFiltered = other.elevationGainFiltered
        elevationLossFiltered = other.elevationLossFiltered
        durationMs = other.durationMs
        boundsRad = other.boundsRad
    }

    // ---------- Derived data --------------------------------------------------

    /**
     * Recompute every derived field from the 4 primary inputs : `latitude`, `longitude`,
     * `elevation`, `time`. Output fields written : `distance`, `elapsed`, `dx`, `dt`, `speed`,
     * `grade`, `bearing`. Stats properties are also refreshed.
     *
     * Port of `Path.ts#computeDerivedData()` (two-pass algorithm).
     */
    fun computeDerivedData() {
        resetStats()
        if (size == 0) return

        val timeStart = time(0)

        // First pass : cumulative distance, elevation gain/loss, geographic bounds.
        var cumDist = 0.0
        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLon = Double.POSITIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        var minEle = Double.POSITIVE_INFINITY
        var maxEle = Double.NEGATIVE_INFINITY
        var gain = 0.0
        var loss = 0.0

        for (i in 0 until size) {
            val lat = latitude(i)
            val lon = longitude(i)
            val ele = elevation(i)

            minLat = min(minLat, lat)
            maxLat = max(maxLat, lat)
            minLon = min(minLon, lon)
            maxLon = max(maxLon, lon)
            minEle = min(minEle, ele)
            maxEle = max(maxEle, ele)

            if (i > 0) {
                val prevCoord = LatLon(latitudeDeg(i - 1), longitudeDeg(i - 1))
                val curCoord = LatLon(latitudeDeg(i), longitudeDeg(i))
                cumDist += Distance.haversine(prevCoord, curCoord)

                val dEle = ele - elevation(i - 1)
                if (dEle > 0) gain += dEle else loss += dEle
            }
            setDistance(i, cumDist)
        }

        totalDistance = cumDist
        minElevation = if (minEle == Double.POSITIVE_INFINITY) 0.0 else minEle
        maxElevation = if (maxEle == Double.NEGATIVE_INFINITY) 0.0 else maxEle
        elevationGain = gain
        elevationLoss = loss
        boundsRad = BoundsRad(minLat, maxLat, minLon, maxLon)
        durationMs = if (size >= 2) time(size - 1) - timeStart else 0.0

        // Second pass : per-point elapsed, dx, dt, speed, grade, bearing.
        for (i in 0 until size) {
            setElapsed(i, (time(i) - timeStart) / 1000.0)
            if (size <= 1) continue

            val im1 = max(0, i - 1)
            val ip1 = min(size - 1, i + 1)

            setBearing(i, computeBearing(im1, ip1))

            val dDist = (distance(ip1) - distance(im1)) / 2.0
            val dEle = (elevation(ip1) - elevation(im1)) / 2.0
            val grade = if (dDist == 0.0) 0.0 else dEle / dDist
            setGrade(i, grade)

            val dTime = (time(ip1) - time(im1)) / 2000.0
            setDx(i, dDist)
            setDt(i, dTime)
            setSpeed(i, if (dTime == 0.0) 0.0 else dDist / dTime)
        }
    }

    private fun resetStats() {
        totalDistance = 0.0
        minElevation = 0.0
        maxElevation = 0.0
        elevationGain = 0.0
        elevationLoss = 0.0
        // NaN, not 0: any stage that rewrites elevations invalidates the filtered figure, and
        // "not computed" must not be readable as "flat".
        elevationGainFiltered = Double.NaN
        elevationLossFiltered = Double.NaN
        durationMs = 0.0
        boundsRad = BoundsRad.EMPTY
    }

    /** Bearing between two points in radians, using simple cylindrical projection (good enough
     *  for short segments at non-polar latitudes). Port of `Path.ts#computeBearing`. */
    private fun computeBearing(
        from: Int,
        to: Int,
    ): Double {
        val lat1 = latitude(from)
        val lon1 = longitude(from)
        val lat2 = latitude(to)
        val lon2 = longitude(to)
        // Cylindrical projection (x = lon * cos(lat), y = lat)
        val x1 = lon1 * cos(lat1)
        val y1 = lat1
        val x2 = lon2 * cos(lat2)
        val y2 = lat2
        val dy = y2 - y1
        val dx = x2 - x1
        return atan2(-dy, dx)
    }

    companion object {
        /**
         * Build a [Path] from a sequence of [LatLonElevation], copying lat/lon (converted to
         * radians) and elevation into the first three slots. Other slots remain 0.0.
         *
         * [computeDerivedData] is called before returning. It used to be the caller's job, and
         * every caller got it wrong the same way: `distance(i)` stays 0 until it runs, so a path
         * that looks fully built silently has no length — [PointPerDistance] then finds every
         * point at distance 0 from the start and collapses the route. This factory is the only
         * way to build a [Path] from outside the library, so it owes the caller an object that
         * works. Pass through [Path] and the setters directly if you need the raw form.
         *
         * Java callers get this as `PathJvm.fromCoordinates(coords)`: `@JvmStatic` would read
         * better here but does not resolve from `commonMain`, so the facade carries it.
         */
        fun fromCoordinates(coords: List<CoordinatesElevation>): Path {
            val path = Path(coords.size)
            for ((i, c) in coords.withIndex()) {
                path.setLatitude(i, c.latitude * MathConstants.DEG_TO_RAD)
                path.setLongitude(i, c.longitude * MathConstants.DEG_TO_RAD)
                path.setElevation(i, c.elevation)
            }
            path.computeDerivedData()
            return path
        }
    }
}
