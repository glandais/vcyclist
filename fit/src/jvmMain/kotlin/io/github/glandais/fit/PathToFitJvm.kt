@file:JvmName("PathToFitJvm")

package io.github.glandais.fit

import io.github.glandais.engine.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Java-callable form of the `Path` → FIT conversions (task g27). See `GpxWriterJvm` for why these
 * facades exist rather than `@JvmOverloads` on the common declarations.
 *
 * `interPathGap` is exposed in **milliseconds** rather than as a `kotlin.time.Duration`: the
 * latter is a value class, which Java sees as a raw `long` of unspecified unit — passing the wrong
 * one compiles and silently shifts every timestamp after the first path.
 */
@JvmOverloads
fun toFitCourse(
    path: Path,
    name: String,
    startTime: Instant,
    sport: FitSport = FitSport.CYCLING,
): FitCourse = path.toFitCourse(name, startTime, sport)

@JvmOverloads
fun toFitBytes(
    path: Path,
    name: String,
    startTime: Instant,
    sport: FitSport = FitSport.CYCLING,
): ByteArray = path.toFitBytes(name, startTime, sport)

@JvmOverloads
fun toFitCourse(
    paths: List<Path>,
    name: String,
    startTime: Instant,
    sport: FitSport = FitSport.CYCLING,
    interPathGapMs: Long = 0L,
): FitCourse = paths.toFitCourse(name, startTime, sport, interPathGapMs.gap())

@JvmOverloads
fun toFitBytes(
    paths: List<Path>,
    name: String,
    startTime: Instant,
    sport: FitSport = FitSport.CYCLING,
    interPathGapMs: Long = 0L,
): ByteArray = paths.toFitBytes(name, startTime, sport, interPathGapMs.gap())

private fun Long.gap(): Duration = milliseconds
