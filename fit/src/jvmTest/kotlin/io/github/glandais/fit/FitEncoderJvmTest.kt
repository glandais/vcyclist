package io.github.glandais.fit

import com.garmin.fit.FitDecoder
import com.garmin.fit.Sport
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.garmin.fit.File as FitFileType

/**
 * Minimal encode → decode round-trip, replaying the produced bytes through the Garmin SDK's own
 * decoder. This validates that the module assembles and emits a file the reference
 * implementation accepts ; the exhaustive field-by-field round-trip is task g10.
 */
class FitEncoderJvmTest {
    private val start = Instant.parse("2026-07-28T08:00:00Z")

    private fun course(): FitCourse {
        val records =
            listOf(
                FitRecord(
                    timestamp = start,
                    latitudeDeg = 45.680697,
                    longitudeDeg = 6.396115,
                    altitudeM = 350.1,
                    distanceM = 0.0,
                    speedMs = 0.0,
                    powerW = 45,
                    heartRate = 120,
                    cadence = 85,
                    temperatureC = 18.0,
                ),
                FitRecord(
                    timestamp = start + kotlin.time.Duration.parse("10s"),
                    latitudeDeg = 45.681335,
                    longitudeDeg = 6.396195,
                    altitudeM = 349.7,
                    distanceM = 71.5,
                    speedMs = 7.15,
                    powerW = 260,
                    heartRate = 141,
                    cadence = 88,
                    temperatureC = 18.0,
                ),
                FitRecord(
                    timestamp = start + kotlin.time.Duration.parse("20s"),
                    latitudeDeg = 45.681565,
                    longitudeDeg = 6.396291,
                    altitudeM = 349.5,
                    distanceM = 143.0,
                    speedMs = 7.15,
                    powerW = 255,
                    heartRate = 145,
                    cadence = 87,
                    temperatureC = 19.0,
                ),
            )
        return FitCourse(
            name = "Col de la Madeleine",
            startTime = start,
            records = records,
            lap =
                FitLap(
                    startTime = start,
                    totalElapsedTimeS = 20.0,
                    totalTimerTimeS = 20.0,
                    totalDistanceM = 143.0,
                    totalAscentM = 0,
                    totalDescentM = 1,
                    maxSpeedMs = 7.15,
                    minAltitudeM = 349.5,
                    maxAltitudeM = 350.1,
                    startLatitudeDeg = 45.680697,
                    startLongitudeDeg = 6.396115,
                    endLatitudeDeg = 45.681565,
                    endLongitudeDeg = 6.396291,
                ),
        )
    }

    /** Replay bytes through the SDK's own decoder — the reference reader for this format. */
    private fun decode(bytes: ByteArray) = FitDecoder().decode(ByteArrayInputStream(bytes))

    @Test
    fun `encoded bytes carry the FIT signature and decode without error`() {
        val bytes = FitEncoder.encode(course())
        assertTrue(bytes.size > 100, "suspiciously small FIT file: ${bytes.size} bytes")
        // Bytes 8..11 of a FIT header are the ASCII data-type marker ".FIT".
        assertEquals(".FIT", bytes.copyOfRange(8, 12).decodeToString())
        assertNotNull(decode(bytes))
    }

    @Test
    fun `the file identifies itself as a cycling course`() {
        val messages = decode(FitEncoder.encode(course()))

        val fileId = messages.fileIdMesgs.single()
        assertEquals(FitFileType.COURSE, fileId.type)

        val courseMesg = messages.courseMesgs.single()
        assertEquals("Col de la Madeleine", courseMesg.name)
        assertEquals(Sport.CYCLING, courseMesg.sport)
    }

    @Test
    fun `every record survives with its position, altitude and sensor fields`() {
        val src = course()
        val messages = decode(FitEncoder.encode(src))
        val records = messages.recordMesgs

        assertEquals(src.records.size, records.size)
        src.records.forEachIndexed { i, expected ->
            val actual = records[i]
            // Position comes back in semicircles; convert to compare against the source degrees.
            assertEquals(
                expected.latitudeDeg,
                FitUnits.semicirclesToDegrees(actual.positionLat),
                1e-6,
                "record $i latitude",
            )
            assertEquals(
                expected.longitudeDeg,
                FitUnits.semicirclesToDegrees(actual.positionLong),
                1e-6,
                "record $i longitude",
            )
            // Altitude is stored scaled by 5 with a +500 offset, so 0.2 m is the quantum.
            assertEquals(expected.altitudeM!!, actual.altitude.toDouble(), 0.2, "record $i altitude")
            assertEquals(expected.distanceM, actual.distance.toDouble(), 0.01, "record $i distance")
            assertEquals(expected.speedMs!!, actual.speed.toDouble(), 0.001, "record $i speed")
            assertEquals(expected.powerW, actual.power, "record $i power")
            assertEquals(expected.heartRate, actual.heartRate.toInt(), "record $i heart rate")
            assertEquals(expected.cadence, actual.cadence.toInt(), "record $i cadence")
            assertEquals(expected.timestamp, FitUnits.fromFitTimestamp(actual.timestamp.timestamp))
        }
    }

