package io.github.glandais.fit

/**
 * FIT global message numbers and the enum wire values this port emits.
 *
 * The Java SDK hides these behind typed classes (`RecordMesg`, `Sport.CYCLING`, …), but the
 * JavaScript SDK addresses messages by number and fields by name, so the JS encoder needs the
 * raw values. Keeping them in commonMain — rather than inline in the `actual` — is what stops
 * the JVM and JS encoders from drifting apart, and [FitMessageNumbersTest] pins them against
 * the FIT profile.
 *
 * Values verified against `@garmin/fitsdk@21.205.0`'s `Profile.MesgNum`.
 */
internal object FitMessageNumbers {
    const val FILE_ID = 0
    const val COURSE = 31
    const val LAP = 19
    const val RECORD = 20
    const val EVENT = 21

    /** `file` enum: a Course file. */
    const val FILE_TYPE_COURSE = 6

    /** `manufacturer` enum: Dynastream, as gpx2web uses. */
    const val MANUFACTURER_DYNASTREAM = 15

    /** `event` enum: TIMER. */
    const val EVENT_TIMER = 0

    /** `event_type` enum: START. */
    const val EVENT_TYPE_START = 0

    /** `event_type` enum: STOP — closes one record run when another follows (task g25). */
    const val EVENT_TYPE_STOP = 1

    /** `event_type` enum: STOP_ALL — closes the last run, and with it the file. */
    const val EVENT_TYPE_STOP_ALL = 4
}
