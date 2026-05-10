package io.github.glandais.elevation

import kotlin.math.hypot

data class Vector3D(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    fun distanceTo(other: Vector3D): Double = hypot(hypot(x - other.x, y - other.y), z - other.z)

    operator fun minus(other: Vector3D): Vector3D = Vector3D(x - other.x, y - other.y, z - other.z)

    operator fun plus(other: Vector3D): Vector3D = Vector3D(x + other.x, y + other.y, z + other.z)

    operator fun times(scalar: Double): Vector3D = Vector3D(x * scalar, y * scalar, z * scalar)

    fun dot(other: Vector3D): Double = x * other.x + y * other.y + z * other.z

    fun cross(other: Vector3D): Vector3D =
        Vector3D(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x,
        )

    fun magnitude(): Double = hypot(hypot(x, y), z)

    fun normalize(): Vector3D {
        val mag = magnitude()
        if (mag == 0.0) return ZERO
        return this * (1.0 / mag)
    }

    fun distanceToSegment(
        segmentStart: Vector3D,
        segmentEnd: Vector3D,
    ): Double {
        val segmentVector = segmentEnd - segmentStart
        val segmentLengthSq = segmentVector.dot(segmentVector)

        if (segmentLengthSq == 0.0) return distanceTo(segmentStart)

        val pointVector = this - segmentStart
        val projection = pointVector.dot(segmentVector) / segmentLengthSq
        val clamped = projection.coerceIn(0.0, 1.0)
        val closest = segmentStart + segmentVector * clamped
        return distanceTo(closest)
    }

    companion object {
        val ZERO: Vector3D = Vector3D(0.0, 0.0, 0.0)
    }
}
