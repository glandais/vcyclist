package io.github.glandais.engine.path

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.RawTile
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.firstTrackAsPath

/**
 * Kotlin-side fixtures for the Java bridge test (task g22).
 *
 * `ElevationProvider`'s fetcher is a `suspend (String) -> RawTile`, which cannot be written in
 * Java — which is the very reason the bridges exist. Java and Kotlin sources of a JVM test
 * compilation see each other, so these `@JvmStatic` factories are callable straight from
 * `ElevationStepJavaTest`.
 */
object JvmBridgeFixtures {
    private const val TILE_SIZE = 4

    /** A short, real GPX: three points on the same slope, timestamps included. */
    private const val GPX =
        """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
  <trk><name>bridge</name><trkseg>
    <trkpt lat="45.0000" lon="6.0000"><ele>1000.0</ele><time>2024-05-01T08:00:00Z</time></trkpt>
    <trkpt lat="45.0010" lon="6.0010"><ele>1010.0</ele><time>2024-05-01T08:00:30Z</time></trkpt>
    <trkpt lat="45.0020" lon="6.0020"><ele>1025.0</ele><time>2024-05-01T08:01:00Z</time></trkpt>
  </trkseg></trk>
</gpx>"""

    @JvmStatic
    fun samplePath(): Path = GpxParser.parse(GPX).firstTrackAsPath()

    /** Every pixel decodes to the same altitude, so the corrected path is trivially checkable. */
    @JvmStatic
    fun flatProvider(elevationM: Int): ElevationProvider {
        val raw = elevationM + 32768
        val rgba =
            ByteArray(TILE_SIZE * TILE_SIZE * 4) { i ->
                when (i % 4) {
                    0 -> ((raw shr 8) and 0xFF).toByte()
                    1 -> (raw and 0xFF).toByte()
                    3 -> 255.toByte()
                    else -> 0
                }
            }
        val tile = RawTile(TILE_SIZE, TILE_SIZE, rgba)
        val config =
            ElevationProviderConfig(
                zoomLevel = 0,
                tileSize = TILE_SIZE,
                cacheSize = 4,
                tileUrlTemplate = "test://{z}/{x}/{y}",
            )
        return ElevationProvider(config) { tile }
    }
}
