package io.github.glandais.fit

/**
 * FIT encoder.
 *
 * The granularity of this `expect` is **deliberately coarse**: the Garmin Java SDK
 * (`FileEncoder`, `RecordMesg`, `LapMesg`, …) and the Garmin JavaScript SDK (an `Encoder`
 * consuming plain objects shaped like its `Decoder`'s output) share no abstraction whatsoever.
 * A finer split — one wrapper per FIT message — would force the `Path` → course conversion to
 * be written twice and would double the surface for unit-conversion bugs, which are the
 * dominant failure mode in this format.
 *
 * So the only shared contract is *"a [FitCourse] goes in, a FIT file comes out"*. Everything
 * upstream of it lives in commonMain and is tested once on all four targets.
 *
 * ## Implementations
 *
 * - JVM — `com.garmin:fit`, the official Java SDK (task g08).
 * - JS and Wasm — `@garmin/fitsdk`, the official JavaScript SDK, pinned to the same profile
 *   revision (task g09). The two web `actual`s are structural mirrors of each other and are
 *   held byte-identical by `FitReferenceBytes`.
 */
expect object FitEncoder {
    /** Encode [course] and return the complete FIT file, header and CRC included. */
    fun encode(course: FitCourse): ByteArray
}
