package io.github.glandais.codegen.ts

/**
 * Which stringly-typed sites of the JS façades are closed sets, and which are genuinely free text.
 *
 * Kotlin spells `roadCondition` as `String?` because an `external interface` cannot hold an enum a
 * JS caller could write as an object literal. TypeScript can express the closed set, and the doors
 * already parse through a wire catalogue in `commonMain`, so the union is **derived** from that
 * catalogue rather than transcribed — see [EnumCatalog].
 *
 * The shape is [OptionCatalog][io.github.glandais.codegen.surface.OptionCatalog]'s, for the same
 * reason: completeness is *derived*, not declared. `TsFacadeTest` asserts every `String` parameter
 * and property of both façades appears in exactly one of the two lists below, so a new stringly
 * option cannot reach TypeScript as a bare `string` without somebody writing down why.
 */
object StringUnions {
    /**
     * A site whose value is one of an enum's wire spellings.
     *
     * @param alias the exported TypeScript type name. Named rather than inlined so consumers can
     *   import it, and so `PointField`'s 44 spellings do not appear twice in one signature.
     */
    data class Bound(
        val site: String,
        val enum: String,
        val style: EnumCatalog.Style = EnumCatalog.Style.WIRE,
        val alias: String = enum,
    )

    /** A site that really is free text, with the reason it cannot be closed. */
    data class FreeText(
        val site: String,
        val reason: String,
    )

    val bound =
        listOf(
            Bound("CyclistDto.roadCondition", "RoadCondition"),
            Bound("EnhanceOptionsDto.racingLineCorridor", "CorridorMode"),
            Bound("EnhanceOptionsDto.elevationGainPreset", "ElevationGainPreset"),
            Bound("PowerProviderDto.type", "PowerModel"),
            Bound("CornerDto.kind", "CornerKind", EnumCatalog.Style.NAME),
            Bound("writeGpx.powerSource", "GpxPowerSource"),
            Bound("writeGpxAt.powerSource", "GpxPowerSource"),
            Bound("writeGpxTracks.powerSource", "GpxPowerSource"),
            Bound("getField.fieldProp", "PointField", alias = "PointFieldProp"),
            Bound("FieldDefinitionDto.prop", "PointField", alias = "PointFieldProp"),
        )

    val freeText =
        listOf(
            FreeText("WaypointDto.name", "a `<wpt><name>` written by whatever produced the file"),
            FreeText("WaypointDto.description", "free-form `<desc>`"),
            FreeText("WaypointDto.symbol", "`<sym>` ; the vocabulary is the consuming device's, not ours"),
            FreeText("WaypointDto.type", "free-form `<type>`"),
            FreeText("FieldDefinitionDto.unit", "free-form, as PointField's KDoc says"),
            FreeText("FieldDefinitionDto.shortDescription", "a human-readable label"),
            FreeText("FieldDefinitionDto.categoryId", "PointFieldCategory has no parsed wire spelling"),
            FreeText("FieldDefinitionDto.categoryName", "a human-readable label"),
            FreeText("ElevationProviderConfigDto.tileUrlTemplate", "a URL template"),
            FreeText("writeGpx.trackName", "the `<trk><name>` the caller wants"),
            FreeText("writeGpxAt.trackName", "the `<trk><name>` the caller wants"),
            FreeText("pathToCsv.separator", "any delimiter ; CsvOptions does not restrict it"),
            FreeText("pathToCsv.lineSeparator", "any line ending ; CsvOptions does not restrict it"),
            FreeText("pathToFit.name", "the FIT course name"),
            FreeText("pathsToFit.name", "the FIT course name"),
            FreeText("parseGpx.xml", "a GPX document"),
            FreeText("parseGpxTracks.xml", "a GPX document"),
            FreeText("parseGpxSegments.xml", "a GPX document"),
            FreeText("parseGpxTracksOnly.xml", "a GPX document"),
            FreeText("parseGpxRoutesOnly.xml", "a GPX document"),
            FreeText("parseGpxWaypoints.xml", "a GPX document"),
        )

    private val boundBySite = bound.associateBy { it.site }
    private val freeTextSites = freeText.map { it.site }.toSet()

    fun boundAt(site: String): Bound? = boundBySite[site]

    fun isDeclared(site: String): Boolean = site in boundBySite || site in freeTextSites
}
