@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package io.github.glandais.engine

import io.github.glandais.engine.path.Path
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Task 41 — the ledger entries R9, R15, R16, R18 and R19 reached the core and the CLI without ever
 * reaching `EngineJsApi`. These tests cover the **parameter passing**, not the physics : R9/R16/R18/
 * R19 each have their own `commonTest` suites, and re-asserting their magnitudes here would only
 * duplicate them against a worse fixture.
 *
 * What is asserted is the failure mode this task actually fixes — *a field declared on a DTO and
 * never read*. So every case is "omitting it changes nothing" paired with "setting it changes
 * something", which no amount of declaring-without-wiring can pass.
 *
 * ## Why a synthetic fixture
 *
 * `GpxFixtures.SAMPLE_GPX` is a near-straight 47-point trace : cornering never binds on it, so a
 * wet road would be indistinguishable from a dry one and the R9 case would pass vacuously. [spiral]
 * lays points on a tight circular arc where curvature is the binding constraint by construction.
 */
class EngineJsApiLedgerTest {
    // ── Fixture ──────────────────────────────────────────────────────────────────────────────

    /**
     * A GPX track following a circle of [radiusM] at the equator, so that a degree of longitude and
     * a degree of latitude are (near enough) the same distance and the arc stays circular.
     *
     * A 20 m radius corner caps cornering at ~42 km/h dry and ~27 km/h wet — both well inside the
     * 100 km/h speed limit, so the *corner* is what binds, which is the point.
     */
    private fun spiral(
        points: Int = 400,
        radiusM: Double = 20.0,
        turns: Double = 6.0,
    ): String {
        val metersPerDeg = 111_320.0
        val body =
            (0 until points).joinToString("") { i ->
                val theta = turns * 2.0 * kotlin.math.PI * i / (points - 1)
                val lat = radiusM * sin(theta) / metersPerDeg
                val lon = radiusM * cos(theta) / metersPerDeg
                """<trkpt lat="$lat" lon="$lon"><ele>100.0</ele></trkpt>"""
            }
        // Built by concatenation, not a raw string: the XML declaration must start at offset 0, and
        // a trimIndent()-ed literal keeps a leading newline that the parser rejects.
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<gpx version=\"1.1\" creator=\"test\"><trk><name>spiral</name><trkseg>" +
            body +
            "</trkseg></trk></gpx>"
    }

    /**
     * A dead-straight eastward track that climbs [gainM] over its first half and descends it over
     * the second.
     *
     * Needed by the R18 and R19 cases for two independent reasons. [spiral] is **flat**, so terrain
     * pacing has nothing to react to and its multiplier is 1 everywhere — the R19 case would pass
     * whether or not the flag were wired. And [spiral] is nothing but corners, so the pedal-strike
     * cut-off (R10) zeroes `pCyclistProvidedMuscular` constantly, and that drop is deliberately
     * *not* rate-limited — measuring a slew bound there would measure R10, not R18.
     */
    private fun straightHill(
        points: Int = 300,
        lengthM: Double = 3000.0,
        gainM: Double = 150.0,
    ): String {
        val metersPerDeg = 111_320.0
        val body =
            (0 until points).joinToString("") { i ->
                val f = i.toDouble() / (points - 1)
                val lon = f * lengthM / metersPerDeg
                val ele = 100.0 + gainM * (if (f <= 0.5) f * 2.0 else (1.0 - f) * 2.0)
                """<trkpt lat="0.0" lon="$lon"><ele>$ele</ele></trkpt>"""
            }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<gpx version=\"1.1\" creator=\"test\"><trk><name>hill</name><trkseg>" +
            body +
            "</trkseg></trk></gpx>"
    }

    /**
     * Worst |ΔP/Δt| over the ride, read from [field].
     *
     * `pCyclistProvidedMuscular` is the field to use for R18, **not**
     * `pCyclistProvidedOptimalPower` : the latter is written by `CyclistPowerProviderBase`, i.e. by
     * the innermost provider, so it records the rider's intent *before* the decorators. The
     * decorated value is what `MuscularPowerProvider` receives and writes.
     */
    private fun worstSlew(
        path: Path,
        field: String = "pCyclistProvidedMuscular",
    ): Double {
        var worst = 0.0
        for (i in 1 until pathSize(path)) {
            // `time` is unambiguously ms; `dt` carries seconds after the pipeline (see CLAUDE.md).
            val dtS = (getField(path, i, "time") - getField(path, i - 1, "time")) / 1000.0
            if (dtS <= 0.0) continue
            val a = getField(path, i, field)
            val b = getField(path, i - 1, field)
            // Skip any transition touching zero, as the commonTest suite does. Two unrelated things
            // produce one: the pedal-strike cut-off (R10), deliberately not rate-limited, and the
            // final point of the track, for which the provider is never called at all — its field
            // stays zero-initialised, which reads as a 300 W drop that never happened.
            if (a == 0.0 || b == 0.0) continue
            worst = maxOf(worst, abs(a - b) / dtS)
        }
        return worst
    }

