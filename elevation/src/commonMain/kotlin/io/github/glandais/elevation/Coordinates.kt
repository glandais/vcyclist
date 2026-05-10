package io.github.glandais.elevation

interface Coordinates {
    val latitude: Double
    val longitude: Double
    val elevation: Double?
}

interface CoordinatesElevation : Coordinates {
    override val elevation: Double
}

data class LatLon(
    override val latitude: Double,
    override val longitude: Double,
    override val elevation: Double? = null,
) : Coordinates

data class LatLonElevation(
    override val latitude: Double,
    override val longitude: Double,
    override val elevation: Double,
) : CoordinatesElevation

fun Coordinates.toCoordinatesElevation(): CoordinatesElevation = LatLonElevation(latitude, longitude, elevation ?: 0.0)
