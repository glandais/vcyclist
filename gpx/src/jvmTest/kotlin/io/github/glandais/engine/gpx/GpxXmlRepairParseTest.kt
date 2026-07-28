package io.github.glandais.engine.gpx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Cases C and D from the g04 task spec : `GpxParser.parse`'s repair-on-failure retry, and the
 * `repairOnFailure = false` opt-out.
 *
 * **JVM-only, deliberately** : these need a document that (a) fails outright on a strict parse
 * and (b) parses cleanly once [GpxXmlRepair] has run. [GpxFixtures.BARE_AMPERSAND_GPX] is that
 * document on the JVM and Kotlin/JS-Node `xmlutil` backends — both reject a
 * bare `&` in text content as a syntax error, exactly like the strict XML 1.0 spec requires.
 *
 * The **Kotlin/JS browser** backend does not : it defers to the browser's native `DOMParser`,
 * which silently recovers from the same input by truncating the tree at the error instead of
 * raising it (no exception, but also no track points) — the same well-documented leniency
 * already called out in `GpxParserTest`'s case 01 comment for mismatched closing tags. Since
 * that backend never throws on this input, cases C ("does parsing succeed after an automatic
 * repair") and D ("does disabling repair surface the original failure") cannot both be
 * expressed as one assertion true across all three targets — there is no failure to observe or
 * suppress in the browser. Pinning these two cases to `jvmTest` keeps the assertions strict and
 * meaningful instead of watering them down to "does not throw", which both backends already
 * satisfy trivially. [GpxXmlRepair] itself (the string transform) is still fully covered by
 * `GpxXmlRepairTest` on all three targets ; only this parser-retry integration is JVM-only.
 */
class GpxXmlRepairParseTest {
    @Test
    fun `case C — parse transparently repairs a broken document by default`() {
        val doc = GpxParser.parse(GpxFixtures.BARE_AMPERSAND_GPX)
        assertEquals(1, doc.tracks.size)
        assertEquals(
            2,
            doc.tracks
                .single()
                .points.size,
        )
    }

    @Test
    fun `case D — parse with repairOnFailure=false surfaces the original failure`() {
        assertFailsWith<IllegalArgumentException> {
            GpxParser.parse(GpxFixtures.BARE_AMPERSAND_GPX, repairOnFailure = false)
        }
    }
}
