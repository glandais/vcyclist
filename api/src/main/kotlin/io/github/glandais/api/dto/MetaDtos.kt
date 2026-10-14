package io.github.glandais.api.dto

import io.github.glandais.api.API_VERSION
import io.github.glandais.api.engineVersion
import io.github.glandais.api.http.OutputFormat
import io.github.glandais.elevation.ElevationSource
import io.github.glandais.engine.RoadCondition
import io.github.glandais.engine.TunnelScope
import io.github.glandais.engine.gpx.GpxPowerSource
import io.github.glandais.engine.path.ElevationCorrectionStatus
import io.github.glandais.engine.path.ElevationGainPreset
import io.github.glandais.engine.path.PointField
import io.github.glandais.engine.physics.PowerModel
import io.github.glandais.engine.trajectory.CornerKind
import io.github.glandais.engine.trajectory.CorridorMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /health`. */
@Serializable
@SerialName("Health")
data class HealthDto(
    val status: String,
)

/** `GET /version`: the contract's version and the engine's. */
@Serializable
@SerialName("Version")
data class VersionDto(
    val apiVersion: String,
    val engineVersion: String,
) {
    companion object {
        fun current(): VersionDto = VersionDto(API_VERSION, engineVersion)
    }
}

/**
 * `GET /catalog`: the wire value of every enum a request may name.
 *
 * Every list is read from the engine's own catalogue ([PowerModel.ids], [RoadCondition.wireNames],
 * …), never spelled here, so an engine enum that grows reaches this answer without an edit.
 */
@Serializable
@SerialName("Catalog")
data class CatalogDto(
    val powerModels: List<String>,
    val roadConditions: List<String>,
    val corridorModes: List<String>,
    val elevationGainPresets: List<String>,
    val gpxPowerSources: List<String>,
    val elevationSources: List<String>,
    val cornerKinds: List<String>,
    val elevationCorrectionStatuses: List<String>,
    val tunnelScopes: List<String>,
    val outputFormats: List<String>,
) {
    companion object {
        fun current(): CatalogDto =
            CatalogDto(
                powerModels = PowerModel.ids,
                roadConditions = RoadCondition.wireNames,
                corridorModes = CorridorMode.entries.map { it.id },
                elevationGainPresets = ElevationGainPreset.entries.map { it.id },
                gpxPowerSources = GpxPowerSource.wireNames,
                elevationSources = ElevationSource.ids,
                // The CornerKind *name*, as the JS and WASI corner DTOs emit it.
                cornerKinds = CornerKind.entries.map { it.name },
                // Response-side, like `cornerKinds`: the statuses of `TrackResult.elevationCorrections`.
                elevationCorrectionStatuses = ElevationCorrectionStatus.ids,
                tunnelScopes = TunnelScope.ids,
                outputFormats = OutputFormat.ids,
            )
    }
}

/**
 * One [PointField], as `GET /fields` lists them — the mirror of WASI's `vcFieldDefinitions`
 * (`WasiJsonOutput.fieldDefinitionsJson`), `index` included: it is the field's ordinal.
 */
@Serializable
@SerialName("FieldDefinition")
data class FieldDefinitionDto(
    val index: Int,
    val prop: String,
    val unit: String,
    val shortDescription: String,
    val categoryId: String,
    val categoryName: String,
    val notSelectable: Boolean,
    val anglesInRadians: Boolean,
) {
    companion object {
        fun all(): List<FieldDefinitionDto> =
            PointField.entries.mapIndexed { index, f ->
                FieldDefinitionDto(
                    index = index,
                    prop = f.prop,
                    unit = f.unit,
                    shortDescription = f.shortDescription,
                    categoryId = f.category.id,
                    categoryName = f.category.displayName,
                    notSelectable = f.notSelectable,
                    anglesInRadians = f.anglesInRadians,
                )
            }
    }
}
