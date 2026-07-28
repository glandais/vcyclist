package io.github.glandais.fit

/**
 * wasmWasi `actual` — **not implemented**, on purpose.
 *
 * Both existing implementations delegate to an official Garmin SDK (`com.garmin:fit` on the JVM,
 * `@garmin/fitsdk` on JS), and neither can run here: the Java SDK is JVM bytecode, the JavaScript
 * one needs a JS host that WASI does not provide. Writing a FIT file from this target therefore
 * requires a pure-Kotlin encoder, which is exactly what task w12 is about.
 *
 * Until then this throws rather than returning an empty [ByteArray]: everything upstream — the
 * `Path` → [FitCourse] conversion — is `commonMain` and keeps working under WASI, so a silent
 * empty result would look like a successful export and produce a file no device can read. The
 * failure is loud and names its way out.
 */
actual object FitEncoder {
    actual fun encode(course: FitCourse): ByteArray =
        throw UnsupportedOperationException(
            "FitEncoder.encode is not available on wasmWasi: both actuals wrap an official Garmin SDK " +
                "(com.garmin:fit on JVM, @garmin/fitsdk on JS) and neither runs under WASI. " +
                "A pure-Kotlin encoder is tracked as task w12. Course was '${course.name}'.",
        )
}
