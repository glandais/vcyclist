package io.github.glandais.parity

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.ElevationProviderConfig
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Kotlin/JVM side of the elevation (DEM) parity sweep — **needs network**.
 *
 * Resolves a fixed list of coordinates against the same Terrarium tiles as the TS runner
 * (`ts/liveElevation.ts`), so the two WebP decoder stacks are compared on identical bytes :
 * TwelveMonkeys `imageio-webp` here, node-canvas on the TS side.
 *
 * On the same decoded tile the two must agree to ~1e-9, not to the ±1 m Terrarium
 * resolution : a metre-scale gap would mean a decode or bilinear-interpolation bug, not
 * tile noise.
 *
 * Coordinates are chosen to exercise a single tile (dense cluster) as well as several
 * distinct tiles, plus below-sea-level and high-altitude samples.
 */
val ELEVATION_COORDS: List<Pair<Double, Double>> =
    listOf(
        45.8326 to 6.8652, // Mont Blanc summit
        31.5 to 35.5, // Dead Sea shore (negative elevation)
        46.52847 to 10.45213, // Stelvio pass
        46.5285 to 10.45215, // ~2 m away: same tile, adjacent pixels
        46.5290 to 10.45300, // same tile, different pixel
        46.5300 to 10.45500, // same tile
        0.0 to 0.0, // Gulf of Guinea (sea level, tile origin)
        -33.9249 to 18.4241, // Cape Town (southern hemisphere)
        64.1466 to -21.9426, // Reykjavik (western hemisphere)
        27.9881 to 86.9250, // Everest
    )

fun main(argv: Array<String>) {
    val args = argv.toList()

    fun arg(name: String): String {
        val i = args.indexOf("--$name")
        require(i >= 0 && i + 1 < args.size) { "missing --$name" }
        return args[i + 1]
    }

    val provider = ElevationProvider(ElevationProviderConfig(cacheSize = 64))
    val out = LinkedHashMap<String, Double>()

    runBlocking {
        ELEVATION_COORDS.forEachIndexed { i, (lat, lon) ->
            out["elevation.$i"] = provider.getElevation(lat, lon)
        }
    }

    File(arg("out")).writeText(
        out.entries.joinToString(separator = ",\n ", prefix = "{\n ", postfix = "\n}\n") {
            "\"${it.key}\": ${DumpFormat.json(it.value)}"
        },
    )
    System.err.println("[kt/elevation-live] ${out.size} values")
}
