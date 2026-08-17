# t08 — Road width from the OSM `highway` class

## Goal

Derive a per-point road width from the OSM `highway` classification that routers actually emit, so
the racing-line corridor stops resting on a single global guess.

## Why this, and why it is only a partial answer

Design §12 question 1 is the decisive open question of the whole racing-line design: the corridor
is a fiction without width data, and `R_line − R_c = h·cot²(δ/4)` is **linear in `h`**, so a 2×
width error is a 2× error in the entire result, in an unknown direction. The design's answer is
OSM ingestion.

Inspecting a real router export (gpx.studio, Stelvio) shows what is actually available:

```xml
<extensions><gpxtpx:TrackPointExtension><gpxtpx:Extensions>
  <highway>secondary</highway>
  <surface>asphalt</surface>
</gpxtpx:Extensions></gpxtpx:TrackPointExtension></extensions>
```

**No `width`. No `lanes`.** Every one of the 217 points carries the same `highway=secondary` and
`surface=asphalt`. So the honest scope of this task is:

- `highway` → a **class-typical** width. Coarse, but it is a real signal and it varies along any
  route that changes road class, which the global default cannot do.
- On a route of uniform classification it changes **nothing** — and `secondary` maps to 6.0 m,
  which is already `defaultRoadWidthM`, so on this particular file the output is byte-identical.
  That is the correct outcome, not a disappointment: the mechanism exists for the mixed routes
  where it matters.

## Why `surface` is parsed and then deliberately dropped

`surface` is the more valuable of the two — grip enters `v_max = √(µgR)` directly, so it moves the
speed envelope *everywhere* rather than refining it where the envelope binds, which per ledger R11,
R24 and R25 is the difference between fractions of a percent and something worth measuring.

It is still not ingested here, for two reasons. There is no consumer: `µ` is a scalar on `Cyclist`
today, and making it per-point is a physics change touching `MaxSpeedComputer`, the friction
ellipse and the pedal-strike cut-off — a separate task, not a rider on this one. And a field
nothing reads is a claim the API cannot keep, the same reason `CornerKind` has no `ROUNDABOUT`.
Task notes record what it would be worth.

## Depends on

[t02](t02-road-width.md) — `ROAD_WIDTH` and the extension plumbing.

## Steps

1. **Parse.** `ExtensionsAccumulator.highway: String?`, recognising the `highway` leaf. The
   existing recursive scan already descends through `gpxtpx:TrackPointExtension` and
   `gpxtpx:Extensions`, so only the leaf name is new.
2. **Model.** `GpxTrackPoint.highway: String? = null`, appended last.
3. **Map.** `OsmHighway.defaultWidthM(highway)` — class-typical rideable widths, `null` for
   anything unrecognised.
4. **Resolve.** In `pointsToPath`, width precedence becomes: the point's own `roadwidth`, then the
   track-level default, then the `highway` class, then `NaN` (engine default). Explicit data always
   beats inference.
5. **Tests.** Per-class widths, precedence, an unknown class falling through to `NaN`, and a
   round-trip on the real export's element shape.

## Validation

- `./gradlew check ktlintCheck` green on all targets.
- `<highway>residential</highway>` yields a narrower corridor than `<highway>primary</highway>`.
- An explicit `<roadwidth>` beats the class.
- An unknown or absent class leaves `NaN`, so the engine default applies — inference must never
  produce a width the file did not support.
- A route whose class maps to the current default is byte-identical.

## Done when

- [x] `highway` parsed into `GpxTrackPoint`
- [x] `OsmHighway` mapping with the documented precedence
- [x] Tests for classes, precedence and unknown values
- [x] `./gradlew check ktlintCheck` green, working tree clean

## Notes

- The widths are **class-typical, not measured**, and every one is a judgement call. They are
  sourced from the OSM wiki's usual carriageway figures and clamped into the same
  `[2.5, 20] m` plausibility range t02 established. Being wrong by a metre here is better than the
  status quo of being wrong by a metre *everywhere at once*, but it is not data.
- `highway` is a per-*way* tag, so it changes in steps at way boundaries. t04's 20 m width
  smoothing already handles that — a step in the width is a step in the constraint set.
- What `surface` would be worth, when something can read it: OSM's common values span asphalt
  through gravel to cobblestone, and ledger R9 already records that wet grip is 40 % of dry, i.e. a
  1.58× cut in cornering speed. A gravel section is a comparable or larger cut, applied to the
  envelope itself. That is a bigger lever than anything left in the racing-line plan.


## Outcome

Shipped, and it works — but the measurement is the point of this entry.

Two real router exports were inspected. Both carry `highway` and `surface` per point and **neither
carries `width` or `lanes`**. A Stelvio export is uniformly `highway=secondary`; a 128 km route
spans five classes (secondary 3850 points, tertiary 1298, primary 356, cycleway 55, unclassified 6).

On that 128 km route, `FULL_ROAD`, full pipeline:

| run | duration | vs plain |
|---|---|---|
| plain, no racing line | 19 396 s | — |
| racing line, **inferred** widths | 19 326 s | −0.36 % |
| racing line, uniform 6.0 m | 19 327 s | −0.36 % |

**Inference against a uniform default is worth one second in five and a half hours** — 0.005 %.
The reason is visible in the class histogram: 69 % of the route is `secondary`, whose class-typical
width *is* the 6.0 m default, and the classes that differ carry too little of the route to matter.

So design §12 question 1 — "the corridor is a fiction without width data" — is **not** answered by
OSM ingestion, and cannot be, because the width data does not exist in the export. What exists is a
road-class proxy that is nearly constant on any given route. The feasibility study called OSM
ingestion the true blocking dependency for the racing line; measured, it is not a dependency at
all, because it has nothing to give. The corridor remains a global assumption, and `--road-width`
remains the honest way to correct it.

An unrelated finding from the same run, worth more than the task itself: **`LANE` beat `FULL_ROAD`**
— 19 312 s against 19 326 s. The legal corridor is *faster* than the illegal one, because
full-road weaving costs more distance than the extra corner speed returns. That is the same
mechanism R25 found, and it means the mode that is safe to default is also the one to prefer.

### The `--road-width` precedence fix

Adding inference silently broke the flag: `GpxToPath` writes an inferred width, file-derived data
outranks an option default, so on any router-tagged route `--road-width 3` did exactly nothing.
The CLI now stamps the width whenever the option was *matched* — checked against
`hasMatchedOption`, not against the default value, so passing the default explicitly still counts.
A class-typical figure is a guess, a file's `<roadwidth>` is somebody else's measurement, and a
typed argument is this user telling us about this road; the last of those wins.
