package io.github.glandais.engine.wasi

/**
 * The `EngineJsApi` → `EngineWasiApi` correspondence table, as data rather than as prose.
 *
 * Task w04 asks for one line per JS export with an explicit decision, and for that table to be
 * *verifiable*. Prose in a markdown file cannot be: it goes stale the first time someone adds a
 * `@JsExport` and forgets. Here, `WasiParityTableTest` (jvmTest) reads the `@JsExport`
 * declarations straight out of `EngineJsApi.kt` and fails if any of them is missing from this
 * list — so the table cannot silently fall behind the façade it claims to mirror.
 *
 * Task w10 renders this into `docs/wasm-wasi-abi.md`; this stays the source of truth.
 */
internal enum class ParityDecision {
    /** Same capability, same name modulo the `vc` prefix. */
    PORTED,

    /** Same capability, different shape — several JS functions folded into one options object. */
    RESHAPED,

    /** Not available on this target, with the reason in [ParityEntry.note]. */
    NOT_PORTED,
}

internal class ParityEntry(
    val jsExport: String,
    val wasiExport: String,
    val decision: ParityDecision,
    val note: String,
)

/**
 * One entry per `@JsExport` of `EngineJsApi`. Kept in the order of the JS file so the two read
 * side by side.
 */
internal val PARITY_TABLE: List<ParityEntry> =
    listOf(
        ParityEntry(
            "parseGpxWaypoints",
            "vcParseGpxWaypointsJson",
            ParityDecision.RESHAPED,
            "returns the WaypointDto array as JSON rather than as objects",
        ),
        ParityEntry("parseGpx", "vcParseGpx", ParityDecision.PORTED, "first track, one handle"),
        ParityEntry(
            "parseGpxTracks",
            "vcParseGpxMulti(mode=0)",
            ParityDecision.RESHAPED,
            "returns a list handle; vcListSize / vcListGet walk it",
        ),
        ParityEntry("parseGpxSegments", "vcParseGpxMulti(mode=1)", ParityDecision.RESHAPED, "same list handle"),
        ParityEntry("parseGpxTracksOnly", "vcParseGpxMulti(mode=2)", ParityDecision.RESHAPED, "same list handle"),
        ParityEntry("parseGpxRoutesOnly", "vcParseGpxMulti(mode=3)", ParityDecision.RESHAPED, "same list handle"),
        ParityEntry(
            "writeGpxTracks",
            "vcWriteGpxTracks",
            ParityDecision.PORTED,
            "waypoints are not forwarded: the ABI has no waypoint handle, see w10",
        ),
        ParityEntry("pathSize", "vcPathSize", ParityDecision.PORTED, ""),
        ParityEntry("pathTotalDistance", "vcPathTotalDistance", ParityDecision.PORTED, ""),
        ParityEntry("pathDurationMs", "vcPathDurationMs", ParityDecision.PORTED, ""),
        ParityEntry("pathElevationGain", "vcPathElevationGain", ParityDecision.PORTED, ""),
        ParityEntry("pathElevationLoss", "vcPathElevationLoss", ParityDecision.PORTED, ""),
        ParityEntry("pointAt", "vcPointJson", ParityDecision.RESHAPED, "PointDto as JSON"),
        ParityEntry("writeGpx", "vcWriteGpx", ParityDecision.RESHAPED, "writeExtensions moved into the options object"),
        ParityEntry(
            "writeGpxAt",
            "vcWriteGpx",
            ParityDecision.RESHAPED,
            "same export: startTimeEpochMs present in the options means absolute times",
        ),
        ParityEntry("enhance", "vcEnhance", ParityDecision.PORTED, "synchronous; fixElevation needs w05"),
        ParityEntry(
            "enhanceWithCourse",
            "vcEnhanceWithCourse",
            ParityDecision.RESHAPED,
            "cyclist / bike / wind / power / options are sub-objects of one JSON payload",
        ),
        ParityEntry(
            "getField",
            "vcGetField",
            ParityDecision.RESHAPED,
            "takes the field index from vcFieldDefinitionsJson, not its name",
        ),
        ParityEntry("fieldDefinitions", "vcFieldDefinitionsJson", ParityDecision.RESHAPED, "JSON array, index included"),
        ParityEntry("pathLatitudeDeg", "vcPathLatitudeDeg", ParityDecision.PORTED, ""),
        ParityEntry("pathLongitudeDeg", "vcPathLongitudeDeg", ParityDecision.PORTED, ""),
        ParityEntry("pathToCsv", "vcPathToCsv", ParityDecision.RESHAPED, "separator / unitsInHeader in the options"),
        ParityEntry("pathToJson", "vcPathToJson", ParityDecision.RESHAPED, "pretty / decimals / includeMeta in the options"),
        ParityEntry(
            "pathToFit",
            "vcPathToFit",
            ParityDecision.RESHAPED,
            "name and startTimeEpochMs moved into the payload; startTimeEpochMs stays mandatory (w12)",
        ),
        ParityEntry(
            "pathsToFit",
            "vcPathsToFit",
            ParityDecision.RESHAPED,
            "list handle; interPathGapMs is a payload field (w12)",
        ),
        ParityEntry("dominantHeadwindAzimuth", "vcDominantHeadwindAzimuth", ParityDecision.PORTED, ""),
        ParityEntry(
            "dominantHeadwindAzimuthOfTracks",
            "vcDominantHeadwindAzimuthOfTracks",
            ParityDecision.PORTED,
            "takes a list handle",
        ),
        ParityEntry("detectClimbs", "vcDetectClimbsJson", ParityDecision.RESHAPED, "no options object means the defaults"),
        ParityEntry(
            "detectClimbsWithOptions",
            "vcDetectClimbsJson",
            ParityDecision.RESHAPED,
            "same export: the six scalars become the fields of the options object",
        ),
        ParityEntry(
            "analyzeRacingLine",
            "vcAnalyzeRacingLineJson",
            ParityDecision.RESHAPED,
            "the report crosses as JSON, so its NaN slots arrive as null; JS keeps the DoubleArray",
        ),
    )
