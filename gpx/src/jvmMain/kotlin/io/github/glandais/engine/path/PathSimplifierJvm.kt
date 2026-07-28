@file:JvmName("PathSimplifierJvm")

package io.github.glandais.engine.path

/**
 * Java-callable form of [PathSimplifier] (task g27). See `GpxWriterJvm` for the rationale.
 *
 * This is the case that opened the whole task: `simplify(path, toleranceM)` forced a Java caller
 * to know and repeat `3.0` for `zExaggeration` — duplicating, in their code, a constant the
 * library already owns.
 */
@JvmOverloads
fun simplify(
    path: Path,
    toleranceM: Double,
    zExaggeration: Double = 3.0,
): Path = PathSimplifier.simplify(path, toleranceM, zExaggeration)
