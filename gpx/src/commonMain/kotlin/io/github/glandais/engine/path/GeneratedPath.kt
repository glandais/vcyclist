// DO NOT EDIT — regenerate via: ./gradlew :codegen:run
// Source of truth: PointField.kt

package io.github.glandais.engine.path

/**
 * Flat-array storage for [size] points × [PointField.COUNT] double slots each.
 * Per-field named accessors below + generic [get]/[set] by [PointField].
 */
abstract class GeneratedPath(
    val size: Int,
) {
    init {
        require(size >= 0) { "Negative size: $size" }
    }

    /**
     * Zero-initialised, matching the TS `AbstractPath` backing store
     * (`new Float64Array(...)`). "Absent" is signalled by writing `Double.NaN`
     * explicitly — see `GpxToPath`, which does so for absent sensor fields.
     *
     * Slots whose [PointField] declares `nanDefault = true` are the exception:
     * they are NaN-filled below, because their natural zero is a legal value and
     * would be indistinguishable from "never written".
     */
    protected val data: DoubleArray = DoubleArray(size * PointField.COUNT)

    init {
        for (i in 0 until size) {
            data[i * PointField.COUNT + 38] = Double.NaN // trajectoryCurvature
        }
    }

    /** Generic read by [field]. */
    fun get(
        i: Int,
        field: PointField,
    ): Double = data[i * PointField.COUNT + field.ordinal]

    /** Generic write by [field]. */
    fun set(
        i: Int,
        field: PointField,
        v: Double,
    ) {
        data[i * PointField.COUNT + field.ordinal] = v
    }

    fun latitude(i: Int): Double = data[i * PointField.COUNT + 0]

    fun setLatitude(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 0] = v
    }

    fun longitude(i: Int): Double = data[i * PointField.COUNT + 1]

    fun setLongitude(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 1] = v
    }

    fun distance(i: Int): Double = data[i * PointField.COUNT + 2]

    fun setDistance(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 2] = v
    }

    fun dx(i: Int): Double = data[i * PointField.COUNT + 3]

    fun setDx(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 3] = v
    }

    fun time(i: Int): Double = data[i * PointField.COUNT + 4]

    fun setTime(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 4] = v
    }

    fun elapsed(i: Int): Double = data[i * PointField.COUNT + 5]

    fun setElapsed(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 5] = v
    }

    fun dt(i: Int): Double = data[i * PointField.COUNT + 6]

    fun setDt(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 6] = v
    }

    fun bearing(i: Int): Double = data[i * PointField.COUNT + 7]

    fun setBearing(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 7] = v
    }

    fun elevation(i: Int): Double = data[i * PointField.COUNT + 8]

    fun setElevation(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 8] = v
    }

    fun grade(i: Int): Double = data[i * PointField.COUNT + 9]

    fun setGrade(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 9] = v
    }

    fun radius(i: Int): Double = data[i * PointField.COUNT + 10]

    fun setRadius(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 10] = v
    }

    fun aeroCoef(i: Int): Double = data[i * PointField.COUNT + 11]

    fun setAeroCoef(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 11] = v
    }

    fun windBearing(i: Int): Double = data[i * PointField.COUNT + 12]

    fun setWindBearing(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 12] = v
    }

    fun windAlpha(i: Int): Double = data[i * PointField.COUNT + 13]

    fun setWindAlpha(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 13] = v
    }

    fun pAero(i: Int): Double = data[i * PointField.COUNT + 14]

    fun setPAero(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 14] = v
    }

    fun pGravity(i: Int): Double = data[i * PointField.COUNT + 15]

    fun setPGravity(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 15] = v
    }

    fun pRollingResistance(i: Int): Double = data[i * PointField.COUNT + 16]

    fun setPRollingResistance(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 16] = v
    }

    fun pWheelBearings(i: Int): Double = data[i * PointField.COUNT + 17]

    fun setPWheelBearings(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 17] = v
    }

    fun pInputPower(i: Int): Double = data[i * PointField.COUNT + 18]

    fun setPInputPower(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 18] = v
    }

    fun pCyclistProvidedOptimalPower(i: Int): Double = data[i * PointField.COUNT + 19]

    fun setPCyclistProvidedOptimalPower(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 19] = v
    }

    fun pCyclistProvidedOptimalPowerWithHarmonics(i: Int): Double = data[i * PointField.COUNT + 20]

    fun setPCyclistProvidedOptimalPowerWithHarmonics(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 20] = v
    }

    fun pCyclistPowerNeeded(i: Int): Double = data[i * PointField.COUNT + 21]

    fun setPCyclistPowerNeeded(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 21] = v
    }

    fun pCyclistProvidedMuscular(i: Int): Double = data[i * PointField.COUNT + 22]

    fun setPCyclistProvidedMuscular(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 22] = v
    }

    fun pCyclistProvidedWheel(i: Int): Double = data[i * PointField.COUNT + 23]

    fun setPCyclistProvidedWheel(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 23] = v
    }

    fun pComputedTotalPower(i: Int): Double = data[i * PointField.COUNT + 24]

    fun setPComputedTotalPower(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 24] = v
    }

    fun pComputedWheelPower(i: Int): Double = data[i * PointField.COUNT + 25]

    fun setPComputedWheelPower(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 25] = v
    }

    fun pComputedPower(i: Int): Double = data[i * PointField.COUNT + 26]

    fun setPComputedPower(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 26] = v
    }

    fun speed(i: Int): Double = data[i * PointField.COUNT + 27]

    fun setSpeed(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 27] = v
    }

    fun speedMax(i: Int): Double = data[i * PointField.COUNT + 28]

    fun setSpeedMax(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 28] = v
    }

    fun speedMaxIncline(i: Int): Double = data[i * PointField.COUNT + 29]

    fun setSpeedMaxIncline(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 29] = v
    }

    fun virtSpeedCurrent(i: Int): Double = data[i * PointField.COUNT + 30]

    fun setVirtSpeedCurrent(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 30] = v
    }

    fun temperature(i: Int): Double = data[i * PointField.COUNT + 31]

    fun setTemperature(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 31] = v
    }

    fun windSpeed(i: Int): Double = data[i * PointField.COUNT + 32]

    fun setWindSpeed(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 32] = v
    }

    fun windDirection(i: Int): Double = data[i * PointField.COUNT + 33]

    fun setWindDirection(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 33] = v
    }

    fun heartRate(i: Int): Double = data[i * PointField.COUNT + 34]

    fun setHeartRate(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 34] = v
    }

    fun cadence(i: Int): Double = data[i * PointField.COUNT + 35]

    fun setCadence(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 35] = v
    }

    fun wPrimeBalance(i: Int): Double = data[i * PointField.COUNT + 36]

    fun setWPrimeBalance(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 36] = v
    }

    fun pBrake(i: Int): Double = data[i * PointField.COUNT + 37]

    fun setPBrake(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 37] = v
    }

    fun trajectoryCurvature(i: Int): Double = data[i * PointField.COUNT + 38]

    fun setTrajectoryCurvature(
        i: Int,
        v: Double,
    ) {
        data[i * PointField.COUNT + 38] = v
    }
}
