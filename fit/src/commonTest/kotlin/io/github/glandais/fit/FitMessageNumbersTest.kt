package io.github.glandais.fit

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the global message numbers and enum wire values against the FIT profile.
 *
 * The Java SDK hides these behind typed constants, so only the JS and Wasm encoders read them —
 * which means a wrong value here would produce a file that encodes happily and then means
 * something else entirely (a `sport` of 1 is running, not cycling). Values were read out of
 * `@garmin/fitsdk@21.205.0`'s `Profile.MesgNum` and `Profile.types`.
 */
class FitMessageNumbersTest {
    @Test
    fun `global message numbers match the FIT profile`() {
        assertEquals(0, FitMessageNumbers.FILE_ID)
        assertEquals(19, FitMessageNumbers.LAP)
        assertEquals(20, FitMessageNumbers.RECORD)
        assertEquals(21, FitMessageNumbers.EVENT)
        assertEquals(31, FitMessageNumbers.COURSE)
    }

    @Test
    fun `enum wire values match the FIT profile`() {
        assertEquals(6, FitMessageNumbers.FILE_TYPE_COURSE, "file.course")
        assertEquals(15, FitMessageNumbers.MANUFACTURER_DYNASTREAM, "manufacturer.dynastream")
        assertEquals(0, FitMessageNumbers.EVENT_TIMER, "event.timer")
        assertEquals(0, FitMessageNumbers.EVENT_TYPE_START, "eventType.start")
        assertEquals(4, FitMessageNumbers.EVENT_TYPE_STOP_ALL, "eventType.stopAll")
    }
}
