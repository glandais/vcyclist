package io.github.glandais.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /defaults`: what this door applies when a field is absent. Every member is filled by an
 * inverse mapper from [io.github.glandais.api.mapping.HttpDefaults], never by hand, and feeding a
 * member back as a request gives the same engine object — the published is the applied.
 *
 * The request-shaped members are declared nullable although they are always filled: that
 * makes them the very `EnhanceOptions`, `Cyclist`, `Bike`, `PowerProvider`, `CsvOptions`,
 * `JsonOptions`, `GpxWriteOptions`, `FitOptions` and `ClimbOptions` schemas of the request in the
 * contract (Ktor would otherwise emit a second, non-null copy of each, named `EnhanceOptions2`…),
 * so a client sends a published member back with the type it read it as.
 *
 * @property gpx `trackName` is the one `/enhance` applies; `/export` keeps the input's names
 * @property fit `startTimeEpochMs` is `null`: it has no default, `format=fit` requires it
 * @property elevation the DEM routes' defaults; the zoom is per source (`GET /elevation/sources`)
 * @property limits the server's own limits, from its configuration
 */
@Serializable
@SerialName("Defaults")
data class DefaultsDto(
    val enhance: EnhanceOptionsDto?,
    val cyclist: CyclistDto?,
    val bike: BikeDto?,
    val power: PowerProviderDto?,
    val csv: CsvOptionsDto?,
    val json: JsonOptionsDto?,
    val gpx: GpxWriteOptionsDto?,
    val fit: FitOptionsDto?,
    val climb: ClimbOptionsDto?,
    val elevation: ElevationDefaultsDto,
    val limits: LimitsDto,
)

/**
 * The server's limits.
 *
 * @property maxBodyBytes larger request bodies are 413 `payload-too-large`
 * @property maxTrackPoints more points in the selected paths is 413 `too-many-points`
 * @property maxElevationPoints more points in one elevation request is 413 `too-many-points`
 * @property requestTimeoutMs a computation that has not answered by then is 503 `timeout`
 * @property computeConcurrency computations run at once; the others wait for the timeout
 */
@Serializable
@SerialName("Limits")
data class LimitsDto(
    val maxBodyBytes: Long,
    val maxTrackPoints: Int,
    val maxElevationPoints: Int,
    val requestTimeoutMs: Long,
    val computeConcurrency: Int,
)
