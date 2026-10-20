package io.github.glandais.fit

import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The single [FitCourse] every target encodes in its tests.
 *
 * Having one definition shared by the JVM and JS suites is what makes the cross-target
 * comparison meaningful: if each target built its own course, "the bytes differ" would never
 * distinguish an encoder bug from a fixture difference.
 *
 * Values come from `sample.gpx`'s first trackpoints so they are recognisable across the repo.
 */
object FitReferenceCourse {
    const val NAME: String = "Col de la Madeleine"

    val START: Instant = Instant.parse("2026-07-28T08:00:00Z")

    fun build(): FitCourse {
        val records =
            listOf(
                FitRecord(
                    timestamp = START,
                    latitudeDeg = 45.680697,
                    longitudeDeg = 6.396115,
                    altitudeM = 350.1,
                    distanceM = 0.0,
                    speedMs = 0.0,
                    powerW = 45,
                ),
                FitRecord(
                    timestamp = START + 10.seconds,
                    latitudeDeg = 45.681335,
                    longitudeDeg = 6.396195,
                    altitudeM = 349.7,
                    distanceM = 71.5,
                    speedMs = 7.15,
                    powerW = 260,
                ),
                FitRecord(
                    timestamp = START + 20.seconds,
                    latitudeDeg = 45.681565,
                    longitudeDeg = 6.396291,
                    altitudeM = 349.5,
                    distanceM = 143.0,
                    speedMs = 7.15,
                    powerW = 255,
                ),
            )
        return FitCourse(
            name = NAME,
            startTime = START,
            records = records,
            lap =
                FitLap(
                    startTime = START,
                    totalElapsedTimeS = 20.0,
                    totalTimerTimeS = 20.0,
                    totalDistanceM = 143.0,
                    totalAscentM = 0,
                    totalDescentM = 1,
                ),
        )
    }

    /**
     * [build] plus two course points: `SUMMIT` "Sommet" on record 1 and an unnamed `WATER` on
     * record 2 — the second has a different layout (no `name`), so the bytes pin both.
     */
    fun buildWithCoursePoints(): FitCourse {
        val course = build()
        val (_, r1, r2) = course.records
        return course.copy(
            coursePoints =
                listOf(
                    FitCoursePoint(r1.timestamp, r1.latitudeDeg, r1.longitudeDeg, r1.distanceM, FitCoursePointType.SUMMIT, "Sommet"),
                    FitCoursePoint(r2.timestamp, r2.latitudeDeg, r2.longitudeDeg, r2.distanceM, FitCoursePointType.WATER),
                ),
        )
    }
}
