package io.github.glandais.engine.path

import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.ElevationSource
import io.github.glandais.elevation.IgnLidarHdLayer
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
 * MNT elsewhere, all NGF-IGN69 — so the E1 bench never calls Overpass or the MNS.
 *
 * Four tests:
 * - [truth_files_parse_and_hold_their_guards] runs on every `jvmTest`: it only reads the frozen
 *   files, checks their sizes (so a broken reader fails loudly instead of measuring nothing) and
 *   the spec's guards (Saint-Nazaire at 63.7 m and a 5.9 % slope P95, Grands Goulets 600 → 681 m).
 * - [deck_detector_reproduces_python_on_its_own_samples] runs on every `jvmTest` too: the E2
 *   [DeckDetector] on fetch.py's own MNT/MNS samples (frozen in `truth/`) must find exactly the
 *   bridges, rejections and canopy groups of `analysis/results.json`.
 * - [bench] (E1) and [deckDetector] (E2: the detector on vcyclist's own IGN LiDAR HD samples, MNH
 *   at z13/z14/z15, two-phase fetching, tile cost) are live HTTP and gated on `INTEGRATION=1`.
 *   Tiles are cached on disk under `build/dem-cache` at the repository root (or
 *   `$VCYCLIST_DEM_CACHE`; same layout as the CLI's `--cache`, so `~/.vcyclist/cache` works too).
 *   A cold E1 run fetches ≈ 190 IGN tiles (≈ 48 MB) and ≈ 20 Mapterhorn tiles (≈ 2 MB); E2 adds
 *   the MNS at z15 and the MNH at z13/z14/z15, 512 BIL tiles (≈ 134 MB). A warm run fetches
 *   nothing.
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

    // ── E2: the deck detector ──────────────────────────────────────────────────────────────────

    /** A minimal JSON reader for `analysis/results.json` (no JSON library on this classpath). */
    private class Json(
        private val s: String,
    ) {
        private var i = 0

        fun parse(): Any? {
            ws()
            return when (s[i]) {
                '{' -> {
                    i++
                    val m = LinkedHashMap<String, Any?>()
                    ws()
                    if (s[i] == '}') {
                        i++
                    } else {
                        while (true) {
                            ws()
                            val k = parse() as String
                            ws()
                            i++ // ':'
                            m[k] = parse()
                            ws()
                            if (s[i++] == '}') break
                        }
                    }
                    m
                }
                '[' -> {
                    i++
                    val l = ArrayList<Any?>()
                    ws()
                    if (s[i] == ']') {
                        i++
                    } else {
                        while (true) {
                            l += parse()
                            ws()
                            if (s[i++] == ']') break
                        }
                    }
                    l
                }
                '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (s[i] != '"') {
                        if (s[i] == '\\') {
                            i++
                            when (s[i]) {
                                'u' -> {
                                    sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                                    i += 4
                                }
                                'n' -> sb.append('\n')
                                't' -> sb.append('\t')
                                else -> sb.append(s[i])
                            }
                        } else {
                            sb.append(s[i])
                        }
                        i++
                    }
                    i++
                    sb.toString()
                }
                't' -> {
                    i += 4
                    true
                }
                'f' -> {
                    i += 5
                    false
                }
                'n' -> {
                    i += 4
                    null
                }
                else -> {
                    val st = i
                    while (i < s.length && s[i] in "+-0123456789.eEInfinityNa") i++
                    s.substring(st, i).toDouble()
                }
            }
        }

        private fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }

    /** One span of Python's `analyse()`: an accepted bridge or one of its `others`. */
    private class PySpan(
        val i0: Int,
        val i1: Int,
        val canyon: Double,
        val slope95: Double,
        val rough: Double,
        val underLow: Boolean,
        val accepted: Boolean,
    )

    private class PyRoute(
        val spans: List<PySpan>,
        val roughGroups: Int,
        val roughLengthM: Double,
    )

    @Suppress("UNCHECKED_CAST")
    private fun readPython(): Map<String, PyRoute> {
        val root = Json(File(dataDir, "analysis/results.json").readText()).parse() as Map<String, Map<String, Any?>>
        return ROUTES.keys.associateWith { route ->
            val r = root.getValue(route)

            fun spans(
                key: String,
                accepted: Boolean,
            ) = (r.getValue(key) as List<Map<String, Any?>>).map {
                PySpan(
                    i0 = (it.getValue("i0") as Double).toInt(),
                    i1 = (it.getValue("i1") as Double).toInt(),
                    canyon = it.getValue("canyon") as Double,
                    slope95 = it.getValue("slope95") as Double,
                    rough = it.getValue("rough") as Double,
                    underLow = it.getValue("under_low") as Boolean,
                    accepted = accepted,
                )
            }
            val rough = r.getValue("rough") as Map<String, Any?>
            PyRoute(
                spans = spans("bridges", true) + spans("others", false),
                roughGroups = (rough.getValue("n") as Double).toInt(),
                roughLengthM = rough.getValue("length") as Double,
            )
        }
    }

    /** Python's rejection, re-derived from its record with the same decision as `analyze.py`:74. */
    private fun PySpan.reason(): String =
        when {
            accepted -> "accepted"
            !underLow -> "NO_CANYON"
            i1 - i0 <= 5 -> "SHORT_ROUGH"
            else -> "STEEP"
        }

    /** Distinct tile URLs per (layer label, route), and disk/network counts per label. */
    private class TileLedger {
        val urls = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
        val hits = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val misses = java.util.concurrent.ConcurrentHashMap<String, Int>()
    }

    private val ledger = TileLedger()

    @Volatile private var ledgerKey = ""

    /** [cachedFetcher], recording every URL under the current [ledgerKey]. */
    private fun countingFetcher(config: ElevationProviderConfig): suspend (String) -> RawTile {
        val inner = cachedFetcher(config)
        return { url ->
            ledger.urls
                .computeIfAbsent(ledgerKey) {
                    java.util.concurrent.ConcurrentHashMap
                        .newKeySet()
                }.add(url)
            inner(url)
        }
    }

    /**
     * Samples [stations] with a fresh provider of [layer] at [zoom] (bilinear, `NaN` kept), sequentially
     * after every other layer so IGN's pacing budget is never shared.
     */
    private fun sampleLayer(
        route: String,
        label: String,
        stations: List<io.github.glandais.elevation.LatLon>,
        provider: io.github.glandais.elevation.ElevationProvider,
    ): DoubleArray {
        ledgerKey = "$label|$route"
        val h0 = tileHits.get()
        val m0 = tileMisses.get()
        val z = runBlocking { provider.setElevations(stations) }.map { it.elevation }.toDoubleArray()
        ledger.hits.merge(label, tileHits.get() - h0, Int::plus)
        ledger.misses.merge(label, tileMisses.get() - m0, Int::plus)
        return z
    }

    private fun windowed(
        z: DoubleArray,
        windows: List<IntRange>,
    ): DoubleArray {
        val out = DoubleArray(z.size) { Double.NaN }
        windows.forEach { w -> w.forEach { out[it] = z[it] } }
        return out
    }

    /**
     * [stations] moved half a pixel west and north at [zoom] (256 px). `ElevationCalculator`
     * interpolates with pixel *corners* on the grid (`toPixelFloat` has no `− 0.5`), whereas a WMS
     * GetMap pixel, and fetch.py's sampler, sit at pixel *centres*: sampling the shifted point with
     * the corner convention reads what a centre-convention sampler reads at the station.
     */
    private fun halfPixelShift(
        stations: List<io.github.glandais.elevation.LatLon>,
        zoom: Int,
    ): List<io.github.glandais.elevation.LatLon> {
        val world = (1 shl zoom) * 256.0
        return stations.map { p ->
            val lat = p.latitude * MathConstants.DEG_TO_RAD
            val x = (p.longitude + 180.0) / 360.0 * world - 0.5
            val y = (1.0 - kotlin.math.ln(kotlin.math.tan(lat) + 1.0 / cos(lat)) / Math.PI) / 2.0 * world - 0.5
            io.github.glandais.elevation.LatLon(
                kotlin.math.atan(kotlin.math.sinh(Math.PI * (1.0 - 2.0 * y / world))) / MathConstants.DEG_TO_RAD,
                x / world * 360.0 - 180.0,
            )
        }
    }

    private class Variant(
        val id: String,
        val label: String,
    )

    /** fetch.py's MNS sample at each station (`truth/<route>_mns.csv`, written by export_truth.py). */
    private fun readMns(route: String): DoubleArray {
        val lines = File(dataDir, "analysis/truth/${route}_mns.csv").readLines()
        val declared = Regex("""\bn=(\d+)""").find(lines.first())!!.groupValues[1].toInt()
        val values = lines.filter { it.isNotBlank() && !it.startsWith("#") && it != "mns" }.map { it.toDouble() }
        assertEquals(declared, values.size, "${route}_mns.csv: row count against its own header")
        return values.toDoubleArray()
    }

    /**
     * The port, on the analysis's own input: `analyze.py` ran its bridge detector on fetch.py's MNT
     * (the truth's `mnt` column) and MNS (`_mns.csv`), both frozen at the centimetre. On those
     * arrays [DeckDetector] must find exactly Python's 21 bridges, its 9 `others` with the same
     * verdict, and its canopy groups — the parity check that no live sampling can give, since
     * vcyclist's XYZ tiles are not fetch.py's grid. Runs on every `jvmTest`, no network.
     */
    @Test
    fun deck_detector_reproduces_python_on_its_own_samples() {
        assertNotNull(dataDir, "docs/deck-strava-data not found")
        val python = readPython()
        assertEquals(21, python.values.sumOf { r -> r.spans.count { it.accepted } }, "results.json bridges")
        assertEquals(9, python.values.sumOf { r -> r.spans.count { !it.accepted } }, "results.json others")
        for ((route, rows) in ROUTES) {
            val truth = readTruth(route)
            val mns = readMns(route)
            assertEquals(rows, mns.size, "${route}_mns.csv rows")
            val d = DeckDetector.detect(DeckProfile.fromSurface(mns, truth.mnt), DeckDetectorOptions.DEFAULT)
            val py = python.getValue(route)
            assertEquals(py.spans.filter { it.accepted }.map { it.i0..it.i1 }, d.accepted.map { it.first..it.last }, "$route accepted")
            val measured = d.rejected.filter { it.rejection != DeckRejection.ROUGH }
            assertEquals(
                py.spans.filter { !it.accepted }.map {
                    "${it.i0}..${it.i1} ${it.reason()}".let { k ->
                        BOUNDED_ABUTMENT["$route $k"] ?: k
                    }
                },
                measured.map { "${it.first}..${it.last} ${it.rejection}" },
                "$route others",
            )
            val rough = d.rejected.filter { it.rejection == DeckRejection.ROUGH }
            assertEquals(py.roughGroups, rough.size, "$route ROUGH groups")
            assertEquals(py.roughLengthM, rough.sumOf { (it.last - it.first + 1) * STEP_M }, 1e-9, "$route ROUGH length")
            for (sp in py.spans) {
                if ("$route ${sp.i0}..${sp.i1} ${sp.reason()}" in BOUNDED_ABUTMENT) continue
                val m = (d.accepted + measured).single { it.first == sp.i0 && it.last == sp.i1 }.metrics!!
                // Centimetre inputs against Python's float64 ones: metrics agree to a few centimetres.
                if (sp.slope95 < 99.0) assertEquals(sp.slope95, m.slopeP95 * 100, 0.05, "$route ${sp.i0} slope P95 %")
                assertEquals(sp.rough, m.roughnessM, 0.05, "$route ${sp.i0} roughness")
                assertEquals(sp.canyon, m.canyonM, 0.05, "$route ${sp.i0} canyon")
            }
        }
    }

    @Test
    fun deckDetector() {
        if (!enabled || dataDir == null) {
            println("[decks] set INTEGRATION=1 (and run from the repository) to measure the E2 deck detector")
            return
        }
        val structures = readStructures()
        assertEquals(STRUCTURE_COUNTS, structures.groupingBy { it.kind }.eachCount())
        val python = readPython()
        assertEquals(21, python.values.sumOf { r -> r.spans.count { it.accepted } }, "results.json bridges")
        assertEquals(9, python.values.sumOf { r -> r.spans.count { !it.accepted } }, "results.json others")
        val opts = DeckDetectorOptions.DEFAULT
        println("[decks] tile cache: ${cacheDir.absolutePath}")
        println("[decks] options: ${opts.toString().substringAfter('(').dropLast(1)}")

        val layers: List<Pair<String, () -> io.github.glandais.elevation.ElevationProvider>> =
            listOf(
                "MNT z15" to { IgnLidarHdLayer.MNT.provider(15) { countingFetcher(it) } },
                "MNS z15" to { IgnLidarHdLayer.MNS.provider(15) { countingFetcher(it) } },
                "MNH z15" to { IgnLidarHdLayer.MNH.provider(15) { countingFetcher(it) } },
                "MNH z14" to { IgnLidarHdLayer.MNH.provider(14) { countingFetcher(it) } },
                "MNH z13" to { IgnLidarHdLayer.MNH.provider(13) { countingFetcher(it) } },
                "DEM ign-lidar-hd" to { ElevationSource.IGN_LIDAR_HD.provider(null) { countingFetcher(it) } },
                "DEM mapterhorn" to { ElevationSource.MAPTERHORN.provider(null) { countingFetcher(it) } },
            )
        val variants =
            listOf(
                Variant("py", "python's own samples"),
                Variant("a", "MNS-MNT z15 (parity)"),
                Variant("a½", "idem, pixel centres"),
                Variant("b", "MNH z15"),
                Variant("c", "MNH z14"),
                Variant("d", "MNH z13"),
                Variant("e14", "2-phase MNH z14"),
                Variant("e13", "2-phase MNH z13"),
                Variant("f-ign", "MNS - DEM ign"),
                Variant("f-mth", "MNS - DEM mapterhorn"),
                Variant("f-mthc", "canyon on mapterhorn"),
            )
        // variant -> route -> detection
        val results = variants.associate { it.id to LinkedHashMap<String, DeckDetection>() }
        val profiles = HashMap<String, DeckProfile>()
        val twoPhaseSame = LinkedHashMap<String, Boolean>()
        val windowStations = LinkedHashMap<String, Int>()
        val wallMs = LinkedHashMap<String, Double>()

        for ((route, rows) in ROUTES) {
            val truth = readTruth(route)
            assertEquals(rows, truth.size)
            val stations =
                (0 until truth.size).map {
                    io.github.glandais.elevation
                        .LatLon(truth.lat[it], truth.lon[it])
                }
            // A fresh provider per layer and route: seven 100-tile caches at once do not fit the test heap.
            val z = layers.associate { (label, p) -> label to sampleLayer(route, label, stations, p()) }
            val mnt = z.getValue("MNT z15")
            val mns = z.getValue("MNS z15")
            val parity = DeckProfile.fromSurface(mns, mnt)
            run {
                val mnh15 = z.getValue("MNH z15")
                val diff = (0 until truth.size).map { abs(mnh15[it] - parity.mnhM[it]) }.filter { !it.isNaN() }
                println(
                    "[decks] %s: MNH layer z15 against MNS − MNT z15: max |Δ| %.3f m, %d stations differ by > 0.1 m; NaN MNT %d, MNS %d, MNH %d"
                        .fmt(
                            route,
                            diff.maxOrNull() ?: Double.NaN,
                            diff.count { it > 0.1 },
                            mnt.count { it.isNaN() },
                            mns.count { it.isNaN() },
                            mnh15.count { it.isNaN() },
                        ),
                )
            }
            profiles[route] = parity
            val byVariant = LinkedHashMap<String, DeckProfile>()
            byVariant["py"] = DeckProfile.fromSurface(readMns(route), truth.mnt)
            byVariant["a"] = parity
            run {
                val shifted = halfPixelShift(stations, 15)
                val mntC = sampleLayer(route, "MNT z15 (centres)", shifted, IgnLidarHdLayer.MNT.provider(15) { countingFetcher(it) })
                val mnsC = sampleLayer(route, "MNS z15 (centres)", shifted, IgnLidarHdLayer.MNS.provider(15) { countingFetcher(it) })
                byVariant["a½"] = DeckProfile.fromSurface(mnsC, mntC)
            }
            byVariant["b"] = DeckProfile(z.getValue("MNH z15"), mns, mnt)
            byVariant["c"] = DeckProfile(z.getValue("MNH z14"), mns, mnt)
            byVariant["d"] = DeckProfile(z.getValue("MNH z13"), mns, mnt)
            for ((id, zoom) in listOf("e14" to 14, "e13" to 13)) {
                val mnh = z.getValue("MNH z$zoom")
                val windows = DeckDetector.surfaceWindows(mnh, opts)
                val inside = windows.sumOf { it.last - it.first + 1 }
                windowStations["$id|$route"] = inside
                val windowProvider = IgnLidarHdLayer.MNS.provider(15) { countingFetcher(it) }
                val windowIdx = windows.flatMap { it.toList() }
                val sampled = sampleLayer(route, "MNS z15 in z$zoom windows", windowIdx.map { stations[it] }, windowProvider)
                val mnsW = DoubleArray(truth.size) { Double.NaN }
                windowIdx.forEachIndexed { j, k -> mnsW[k] = sampled[j] }
                byVariant[id] = DeckProfile(mnh, mnsW, windowed(mnt, windows))
            }
            byVariant["f-ign"] = DeckProfile.fromSurface(mns, z.getValue("DEM ign-lidar-hd"))
            byVariant["f-mth"] = DeckProfile.fromSurface(mns, z.getValue("DEM mapterhorn"))
            // MNH from the LiDAR MNT, canyon test on Mapterhorn's: isolates what the canyon alone loses.
            byVariant["f-mthc"] = DeckProfile(parity.mnhM, mns, z.getValue("DEM mapterhorn"))
            for ((id, p) in byVariant) results.getValue(id)[route] = DeckDetector.detect(p, opts)
            twoPhaseSame["e14|$route"] = results.getValue("e14")[route].toString() == results.getValue("c")[route].toString()
            twoPhaseSame["e13|$route"] = results.getValue("e13")[route].toString() == results.getValue("d")[route].toString()
            val t0 = System.nanoTime()
            repeat(5) { DeckDetector.detect(parity, opts) }
            wallMs[route] = (System.nanoTime() - t0) / 5e6
            // (e) must equal the full-MNS run on the same MNH; printed below, asserted here.
        }

        val bridges = structures.filter { it.kind == "bridge" }
        val missed = structures.filter { it.kind == "missed" }
        val elsewhere = structures.filter { it.kind == "tunnel" || it.kind == "balcony" }

        fun overlaps(
            c: DeckCandidate,
            st: Structure,
        ) = c.first <= st.i1 && c.last >= st.i0

        /** Stations of [c] on [st]. */
        fun shared(
            c: DeckCandidate,
            st: Structure,
        ) = maxOf(0, minOf(c.last, st.i1) - maxOf(c.first, st.i0) + 1)

        /**
         * A missed OSM bridge counts as found only when the accepted span lies mostly on it: an
         * over-extended detection that brushes it (z13: 145 stations for D 70's 10) does not.
         */
        fun mostlyOn(
            c: DeckCandidate,
            st: Structure,
        ) = 2 * shared(c, st) >= c.last - c.first + 1

        /** A candidate explains a structure when one of its tall stations lies on it, not a shared abutment. */
        fun attributable(
            c: DeckCandidate,
            st: Structure,
            p: DeckProfile,
        ) = (maxOf(c.first, st.i0)..minOf(c.last, st.i1)).any { p.mnhM[it] > opts.tallMnhM }

        // ── Detections of the parity variant, route by route ──────────────────────────────────
        val a = results.getValue("a")
        for (route in ROUTES.keys) {
            val d = a.getValue(route)
            val p = profiles.getValue(route)
            val spans = structures.filter { it.route == route }
            println()
            println("[decks] ═══ $route — variant (a) MNS−MNT z15, detector %.1f ms".fmt(wallMs.getValue(route)))
            println(
                "[decks] %-13s | %5s | %6s | %6s | %6s | %6s | %6s | %-11s | %s".fmt(
                    "km",
                    "L m",
                    "MNHmax",
                    "rough",
                    "P95 %",
                    "canyon",
                    "drop",
                    "verdict",
                    "structure (truth)",
                ),
            )
            for (c in (d.accepted + d.rejected.filter { it.rejection != DeckRejection.ROUGH }).sortedBy { it.first }) {
                val m = c.metrics!!
                val mnhMax = (c.first..c.last).maxOf { k -> p.mnhM[k].takeUnless { it.isNaN() } ?: Double.NEGATIVE_INFINITY }
                val st = spans.filter { attributable(c, it, p) }.joinToString("; ") { "${it.kind} ${it.label}" }.ifEmpty { "—" }
                println(
                    "[decks] %6.2f–%6.2f | %5.0f | %6.1f | %6.2f | %6.1f | %6.1f | %6.1f | %-11s | %s".fmt(
                        c.startM / 1000,
                        c.endM / 1000,
                        m.lengthM,
                        mnhMax,
                        m.roughnessM,
                        m.slopeP95 * 100,
                        m.canyonM,
                        m.mntDropM,
                        c.rejection?.name ?: "DECK",
                        st,
                    ),
                )
            }
            val rough = d.rejected.filter { it.rejection == DeckRejection.ROUGH }
            val py = python.getValue(route)
            println(
                "[decks] ROUGH groups: %d / %.0f m (python %d / %.0f m); tall runs < 3 stations: %d".fmt(
                    rough.size,
                    rough.sumOf { (it.last - it.first + 1) * opts.stepM },
                    py.roughGroups,
                    py.roughLengthM,
                    d.tallRunsTooShort,
                ),
            )
        }

        // ── Per bridge: endpoints and metrics against Python, verdict per variant ─────────────
        println()
        println("[decks] bridges (truth = analyze.py): Δ in m of variant (a) against python i0/i1; metrics (a) / python")
        println(
            "[decks] %-5s %-28s | %13s | %5s %5s | %6s %6s | %5s %5s | %5s %5s | %s".fmt(
                "route",
                "name",
                "km",
                "Δfrst",
                "Δlast",
                "P95 %",
                "py",
                "rough",
                "py",
                "canyn",
                "py",
                variants.joinToString(" ") { "%-6s".fmt(it.id) },
            ),
        )
        for (st in bridges) {
            val pySpan = python.getValue(st.route).spans.single { it.accepted && it.i0 == st.i0 }
            val mine =
                a.getValue(st.route).let { it.accepted + it.rejected }.filter { overlaps(it, st) }.maxByOrNull {
                    minOf(it.last, st.i1) -
                        maxOf(it.first, st.i0)
                }
            val m = mine?.metrics
            val verdicts =
                variants.joinToString(" ") { v ->
                    val d = results.getValue(v.id).getValue(st.route)
                    val hit = d.accepted.any { overlaps(it, st) }
                    val why = d.rejected.filter { overlaps(it, st) }.joinToString("/") { it.rejection!!.name.take(6) }
                    "%-6s".fmt(if (hit) "ok" else why.ifEmpty { "none" })
                }
            println(
                "[decks] %-5s %-28s | %6.2f–%6.2f | %5s %5s | %6.2f %6.2f | %5.2f %5.2f | %5.1f %5.1f | %s".fmt(
                    st.route,
                    st.label,
                    st.s0 / 1000,
                    st.s1 / 1000,
                    mine?.let { "%+.0f".fmt((it.first - st.i0) * opts.stepM) } ?: "-",
                    mine?.let { "%+.0f".fmt((it.last - st.i1) * opts.stepM) } ?: "-",
                    (m?.slopeP95 ?: Double.NaN) * 100,
                    if (pySpan.slope95 >= 99.0) Double.NaN else pySpan.slope95,
                    m?.roughnessM ?: Double.NaN,
                    pySpan.rough,
                    m?.canyonM ?: Double.NaN,
                    pySpan.canyon,
                    verdicts,
                ),
            )
        }

        // ── Missed OSM bridges: why ───────────────────────────────────────────────────────────
        println()
        println("[decks] the 15 OSM bridges python missed — variant (a)")
        for (st in missed) {
            val d = a.getValue(st.route)
            val p = profiles.getValue(st.route)
            val hit = d.accepted.any { mostlyOn(it, st) }
            val why = d.rejected.filter { attributable(it, st, p) }.joinToString("/") { it.rejection!!.name }
            val mnhMax = (st.i0..st.i1).maxOf { p.mnhM[it] }
            var longestTall = 0
            var run = 0
            for (k in st.i0..st.i1) {
                run = if (p.mnhM[k] > opts.tallMnhM) run + 1 else 0
                longestTall = maxOf(longestTall, run)
            }
            println(
                "[decks] %-5s %-28s | %6.2f–%6.2f | MNH max %5.1f (python %s), longest tall run %2d | %s".fmt(
                    st.route,
                    st.label,
                    st.s0 / 1000,
                    st.s1 / 1000,
                    mnhMax,
                    st.flag.removePrefix("mnh="),
                    longestTall,
                    if (hit) "FOUND" else why.ifEmpty { "no tall run" },
                ),
            )
        }

        // ── Summary per variant ──────────────────────────────────────────────────────────────
        println()
        println(
            "[decks] summary per variant: recall on 21 bridges (t1/t2/t3/t4, any overlap), on 15 missed (span mostly on it), " +
                "false positives (no overlap), accepted span / deck-mask stations outside every bridge and missed span, rejections",
        )
        println(
            "[decks] %-22s | %-15s | %6s | %3s | %-11s | %-26s | %-16s | %s".fmt(
                "variant",
                "bridges",
                "missed",
                "FP",
                "out st/deck",
                "NO_CANYON/STEEP/SHORT/NODATA",
                "ROUGH n / km",
                "parity vs python",
            ),
        )
        val fpByVariant = LinkedHashMap<String, List<Pair<String, DeckCandidate>>>()
        for (v in variants) {
            val r = results.getValue(v.id)
            val perRoute =
                ROUTES.keys.joinToString("/") { route ->
                    bridges.filter { it.route == route }.count { st -> r.getValue(route).accepted.any { overlaps(it, st) } }.toString()
                }
            val found = bridges.count { st -> r.getValue(st.route).accepted.any { overlaps(it, st) } }
            val foundMissed = missed.count { st -> r.getValue(st.route).accepted.any { mostlyOn(it, st) } }
            var outSpan = 0
            var outDeck = 0
            for (route in ROUTES.keys) {
                val onTruth = BooleanArray(ROUTES.getValue(route))
                (bridges + missed).filter { it.route == route }.forEach { st -> for (k in st.i0..st.i1) onTruth[k] = true }
                for (c in r.getValue(route).accepted) {
                    outSpan += (c.first..c.last).count { !onTruth[it] }
                    outDeck += c.deckRanges.sumOf { d -> d.count { !onTruth[it] } }
                }
            }
            val fps =
                ROUTES.keys.flatMap { route ->
                    r.getValue(route).accepted.filter { c -> (bridges + missed).none { it.route == route && overlaps(c, it) } }.map {
                        route to
                            it
                    }
                }
            fpByVariant[v.id] = fps
            val all = r.values.flatMap { it.rejected }

            fun count(x: DeckRejection) = all.count { it.rejection == x }
            val rough = all.filter { it.rejection == DeckRejection.ROUGH }
            // Parity: python's accepted spans matched one-to-one, endpoint deltas, verdict flips.
            var maxDelta = 0
            var flips = 0
            for (route in ROUTES.keys) {
                val d = r.getValue(route)
                for (sp in python.getValue(route).spans) {
                    val mine =
                        (d.accepted + d.rejected).filter {
                            it.first <= sp.i1 &&
                                it.last >= sp.i0 &&
                                it.rejection != DeckRejection.ROUGH
                        }
                    val accepted = d.accepted.any { it.first <= sp.i1 && it.last >= sp.i0 }
                    if (accepted != sp.accepted) flips++
                    mine.singleOrNull()?.let { maxDelta = maxOf(maxDelta, abs(it.first - sp.i0), abs(it.last - sp.i1)) }
                }
                flips += d.accepted.count { c -> python.getValue(route).spans.none { c.first <= it.i1 && c.last >= it.i0 } }
            }
            val pyAccepted = python.values.sumOf { p -> p.spans.count { it.accepted } }
            val sameSet = flips == 0 && r.values.sumOf { it.accepted.size } == pyAccepted
            println(
                "[decks] %-22s | %2d/21 %-9s | %2d/15  | %3d | %4d / %4d | %3d / %3d / %3d / %3d       | %3d / %5.2f     | same set %s, max |Δ| %d st, flips %d"
                    .fmt(
                        "(${v.id}) ${v.label}",
                        found,
                        "($perRoute)",
                        foundMissed,
                        fps.size,
                        outSpan,
                        outDeck,
                        count(DeckRejection.NO_CANYON),
                        count(DeckRejection.STEEP),
                        count(DeckRejection.SHORT_ROUGH),
                        count(DeckRejection.NO_DATA),
                        rough.size,
                        rough.sumOf { (it.last - it.first + 1) * opts.stepM } / 1000,
                        if (sameSet) "yes" else "no",
                        maxDelta,
                        flips,
                    ),
            )
        }
        val pyOthers =
            python.values
                .flatMap { it.spans.filter { s -> !s.accepted } }
                .groupingBy { it.reason() }
                .eachCount()
        println(
            "[decks] %-22s | 21/21 (3/16/1/1) |  0/15  |   0 |             | %3d / %3d / %3d / %3d       | %3d / %5.2f     |".fmt(
                "python (results.json)",
                pyOthers["NO_CANYON"] ?: 0,
                pyOthers["STEEP"] ?: 0,
                pyOthers["SHORT_ROUGH"] ?: 0,
                0,
                python.values.sumOf { it.roughGroups },
                python.values.sumOf { it.roughLengthM } / 1000,
            ),
        )
        for ((id, fps) in fpByVariant) {
            for ((route, c) in fps) {
                val inStructure = elsewhere.filter { it.route == route && overlaps(c, it) }.joinToString { "${it.kind} ${it.label}" }
                println(
                    "[decks]   FP (%s) %s km %.2f–%.2f, L %.0f m, P95 %.1f %%, rough %.2f, canyon %.1f%s".fmt(
                        id,
                        route,
                        c.startM / 1000,
                        c.endM / 1000,
                        c.metrics!!.lengthM,
                        c.metrics!!.slopeP95 * 100,
                        c.metrics!!.roughnessM,
                        c.metrics!!.canyonM,
                        if (inStructure.isEmpty()) "" else " — inside $inStructure",
                    ),
                )
            }
        }

        // ── Deck mask against the truth's B / b ──────────────────────────────────────────────
        println()
        for (id in listOf("py", "a")) {
            var agree = 0
            var total = 0
            for (route in ROUTES.keys) {
                val truth = readTruth(route)
                val deck = BooleanArray(truth.size)
                results
                    .getValue(id)
                    .getValue(route)
                    .accepted
                    .forEach { c -> c.deckRanges.forEach { r -> r.forEach { deck[it] = true } } }
                for (st in bridges.filter { it.route == route }) {
                    for (k in st.i0..st.i1) {
                        total++
                        if (deck[k] == (truth.kind[k] == 'B')) agree++
                    }
                }
            }
            println(
                "[decks] deck mask (%s) vs truth kind B/b on the 21 bridge spans: %d / %d stations agree (%.1f %%)".fmt(
                    id,
                    agree,
                    total,
                    100.0 * agree / total,
                ),
            )
        }

        // ── Two-phase ────────────────────────────────────────────────────────────────────────
        println()
        println("[decks] two-phase (MNS fetched in surfaceWindows(MNH) only): windows stations / route stations, same as full MNS")
        for ((key, same) in twoPhaseSame) {
            val (id, route) = key.split('|')
            println(
                "[decks]   (%s) %s: %d / %d stations, detection identical to the full-MNS run: %s".fmt(
                    id,
                    route,
                    windowStations.getValue(key),
                    ROUTES.getValue(route),
                    same,
                ),
            )
        }

        // ── Tile cost ────────────────────────────────────────────────────────────────────────
        println()
        println("[decks] tile cost: distinct tiles per route (BIL 256² × 4 B = 0.262 MB each; Mapterhorn WebP sized on disk)")
        val labels =
            ledger.urls.keys
                .map { it.substringBefore('|') }
                .distinct()
        println(
            "[decks] %-26s | %s | %6s | %7s | %5s | %7s".fmt(
                "layer",
                ROUTES.keys.joinToString(" | ") {
                    "%5s".fmt(it)
                },
                "total",
                "MB",
                "disk",
                "fetched",
            ),
        )
        for (label in labels) {
            val per = ROUTES.keys.map { route -> ledger.urls["$label|$route"]?.size ?: 0 }
            val all = ROUTES.keys.flatMap { route -> ledger.urls["$label|$route"].orEmpty() }.toSet()
            val mb =
                all.sumOf { url ->
                    val enc = if ("geopf" in url) TileEncoding.BIL32 else TileEncoding.TERRARIUM
                    File(cacheDir, cachePath(url, enc)).length()
                } / 1e6
            println(
                "[decks] %-26s | %s | %6d | %7.1f | %5d | %7d".fmt(
                    label,
                    per.joinToString(" | ") { "%5d".fmt(it) },
                    all.size,
                    mb,
                    ledger.hits[label] ?: 0,
                    ledger.misses[label] ?: 0,
                ),
            )
        }
        println(
            "[decks] tiles: %d from disk, %d fetched (%.1f MB) in this test".fmt(
                tileHits.get(),
                tileMisses.get(),
                bytesFetched.get() / 1e6,
            ),
        )
        println(
            "[decks] not judged: the spec's acceptance criterion (zero detections on forest and urban witnesses) needs negative witness routes, which the bench does not have yet (E1, « à ajouter »)",
        )

        // Hard checks. Recall 21/21 is pinned on Python's own samples, offline, by
        // deck_detector_reproduces_python_on_its_own_samples — and re-checked here as (py). On
        // vcyclist's own z15 samples (a) it is a measurement, not a contract: the XYZ grid is not
        // fetch.py's, and narrow decks fall between its pixels.
        val pyFound =
            bridges.count { st ->
                results
                    .getValue("py")
                    .getValue(st.route)
                    .accepted
                    .any { overlaps(it, st) }
            }
        assertEquals(21, pyFound, "variant (py) recall on the 21 python bridges")
        assertEquals(0, fpByVariant.getValue("py").size, "variant (py) false positives")
        assertEquals(0, fpByVariant.getValue("a").size, "variant (a) false positives")
        twoPhaseSame.forEach { (key, same) -> assertTrue(same, "two-phase $key differs from the full-MNS run") }
    }

    private companion object {
        const val STEP_M = 5.0
        const val MATCH_MAX_M = 30.0
        const val STEEP_GRADE = 0.15

        /** Truth rows per route — the size self-check of the CSV reader. */
        val ROUTES = linkedMapOf("test1" to 2006, "test2" to 4911, "test3" to 2028, "test4" to 22251)

        /**
         * The one span where [DeckDetector] knowingly parts from analyze.py on its own samples: Combe
         * Laval (`test4` km 68.5, a cliff road, not a bridge), whose `MNH > 1` extension runs 29
         * stations past its tall core. `maxAbutmentM` (100 m) ends it at the core, which turns
         * Python's `STEEP` into `NO_CANYON`. Still rejected; no accepted span moves.
         */
        val BOUNDED_ABUTMENT = mapOf("test4 13703..13745 STEEP" to "13703..13716 NO_CANYON")

        /** structures.csv rows per kind — the size self-check of its reader. */
        val STRUCTURE_COUNTS = mapOf("bridge" to 21, "tunnel" to 4, "balcony" to 6, "missed" to 15)
    }
}

/** `String.format` in the root locale: the tables are pasted into docs, with decimal points. */
private fun String.fmt(vararg args: Any?): String = String.format(Locale.ROOT, this, *args)
