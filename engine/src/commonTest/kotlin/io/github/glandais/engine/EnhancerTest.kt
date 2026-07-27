package io.github.glandais.engine

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.MathConstants
import io.github.glandais.elevation.RawTile
import io.github.glandais.engine.gpx.GpxFixtures
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.tracksAsPaths
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.physics.PowerProviderConstant
import io.github.glandais.engine.physics.RhoProviderEstimate
import io.github.glandais.engine.physics.WindProviderNone
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EnhancerTest {
    // ---- Helpers ------------------------------------------------------------

    /**
     * Constant-elevation fetcher : every pixel decodes to [elev] (Terrarium encoding).
     * Reused pattern from `ElevationStepTest` / task 24.
     */
    private fun constantFetcher(
        tileSize: Int,
        elev: Int,
    ): suspend (String) -> RawTile =
        { url ->
            val raw = elev + 32768
            val r = (raw shr 8) and 0xFF
            val g = raw and 0xFF
            val rgba = ByteArray(tileSize * tileSize * 4)
            for (py in 0 until tileSize) {
                for (px in 0 until tileSize) {
                    val idx = (py * tileSize + px) * 4
                    rgba[idx] = r.toByte()
                    rgba[idx + 1] = g.toByte()
                    rgba[idx + 2] = 0
                    rgba[idx + 3] = 255.toByte()
                }
            }
            check(url.isNotEmpty())
            RawTile(tileSize, tileSize, rgba)
        }

    private fun providerWith(fetcher: suspend (String) -> RawTile): ElevationProvider =
        ElevationProvider(
            ElevationProviderConfig(
                zoomLevel = 0,
                tileSize = 4,
                cacheSize = 4,
                tileUrlTemplate = "test://{z}/{x}/{y}",
            ),
            fetcher = fetcher,
        )

    /** A small but plausible 5-point GPS path : ~50 m spacing in France, gentle elevation. */
    private fun samplePath(): Path {
        // ~50 m east of 48.000, 2.000 per step (longitude step at 48° N : 1.5e-4° ≈ 11 m).
        // Use 7e-4° per step ≈ ~52 m to be safer.
        val latDeg = doubleArrayOf(48.0000, 48.0000, 48.0000, 48.0000, 48.0000)
        val lonDeg =
            doubleArrayOf(
                2.0000,
                2.0007,
                2.0014,
                2.0021,
                2.0028,
            )
        val eleM = doubleArrayOf(100.0, 102.0, 104.0, 103.0, 101.0)
        // Times spaced 5 s apart (raw GPX cadence).
        val timeMs = doubleArrayOf(0.0, 5_000.0, 10_000.0, 15_000.0, 20_000.0)
        val p = Path(latDeg.size)
        for (i in latDeg.indices) {
            p.setLatitude(i, latDeg[i] * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, lonDeg[i] * MathConstants.DEG_TO_RAD)
            p.setElevation(i, eleM[i])
            p.setTime(i, timeMs[i])
        }
        p.computeDerivedData()
        return p
    }

    private val allOff =
        EnhanceOptions(
            fixElevation = false,
            computeMaxSpeeds = false,
            virtualizeTrack = false,
            computeOnePointPerSecond = false,
            simplifyPath = SimplifyPathOptions(enabled = false),
        )

    // ---- 1. all steps off → PointPerDistance still runs (always) + smoothElevation ----

    @Test
    fun all_off_returns_densified_smoothed_path() =
        runTest {
            val src = samplePath()
            val out = Enhancer.enhanceCourse(Enhancer.getDefaultCourse(src), allOff)
            // PointPerDistance (always run, non-toggleable) densifies the 5-point sparse path
            // to ~1-2 m spacing → output has many more points than input.
            assertTrue(out.size > 0, "empty output : ${out.size}")
            assertTrue(out.size >= src.size, "densified path smaller than input : ${out.size} < ${src.size}")
            // First and last waypoints preserved (PointPerDistance keeps source endpoints and
            // densification is linear interpolation, no smoothing of lat/lon).
            assertEquals(src.latitude(0), out.latitude(0), 1e-12)
            assertEquals(src.longitude(0), out.longitude(0), 1e-12)
            assertEquals(src.latitude(src.size - 1), out.latitude(out.size - 1), 1e-12)
            assertEquals(src.longitude(src.size - 1), out.longitude(out.size - 1), 1e-12)
            // totalDistance approximately preserved (linear interpolation is close to Haversine
            // on < 100 m segments).
            val srcDist = src.totalDistance
            assertTrue(out.totalDistance > 0.0)
            assertTrue(
                kotlin.math.abs(out.totalDistance - srcDist) / srcDist < 0.01,
                "totalDistance drift > 1% : src=$srcDist out=${out.totalDistance}",
            )
        }

    // ---- 2. DEFAULT pipeline with mock provider → finite path, monotone time --

    @Test
    fun default_pipeline_with_mock_provider_produces_monotone_time_path() =
        runTest {
            val src = samplePath()
            val provider = providerWith(constantFetcher(4, 250))
            val out =
                Enhancer.enhanceCourse(
                    Enhancer.getDefaultCourse(src),
                    EnhanceOptions.DEFAULT,
                    provider,
                )
            assertTrue(out.size >= 2, "pipeline output too small : ${out.size}")
            // time monotone (PointPerSecond + simplify keep ordering).
            for (i in 1 until out.size) {
                assertTrue(out.time(i) >= out.time(i - 1), "time regression at $i")
            }
        }

    // ---- 3. enhanceCourseDefault equivalent to enhanceCourse(getDefaultCourse) --

    @Test
    fun enhanceCourseDefault_equivalent_to_enhanceCourse_getDefaultCourse() =
        runTest {
            val src1 = samplePath()
            val src2 = samplePath()
            val out1 = Enhancer.enhanceCourseDefault(src1, elevationProvider = null)
            val out2 =
                Enhancer.enhanceCourse(
                    Enhancer.getDefaultCourse(src2),
                    EnhanceOptions.DEFAULT,
                    elevationProvider = null,
                )
            assertEquals(out1.size, out2.size)
            for (i in 0 until out1.size) {
                assertEquals(out1.latitude(i), out2.latitude(i), 1e-15, "lat $i")
                assertEquals(out1.longitude(i), out2.longitude(i), 1e-15, "lon $i")
                assertEquals(out1.elevation(i), out2.elevation(i), 1e-9, "ele $i")
                assertEquals(out1.time(i), out2.time(i), 1e-9, "time $i")
            }
        }

    // ---- 4. getDefaultCourse : 280 W power provider ------------------------

    @Test
    fun getDefaultCourse_power_provider_is_constant_280W() {
        val course = Enhancer.getDefaultCourse(samplePath())
        val provider = course.cyclistPowerProvider
        assertTrue(provider is PowerProviderConstant, "expected PowerProviderConstant, got ${provider::class}")
        assertEquals(EngineConstants.DEFAULT_CYCLIST_POWER_W, provider.power)
        assertEquals(280.0, provider.power)
    }

    // ---- 5. getDefaultCourse : RhoProviderEstimate -------------------------

    @Test
    fun getDefaultCourse_rho_provider_is_estimate() {
        val course = Enhancer.getDefaultCourse(samplePath())
        assertSame(RhoProviderEstimate, course.rhoProvider)
    }

    // ---- 6. getDefaultCourse : WindProviderNone ----------------------------

    @Test
    fun getDefaultCourse_wind_provider_is_none() {
        val course = Enhancer.getDefaultCourse(samplePath())
        assertSame(WindProviderNone, course.windProvider)
    }

    // ---- 7. virtualizeTrack=true forces computeMaxSpeeds ------------------

    @Test
    fun virtualize_track_runs_even_without_explicit_max_speeds_flag() =
        runTest {
            val src = samplePath()
            val options =
                EnhanceOptions(
                    fixElevation = false,
                    computeMaxSpeeds = false,
                    virtualizeTrack = true,
                    computeOnePointPerSecond = false,
                    simplifyPath = SimplifyPathOptions(enabled = false),
                )
            // Should not crash : virtualize requires speedMax, which is computed implicitly.
            val out =
                Enhancer.enhanceCourse(
                    Enhancer.getDefaultCourse(src),
                    options,
                    elevationProvider = null,
                )
            // PointPerDistance (always run) densifies → out.size != src.size, just check non-empty.
            assertTrue(out.size > 0, "empty output : ${out.size}")
            // Virtualization writes a finite, positive time at the second-to-last point.
            assertTrue(out.time(out.size - 2).isFinite())
            assertTrue(out.time(out.size - 2) >= 0.0)
        }

    // ---- 8. fixElevation skipped silently when provider is null -----------

    @Test
    fun fix_elevation_skipped_when_provider_null_but_smoother_still_runs() =
        runTest {
            val src = samplePath()
            // Force a noticeable spike to verify the smoother does run.
            src.setElevation(2, 500.0)
            src.computeDerivedData()
            // Only fixElevation enabled in options, but provider is null → step skipped.
            // smoothElevation always runs → middle peak should be reduced.
            val options =
                EnhanceOptions(
                    fixElevation = true,
                    computeMaxSpeeds = false,
                    virtualizeTrack = false,
                    computeOnePointPerSecond = false,
                    simplifyPath = SimplifyPathOptions(enabled = false),
                )
            val out =
                Enhancer.enhanceCourse(
                    Enhancer.getDefaultCourse(src),
                    options,
                    elevationProvider = null,
                )
            // PointPerDistance (always run) densifies → out.size != src.size, just check non-empty.
            assertTrue(out.size > 0, "empty output : ${out.size}")
            // The 500 m spike at the middle of the source should be smoothed away over the
            // densified path. Check the max elevation is well below the input spike.
            var maxEle = Double.NEGATIVE_INFINITY
            for (i in 0 until out.size) {
                if (out.elevation(i) > maxEle) maxEle = out.elevation(i)
            }
            assertTrue(maxEle < 500.0, "spike not reduced : max=$maxEle")
        }

    // ---- 9. complete pipeline on 5-point path → reasonable size -----------

    @Test
    fun complete_pipeline_on_short_path_produces_reasonable_size() =
        runTest {
            val src = samplePath()
            val out =
                Enhancer.enhanceCourseDefault(
                    src,
                    elevationProvider = null,
                    options = EnhanceOptions.DEFAULT,
                )
            // 5 points → after virtualize keeps size, PointPerSecond resamples to 1 Hz
            // (~20 s span → ~20 epochs), then simplify drops to a handful.
            // Loose bounds : at least 2 (start + end), at most 100.
            assertTrue(out.size in 2..100, "unexpected output size : ${out.size}")
        }

    // ---- 10. provider that throws → exception propagates ------------------

    @Test
    fun failing_provider_propagates_exception() =
        runTest {
            val src = samplePath()
            val throwingFetcher: suspend (String) -> RawTile = { _ -> error("boom from fetcher") }
            val provider = providerWith(throwingFetcher)
            assertFailsWith<IllegalStateException> {
                Enhancer.enhanceCourse(
                    Enhancer.getDefaultCourse(src),
                    EnhanceOptions.DEFAULT,
                    provider,
                )
            }
        }

    // ---- 11. pipeline without simplification → matches PointPerSecond size --

    @Test
    fun pipeline_without_simplify_matches_one_point_per_second_size() =
        runTest {
            val src = samplePath()
            val options =
                EnhanceOptions(
                    fixElevation = false,
                    computeMaxSpeeds = true,
                    virtualizeTrack = true,
                    computeOnePointPerSecond = true,
                    simplifyPath = SimplifyPathOptions(enabled = false),
                )
            val out =
                Enhancer.enhanceCourse(
                    Enhancer.getDefaultCourse(src),
                    options,
                    elevationProvider = null,
                )
            // PointPerSecond should resample to (roughly) one point per epoch second.
            // The exact count depends on virtualization timing; check it's at least 2.
            assertTrue(out.size >= 2, "expected at least 2 points after 1Hz resample, got ${out.size}")
            // No simplification → 1 Hz cadence preserved : consecutive times differ by ~1 000 ms.
            if (out.size >= 2) {
                val dt = out.time(1) - out.time(0)
                assertTrue(dt > 0.0 && dt <= 2_000.0, "non-1Hz cadence : dt=$dt ms")
            }
        }

    // ---- 12. pipeline with only simplify enabled → all other steps skipped --

    @Test
    fun pipeline_with_only_simplify_enabled() =
        runTest {
            val src = samplePath()
            val options =
                EnhanceOptions(
                    fixElevation = false,
                    computeMaxSpeeds = false,
                    virtualizeTrack = false,
                    computeOnePointPerSecond = false,
                    simplifyPath = SimplifyPathOptions(enabled = true, toleranceM = 1.0, zExaggeration = 3.0),
                )
            val out =
                Enhancer.enhanceCourse(
                    Enhancer.getDefaultCourse(src),
                    options,
                    elevationProvider = null,
                )
            // PointPerDistance densifies, smoother runs, then simplify : Douglas-Peucker
            // collapses near-straight densified segments back down to a handful of points.
            assertTrue(out.size >= 2, "simplified size below 2 : ${out.size}")
            // The first and last source waypoints should be retained by Douglas-Peucker.
            assertEquals(src.latitude(0), out.latitude(0), 1e-12)
            assertEquals(src.latitude(src.size - 1), out.latitude(out.size - 1), 1e-12)
        }

    // ---- enhanceCourses (multi-track, task g02) -----------------------------

    @Test
    fun enhanceCourses_returns_one_virtualized_path_per_input_in_order() =
        runTest {
            val paths = GpxParser.parse(GpxFixtures.MULTI_TRACK_GPX).tracksAsPaths()
            assertEquals(2, paths.size)

            val out = Enhancer.enhanceCourses(paths, elevationProvider = null)

            assertEquals(2, out.size, "one result per input track")
            for ((i, p) in out.withIndex()) {
                assertTrue(p.size >= 2, "track $i collapsed to ${p.size} points")
                assertTrue(p.totalDistance > 0.0, "track $i has no distance")
                // Virtualization ran : the simulation writes a strictly positive duration and a
                // non-zero computed speed, neither of which is present on the raw parsed path.
                assertTrue(p.durationMs > 0.0, "track $i was not virtualized (durationMs=${p.durationMs})")
                assertTrue(p.speed(1) > 0.0, "track $i has zero speed at point 1")
            }
            // Order is preserved : input track 1 sits at 45° N, track 2 at 46° N.
            assertEquals(45.0, out[0].latitudeDeg(0), 0.01)
            assertEquals(46.0, out[1].latitudeDeg(0), 0.01)
        }

    @Test
    fun enhanceCourses_on_an_empty_list_returns_an_empty_list() =
        runTest {
            assertEquals(0, Enhancer.enhanceCourses(emptyList(), elevationProvider = null).size)
        }
}