    /** Power at the first simulated point — where the slew ramp is visible. */
    private fun firstPower(path: Path): Double = getField(path, 0, "pCyclistProvidedMuscular")

    private fun cyclist(roadCondition: String? = null): CyclistDto {
        val base =
            js(
                "({ massKg: 80, cd: 0.7, frontalAreaM2: 0.5, " +
                    "maxLeanAngleDeg: 35, maxBrakeG: 0.4, maxSpeedKmH: 100 })",
            )
        if (roadCondition != null) base.roadCondition = roadCondition
        return base.unsafeCast<CyclistDto>()
    }

    /**
     * A bike with the pedal-strike cut-off (R10) disabled, so `pCyclistProvidedMuscular` *is* the
     * decorator chain's output.
     *
     * Required by the R18 cases and not optional: on a straight road `MaxSpeedComputer` saturates
     * the radius at 200 m, and `atan(v²/(g·200))` passes 20° at about 28 m/s — so a fast descent
     * lifts the pedals even with no corner in sight. That drop is deliberately not rate-limited,
     * so leaving the cut-off on would measure R10 rather than R18.
     */
    private fun bikeWithoutPedalStrike(): BikeDto =
        js(
            "({ crr: 0.004, inertiaFront: 0.05, inertiaRear: 0.07, wheelRadiusM: 0.35, " +
                "efficiency: 0.976, maxPedalingLeanAngleDeg: 90 })",
        ).unsafeCast<BikeDto>()

    private suspend fun durationOf(
        gpx: String,
        cyclistDto: CyclistDto?,
        power: PowerProviderDto?,
    ): Double = pathDurationMs(enhanceWithCourse(parseGpx(gpx), cyclistDto, null, null, power, null).await())

    // ── R9 — road condition ──────────────────────────────────────────────────────────────────

    @Test
    fun `omitting roadCondition is exactly the dry preset`() =
        runTest {
            val gpx = spiral()
            val omitted = durationOf(gpx, cyclist(), null)
            val dry = durationOf(gpx, cyclist("dry"), null)
            // DRY is atan(tan(35°)) round-tripped, which is exact — the ledger claims bit-for-bit
            // and this is the JS-side half of that claim.
            assertEquals(omitted, dry, absoluteTolerance = 0.0)
        }

    @Test
    fun `wet costs time on a corner-bound course`() =
        runTest {
            val gpx = spiral()
            val dry = durationOf(gpx, cyclist("dry"), null)
            val wet = durationOf(gpx, cyclist("wet"), null)
            assertTrue(wet > dry, "wet should be slower than dry (dry=$dry wet=$wet)")
        }

    @Test
    fun `roadCondition is case-insensitive, and an unknown value is rejected`() =
        runTest {
            val gpx = spiral()
            assertEquals(
                durationOf(gpx, cyclist("wet"), null),
                durationOf(gpx, cyclist("WET"), null),
                absoluteTolerance = 0.0,
            )
            assertFailsWith<IllegalStateException> { cyclist("damp").let { durationOf(gpx, it, null) } }
        }

    // ── R16 — critical-power rider ───────────────────────────────────────────────────────────

    @Test
    fun `critical-power with a spent reserve settles below a constant target`() =
        runTest {
            val gpx = spiral()
            val constant = js("({ type: 'constant', power: 300 })").unsafeCast<PowerProviderDto>()
            // W' of 100 J at 300 W against CP 200 W empties in well under a second, so the taper is
            // fully engaged for the whole ride — no dependence on the fixture being long.
            val cp =
                js("({ type: 'critical-power', power: 300, criticalPower: 200, wPrime: 100 })")
                    .unsafeCast<PowerProviderDto>()
            val fast = durationOf(gpx, cyclist(), constant)
            val slow = durationOf(gpx, cyclist(), cp)
            assertTrue(slow > fast, "a spent W' reserve should be slower (constant=$fast cp=$slow)")
        }

    @Test
    fun `an unknown power model is still rejected`() =
        runTest {
            // The R17 spelling, kept as a case because it is the one that broke the demo.
            val bogus = js("({ type: 'constant_tiring', power: 250 })").unsafeCast<PowerProviderDto>()
            // The error surfaces as a rejected Promise, not a synchronous throw: enhanceWithCourse
            // builds the provider inside the coroutine.
            assertFailsWith<IllegalStateException> {
                enhanceWithCourse(parseGpx(spiral()), null, null, null, bogus, null).await()
            }
        }

