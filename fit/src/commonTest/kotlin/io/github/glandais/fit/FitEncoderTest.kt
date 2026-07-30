package io.github.glandais.fit

import com.garmin.fit.FitDecoder
import com.garmin.fit.FitMessages
import com.garmin.fit.types.EventType
import com.garmin.fit.types.Sport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import com.garmin.fit.types.File as FitFileType

/**
 * Encode → decode round-trip of a hand-built [FitCourse].
 *
 * Was `FitEncoderJvmTest`, in `jvmTest`, because decoding needed the Garmin **Java** SDK. Since
 * w12 both halves — [FitEncoder] and the decoder it is replayed through — are multiplatform, so
 * this runs on JVM, JS (Node and headless Chrome) *and* wasmWasi. Independent confirmation from
 * the vendors' own implementations lives in `FitEncoderJsTest`, which decodes the same bytes
 * with `@garmin/fitsdk`.
 */
class FitEncoderTest {
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
                    timestamp = start + 10.seconds,
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
                    timestamp = start + 20.seconds,
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

    /** Replay bytes through the SDK's decoder, failing on anything it could not read. */
    private fun decode(bytes: ByteArray): FitMessages {
        val decoder = FitDecoder(bytes)
        assertTrue(decoder.checkIntegrity(), "the decoder rejected the file's integrity check")
        val result = decoder.decode()
        assertEquals(emptyList(), result.errors.map { it.message }, "decoder reported errors")
        return result.messages
    }

    @Test
    fun `encoded bytes carry the FIT signature and decode without error`() {
        val bytes = FitEncoder.encode(course())
        assertTrue(bytes.size > 100, "suspiciously small FIT file: ${bytes.size} bytes")
        // Bytes 8..11 of a FIT header are the ASCII data-type marker ".FIT".
        assertEquals(".FIT", bytes.copyOfRange(8, 12).decodeToString())
        assertTrue(decode(bytes).recordMesgs.isNotEmpty())
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
                FitUnits.semicirclesToDegrees(actual.positionLat!!),
                1e-6,
                "record $i latitude",
            )
            assertEquals(
                expected.longitudeDeg,
                FitUnits.semicirclesToDegrees(actual.positionLong!!),
                1e-6,
                "record $i longitude",
            )
            // Altitude is stored scaled by 5 with a +500 offset, so 0.2 m is the quantum.
            assertEquals(expected.altitudeM!!, actual.altitude!!, 0.2, "record $i altitude")
            assertEquals(expected.distanceM, actual.distance!!, 0.01, "record $i distance")
            assertEquals(expected.speedMs!!, actual.speed!!, 0.001, "record $i speed")
            assertEquals(expected.powerW, actual.power!!.toInt(), "record $i power")
            assertEquals(expected.heartRate, actual.heartRate!!.toInt(), "record $i heart rate")
            assertEquals(expected.cadence, actual.cadence!!.toInt(), "record $i cadence")
            assertEquals(expected.timestamp, actual.timestamp, "record $i timestamp")
        }
    }

    @Test
    fun `the lap summary agrees with the records`() {
        val src = course()
        val messages = decode(FitEncoder.encode(src))
        val lap = messages.lapMesgs.single()

        assertEquals(src.lap.totalDistanceM, lap.totalDistance!!, 0.01)
        assertEquals(src.lap.totalElapsedTimeS, lap.totalElapsedTime!!, 0.01)
        assertEquals(src.lap.totalTimerTimeS, lap.totalTimerTime!!, 0.01)
        assertEquals(src.lap.startTime, lap.startTime)
        // A lap's `timestamp` is its END, so it must land elapsedTime after its startTime.
        assertEquals(src.lap.startTime + 20.seconds, lap.timestamp)
        // The lap total must match the last record, or head units reject the course.
        assertEquals(messages.recordMesgs.last().distance!!, lap.totalDistance!!, 0.01)
    }

    @Test
    fun `timer events bracket the record stream`() {
        val messages = decode(FitEncoder.encode(course()))
        val events = messages.eventMesgs
        assertEquals(2, events.size, "expected exactly a START and a STOP_ALL timer event")
        assertEquals(EventType.START, events.first().eventType)
        assertEquals(EventType.STOP_ALL, events.last().eventType)
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
        assertNull(record.power)
        assertNull(record.heartRate)
        assertNull(record.cadence)
        assertNull(record.temperature)
    }

    @Test
    fun `the encoder reproduces its committed reference bytes`() {
        assertContentEquals(FitReferenceBytes.REFERENCE, FitEncoder.encode(FitReferenceCourse.build()))
    }
}
