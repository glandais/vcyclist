package io.github.glandais.api.mapping

import io.github.glandais.api.dto.BoundsDto
import io.github.glandais.api.dto.PathSummaryDto
import io.github.glandais.api.dto.PointDto
import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path

/** `this`, or `null` when it is `NaN` or infinite: kotlinx refuses `NaN`, the WASI ABI writes `null`. */
fun Double.finiteOrNull(): Double? = takeIf { it.isFinite() }

/** The whole-track statistics of this path; bounds in degrees, `null` when empty. */
fun Path.toSummary(): PathSummaryDto =
    PathSummaryDto(
        size = size,
        totalDistance = totalDistance,
        durationMs = durationMs.finiteOrNull(),
        minElevation = if (size == 0) null else minElevation.finiteOrNull(),
        maxElevation = if (size == 0) null else maxElevation.finiteOrNull(),
        elevationGain = elevationGain,
        elevationLoss = elevationLoss,
        elevationGainFiltered = elevationGainFiltered.finiteOrNull(),
        elevationLossFiltered = elevationLossFiltered.finiteOrNull(),
        reportedElevationGain = reportedElevationGain,
        reportedElevationLoss = reportedElevationLoss,
        bounds =
            if (size == 0) {
                null
            } else {
                BoundsDto(
                    minLatitude = boundsRad.minLat * MathConstants.RAD_TO_DEG,
                    minLongitude = boundsRad.minLon * MathConstants.RAD_TO_DEG,
                    maxLatitude = boundsRad.maxLat * MathConstants.RAD_TO_DEG,
                    maxLongitude = boundsRad.maxLon * MathConstants.RAD_TO_DEG,
                )
            },
    )

/** Every point, in the shape of the JS / WASI `PointDto`. */
fun Path.toPoints(): List<PointDto> =
    indices.map { i ->
        PointDto(
            latitudeDeg = latitudeDeg(i).finiteOrNull(),
            longitudeDeg = longitudeDeg(i).finiteOrNull(),
            elevation = elevation(i).finiteOrNull(),
            timeMs = time(i).finiteOrNull(),
            speed = speed(i).finiteOrNull(),
            pComputedPower = pComputedPower(i).finiteOrNull(),
            distance = distance(i).finiteOrNull(),
            grade = grade(i).finiteOrNull(),
        )
    }
