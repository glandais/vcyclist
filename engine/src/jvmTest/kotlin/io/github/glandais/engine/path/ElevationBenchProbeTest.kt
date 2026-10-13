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
 * Nine tests:
 * - [truth_files_parse_and_hold_their_guards] runs on every `jvmTest`: it only reads the frozen
 *   files, checks their sizes (so a broken reader fails loudly instead of measuring nothing) and
 *   the spec's guards (Saint-Nazaire at 63.7 m and a 5.9 % slope P95, Grands Goulets 600 → 681 m).
 * - [deck_detector_reproduces_python_on_its_own_samples] runs on every `jvmTest` too: the E2
 *   [DeckDetector] on fetch.py's own MNT/MNS samples (frozen in `truth/`) must find exactly the
 *   bridges, rejections and canopy groups of `analysis/results.json`.
 * - [deck_controls_parse_and_hold_their_guards] runs on every `jvmTest` too (E2 bis): the negative
 *   controls and held-out positives of `analysis/controls.py` (`controls/<id>.gpx`,
 *   `analysis/truth/controls/`) parse, keep their pinned counts, and their GPX matches their truth.
 * - [bench] (E1), [deckDetector] (E2: the detector on vcyclist's own IGN LiDAR HD samples, MNH
 *   at z13/z14/z15, two-phase fetching, tile cost) and [deckControls] (E2 bis: the detector on the
 *   controls, and a threshold sensitivity sweep) are live HTTP and gated on `INTEGRATION=1`.
 *   Tiles are cached on disk under `build/dem-cache` at the repository root (or
 *   `$VCYCLIST_DEM_CACHE`; same layout as the CLI's `--cache`, so `~/.vcyclist/cache` works too).
 *   A cold E1 run fetches ≈ 190 IGN tiles (≈ 48 MB) and ≈ 20 Mapterhorn tiles (≈ 2 MB); E2 adds
 *   the MNS at z15 and the MNH at z13/z14/z15, 512 BIL tiles (≈ 134 MB); E2 bis adds MNT and MNS
 *   z15 for the 20 controls, 178 BIL tiles (≈ 47 MB). A warm run fetches nothing.
 * - [deckHoldoutJudge] (E2 quinquies) is the only reader of the second held-out series `p09..`
 *   (`holdout2`): gated on `INTEGRATION=1` **and** `DECK_JUDGE=1`, so a plain `INTEGRATION=1` run
 *   of this class never measures it. Print-only; a failure there is reported, never tuned. Since
 *   E3 it also prints the deck profile of every region it accepts.
 * - E3 (the deck step, [BridgeDeckStep]): [bench] gains the `ign -decks` column (E1's
 *   `ign-lidar-hd`, surface model detached) beside `ign-lidar-hd`, which now replaces decks;
 *   [deckProfiles] compares the profile variants of the E3 plan region by region on the Strava
 *   routes and `p01..p08`; [deckSampleGpx] runs `sample.gpx` with and without decks. All three are
 *   `INTEGRATION=1`; the MNS adds one tile per MNT tile, from the same disk cache.
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
     * Windowed nearest-station matching: a cursor walks the truth, and each output point looks for
     * its nearest station from 20 stations behind the cursor to 200 ahead. A global nearest
     * neighbour would jump across test2's self-crossings (Pont Général Audibert is ridden twice).
     * **Not monotone**: the cursor can step back, so where the route passes back within metres of
     * itself (test2 near Pont de Bellevue, km 1.00 / 1.22) points of one passage can be matched to
     * stations of the other.
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
        /** The column: `mapterhorn`, `ign -decks` (E1's `ign-lidar-hd`) or `ign-lidar-hd` (E3). */
        val label: String,
        val dense: Path,
        /** D+ reported by a full `EnhanceOptions.DEFAULT` run. */
        val reportedGainM: Double,
        /** Same, `simplifyPath` off: the reported D+ before `PathSimplifier`. */
        val oneHzGainM: Double,
        /** Simulated duration of the full run. */
        val durationMs: Double,
        /** Fractional truth index per dense point, NaN where rejected. */
        val match: DoubleArray,
        val rejected: Int,
        /** Median of vcyclist - truth on open terrain: a diagnostic, never subtracted. */
        val openMedianM: Double,
        /** Step 3b's status, `null` when the provider carries no surface model. */
        val deckStatus: BridgeDeckStatus?,
        val replacements: List<DeckReplacement>,
        val dropped: Int,
    ) {
        /** Whether the deck step's write-back range covers distance [d]. */
        fun replaced(d: Double): Boolean =
            replacements.any { rep ->
                (!rep.pinnedStart || d >= rep.first * STEP_M) && (!rep.pinnedEnd || d <= rep.last * STEP_M)
            }
    }

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
        val ign = { ElevationSource.IGN_LIDAR_HD.provider(null) { cfg -> cachedFetcher(cfg) } }
        assertNotNull(ign().surfaceModel, "ign-lidar-hd at its default zoom carries the MNS")
        // E1's columns, then E3's: `ign -decks` is E1's `ign-lidar-hd`, the surface model detached.
        // Built afresh for each run: three providers' 100-tile caches at once exhaust the test heap
        // on test4, and the tiles come from disk anyway.
        val providers: Map<String, () -> io.github.glandais.elevation.ElevationProvider> =
            linkedMapOf(
                "mapterhorn" to { ElevationSource.MAPTERHORN.provider(null) { cfg -> cachedFetcher(cfg) } },
                "ign -decks" to { ign().withSurfaceModel(null) },
                "ign-lidar-hd" to ign,
            )
        var regions = 0
        val replacedBridges = ArrayList<String>()
        val notReplaced = ArrayList<String>()
        val bellevueRegions = ArrayList<Int>()
        val targets = ArrayList<Pair<String, Boolean>>()
        val targetLines = ArrayList<String>()

        for ((route, rows) in ROUTES) {
            val truth = readTruth(route)
            assertEquals(rows, truth.size)
            val spans = structures.filter { it.route == route }
            val onStructure = BooleanArray(truth.size)
            spans.forEach { st -> for (k in st.i0..st.i1) onStructure[k] = true }
            val input = GpxParser.parse(File(dataDir, "$route.gpx").readText()).firstTrackAsPath()

            val runs =
                providers.map { (label, newProvider) ->
                    val provider = newProvider()
                    var deck: BridgeDeckResult? = null
                    val dense =
                        runBlocking {
                            var p = PointPerDistance.compute(input, minDistanceM = -1.0, maxDistanceM = 30.0)
                            p = ElevationStep.fixElevation(p, provider)
                            p = PointPerDistance.compute(p, minDistanceM = 1.0, maxDistanceM = 2.0)
                            // Step 3b, as Enhancer runs it.
                            provider.surfaceModel?.let { surface ->
                                val result = BridgeDeckStep.apply(p, provider, surface)
                                // It moves nothing outside its stretches, bit for bit.
                                for (i in 0 until p.size) {
                                    val d = p.distance(i)
                                    val inside =
                                        result.replacements.any { rep ->
                                            (!rep.pinnedStart || d >= rep.first * STEP_M) && (!rep.pinnedEnd || d <= rep.last * STEP_M)
                                        }
                                    if (!inside) {
                                        assertEquals(
                                            p.elevation(i).toRawBits(),
                                            result.path.elevation(i).toRawBits(),
                                            "$route: point $i moved",
                                        )
                                    }
                                }
                                deck = result
                                p = result.path
                            }
                            ElevationStep.smoothElevation(p, EnhanceOptions.DEFAULT.elevationSmoothWindowM)
                        }
                    val full = runBlocking { Enhancer.enhanceCourseDefault(input, provider, EnhanceOptions.DEFAULT) }
                    val noSimplify = EnhanceOptions.DEFAULT.copy(simplifyPath = SimplifyPathOptions(enabled = false))
                    val oneHzGain = runBlocking { Enhancer.enhanceCourseDefault(input, provider, noSimplify) }.reportedElevationGain
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
                        label = label,
                        dense = dense,
                        reportedGainM = full.reportedElevationGain,
                        oneHzGainM = oneHzGain,
                        durationMs = full.durationMs,
                        match = match,
                        rejected = matcher.rejected,
                        openMedianM = median(open),
                        deckStatus = deck?.status,
                        replacements = deck?.replacements ?: emptyList(),
                        dropped = deck?.dropped?.size ?: 0,
                    )
                }
            report(route, truth, spans, onStructure, runs)
            val withDecks = runs.single { it.label == "ign-lidar-hd" }
            val without = runs.single { it.label == "ign -decks" }
            reportDecks(route, truth, structures, withDecks)
            // test4's one bridge, Échevis (25 m), is not detected: NO_DECK there.
            assertEquals(
                if (route ==
                    "test4"
                ) {
                    BridgeDeckStatus.NO_DECK
                } else {
                    BridgeDeckStatus.APPLIED
                },
                withDecks.deckStatus,
                "$route: deck step",
            )
            regions += withDecks.replacements.size

            // Against the same route without a surface model (the E1 column): sourceElevation is
            // bit-identical outside the stretches, so detaching the surface is E1 exactly.
            assertEquals(without.dense.size, withDecks.dense.size, "$route: dense sizes")
            for (i in 0 until withDecks.dense.size) {
                if (withDecks.replaced(withDecks.dense.distance(i))) continue
                assertEquals(
                    without.dense.sourceElevation(i).toRawBits(),
                    withDecks.dense.sourceElevation(i).toRawBits(),
                    "$route: sourceElevation of point $i differs from ign -decks",
                )
            }

            // Bridges a replaced stretch touches, and the regions each lies in.
            fun regionsOn(st: Structure): List<DeckReplacement> =
                withDecks.replacements.filter { rep ->
                    (0 until withDecks.dense.size).any { i ->
                        val f = withDecks.match[i]
                        val d = withDecks.dense.distance(i)
                        !f.isNaN() && f.roundToInt() in st.i0..st.i1 && d >= rep.first * STEP_M && d <= rep.last * STEP_M
                    }
                }
            for (st in spans.filter { it.kind == "bridge" }) {
                val on = regionsOn(st)
                if (on.isNotEmpty()) replacedBridges += "${st.route} ${st.label}" else notReplaced += "${st.route} ${st.label}"
                if ("Bellevue" in st.name) bellevueRegions += on.map { it.spans.size }
                // The spec's Validation targets on decks: ≤ 2 m on the Nantes bridges, ≤ 5 m on
                // Saint-Nazaire (src: what ElevationGain reads).
                if (st.route == "test2" || "Saint-Nazaire" in st.name) {
                    val e =
                        errors(withDecks, truth, { it in st.i0..st.i1 && truth.kind[it] == 'B' }) { withDecks.dense.sourceElevation(it) }
                    val limit = if (st.route == "test2") 2.0 else 5.0
                    val ok = abs(e.maxErr) <= limit
                    targets += "${st.route} ${st.label}" to ok
                    // Where the worst point lies: metres from the structure's first B station,
                    // and from its last one.
                    val bStations = (st.i0..st.i1).filter { truth.kind[it] == 'B' }
                    var worstAt = Double.NaN
                    var worst = 0.0
                    for (i in 0 until withDecks.dense.size) {
                        val f = withDecks.match[i]
                        if (f.isNaN() || f.roundToInt() !in bStations) continue
                        val err = withDecks.dense.sourceElevation(i) - truth.zAt(f)
                        if (abs(err) > abs(worst)) {
                            worst = err
                            worstAt = f
                        }
                    }
                    targetLines +=
                        "[bench]   %-5s %-28s B rms %5.2f max %+6.2f m at %+.0f m from the first B station, %+.0f m from the last (target ≤ %.0f m) %s%s"
                            .fmt(
                                st.route,
                                st.label,
                                e.rms,
                                e.maxErr,
                                (worstAt - bStations.first()) * STEP_M,
                                (worstAt - bStations.last()) * STEP_M,
                                limit,
                                if (ok) "ok" else "MISSED",
                                if (on.isNotEmpty()) "" else " (not replaced: the detector does not accept it)",
                            )
                }
            }
            if (route != "test4") {
                val ratio = gridDem(withDecks) / ElevationGain.compute(truth.s, truth.z, ElevationGainOptions.DEFAULT).gainM
                val ok = ratio in 0.8..1.2
                targets += "$route D+ dem" to ok
                targetLines += "[bench]   %-5s D+ dem / true %.2f (target 0.80–1.20) %s".fmt(route, ratio, if (ok) "ok" else "MISSED")
            }
        }

        println()
        println(
            "[bench] E3: %d regions replaced on the four routes, %d / %d bridges replaced; not replaced: %s; Pont de Bellevue in regions of %s span(s)"
                .fmt(
                    regions,
                    replacedBridges.size,
                    replacedBridges.size + notReplaced.size,
                    notReplaced.joinToString(),
                    bellevueRegions,
                ),
        )
        println("[bench] E3 Validation targets (ign-lidar-hd with decks, src = sourceElevation):")
        targetLines.forEach(::println)
        println(
            "[bench] tiles: %d from disk, %d fetched (%.1f MB)".fmt(
                tileHits.get(),
                tileMisses.get(),
                bytesFetched.get() / 1e6,
            ),
        )
        assertEquals(EXPECTED_DECK_REGIONS, regions, "regions replaced on the four routes")
        assertEquals(EXPECTED_REPLACED_BRIDGES, replacedBridges.size, "bridges replaced")
        assertEquals(listOf(2), bellevueRegions, "Pont de Bellevue: its two parts joined into one region")
        for ((name, ok) in targets) if (name !in TARGETS_MISSED) assertTrue(ok, "Validation target missed: $name")
        assertEquals(TARGETS_MISSED, targets.filter { !it.second }.map { it.first }.toSet(), "targets missed (reported in the task doc)")
    }

    /** D+ at the `dem` preset of [r]'s `sourceElevation`, resampled on the 5 m grid like the truth. */
    private fun gridDem(r: Run): Double {
        val d = DoubleArray(r.dense.size) { r.dense.distance(it) }
        val src5 = grid5(d, DoubleArray(r.dense.size) { r.dense.sourceElevation(it) })
        return ElevationGain.compute(DoubleArray(src5.size) { it * STEP_M }, src5, ElevationGainOptions.DEFAULT).gainM
    }

    /** E3's block of the bench: the deck step's status and every stretch it replaced. */
    private fun reportDecks(
        route: String,
        truth: Truth,
        structures: List<Structure>,
        r: Run,
    ) {
        if (r.deckStatus == null) return
        val onTruth = BooleanArray(truth.size)
        structures.filter { it.route == route && (it.kind == "bridge" || it.kind == "missed") }.forEach { st ->
            for (k in st.i0..st.i1) onTruth[k] = true
        }
        val outside = HashSet<Int>()
        for (i in 0 until r.dense.size) {
            val f = r.match[i]
            if (!f.isNaN() && r.replaced(r.dense.distance(i)) && !onTruth[f.roundToInt()]) outside += f.roundToInt()
        }
        println(
            "[bench] E3 deck step: %s, %d region(s), %d dropped, %d truth stations replaced outside every bridge (incl. missed OSM bridges)"
                .fmt(
                    r.deckStatus,
                    r.replacements.size,
                    r.dropped,
                    outside.size,
                ),
        )
        for (rep in r.replacements) {
            val st =
                structures
                    .filter { it.route == route && (it.kind == "bridge" || it.kind == "missed") }
                    .filter { s ->
                        (0 until r.dense.size).any { i ->
                            val f = r.match[i]
                            val d = r.dense.distance(i)
                            !f.isNaN() && f.roundToInt() in s.i0..s.i1 && d >= rep.first * STEP_M && d <= rep.last * STEP_M
                        }
                    }.joinToString("; ") { "${it.kind} ${it.label}" }
                    .ifEmpty { "—" }
            println(
                "[bench]   km %6.2f–%6.2f, %d span(s), %3d anchors, anchors km %6.2f–%6.2f, deck top %6.2f m | %s".fmt(
                    rep.first * STEP_M / 1000,
                    rep.last * STEP_M / 1000,
                    rep.spans.size,
                    rep.anchors.size,
                    rep.anchorFirst * STEP_M / 1000,
                    rep.anchorLast * STEP_M / 1000,
                    (rep.anchorFirst..rep.anchorLast).maxOf { rep.elevationM[it - rep.first] },
                    st,
                ),
            )
        }
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
            val reported = r.reportedGainM
            val oneHz = r.oneHzGainM
            val src5 = grid5(d, src)
            val s5 = DoubleArray(src5.size) { it * STEP_M }
            val gridDem = ElevationGain.compute(s5, src5, dem).gainM
            val gridRaw = ElevationGain.compute(s5, src5, raw).gainM
            val openErr = errors(r, truth, { truth.kind[it] == 'T' && !onStructure[it] }) { src[it] }
            val secs = (r.durationMs / 1000).roundToInt()
            println(
                "$tag %-12s | %6d | %5d | %+7.2f | %7.0f | %5.2f | %7.0f | %5.2f | %7.0f | %5.2f | %7.0f | %5.2f | %2d:%02d:%02d | %8.2f | %7d | %7d"
                    .fmt(
                        r.label,
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
        // E3: the deck truth (`B`: MNS median on a detected deck) and, apart, the stations under a
        // deck hole whose truth is the MNT (`b`: Mauves, Saint-Nazaire), which judge nothing.
        // Every station last, the whole route against its truth.
        for ((kind, label) in listOf('B' to "deck B", 'b' to "hole b (not judged)", '*' to "all stations")) {
            if (kind != '*' && truth.kind.none { it == kind }) continue
            val scope: (Int) -> Boolean = { kind == '*' || truth.kind[it] == kind }
            println(
                "$tag %-20s | %s".fmt(
                    label,
                    runs.joinToString(" | ") { r ->
                        val es = errors(r, truth, scope) { r.dense.sourceElevation(it) }
                        val ee = errors(r, truth, scope) { r.dense.elevation(it) }
                        "%s: src rms %5.2f max %+7.2f, ele rms %5.2f max %+7.2f".fmt(r.label, es.rms, es.maxErr, ee.rms, ee.maxErr)
                    },
                ),
            )
        }
        if (spans.isEmpty()) return
        println("$tag structures — err = vcyclist - truth (m), max is the signed worst; src = sourceElevation, ele = 150 m smoothed")
        val head =
            runs.joinToString(" | ") { r ->
                "%-34s".fmt("${r.label}: src max/rms  ele max/rms")
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
     * [stations] moved half a pixel **east and south** at [zoom] (256 px). `ElevationCalculator`
     * interpolates between pixel *centres* since `elevation-half-pixel`, like a WMS GetMap pixel and
     * fetch.py's sampler; before it, it read the grid as if each value sat on the pixel's north-west
     * *corner*, i.e. the terrain half a pixel south-east of the station. Sampling the shifted point
     * with the corrected calculator reproduces that old corner reading — exactly in Mercator, within
     * 0.005 px on IGN's EPSG:4326 tiles — so variant (a⌐) is the pre-fix (a), kept as the point of
     * comparison.
     */
    private fun halfPixelShift(
        stations: List<io.github.glandais.elevation.LatLon>,
        zoom: Int,
    ): List<io.github.glandais.elevation.LatLon> {
        val world = (1 shl zoom) * 256.0
        return stations.map { p ->
            val lat = p.latitude * MathConstants.DEG_TO_RAD
            val x = (p.longitude + 180.0) / 360.0 * world + 0.5
            val y = (1.0 - kotlin.math.ln(kotlin.math.tan(lat) + 1.0 / cos(lat)) / Math.PI) / 2.0 * world + 0.5
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
     *
     * The exact parity runs on [DeckDetectorOptions.PYTHON]. A second check runs DEFAULT (E2 ter's
     * bridged slope and `mntAboveMnsAsTall`) on the same samples: the same 21 accepted spans with the
     * same ends, every P95 within `maxSlopeP95`, and the same 9 rejection verdicts — the P95s
     * themselves may move (two `test2` spans do).
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
            val profile = DeckProfile.fromSurface(mns, truth.mnt)
            val d = DeckDetector.detect(profile, DeckDetectorOptions.PYTHON)
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
            // E2 ter DEFAULT on the same samples: same spans, same verdicts.
            val e = DeckDetector.detect(profile, DeckDetectorOptions.DEFAULT)
            assertEquals(d.accepted.map { it.first..it.last }, e.accepted.map { it.first..it.last }, "$route accepted (DEFAULT)")
            e.accepted.forEach {
                assertTrue(it.metrics!!.slopeP95.isNaN() || it.metrics!!.slopeP95 <= DeckDetectorOptions.DEFAULT.maxSlopeP95)
            }
            assertEquals(
                measured.map { "${it.first}..${it.last} ${it.rejection}" },
                e.rejected.filter { it.rejection != DeckRejection.ROUGH }.map { "${it.first}..${it.last} ${it.rejection}" },
                "$route others (DEFAULT)",
            )
            for (c in e.accepted) {
                val before = d.accepted.single { it.first == c.first }.metrics!!
                val m = c.metrics!!
                if (abs(m.slopeP95 - before.slopeP95) > 1e-9) {
                    println(
                        "[decks] python samples, %s %d..%d: P95 %.1f %% (PYTHON) -> %.1f %% (DEFAULT), clean %.2f".fmt(
                            route,
                            c.first,
                            c.last,
                            before.slopeP95 * 100,
                            m.slopeP95 * 100,
                            m.cleanFraction,
                        ),
                    )
                }
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
        // E2's variants are reproduced on the E2 bis detector (PYTHON); E2 ter's DEFAULT is row (ater).
        val opts = DeckDetectorOptions.PYTHON
        val ter = DeckDetectorOptions.DEFAULT
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
                Variant("a⌐", "idem, old corner convention"),
                Variant("b", "MNH z15"),
                Variant("c", "MNH z14"),
                Variant("d", "MNH z13"),
                Variant("e14", "2-phase MNH z14"),
                Variant("e13", "2-phase MNH z13"),
                Variant("f-ign", "MNS - DEM ign"),
                Variant("f-mth", "MNS - DEM mapterhorn"),
                Variant("f-mthc", "canyon on mapterhorn"),
                Variant("ater", "(a), E2 ter DEFAULT"),
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
                val mntC = sampleLayer(route, "MNT z15 (corners)", shifted, IgnLidarHdLayer.MNT.provider(15) { countingFetcher(it) })
                val mnsC = sampleLayer(route, "MNS z15 (corners)", shifted, IgnLidarHdLayer.MNS.provider(15) { countingFetcher(it) })
                byVariant["a⌐"] = DeckProfile.fromSurface(mnsC, mntC)
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
            results.getValue("ater")[route] = DeckDetector.detect(parity, ter)
            twoPhaseSame["e14|$route"] = results.getValue("e14")[route].toString() == results.getValue("c")[route].toString()
            twoPhaseSame["e13|$route"] = results.getValue("e13")[route].toString() == results.getValue("d")[route].toString()
            val t0 = System.nanoTime()
            repeat(5) { DeckDetector.detect(parity, opts) }
            wallMs[route] = (System.nanoTime() - t0) / 5e6
            val t1 = System.nanoTime()
            repeat(5) { DeckDetector.detect(parity, ter) }
            wallMs["ater|$route"] = (System.nanoTime() - t1) / 5e6
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
        for ((route, id) in ROUTES.keys.flatMap { r -> listOf(r to "a", r to "ater") }) {
            val d = results.getValue(id).getValue(route)
            val p = profiles.getValue(route)
            val spans = structures.filter { it.route == route }
            println()
            println(
                "[decks] ═══ $route — variant (%s) MNS−MNT z15, %s, detector %.1f ms".fmt(
                    id,
                    if (id == "a") "PYTHON" else "DEFAULT (E2 ter)",
                    wallMs.getValue(if (id == "a") route else "ater|$route"),
                ),
            )
            println(
                "[decks] %-13s | %5s | %6s | %6s | %6s | %6s | %5s | %6s | %6s | %-11s | %s".fmt(
                    "km",
                    "L m",
                    "MNHmax",
                    "rough",
                    "P95 %",
                    "raw %",
                    "clean",
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
                    "[decks] %6.2f–%6.2f | %5.0f | %6.1f | %6.2f | %6.1f | %6.1f | %5.2f | %6.1f | %6.1f | %-11s | %s".fmt(
                        c.startM / 1000,
                        c.endM / 1000,
                        m.lengthM,
                        mnhMax,
                        m.roughnessM,
                        m.slopeP95 * 100,
                        m.rawSlopeP95 * 100,
                        m.cleanFraction,
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
            val score = scoreStrava(r, structures)
            val perRoute = score.perRoute.joinToString("/")
            val found = score.found
            val foundMissed = missed.count { st -> r.getValue(st.route).accepted.any { mostlyOn(it, st) } }
            val outSpan = score.outSpan
            val outDeck = score.outDeck
            val fps = score.fps
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
            "[decks] the spec's acceptance criterion (zero detections on forest and urban controls) is judged by deckControls (E2 bis)",
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
        // E2 on PYTHON, then E2 ter DEFAULT: no bridge lost, no FP, no new station outside the truth.
        // Since elevation-half-pixel (pixel centres): 16 -> 14 bridges, 8 / 8 -> 5 / 5 outside the truth;
        // (a⌐) still reads the old corners and gives the old 16 / 8 / 8. See that task's H3.
        val e2 = scoreStrava(results.getValue("a"), structures)
        assertEquals(14, e2.found, "variant (a) PYTHON Strava bridges (E2)")
        assertEquals(5, e2.outSpan, "variant (a) PYTHON stations outside truth, spans (E2)")
        assertEquals(5, e2.outDeck, "variant (a) PYTHON stations outside truth, deck mask (E2)")
        val corners = scoreStrava(results.getValue("a⌐"), structures)
        assertEquals(16, corners.found, "variant (a⌐) old corner convention Strava bridges")
        assertEquals(8, corners.outSpan, "variant (a⌐) old corner convention stations outside truth, spans")
        assertEquals(8, corners.outDeck, "variant (a⌐) old corner convention stations outside truth, deck mask")
        // DEFAULT since E2 quinquies (recalibrated at pixel centres): a steep merged span is judged as
        // its parts, and a bridged gap is vetoed only by the ground. 18 bridges: (a)'s 14, the two
        // the centres lost (Willy-Brandt, way 362043805), Bellevue and Haudaudine — every bridge the
        // old corner reading found, none lost; two more abutment stations outside the truth.
        val e2ter = scoreStrava(results.getValue("ater"), structures)
        println("[decks] (ater) DEFAULT: ${e2ter.found} bridges, FP ${e2ter.fps.size}, outside ${e2ter.outSpan} / ${e2ter.outDeck}")
        assertEquals(18, e2ter.found, "variant (ater) DEFAULT Strava bridges")
        assertTrue(e2ter.foundKeys.containsAll(e2.foundKeys), "variant (ater) loses a bridge of (a): ${e2.foundKeys - e2ter.foundKeys}")
        assertTrue(
            e2ter.foundKeys.containsAll(corners.foundKeys),
            "variant (ater) loses a corner-era bridge: ${corners.foundKeys - e2ter.foundKeys}",
        )
        assertEquals(0, e2ter.fps.size, "variant (ater) false positives")
        assertEquals(7, e2ter.outSpan, "variant (ater) stations outside truth, spans")
        assertEquals(7, e2ter.outDeck, "variant (ater) stations outside truth, deck mask")
        twoPhaseSame.forEach { (key, same) -> assertTrue(same, "two-phase $key differs from the full-MNS run") }
    }

    // ── E2 bis: dedicated controls ─────────────────────────────────────────────────────────────

    /** One row of `truth/controls/index.csv`. */
    private class ControlRow(
        val id: String,
        val role: String,
        val category: String,
        val n: Int,
        val lengthM: Double,
        val counts: Map<Char, Int>,
        val place: String,
    ) {
        /** A held-out positive of either series: `D` spans to find, route beyond them on ground. */
        val positive: Boolean get() = role == "held-out-positive" || role == HOLDOUT2

        /**
         * The second held-out series (`p09..`, E2 quinquies), frozen before any detector run. Read
         * only by [deckHoldoutJudge]; [deckControls] drops these rows before sampling anything.
         */
        val holdout2: Boolean get() = role == HOLDOUT2
    }

    /** A control path of `controls.py`: 5 m stations and their kind (`GOLXxD`). */
    private class Control(
        val row: ControlRow,
        val s: DoubleArray,
        val lat: DoubleArray,
        val lon: DoubleArray,
        val kind: CharArray,
        val way: LongArray,
    ) {
        val id: String get() = row.id
        val size: Int get() = s.size
    }

    /**
     * One row of `truth/controls/spans.csv`: a maximal run of `D`, `X`, `O` or `L` stations. A `D`/`X`
     * run names its [structure] (`bridge`, `tunnel`, `layer`, or `underpass`: a tunnel or culvert under
     * the route) and its OSM [way].
     */
    private class ControlSpan(
        val id: String,
        val kind: Char,
        val i0: Int,
        val i1: Int,
        val way: Long,
        val structure: String,
        val name: String,
    ) {
        val lengthM: Double get() = (i1 - i0 + 1) * STEP_M
    }

    private fun readControlIndex(): List<ControlRow> {
        val lines = File(dataDir, "analysis/truth/controls/index.csv").readLines().filter { it.isNotBlank() }
        assertEquals("id,role,category,n,length_m,G,O,L,X,x,D,place", lines.first())
        val rows =
            lines.drop(1).map { line ->
                val c = line.split(',')
                assertEquals(12, c.size, "index.csv: column count in '$line'")
                ControlRow(
                    id = c[0],
                    role = c[1],
                    category = c[2],
                    n = c[3].toInt(),
                    lengthM = c[4].toDouble(),
                    counts = CONTROL_KINDS.withIndex().associate { (j, k) -> k to c[5 + j].toInt() },
                    place = c[11],
                )
            }
        assertEquals(CONTROL_ROLES, rows.groupingBy { it.role }.eachCount(), "index.csv roles")
        assertEquals(rows.size, rows.map { it.id }.toSet().size, "index.csv: duplicate id")
        return rows
    }

    private fun readControl(row: ControlRow): Control {
        val lines = File(dataDir, "analysis/truth/controls/${row.id}.csv").readLines()
        val header = lines.first()
        assertTrue(
            header.startsWith("# control=${row.id} role=${row.role} category=${row.category} step_m=5 "),
            "${row.id}.csv header: $header",
        )
        assertEquals("s,lat,lon,kind,way", lines[2], "${row.id}.csv column header")
        val declared = Regex("""\bn=(\d+)""").find(header)!!.groupValues[1].toInt()
        val rows = lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("s,") }.map { it.split(',') }
        assertEquals(declared, rows.size, "${row.id}.csv: row count against its own header")
        assertEquals(row.n, rows.size, "${row.id}.csv: row count against index.csv")
        rows.forEach { assertEquals(5, it.size, "${row.id}.csv: column count") }
        val c =
            Control(
                row = row,
                s = DoubleArray(rows.size) { rows[it][0].toDouble() },
                lat = DoubleArray(rows.size) { rows[it][1].toDouble() },
                lon = DoubleArray(rows.size) { rows[it][2].toDouble() },
                kind = CharArray(rows.size) { rows[it][3].single() },
                way = LongArray(rows.size) { rows[it][4].toLong() },
            )
        for (k in 0 until c.size) assertEquals(k * STEP_M, c.s[k], 1e-9, "${row.id}.csv: s of station $k")
        c.kind.forEach { assertTrue(it in CONTROL_KINDS, "${row.id}.csv: kind '$it'") }
        assertEquals(row.counts, CONTROL_KINDS.associateWith { k -> c.kind.count { it == k } }, "${row.id}.csv: kinds against index.csv")
        return c
    }

    private fun runsOf(
        kind: CharArray,
        k: Char,
    ): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = -1
        for (i in 0..kind.size) {
            val on = i < kind.size && kind[i] == k
            if (on && start < 0) start = i
            if (!on && start >= 0) {
                out += start until i
                start = -1
            }
        }
        return out
    }

    private fun readControlSpans(controls: Map<String, Control>): List<ControlSpan> {
        val lines = File(dataDir, "analysis/truth/controls/spans.csv").readLines().filter { it.isNotBlank() }
        assertEquals("id,kind,i0,i1,s0,s1,way,structure,name", lines.first())
        val spans =
            lines.drop(1).map { line ->
                val c = line.split(',')
                assertEquals(9, c.size, "spans.csv: column count in '$line'")
                ControlSpan(c[0], c[1].single(), c[2].toInt(), c[3].toInt(), c[6].toLong(), c[7], c[8])
            }
        for (sp in spans) {
            if (sp.kind == 'D' || sp.kind == 'X') {
                assertTrue(sp.structure in STRUCTURES, "spans.csv: ${sp.id} ${sp.kind} ${sp.i0}: structure '${sp.structure}'")
                assertTrue(sp.way > 0, "spans.csv: ${sp.id} ${sp.kind} ${sp.i0}: way")
            } else {
                assertEquals("", sp.structure, "spans.csv: ${sp.id} ${sp.kind} ${sp.i0}: structure")
            }
            if (sp.kind == 'D') assertEquals("bridge", sp.structure, "spans.csv: ${sp.id} D ${sp.i0}")
        }
        for ((id, control) in controls) {
            for (k in "DXOL") {
                assertEquals(
                    runsOf(control.kind, k),
                    spans.filter { it.id == id && it.kind == k }.map { it.i0..it.i1 },
                    "spans.csv: $id runs of $k",
                )
            }
        }
        assertEquals(controls.keys, spans.map { it.id }.toSet() + controls.keys, "spans.csv: unknown id")
        return spans
    }

    /** Great-circle distance, fetch.py's `hav` (R = 6 371 008.8 m). */
    private fun haversine(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val p1 = lat1 * MathConstants.DEG_TO_RAD
        val p2 = lat2 * MathConstants.DEG_TO_RAD
        val dp = p2 - p1
        val dl = (lon2 - lon1) * MathConstants.DEG_TO_RAD
        val h = kotlin.math.sin(dp / 2).let { it * it } + cos(p1) * cos(p2) * kotlin.math.sin(dl / 2).let { it * it }
        return 2 * 6_371_008.8 * kotlin.math.asin(sqrt(h))
    }

    /**
     * The control GPX resampled at 5 m like `controls.py` (and fetch.py): station count, then the
     * position at [atM] metres. Proves that GPX and truth come from the same run.
     */
    private fun resampleGpx(
        file: File,
        atM: Double,
    ): Triple<Int, Pair<Double, Double>, Pair<Double, Double>> {
        val pts =
            Regex("""<trkpt lat="([-\d.]+)" lon="([-\d.]+)"""")
                .findAll(file.readText())
                .map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
                .toList()
        assertTrue(pts.size >= 2, "${file.name}: trkpt count ${pts.size}")
        val d = DoubleArray(pts.size)
        for (i in 1 until pts.size) d[i] = d[i - 1] + haversine(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second)
        val n = kotlin.math.ceil(d.last() / STEP_M).toInt()
        var j = 0
        while (j < pts.size - 2 && d[j + 1] < atM) j++
        val u = ((atM - d[j]) / (d[j + 1] - d[j])).coerceIn(0.0, 1.0)
        val at = (pts[j].first + u * (pts[j + 1].first - pts[j].first)) to (pts[j].second + u * (pts[j + 1].second - pts[j].second))
        return Triple(n, pts.first(), at)
    }

    /** A negative's own bridges (`X`, `structure=bridge`, 20 m or more): scored for recall, not as FP. */
    private fun ownBridges(
        index: List<ControlRow>,
        spans: List<ControlSpan>,
    ): List<ControlSpan> {
        val negatives = index.filter { !it.positive }.map { it.id }.toSet()
        return spans.filter { it.id in negatives && it.kind == 'X' && it.structure == "bridge" && it.lengthM >= 20.0 - 1e-9 }
    }

    private fun readControls(): Triple<List<ControlRow>, Map<String, Control>, List<ControlSpan>> {
        val index = readControlIndex()
        val controls = index.associate { it.id to readControl(it) }
        val spans = readControlSpans(controls)
        return Triple(index, controls, spans)
    }

    /**
     * E2 bis, on every `jvmTest`: the controls of `analysis/controls.py` (16 negatives, 8 held-out
     * positives, and the 9 of the second held-out series, E2 quinquies) parse, keep their guards, and
     * their GPX is the same run as their truth. Reads no tile, so it touches `holdout2` safely.
     */
    @Test
    fun deck_controls_parse_and_hold_their_guards() {
        assertNotNull(dataDir, "docs/deck-strava-data not found")
        val (index, controls, spans) = readControls()
        assertEquals(CONTROL_GUARDS.keys, controls.keys, "CONTROL_GUARDS ids")
        assertEquals(
            NEGATIVE_OWN_BRIDGES,
            ownBridges(index, spans).map { "${it.id} ${it.name}" },
            "own bridges (X, structure=bridge, >= 20 m) on the negatives",
        )
        for (row in index) {
            val c = controls.getValue(row.id)
            val dRuns = spans.filter { it.id == row.id && it.kind == 'D' }
            if (row.positive) {
                assertTrue(dRuns.any { it.lengthM >= 20.0 }, "${row.id}: a held-out positive needs a D span of 20 m or more")
                // DeckDetector clamps the outside MNT of the canyon test at a route end: a counted
                // span needs ground beyond it on both sides, or the bench measures the clamping.
                for (sp in dRuns.filter { it.lengthM >= 20.0 }) {
                    val before = sp.i0 * STEP_M
                    val after = (c.size - 1 - sp.i1) * STEP_M
                    assertTrue(
                        before >= END_MARGIN_M && after >= END_MARGIN_M,
                        "${row.id}: D span ${sp.name} has %.0f m of route before and %.0f m after (< %.0f)".fmt(
                            before,
                            after,
                            END_MARGIN_M,
                        ),
                    )
                }
            } else {
                assertEquals(0, row.counts.getValue('D'), "${row.id}: a negative has no D station")
            }
            val guard = CONTROL_GUARDS.getValue(row.id)
            val actual =
                intArrayOf(
                    c.size,
                    row.counts.getValue('O'),
                    row.counts.getValue('L'),
                    row.counts.getValue('X') + row.counts.getValue('x'),
                    dRuns.size,
                )
            assertEquals(guard.toList(), actual.toList(), "${row.id}: n, O, L, X+x, D spans against CONTROL_GUARDS")
            val gpx = File(dataDir, "controls/${row.id}.gpx")
            assertTrue(gpx.isFile, "${gpx.path} missing")
            val (n, first, last) = resampleGpx(gpx, c.s.last())
            assertTrue(abs(n - c.size) <= 1, "${row.id}: GPX resamples to $n stations, truth has ${c.size}")
            assertEquals(c.lat.first(), first.first, 1e-6, "${row.id}: first station latitude")
            assertEquals(c.lon.first(), first.second, 1e-6, "${row.id}: first station longitude")
            assertEquals(c.lat.last(), last.first, 1e-6, "${row.id}: last station latitude")
            assertEquals(c.lon.last(), last.second, 1e-6, "${row.id}: last station longitude")
        }
    }

    private class StravaScore(
        val found: Int,
        val perRoute: List<Int>,
        val fps: List<Pair<String, DeckCandidate>>,
        val outSpan: Int,
        val outDeck: Int,
        /** `"route i0"` of each bridge found. */
        val foundKeys: Set<String>,
    )

    private val Structure.key: String get() = "$route $i0"

    /**
     * E2's scoring of the four Strava routes, shared by [deckDetector] and [deckControls]: a bridge
     * is found when an accepted span overlaps it; a false positive is an accepted span that touches
     * no bridge and no missed OSM bridge; "outside truth" counts the stations of accepted spans (and
     * of their deck mask) on neither.
     */
    private fun scoreStrava(
        dets: Map<String, DeckDetection>,
        structures: List<Structure>,
    ): StravaScore {
        val bridges = structures.filter { it.kind == "bridge" }
        val truthSpans = structures.filter { it.kind == "bridge" || it.kind == "missed" }

        fun overlaps(
            c: DeckCandidate,
            st: Structure,
        ) = c.first <= st.i1 && c.last >= st.i0
        val foundKeys = bridges.filter { st -> dets.getValue(st.route).accepted.any { overlaps(it, st) } }.map { it.key }.toSet()
        var outSpan = 0
        var outDeck = 0
        for (route in ROUTES.keys) {
            val onTruth = BooleanArray(ROUTES.getValue(route))
            truthSpans.filter { it.route == route }.forEach { st -> for (k in st.i0..st.i1) onTruth[k] = true }
            for (c in dets.getValue(route).accepted) {
                outSpan += (c.first..c.last).count { !onTruth[it] }
                outDeck += c.deckRanges.sumOf { d -> d.count { !onTruth[it] } }
            }
        }
        val fps =
            ROUTES.keys.flatMap { route ->
                dets
                    .getValue(route)
                    .accepted
                    .filter { c -> truthSpans.none { it.route == route && overlaps(c, it) } }
                    .map { route to it }
            }
        return StravaScore(
            found = foundKeys.size,
            perRoute = ROUTES.keys.map { r -> bridges.count { it.route == r && it.key in foundKeys } },
            fps = fps,
            outSpan = outSpan,
            outDeck = outDeck,
            foundKeys = foundKeys,
        )
    }

    private class ControlScore(
        val accepted: List<DeckCandidate>,
        val fps: List<DeckCandidate>,
        /** Stations of accepted spans whose kind is G, O or L. */
        val outSpan: Map<Char, Int>,
        /** Same, in the accepted deck masks. */
        val outDeck: Map<Char, Int>,
        /** `D` spans of 20 m or more: the held-out recall's denominator. */
        val dSpans: List<ControlSpan>,
        val dFound: List<ControlSpan>,
        /** `D` spans under 20 m, listed, not counted. */
        val dSmall: List<ControlSpan>,
        /** Stations of the counted `D` spans, then those inside an accepted span, then inside a deck mask. */
        val dStations: Int,
        val dInSpan: Int,
        val dInDeck: Int,
        val rejections: Map<DeckRejection, Int>,
        val roughKm: Double,
        /** Measured rejections (every one but ROUGH), the candidates a threshold could turn into a detection. */
        val measured: List<DeckCandidate>,
        /** A negative's own bridges ([ownBridges]), then those an accepted span touches. */
        val own: List<ControlSpan>,
        val ownFound: List<ControlSpan>,
    ) {
        fun out(k: Char) = outSpan.getValue(k)

        /** `D` coverage in percent, rounded (403 / 1 698 is 24 %, not 23). */
        fun coveragePct(): Long = Math.round(100.0 * dInSpan / dStations)
    }

    /** Verdicts of the candidates that overlap stations [i0]..[i1]: why a structure was not found. */
    private fun verdictsOn(
        d: DeckDetection,
        i0: Int,
        i1: Int,
    ): String =
        d.rejected
            .filter { it.first <= i1 && it.last >= i0 }
            .joinToString("+") { it.rejection!!.name }
            .ifEmpty { "no candidate" }

    private fun scoreControl(
        c: Control,
        spans: List<ControlSpan>,
        d: DeckDetection,
        opts: DeckDetectorOptions,
    ): ControlScore {
        val excluded = setOf('D', 'X', 'x')
        val dAll = spans.filter { it.id == c.id && it.kind == 'D' }
        val dSpans = dAll.filter { it.lengthM >= 20.0 - 1e-9 }
        val own =
            if (c.row.positive) {
                emptyList()
            } else {
                spans.filter { it.id == c.id && it.kind == 'X' && it.structure == "bridge" && it.lengthM >= 20.0 - 1e-9 }
            }
        return ControlScore(
            accepted = d.accepted,
            fps = d.accepted.filter { cand -> (cand.first..cand.last).none { c.kind[it] in excluded } },
            outSpan = "GOL".associateWith { k -> d.accepted.sumOf { cand -> (cand.first..cand.last).count { c.kind[it] == k } } },
            outDeck = "GOL".associateWith { k -> d.accepted.sumOf { cand -> cand.deckRanges.sumOf { r -> r.count { c.kind[it] == k } } } },
            dSpans = dSpans,
            dFound = dSpans.filter { sp -> d.accepted.any { it.first <= sp.i1 && it.last >= sp.i0 } },
            dSmall = dAll - dSpans.toSet(),
            dStations = dSpans.sumOf { it.i1 - it.i0 + 1 },
            dInSpan = dSpans.sumOf { sp -> (sp.i0..sp.i1).count { k -> d.accepted.any { k in it.first..it.last } } },
            dInDeck = dSpans.sumOf { sp -> (sp.i0..sp.i1).count { k -> d.accepted.any { a -> a.deckRanges.any { k in it } } } },
            rejections = DeckRejection.entries.associateWith { r -> d.rejected.count { it.rejection == r } },
            roughKm = d.rejected.filter { it.rejection == DeckRejection.ROUGH }.sumOf { (it.last - it.first + 1) * opts.stepM } / 1000,
            measured = d.rejected.filter { it.metrics != null },
            own = own,
            ownFound = own.filter { sp -> d.accepted.any { it.first <= sp.i1 && it.last >= sp.i0 } },
        )
    }

    /** Kinds of a candidate's tall stations, e.g. `O3 G2`. */
    private fun tallKinds(
        c: Control,
        p: DeckProfile,
        cand: DeckCandidate,
        opts: DeckDetectorOptions,
    ): String =
        (cand.first..cand.last)
            .filter { p.mnhM[it] > opts.tallMnhM }
            .groupingBy { c.kind[it] }
            .eachCount()
            .entries
            .sortedBy { CONTROL_KINDS.indexOf(it.key) }
            .joinToString(" ") { "${it.key}${it.value}" }
            .ifEmpty { "-" }

    /** Runs of `MNH > tallMnhM` of at least `minTallRunM` lying inside [cand]: how many the gap rule merged. */
    private fun tallRunsIn(
        p: DeckProfile,
        cand: DeckCandidate,
        opts: DeckDetectorOptions,
    ): Int {
        val minRun = kotlin.math.round(opts.minTallRunM / opts.stepM).toInt()
        var count = 0
        var len = 0
        for (k in cand.first..cand.last + 1) {
            if (k <= cand.last && p.mnhM[k] > opts.tallMnhM) {
                len++
            } else {
                if (len >= minRun) count++
                len = 0
            }
        }
        return count
    }

    /**
     * E2 bis, `INTEGRATION=1`: [DeckDetector] on the 16 negative controls (one wooded-ravine one since E2 quater,
     * three more since E2 quinquies) and 8 held-out positives
     * of `analysis/controls.py`, sampled exactly as variant (a) of [deckDetector] (MNS − MNT z15,
     * bilinear, one layer after the other), then a one-at-a-time sensitivity sweep around
     * [DeckDetectorOptions.DEFAULT] — built here with `copy`, DEFAULT itself never changes. The sweep
     * is printed, not asserted: a probe must not go red on a finding. Asserted: data integrity, tile
     * counts, that the extracted Strava scoring reproduces E2 on [DeckDetectorOptions.PYTHON] (14/21,
     * FP 0, 5 / 5 stations at pixel centres — 16/21 and 8 / 8 at the old corner convention) and that
     * DEFAULT, recalibrated at pixel centres (E2 quinquies), finds 18/21 — every PYTHON bridge among
     * them — with 7 / 7 stations outside the truth, and the acceptance criterion at DEFAULT: 0 FP and
     * 0 `G`/`O`/`L` station inside a detection on the 16 negatives, n04's own Pont de la Beaujoire
     * found. The second held-out series (`holdout2`) is dropped before sampling: only
     * [deckHoldoutJudge] reads it. Since E2 quinquies: nothing PYTHON finds is lost, held-out `D`
     * coverage exactly 1 666 / 1 692 = 98 % (73 % under E2 ter at pixel centres, 94 % at the
     * corners), 14/15 spans (the rue des Ponts the one missed), p01/p07 each ≥ 95 % covered, and
     * with the canyon test off no more `G`/`O`/`L` stations than PYTHON lets through. Every figure moved by the pixel-centre fix is in `docs/tasks/elevation-half-pixel.md`
     * (H3), and the recalibration in `elevation-bridge-decks.md` (*Recalage aux centres*).
     */
    @Test
    fun deckControls() {
        if (!enabled || dataDir == null) {
            println("[controls] set INTEGRATION=1 (and run from the repository) to measure the deck detector on the controls")
            return
        }
        val tag = "[controls]"
        val structures = readStructures()
        assertEquals(STRUCTURE_COUNTS, structures.groupingBy { it.kind }.eachCount())
        val (allIndex, controls, spans) = readControls()
        // The second held-out series is the judge's alone: dropped here, before any tile is read,
        // so no calibration figure of this test can depend on it (E2 quinquies, rule 7).
        val index = allIndex.filter { !it.holdout2 }
        assertEquals(CONTROL_ROLES.getValue(HOLDOUT2), allIndex.size - index.size, "holdout2 rows dropped")
        println("$tag tile cache: ${cacheDir.absolutePath}")

        fun sample(
            key: String,
            lat: DoubleArray,
            lon: DoubleArray,
        ): DeckProfile = sampleSurface(key, lat, lon)
        val strava =
            ROUTES.mapValues { (route, rows) ->
                val t = readTruth(route)
                assertEquals(rows, t.size)
                sample(route, t.lat, t.lon)
            }
        val profiles = LinkedHashMap<String, DeckProfile>()
        val outOfCoverage = LinkedHashSet<String>()
        println()
        println(
            "$tag sampling (MNS − MNT z15, variant (a)): NaN per control; > 1 % NaN MNT = outside coverage, flagged and left out of the totals",
        )
        for (row in index) {
            val c = controls.getValue(row.id)
            val p = sample(row.id, c.lat, c.lon)
            profiles[row.id] = p
            val nanMnt = p.mntM.count { it.isNaN() }
            val nanMns = p.mnsM.count { it.isNaN() }
            val tiles = ledger.urls["MNT z15|${row.id}"]?.size ?: 0
            assertTrue(tiles > 0, "${row.id}: no MNT tile")
            assertTrue((ledger.urls["MNS z15|${row.id}"]?.size ?: 0) > 0, "${row.id}: no MNS tile")
            if (nanMnt > 0.01 * c.size) outOfCoverage += row.id
            println(
                "$tag   %-30s %5d stations, %2d MNT tiles, NaN MNT %d, MNS %d%s".fmt(
                    row.id,
                    c.size,
                    tiles,
                    nanMnt,
                    nanMns,
                    if (row.id in outOfCoverage) "  ← OUTSIDE COVERAGE" else "",
                ),
            )
        }

        val default = DeckDetectorOptions.DEFAULT
        val python = DeckDetectorOptions.PYTHON
        val pyStrava = scoreStrava(strava.mapValues { DeckDetector.detect(it.value, python) }, structures)
        // The extraction pin: E2's numbers for variant (a), on the E2 bis detector — re-measured at pixel
        // centres by elevation-half-pixel (H3): 16 -> 14 bridges, 8 / 8 -> 5 / 5.
        assertEquals(14, pyStrava.found, "PYTHON Strava bridges (E2)")
        assertEquals(0, pyStrava.fps.size, "PYTHON Strava false positives (E2)")
        assertEquals(5, pyStrava.outSpan, "PYTHON Strava stations outside truth, spans (E2)")
        assertEquals(5, pyStrava.outDeck, "PYTHON Strava stations outside truth, deck mask (E2)")
        // DEFAULT, recalibrated at pixel centres (E2 quinquies): every bridge PYTHON finds, plus
        // Willy-Brandt, way 362043805, Bellevue and Haudaudine — 18, none lost against the 16 of the
        // old corner reading; no FP; two more abutment stations outside the truth (5 -> 7).
        val baseStrava = scoreStrava(strava.mapValues { DeckDetector.detect(it.value, default) }, structures)
        assertEquals(18, baseStrava.found, "DEFAULT Strava bridges")
        assertTrue(
            baseStrava.foundKeys.containsAll(pyStrava.foundKeys),
            "DEFAULT loses PYTHON's ${pyStrava.foundKeys - baseStrava.foundKeys}",
        )
        assertEquals(0, baseStrava.fps.size, "DEFAULT Strava false positives")
        assertEquals(7, baseStrava.outSpan, "DEFAULT Strava stations outside truth, spans")
        assertEquals(7, baseStrava.outDeck, "DEFAULT Strava stations outside truth, deck mask")

        // ── DEFAULT, per control ──────────────────────────────────────────────────────────────
        println()
        println(
            "$tag DEFAULT, per control: accepted spans; FP = accepted with no D/X/x station; out = G/O/L stations in accepted spans (deck mask)",
        )
        println(
            "$tag %-30s | %-4s | %5s | %-24s | %3s | %-17s | %-9s | %-26s | %s".fmt(
                "control",
                "role",
                "km",
                "accepted (km)",
                "FP",
                "out G/O/L (mask)",
                "recall",
                "NO_CAN/STEEP/SH_R/NODATA",
                "ROUGH n / km",
            ),
        )
        val scores = LinkedHashMap<String, ControlScore>()
        for (row in index) {
            val c = controls.getValue(row.id)
            val p = profiles.getValue(row.id)
            val d = DeckDetector.detect(p, default)
            val sc = scoreControl(c, spans, d, default)
            scores[row.id] = sc
            println(
                "$tag %-30s | %-4s | %5.2f | %-24s | %3d | %-17s | %-9s | %-26s | %3d / %5.2f".fmt(
                    row.id,
                    if (row.positive) "pos" else "neg",
                    row.lengthM / 1000,
                    sc.accepted.joinToString(" ") { "%.2f–%.2f".fmt(it.startM / 1000, it.endM / 1000) }.ifEmpty { "—" },
                    sc.fps.size,
                    "%d/%d/%d (%d/%d/%d)".fmt(sc.out('G'), sc.out('O'), sc.out('L'), sc.outDeck['G'], sc.outDeck['O'], sc.outDeck['L']),
                    if (row.positive) "${sc.dFound.size}/${sc.dSpans.size} ${sc.coveragePct()}%" else "",
                    "%d / %d / %d / %d".fmt(
                        sc.rejections[DeckRejection.NO_CANYON],
                        sc.rejections[DeckRejection.STEEP],
                        sc.rejections[DeckRejection.SHORT_ROUGH],
                        sc.rejections[DeckRejection.NO_DATA],
                    ),
                    sc.rejections[DeckRejection.ROUGH],
                    sc.roughKm,
                ),
            )
        }
        println()
        println(
            "$tag DEFAULT, every candidate the detector measured on the controls (ROUGH groups are never measured); tall = kinds of the MNH > tallMnhM stations",
        )
        println(
            "$tag %-30s | %-13s | %5s | %6s | %5s | %6s | %6s | %5s | %6s | %6s | %-11s | %-12s | %s".fmt(
                "control",
                "km",
                "L m",
                "MNHmax",
                "rough",
                "P95 %",
                "raw %",
                "clean",
                "canyon",
                "drop",
                "verdict",
                "tall",
                "structure (truth)",
            ),
        )
        for (row in index) {
            val c = controls.getValue(row.id)
            val p = profiles.getValue(row.id)
            val sc = scores.getValue(row.id)
            for (cand in (sc.accepted + sc.measured).sortedBy { it.first }) {
                val m = cand.metrics!!
                val mnhMax = (cand.first..cand.last).maxOf { k -> p.mnhM[k].takeUnless { it.isNaN() } ?: Double.NEGATIVE_INFINITY }
                val st =
                    spans
                        .filter { it.id == row.id && it.i0 <= cand.last && it.i1 >= cand.first }
                        .joinToString("; ") { "${it.kind} ${it.name.take(40)}" }
                        .ifEmpty { "—" }
                println(
                    "$tag %-30s | %6.2f–%6.2f | %5.0f | %6.1f | %5.2f | %6.1f | %6.1f | %5.2f | %6.1f | %6.1f | %-11s | %-12s | %s".fmt(
                        row.id,
                        cand.startM / 1000,
                        cand.endM / 1000,
                        m.lengthM,
                        mnhMax,
                        m.roughnessM,
                        m.slopeP95 * 100,
                        m.rawSlopeP95 * 100,
                        m.cleanFraction,
                        m.canyonM,
                        m.mntDropM,
                        cand.rejection?.name ?: (if (cand in sc.fps) "DECK (FP)" else "DECK"),
                        tallKinds(c, p, cand, default),
                        st,
                    ),
                )
            }
        }
        for (row in index.filter { it.positive }) {
            val sc = scores.getValue(row.id)
            val missed = sc.dSpans - sc.dFound.toSet()
            if (missed.isNotEmpty() || sc.dSmall.isNotEmpty()) {
                println(
                    "$tag   %s: D missed %s; D < 20 m (not counted) %s".fmt(
                        row.id,
                        missed.joinToString { "${it.name} (%.0f m)".fmt(it.lengthM) }.ifEmpty { "none" },
                        sc.dSmall.joinToString { "${it.name} (%.0f m)".fmt(it.lengthM) }.ifEmpty { "none" },
                    ),
                )
            }
        }
        // Where a held-out bridge is STEEP: which stations carry the slope, rebuilt as the detector
        // does (centred slope over ± slopeHalfWindowM on the MNS rolling median of surfaceMedianWindowM).
        val medianHalf = (default.surfaceMedianWindowM / default.stepM).roundToInt() / 2
        val slopeHalf = (default.slopeHalfWindowM / default.stepM).roundToInt()
        println()
        println(
            "$tag STEEP held-out bridges: stations with |slope| > maxSlopeP95, first 100 m / middle / last 100 m of the span; the five steepest",
        )
        for (row in index.filter { it.positive }) {
            val p = profiles.getValue(row.id)
            for (cand in scores.getValue(row.id).measured.filter { it.rejection == DeckRejection.STEEP }) {
                val mf =
                    DoubleArray(p.size) { k ->
                        median((maxOf(0, k - medianHalf)..minOf(p.size - 1, k + medianHalf)).map { p.mnsM[it] }.filter { !it.isNaN() })
                    }
                val slopes =
                    (cand.first + slopeHalf..cand.last - slopeHalf).map { k ->
                        k to (mf[k + slopeHalf] - mf[k - slopeHalf]) / (2 * slopeHalf * default.stepM)
                    }
                val steep = slopes.filter { abs(it.second) > default.maxSlopeP95 }
                val edge = 20
                println(
                    "$tag   %s km %.2f–%.2f: %d steep of %d (%d / %d / %d); %s".fmt(
                        row.id,
                        cand.startM / 1000,
                        cand.endM / 1000,
                        steep.size,
                        slopes.size,
                        steep.count { it.first < cand.first + edge },
                        steep.count { it.first in cand.first + edge..cand.last - edge },
                        steep.count { it.first > cand.last - edge },
                        steep.sortedByDescending { abs(it.second) }.take(5).joinToString(", ") { (k, sl) ->
                            "km %.3f %+.0f %% (MNS %.1f, med %.1f, MNT %.1f)".fmt(k * STEP_M / 1000, sl * 100, p.mnsM[k], mf[k], p.mntM[k])
                        },
                    ),
                )
            }
        }
        val scored = index.filter { it.id !in outOfCoverage }
        val negs = scored.filter { !it.positive }
        val poss = scored.filter { it.positive }
        val defaultDetections = scored.associate { it.id to DeckDetector.detect(profiles.getValue(it.id), default) }
        println()
        println(
            "$tag DEFAULT totals: negatives (%d scored) FP %d, stations outside truth G/O/L %d/%d/%d (mask %d/%d/%d), controls with a detection %d, own bridges found %d/%d; held-out recall %d/%d (D stations in accepted spans %d/%d = %d %%, in deck masks %d), FP on positives %d; Strava %d/21, FP %d, outside %d / %d"
                .fmt(
                    negs.size,
                    negs.sumOf { scores.getValue(it.id).fps.size },
                    negs.sumOf { scores.getValue(it.id).out('G') },
                    negs.sumOf { scores.getValue(it.id).out('O') },
                    negs.sumOf { scores.getValue(it.id).out('L') },
                    negs.sumOf { scores.getValue(it.id).outDeck.getValue('G') },
                    negs.sumOf { scores.getValue(it.id).outDeck.getValue('O') },
                    negs.sumOf { scores.getValue(it.id).outDeck.getValue('L') },
                    negs.count { scores.getValue(it.id).accepted.isNotEmpty() },
                    negs.sumOf { scores.getValue(it.id).ownFound.size },
                    negs.sumOf { scores.getValue(it.id).own.size },
                    poss.sumOf { scores.getValue(it.id).dFound.size },
                    poss.sumOf { scores.getValue(it.id).dSpans.size },
                    poss.sumOf { scores.getValue(it.id).dInSpan },
                    poss.sumOf { scores.getValue(it.id).dStations },
                    Math.round(100.0 * poss.sumOf { scores.getValue(it.id).dInSpan } / poss.sumOf { scores.getValue(it.id).dStations }),
                    poss.sumOf { scores.getValue(it.id).dInDeck },
                    poss.sumOf { scores.getValue(it.id).fps.size },
                    baseStrava.found,
                    baseStrava.fps.size,
                    baseStrava.outSpan,
                    baseStrava.outDeck,
                ),
        )
        for (r in negs) {
            val sc = scores.getValue(r.id)
            for (sp in sc.own) {
                println(
                    "$tag   own bridge %s %s (%.0f m): %s".fmt(
                        r.id,
                        sp.name,
                        sp.lengthM,
                        if (sp in sc.ownFound) "found" else verdictsOn(defaultDetections.getValue(r.id), sp.i0, sp.i1),
                    ),
                )
            }
        }
        // The acceptance criterion, asserted at DEFAULT only (the sweep stays print-only): no false
        // positive and no G/O/L station inside a detection on any negative, and the one own bridge
        // that DEFAULT finds (Beaujoire) still found. Every negative must be scored.
        assertEquals(CONTROL_ROLES.getValue("negative"), negs.size, "negatives inside LiDAR HD coverage")
        assertEquals(0, negs.sumOf { scores.getValue(it.id).fps.size }, "DEFAULT false positives on the negatives")
        for (k in "GOL") {
            assertEquals(0, negs.sumOf { scores.getValue(it.id).out(k) }, "DEFAULT $k stations inside detections on the negatives")
            assertEquals(
                0,
                negs.sumOf { scores.getValue(it.id).outDeck.getValue(k) },
                "DEFAULT $k stations inside deck masks on the negatives",
            )
        }
        assertTrue(
            scores.getValue("n04-nantes-periph-nord").ownFound.any { "Beaujoire" in it.name },
            "DEFAULT finds n04's own Pont de la Beaujoire",
        )
        // E2 ter: nothing PYTHON finds is lost, and the steep bridges are covered. Normandie, lost at
        // pixel centres under the E2 ter rule (P95 6.5 -> 41.1 %, elevation-half-pixel H3), is back
        // since E2 quinquies (cleanGapGroundMnhM): no span lost anywhere.
        val pyScores =
            scored.associate {
                it.id to
                    scoreControl(controls.getValue(it.id), spans, DeckDetector.detect(profiles.getValue(it.id), python), python)
            }
        for (r in scored) {
            val lost = pyScores.getValue(r.id).dFound - scores.getValue(r.id).dFound.toSet()
            assertTrue(lost.isEmpty(), "${r.id}: DEFAULT loses PYTHON's ${lost.map { it.name }}")
            val ownLost = pyScores.getValue(r.id).ownFound - scores.getValue(r.id).ownFound.toSet()
            assertTrue(ownLost.isEmpty(), "${r.id}: DEFAULT loses PYTHON's own bridge ${ownLost.map { it.name }}")
        }
        val dIn = poss.sumOf { scores.getValue(it.id).dInSpan }
        val dAll = poss.sumOf { scores.getValue(it.id).dStations }
        println(
            "$tag E2 ter against PYTHON: held-out spans %d/%d -> %d/%d, D stations in accepted spans %d -> %d / %d".fmt(
                poss.sumOf { pyScores.getValue(it.id).dFound.size },
                poss.sumOf { pyScores.getValue(it.id).dSpans.size },
                poss.sumOf { scores.getValue(it.id).dFound.size },
                poss.sumOf { scores.getValue(it.id).dSpans.size },
                poss.sumOf { pyScores.getValue(it.id).dInSpan },
                dIn,
                dAll,
            ),
        )
        for (r in poss) {
            val sc = scores.getValue(r.id)
            val py = pyScores.getValue(r.id)
            println(
                "$tag   %-22s spans %d/%d -> %d/%d, D coverage %d %% -> %d %%".fmt(
                    r.id,
                    py.dFound.size,
                    py.dSpans.size,
                    sc.dFound.size,
                    sc.dSpans.size,
                    py.coveragePct(),
                    sc.coveragePct(),
                ),
            )
        }
        // 94 % at the old corner convention; 73 % at pixel centres under E2 ter, Normandie's 2 150 m
        // deck being the difference (elevation-half-pixel H3); 98 % since E2 quinquies (Normandie
        // back, the rue des Ponts the only span missed). An equality, so a move either way shows.
        assertEquals(1666 to 1692, dIn to dAll, "DEFAULT D coverage on the held-out positives (98 %)")
        assertEquals(14, poss.sumOf { scores.getValue(it.id).dFound.size }, "DEFAULT held-out spans found")
        for (id in listOf("p01-chevire", "p07-millau-a75")) {
            val sc = scores.getValue(id)
            assertTrue(100.0 * sc.dInSpan / sc.dStations >= 95.0, "$id D coverage: ${sc.dInSpan} / ${sc.dStations}")
        }
        assertEquals(
            listOf("p02-thouare Rue des Ponts"),
            poss.flatMap { r -> scores.getValue(r.id).let { sc -> (sc.dSpans - sc.dFound.toSet()).map { "${r.id} ${it.name}" } } },
            "the held-out spans DEFAULT misses",
        )

        // ── Sensitivity: one variation at a time ──────────────────────────────────────────────
        val settings: List<Pair<String, DeckDetectorOptions>> =
            listOf(
                "DEFAULT" to default,
                "PYTHON (E2 bis DEFAULT)" to python,
                "slopeOnCleanDeck false" to default.copy(slopeOnCleanDeck = false),
                "mntAboveMnsAsTall false" to default.copy(mntAboveMnsAsTall = false),
                // E2 quinquies: each of its two changes undone, then both (E2 ter's DEFAULT).
                "splitSteepMerged false" to default.copy(splitSteepMerged = false),
                "cleanGapGroundMnhM 5 (E2 ter)" to default.copy(cleanGapGroundMnhM = 5.0),
                "E2 ter DEFAULT" to default.copy(splitSteepMerged = false, cleanGapGroundMnhM = 5.0),
                "cleanGapGroundMnhM 2.5" to default.copy(cleanGapGroundMnhM = 2.5),
                "maxSlopeP95 0.08" to default.copy(maxSlopeP95 = 0.08),
                "minCleanFraction 0" to default.copy(minCleanFraction = 0.0),
                "minCleanFraction 0.4" to default.copy(minCleanFraction = 0.4),
                "minCleanStationsCount 3" to default.copy(minCleanStationsCount = 3),
                "minCleanStationsCount 10" to default.copy(minCleanStationsCount = 10),
                "tallMnhM 4.0" to default.copy(tallMnhM = 4.0),
                "tallMnhM 3.0" to default.copy(tallMnhM = 3.0),
                "tallMnhM 2.5" to default.copy(tallMnhM = 2.5),
                "minTallRunM 10" to default.copy(minTallRunM = 10.0),
                "minTallRunM 5" to default.copy(minTallRunM = 5.0),
                "maxRoughnessM 1.0" to default.copy(maxRoughnessM = 1.0),
                "maxRoughnessM 1.2" to default.copy(maxRoughnessM = 1.2),
                "maxRoughnessM 1.5" to default.copy(maxRoughnessM = 1.5),
                // Also the clean threshold of the bridged slope: moves the run filter too.
                "maxRoughnessM 0.5 (+runs)" to default.copy(maxRoughnessM = 0.5),
                "minSmoothFraction 0.5" to default.copy(minSmoothFraction = 0.5),
                "minSmoothFraction 0.4" to default.copy(minSmoothFraction = 0.4),
                "maxShortRoughnessM 0.6" to default.copy(maxShortRoughnessM = 0.6),
                "maxShortRoughnessM 0.8" to default.copy(maxShortRoughnessM = 0.8),
                "roughnessWindowM 25" to default.copy(roughnessWindowM = 25.0),
                "roughnessWindowM 45" to default.copy(roughnessWindowM = 45.0),
                "surfaceMedianWindowM 15" to default.copy(surfaceMedianWindowM = 15.0),
                "surfaceMedianWindowM 35" to default.copy(surfaceMedianWindowM = 35.0),
                "mergeGapM 200" to default.copy(mergeGapM = 200.0),
                "mergeGapM 100" to default.copy(mergeGapM = 100.0),
                "mergeGapM 50" to default.copy(mergeGapM = 50.0),
                "maxSlopeP95 0.12" to default.copy(maxSlopeP95 = 0.12),
                "maxSlopeP95 0.15" to default.copy(maxSlopeP95 = 0.15),
                "minCanyonDropM 2.0" to default.copy(minCanyonDropM = 2.0),
                "minCanyonDropM 1.0" to default.copy(minCanyonDropM = 1.0),
                // Not candidates: the canyon test rejects every measured candidate on the negatives;
                // these rows show what the other guards hold on their own (−1 m: canyon test off,
                // every measured drop on the controls is above −1 m).
                "minCanyonDropM 0.3" to default.copy(minCanyonDropM = 0.3),
                "minCanyonDropM -1 (off)" to default.copy(minCanyonDropM = -1.0),
                "PYTHON, canyon off" to python.copy(minCanyonDropM = -1.0),
                "deckMnhM 0.5" to default.copy(deckMnhM = 0.5),
                "deckMnhM 2.0" to default.copy(deckMnhM = 2.0),
                "minLengthM 20" to default.copy(minLengthM = 20.0),
                "stress, not a proposal" to
                    default.copy(tallMnhM = 3.0, minTallRunM = 10.0, maxRoughnessM = 1.2, maxShortRoughnessM = 0.8, mergeGapM = 100.0),
                // E2 quater: the two variations that cost nothing on the E2 bis sweep, together.
                "mergeGapM 200 + median 35" to default.copy(mergeGapM = 200.0, surfaceMedianWindowM = 35.0),
            )
        assertEquals(45, settings.size, "DEFAULT + 44 variants")
        val negGol = HashMap<String, Int>()
        val bridges = structures.filter { it.kind == "bridge" }
        val lostFive = LOST_FIVE.mapValues { (_, needle) -> bridges.single { needle in it.name }.key }
        assertEquals(5, lostFive.size)
        // Two of E2's five lost bridges are found since E2 quinquies, by the split of a steep merge.
        assertEquals(
            setOf("Bellevue", "Haudaudine"),
            lostFive.filter { it.value in baseStrava.foundKeys }.keys,
            "the bridges of LOST_FIVE that DEFAULT finds",
        )
        val baseOwn = negs.flatMap { r -> scores.getValue(r.id).ownFound.map { "${r.id} ${it.i0}" } }.toSet()
        println()
        println(
            "$tag sensitivity, one variation at a time from DEFAULT (stress row: several at once). Strava: bridges /21 (t1/t2/t3/t4), " +
                "of the 5 lost found, lost vs DEFAULT (verdict), FP, outside span/mask; held-out recall; " +
                "negatives: FP, outside G/O/L (span), " +
                "own bridges found /${negs.sumOf { scores.getValue(it.id).own.size }} (lost vs DEFAULT, verdict)",
        )
        println(
            "$tag | %-24s | %-14s | %-30s | %-40s | %-9s | %-30s | %-6s | %-11s | %s |".fmt(
                "setting",
                "bridges /21",
                "recovered",
                "lost (verdict)",
                "FP / out",
                "held-out recall (missed)",
                "neg FP",
                "neg G/O/L",
                "own bridges",
            ),
        )
        for ((label, o) in settings) {
            val dets = strava.mapValues { DeckDetector.detect(it.value, o) }
            val ss = scoreStrava(dets, structures)
            val recovered = lostFive.filter { it.value in ss.foundKeys }.keys
            val lost =
                bridges
                    .filter { it.key in baseStrava.foundKeys && it.key !in ss.foundKeys }
                    .map { "${it.label} (${verdictsOn(dets.getValue(it.route), it.i0, it.i1)})" }
            val cd = scored.associate { it.id to DeckDetector.detect(profiles.getValue(it.id), o) }
            val sc = scored.associate { it.id to scoreControl(controls.getValue(it.id), spans, cd.getValue(it.id), o) }
            val missedPos =
                poss.flatMap { r ->
                    val s = sc.getValue(r.id)
                    (s.dSpans - s.dFound.toSet()).map { "${r.id.substringBefore('-')} ${it.name.take(18)}" }
                }
            val ownLost =
                negs.flatMap { r ->
                    sc
                        .getValue(r.id)
                        .own
                        .filter { "${r.id} ${it.i0}" in baseOwn && it !in sc.getValue(r.id).ownFound }
                        .map {
                            "${r.id.substringBefore(
                                '-',
                            )} ${it.name.substringAfterLast(" - ").take(22)} (${verdictsOn(cd.getValue(r.id), it.i0, it.i1)})"
                        }
                }
            val ownGained =
                negs.flatMap { r ->
                    sc
                        .getValue(
                            r.id,
                        ).ownFound
                        .filter { "${r.id} ${it.i0}" !in baseOwn }
                        .map { "+${r.id.substringBefore('-')} ${it.name.take(22)}" }
                }
            val dIn = poss.sumOf { sc.getValue(it.id).dInSpan }
            val dAll = poss.sumOf { sc.getValue(it.id).dStations }
            negGol[label] = negs.sumOf { r -> "GOL".sumOf { sc.getValue(r.id).out(it) } }
            println(
                "$tag | %-24s | %2d (%s) | %-30s | %-40s | %d / %d/%d | %-30s | %-6s | %-11s | %s |".fmt(
                    label,
                    ss.found,
                    ss.perRoute.joinToString("/"),
                    recovered.joinToString(", ").ifEmpty { "—" },
                    lost.joinToString(", ").ifEmpty { "—" },
                    ss.fps.size,
                    ss.outSpan,
                    ss.outDeck,
                    "%d/%d %d%%%s".fmt(
                        poss.sumOf { sc.getValue(it.id).dFound.size },
                        poss.sumOf { sc.getValue(it.id).dSpans.size },
                        Math.round(100.0 * dIn / dAll),
                        if (missedPos.isEmpty()) "" else " (${missedPos.joinToString(", ")})",
                    ),
                    "${negs.sumOf { sc.getValue(it.id).fps.size }}" +
                        (if (poss.sumOf { sc.getValue(it.id).fps.size } > 0) " +${poss.sumOf { sc.getValue(it.id).fps.size }}p" else ""),
                    "%d/%d/%d".fmt(
                        negs.sumOf { sc.getValue(it.id).out('G') },
                        negs.sumOf { sc.getValue(it.id).out('O') },
                        negs.sumOf { sc.getValue(it.id).out('L') },
                    ),
                    "%d%s".fmt(
                        negs.sumOf { sc.getValue(it.id).ownFound.size },
                        (ownLost.map { "−$it" } + ownGained).joinToString(", ", prefix = " ").takeIf { it.isNotBlank() } ?: "",
                    ),
                ),
            )
            // Where a relaxation lets something through on a control, say what and where.
            if (label != "DEFAULT") {
                for ((route, cand) in ss.fps) {
                    val p = strava.getValue(route)
                    val m = cand.metrics!!
                    val inside = structures.filter { it.route == route && cand.first <= it.i1 && cand.last >= it.i0 }
                    println(
                        "$tag     Strava FP %s km %.2f–%.2f, L %.0f m, MNH max %.1f, rough %.2f, P95 %.1f %%, drop %.1f%s".fmt(
                            route,
                            cand.startM / 1000,
                            cand.endM / 1000,
                            m.lengthM,
                            (cand.first..cand.last).maxOf { k -> p.mnhM[k].takeUnless { it.isNaN() } ?: Double.NEGATIVE_INFINITY },
                            m.roughnessM,
                            m.slopeP95 * 100,
                            m.mntDropM,
                            inside.joinToString(prefix = " — inside ") { "${it.kind} ${it.label}" }.takeIf { inside.isNotEmpty() } ?: "",
                        ),
                    )
                }
                for (r in negs) {
                    val ctl = controls.getValue(r.id)
                    for (cand in sc.getValue(r.id).accepted - sc.getValue(r.id).fps.toSet()) {
                        val gol = (cand.first..cand.last).count { ctl.kind[it] in "GOL" }
                        if (gol == 0) continue
                        val m = cand.metrics!!
                        println(
                            "$tag     G/O/L in %s km %.2f–%.2f (on its own structure): %d stations, L %.0f m, P95 %.1f %% (raw %.1f, clean %.2f)"
                                .fmt(
                                    r.id,
                                    cand.startM / 1000,
                                    cand.endM / 1000,
                                    gol,
                                    m.lengthM,
                                    m.slopeP95 * 100,
                                    m.rawSlopeP95 * 100,
                                    m.cleanFraction,
                                ),
                        )
                    }
                }
                for (r in scored) {
                    for (cand in sc.getValue(r.id).fps) {
                        val p = profiles.getValue(r.id)
                        val m = cand.metrics!!
                        println(
                            "$tag     FP %s km %.2f–%.2f, L %.0f m, MNH max %.1f, rough %.2f, P95 %.1f %%, drop %.1f, tall %s".fmt(
                                r.id,
                                cand.startM / 1000,
                                cand.endM / 1000,
                                m.lengthM,
                                (cand.first..cand.last).maxOf { k -> p.mnhM[k].takeUnless { it.isNaN() } ?: Double.NEGATIVE_INFINITY },
                                m.roughnessM,
                                m.slopeP95 * 100,
                                m.mntDropM,
                                tallKinds(controls.getValue(r.id), p, cand, o),
                            ),
                        )
                    }
                }
            }
        }
        // Diagnostic guard: with the canyon test off, the bridged slope lets no more canopy or
        // buildings through than the E2 bis detector did (10 G/O/L stations, n04 and n09).
        assertTrue(
            negGol.getValue("minCanyonDropM -1 (off)") <= negGol.getValue("PYTHON, canyon off"),
            "canyon off: DEFAULT G/O/L ${negGol["minCanyonDropM -1 (off)"]} > PYTHON ${negGol["PYTHON, canyon off"]}",
        )

        // ── E2 quater: ravine negatives, and the two cheap variations together ────────
        val quater =
            listOf(
                "DEFAULT" to default,
                "mergeGapM 200" to default.copy(mergeGapM = 200.0),
                "surfaceMedianWindowM 35" to default.copy(surfaceMedianWindowM = 35.0),
                "mergeGapM 200 + median 35" to default.copy(mergeGapM = 200.0, surfaceMedianWindowM = 35.0),
            )
        val ravines = negs.filter { it.category == RAVINE_CATEGORY }
        assertEquals(RAVINE_NEGATIVES, ravines.size, "ravine negatives scored")
        println()
        println(
            "$tag E2 quater: per held-out positive, spans found / counted and D coverage; then every candidate measured on the ravine negatives",
        )
        for ((label, o) in quater) {
            val sc =
                scored.associate {
                    it.id to scoreControl(controls.getValue(it.id), spans, DeckDetector.detect(profiles.getValue(it.id), o), o)
                }
            println(
                "$tag   %-26s %s".fmt(
                    label,
                    poss.joinToString("  ") { r ->
                        val s = sc.getValue(r.id)
                        "${r.id.substringBefore('-')} ${s.dFound.size}/${s.dSpans.size} ${s.coveragePct()}%"
                    },
                ),
            )
            // Where a positive loses coverage against DEFAULT: what the detector did on its D stations.
            for (r in poss) {
                val s = sc.getValue(r.id)
                if (s.dInSpan >= scores.getValue(r.id).dInSpan) continue
                val d = DeckDetector.detect(profiles.getValue(r.id), o)
                for (cand in (d.accepted + d.rejected.filter { it.metrics != null }).sortedBy { it.first }) {
                    if (s.dSpans.none { cand.first <= it.i1 && cand.last >= it.i0 }) continue
                    val m = cand.metrics!!
                    println(
                        "$tag     %-28s km %.2f–%.2f, L %.0f m, P95 %.1f %% (raw %.1f, clean %.2f, onClean %s), drop %.1f, %s".fmt(
                            r.id,
                            cand.startM / 1000,
                            cand.endM / 1000,
                            m.lengthM,
                            m.slopeP95 * 100,
                            m.rawSlopeP95 * 100,
                            m.cleanFraction,
                            m.slopeOnCleanDeck,
                            m.mntDropM,
                            cand.rejection?.name ?: "DECK",
                        ),
                    )
                }
            }
            for (r in ravines) {
                val c = controls.getValue(r.id)
                val p = profiles.getValue(r.id)
                val s = sc.getValue(r.id)
                for (cand in (s.accepted + s.measured).sortedBy { it.first }) {
                    val m = cand.metrics!!
                    println(
                        "$tag     %-28s km %.2f–%.2f, L %.0f m, drop %.1f, P95 %.1f %% (raw %.1f, clean %.2f, onClean %s), rough %.2f, %s, tall %s, tall runs %d"
                            .fmt(
                                r.id,
                                cand.startM / 1000,
                                cand.endM / 1000,
                                m.lengthM,
                                m.mntDropM,
                                m.slopeP95 * 100,
                                m.rawSlopeP95 * 100,
                                m.cleanFraction,
                                m.slopeOnCleanDeck,
                                m.roughnessM,
                                cand.rejection?.name ?: (if (cand in s.fps) "DECK (FP)" else "DECK"),
                                tallKinds(c, p, cand, o),
                                tallRunsIn(p, cand, o),
                            ),
                    )
                }
                // A ravine negative is only a witness while DEFAULT still measures a candidate past
                // the canyon test on it: if the candidate vanishes (it is fragile, see the task doc),
                // 0 FP / 0 G/O/L above would pass on nothing. At pixel centres n13's candidate did
                // vanish (elevation-half-pixel H3): pinned as such, so its return is seen too.
                if (label == "DEFAULT") {
                    val witnesses = s.measured.filter { it.metrics!!.mntDropM > o.minCanyonDropM }
                    if (r.id in RAVINES_WITHOUT_WITNESS) {
                        assertTrue(
                            witnesses.isEmpty(),
                            "${r.id}: a candidate past the canyon test is back at DEFAULT — re-read elevation-half-pixel H3",
                        )
                    } else {
                        assertTrue(
                            witnesses.isNotEmpty(),
                            "${r.id}: no candidate past the canyon test at DEFAULT, the ravine negative tests nothing",
                        )
                        assertTrue(
                            witnesses.any { tallRunsIn(p, it, o) >= 2 },
                            "${r.id}: no measured candidate merges two tall runs at DEFAULT",
                        )
                    }
                }
            }
        }
        println()
        println(
            "$tag tiles: %d from disk, %d fetched (%.1f MB) in this test".fmt(tileHits.get(), tileMisses.get(), bytesFetched.get() / 1e6),
        )
    }

    // ── E3: the deck profile ────────────────────────────────────────────────────────────────

    /** Rolling nan-quantile ([q] in 0..1, numpy's linear percentile) over `2·half + 1` stations, clamped. */
    private fun rollingNanQuantile(
        x: DoubleArray,
        half: Int,
        q: Double,
    ): DoubleArray =
        DoubleArray(x.size) { k ->
            val w = (k - half..k + half).map { x[it.coerceIn(0, x.size - 1)] }.filter { !it.isNaN() }.sorted()
            if (w.isEmpty()) {
                Double.NaN
            } else {
                val rank = (w.size - 1) * q
                val lo = floor(rank).toInt()
                w[lo] + (w[minOf(lo + 1, w.size - 1)] - w[lo]) * (rank - lo)
            }
        }

    /** The detector's `clean` (window-max roughness below `maxRoughnessM`), rebuilt from its definition. */
    private fun cleanStations(
        p: DeckProfile,
        o: DeckDetectorOptions,
    ): BooleanArray {
        val n = p.size
        val half = (o.roughnessWindowM / o.stepM).roundToInt() / 2
        val mf = rollingNanQuantile(p.mnsM, (o.surfaceMedianWindowM / o.stepM).roundToInt() / 2, 0.5)
        val rough =
            DoubleArray(n) { k ->
                sqrt(
                    (k - half..k + half).sumOf { j -> (p.mnsM[j.coerceIn(0, n - 1)] - mf[j.coerceIn(0, n - 1)]).let { it * it } } /
                        (2 * half + 1),
                )
            }
        return BooleanArray(n) { k ->
            val w = (k - half..k + half).map { rough[it.coerceIn(0, n - 1)] }
            w.none { it.isNaN() } && w.max() < o.maxRoughnessM
        }
    }

    /**
     * A profile of [rep]'s stretch from [deck] on its anchors' span: pinned to the MNT at the pins,
     * flat to a route end, linear between — what [DeckReplacement.withEnds] does to the code's own.
     */
    private fun ramped(
        rep: DeckReplacement,
        deck: DoubleArray,
        mnt: DoubleArray,
    ): DoubleArray {
        val z = DoubleArray(rep.last - rep.first + 1) { Double.NaN }
        for (k in rep.anchorFirst..rep.anchorLast) z[k - rep.first] = deck[k - rep.anchorFirst]
        if (rep.pinnedStart) z[0] = mnt[rep.first] else for (k in rep.first until rep.anchorFirst) z[k - rep.first] = deck.first()
        if (rep.pinnedEnd) z[z.size - 1] = mnt[rep.last] else for (k in rep.anchorLast + 1..rep.last) z[k - rep.first] = deck.last()
        var prev = -1
        for (j in z.indices) {
            if (z[j].isNaN()) continue
            if (prev >= 0) for (m in prev + 1 until j) z[m] = z[prev] + (z[j] - z[prev]) * (m - prev) / (j - prev)
            prev = j
        }
        return z
    }

    /** The §1.1 variants of the E3 plan on [rep]: R1 raw `mf` on the deck mask, R4 anchors + median, R5 anchors + P25, R8 the code. */
    private fun deckVariants(
        p: DeckProfile,
        rep: DeckReplacement,
        o: DeckDetectorOptions,
    ): Map<String, DoubleArray> {
        val half = (o.surfaceMedianWindowM / o.stepM).roundToInt() / 2
        val mf = rollingNanQuantile(p.mnsM, half, 0.5)
        val mask = rep.spans.flatMap { c -> c.deckRanges.flatMap { it.toList() } }.toSet()
        val r1 =
            DoubleArray(rep.last - rep.first + 1) { j ->
                (rep.first + j).let { k ->
                    if (k in mask &&
                        !mf[k].isNaN()
                    ) {
                        mf[k]
                    } else {
                        p.mntM[k]
                    }
                }
            }
        val u = rep.anchorFirst
        val v = rep.anchorLast
        val x = DoubleArray(v - u + 1 + 2 * half) { Double.NaN }
        for (j in 0 until rep.anchors.size - 1) {
            val c0 = rep.anchors[j]
            val c1 = rep.anchors[j + 1]
            for (k in c0..c1) x[k - u + half] = p.mnsM[c0] + (p.mnsM[c1] - p.mnsM[c0]) * (k - c0) / (c1 - c0)
        }

        fun anchored(q: Double) = rollingNanQuantile(x, half, q).let { m -> DoubleArray(v - u + 1) { m[it + half] } }
        return linkedMapOf(
            "R1 raw mf on mask" to r1,
            "R4 anchors+median" to ramped(rep, anchored(0.5), p.mntM),
            "R5 anchors+P25" to ramped(rep, anchored(0.25), p.mntM),
            "R8 code (cap)" to rep.withEnds(p.mntM[rep.first], p.mntM[rep.last]),
        )
    }

    /**
     * One region of E3, every §1.1 variant: coverage of the deck stations ([deckKind]: `B` on the
     * Strava routes, `D` on the controls), slope P95 (20 m, the detector's) and max (5 m) on them
     * inside the anchors' span, the abutment steps (profile at the first / last anchor minus the MNT
     * at the pin), the deck top, "deck RMS" — RMS of profile − `mf` on the clean deck stations, a
     * self-consistency check, **not** a truth — and, where [truthZ] exists, RMS / max against it.
     * Returns the variants' max 5 m slope on the anchors' span, by label.
     */
    private fun printRegion(
        tag: String,
        id: String,
        p: DeckProfile,
        rep: DeckReplacement,
        kind: CharArray,
        deckKind: Char,
        truthZ: DoubleArray?,
        o: DeckDetectorOptions,
    ): Map<String, Pair<Double, Double>> {
        val half = (o.surfaceMedianWindowM / o.stepM).roundToInt() / 2
        val mf = rollingNanQuantile(p.mnsM, half, 0.5)
        val clean = cleanStations(p, o)
        val onDeck = (rep.first..rep.last).filter { kind[it] == deckKind }
        val deckRuns = runsOf(kind, deckKind).filter { it.first <= rep.last && it.last >= rep.first }
        val deckAll = deckRuns.sumOf { it.last - it.first + 1 }
        val inSpan = deckRuns.sumOf { r -> r.count { it in rep.spanFirst..rep.spanLast } }
        println(
            "$tag E3 %s km %.2f–%.2f (span %.2f–%.2f, anchors %.2f–%.2f, %d anchors, %d span(s)): %c coverage %d / %d".fmt(
                id,
                rep.first * o.stepM / 1000,
                rep.last * o.stepM / 1000,
                rep.spanFirst * o.stepM / 1000,
                rep.spanLast * o.stepM / 1000,
                rep.anchorFirst * o.stepM / 1000,
                rep.anchorLast * o.stepM / 1000,
                rep.anchors.size,
                rep.spans.size,
                deckKind,
                inSpan,
                deckAll,
            ),
        )
        val out = LinkedHashMap<String, Pair<Double, Double>>()
        for ((label, z) in deckVariants(p, rep, o)) {
            fun at(k: Int) = z[k - rep.first]
            val inner = onDeck.filter { it - 2 >= rep.anchorFirst && it + 2 <= rep.anchorLast }
            val slopes20 = inner.map { abs(at(it + 2) - at(it - 2)) / (4 * o.stepM) }.sorted()
            val p95 =
                if (slopes20.isEmpty()) {
                    Double.NaN
                } else {
                    val rank = (slopes20.size - 1) * 0.95
                    val lo = floor(rank).toInt()
                    slopes20[lo] + (slopes20[minOf(lo + 1, slopes20.size - 1)] - slopes20[lo]) * (rank - lo)
                }
            val step5 = onDeck.filter { it in rep.anchorFirst until rep.anchorLast }.map { abs(at(it + 1) - at(it)) / o.stepM }
            val max5 = step5.maxOrNull() ?: Double.NaN
            val maxAll = (rep.anchorFirst until rep.anchorLast).maxOfOrNull { abs(at(it + 1) - at(it)) / o.stepM } ?: Double.NaN
            out[label] = max5 to maxAll
            val cleanDeck = onDeck.filter { clean[it] && !mf[it].isNaN() }
            val deckRms = sqrt(cleanDeck.sumOf { (at(it) - mf[it]).let { d -> d * d } } / cleanDeck.size.coerceAtLeast(1))
            val truth =
                truthZ?.let { tz ->
                    val e = onDeck.map { at(it) - tz[it] }
                    val worst = e.maxByOrNull { abs(it) } ?: Double.NaN
                    " | truth %c rms %5.2f max %+6.2f".fmt(deckKind, sqrt(e.sumOf { it * it } / e.size.coerceAtLeast(1)), worst)
                } ?: ""
            println(
                "$tag     %-18s P95 %5.1f %% max %5.1f %% (all anchors' span %5.1f %%) | abutments %+5.2f / %+5.2f m | top %7.2f | deck RMS %5.2f on %d clean%s"
                    .fmt(
                        label,
                        p95 * 100,
                        max5 * 100,
                        maxAll * 100,
                        at(rep.anchorFirst) - p.mntM[rep.first],
                        at(rep.anchorLast) - p.mntM[rep.last],
                        (rep.anchorFirst..rep.anchorLast).maxOf { at(it) },
                        deckRms,
                        cleanDeck.size,
                        truth,
                    ),
            )
        }
        return out
    }

    /**
     * E3, `INTEGRATION=1`: [DeckProfileBuilder] on the variant (a) samples of the Strava routes and
     * of the tuning controls `p01..p08` (`p09..` dropped before any tile is read, as in
     * [deckControls]), with the §1.1 variants of the E3 plan side by side. The controls have **no
     * elevation truth**: their "deck RMS" measures the profile against the raw MNS median on clean
     * deck stations, a self-consistency check. Asserted: every profile keeps its slope at or under
     * `maxSlopeP95` between two stations of its anchors' span; Saint-Nazaire's `B` RMS ≤ 0.15 m;
     * the cap is what bounds Cheviré, Normandie and Millau (their max reaches it).
     */
    @Test
    fun deckProfiles() {
        if (!enabled || dataDir == null) {
            println("[profiles] set INTEGRATION=1 (and run from the repository) to measure the E3 deck profiles")
            return
        }
        val tag = "[profiles]"
        val o = DeckDetectorOptions.DEFAULT
        val cap = o.maxSlopeP95
        val structures = readStructures()
        val (allIndex, controls, _) = readControls()
        val tuning = allIndex.filter { it.positive && !it.holdout2 }
        assertEquals(CONTROL_ROLES.getValue("held-out-positive"), tuning.size, "p01..p08")
        println("$tag tile cache: ${cacheDir.absolutePath}")
        val maxima = LinkedHashMap<String, List<Map<String, Pair<Double, Double>>>>()
        var nazaireRms = Double.NaN
        for ((route, rows) in ROUTES) {
            val t = readTruth(route)
            assertEquals(rows, t.size)
            val p = sampleSurface(route, t.lat, t.lon)
            val built = DeckProfileBuilder.build(p, DeckDetector.detect(p, o), o)
            println()
            println("$tag ═══ $route: %d region(s), %d dropped".fmt(built.replacements.size, built.dropped.size))
            maxima[route] =
                built.replacements.map { rep ->
                    printRegion(tag, route, p, rep, t.kind, 'B', t.z, o).also {
                        if (structures.any { it.route == route && "Saint-Nazaire" in it.name && it.i0 <= rep.last && it.i1 >= rep.first }) {
                            val z = rep.withEnds(p.mntM[rep.first], p.mntM[rep.last])
                            val e = (rep.first..rep.last).filter { t.kind[it] == 'B' }.map { z[it - rep.first] - t.z[it] }
                            nazaireRms = sqrt(e.sumOf { it * it } / e.size)
                        }
                    }
                }
        }
        for (row in tuning) {
            val c = controls.getValue(row.id)
            val p = sampleSurface(row.id, c.lat, c.lon)
            val built = DeckProfileBuilder.build(p, DeckDetector.detect(p, o), o)
            println()
            println(
                "$tag ═══ ${row.id}: %d region(s), %d dropped (no elevation truth: deck RMS is against the raw MNS median)".fmt(
                    built.replacements.size,
                    built.dropped.size,
                ),
            )
            maxima[row.id] = built.replacements.map { rep -> printRegion(tag, row.id, p, rep, c.kind, 'D', null, o) }
        }
        println()
        println("$tag Saint-Nazaire B rms (code): %.3f m".fmt(nazaireRms))
        for ((id, regions) in maxima) {
            for (m in regions) {
                val (_, all) = m.getValue("R8 code (cap)")
                assertTrue(all <= cap + 1e-9, "$id: a slope of ${all * 100} % between two stations of the anchors' span")
            }
        }
        assertTrue(nazaireRms <= 0.15, "Saint-Nazaire B rms $nazaireRms")
        for (id in listOf("p01-chevire", "p06-normandie", "p07-millau-a75")) {
            val top = maxima.getValue(id).maxOf { it.getValue("R8 code (cap)").first }
            assertEquals(cap, top, 1e-9, "$id: the cap bounds the deck")
        }
    }

    /**
     * E3, `INTEGRATION=1`: `sample.gpx` (130 km, the project's reference ride) through
     * `ign-lidar-hd` with and without its surface model — the spec's Validation asks for the
     * reported D+ and the simulated duration within 0.5 % off structures, and every difference
     * explained structure by structure. Printed: each replaced stretch, its length, the deck top
     * against the terrain it replaces, and the D+ of the dense `sourceElevation` with and without.
     */
    @Test
    fun deckSampleGpx() {
        if (!enabled || dataDir == null) {
            println("[sample] set INTEGRATION=1 (and run from the repository) to run sample.gpx with and without decks")
            return
        }
        val tag = "[sample]"
        val file = File(dataDir.parentFile.parentFile, "gpx/src/commonTest/resources/sample.gpx")
        val input = GpxParser.parse(file.readText()).firstTrackAsPath()
        val h0 = tileHits.get()
        val m0 = tileMisses.get()
        val results =
            listOf(false, true).associateWith { decks ->
                val ign = ElevationSource.IGN_LIDAR_HD.provider(null) { cfg -> cachedFetcher(cfg) }
                val provider = if (decks) ign else ign.withSurfaceModel(null)
                runBlocking { Enhancer.enhanceCourseDefault(input, provider, EnhanceOptions.DEFAULT) }
            }
        val ign = ElevationSource.IGN_LIDAR_HD.provider(null) { cfg -> cachedFetcher(cfg) }
        val (pre, deck) =
            runBlocking {
                var p = PointPerDistance.compute(input, minDistanceM = -1.0, maxDistanceM = 30.0)
                p = ElevationStep.fixElevation(p, ign)
                p = PointPerDistance.compute(p, minDistanceM = 1.0, maxDistanceM = 2.0)
                p to BridgeDeckStep.apply(p, ign, ign.surfaceModel!!)
            }
        val without = results.getValue(false)
        val with = results.getValue(true)
        println(
            "$tag sample.gpx %.1f km | reported D+ %.1f -> %.1f m (%+.2f %%) | duration %.0f -> %.0f s (%+.2f %%) | deck step %s, %d region(s), %d dropped"
                .fmt(
                    input.totalDistance / 1000,
                    without.reportedElevationGain,
                    with.reportedElevationGain,
                    100 * (with.reportedElevationGain / without.reportedElevationGain - 1),
                    without.durationMs / 1000,
                    with.durationMs / 1000,
                    100 * (with.durationMs / without.durationMs - 1),
                    deck.status,
                    deck.replacements.size,
                    deck.dropped.size,
                ),
        )

        fun denseGain(p: Path): Double {
            val d = DoubleArray(p.size) { p.distance(it) }
            val z5 = grid5(d, DoubleArray(p.size) { p.elevation(it) })
            return ElevationGain.compute(DoubleArray(z5.size) { it * STEP_M }, z5, ElevationGainOptions.DEFAULT).gainM
        }
        println("$tag dense profile (5 m grid, dem preset): D+ %.1f -> %.1f m".fmt(denseGain(pre), denseGain(deck.path)))
        for (rep in deck.replacements) {
            val idx = (0 until pre.size).filter { pre.distance(it) in rep.first * STEP_M..rep.last * STEP_M }
            println(
                "$tag   km %7.2f–%7.2f, %3.0f m, %d span(s), %3d anchors: terrain %6.1f..%6.1f m -> deck %6.1f..%6.1f m, mean lift %+.1f m (%.5f, %.5f)"
                    .fmt(
                        rep.first * STEP_M / 1000,
                        rep.last * STEP_M / 1000,
                        (rep.last - rep.first) * STEP_M,
                        rep.spans.size,
                        rep.anchors.size,
                        idx.minOf { pre.elevation(it) },
                        idx.maxOf { pre.elevation(it) },
                        idx.minOf { deck.path.elevation(it) },
                        idx.maxOf { deck.path.elevation(it) },
                        idx.sumOf { deck.path.elevation(it) - pre.elevation(it) } / idx.size,
                        pre.latitudeDeg(idx[idx.size / 2]),
                        pre.longitudeDeg(idx[idx.size / 2]),
                    ),
            )
        }
        println(
            "$tag tiles: %d from disk, %d fetched in this test (%.1f MB in total)".fmt(
                tileHits.get() - h0,
                tileMisses.get() - m0,
                bytesFetched.get() / 1e6,
            ),
        )
    }

    /** Variant (a) of [deckDetector]: MNS − MNT z15, bilinear, one layer after the other. */
    private fun sampleSurface(
        key: String,
        lat: DoubleArray,
        lon: DoubleArray,
    ): DeckProfile {
        val stations =
            lat.indices.map {
                io.github.glandais.elevation
                    .LatLon(lat[it], lon[it])
            }
        val mnt = sampleLayer(key, "MNT z15", stations, IgnLidarHdLayer.MNT.provider(15) { countingFetcher(it) })
        val mns = sampleLayer(key, "MNS z15", stations, IgnLidarHdLayer.MNS.provider(15) { countingFetcher(it) })
        return DeckProfile.fromSurface(mns, mnt)
    }

    /**
     * E2 quinquies, the judge: [DeckDetector] on the second held-out series `p09..` (role
     * `holdout2` in `index.csv`), frozen by the rule of `elevation-bridge-decks.md` *before* any
     * detector run. Gated twice — `INTEGRATION=1` **and** `DECK_JUDGE=1` — so that no calibration
     * run, and no `INTEGRATION=1` run of the whole class, ever reads it: [deckControls] drops these
     * rows before sampling.
     *
     *     INTEGRATION=1 DECK_JUDGE=1 ./gradlew :engine:jvmTest --tests '*deckHoldoutJudge*' --rerun-tasks -i
     *
     * Sampled exactly as [deckControls] (variant (a)), scored exactly as its held-out positives, at
     * [DeckDetectorOptions.DEFAULT], the E2 ter DEFAULT (`10faced`) then [DeckDetectorOptions.PYTHON].
     * Printed, never asserted beyond data integrity: a failure here is reported in the task doc,
     * never tuned. A threshold changed after reading this output makes the series a calibration
     * set, and a third series is then needed.
     */
    @Test
    fun deckHoldoutJudge() {
        if (!enabled || dataDir == null || System.getenv(JUDGE_ENV) != "1") {
            println("[judge] set INTEGRATION=1 and $JUDGE_ENV=1 to judge the deck detector on the second held-out series (p09..)")
            return
        }
        val tag = "[judge]"
        val (allIndex, controls, spans) = readControls()
        val judged = allIndex.filter { it.holdout2 }
        assertEquals(CONTROL_ROLES.getValue(HOLDOUT2), judged.size, "holdout2 rows")
        println("$tag tile cache: ${cacheDir.absolutePath}")
        val profiles = LinkedHashMap<String, DeckProfile>()
        val outOfCoverage = LinkedHashSet<String>()
        for (row in judged) {
            val c = controls.getValue(row.id)
            val p = sampleSurface(row.id, c.lat, c.lon)
            profiles[row.id] = p
            val nanMnt = p.mntM.count { it.isNaN() }
            if (nanMnt > 0.01 * c.size) outOfCoverage += row.id
            println(
                "$tag   %-26s %-30s %5d stations, NaN MNT %d, MNS %d%s".fmt(
                    row.id,
                    row.category,
                    c.size,
                    nanMnt,
                    p.mnsM.count { it.isNaN() },
                    if (row.id in outOfCoverage) "  ← OUTSIDE COVERAGE (left out of the totals)" else "",
                ),
            )
        }
        val scored = judged.filter { it.id !in outOfCoverage }
        // The E2 ter DEFAULT (10faced), as the sweep of deckControls builds it: the gain of E2
        // quinquies is attributable only against it.
        val e2terDefault = DeckDetectorOptions.DEFAULT.copy(splitSteepMerged = false, cleanGapGroundMnhM = 5.0)
        for ((label, opts) in listOf(
            "DEFAULT" to DeckDetectorOptions.DEFAULT,
            "E2 ter DEFAULT" to e2terDefault,
            "PYTHON" to DeckDetectorOptions.PYTHON,
        )) {
            println()
            println(
                "$tag $label: spans found / counted, D coverage, accepted spans, FP, G/O/L stations in spans (mask), missed spans with their verdicts",
            )
            val scores = LinkedHashMap<String, ControlScore>()
            for (row in judged) {
                val c = controls.getValue(row.id)
                val d = DeckDetector.detect(profiles.getValue(row.id), opts)
                val sc = scoreControl(c, spans, d, opts)
                scores[row.id] = sc
                println(
                    "$tag   %-26s %d/%d %3d %% | %-24s | FP %d | %d/%d/%d (%d/%d/%d) | %s".fmt(
                        row.id,
                        sc.dFound.size,
                        sc.dSpans.size,
                        sc.coveragePct(),
                        sc.accepted.joinToString(" ") { "%.2f–%.2f".fmt(it.startM / 1000, it.endM / 1000) }.ifEmpty { "—" },
                        sc.fps.size,
                        sc.out('G'),
                        sc.out('O'),
                        sc.out('L'),
                        sc.outDeck['G'],
                        sc.outDeck['O'],
                        sc.outDeck['L'],
                        (sc.dSpans - sc.dFound.toSet())
                            .joinToString { "${it.name} %.0f m (${verdictsOn(d, it.i0, it.i1)})".fmt(it.lengthM) }
                            .ifEmpty { "none missed" },
                    ),
                )
                if (label != "PYTHON") {
                    for (cand in (sc.accepted + sc.measured).sortedBy { it.first }) {
                        val m = cand.metrics!!
                        println(
                            "$tag       km %.2f–%.2f, L %.0f m, rough %.2f, P95 %.1f %% (raw %.1f, clean %.2f), canyon %.1f, drop %.1f, %s"
                                .fmt(
                                    cand.startM / 1000,
                                    cand.endM / 1000,
                                    m.lengthM,
                                    m.roughnessM,
                                    m.slopeP95 * 100,
                                    m.rawSlopeP95 * 100,
                                    m.cleanFraction,
                                    m.canyonM,
                                    m.mntDropM,
                                    cand.rejection?.name ?: (if (cand in sc.fps) "DECK (FP)" else "DECK"),
                                ),
                        )
                    }
                }
            }
            val dIn = scored.sumOf { scores.getValue(it.id).dInSpan }
            val dAll = scored.sumOf { scores.getValue(it.id).dStations }
            println(
                "$tag $label totals (%d of %d in coverage): spans %d/%d, D stations in accepted spans %d / %d = %d %%, in deck masks %d, FP %d, G/O/L %d/%d/%d"
                    .fmt(
                        scored.size,
                        judged.size,
                        scored.sumOf { scores.getValue(it.id).dFound.size },
                        scored.sumOf { scores.getValue(it.id).dSpans.size },
                        dIn,
                        dAll,
                        if (dAll == 0) 0 else Math.round(100.0 * dIn / dAll),
                        scored.sumOf { scores.getValue(it.id).dInDeck },
                        scored.sumOf { scores.getValue(it.id).fps.size },
                        scored.sumOf { scores.getValue(it.id).out('G') },
                        scored.sumOf { scores.getValue(it.id).out('O') },
                        scored.sumOf { scores.getValue(it.id).out('L') },
                    ),
            )
        }
        // E3 (added after the E2 quinquies judgement; DEFAULT only): the deck profile of every
        // region, as deckProfiles prints it. No elevation truth here either.
        println()
        println("$tag E3 deck profiles at DEFAULT (no elevation truth: deck RMS is against the raw MNS median)")
        for (row in judged) {
            val c = controls.getValue(row.id)
            val p = profiles.getValue(row.id)
            val built = DeckProfileBuilder.build(p, DeckDetector.detect(p, DeckDetectorOptions.DEFAULT), DeckDetectorOptions.DEFAULT)
            println("$tag ═══ ${row.id}: %d region(s), %d dropped".fmt(built.replacements.size, built.dropped.size))
            for (rep in built.replacements) printRegion(tag, row.id, p, rep, c.kind, 'D', null, DeckDetectorOptions.DEFAULT)
        }
        println()
        println(
            "$tag tiles: %d from disk, %d fetched (%.1f MB) in this test".fmt(tileHits.get(), tileMisses.get(), bytesFetched.get() / 1e6),
        )
    }

    private companion object {
        const val STEP_M = 5.0
        const val MATCH_MAX_M = 30.0
        const val STEEP_GRADE = 0.15

        /** E3: regions the deck step replaces on the four routes (Bellevue's two parts joined). */
        const val EXPECTED_DECK_REGIONS = 18

        /** E3: bridges of structures.csv that a replaced stretch touches. */
        const val EXPECTED_REPLACED_BRIDGES = 18

        /**
         * E3: Validation targets of the spec that the bench misses, reported in the task doc, never
         * tuned: four Nantes decks whose worst point is the last `B` station, on the ramp down to
         * the end pin; the Rotonde, which the detector does not accept; and test3's D+, which the
         * MNT's own station-to-station noise inflates off the bridge (E1, *Limites*).
         */
        val TARGETS_MISSED: Set<String> =
            setOf(
                "test2 Pont de Pirmil",
                "test2 Pont Aristide Briand",
                "test2 way 1287936957",
                "test2 way 410671587",
                "test2 Pont de la Rotonde",
                "test3 D+ dem",
            )

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

        /** Station kinds of the controls, in `index.csv` column order. */
        const val CONTROL_KINDS = "GOLXxD"

        /** `structure` values of a `D`/`X` span in `spans.csv`. */
        val STRUCTURES = setOf("bridge", "tunnel", "layer", "underpass")

        /** Route beyond every counted held-out `D` span, both sides (`controls.py` `END_MARGIN_M`). */
        const val END_MARGIN_M = 150.0

        /**
         * The negatives' own bridges, `"id name"`: excluded from the FP count, scored for recall so
         * that a threshold which loses them shows it. Pont de la Rotonde is also a Strava bridge
         * (`test2`), lost by variant (a) in E2.
         */
        val NEGATIVE_OWN_BRIDGES =
            listOf(
                "n04-nantes-periph-nord Périphérique Extérieur - Pont de la Beaujoire",
                "n08-nantes-centre Pont de la Rotonde",
                "n14-strasbourg-under-a35 Pont de la Porte de Saverne",
            )

        /**
         * E2 quater: category of the wooded-ravine negatives (`n13`): a route through a wooded ravine
         * where DEFAULT merges two kept tall runs across an MNT canyon, off any OSM bridge, so the
         * canyon test passes and the clean-deck slope decides. Their count is the size self-check of the
         * E2 quater block, and the block asserts that DEFAULT still measures such a candidate on each.
         */
        const val RAVINE_CATEGORY = "ravine-merged-tall-runs"
        const val RAVINE_NEGATIVES = 1

        /**
         * Ravine negatives on which DEFAULT no longer measures any candidate past the canyon test since
         * the bilinear read moved to pixel centres (elevation-half-pixel H3): still negative, but no
         * longer a witness of anything. A new ravine witness is the way to restore the guard.
         */
        val RAVINES_WITHOUT_WITNESS = setOf("n13-morlaix-pennele")

        /** `index.csv` rows per role — the size self-check of its reader. */
        val CONTROL_ROLES = mapOf("negative" to 16, "held-out-positive" to 8, HOLDOUT2 to 9)

        /** Role of the second held-out series in `index.csv` (E2 quinquies). */
        const val HOLDOUT2 = "holdout2"

        /** The env switch of [deckHoldoutJudge], on top of `INTEGRATION=1`. */
        const val JUDGE_ENV = "DECK_JUDGE"

        /**
         * Per control, from the second `controls.py` generation (underpasses under the route
         * excluded, `L` = bridges only, no U-turn, 150 m of route beyond every counted `D` span):
         * stations, O, L, X + x stations, and D spans. A regenerated truth that drifts (other OSM
         * data, another route) fails here loudly.
         */
        val CONTROL_GUARDS: Map<String, IntArray> =
            mapOf(
                "n01-paris-metro6" to intArrayOf(396, 7, 377, 0, 0),
                "n02-nantes-chevire-under" to intArrayOf(309, 11, 0, 0, 0),
                "n03-millau-d992" to intArrayOf(482, 12, 0, 16, 0),
                "n04-nantes-periph-nord" to intArrayOf(801, 73, 6, 88, 0),
                "n05-canal-midi-platanes" to intArrayOf(735, 7, 0, 0, 0),
                "n06-fontainebleau-route-ronde" to intArrayOf(652, 0, 0, 0, 0),
                "n07-landes-velodyssee" to intArrayOf(340, 0, 0, 0, 0),
                "n08-nantes-centre" to intArrayOf(581, 16, 0, 65, 0),
                "n09-peage-lancon" to intArrayOf(518, 16, 0, 0, 0),
                "n10-loire-levee" to intArrayOf(670, 0, 0, 0, 0),
                "n11-morlaix-viaduc" to intArrayOf(307, 7, 0, 49, 0),
                "n12-toulouse-brienne" to intArrayOf(287, 0, 0, 22, 0),
                "n13-morlaix-pennele" to intArrayOf(187, 0, 0, 0, 0),
                // E2 quinquies negatives (2026-10-13).
                "n14-strasbourg-under-a35" to intArrayOf(199, 33, 0, 18, 0),
                "n15-lyon-berges-rhone" to intArrayOf(302, 30, 0, 0, 0),
                "n16-cousin-d427" to intArrayOf(333, 0, 0, 0, 0),
                "p01-chevire" to intArrayOf(602, 11, 0, 28, 1),
                "p02-thouare" to intArrayOf(368, 0, 0, 42, 3),
                "p03-ancenis" to intArrayOf(298, 0, 0, 14, 1),
                "p04-langeais" to intArrayOf(193, 0, 0, 14, 1),
                "p05-saumur" to intArrayOf(291, 0, 0, 41, 3),
                "p06-normandie" to intArrayOf(905, 10, 60, 42, 3),
                "p07-millau-a75" to intArrayOf(869, 0, 0, 44, 2),
                "p08-periph-overpass" to intArrayOf(119, 0, 0, 14, 1),
                // E2 quinquies, second held-out series (holdout2), frozen before any detector run.
                "p09-cubzac" to intArrayOf(374, 0, 0, 14, 1),
                "p10-pont-saint-esprit" to intArrayOf(353, 0, 0, 14, 1),
                "p11-sully" to intArrayOf(258, 0, 0, 14, 1),
                "p12-tonnay-charente" to intArrayOf(332, 0, 0, 38, 2),
                "p13-chalon-bourgogne" to intArrayOf(243, 0, 0, 46, 1),
                "p14-saint-cloud" to intArrayOf(252, 0, 0, 69, 1),
                "p15-blois" to intArrayOf(222, 0, 0, 14, 1),
                "p16-reims-a4" to intArrayOf(193, 0, 0, 14, 1),
                "p17-strasbourg-ill" to intArrayOf(187, 0, 0, 28, 2),
            )

        /** The five bridges variant (a) loses on DEFAULT (E2), by a substring of their structures.csv name. */
        val LOST_FIVE =
            linkedMapOf(
                "écluse" to "Pont écluse sud aval",
                "Rotonde" to "Pont de la Rotonde",
                "Bellevue" to "Pont de Bellevue",
                "Échevis" to "Echevis",
                "Haudaudine" to "Pont Haudaudine",
            )
    }
}

/** `String.format` in the root locale: the tables are pasted into docs, with decimal points. */
private fun String.fmt(vararg args: Any?): String = String.format(Locale.ROOT, this, *args)
