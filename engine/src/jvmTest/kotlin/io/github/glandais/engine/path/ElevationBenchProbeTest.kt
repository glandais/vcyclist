package io.github.glandais.engine.path

import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.ElevationSource
import io.github.glandais.elevation.MathConstants
import io.github.glandais.elevation.RawTile
import io.github.glandais.elevation.TileEncoding
import io.github.glandais.elevation.decodeBil32Tile
import io.github.glandais.elevation.decodeTileBytes
import io.github.glandais.elevation.fetchTileBytes
import io.github.glandais.elevation.pacedTileBytesFetcher
import io.github.glandais.elevation.provider
import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.SimplifyPathOptions
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.firstTrackAsPath
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The E1 bench of `docs/tasks/elevation-bridge-decks.md`: how far vcyclist's elevation is from the
 * road on bridges, in tunnels and on cliff roads, *before* any correction.
 *
 * The four routes of `docs/deck-strava-data/` (hand-drawn Strava routes over Saint-Nazaire, Nantes,
 * Mauves and the Vercors) go through the real pipeline with each DEM source. The truth is the
 * estimated roadway of the analysis, frozen by `analysis/export_truth.py` into
 * `analysis/truth/` (one CSV per route) — LiDAR MNS median on detected decks, portal-to-portal line in
 * tunnels, Strava shifted onto the MNT frame on balcony spans (where the MNT reads the cliff), LiDAR
 * MNT elsewhere, all NGF-IGN69 — so the bench never calls Overpass or the MNS.
 *
 * Two tests:
 * - [truth_files_parse_and_hold_their_guards] runs on every `jvmTest`: it only reads the frozen
 *   files, checks their sizes (so a broken reader fails loudly instead of measuring nothing) and
 *   the spec's guards (Saint-Nazaire at 63.7 m and a 5.9 % slope P95, Grands Goulets 600 → 681 m).
 * - [bench] is live HTTP and gated on `INTEGRATION=1`. Tiles are cached on disk under
 *   `build/dem-cache` at the repository root (or `$VCYCLIST_DEM_CACHE`; same layout as the CLI's
 *   `--cache`, so `~/.vcyclist/cache` works too). A cold run fetches ≈ 190 IGN tiles (≈ 48 MB) and
 *   ≈ 20 Mapterhorn tiles (≈ 2 MB), about 210 tiles / 51 MB in all; a warm one fetches nothing.
 *
 *     INTEGRATION=1 ./gradlew :engine:jvmTest --tests '*ElevationBenchProbeTest*' --rerun-tasks -i
 *
 * Errors are read on the dense path (steps 1–4 of the pipeline, 1–2 m spacing): `sourceElevation`
 * is what `ElevationGain` reads, `elevation` (150 m kernel) is what the physics rides. Reported D+
 * and simulated duration come from a full `EnhanceOptions.DEFAULT` run; a second full run with
 * `simplifyPath` off isolates what `PathSimplifier` does to the reported D+. No offset is subtracted
 * from either source: both serve NGF-IGN69 LiDAR in France (Mapterhorn z12 is subsampled LiDAR HD),
 * so the median difference on open terrain is printed as a diagnostic of local bias, not a datum.
 */
