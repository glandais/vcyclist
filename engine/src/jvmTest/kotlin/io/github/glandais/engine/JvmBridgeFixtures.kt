package io.github.glandais.engine

import io.github.glandais.engine.path.Path

/**
 * What the Java pin tests cannot express themselves.
 *
 * A Java test cannot call a Kotlin constructor that relies on default arguments — which is the
 * very gap the `*Jvm.kt` facades exist to close, so a test asserting "the factory agrees with the
 * data class" has to get the data-class side from Kotlin. Keeping that here rather than widening
 * the facade avoids adding public API whose only caller is a test.
 */
object JvmBridgeFixtures {
    /** `CoursePhysics(course)` with every provider left at its default. */
    @JvmStatic
    fun defaultCoursePhysics(course: Course): CoursePhysics = CoursePhysics(course)

    /** `Course(path)` with the default cyclist and bike. */
    @JvmStatic
    fun defaultCourse(path: Path): Course = Course(path)
}
