package io.github.glandais.engine.trajectory

/**
 * A path's geometry expressed in a single local planar frame, in metres.
 *
 * ## Why a frame at all
 *
 * Curvature is a planar notion, and the only planar projection previously in the codebase is the
 * one inlined in `Path.computeBearing`, which uses **absolute** longitude with a per-point cosine
 * (`x = lon·cos(lat)`). That map is sheared: `∂x/∂lat = −lon·sin(lat)`, which at 6°E / 45°N is
 * `−0.074`, i.e. `atan(0.074) = 4.2°` of shear on a due-north segment — and it grows linearly
 * with longitude, so the error is worse the further east you ride. Anything derived from those
 * bearings inherits the shear.
 *
 * This frame instead anchors **once**, at the bounds centre, and holds `k = cos(lat0)` constant:
 *
 * ```
 * x = R_E·k·(lon − lon0)      (east, metres)
 * y = R_E·(lat − lat0)        (north, metres)
 * ```
 *
 * so the map is a fixed affine transform of (lon, lat) — exactly invertible, and free of the
 * per-point distortion. The residual scale error `≈ tan(lat0)·Δlat` is east–west only and reaches
 * 0.87 % over a 55 km north–south span at 45°, an order below anything this is used for. The frame
 * is deliberately **not** re-anchored per corner: mixing frames creates seams at the joins.
 *
 * ## Conventions
 *
 * `x` east, `y` north, standard mathematical azimuth (`theta = atan2(dy, dx)`, counter-clockwise
 * from east). Curvature is **signed**, positive when turning left.
 *
 * This is *not* `Path.bearing`'s convention, which is `atan2(-dy, dx)` — screen-style and
 * clockwise. Nothing in this package reads `path.bearing(i)`, and nothing should start.
 *
 * @property x eastings, metres, smoothed if the frame was built with a smoothing window
 * @property y northings, metres
 * @property s cumulative arclength, metres (taken from `path.distance`, monotone by construction)
 * @property theta unwrapped heading, radians — continuous, with no ±π branch cut
 * @property kappa signed curvature, m⁻¹, positive turning left
 * @property lat0 anchor latitude, radians
 * @property lon0 anchor longitude, radians
 * @property k `cos(lat0)`, the constant east–west scale factor
 */
internal class PlanarFrame(
    val x: DoubleArray,
    val y: DoubleArray,
    val s: DoubleArray,
    val theta: DoubleArray,
    val kappa: DoubleArray,
    val lat0: Double,
    val lon0: Double,
    val k: Double,
) {
    val size: Int get() = x.size
}
