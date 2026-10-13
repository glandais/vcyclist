package io.github.glandais.engine.path

import io.github.glandais.elevation.Distance
import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.IgnLidarHdLayer
import io.github.glandais.elevation.LatLon
import io.github.glandais.elevation.MathConstants
import io.github.glandais.elevation.RawTile
import io.github.glandais.elevation.decodeBil32Tile
import io.github.glandais.elevation.provider

/**
 * A synthetic LiDAR world for the bridge-deck step: an eastward road along latitude [LAT] whose
 * terrain and surface depend on the distance from [LON0] only. Banks at 10 m; a 400 m bridge from
 * 1 000 m, its deck at 18 m, 25 m abutments over 8 m ground, the river at 0 m under the rest.
 *
 * Tiles are IGN-shaped (z15, 256 px, EPSG:4326 `BBOX` in the URL), so the layers' own
 * configurations serve them: each pixel's longitude is read off the requested bbox.
 */
internal object DeckWorld {
    const val LAT = 47.0
    const val LON0 = 1.0
    const val DECK_START_M = 1_000.0
    const val DECK_END_M = 1_400.0
    const val DECK_M = 18.0
    const val BANK_M = 10.0

    /** Metres per degree of longitude along [LAT], as `Path.computeDerivedData` measures them. */
    val metresPerDegree: Double = Distance.haversine(LatLon(LAT, LON0), LatLon(LAT, LON0 + 0.001)) * 1000

    fun mnt(x: Double): Double =
        when {
            x < DECK_START_M || x > DECK_END_M -> BANK_M
            x < DECK_START_M + 25 || x > DECK_END_M - 25 -> 8.0
            else -> 0.0
        }

    fun mns(x: Double): Double = if (x < DECK_START_M || x > DECK_END_M) BANK_M else DECK_M

    /** A BIL tile of [f] (metres along the road) for the bbox in [url]. */
    fun tile(
        url: String,
        f: (Double) -> Double,
    ): RawTile {
        val bbox =
            url
                .substringAfter("BBOX=")
                .substringBefore('&')
                .split(',')
                .map { it.toDouble() }
        val west = bbox[1]
        val east = bbox[3]
        val size = 256
        val out = ByteArray(size * size * 4)
        for (px in 0 until size) {
            val lon = west + (px + 0.5) * (east - west) / size
            val bits = f((lon - LON0) * metresPerDegree).toFloat().toRawBits()
            for (py in 0 until size) {
                val o = (py * size + px) * 4
                for (k in 0 until 4) out[o + k] = (bits shr (8 * k)).toByte()
            }
        }
        return decodeBil32Tile(out, size, url)
    }

    /** The terrain, as the pipeline's own provider would serve it (no fallback needed here). */
    fun terrain(f: (Double) -> Double = ::mnt): ElevationProvider =
        ElevationProvider(IgnLidarHdLayer.MNT.config(15)) { url -> tile(url, f) }

    /** The surface model: a raw MNS layer, `NaN` on IGN's no-data. */
    fun surface(fetch: suspend (String) -> RawTile = { url -> tile(url, ::mns) }): ElevationProvider =
        IgnLidarHdLayer.MNS.provider(15) { fetch }

    /** An eastward path of [n] points every [stepM] metres from [LON0], its elevation the terrain. */
    fun path(
        lengthM: Double = 3_000.0,
        stepM: Double = 2.0,
    ): Path {
        val n = (lengthM / stepM).toInt() + 1
        val p = Path(n)
        for (i in 0 until n) {
            val x = i * stepM
            p.setLatitude(i, LAT * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (LON0 + x / metresPerDegree) * MathConstants.DEG_TO_RAD)
            p.setElevation(i, mnt(x))
            p.setTime(i, i * 1_000.0)
        }
        p.computeDerivedData()
        return p
    }
}