    // ── R18 — slew limit ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the slew limit bounds how fast the rider's power may move`() =
        runTest {
            val gpx = straightHill()
            val unlimited =
                enhanceWithCourse(
                    parseGpx(gpx),
                    cyclist(),
                    bikeWithoutPedalStrike(),
                    null,
                    js("({ type: 'constant', power: 300 })").unsafeCast<PowerProviderDto>(),
                    null,
                ).await()
            val slewed =
                enhanceWithCourse(
                    parseGpx(gpx),
                    cyclist(),
                    bikeWithoutPedalStrike(),
                    null,
                    js("({ type: 'constant', power: 300, maxSlewWPerS: 50 })")
                        .unsafeCast<PowerProviderDto>(),
                    null,
                ).await()

            assertTrue(
                worstSlew(slewed) <= 50.0 * 1.01,
                "slew-limited power moved at ${worstSlew(slewed)} W/s, above the 50 W/s bound",
            )

            // The bound alone would pass vacuously here, and it is worth being explicit about why:
            // against a *constant* target there is nothing to smooth except the start of the ride,
            // so the unconstrained run never exceeds 50 W/s either (measured: 0 W/s). The ledger
            // says exactly this about R18 — it only becomes load-bearing once a provider reacts to
            // terrain. So the discriminating assertion is the start ramp, not the bound.
            assertEquals(
                300.0,
                firstPower(unlimited),
                absoluteTolerance = 1e-9,
                "an unlimited rider appears at full power",
            )
            assertTrue(
                firstPower(slewed) < 300.0,
                "a slew-limited rider must ramp up from a standstill, not appear at 300 W " +
                    "(saw ${firstPower(slewed)} W at the first point)",
            )
        }

    @Test
    fun `slew is off when omitted or zero`() =
        runTest {
            val gpx = straightHill()
            val plain = durationOf(gpx, cyclist(), js("({ type: 'constant', power: 300 })").unsafeCast<PowerProviderDto>())
            val zero =
                durationOf(
                    gpx,
                    cyclist(),
                    js("({ type: 'constant', power: 300, maxSlewWPerS: 0 })").unsafeCast<PowerProviderDto>(),
                )
            val on =
                durationOf(
                    gpx,
                    cyclist(),
                    js("({ type: 'constant', power: 300, maxSlewWPerS: 50 })").unsafeCast<PowerProviderDto>(),
                )
            assertEquals(plain, zero, absoluteTolerance = 0.0)
            assertTrue(on != plain, "a 50 W/s limit should change the ride; it did not")
        }

    // ── R19 — terrain pacing ─────────────────────────────────────────────────────────────────

    @Test
    fun `pacing is off by default and changes the ride when on`() =
        runTest {
            // A hill, not the spiral: pacing keys on grade and headwind, both zero on a flat course.
            val gpx = straightHill()
            val off = durationOf(gpx, cyclist(), js("({ type: 'constant', power: 250 })").unsafeCast<PowerProviderDto>())
            val explicitOff =
                durationOf(
                    gpx,
                    cyclist(),
                    js("({ type: 'constant', power: 250, pacing: false })").unsafeCast<PowerProviderDto>(),
                )
            val on =
                durationOf(
                    gpx,
                    cyclist(),
                    js("({ type: 'constant', power: 250, pacing: true })").unsafeCast<PowerProviderDto>(),
                )
            assertEquals(off, explicitOff, absoluteTolerance = 0.0)
            assertTrue(on != off, "terrain pacing should change the ride; it did not")
        }

    @Test
    fun `pacing and slew compose in the CLI's order`() =
        runTest {
            // Both decorators on: the run must succeed and differ from either alone. The order
            // itself (slew outermost) is asserted by the bound below — if pacing wrapped slew, the
            // pacing multiplier would be applied *after* smoothing and could exceed the limit.
            val both =
                enhanceWithCourse(
                    parseGpx(straightHill()),
                    cyclist(),
                    bikeWithoutPedalStrike(),
                    null,
                    js("({ type: 'constant', power: 300, pacing: true, maxSlewWPerS: 50 })")
                        .unsafeCast<PowerProviderDto>(),
                    null,
                ).await()

            assertTrue(
                worstSlew(both) <= 50.0 * 1.01,
                "with pacing inside the slew limiter the bound must still hold; saw ${worstSlew(both)} W/s",
            )
        }

    // ── Task 43 — the DTOs reject what they do not read ──────────────────────────────────────

