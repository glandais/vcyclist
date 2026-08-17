package io.github.glandais.codegen

import java.io.File

/*
 * Codegen for gpx/src/commonMain/kotlin/io/github/glandais/engine/path/
 *   - GeneratedPath.kt        (abstract class with 40 typed accessor pairs + generic get/set)
 *   - PointFieldAccessors.kt  (POINT_FIELD_ACCESSORS list bound to GeneratedPath member refs)
 *
 * Run from vcyclist/ root after editing PointField.kt:
 *
 *     ./gradlew :codegen:run
 *
 * The FIELDS list below MUST mirror PointField.kt (declaration order is part of the file format).
 * The "POINT_FIELD_ACCESSORS contains every PointField" test in commonTest verifies the sync.
 */

private data class FieldSpec(
    val enumName: String,
    val prop: String,
    /** Mirrors `PointField.nanDefault` — flagged slots are NaN-filled at construction. */
    val nanDefault: Boolean = false,
)

// Keep in sync with gpx/src/commonMain/kotlin/io/github/glandais/engine/path/PointField.kt
private val FIELDS =
    listOf(
        FieldSpec("LATITUDE", "latitude"),
        FieldSpec("LONGITUDE", "longitude"),
        FieldSpec("DISTANCE", "distance"),
        FieldSpec("DX", "dx"),
        FieldSpec("TIME", "time"),
        FieldSpec("ELAPSED", "elapsed"),
        FieldSpec("DT", "dt"),
        FieldSpec("BEARING", "bearing"),
        FieldSpec("ELEVATION", "elevation"),
        FieldSpec("GRADE", "grade"),
        FieldSpec("RADIUS", "radius"),
        FieldSpec("AERO_COEF", "aeroCoef"),
        FieldSpec("WIND_BEARING", "windBearing"),
        FieldSpec("WIND_ALPHA", "windAlpha"),
        FieldSpec("P_AERO", "pAero"),
        FieldSpec("P_GRAVITY", "pGravity"),
        FieldSpec("P_ROLLING_RESISTANCE", "pRollingResistance"),
        FieldSpec("P_WHEEL_BEARINGS", "pWheelBearings"),
        FieldSpec("P_INPUT_POWER", "pInputPower"),
        FieldSpec("P_CYCLIST_PROVIDED_OPTIMAL_POWER", "pCyclistProvidedOptimalPower"),
        FieldSpec("P_CYCLIST_PROVIDED_OPTIMAL_POWER_HARMONICS", "pCyclistProvidedOptimalPowerWithHarmonics"),
        FieldSpec("P_CYCLIST_PROVIDED_POWER_NEEDED", "pCyclistPowerNeeded"),
        FieldSpec("P_CYCLIST_PROVIDED_MUSCULAR", "pCyclistProvidedMuscular"),
        FieldSpec("P_CYCLIST_PROVIDED_WHEEL", "pCyclistProvidedWheel"),
        FieldSpec("P_COMPUTED_TOTAL_POWER", "pComputedTotalPower"),
        FieldSpec("P_COMPUTED_WHEEL_POWER", "pComputedWheelPower"),
        FieldSpec("POWER", "pComputedPower"),
        FieldSpec("SPEED", "speed"),
        FieldSpec("SPEED_MAX", "speedMax"),
        FieldSpec("SPEED_MAX_INCLINE", "speedMaxIncline"),
        FieldSpec("VIRT_SPEED_CURRENT", "virtSpeedCurrent"),
        FieldSpec("TEMPERATURE", "temperature"),
        FieldSpec("WIND_SPEED", "windSpeed"),
        FieldSpec("WIND_DIRECTION", "windDirection"),
        FieldSpec("HEART_RATE", "heartRate"),
        FieldSpec("CADENCE", "cadence"),
        FieldSpec("W_PRIME_BALANCE", "wPrimeBalance"),
        FieldSpec("P_BRAKE", "pBrake"),
        FieldSpec("TRAJECTORY_CURVATURE", "trajectoryCurvature", nanDefault = true),
        FieldSpec("ROAD_WIDTH", "roadWidth", nanDefault = true),
    )

private const val EXPECTED_COUNT = 40

fun main() {
    require(FIELDS.size == EXPECTED_COUNT) {
        "FIELDS list has ${FIELDS.size} entries, expected $EXPECTED_COUNT"
    }
    val targetDir = File("gpx/src/commonMain/kotlin/io/github/glandais/engine/path")
    require(targetDir.isDirectory) {
        "Target dir does not exist (run from vcyclist/ root): ${targetDir.absolutePath}"
    }
    File(targetDir, "GeneratedPath.kt").writeText(buildGeneratedPath())
    File(targetDir, "PointFieldAccessors.kt").writeText(buildAccessors())
    println("Wrote ${FIELDS.size * 2 + 2} declarations into ${targetDir.path}")
}

