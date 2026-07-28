@file:JvmName("PathWindJvm")

package io.github.glandais.engine.path

import io.github.glandais.elevation.Vector3D

/**
 * Java-callable form of [dominantHeadwindDirection] and [dominantHeadwindAzimuthDeg] (task g27).
 * See `GpxWriterJvm` for why these facades exist.
 *
 * These four are extension functions with no default argument, so Java *can* already reach them —
 * as `PathWindKt.dominantHeadwindDirection(paths)`. That name is the compiler's, not the API's:
 * it is derived from the file name, so renaming `PathWind.kt` upstream would break every Java
 * caller at link time, with nothing at the call site hinting that the name was ever fragile.
 * The facade is the stable name.
 *
 * Mind the two different "no answer" conventions, which the common declarations explain in full:
 * the direction returns `null`, the azimuth returns `NaN` — so a Java caller must test the second
 * with [java.lang.Double.isNaN], never `== 0.0`, since `0.0` means due north.
 */
fun dominantHeadwindDirection(path: Path): Vector3D? = path.dominantHeadwindDirection()

fun dominantHeadwindDirection(paths: List<Path>): Vector3D? = paths.dominantHeadwindDirection()

fun dominantHeadwindAzimuthDeg(path: Path): Double = path.dominantHeadwindAzimuthDeg()

fun dominantHeadwindAzimuthDeg(paths: List<Path>): Double = paths.dominantHeadwindAzimuthDeg()
