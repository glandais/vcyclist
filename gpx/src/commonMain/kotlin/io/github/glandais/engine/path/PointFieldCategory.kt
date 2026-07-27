package io.github.glandais.engine.path

/**
 * Logical grouping of [PointField]s. Order is canonical (used by the UI to render
 * groups in a stable order) and matches the source TS `fieldDefinitions.ts`.
 *
 * @property id stable identifier matching the TS `id` (e.g. `"power_physics"`)
 * @property displayName human-readable label, may contain emojis (matches TS)
 */
enum class PointFieldCategory(
    val id: String,
    val displayName: String,
) {
    COORDINATES("coordinates", "Coordinates"),
    TEMPORAL("temporal", "Temporal"),
    ANGLES("angles", "Angles"),
    ELEVATION("elevation", "🏔️ Elevation"),
    GRADE("grade", "📐 Grade"),
    RADIUS("radius", "Radius"),
    AERO_COEF("aero_coef", "Aero coef"),
    CYCLIST_WIND("cyclist_wind", "Cyclist wind"),
    POWER_PHYSICS("power_physics", "⚡ Power Physics"),
    POWER_CYCLIST("power_cyclist", "⚡ Power Cyclist"),
    POWER_POST("power_post", "⚡ Power Post processed"),
    SPEED("speed", "Speed & Motion"),
    ENVIRONMENTAL("environmental", "Environmental"),
    PHYSIOLOGICAL("physiological", "Physiological"),
}