private fun cap(prop: String): String = prop.replaceFirstChar { it.uppercase() }

private fun buildGeneratedPath(): String =
    buildString {
        appendLine("// DO NOT EDIT — regenerate via: ./gradlew :codegen:run")
        appendLine("// Source of truth: PointField.kt")
        appendLine()
        appendLine("package io.github.glandais.engine.path")
        appendLine()
        appendLine("/**")
        appendLine(" * Flat-array storage for [size] points × [PointField.COUNT] double slots each.")
        appendLine(" * Per-field named accessors below + generic [get]/[set] by [PointField].")
        appendLine(" */")
        appendLine("abstract class GeneratedPath(")
        appendLine("    val size: Int,")
        appendLine(") {")
        appendLine("    init {")
        appendLine("        require(size >= 0) { \"Negative size: \$size\" }")
        appendLine("    }")
        appendLine()
        val nanFields = FIELDS.withIndex().filter { it.value.nanDefault }
        appendLine("    /**")
        appendLine("     * Zero-initialised, matching the TS `AbstractPath` backing store")
        appendLine("     * (`new Float64Array(...)`). \"Absent\" is signalled by writing `Double.NaN`")
        appendLine("     * explicitly — see `GpxToPath`, which does so for absent sensor fields.")
        if (nanFields.isNotEmpty()) {
            appendLine("     *")
            appendLine("     * Slots whose [PointField] declares `nanDefault = true` are the exception:")
            appendLine("     * they are NaN-filled below, because their natural zero is a legal value and")
            appendLine("     * would be indistinguishable from \"never written\".")
        }
        appendLine("     */")
        appendLine("    protected val data: DoubleArray = DoubleArray(size * PointField.COUNT)")
        appendLine()
        if (nanFields.isNotEmpty()) {
            appendLine("    init {")
            appendLine("        for (i in 0 until size) {")
            nanFields.forEach { (idx, f) ->
                appendLine("            data[i * PointField.COUNT + $idx] = Double.NaN // ${f.prop}")
            }
            appendLine("        }")
            appendLine("    }")
            appendLine()
        }
        appendLine("    /** Generic read by [field]. */")
        appendLine("    fun get(")
        appendLine("        i: Int,")
        appendLine("        field: PointField,")
        appendLine("    ): Double = data[i * PointField.COUNT + field.ordinal]")
        appendLine()
        appendLine("    /** Generic write by [field]. */")
        appendLine("    fun set(")
        appendLine("        i: Int,")
        appendLine("        field: PointField,")
        appendLine("        v: Double,")
        appendLine("    ) {")
        appendLine("        data[i * PointField.COUNT + field.ordinal] = v")
        appendLine("    }")
        appendLine()
        FIELDS.forEachIndexed { idx, f ->
            appendLine("    fun ${f.prop}(i: Int): Double = data[i * PointField.COUNT + $idx]")
            appendLine()
            appendLine("    fun set${cap(f.prop)}(")
            appendLine("        i: Int,")
            appendLine("        v: Double,")
            appendLine("    ) {")
            appendLine("        data[i * PointField.COUNT + $idx] = v")
            appendLine("    }")
            if (idx < FIELDS.lastIndex) appendLine()
        }
        appendLine("}")
    }

private fun buildAccessors(): String =
    buildString {
        appendLine("// DO NOT EDIT — regenerate via: ./gradlew :codegen:run")
        appendLine()
        appendLine("package io.github.glandais.engine.path")
        appendLine()
        appendLine("/** Bound accessor for a single [PointField], usable from KMP-safe generic code. */")
        appendLine("internal data class PointFieldAccessor(")
        appendLine("    val field: PointField,")
        appendLine("    val getter: (GeneratedPath, Int) -> Double,")
        appendLine("    val setter: (GeneratedPath, Int, Double) -> Unit,")
        appendLine(")")
        appendLine()
        appendLine("internal val POINT_FIELD_ACCESSORS: List<PointFieldAccessor> =")
        appendLine("    listOf(")
        FIELDS.forEach { f ->
            val cap = cap(f.prop)
            appendLine("        PointFieldAccessor(")
            appendLine("            PointField.${f.enumName},")
            appendLine("            GeneratedPath::${f.prop},")
            appendLine("            GeneratedPath::set$cap,")
            appendLine("        ),")
        }
        appendLine("    )")
    }
