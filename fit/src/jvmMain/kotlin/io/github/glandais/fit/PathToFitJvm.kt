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

/**
 * The same four, taking the start as **epoch milliseconds**.
 *
 * `kotlin.time.Instant` is a perfectly ordinary class from Java, but constructing one means
 * writing `kotlin.time.Instant.Companion.fromEpochMilliseconds(t)` at the call site — the only
 * place a Java consumer of this library has to name a `kotlin.*` type at all. Since the value it
 * carries here always comes from `path.time(0)`, which is already a `Double` of epoch
 * milliseconds, the round trip through `Instant` buys the caller nothing.
 */
@JvmOverloads
fun toFitCourse(
    path: Path,
    name: String,
    startTimeEpochMs: Long,
    sport: FitSport = FitSport.CYCLING,
): FitCourse = path.toFitCourse(name, startTimeEpochMs.instant(), sport)

@JvmOverloads
fun toFitBytes(
    path: Path,
    name: String,
    startTimeEpochMs: Long,
    sport: FitSport = FitSport.CYCLING,
): ByteArray = path.toFitBytes(name, startTimeEpochMs.instant(), sport)

@JvmOverloads
fun toFitCourse(
    paths: List<Path>,
    name: String,
    startTimeEpochMs: Long,
    sport: FitSport = FitSport.CYCLING,
    interPathGapMs: Long = 0L,
): FitCourse = paths.toFitCourse(name, startTimeEpochMs.instant(), sport, interPathGapMs.gap())

@JvmOverloads
fun toFitBytes(
    paths: List<Path>,
    name: String,
    startTimeEpochMs: Long,
    sport: FitSport = FitSport.CYCLING,
    interPathGapMs: Long = 0L,
): ByteArray = paths.toFitBytes(name, startTimeEpochMs.instant(), sport, interPathGapMs.gap())

private fun Long.gap(): Duration = milliseconds

private fun Long.instant(): Instant = Instant.fromEpochMilliseconds(this)
