package io.github.glandais.engine.path

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [PointPerSecond] (1 Hz resampler).
 *
 * The synthetic paths set lat/lon (radians)/elevation/time manually. Default unset slots are
 * 0.0, which is fine for our assertions ; tests that exercise NaN propagation set NaN
 * explicitly.
 */
class PointPerSecondTest {
    private fun pathWithTimes(times: LongArray): Path {
        val p = Path(times.size)
        for (i in times.indices) {
            p.setLatitude(i, 0.0)
            // Tiny longitude shift so consecutive points are not identical (haversine > 0).
            p.setLongitude(i, i * 1e-5)
            p.setElevation(i, 100.0 + i * 10.0)
            p.setTime(i, times[i].toDouble())
        }
        return p
    }

    // --- Test 1 : empty source -------------------------------------------------
    @Test
    fun emptySourceReturnsEmptyPath() {
        val source = Path(0)
        val out = PointPerSecond.computeOnePointPerSecond(source)
        assertEquals(0, out.size)
    }

    // --- Test 2 : single point mid-second --------------------------------------
    // Source : 1 point at t = 1234 ms. i = 0 = n-1 ; msInSec != 0.
    //   - branch i==0 && msInSec1 != 0  → Copy at epoch=1
    //   - branch i==n-1 && msInSec1 != 0 → Copy at epoch=2 (overwrites? no, different key)
    // Result : 2 points at times 1000 and 2000, both copies of source[0].
    @Test
    fun singlePointMidSecondYieldsTwoCopies() {
        val source = pathWithTimes(longArrayOf(1234L))
        val out = PointPerSecond.computeOnePointPerSecond(source)
        assertEquals(2, out.size)
        assertEquals(1000.0, out.time(0))
        assertEquals(2000.0, out.time(1))
        // Both points are copies of source[0] : same elevation.
        assertEquals(100.0, out.elevation(0))
        assertEquals(100.0, out.elevation(1))
    }

    // --- Test 3 : two points exactly on epoch boundaries (0 ms, 1000 ms) -------
    // i=0 : msInSec1 == 0, epoch1=0 ; epoch2=1 ; epoch1 != epoch2
    //   → epochStart = epoch1 = 0 ; epochEnd = epoch2 = 1
    //   → loop e=0 (coef=0 → source[0]), e=1 (coef=1 → source[1])
    // i=1 = n-1 : msInSec1 == 0, no copy added.
    // Result : 2 epochs (0, 1), times 0 and 1000.
    @Test
    fun twoPointsAlignedYieldTwoBoundaryPoints() {
        val source = pathWithTimes(longArrayOf(0L, 1000L))
        val out = PointPerSecond.computeOnePointPerSecond(source)
        assertEquals(2, out.size)
        assertEquals(0.0, out.time(0))
        assertEquals(1000.0, out.time(1))
        assertEquals(100.0, out.elevation(0)) // coef=0 → source[0]
        assertEquals(110.0, out.elevation(1)) // coef=1 → source[1]
    }

    // --- Test 4 : two points spanning 5 s --------------------------------------
    @Test
    fun twoPointsSpanningFiveSecondsYieldsSixSamples() {
        val source = pathWithTimes(longArrayOf(0L, 5000L))
        val out = PointPerSecond.computeOnePointPerSecond(source)
        // epoch1=0, epoch2=5, msInSec1=0 → epochStart=0, epochEnd=5 → epochs 0..5 = 6 points.
        assertEquals(6, out.size)
        for (i in 0 until out.size) {
            assertEquals((i * 1000).toDouble(), out.time(i))
        }
    }

    // --- Test 5 : linear elevation interpolation -------------------------------
    // Use times 0 and 2000 ms : intermediate epoch 1 has coef = (1000 - 0)/2000 = 0.5
    // → interpolated elevation 150.
    @Test
    fun elevationLinearlyInterpolatedAtMidpoint() {
        val p = Path(2)
        p.setLatitude(0, 0.0)
        p.setLongitude(0, 0.0)
        p.setElevation(0, 100.0)
        p.setTime(0, 0.0)
        p.setLatitude(1, 0.0)
        p.setLongitude(1, 1e-5)
        p.setElevation(1, 200.0)
        p.setTime(1, 2000.0)
        val out = PointPerSecond.computeOnePointPerSecond(p)
        // Epochs 0, 1, 2 → 3 points
        assertEquals(3, out.size)
        assertEquals(100.0, out.elevation(0), absoluteTolerance = 1e-12)
        assertEquals(150.0, out.elevation(1), absoluteTolerance = 1e-12)
        assertEquals(200.0, out.elevation(2), absoluteTolerance = 1e-12)
    }