    @Test
    fun `an unknown DTO key is an error, not a silent default`() =
        runTest {
            // The failure this prevents: `external interface` ignores unknown properties, so the
            // demo spent nine ledger entries sending R17's removed `tiringDuration` and getting a
            // default rather than a complaint. WASI has always been strict; JS is now too.
            val stale =
                js("({ type: 'durability', power: 250, tiringDuration: 7200 })")
                    .unsafeCast<PowerProviderDto>()
            val thrown =
                assertFailsWith<IllegalStateException> {
                    enhanceWithCourse(parseGpx(spiral()), null, null, null, stale, null).await()
                }
            assertTrue(
                thrown.message!!.contains("tiringDuration"),
                "the error must name the offending key, got: ${thrown.message}",
            )
        }

    @Test
    fun `every DTO checks its own keys`() =
        runTest {
            val gpx = spiral()

            val strayCyclist =
                js(
                    "({ massKg: 80, cd: 0.7, frontalAreaM2: 0.5, maxLeanAngleDeg: 35, " +
                        "maxBrakeG: 0.4, maxSpeedKmH: 100, weightKg: 80 })",
                ).unsafeCast<CyclistDto>()
            val strayBike =
                js(
                    "({ crr: 0.004, inertiaFront: 0.05, inertiaRear: 0.07, wheelRadiusM: 0.35, " +
                        "efficiency: 0.976, wheelDiameter: 0.7 })",
                ).unsafeCast<BikeDto>()
            val strayWind = js("({ windSpeed: 5, windDirection: 90, gusts: 2 })").unsafeCast<WindDto>()
            val strayOptions =
                js("({ fixElevation: false, simplify: true })").unsafeCast<EnhanceOptionsDto>()

            assertFailsWith<IllegalStateException>("CyclistDto") {
                enhanceWithCourse(parseGpx(gpx), strayCyclist, null, null, null, null).await()
            }
            assertFailsWith<IllegalStateException>("BikeDto") {
                enhanceWithCourse(parseGpx(gpx), null, strayBike, null, null, null).await()
            }
            assertFailsWith<IllegalStateException>("WindDto") {
                enhanceWithCourse(parseGpx(gpx), null, null, strayWind, null, null).await()
            }
            assertFailsWith<IllegalStateException>("EnhanceOptionsDto") {
                enhanceWithCourse(parseGpx(gpx), null, null, null, null, strayOptions).await()
            }
        }

    @Test
    fun `the JS default power is the library default`() =
        runTest {
            // Until task 43 this façade hardcoded 250 W while the CLI used 280 W, so the same
            // "unconfigured rider" rode differently depending on the door. Omitting the power DTO
            // must now match asking for the library default explicitly.
            val gpx = straightHill()
            val implicit = durationOf(gpx, cyclist(), null)
            val explicit =
                durationOf(
                    gpx,
                    cyclist(),
                    js("({ type: 'constant', power: 280 })").unsafeCast<PowerProviderDto>(),
                )
            assertEquals(implicit, explicit, absoluteTolerance = 0.0)
        }

    // ── R15 — W′ balance options ─────────────────────────────────────────────────────────────

    @Test
    fun `wPrimeBalance CP moves the W-prime field and nothing else`() =
        runTest {
            val gpx = spiral()
            val power = js("({ type: 'constant', power: 300 })").unsafeCast<PowerProviderDto>()
            val opts = js("({ computeOnePointPerSecond: true })").unsafeCast<EnhanceOptionsDto>()
            val lowCp =
                js("({ computeOnePointPerSecond: true, wPrimeBalanceCriticalPower: 150 })")
                    .unsafeCast<EnhanceOptionsDto>()

            val a = enhanceWithCourse(parseGpx(gpx), cyclist(), null, null, power, opts).await()
            val b = enhanceWithCourse(parseGpx(gpx), cyclist(), null, null, power, lowCp).await()

            assertEquals(pathSize(a), pathSize(b), "changing the W' CP must not resample the path")
            var wPrimeDiffers = false
            for (i in 0 until pathSize(a)) {
                for (def in fieldDefinitions()) {
                    val va = getField(a, i, def.prop)
                    val vb = getField(b, i, def.prop)
                    if (def.prop == "wPrimeBalance") {
                        if (va != vb) wPrimeDiffers = true
                    } else {
                        assertEquals(va, vb, absoluteTolerance = 0.0, "field ${def.prop} moved at $i")
                    }
                }
            }
            assertTrue(wPrimeDiffers, "a lower CP should have changed the W' balance trace")
        }

    @Test
    fun `wPrimeBalance can be turned off`() =
        runTest {
            val gpx = spiral()
            val off =
                js("({ computeOnePointPerSecond: true, wPrimeBalanceEnabled: false })")
                    .unsafeCast<EnhanceOptionsDto>()
            val path = enhanceWithCourse(parseGpx(gpx), cyclist(), null, null, null, off).await()
            for (i in 0 until pathSize(path)) {
                assertEquals(0.0, getField(path, i, "wPrimeBalance"), absoluteTolerance = 0.0)
            }
        }
}
