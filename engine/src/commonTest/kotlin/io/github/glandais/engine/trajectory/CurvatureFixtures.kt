package io.github.glandais.engine.trajectory

import io.github.glandais.elevation.EarthConstants
import io.github.glandais.engine.path.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds paths whose true geometry is known exactly, in metres, then inverse-projects them to
 * lat/lon so the stage under test has to do its own projection.
 *
 * Everything is anchored at 45°N 6°E — deliberately *not* on the prime meridian, because the
 * shear defect in `Path.computeBearing` is proportional to absolute longitude and vanishes at
 * `lon = 0`. A fixture built at Greenwich would silently pass tests that the shipped estimator
 * fails everywhere people actually ride.
 */
internal object CurvatureFixtures {
    const val LAT0_DEG = 45.0
    const val LON0_DEG = 6.0

    private val lat0 = LAT0_DEG * PI / 180.0
    private val lon0 = LON0_DEG * PI / 180.0
    private val k = cos(lat0)

    /** Inverse of the projection in `LocalFrame`, so metres in → the lat/lon a `Path` carries. */
    fun pathOf(
        xs: DoubleArray,
        ys: DoubleArray,
        elevationM: Double = 100.0,
    ): Path {
        require(xs.size == ys.size)
        val path = Path(xs.size)
        for (i in xs.indices) {
            path.setLatitude(i, lat0 + ys[i] / EarthConstants.MEAN_RADIUS)
            path.setLongitude(i, lon0 + xs[i] / (EarthConstants.MEAN_RADIUS * k))
            path.setElevation(i, elevationM)
        }
        path.computeDerivedData()
        return path
    }

    /**
     * A straight run of [lengthM] metres on the given [headingRad], sampled every [spacingM].
     *
     * @param lateralNoise per-point lateral displacement, metres, indexed from 0
     */
    fun straight(
        lengthM: Double,
        spacingM: Double,
        headingRad: Double = 0.0,
        startX: Double = 0.0,
        startY: Double = 0.0,
        lateralNoise: (Int) -> Double = { 0.0 },
    ): Pair<DoubleArray, DoubleArray> {
        val n = (lengthM / spacingM).toInt() + 1
        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        val ux = cos(headingRad)
        val uy = sin(headingRad)
        // Left normal of the heading.
        val nx = -uy
        val ny = ux
        for (i in 0 until n) {
            val t = i * spacingM
            val off = lateralNoise(i)
            xs[i] = startX + ux * t + nx * off
            ys[i] = startY + uy * t + ny * off
        }
        return xs to ys
    }

    /**
     * A circular arc of [radiusM] turning through [turnRad], sampled every [spacingM] of
     * arclength.
     *
     * Positive [turnRad] turns **left**, matching the sign convention of the frame under test.
     * The arc starts at ([startX], [startY]) heading [startHeadingRad].
     */
    fun arc(
        radiusM: Double,
        turnRad: Double,
        spacingM: Double,
        startX: Double = 0.0,
        startY: Double = 0.0,
        startHeadingRad: Double = 0.0,
        includeStart: Boolean = true,
    ): Pair<DoubleArray, DoubleArray> {
        val arcLength = radiusM * kotlin.math.abs(turnRad)
        val steps = (arcLength / spacingM).toInt()
        val sign = if (turnRad >= 0.0) 1.0 else -1.0
        // Centre sits one radius to the left (right) of the start heading for a left (right) turn.
        val cx = startX - sign * radiusM * sin(startHeadingRad)
        val cy = startY + sign * radiusM * cos(startHeadingRad)
        val phi0 = kotlin.math.atan2(startY - cy, startX - cx)
        val first = if (includeStart) 0 else 1
        val n = steps - first + 1
        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        for (idx in 0 until n) {
            val i = idx + first
            val phi = phi0 + sign * (i * spacingM) / radiusM
            xs[idx] = cx + radiusM * cos(phi)
            ys[idx] = cy + radiusM * sin(phi)
        }
        return xs to ys
    }

    /** Concatenate segments, dropping each subsequent segment's duplicated first point. */
    fun join(vararg parts: Pair<DoubleArray, DoubleArray>): Pair<DoubleArray, DoubleArray> {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for ((i, p) in parts.withIndex()) {
            val from = if (i == 0) 0 else 1
            for (j in from until p.first.size) {
                xs.add(p.first[j])
                ys.add(p.second[j])
            }
        }
        return xs.toDoubleArray() to ys.toDoubleArray()
    }

    /** Heading at the end of an arc that started at [startHeadingRad] and turned [turnRad]. */
    fun endHeading(
        startHeadingRad: Double,
        turnRad: Double,
    ): Double = startHeadingRad + turnRad

    /** End point of an arc, so the next segment can be appended without a seam. */
    fun arcEnd(
        radiusM: Double,
        turnRad: Double,
        startX: Double,
        startY: Double,
        startHeadingRad: Double,
    ): Pair<Double, Double> {
        val sign = if (turnRad >= 0.0) 1.0 else -1.0
        val cx = startX - sign * radiusM * sin(startHeadingRad)
        val cy = startY + sign * radiusM * cos(startHeadingRad)
        val phi0 = kotlin.math.atan2(startY - cy, startX - cx)
        val phi = phi0 + turnRad
        return (cx + radiusM * cos(phi)) to (cy + radiusM * sin(phi))
    }

    /**
     * Deterministic uniform noise in `[-1, 1]`, from a fixed 32-bit LCG.
     *
     * A fixed generator rather than a sum of sinusoids: a 40 m sinusoid **is** a real corner of
     * ~60 m radius, and a correct estimator should report it. Testing robustness against one
     * cannot distinguish "robust" from "broken".
     */
    fun lcg(seed: Int = 12345): (Int) -> Double {
        var state = seed
        val cache = HashMap<Int, Double>()
        var generated = -1
        return { i ->
            while (generated < i) {
                state = state * 1_664_525 + 1_013_904_223
                generated++
                cache[generated] = ((state ushr 8) and 0xFFFF).toDouble() / 32768.0 - 1.0
            }
            cache[i] ?: 0.0
        }
    }
}
