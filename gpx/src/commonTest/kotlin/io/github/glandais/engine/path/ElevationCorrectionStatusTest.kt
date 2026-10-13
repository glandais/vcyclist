package io.github.glandais.engine.path

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ElevationCorrectionStatusTest {
    @Test
    fun ids_are_unique_and_listed_in_order() {
        val ids = ElevationCorrectionStatus.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(ids, ElevationCorrectionStatus.ids)
        assertEquals(6, ids.size)
    }

    @Test
    fun byId_round_trips() {
        for (s in ElevationCorrectionStatus.entries) {
            assertEquals(s, ElevationCorrectionStatus.byId(s.id))
        }
        assertEquals(ElevationCorrectionStatus.FETCH_FAILED, ElevationCorrectionStatus.byId("fetch-failed"))
    }

    @Test
    fun byId_rejects_an_unknown_id_with_the_known_ones() {
        val e = assertFailsWith<IllegalArgumentException> { ElevationCorrectionStatus.byId("APPLIED") }
        assertTrue(e.message!!.contains("not-run"), e.message)
    }
}
