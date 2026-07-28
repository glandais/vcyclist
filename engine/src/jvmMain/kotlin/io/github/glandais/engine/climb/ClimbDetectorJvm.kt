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
 */
@JvmOverloads
fun climbOptions(
    minMinClimbElevationM: Double = 10.0,
    maxMinClimbElevationM: Double = 35.0,
    minClimbElevationRatio: Double = 100.0,
    minGradePercent: Double = 3.0,
    maxDiffRealGradeRatio: Double = 1.3,
    booster: Double = 1.3,
    maxAnalysisPoints: Int = 3000,
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
