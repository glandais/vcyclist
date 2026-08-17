package io.github.glandais.engine.path

/**
 * Canonical enumeration of every numeric slot stored per `Path` point.
 *
 * The ordinal of each entry is the field's index into the underlying `DoubleArray`
 * (laid out in task 11). The order **must not** change: it is part of the file format
 * (GPX extensions) and the wire format (future JS DTOs).
 *
 * @property prop camelCase property name used in JSON serialization and code generation
 * @property unit physical unit (free-form string, matches TS `unit` field)
 * @property shortDescription one-line human-readable label
 * @property category logical group (for UI / docs)
 * @property notSelectable hidden from generic per-field selection UI (e.g. latitude/time)
 * @property anglesInRadians true if the value is stored in radians and exposing a degrees getter
 *   is recommended (deferred to task 12)
 * @property nanDefault true if a freshly allocated `Path` must read `Double.NaN` for this slot
 *   rather than `0.0`. Use it for any field whose *absence* is meaningful and whose natural
 *   zero would be misread as a value — a `0.0` curvature, for instance, is a straight line, not
 *   "not computed". Readers of such a field gate on `isNaN()`; without the flag every such
 *   sentinel is dead on arrival. Costs one write per point per flagged field at construction.
 */
enum class PointField(
    val prop: String,
    val unit: String,
    val shortDescription: String,
    val category: PointFieldCategory,
    val notSelectable: Boolean = false,
    val anglesInRadians: Boolean = false,
    val nanDefault: Boolean = false,
) {
    // --- Coordinates ---------------------------------------------------------
    LATITUDE(
        "latitude",
        "radians",
        "Latitude (radians)",
        PointFieldCategory.COORDINATES,
        notSelectable = true,
        anglesInRadians = true,
    ),
    LONGITUDE(
        "longitude",
        "radians",
        "Longitude (radians)",
        PointFieldCategory.COORDINATES,
        notSelectable = true,
        anglesInRadians = true,
    ),
    DISTANCE("distance", "meters", "Distance (meters)", PointFieldCategory.COORDINATES),
    DX("dx", "meters", "dx (meters)", PointFieldCategory.COORDINATES),

    // --- Temporal ------------------------------------------------------------
    TIME(
        "time",
        "ms",
        "Timestamp (ms since epoch)",
        PointFieldCategory.TEMPORAL,
        notSelectable = true,
    ),
    ELAPSED("elapsed", "ms", "Elapsed duration (ms)", PointFieldCategory.TEMPORAL),
    DT("dt", "ms", "dt (ms)", PointFieldCategory.TEMPORAL),

    // --- Angles --------------------------------------------------------------
    BEARING(
        "bearing",
        "radians",
        "Direction bearing (radians)",
        PointFieldCategory.ANGLES,
        anglesInRadians = true,
    ),

    // --- Elevation -----------------------------------------------------------
    ELEVATION("elevation", "meters", "Elevation (meters)", PointFieldCategory.ELEVATION),

    // --- Grade ---------------------------------------------------------------
    GRADE("grade", "%", "Road grade/slope (%)", PointFieldCategory.GRADE),

    // --- Radius --------------------------------------------------------------
    RADIUS("radius", "meters", "Turn radius (meters)", PointFieldCategory.RADIUS),

    // --- Aero coef -----------------------------------------------------------
    AERO_COEF("aeroCoef", "aero", "Aerodynamic coefficient", PointFieldCategory.AERO_COEF),

    // --- Cyclist wind --------------------------------------------------------
    WIND_BEARING(
        "windBearing",
        "radians",
        "Wind bearing (radians)",
        PointFieldCategory.CYCLIST_WIND,
        anglesInRadians = true,
    ),
    WIND_ALPHA(
        "windAlpha",
        "radians",
        "Wind angle (radians)",
        PointFieldCategory.CYCLIST_WIND,
        anglesInRadians = true,
    ),

    // --- Power Physics -------------------------------------------------------
    P_AERO("pAero", "watts", "Aerodynamic power", PointFieldCategory.POWER_PHYSICS),
    P_GRAVITY("pGravity", "watts", "Gravitational power", PointFieldCategory.POWER_PHYSICS),
    P_ROLLING_RESISTANCE(
        "pRollingResistance",
        "watts",
        "Rolling resistance power",
        PointFieldCategory.POWER_PHYSICS,
    ),
    P_WHEEL_BEARINGS(
        "pWheelBearings",
        "watts",
        "Wheel bearings power",
        PointFieldCategory.POWER_PHYSICS,
    ),

    // --- Power Cyclist -------------------------------------------------------
    P_INPUT_POWER("pInputPower", "watts", "GPX input power", PointFieldCategory.POWER_CYCLIST),
    P_CYCLIST_PROVIDED_OPTIMAL_POWER(
        "pCyclistProvidedOptimalPower",
        "watts",
        "Optimal power",
        PointFieldCategory.POWER_CYCLIST,
    ),
    P_CYCLIST_PROVIDED_OPTIMAL_POWER_HARMONICS(
        "pCyclistProvidedOptimalPowerWithHarmonics",
        "watts",
        "Optimal power with harmonics",
        PointFieldCategory.POWER_CYCLIST,
    ),
    P_CYCLIST_PROVIDED_POWER_NEEDED(
        "pCyclistPowerNeeded",
        "watts",
        "Power needed",
        PointFieldCategory.POWER_CYCLIST,
    ),
    P_CYCLIST_PROVIDED_MUSCULAR(
        "pCyclistProvidedMuscular",
        "watts",
        "Raw cyclist power",
        PointFieldCategory.POWER_CYCLIST,
    ),
    P_CYCLIST_PROVIDED_WHEEL(
        "pCyclistProvidedWheel",
        "watts",
        "Cyclist power transmitted to ground",
        PointFieldCategory.POWER_CYCLIST,
    ),

    // --- Power Post-processed ------------------------------------------------
    P_COMPUTED_TOTAL_POWER(
        "pComputedTotalPower",
        "watts",
        "Power from kinetic energy change",
        PointFieldCategory.POWER_POST,
    ),
    P_COMPUTED_WHEEL_POWER(
        "pComputedWheelPower",
        "watts",
        "Wheel power from kinetic energy change",
        PointFieldCategory.POWER_POST,
    ),
    POWER("pComputedPower", "watts", "Total power (watts)", PointFieldCategory.POWER_POST),

    // --- Speed & Motion ------------------------------------------------------
    SPEED("speed", "m/s", "Current speed (m/s)", PointFieldCategory.SPEED),
    SPEED_MAX("speedMax", "m/s", "Maximum speed (m/s)", PointFieldCategory.SPEED),
    SPEED_MAX_INCLINE(
        "speedMaxIncline",
        "m/s",
        "Max speed on incline (m/s)",
        PointFieldCategory.SPEED,
    ),
    VIRT_SPEED_CURRENT(
        "virtSpeedCurrent",
        "m/s",
        "Virtual current speed (m/s)",
        PointFieldCategory.SPEED,
    ),

    // --- Environmental -------------------------------------------------------
    TEMPERATURE(
        "temperature",
        "celsius",
        "Temperature (celsius)",
        PointFieldCategory.ENVIRONMENTAL,
    ),
    WIND_SPEED("windSpeed", "m/s", "Wind speed (m/s)", PointFieldCategory.ENVIRONMENTAL),
    WIND_DIRECTION(
        "windDirection",
        "radians",
        "Wind direction (radians)",
        PointFieldCategory.ENVIRONMENTAL,
        anglesInRadians = true,
    ),

    // --- Physiological -------------------------------------------------------
    HEART_RATE("heartRate", "bpm", "Heart rate (bpm)", PointFieldCategory.PHYSIOLOGICAL),
    CADENCE("cadence", "rpm", "Pedaling cadence (rpm)", PointFieldCategory.PHYSIOLOGICAL),

    /**
     * Remaining anaerobic work capacity, in joules — the W′ balance of the Critical Power
     * model, written by `WPrimeBalanceComputer` (`:engine`).
     *
     * **Not a TS field.** `virtual-cyclist` has 36 fields and no physiological layer at all.
     */
    W_PRIME_BALANCE("wPrimeBalance", "joules", "W′ balance (J)", PointFieldCategory.PHYSIOLOGICAL),

    /**
     * Braking power (W, ≤ 0) — the wheel power a rider must *remove* to hold the speed limits
     * `MaxSpeedComputer` computed, written by `PowerComputer.computeCyclistPower` (`:engine`).
     *
     * Declared last so no existing ordinal moves, but it belongs to
     * [PointFieldCategory.POWER_PHYSICS] : it is a resistive term like drag or gravity, and it
     * carries the same sign convention (negative removes energy).
     *
     * **Not a TS field.** The TS reference discards this energy silently.
     */
    P_BRAKE("pBrake", "watts", "Braking power", PointFieldCategory.POWER_PHYSICS),

    /**
     * Signed curvature of the ridden trajectory, in m⁻¹, positive turning **left** — written by
     * the curvature estimator in `:engine`'s `trajectory` package, and read by
     * `MaxSpeedComputer` in preference to its own windowed bearing-difference estimate.
     *
     * [nanDefault] is `true` and load-bearing: `MaxSpeedComputer` gates on `isNaN()` to decide
     * whether the estimator ran. A zero default would read as "present, radius 1e9", which is
     * indistinguishable from a straight road and would silently suppress every cornering limit.
     *
     * Note the sign convention is **not** `Path.bearing`'s — that one is `atan2(-dy, dx)`,
     * screen-style and clockwise-from-east. This field follows the standard math azimuth of the
     * local planar frame it is computed in.
     *
     * **Not a TS field.** The TS reference has no curvature field.
     */
    TRAJECTORY_CURVATURE(
        "trajectoryCurvature",
        "1/m",
        "Trajectory curvature (1/m, + = left)",
        PointFieldCategory.RADIUS,
        nanDefault = true,
    ),
    ;

    /** Field index in the per-point `DoubleArray` slot (== [ordinal]). */
    val index: Int get() = ordinal

    companion object {
        /** Number of fields per point. Single source of truth for codegen (task 11). */
        const val COUNT: Int = 39

        private val byPropMap: Map<String, PointField> = entries.associateBy { it.prop }

        /** Lookup by camelCase property name (e.g. `"latitude"` → [LATITUDE]). */
        fun byProp(prop: String): PointField? = byPropMap[prop]

        /** All fields belonging to [category], in declaration order. */
        fun byCategory(category: PointFieldCategory): List<PointField> = entries.filter { it.category == category }
    }
}