    @Test
    fun `the lap summary agrees with the records`() {
        val src = course()
        val messages = decode(FitEncoder.encode(src))
        val lap = messages.lapMesgs.single()

        assertEquals(src.lap.totalDistanceM, lap.totalDistance.toDouble(), 0.01)
        assertEquals(src.lap.totalElapsedTimeS, lap.totalElapsedTime.toDouble(), 0.01)
        assertEquals(src.lap.totalTimerTimeS, lap.totalTimerTime.toDouble(), 0.01)
        assertEquals(src.lap.startTime, FitUnits.fromFitTimestamp(lap.startTime.timestamp))
        // A lap's `timestamp` is its END, so it must land elapsedTime after its startTime.
        assertEquals(
            src.lap.startTime + kotlin.time.Duration.parse("20s"),
            FitUnits.fromFitTimestamp(lap.timestamp.timestamp),
        )
        // The lap total must match the last record, or head units reject the course.
        assertEquals(
            messages.recordMesgs
                .last()
                .distance
                .toDouble(),
            lap.totalDistance.toDouble(),
            0.01,
        )
    }

    @Test
    fun `timer events bracket the record stream`() {
        val messages = decode(FitEncoder.encode(course()))
        val events = messages.eventMesgs
        assertEquals(2, events.size, "expected exactly a START and a STOP_ALL timer event")
        assertEquals(com.garmin.fit.EventType.START, events.first().eventType)
        assertEquals(com.garmin.fit.EventType.STOP_ALL, events.last().eventType)
    }

    @Test
    fun `encoding is deterministic`() {
        // FileIdMesg deliberately avoids a wall-clock `timeCreated`, so the same course encodes
        // to the same bytes — without that, no byte-level assertion in g10 could ever hold.
        assertContentEquals(FitEncoder.encode(course()), FitEncoder.encode(course()))
    }

    @Test
    fun `optional record fields are omitted rather than written as zero`() {
        // `copy(records = …)` is gone since g25: records live in a FitSegment now.
        val source = course()
        val bare =
            source.copy(
                segments =
                    source.segments.map { segment ->
                        segment.copy(
                            records =
                                segment.records.map {
                                    it.copy(powerW = null, heartRate = null, cadence = null, temperatureC = null)
                                },
                        )
                    },
            )
        val record = decode(FitEncoder.encode(bare)).recordMesgs.first()
        // A FIT consumer must be able to tell "no power meter" from "0 W".
        assertEquals(null, record.power)
        assertEquals(null, record.heartRate)
        assertEquals(null, record.cadence)
        assertEquals(null, record.temperature)
    }

    // ---- Cross-target contract (task g09) -----------------------------------

    @Test
    fun `the JVM encoder reproduces its committed reference bytes`() {
        assertContentEquals(FitReferenceBytes.JVM, FitEncoder.encode(FitReferenceCourse.build()))
    }

    @Test
    fun `the Java SDK decodes what the JS encoder produces`() {
        // The interoperability claim in FitReferenceBytes' KDoc, verified from this side: the
        // little-endian file the JavaScript SDK writes is readable by the Java SDK, and yields
        // exactly the same values as the JVM's own big-endian output.
        val fromWeb = decode(FitReferenceBytes.WEB)
        val fromJvm = decode(FitReferenceBytes.JVM)

        assertEquals(fromJvm.recordMesgs.size, fromWeb.recordMesgs.size)
        fromJvm.recordMesgs.forEachIndexed { i, expected ->
            val actual = fromWeb.recordMesgs[i]
            assertEquals(expected.positionLat, actual.positionLat, "record $i latitude")
            assertEquals(expected.positionLong, actual.positionLong, "record $i longitude")
            assertEquals(expected.altitude, actual.altitude, "record $i altitude")
            assertEquals(expected.distance, actual.distance, "record $i distance")
            assertEquals(expected.speed, actual.speed, "record $i speed")
            assertEquals(expected.power, actual.power, "record $i power")
            assertEquals(
                expected.timestamp.timestamp,
                actual.timestamp.timestamp,
                "record $i timestamp",
            )
        }
        assertEquals(fromJvm.courseMesgs.single().name, fromWeb.courseMesgs.single().name)
        assertEquals(fromJvm.courseMesgs.single().sport, fromWeb.courseMesgs.single().sport)
        assertEquals(
            fromJvm.lapMesgs
                .single()
                .totalDistance,
            fromWeb.lapMesgs
                .single()
                .totalDistance,
        )
    }

    @Test
    fun `the two encoder families differ only where documented`() {
        val jvm = FitReferenceBytes.JVM
        val web = FitReferenceBytes.WEB
        assertEquals(jvm.size, web.size, "the two files must have the same length")
        // Header byte 1 is the protocol version: 0x20 from the Java SDK, 0x02 from the JS one.
        assertEquals(0x20.toByte(), jvm[1])
        assertEquals(0x02.toByte(), web[1])
        // Bytes 8..11 are the ".FIT" marker on both.
        assertContentEquals(jvm.copyOfRange(8, 12), web.copyOfRange(8, 12))
    }
}
