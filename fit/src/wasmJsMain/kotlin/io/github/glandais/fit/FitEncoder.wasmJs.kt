package io.github.glandais.fit

/**
 * Placeholder Kotlin/Wasm `actual`. See the Kotlin/JS twin — the real `@garmin/fitsdk` binding
 * for both web targets is task g09.
 */
actual object FitEncoder {
    actual fun encode(course: FitCourse): ByteArray = throw NotImplementedError(NOT_IMPLEMENTED_MESSAGE)
}
