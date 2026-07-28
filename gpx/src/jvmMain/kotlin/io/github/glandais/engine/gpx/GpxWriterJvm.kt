@file:JvmName("GpxWriterJvm")

package io.github.glandais.engine.gpx

import io.github.glandais.engine.path.Path
import kotlin.time.Instant

/**
 * Java-callable form of [GpxWriter] (task g27).
 *
 * ## Why this file exists
 *
 * Kotlin default arguments are a compile-time feature of the *Kotlin* compiler: from Java every
 * parameter is mandatory and positional. `@JvmOverloads` normally fixes that by generating the
 * overload ladder — but `kotlin.jvm.JvmOverloads` **cannot be resolved from a common source set**
 * (Kotlin 2.3.21, verified in task g23: `Unresolved reference 'JvmOverloads'`), and every writer
 * entry point lives in `commonMain`. So the ladder is generated here instead, on a JVM-only
 * delegate.
 *
 * The indirection buys one thing beyond brevity: adding a defaulted parameter to the common
 * function no longer breaks Java callers, as long as this facade keeps its own signature. That is
 * exactly what happened in g23, when `writeExtensions` landed and a Java test stopped compiling.
 *
 * Kotlin callers should keep using [GpxWriter] directly; nothing here adds behaviour.
 */
@JvmOverloads
fun write(
    document: GpxDocument,
    writeExtensions: Boolean = true,
): String = GpxWriter.write(document, writeExtensions)

@JvmOverloads
fun write(
    path: Path,
    name: String = "noname",
    trackName: String? = null,
    startTime: Instant? = null,
    writeExtensions: Boolean = true,
): String = GpxWriter.write(path, name, trackName, startTime, writeExtensions)

@JvmOverloads
fun write(
    paths: List<Path>,
    name: String = "noname",
    trackNames: List<String>? = null,
    waypoints: List<GpxWaypoint> = emptyList(),
    startTime: Instant? = null,
    writeExtensions: Boolean = true,
): String = GpxWriter.write(paths, name, trackNames, waypoints, startTime, writeExtensions)