    // --- Test 6 : output times strictly increasing and multiple of 1000 --------
    @Test
    fun outputTimesAreStrictlyIncreasingMultiplesOf1000() {
        val source = pathWithTimes(longArrayOf(250L, 1500L, 3700L, 6000L, 8250L))
        val out = PointPerSecond.computeOnePointPerSecond(source)
        assertTrue(out.size >= 2, "expected several resampled points, got ${out.size}")
        for (i in 0 until out.size) {
            val t = out.time(i).toLong()
            assertEquals(0L, t % 1000L, "time($i) = $t is not a multiple of 1000 ms")
            if (i > 0) {
                assertTrue(out.time(i) > out.time(i - 1), "time($i) not strictly > time(${i - 1})")
            }
        }
    }

    // --- Test 7 : idempotence on already 1 Hz path -----------------------------
    // Source times 0/1000/2000/3000 ms. All aligned (msInSec == 0).
    // Walk-through :
    //   i=0,e∈[0,1] → plan[0]=Interp(0,1,coef=0), plan[1]=Interp(0,1,coef=1)
    //   i=1,e∈[1,2] → plan[1]=Interp(1,2,coef=0), plan[2]=Interp(1,2,coef=1)
    //   i=2,e∈[2,3] → plan[2]=Interp(2,3,coef=0), plan[3]=Interp(2,3,coef=1)
    //   i=3 = n-1, msInSec==0 → no copy.
    // Final plan : epoch 0/1/2/3 with coef=0 (source[i]) or coef=1 (source[i+1]) — exactly
    // the input values. Output identical in size and content (modulo derived data recompute).
    @Test
    fun idempotentOnAligned1HzPath() {
        val source = pathWithTimes(longArrayOf(0L, 1000L, 2000L, 3000L))
        val out = PointPerSecond.computeOnePointPerSecond(source)
        assertEquals(4, out.size)
        for (i in 0 until 4) {
            assertEquals((i * 1000).toDouble(), out.time(i))
            assertEquals(100.0 + i * 10.0, out.elevation(i), absoluteTolerance = 1e-12)
        }
    }

    // --- Test 8 : computeDerivedData side-effect -------------------------------
    // After resampling, distance(0)=0 and distance(i) > 0 for i>0 (lat/lon shift).
    @Test
    fun derivedDataComputedAfterResample() {
        val source = pathWithTimes(longArrayOf(0L, 1000L, 2000L, 3000L))
        val out = PointPerSecond.computeOnePointPerSecond(source)
        assertEquals(0.0, out.distance(0))
        for (i in 1 until out.size) {
            assertTrue(out.distance(i) > out.distance(i - 1), "distance($i) not > distance(${i - 1})")
        }
        assertTrue(out.totalDistance > 0.0)
    }

    // --- Test 9 : NaN propagation on interpolated slot -------------------------
    // If source[0].temperature is NaN, the interpolated slot at epoch 1 must be NaN.
    @Test
    fun nanInSourceFieldPropagatesToInterpolated() {
        val p = Path(2)
        p.setLatitude(0, 0.0)
        p.setLongitude(0, 0.0)
        p.setElevation(0, 100.0)
        p.setTime(0, 0.0)
        p.setLatitude(1, 0.0)
        p.setLongitude(1, 1e-5)
        p.setElevation(1, 200.0)
        p.setTime(1, 2000.0)
        p.setTemperature(0, Double.NaN)
        p.setTemperature(1, 25.0)
        val out = PointPerSecond.computeOnePointPerSecond(p)
        // Epoch 1 (idx 1) is interpolated with coef=0.5 → temperature = NaN.
        assertTrue(out.temperature(1).isNaN(), "interpolated temperature should be NaN")
        // Epoch 0 (idx 0) is interpolated with coef=0 → still NaN (v1=NaN propagates).
        assertTrue(out.temperature(0).isNaN(), "coef=0 interpolation with NaN source must propagate NaN")
        // Epoch 2 (idx 2) is interpolated with coef=1 → still NaN (v1=NaN propagates).
        assertTrue(out.temperature(2).isNaN(), "coef=1 interpolation with NaN source must propagate NaN")
    }

