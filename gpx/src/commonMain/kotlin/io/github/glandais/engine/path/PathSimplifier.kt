package io.github.glandais.engine.path

import io.github.glandais.elevation.CoordinatesElevation
import io.github.glandais.elevation.DouglasPeucker
import io.github.glandais.elevation.LatLonElevation
import io.github.glandais.elevation.MathConstants

/**
 * Path simplification via 3D Douglas-Peucker (ECEF) — wraps the elevation module's
 * [DouglasPeucker] to preserve every [PointField] slot of retained points.
 *
 * Algorithm :
 * 1. Build a `List<CoordinatesElevation>` from the path (degrees).
 * 2. Call [DouglasPeucker.simplify] to get the simplified subset (in order).
 * 3. Walk the source path with a single pointer ; copy every retained point's full slot
 *    set into the output path.
 * 4. Run [Path.computeDerivedData] on the output.
 *
 * Mirrors `processing/DouglasPeucker.ts` semantics while reusing the elevation module's
 * implementation. Unlike the TS reference, every [PointField] slot of retained points is
 * preserved (e.g. `pInputPower`, `heartRate`) — not just lat/lon/elevation.
 *
 * Stateless ; safe for concurrent calls.
 */
object PathSimplifier {
    /**
     * Simplify [path] in 3D space.
     *
     * @param path source path (unchanged)
     * @param toleranceM maximum allowed perpendicular distance from the simplified line (meters)
     * @param zExaggeration vertical exaggeration factor passed to the ECEF conversion. Higher
     *   values amplify altitude differences so that small bumps are more likely to be retained.
     * @return a new [Path] containing the retained points with [Path.computeDerivedData] applied.
     *   For paths of size ≤ 2 a defensive [Path.copy] of the source is returned.
     */
    fun simplify(
        path: Path,
        toleranceM: Double,
        zExaggeration: Double = 3.0,
    ): Path {
        if (path.size <= 2) return path.copy()

        // Build the coordinate list (degrees, with elevation).
        val coords = ArrayList<CoordinatesElevation>(path.size)
        for (i in 0 until path.size) {
            coords +=
                LatLonElevation(
                    latitude = path.latitude(i) * MathConstants.RAD_TO_DEG,
                    longitude = path.longitude(i) * MathConstants.RAD_TO_DEG,
                    elevation = path.elevation(i),
                )
        }

        val simplified = DouglasPeucker.simplify(coords, toleranceM, zExaggeration)

        // Map retained coords back to source indices via reference equality.
        // [DouglasPeucker.simplify] returns the same instances passed in (no .copy()) and
        // preserves order, so a single monotonic linear scan is sufficient.
        val retainedIndices = ArrayList<Int>(simplified.size)
        var cursor = 0
        for (kept in simplified) {
            while (cursor < coords.size && coords[cursor] !== kept) cursor++
            if (cursor == coords.size) error("PathSimplifier: retained point not found in source")
            retainedIndices += cursor
            cursor++ // monotonic progress
        }

        // Materialize a new Path with the retained slots (every field, not just lat/lon/ele).
        val out = Path(retainedIndices.size)
        for ((dstIdx, srcIdx) in retainedIndices.withIndex()) {
            for (field in PointField.entries) {
                out.set(dstIdx, field, path.get(srcIdx, field))
            }
        }
        out.computeDerivedData()
        return out
    }
}
