# Declared but not reached by any UI control

An export is **not** a surface crossing: `docs/ledgers/surface-coverage.md`'s Démo column means
"reachable by a human in the UI", and `writeGpx` sat exported and unused from `g29` until `g35`
precisely because nobody checked. Every `@JsExport` reaches the demo's `import` surface
automatically now — `@glandais/vcyclist-engine`'s `index.d.ts` is generated from the façade — which
makes the distinction sharper, not softer: a binding has never been cheaper, so it has never meant
less. The ones no component calls are listed here so nobody mistakes availability for coverage.

`DemoReachabilityTest` parses this list. A bare identifier before the em dash is an entry; anything
`backticked` is prose.

    enhance                       — the view uses `enhanceWithCourse`, which is a superset
    writeGpx, writeGpxAt          — neither takes waypoints; the download moved to
                                    `writeGpxTracks` in S2 so the source's `<wpt>` survive
    pathsToFit                    — the FIT export is single-path; multi-lap has no control
    parseGpxSegments,
    parseGpxTracksOnly,
    parseGpxRoutesOnly            — the track picker uses `parseGpxTracks`, the superset
    pointAt                       — the chart reads fields in bulk through `getField`
    dominantHeadwindAzimuth,
    dominantHeadwindAzimuthOfTracks
    detectClimbs                  — superseded by `detectClimbsWithOptions`, which the climbs
                                    panel now drives; kept exported for a caller wanting the
                                    defaults without naming six numbers
    pathElevationGainFiltered,
    pathElevationLossFiltered     — the raw primitives, which return NaN when the elevationGain
                                    stage did not run. The UI shows
                                    `pathReportedElevationGain`, which is the same figure with
                                    the raw sum as a fallback, so a panel would have to render
                                    "—" for a state the fallback already resolves. A library
                                    caller that needs to tell "not measured" from "measured"
                                    apart does want them, which is why they stay exported
