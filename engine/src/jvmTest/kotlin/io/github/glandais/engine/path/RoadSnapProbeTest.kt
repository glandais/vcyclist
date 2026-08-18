package io.github.glandais.engine.path

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.LatLon
import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.firstTrackAsPath
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.test.Test

/**
 * Does moving the DEM lookup sideways change anything at zoom 12?
 *
 * This is the cheap experiment that decides ledger row **R29** (snap the lookup to the road, the
 * way Strava's basemap does) before any solver is written. R30 established that the DEM's 35 %
 * over-report on `strava.gpx` is not a resolution problem — z12 and z15 agree to 0.8 % — which
 * leaves *where along the road it is sampled* as the remaining hypothesis. If the profile barely
 * moves across a ±15 m corridor, no choice rule over those samples can help, and the row closes
 * without a Viterbi.
 *
 * Live HTTP, so it is gated twice: `INTEGRATION=1` for the network and the fixture directory being
 * present. Costs a few dozen tiles for one 3.6 km fixture.
 *
 *     INTEGRATION=1 ./gradlew :engine:jvmTest --tests '*RoadSnapProbeTest*' --rerun-tasks -i
 */
class RoadSnapProbeTest {
    private val gpxDir: File? =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "demo/public/gpx") }
            .firstOrNull { it.isDirectory }

    private val enabled: Boolean =
        System.getenv("INTEGRATION") == "1" || System.getProperty("integration") == "true"

    private val offsetsM = listOf(-15.0, -10.0, -5.0, 0.0, 5.0, 10.0, 15.0)

    /**
     * [path] shifted [offsetM] metres along its left normal.
     *
     * The normal comes from the centred difference of the neighbouring coordinates rather than from
     * `PointField.BEARING`, whose sign convention is `atan2(-dy, dx)` and would need unpicking for
     * no gain here.
     */
    private fun shifted(
        path: Path,
        offsetM: Double,
    ): List<LatLon> {
        val r = 6371000.0
        return List(path.size) { i ->
            val prev = maxOf(0, i - 1)
            val next = minOf(path.size - 1, i + 1)
            val latRad = path.latitude(i)
            val cosLat = cos(latRad)
            val east = (path.longitude(next) - path.longitude(prev)) * cosLat * r
            val north = (path.latitude(next) - path.latitude(prev)) * r
            val len = hypot(east, north)
            if (len == 0.0 || offsetM == 0.0) {
                LatLon(latRad * MathConstants.RAD_TO_DEG, path.longitude(i) * MathConstants.RAD_TO_DEG)
            } else {
                // Left normal of the unit heading (east, north) is (-north, east).
                val nEast = -north / len
                val nNorth = east / len
                LatLon(
                    latitude = (latRad + offsetM * nNorth / r) * MathConstants.RAD_TO_DEG,
                    longitude = (path.longitude(i) + offsetM * nEast / (r * cosLat)) * MathConstants.RAD_TO_DEG,
                )
            }
        }
    }

    /** Sum of |second difference| — how rough the profile is, which is what a snapper minimises. */
    private fun roughness(e: DoubleArray): Double {
        if (e.size < 3) return 0.0
        var total = 0.0
        for (i in 1 until e.size - 1) total += abs(e[i + 1] - 2.0 * e[i] + e[i - 1])
        return total / (e.size - 2)
    }

    @Test
    fun lateral_offsets_at_zoom_12() {
        if (!enabled || gpxDir == null) {
            println("[road-snap] set INTEGRATION=1 (and run from the repository) to probe the DEM")
            return
        }
        for (name in listOf("stelvio", "strava")) {
            val file = File(gpxDir, "$name.gpx")
            if (file.exists()) probe(name, file)
        }
    }

    private fun probe(
        name: String,
        file: File,
    ) {
        val input = GpxParser.parse(file.readText()).firstTrackAsPath()
        // ~30 m spacing: the granularity the pipeline hands to fixElevation.
        val path = PointPerDistance.compute(input, minDistanceM = -1.0, maxDistanceM = 30.0)
        val provider = ElevationProvider(ElevationProviderConfig(zoomLevel = 12))
        val distances = DoubleArray(path.size) { path.distance(it) }

        println()
        println("[road-snap] $name.gpx, ${path.size} stations at ~30 m, zoom 12")
        println("[road-snap] %8s | %8s | %10s | %9s".format("offset", "D+ (m)", "roughness", "max |dh|"))

        val centre = runBlocking { provider.setElevations(shifted(path, 0.0)) }.map { it.elevation }.toDoubleArray()
        for (offset in offsetsM) {
            val e =
                if (offset == 0.0) {
                    centre
                } else {
                    runBlocking { provider.setElevations(shifted(path, offset)) }.map { it.elevation }.toDoubleArray()
                }
            val gain = ElevationGain.compute(distances, e, ElevationGainOptions.DEFAULT).gainM
            val maxDelta = e.indices.maxOf { abs(e[it] - centre[it]) }
            println("[road-snap] %8.0f | %8.0f | %10.3f | %9.2f".format(offset, gain, roughness(e), maxDelta))
        }

        // What a per-station chooser with no smoothness penalty does — the naive shape of every
        // "pick the best offset here" rule. Reported to show it is not merely no better than the
        // centre but dramatically worse: choosing independently makes the offset track itself
        // jagged, and the profile inherits that.
        val samples =
            offsetsM.map { offset ->
                if (offset == 0.0) {
                    centre
                } else {
                    runBlocking { provider.setElevations(shifted(path, offset)) }.map { it.elevation }.toDoubleArray()
                }
            }
        val oracle =
            DoubleArray(path.size) { i ->
                if (i == 0 || i == path.size - 1) {
                    centre[i]
                } else {
                    samples.minByOrNull { abs(it[i + 1] - 2.0 * it[i] + it[i - 1]) }!![i]
                }
            }
        println(
            "[road-snap] %8s | %8.0f | %10.3f | %9s".format(
                "per-stn",
                ElevationGain.compute(distances, oracle, ElevationGainOptions.DEFAULT).gainM,
                roughness(oracle),
                "-",
            ),
        )
        println("[road-snap] the file's own profile: %.0f m".format(ElevationGain.compute(input, ElevationGainOptions.DEFAULT).gainM))
    }
}
