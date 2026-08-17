# t01 — `nanDefault` in codegen + `TRAJECTORY_CURVATURE` field

## Goal

Give `PointField` a `nanDefault` flag, have `:codegen` emit a NaN-initialisation loop for the
flagged slots, and append one new field — `TRAJECTORY_CURVATURE` — using it.

Without `nanDefault` every `isNaN()` sentinel in the racing-line design is dead: an appended
field initialises to `0.0`, and a `0.0` curvature read as "present" makes
`MaxSpeedComputer` return `1/1e-9` and cap the whole route (racing-line design finding
"lattice-dp 3"). The flag is what makes the [t03](t03-curvature-estimator.md) hook a strict
no-op when the estimator has not run.

**Scope note.** The design (`docs/design/racing-line.md` §2.1) appends *three* fields
(`ROAD_WIDTH`, `LATERAL_OFFSET`, `TRAJECTORY_CURVATURE`). Only the third is needed to ship
the estimator, and the maintainer scoped this branch to the estimator first, so only it lands
here. `ROAD_WIDTH` belongs with the GPX width plumbing and `LATERAL_OFFSET` /
`SOURCE_LATITUDE` / `SOURCE_LONGITUDE` with the QP stage that actually moves coordinates.

## Depends on

Nothing. **Blocking for t03.**

## Inputs

- `gpx/src/commonMain/kotlin/io/github/glandais/engine/path/PointField.kt` — enum, `COUNT = 38` (L220)
- `gpx/src/commonMain/kotlin/io/github/glandais/engine/path/GeneratedPath.kt` — generated, `data` allocated L22
- `codegen/src/main/kotlin/io/github/glandais/codegen/GeneratePath.kt` — `FieldSpec` L18, `FIELDS` L24-64, `EXPECTED_COUNT = 38` L66, data-array emission L106
- `gpx/src/commonTest/kotlin/io/github/glandais/engine/path/PointFieldTest.kt`, `GeneratedPathTest.kt`

Note the design says "36 → 39". The repo is at **38** (`W_PRIME_BALANCE` and `P_BRAKE` landed
after the design's snapshot), so this is **38 → 39**.

## Steps

1. `PointField`: add `val nanDefault: Boolean = false` to the constructor, after
   `anglesInRadians` so no existing call site moves.
2. Append, following the `P_BRAKE` "declared last so no existing ordinal moves" convention
   documented at `PointField.kt:202-211`:
   ```kotlin
   TRAJECTORY_CURVATURE(
       "trajectoryCurvature", "1/m", "Signed trajectory curvature (+ = left)",
       PointFieldCategory.RADIUS, nanDefault = true,
   )
   ```
   Bump `COUNT` to 39.
3. `GeneratePath.kt`: add `nanDefault` to `FieldSpec`, add the entry to `FIELDS`, bump
   `EXPECTED_COUNT` to 39, and emit after the `data` allocation:
   ```kotlin
   init {
       for (i in 0 until size) {
           data[i * PointField.COUNT + 38] = Double.NaN
           // one line per nanDefault field
       }
   }
   ```
   Emit the block only when at least one field is flagged, so a zero-flag field list still
   generates compiling code.
4. `./gradlew :codegen:run` from the repo root; commit the regenerated `GeneratedPath.kt` and
   `PointFieldAccessors.kt`.
5. Update `PointFieldTest` (38 → 39) and `GeneratedPathTest` (round-trip for the new field).

## Outputs

- `PointField.nanDefault`, `PointField.TRAJECTORY_CURVATURE`, `COUNT = 39`
- Regenerated `GeneratedPath.kt` (with the `init` NaN loop) and `PointFieldAccessors.kt`
- `path.trajectoryCurvature(i)` / `path.setTrajectoryCurvature(i, v)`

## Validation

- `./gradlew :gpx:allTests :codegen:test ktlintCheck` green.
- **The load-bearing test**: a fresh `Path(3)` reads `Double.NaN` for `trajectoryCurvature`
  at every index and `0.0` for all 38 other fields. Without this assertion the whole
  `nanDefault` mechanism can regress silently.
- `./gradlew check` — the full pipeline is byte-identical, since nothing reads the new field yet.

## Done when

- [x] `nanDefault` flag exists and is honoured by the generator
- [x] `TRAJECTORY_CURVATURE` appended, `COUNT`/`EXPECTED_COUNT` both 39
- [x] Generated sources regenerated and committed
- [x] Fresh-`Path`-reads-NaN test present and green
- [x] `./gradlew check ktlintCheck` green, working tree clean

## Notes

- **`COUNT` is a three-way sync**: `PointField.COUNT`, `GeneratePath.FIELDS`, and
  `GeneratePath.EXPECTED_COUNT`. The assertion at `GeneratePath.kt:69-71` catches two of the
  three; `PointFieldTest` catches the last.
- Downstream is already NaN-safe and needs no change: `PointPerDistance.kt:141` and
  `PointPerSecond.kt:115` interpolate linearly (NaN-propagating, which is correct here),
  `PathSimplifier.kt:71` copies fields verbatim, and the CSV/JSON writers already render NaN
  as `""` / `null` (`CsvNumberFormat.kt:47`, `JsonWriter.kt:132`).
- Ordinal 38 is the wire slot. It must never be reordered — see the `PointField` header KDoc.
