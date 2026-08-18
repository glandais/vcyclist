@file:JvmName("ClimbDetectorJvm")

package io.github.glandais.engine.climb

import io.github.glandais.engine.path.Path

/**
 * Java-callable form of [ClimbDetector] and its options (task g27). See `GpxWriterJvm` for why
 * these facades exist.
 */
@JvmOverloads
fun detect(
    path: Path,
    options: ClimbOptions = ClimbOptions.DEFAULT,
): List<Climb> = ClimbDetector.detect(path, options)

/**
 * Every field of [ClimbOptions], in declaration order. Listing them all matters here: a factory
 * that covered only the first four would quietly pin `booster` and `maxAnalysisPoints` to their
 * defaults while looking like it configured the detector.
 *
 * The defaults are read off [ClimbOptions.DEFAULT], never restated. They were literals here until
 * S6 of the surface-alignment work, which is the drift class that once had the façades defending
 * 250 W against the CLI's 280 W — and `DoorDefaultsTest` now fails a reader that spells one.
 */
@JvmOverloads
fun climbOptions(
    minMinClimbElevationM: Double = ClimbOptions.DEFAULT.minMinClimbElevationM,
    maxMinClimbElevationM: Double = ClimbOptions.DEFAULT.maxMinClimbElevationM,
    minClimbElevationRatio: Double = ClimbOptions.DEFAULT.minClimbElevationRatio,
    minGradePercent: Double = ClimbOptions.DEFAULT.minGradePercent,
    maxDiffRealGradeRatio: Double = ClimbOptions.DEFAULT.maxDiffRealGradeRatio,
    booster: Double = ClimbOptions.DEFAULT.booster,
    maxAnalysisPoints: Int = ClimbOptions.DEFAULT.maxAnalysisPoints,
): ClimbOptions =
    ClimbOptions(
        minMinClimbElevationM,
        maxMinClimbElevationM,
        minClimbElevationRatio,
        minGradePercent,
        maxDiffRealGradeRatio,
        booster,
        maxAnalysisPoints,
    )
