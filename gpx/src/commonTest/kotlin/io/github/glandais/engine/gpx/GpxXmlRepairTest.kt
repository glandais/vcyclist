package io.github.glandais.engine.gpx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * One test per repair identified in [GpxXmlRepair]'s KDoc, plus the cross-cutting cases A-F from
 * the g04 task spec (idempotence, [GpxParser] integration, irrecoverable input, large-file cost).
 */
class GpxXmlRepairTest {
    // --- One test per repair -------------------------------------------------------------

    @Test
    fun `leading BOM is stripped`() {
        val result = GpxXmlRepair.repairVerbose(GpxFixtures.BOM_GPX)
        assertEquals(GpxFixtures.VALID_MINIMAL_GPX, result.xml)
        assertEquals(1, result.repairs.size)
        assertTrue(result.repairs.single().contains("BOM"))
    }

    @Test
    fun `illegal control character is removed`() {
        val result = GpxXmlRepair.repairVerbose(GpxFixtures.CONTROL_CHAR_GPX)
        assertTrue(!result.xml.contains('\u0000'))
        assertEquals(GpxFixtures.CONTROL_CHAR_GPX.length - 1, result.xml.length)
        assertEquals(1, result.repairs.size)
        assertTrue(result.repairs.single().contains("control character"))
        // The document must now actually parse.
        GpxParser.parse(result.xml, repairOnFailure = false)
    }

    @Test
    fun `illegal control character repair keeps valid astral surrogate pairs intact`() {
        // A real (already-legal) astral character, e.g. an emoji written as a genuine UTF-16
        // surrogate pair (not a numeric reference) — must survive untouched.
        val withEmoji = GpxFixtures.CONTROL_CHAR_GPX.replaceFirst("broken${'\u0000'}name", "cat😸face")
        val result = GpxXmlRepair.repairVerbose(withEmoji)
        assertEquals(withEmoji, result.xml)
        assertTrue(result.repairs.isEmpty())
    }

    @Test
    fun `bare ampersand is escaped`() {
        val result = GpxXmlRepair.repairVerbose(GpxFixtures.BARE_AMPERSAND_GPX)
        assertEquals(GpxFixtures.VALID_MINIMAL_GPX, result.xml)
        assertEquals(1, result.repairs.size)
        assertTrue(result.repairs.single().contains("&"))
        GpxParser.parse(result.xml, repairOnFailure = false)
    }

    @Test
    fun `bare ampersand repair leaves existing entity and numeric references untouched`() {
        val xml = "<name>&amp; &lt; &gt; &apos; &quot; &#233; &#x2603;</name>"
        val result = GpxXmlRepair.repairVerbose(xml)
        assertEquals(xml, result.xml)
        assertTrue(result.repairs.isEmpty())
    }

    @Test
    fun `surrogate pair numeric references are recombined`() {
        val result = GpxXmlRepair.repairVerbose(GpxFixtures.SURROGATE_PAIR_GPX)
        assertTrue(result.xml.contains("&#128568;"), "expected combined code point reference, got: ${result.xml}")
        assertTrue(!result.xml.contains("&#55357;"))
        assertEquals(1, result.repairs.size)
        assertTrue(result.repairs.single().contains("surrogate"))
        GpxParser.parse(result.xml, repairOnFailure = false)
    }

    @Test
    fun `surrogate pair recombination does not merge non-adjacent or mismatched references`() {
        // &#65; ('A') is not a high surrogate ; &#55357; and &#56888; are adjacent and do merge.
        val xml = "&#65;&#55357;&#56888;"
        val result = GpxXmlRepair.repairVerbose(xml)
        assertEquals("&#65;&#128568;", result.xml)
    }

    // --- Cases A-F from the task spec -----------------------------------------------------

    @Test
    fun `case A — repair of an already-valid document is a no-op`() {
        assertEquals(GpxFixtures.VALID_MINIMAL_GPX, GpxXmlRepair.repair(GpxFixtures.VALID_MINIMAL_GPX))
        assertEquals(GpxFixtures.SAMPLE_GPX, GpxXmlRepair.repair(GpxFixtures.SAMPLE_GPX))
        assertTrue(GpxXmlRepair.repairVerbose(GpxFixtures.VALID_MINIMAL_GPX).repairs.isEmpty())
    }

    @Test
    fun `case B — repair is idempotent`() {
        for (broken in listOf(
            GpxFixtures.BOM_GPX,
            GpxFixtures.CONTROL_CHAR_GPX,
            GpxFixtures.BARE_AMPERSAND_GPX,
            GpxFixtures.SURROGATE_PAIR_GPX,
        )) {
            val once = GpxXmlRepair.repair(broken)
            val twice = GpxXmlRepair.repair(once)
            assertEquals(once, twice, "repair should be idempotent for: $broken")
        }
    }

    // Cases C and D (repairOnFailure retry / opt-out) live in GpxXmlRepairParseTest, a
    // jvmTest-only suite — see that file's header for why.

    @Test
    fun `case E — unrecoverable input throws mentioning the repair attempt`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                GpxParser.parse(GpxFixtures.NOT_XML_AT_ALL)
            }
        assertTrue(
            exception.message.orEmpty().contains("repair", ignoreCase = true),
            "expected the message to mention that a repair was attempted, got: ${exception.message}",
        )
    }

    @Test
    fun `case F — a large valid file triggers no repair`() {
        val large = buildLargeValidGpx(pointCount = 20_000)
        lateinit var result: RepairResult
        val elapsed = measureTime { result = GpxXmlRepair.repairVerbose(large) }
        assertTrue(result.repairs.isEmpty())
        assertEquals(large, result.xml)
        // Not a strict perf assertion (CI machines vary) — just a guard against an accidental
        // O(n^2) regression turning a legitimate large file into a multi-minute hang.
        assertTrue(elapsed.inWholeSeconds < 10, "repairVerbose took too long on a large valid file: $elapsed")
    }

    @Test
    fun `references inside CDATA, comments and processing instructions are literal text`() {
        val xml =
            "<gpx><!-- Café & Croissant --><?app a & b?><name><![CDATA[&#55358;&#56600; & x]]></name>" +
                "<desc>a & b &#55358;&#56600;</desc><desc><![CDATA[unterminated & y</desc></gpx>"
        val repaired = GpxXmlRepair.repair(xml)
        // Outside the spans, both repairs still run; inside them, nothing moves.
        assertEquals(
            "<gpx><!-- Café & Croissant --><?app a & b?><name><![CDATA[&#55358;&#56600; & x]]></name>" +
                "<desc>a &amp; b &#129304;</desc><desc><![CDATA[unterminated & y</desc></gpx>",
            repaired,
        )
    }

    private fun buildLargeValidGpx(pointCount: Int): String {
        val sb = StringBuilder(pointCount * 80)
        sb.append(
            """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>large</name>
    <trkseg>
""",
        )
        for (i in 0 until pointCount) {
            sb.append("      <trkpt lat=\"45.").append(i).append("\" lon=\"6.0\"><ele>100</ele></trkpt>\n")
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }
}
