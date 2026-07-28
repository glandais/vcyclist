package io.github.glandais.cli.mixin

import io.github.glandais.engine.physics.Wind
import io.github.glandais.engine.physics.WindProvider
import io.github.glandais.engine.physics.WindProviderConstant
import io.github.glandais.engine.physics.WindProviderNone
import picocli.CommandLine
import kotlin.math.PI

/**
 * Constant wind, off by default.
 *
 * [directionDeg] follows the meteorological convention the rest of the project uses: 0 = wind
 * coming from the north, increasing clockwise. The engine works in radians, so the conversion
 * happens here, at the boundary.
 */
class WindMixin {
    @field:CommandLine.Option(
        names = ["--wind-speed"],
        description = ["Wind speed in m/s. Omit for no wind."],
    )
    var speedMS: Double? = null

    @field:CommandLine.Option(
        names = ["--wind-direction"],
        description = ["Wind direction in degrees, 0 = from the north, clockwise (default: \${DEFAULT-VALUE})"],
    )
    var directionDeg: Double = 0.0

    /**
     * [WindProviderNone] unless a speed was given — absent wind must mean *no* wind, not a zero
     * vector that still costs an aerodynamic computation.
     */
    fun toWindProvider(): WindProvider {
        val speed = speedMS ?: return WindProviderNone
        return WindProviderConstant(Wind(speedMS = speed, directionRad = directionDeg * PI / 180.0))
    }
}
