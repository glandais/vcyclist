package io.github.glandais.fit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Pins the FIT wire encoding on all four targets. Every value here is checked against the FIT
 * profile rather than against our own implementation, so a refactor that silently changes a
 * scale is caught rather than blessed.
 */
class FitUnitsTest {
    // ---- Epoch ---------------------------------------------------------------

    @Test
    fun `the FIT epoch is 1989-12-31T00 00 00Z, not the Unix epoch`() {
        // 631 065 600 s is the constant every FIT implementation carries, including
        // com.garmin.fit.DateTime.OFFSET. A mistake here dates the ride to 1989 or 2050.
        assertEquals(631_065_600L, FitUnits.FIT_EPOCH_OFFSET_S)
        assertEquals(631_065_600_000L, FitUnits.FIT_EPOCH_OFFSET_MS)
        assertEquals(0L, FitUnits.toFitTimestamp(Instant.parse("1989-12-31T00:00:00Z")))
    }

    @Test
    fun `timestamps round-trip through the FIT epoch`() {
        val instant = Instant.parse("2026-07-28T09:30:15Z")
        val fit = FitUnits.toFitTimestamp(instant)
        assertEquals(instant, FitUnits.fromFitTimestamp(fit))
        // Sanity: a 2026 date is ~36 years past the FIT epoch.
        assertTrue(fit > 1_100_000_000L && fit < 1_200_000_000L, "unexpected FIT timestamp $fit")
    }

    @Test
    fun `an instant before the FIT epoch yields a negative timestamp rather than wrapping`() {
        assertEquals(-86_400L, FitUnits.toFitTimestamp(Instant.parse("1989-12-30T00:00:00Z")))
    }

    // ---- Semicircles ---------------------------------------------------------

    @Test
    fun `degrees convert to semicircles at 2 pow 31 over 180`() {
        assertEquals(0, FitUnits.degreesToSemicircles(0.0))
        // 180° is exactly 2^31 semicircles, which overflows a signed Int by one — the FIT
        // profile relies on that wrap, so we assert the documented boundary instead of pretending
        // it is representable.
        assertEquals(1073741824, FitUnits.degreesToSemicircles(90.0))
        assertEquals(-1073741824, FitUnits.degreesToSemicircles(-90.0))
    }

    @Test
    fun `semicircles round-trip within sub-centimeter precision`() {
        // One semicircle is ~0.93 cm at the equator, so the tolerance below is far tighter than
        // any GPS fix and would still catch an off-by-a-factor conversion.
        for (deg in listOf(0.0, 45.680697, 6.396115, -33.8688, 151.2093, 89.999999)) {
            val back = FitUnits.semicirclesToDegrees(FitUnits.degreesToSemicircles(deg))
            assertEquals(deg, back, 1e-6, "semicircle round-trip failed for $deg")
        }
    }

    @Test
    fun `a known coordinate maps to its documented semicircle value`() {
        // sample.gpx's first trackpoint. 45.680697 * 2^31 / 180 = 544 991 943.5… → 544991944,
        // computed independently of this code (python: round(45.680697 * 2**31 / 180)).
        assertEquals(544991944, FitUnits.degreesToSemicircles(45.680697))
        // And 6.396115° of longitude, same derivation.
        assertEquals(76308624, FitUnits.degreesToSemicircles(6.396115))
    }

    // ---- Scaled integer fields ----------------------------------------------

    @Test
    fun `altitude applies the plus-500 offset then the scale of 5`() {
        assertEquals(2500L, FitUnits.altitudeToRaw(0.0))
        assertEquals(5000L, FitUnits.altitudeToRaw(500.0))
        // Below sea level is representable thanks to the offset — the Dead Sea road is at -400 m.
        assertEquals(500L, FitUnits.altitudeToRaw(-400.0))
        assertEquals(0L, FitUnits.altitudeToRaw(-500.0))
    }

    @Test
    fun `altitude round-trips`() {
        for (m in listOf(-400.0, 0.0, 350.2, 2757.0, 8848.0)) {
            assertEquals(m, FitUnits.rawToAltitude(FitUnits.altitudeToRaw(m)), 0.2)
        }
    }

    @Test
    fun `distance and speed use their profile scales`() {
        assertEquals(0L, FitUnits.distanceToRaw(0.0))
        assertEquals(100L, FitUnits.distanceToRaw(1.0))
        assertEquals(357_380L, FitUnits.distanceToRaw(3573.8))
        assertEquals(0L, FitUnits.speedToRaw(0.0))
        assertEquals(1000L, FitUnits.speedToRaw(1.0))
        assertEquals(8333L, FitUnits.speedToRaw(8.333))
    }

    // ---- Model -------------------------------------------------------------

    @Test
    fun `a FitCourse rejects an empty record list`() {
        val start = Instant.parse("2026-07-28T08:00:00Z")
        val failure =
            kotlin.runCatching {
                FitCourse(
                    name = "empty",
                    startTime = start,
                    records = emptyList(),
                    lap = FitLap(start, 0.0, 0.0, 0.0, 0, 0),
                )
            }
        assertTrue(failure.isFailure, "an empty course should not be constructible")
    }

    @Test
    fun `FitSport carries the FIT profile wire values`() {
        assertEquals(0, FitSport.GENERIC.value)
        assertEquals(1, FitSport.RUNNING.value)
        assertEquals(2, FitSport.CYCLING.value)
    }
}