class ElevationBenchProbeTest {
    private val dataDir: File? =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "docs/deck-strava-data") }
            .firstOrNull { it.isDirectory }

    private val enabled: Boolean =
        System.getenv("INTEGRATION") == "1" || System.getProperty("integration") == "true"

    // ── Frozen truth ────────────────────────────────────────────────────────────────────────────

    private class Truth(
        val route: String,
        val stravaOffsetM: Double,
        val s: DoubleArray,
        val lat: DoubleArray,
        val lon: DoubleArray,
        val z: DoubleArray,
        val mnt: DoubleArray,
        val kind: CharArray,
    ) {
        val size: Int get() = s.size
    }

    private class Structure(
        val route: String,
        val kind: String,
        val i0: Int,
        val i1: Int,
        val s0: Double,
        val s1: Double,
        val z0: Double,
        val z1: Double,
        val zmax: Double,
        val name: String,
        val flag: String,
    ) {
        val label: String
            get() = name.ifEmpty { "km %.2f".fmt(s0 / 1000) }.substringBefore(';').take(28)
    }

    private fun readTruth(route: String): Truth {
        val lines = File(dataDir, "analysis/truth/$route.csv").readLines()
        val header = lines.first()
        val declared = Regex("""\bn=(\d+)""").find(header)!!.groupValues[1].toInt()
        val offset = Regex("""strava_offset_m=(-?[\d.]+)""").find(header)!!.groupValues[1].toDouble()
        val rows = lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("s,") }.map { it.split(',') }
        assertEquals(declared, rows.size, "$route.csv: row count against its own header")
        rows.forEach { assertEquals(6, it.size, "$route.csv: column count") }
        return Truth(
            route = route,
            stravaOffsetM = offset,
            s = DoubleArray(rows.size) { rows[it][0].toDouble() },
            lat = DoubleArray(rows.size) { rows[it][1].toDouble() },
            lon = DoubleArray(rows.size) { rows[it][2].toDouble() },
            z = DoubleArray(rows.size) { rows[it][3].toDouble() },
            mnt = DoubleArray(rows.size) { rows[it][4].toDouble() },
            kind = CharArray(rows.size) { rows[it][5].single() },
        )
    }

    private fun readStructures(): List<Structure> {
        val lines = File(dataDir, "analysis/truth/structures.csv").readLines().filter { it.isNotBlank() }
        assertEquals("route,kind,i0,i1,s0,s1,lat0,lon0,lat1,lon1,z0,z1,zmax,name,flag", lines.first())
        return lines.drop(1).map { line ->
            val c = line.split(',')
            assertEquals(15, c.size, "structures.csv: column count in '$line'")
            Structure(
                route = c[0],
                kind = c[1],
                i0 = c[2].toInt(),
                i1 = c[3].toInt(),
                s0 = c[4].toDouble(),
                s1 = c[5].toDouble(),
                z0 = c[10].toDouble(),
                z1 = c[11].toDouble(),
                zmax = c[12].toDouble(),
                name = c[13],
                flag = c[14],
            )
        }
    }

    /**
     * Slope over a centred window of `2·h` stations on a uniform 5 m grid, as a fraction, edges
     * clamped (analyze.py `slope`, whose `L` metres give `h = round(L / 10)`).
     */
    private fun slope(
        z: DoubleArray,
        h: Int,
    ): DoubleArray =
        DoubleArray(z.size) { k ->
            val a = z[maxOf(0, k - h)]
            val b = z[minOf(z.size - 1, k + h)]
            (b - a) / (2 * h * STEP_M)
        }

    /** Slope over a centred 50 m window: the bench's steepness count. */
    private fun slope50(z: DoubleArray): DoubleArray = slope(z, 5)

    @Test
    fun truth_files_parse_and_hold_their_guards() {
        // No soft skip here: inside the repository the directory always resolves, so a miss means
        // it moved, and a guard test that skips itself is green-but-blind.
        assertNotNull(dataDir, "docs/deck-strava-data not found")
        val truths = ROUTES.mapValues { readTruth(it.key) }
        ROUTES.forEach { (route, rows) -> assertEquals(rows, truths.getValue(route).size, "$route.csv rows") }
        val structures = readStructures()
        assertEquals(STRUCTURE_COUNTS, structures.groupingBy { it.kind }.eachCount(), "structures.csv kinds")
        structures.forEach { st ->
            val t = truths.getValue(st.route)
            assertTrue(st.i0 in 0..st.i1 && st.i1 < t.size, "span ${st.route} ${st.i0}..${st.i1}")
            assertEquals(st.zmax, (st.i0..st.i1).maxOf { t.z[it] }, 0.011, "zmax of ${st.route} ${st.label}")
        }

        val nazaire = structures.single { it.route == "test1" && "Saint-Nazaire" in it.name }
        assertEquals(63.68, nazaire.zmax, 0.01)
        // analyze.py: P95 of |slope(mf, 25)| over [i0 + 2, i1 - 1) — a 20 m window (h = 2) on the
        // deck's MNS median, which is the truth there — with numpy's linear-interpolated percentile.
        val nz = truths.getValue("test1")
        val slopes = slope(nz.z, 2).sliceArray(nazaire.i0 + 2..nazaire.i1 - 2).map { abs(it) }.sorted()
        val rank = (slopes.size - 1) * 0.95
        val lo = floor(rank).toInt()
        val p95 = slopes[lo] + (slopes[minOf(lo + 1, slopes.size - 1)] - slopes[lo]) * (rank - lo)
        assertEquals(725, slopes.size, "Saint-Nazaire slope window")
        assertEquals(0.059, p95, 0.0005, "Saint-Nazaire slope P95")

        val goulets = structures.single { it.kind == "tunnel" && "Grands Goulets" in it.name }
        assertEquals(599.75, goulets.z0, 0.01)
        assertEquals(680.46, goulets.z1, 0.01)
        assertEquals("undetected", goulets.flag)
    }

    // ── Matching vcyclist points to the truth ───────────────────────────────────────────────────

    /**
     * Monotone nearest-station matching: a cursor walks the truth, and each output point looks for
     * its nearest station in a window ahead of it. A global nearest neighbour would jump across
     * test2's self-crossings (Pont Général Audibert is ridden twice).
     */
    private class Matcher(
        private val t: Truth,
    ) {
        private val kx = cos(t.lat[0] * MathConstants.DEG_TO_RAD) * 111_320.0
        private val x = DoubleArray(t.size) { t.lon[it] * kx }
        private val y = DoubleArray(t.size) { t.lat[it] * 110_540.0 }
        private var cursor = 0
        var rejected = 0
            private set

        /** Fractional truth index of the point, or `null` when it is more than [MATCH_MAX_M] away. */
        fun match(
            latDeg: Double,
            lonDeg: Double,
        ): Double? {
            val px = lonDeg * kx
            val py = latDeg * 110_540.0
            var best = -1
            var bestD = Double.MAX_VALUE
            for (k in maxOf(0, cursor - 20)..minOf(t.size - 1, cursor + 200)) {
                val d = hypot(x[k] - px, y[k] - py)
                if (d < bestD) {
                    bestD = d
                    best = k
                }
            }
            if (bestD > MATCH_MAX_M) {
                rejected++
                return null
            }
            cursor = best
            // Project onto whichever neighbouring segment is closer.
            var f = best.toDouble()
            var fd = bestD
            for (a in listOf(best - 1, best)) {
                val b = a + 1
                if (a < 0 || b >= t.size) continue
                val dx = x[b] - x[a]
                val dy = y[b] - y[a]
                val len2 = dx * dx + dy * dy
                if (len2 < 1e-12) continue
                val u = (((px - x[a]) * dx + (py - y[a]) * dy) / len2).coerceIn(0.0, 1.0)
                val d = hypot(x[a] + u * dx - px, y[a] + u * dy - py)
                if (d < fd) {
                    fd = d
                    f = a + u
                }
            }
            return f
        }
    }

    private fun Truth.zAt(f: Double): Double {
        val a = floor(f).toInt().coerceIn(0, size - 1)
        val b = minOf(size - 1, a + 1)
        val u = f - a
        return z[a] * (1 - u) + z[b] * u
    }

    // ── Disk-cached tiles (the CLI's layout, inlined: `:cli` is not on this classpath) ──────────

    private val cacheDir: File by lazy {
        System.getenv("VCYCLIST_DEM_CACHE")?.let(::File)
            ?: File(dataDir!!.parentFile.parentFile, "build/dem-cache")
    }
    private val tileHits = AtomicInteger()
    private val tileMisses = AtomicInteger()
    private val bytesFetched = AtomicLong()

    private fun cachePath(
        url: String,
        encoding: TileEncoding,
    ): String {
        val noScheme = url.substringAfter("://")
        val query = noScheme.substringAfter('?', "")
        val segments =
            noScheme
                .substringBefore('?')
                .split('/')
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .map { it.replace(Regex("[^A-Za-z0-9._-]"), "_") }
        if (query.isEmpty()) return segments.joinToString("/")
        val hash = MessageDigest.getInstance("SHA-256").digest(query.encodeToByteArray())
        val hex = hash.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        return (segments + "$hex.${if (encoding == TileEncoding.BIL32) "bil" else "tile"}").joinToString("/")
    }

    private fun cachedFetcher(config: ElevationProviderConfig): suspend (String) -> RawTile {
        val network = pacedTileBytesFetcher(config.maxRequestsPerSecond, ::fetchTileBytes)
        val decode: suspend (ByteArray, String) -> RawTile = { bytes, url ->
            when (config.encoding) {
                TileEncoding.TERRARIUM -> decodeTileBytes(bytes, url)
                TileEncoding.BIL32 -> decodeBil32Tile(bytes, config.tileSize, url)
            }
        }
        return { url ->
            val file = File(cacheDir, cachePath(url, config.encoding))
            if (file.isFile && file.length() > 0) {
                tileHits.incrementAndGet()
                decode(file.readBytes(), url)
            } else {
                val bytes = network(url)
                tileMisses.incrementAndGet()
                bytesFetched.addAndGet(bytes.size.toLong())
                val tile = decode(bytes, url) // throws before a non-tile body reaches the disk
                file.parentFile.mkdirs()
                val tmp = Files.createTempFile(file.parentFile.toPath(), file.name, ".tmp")
                Files.write(tmp, bytes)
                Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                tile
            }
        }
    }

    // ── The bench ───────────────────────────────────────────────────────────────────────────────

    private class Run(
        val source: ElevationSource,
        val dense: Path,
        val full: Path,
        /** Full run with `simplifyPath` off: the reported D+ before `PathSimplifier`. */
        val unsimplified: Path,
        /** Fractional truth index per dense point, NaN where rejected. */
        val match: DoubleArray,
        val rejected: Int,
        /** Median of vcyclist - truth on open terrain: a diagnostic, never subtracted. */
        val openMedianM: Double,
    )

    private class Err(
        val n: Int,
        val maxErr: Double,
        val rms: Double,
    )

    private fun errors(
        run: Run,
        truth: Truth,
        inScope: (Int) -> Boolean,
        value: (Int) -> Double,
    ): Err {
        var n = 0
        var sum2 = 0.0
        var worst = 0.0
        for (i in 0 until run.dense.size) {
            val f = run.match[i]
            if (f.isNaN() || !inScope(f.roundToInt())) continue
            val e = value(i) - truth.zAt(f)
            n++
            sum2 += e * e
            if (abs(e) > abs(worst)) worst = e
        }
        return Err(n, worst, if (n == 0) Double.NaN else sqrt(sum2 / n))
    }

    /**
     * [z] (sampled at distances [d]) linearly resampled on the truth's 5 m grid, so that slope
     * counts and raw D+ compare like with like: a 1–2 m profile and a 5 m one do not sum the same
     * noise.
     */
    private fun grid5(
        d: DoubleArray,
        z: DoubleArray,
    ): DoubleArray {
        val n = (d.last() / STEP_M).toInt() + 1
        var j = 0
        return DoubleArray(n) { k ->
            val g = k * STEP_M
            while (j < d.size - 2 && d[j + 1] < g) j++
            val span = d[j + 1] - d[j]
            val u = if (span > 0) ((g - d[j]) / span).coerceIn(0.0, 1.0) else 0.0
            z[j] * (1 - u) + z[j + 1] * u
        }
    }

    /** Stations of a 5 m grid where the 50 m slope exceeds 15 %. */
    private fun steepStations(grid: DoubleArray): Int = slope50(grid).count { abs(it) > STEEP_GRADE }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    @Test
    fun bench() {
        if (!enabled || dataDir == null) {
            println("[bench] set INTEGRATION=1 (and run from the repository) to measure the E1 bench")
            return
        }
        val structures = readStructures()
        assertEquals(STRUCTURE_COUNTS.values.sum(), structures.size)
        println("[bench] tile cache: ${cacheDir.absolutePath}")
        val sources = listOf(ElevationSource.MAPTERHORN, ElevationSource.IGN_LIDAR_HD)
        val providers = sources.associateWith { it.provider(null) { cfg -> cachedFetcher(cfg) } }

        for ((route, rows) in ROUTES) {
            val truth = readTruth(route)
            assertEquals(rows, truth.size)
            val spans = structures.filter { it.route == route }
            val onStructure = BooleanArray(truth.size)
            spans.forEach { st -> for (k in st.i0..st.i1) onStructure[k] = true }
            val input = GpxParser.parse(File(dataDir, "$route.gpx").readText()).firstTrackAsPath()

            val runs =
                sources.map { source ->
                    val provider = providers.getValue(source)
                    val dense =
                        runBlocking {
                            var p = PointPerDistance.compute(input, minDistanceM = -1.0, maxDistanceM = 30.0)
                            p = ElevationStep.fixElevation(p, provider)
                            p = PointPerDistance.compute(p, minDistanceM = 1.0, maxDistanceM = 2.0)
                            ElevationStep.smoothElevation(p, EnhanceOptions.DEFAULT.elevationSmoothWindowM)
                        }
                    val full = runBlocking { Enhancer.enhanceCourseDefault(input, provider, EnhanceOptions.DEFAULT) }
                    val noSimplify = EnhanceOptions.DEFAULT.copy(simplifyPath = SimplifyPathOptions(enabled = false))
                    val unsimplified = runBlocking { Enhancer.enhanceCourseDefault(input, provider, noSimplify) }
                    val matcher = Matcher(truth)
                    val match =
                        DoubleArray(dense.size) { i ->
                            matcher.match(dense.latitudeDeg(i), dense.longitudeDeg(i)) ?: Double.NaN
                        }
                    val open =
                        (0 until dense.size).mapNotNull { i ->
                            val f = match[i]
                            if (f.isNaN()) return@mapNotNull null
                            val k = f.roundToInt()
                            if (truth.kind[k] != 'T' || onStructure[k]) null else dense.sourceElevation(i) - truth.zAt(f)
                        }
                    Run(
                        source = source,
                        dense = dense,
                        full = full,
                        unsimplified = unsimplified,
                        match = match,
                        rejected = matcher.rejected,
                        openMedianM = median(open),
                    )
                }
            report(route, truth, spans, onStructure, runs)
        }
        println()
        println(
            "[bench] tiles: %d from disk, %d fetched (%.1f MB)".fmt(
                tileHits.get(),
                tileMisses.get(),
                bytesFetched.get() / 1e6,
            ),
        )
    }

    private fun report(
        route: String,
        truth: Truth,
        spans: List<Structure>,
        onStructure: BooleanArray,
        runs: List<Run>,
    ) {
        val tag = "[bench]"
        println()
        println("$tag ═══ $route.gpx — %.2f km, %d truth stations".fmt(truth.s.last() / 1000, truth.size))
        val dem = ElevationGainOptions.DEFAULT
        val raw = ElevationGainOptions.RAW
        val truthDem = ElevationGain.compute(truth.s, truth.z, dem).gainM
        val truthRaw = ElevationGain.compute(truth.s, truth.z, raw).gainM
        val mntDem = ElevationGain.compute(truth.s, truth.mnt, dem).gainM
        val truthSteep = steepStations(truth.z)
        println(
            "$tag truth: D+ dem %.0f m, D+ raw %.0f m, %d steep stations | LiDAR MNT alone: D+ dem %.0f m, %d steep".fmt(
                truthDem,
                truthRaw,
                truthSteep,
                mntDem,
                steepStations(truth.mnt),
            ),
        )
        println(
            "$tag %-12s | %6s | %5s | %7s | %7s | %5s | %7s | %5s | %7s | %5s | %7s | %5s | %8s | %8s | %7s | %7s".fmt(
                "source",
                "pts",
                "rej",
                "med.op",
                "D+ rep.",
                "/true",
                "D+ 1Hz",
                "/true",
                "D+ dem",
                "/true",
                "D+ raw",
                "/true",
                "duration",
                "RMS open",
                ">15%src",
                ">15%ele",
            ),
        )
        for (r in runs) {
            val d = DoubleArray(r.dense.size) { r.dense.distance(it) }
            val src = DoubleArray(r.dense.size) { r.dense.sourceElevation(it) }
            val ele = DoubleArray(r.dense.size) { r.dense.elevation(it) }
            val reported = r.full.reportedElevationGain
            val oneHz = r.unsimplified.reportedElevationGain
            val src5 = grid5(d, src)
            val s5 = DoubleArray(src5.size) { it * STEP_M }
            val gridDem = ElevationGain.compute(s5, src5, dem).gainM
            val gridRaw = ElevationGain.compute(s5, src5, raw).gainM
            val openErr = errors(r, truth, { truth.kind[it] == 'T' && !onStructure[it] }) { src[it] }
            val secs = (r.full.durationMs / 1000).roundToInt()
            println(
                "$tag %-12s | %6d | %5d | %+7.2f | %7.0f | %5.2f | %7.0f | %5.2f | %7.0f | %5.2f | %7.0f | %5.2f | %2d:%02d:%02d | %8.2f | %7d | %7d"
                    .fmt(
                        r.source.id,
                        r.dense.size,
                        r.rejected,
                        r.openMedianM,
                        reported,
                        reported / truthDem,
                        oneHz,
                        oneHz / truthDem,
                        gridDem,
                        gridDem / truthDem,
                        gridRaw,
                        gridRaw / truthRaw,
                        secs / 3600,
                        secs / 60 % 60,
                        secs % 60,
                        openErr.rms,
                        steepStations(src5),
                        steepStations(grid5(d, ele)),
                    ),
            )
        }
        if (spans.isEmpty()) return
        println("$tag structures — err = vcyclist - truth (m), max is the signed worst; src = sourceElevation, ele = 150 m smoothed")
        val head =
            runs.joinToString(" | ") { r ->
                "%-34s".fmt("${r.source.id}: src max/rms  ele max/rms")
            }
        println("$tag %-8s %-28s | %13s | %6s | %7s | %s".fmt("kind", "name", "km", "L", "true max", head))
        for (st in spans) {
            val cells =
                runs.joinToString(" | ") { r ->
                    val scope: (Int) -> Boolean = { it in st.i0..st.i1 }
                    val es = errors(r, truth, scope) { r.dense.sourceElevation(it) }
                    val ee = errors(r, truth, scope) { r.dense.elevation(it) }
                    "%-34s".fmt("%+7.1f %5.1f   %+7.1f %5.1f".fmt(es.maxErr, es.rms, ee.maxErr, ee.rms))
                }
            println(
                "$tag %-8s %-28s | %6.2f–%6.2f | %6.0f | %7.1f | %s".fmt(
                    st.kind,
                    st.label,
                    st.s0 / 1000,
                    st.s1 / 1000,
                    st.s1 - st.s0 + STEP_M,
                    st.zmax,
                    cells,
                ),
            )
        }
    }

    private companion object {
        const val STEP_M = 5.0
        const val MATCH_MAX_M = 30.0
        const val STEEP_GRADE = 0.15

        /** Truth rows per route — the size self-check of the CSV reader. */
        val ROUTES = linkedMapOf("test1" to 2006, "test2" to 4911, "test3" to 2028, "test4" to 22251)

        /** structures.csv rows per kind — the size self-check of its reader. */
        val STRUCTURE_COUNTS = mapOf("bridge" to 21, "tunnel" to 4, "balcony" to 6, "missed" to 15)
    }
}

/** `String.format` in the root locale: the tables are pasted into docs, with decimal points. */
private fun String.fmt(vararg args: Any?): String = String.format(Locale.ROOT, this, *args)
