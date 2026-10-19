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

/**
 * Java-callable form of [PathSimplifier.simplifyWithIndices]: the simplified path and the source
 * indices it kept, without repeating the `3.0` default for `zExaggeration`.
 */
@JvmOverloads
fun simplifyWithIndices(
    path: Path,
    toleranceM: Double,
    zExaggeration: Double = 3.0,
): PathSimplifier.Simplified = PathSimplifier.simplifyWithIndices(path, toleranceM, zExaggeration)