    // --- Test 10 : high-frequency input (~30 fps) ------------------------------
    // 30 points spaced 33 ms apart, covering ~957 ms total.
    // - i=0 t=0 (aligned) ; loops will see epoch1=0 throughout until t crosses 1000 ms.
    // - times go up to 29*33 = 957 ms : never reach a new epoch. Only the last point (i=29) is
    //   mid-second → adds Copy at epoch=1. No interpolations triggered (all epoch1==epoch2=0).
    // Result : 1 single point at time=1000 (Copy of source[29]).
    @Test
    fun highFrequencyInputCollapsesToBoundaryCopy() {
        val times = LongArray(30) { (it * 33L) }
        val source = pathWithTimes(times)
        val out = PointPerSecond.computeOnePointPerSecond(source)
        assertEquals(1, out.size)
        assertEquals(1000.0, out.time(0))
        // It's a copy of source[29] : elevation = 100 + 29*10 = 390.
        assertEquals(390.0, out.elevation(0))
    }

    // --- Test 11 : interpolation preserves lat/lon ----------------------------
    // 2 points at times 0 and 4000 ms, lat 0→0.4 rad, lon 0→0.8 rad.
    @Test
    fun latLonInterpolatedLinearly() {
        val p = Path(2)
        p.setLatitude(0, 0.0)
        p.setLongitude(0, 0.0)
        p.setElevation(0, 0.0)
        p.setTime(0, 0.0)
        p.setLatitude(1, 0.4)
        p.setLongitude(1, 0.8)
        p.setElevation(1, 0.0)
        p.setTime(1, 4000.0)
        val out = PointPerSecond.computeOnePointPerSecond(p)
        assertEquals(5, out.size) // epochs 0..4
        // At epoch 2 (idx 2) : coef = (2000 - 0) / 4000 = 0.5 → lat=0.2, lon=0.4
        assertEquals(0.2, out.latitude(2), absoluteTolerance = 1e-12)
        assertEquals(0.4, out.longitude(2), absoluteTolerance = 1e-12)
        // All times are multiples of 1000.
        for (i in 0 until out.size) {
            assertEquals(0L, out.time(i).toLong() % 1000L)
        }
    }

    // --- Test 12 : first point aligned, second mid-second ---------------------
    // time(0)=0 aligned, time(1)=1500 ms.
    // i=0 : msInSec==0, no copy. epoch1=0, epoch2=1, epoch1 != epoch2.
    //   epochStart=0, epochEnd=1 → plan[0]=Interp(0,1,coef=0), plan[1]=Interp(0,1,coef=2/3)
    // i=1 = n-1 : msInSec1 != 0 → Copy at epoch=2.
    // Result : 3 epochs (0, 1, 2) at times 0, 1000, 2000.
    @Test
    fun firstPointAlignedSecondPointMidSecond() {
        val source = pathWithTimes(longArrayOf(0L, 1500L))
        val out = PointPerSecond.computeOnePointPerSecond(source)
        assertEquals(3, out.size)
        assertEquals(0.0, out.time(0))
        assertEquals(1000.0, out.time(1))
        assertEquals(2000.0, out.time(2))
        // idx 0 : coef=0 → source[0].elevation = 100
        assertEquals(100.0, out.elevation(0), absoluteTolerance = 1e-12)
        // idx 1 : coef = 1000/1500 = 2/3 → 100 + 10 * 2/3 = 106.6666…
        assertEquals(100.0 + 10.0 * 2.0 / 3.0, out.elevation(1), absoluteTolerance = 1e-12)
        // idx 2 : Copy of source[1] → 110
        assertEquals(110.0, out.elevation(2), absoluteTolerance = 1e-12)
    }
}
