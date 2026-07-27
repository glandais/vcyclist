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
 * ## Availability
 *
 * Only the JVM implementation exists as of task g08. The JS and Wasm `actual`s are placeholders
 * that throw [NotImplementedError] until task g09 wires up `@garmin/fitsdk` — this keeps
 * `./gradlew check` green and multi-target between the two tasks instead of leaving the module
 * uncompilable.
 */
expect object FitEncoder {
    /**
     * Encode [course] and return the complete FIT file, header and CRC included.
     *
     * @throws NotImplementedError on JS and Wasm until task g09.
     */
    fun encode(course: FitCourse): ByteArray
}
