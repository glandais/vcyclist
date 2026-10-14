package io.github.glandais.elevation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * The tile [url] was built for, from [config]'s template: the inverse of `TileManager.buildUrl`.
 * `{z}`, `{x}`, `{y}` are read back; a `{bbox}` (`south,west,north,east`, see [wmsBbox4326]) gives
 * the column from its west edge and the row from its north edge, both tile corners.
 */
internal fun tileCoordinatesOf(
    url: String,
    config: ElevationProviderConfig,
): TileCoordinates {
    val names = ArrayList<String>()
    val pattern = StringBuilder()
    var rest = config.tileUrlTemplate
    val placeholder = Regex("""\{(z|x|y|bbox)\}""")
    while (true) {
        val m = placeholder.find(rest) ?: break
        pattern.append(Regex.escape(rest.substring(0, m.range.first)))
        pattern.append(if (m.groupValues[1] == "bbox") "([^&]+)" else "(-?\\d+)")
        names += m.groupValues[1]
        rest = rest.substring(m.range.last + 1)
    }
    pattern.append(Regex.escape(rest))
    val match =
        Regex(pattern.toString()).matchEntire(url)
            ?: throw IllegalArgumentException("Tile URL $url does not match the template ${config.tileUrlTemplate}")
    val values = names.zip(match.groupValues.drop(1)).toMap()
    val z = values["z"]?.toInt() ?: config.zoomLevel
    val bbox = values["bbox"]
    if (bbox == null) return TileCoordinates(values.getValue("x").toInt(), values.getValue("y").toInt(), z)
    val (_, west, north, _) = bbox.split(',').map { it.toDouble() }
    val tiles = (1 shl z).toDouble()
    val x = ((west + 180.0) / 360.0 * tiles).roundToInt()
    val latRad = north * PI / 180.0
    val y = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * tiles).roundToInt()
    return TileCoordinates(x, y, z)
}
