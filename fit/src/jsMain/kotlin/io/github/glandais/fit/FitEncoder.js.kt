package io.github.glandais.fit

/**
 * Placeholder Kotlin/JS `actual`. The real implementation, backed by `@garmin/fitsdk`, lands in
 * task g09.
 *
 * It exists so that `:fit` compiles and `./gradlew check` stays multi-target between g08 and
 * g09 — an `expect` with no JS `actual` would break the whole build, and dropping the JS target
 * from the module would have to be undone a task later.
 */
actual object FitEncoder {
    actual fun encode(course: FitCourse): ByteArray = throw NotImplementedError(NOT_IMPLEMENTED_MESSAGE)
}
