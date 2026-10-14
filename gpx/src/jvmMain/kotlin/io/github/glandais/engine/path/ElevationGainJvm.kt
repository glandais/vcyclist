@file:JvmName("ElevationGainJvm")

package io.github.glandais.engine.path

/**
 * Java-callable form of [ElevationGain.annotate], so a Java caller writes
 * `ElevationGainJvm.annotate(path)` instead of passing `ElevationGain.INSTANCE` and spelling the
 * default options. Run it **before** `PathSimplifierJvm.simplify`, which carries the two filtered
 * scalars over: measured after it, the Nantes bench reported 0.49 of its true D+.
 */
@JvmOverloads
fun annotate(
    path: Path,
    options: ElevationGainOptions = ElevationGainOptions.DEFAULT,
): ElevationGainResult = ElevationGain.annotate(path, options)
