@file:JvmName("PathJvm")

package io.github.glandais.engine.path

import io.github.glandais.elevation.CoordinatesElevation

/**
 * Java-callable form of [Path.Companion] (task g33). See `GpxWriterJvm` for why these facades
 * exist.
 *
 * `Path.Companion.fromCoordinates(coords)` already works from Java, and unlike `PathWindKt` that
 * name is stable — `Companion` is API, not a compiler artefact. It is simply the noisiest call in
 * the library from where a Java consumer sits, and the one every such consumer makes, since this
 * is the only way to build a [Path] from outside. `@JvmStatic` on the companion function would say
 * exactly this, but it does not resolve from `commonMain`.
 */
fun fromCoordinates(coords: List<CoordinatesElevation>): Path = Path.fromCoordinates(coords)
