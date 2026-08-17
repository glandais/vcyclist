package io.github.glandais.engine.gpx

/**
 * Class-typical road widths for OSM `highway` values.
 *
 * Routers commonly stamp `highway` (and `surface`) onto each track point, and it is in practice the
 * *only* width signal available: a real gpx.studio export of the Stelvio carries `highway` and
 * `surface` on all 217 points and neither `width` nor `lanes` anywhere. So this mapping is what
 * stands between the racing-line corridor and a single global constant.
 *
 * ## These are estimates, and coarse ones
 *
 * Every figure below is a judgement call from the OSM wiki's usual carriageway widths, not a
 * measurement, and the real spread within any class is large — a `secondary` road can be 4 m or
 * 9 m. They earn their place only by varying where the global default cannot: on a route that
 * changes road class, a constant is wrong in one direction for half the ride.
 *
 * The values describe the **rideable carriageway**, both directions where the class implies two,
 * since that is what `ROAD_WIDTH` means and what the corridor halves.
 *
 * An unrecognised or absent class returns `null` rather than a guess. Inference must never
 * manufacture a width the file did not support — the reader then falls back to the engine's own
 * default, which at least is one visible number a user can override.
 */
internal object OsmHighway {
    private val WIDTH_BY_CLASS: Map<String, Double> =
        mapOf(
            // Major roads: two full lanes plus margins.
            "motorway" to 7.5,
            "motorway_link" to 6.0,
            "trunk" to 7.0,
            "trunk_link" to 6.0,
            "primary" to 6.5,
            "primary_link" to 5.5,
            "secondary" to 6.0,
            "secondary_link" to 5.0,
            "tertiary" to 5.5,
            "tertiary_link" to 5.0,
            // Minor roads: two lanes only nominally, often one with passing places.
            "unclassified" to 4.5,
            "residential" to 5.0,
            "living_street" to 4.0,
            "service" to 3.5,
            "road" to 5.0,
            // Ways that are not really carriageways at all.
            "track" to 3.0,
            "cycleway" to 2.5,
            "path" to 2.5,
            "bridleway" to 2.5,
            "footway" to 2.5,
            "pedestrian" to 3.0,
            "steps" to 2.5,
        )

    /**
     * Typical rideable width in metres for an OSM `highway` value, or `null` when the value is
     * absent, blank or unrecognised.
     */
    fun defaultWidthM(highway: String?): Double? {
        val key = highway?.trim()?.lowercase()
        if (key.isNullOrEmpty()) return null
        return WIDTH_BY_CLASS[key]
    }
}
