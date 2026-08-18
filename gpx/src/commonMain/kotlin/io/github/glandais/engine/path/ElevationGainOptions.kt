package io.github.glandais.engine.path

/**
 * Named (threshold, smoothing) pairs for [ElevationGain].
 *
 * Cumulative ascent is not a property of a route. It is a property of a route *and* a measurement
 * scale, the way coastline length is — see [`docs/guides/elevation.md`](../../../../../../../docs/guides/elevation.md)
 * for the measurements. A preset is therefore a *complete* answer to "at what scale", never a
 * threshold on its own.
 *
 * The two knobs are not independent filters. Measured on `strava.gpx`, the dead band alone takes
 * D+ from 1066 m to 661 m and the smoothing alone takes it to 632 m; applied together, nothing
 * changes further. They attack the same noise, and the smoothing dominates.
 */
enum class ElevationGainPreset(
    /** Hysteresis dead band in meters. `0` disables it. */
    val thresholdM: Double,
    /** Triangular-kernel half-width in meters, applied to a private copy of the profile. `0` disables it. */
    val smoothWindowM: Double,
) {
    /** No dead band, no smoothing — reproduces [Path.elevationGain] exactly. The control. */
    RAW(0.0, 0.0),

    /** Strava's threshold for a device with a barometric altimeter. */
    BAROMETRIC(2.0, 15.0),

    /**
     * vcyclist's own, and the default.
     *
     * Elevation here is always DEM-derived — never barometric, never a GPS altimeter — and DEM
     * error is *spatially correlated* rather than white: consecutive points inside one ~13.5 m cell
     * interpolate the same four posts, so there is almost no point-to-point jitter for a 2 m band
     * to remove. Strava's 10 m is sized for GPS-altimeter white noise we do not have, and it is
     * destructive on gentle terrain (`garmin.gpx`, 6 m of genuine undulation over 3.9 km, reports
     * **0 m** at 10 m). But DEM error is not zero either — ~1–3 m vertical RMSE plus a
     * lateral-offset-times-slope term, which is what inflates D+ in mountains.
     *
     * 3.0 m sits between the two and matches GoldenCheetah's shipped default, the only independent
     * prior-art value derived from corrected rather than device elevation. **It is a defensible
     * starting point, not a measured one** — see ledger row R27.
     */
    DEM(3.0, 30.0),

    /** Strava's threshold for a GPS-only trace: 10 m of consistent climbing. */
    GPS(10.0, 50.0),
    ;

    /** The spelling used on the CLI, in JSON options, and in the WASI ABI. */
    val id: String
        get() =
            when (this) {
                RAW -> "raw"
                BAROMETRIC -> "barometric"
                DEM -> "dem"
                GPS -> "gps"
            }

    companion object {
        /**
         * Parse a preset from its [id].
         *
         * Lives here rather than in each façade so the CLI, the JS DTO and the WASI ABI cannot
         * drift apart on what `"dem"` means — and so adding a preset breaks the `when` above in
         * `commonMain`, hence all three targets, before any test runs. Enum names are accepted too,
         * so `DEM` works wherever `dem` does.
         */
        fun byId(name: String): ElevationGainPreset =
            entries.firstOrNull { it.id.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown elevation gain preset '$name': expected one of ${entries.joinToString(", ") { it.id }}",
                )
    }
}

/**
 * How [ElevationGain] should measure cumulative ascent.
 *
 * [thresholdM] and [smoothWindowM] default to the [preset]'s values; setting either explicitly
 * overrides that half of the preset, which is what a `--elevation-gain-threshold` flag on top of a
 * `--elevation-gain-preset` has to mean.
 */
data class ElevationGainOptions(
    val enabled: Boolean = true,
    val preset: ElevationGainPreset = ElevationGainPreset.DEM,
    val thresholdM: Double = preset.thresholdM,
    val smoothWindowM: Double = preset.smoothWindowM,
) {
    init {
        require(thresholdM >= 0.0 && thresholdM.isFinite()) {
            "thresholdM must be finite and >= 0, was $thresholdM"
        }
        require(smoothWindowM >= 0.0 && smoothWindowM.isFinite()) {
            "smoothWindowM must be finite and >= 0, was $smoothWindowM"
        }
    }

    companion object {
        val DEFAULT: ElevationGainOptions = ElevationGainOptions()

        /** Reproduces [Path.elevationGain]: no dead band, no smoothing. */
        val RAW: ElevationGainOptions = ElevationGainOptions(preset = ElevationGainPreset.RAW)

        fun of(preset: ElevationGainPreset): ElevationGainOptions = ElevationGainOptions(preset = preset)
    }
}
