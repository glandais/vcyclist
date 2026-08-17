# t02 — `ROAD_WIDTH` field + GPX width plumbing

## Goal

Carry a per-point road width from GPX into the `Path`, so the racing-line corridor has something
to be built from other than a single global constant.

This is `docs/design/racing-line.md` §2.2. It ships no behaviour: nothing reads `roadWidth` until
the corridor lands in t04. What it buys now is that the *format* is settled before the geometry
depends on it.

## Why per-point, and why now

`R_line − R_c = h·cot²(δ/4)` is **linear in `h`**, so the corridor half-width is not a detail — a
2× width error is a 2× error in the entire racing-line gain, in an unknown direction. The
feasibility study called this the design's decisive open question, and the maintainer's answer was
to keep `LANE` as the default and treat OSM ingestion as later work rather than a prerequisite.
That makes a *file-supplied* width the only real width source for now, so it needs to exist.

Per-point rather than a scalar option because `PointPerDistance` resamples twice before anything
reads the corridor: a scalar cannot survive that, and a per-segment width from any future OSM
source could not be expressed at all.

## Depends on

[t01](t01-nan-default-curvature-field.md) — the `nanDefault` mechanism. `ROAD_WIDTH` uses it:
a `0.0` width is not "unknown", it is a road you cannot ride on.

## Inputs

- `gpx/src/commonMain/.../path/PointField.kt` — `COUNT = 39` after t01
- `codegen/src/main/.../GeneratePath.kt` — `FIELDS`, `EXPECTED_COUNT`
- `gpx/src/commonMain/.../gpx/GpxParser.kt` — `parseExtensions` L369-407, `ExtensionsAccumulator`
  L467, `parseTrack` L146, `parseTrackPoint` L221
- `gpx/src/commonMain/.../gpx/Gpx.kt` — `GpxTrackPoint` L125, `GpxTrack` L79
- `gpx/src/commonMain/.../gpx/GpxToPath.kt` — `pointsToPath` L114
- `gpx/src/commonMain/.../gpx/GpxWriter.kt` — namespace constants L27-32, `writeTrackPoint` L210

## Steps

1. **Field.** Append `ROAD_WIDTH("roadWidth", "meters", …, nanDefault = true)`, 39 → 40, **after**
   `TRAJECTORY_CURVATURE` so its ordinal 38 does not move — that slot is already committed, and
   ordinals are the wire format. Bump
   `PointField.COUNT`, `GeneratePath.FIELDS`, `GeneratePath.EXPECTED_COUNT`; run
   `./gradlew :codegen:run`.
2. **Parse.** `ExtensionsAccumulator.roadWidthM: Double?`; recognise the leaf **`roadwidth`**
   only — see Notes for why `width` is not claimed. First value wins, matching the existing style.
3. **Model.** `GpxTrackPoint.roadWidthM: Double? = null`, appended last (positional call sites).
   `GpxTrack.roadWidthM: Double? = null` for a track-level default, also last.
4. **Track-level default.** `<trk><extensions><roadwidth>` parses into `GpxTrack.roadWidthM` and
   applies to points that carry none of their own.
5. **Convert.** `pointsToPath` writes the resolved width, or `Double.NaN`. Clamp to
   `[2.5, 20] m` on read; anything outside is a typo or a unit mix-up, not a road.
6. **Write.** `GpxWriter` emits `<extensions><vc:roadWidth>` under
   `NS_VCYCLIST = "https://github.com/glandais/vcyclist/xmlschemas/v1"`, declared on the root only
   when a width is actually written.
7. **Tests.** Parse (point-level, track-level, precedence, absent ⇒ NaN, out-of-range clamp),
   round-trip through the writer, and a `gpx_style` regression fixture (Notes).

## Outputs

- `PointField.ROAD_WIDTH`, `COUNT = 40`, regenerated sources
- `GpxTrackPoint.roadWidthM`, `GpxTrack.roadWidthM`
- Parser support for `roadwidth`, writer support for `vc:roadWidth`
- `path.roadWidth(i)`, `NaN` when unknown

## Validation

- `./gradlew check ktlintCheck` green on all targets.
- A fresh `Path` reads `NaN` for `roadWidth`; a GPX without widths round-trips byte-identically
  (no empty `<extensions>` appear).
- `<roadwidth>4.5</roadwidth>` on a `<trkpt>` reaches `path.roadWidth(0)`.
- A track-level `<roadwidth>` applies to points that lack one and loses to points that have one.
- **The `gpx_style` regression**: a file containing `<gpx_style:line><width>3</width></gpx_style:line>`
  must leave `roadWidth` as `NaN`, not 3 m.
- Widths of `0`, `-1`, `2000` are rejected to `NaN` rather than clamped into range — a
  transcription error must not silently become a legal corridor.

## Done when

- [x] `ROAD_WIDTH` appended with `nanDefault`, all three counts synced, sources regenerated
- [x] Parser reads point-level and track-level `roadwidth`, with the documented precedence
- [x] `GpxToPath` writes the resolved width; out-of-range values become `NaN`
- [x] `GpxWriter` emits `vc:roadWidth` and declares the namespace only when used
- [x] `gpx_style` regression test present and green
- [x] `./gradlew check ktlintCheck` green, working tree clean

## Notes

- **The bare leaf `width` is deliberately not claimed**, diverging from design §2.2 which lists
  `"roadwidth"`, `"width"`. `parseExtensions` recurses into unknown containers and matches on
  *local name only*, so claiming `width` would also match `<gpx_style:line><width>3</width>`,
  where the value is a **rendering line width in pixels** — a widely-used styling extension. A
  3-pixel line would become a 3 m road, silently halving the corridor on any styled file. The
  feasibility study flagged the name as risky; on inspection it is worse than risky, so only the
  unambiguous `roadwidth` is honoured. A namespaced `<vc:roadWidth>` lowercases to the same local
  name, so files this project writes round-trip.
- **The 20 m arclength smoothing of §2.2 is deliberately deferred** to the corridor builder in
  t04, not done at parse time. OSM widths are step functions and a step in `h` is a step in the
  constraint set, so the smoothing is genuinely needed — but it needs arclength, and the path is
  resampled twice by `PointPerDistance` after parsing. Smoothing before those resamples would
  smooth the wrong sampling. `PointPerDistance` interpolates linearly and NaN-propagates, so a
  width survives resampling unsmoothed and t04 can smooth it against the geometry it will use.
- `<rtept>` keeps skipping extensions, which is existing deliberate behaviour: routes get the
  default width. Not changed here.
- Memory: +1 double per point, ~4 MB on a 500 k path.


## Outcome

Shipped. No behaviour change: nothing reads `roadWidth` until the corridor lands.

Two placement corrections made during implementation, both caught by existing tests rather than by
review:

- `ROAD_WIDTH` cannot join `PointFieldCategory.COORDINATES`. That category is TS-parity and a test
  pins its membership *and order* (`latitude, longitude, distance, dx`), so a field the TS
  reference does not have cannot be added to it. A new `PointFieldCategory.ROAD` is appended last,
  after the thirteen TS defines, so no existing category's position moves.
- The field must be declared **after** `TRAJECTORY_CURVATURE`, not before it. The first attempt
  inserted ahead of it, which would have moved a slot that is already committed — exactly what the
  `PointField` header forbids. It also orphaned the curvature KDoc onto the new entry, since the
  insertion anchored on the declaration rather than on the doc block above it.
