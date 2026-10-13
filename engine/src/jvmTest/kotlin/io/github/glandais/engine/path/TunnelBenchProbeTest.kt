package io.github.glandais.engine.path

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.ElevationSource
import io.github.glandais.elevation.IgnLidarHdLayer
import io.github.glandais.elevation.LatLon
import io.github.glandais.elevation.MathConstants
import io.github.glandais.elevation.RawTile
import io.github.glandais.elevation.TileEncoding
import io.github.glandais.elevation.decodeBil32Tile
import io.github.glandais.elevation.decodeTileBytes
import io.github.glandais.elevation.fetchTileBytes
import io.github.glandais.elevation.pacedTileBytesFetcher
import io.github.glandais.elevation.provider
import io.github.glandais.engine.ElevationCorrectionOptions
import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.firstTrackAsPath
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E4 of `docs/tasks/elevation-bridge-decks.md`: the **tunnel** controls, and [TunnelDetector] on them. `analysis/controls.py --tunnels` builds them from OSM only into `controls/tunnels/<id>.gpx`
 * and `analysis/truth/tunnels/` (`index.csv`, `spans.csv`, `<id>.csv`); the four Vercors tunnels of
 * `test4` stay in `truth/test4.csv` (kind `N`) and in [ElevationBenchProbeTest].
 *
 * Station kinds: `G` ground, `O` structure overhead, `L` bridge alongside, `X` other structure
 * (bridge, a negative's tunnel, avalanche gallery, underpass; excluded), `x` its 35 m margin
 * (excluded), `N` the site's own tunnel on a positive. Truth on `N`: the line between the MNT at the
 * station just outside the tunnel on each side (as `analyze.py` built `test4`'s).
 *
 * Four roles. `tunnel-tuning` and `tunnel-negative` are the calibration set. `tunnel-holdout` and
 * `tunnel-holdout-negative` were frozen by the rule written in the task doc (E4, *Témoins tunnels*)
 * before any OSM query for them; only [tunnelHoldoutTunnelJudge] samples them, and every other
 * sampling test drops them from the index before reading a tile.
 *
 * - [tunnel_controls_parse_and_hold_their_guards] runs on every `jvmTest` (no tile, so it touches the
 *   held-out rows safely): sizes, pinned counts, GPX against truth, the 150 m ground rule.
 * - [tunnelControls] (`INTEGRATION=1`) samples the calibration set on **both** sources a tunnel
 *   corrector would meet — IGN LiDAR HD MNT z15 and Mapterhorn z12 — and prints the baseline (the MNT
 *   above the portal line on `N`, the steep stations, > 15 % over 100 m, on `G`), then what
 *   [TunnelDetector] makes of each site: its spans, the `N` error before and after the portal line is
 *   laid, the `G` stations it moves. Which tuning tunnels it finds and which ground it moves are
 *   pinned ([TUNING_FOUND], [GROUND_MOVED]).
 * - [tunnelRides] (`INTEGRATION=1`): the demo's rides through the whole pipeline with step 3c off and
 *   on, both sources; a ride without a tunnel must come out bit for bit unchanged.
 * - [tunnelHoldoutTunnelJudge] (`INTEGRATION=1` **and** `TUNNEL_JUDGE=1`): the same reading, detector
 *   included, on the held-out series. Run it **once**, after the rule and the frozen list are
 *   committed on their own (rule 8) and the detector is frozen; it ran once on 2026-10-13, see
 *   *Jugement tunnels* in the task doc.
 *
 *     INTEGRATION=1 ./gradlew :engine:jvmTest --tests '*TunnelBenchProbeTest.tunnelControls' --rerun-tasks -i
 */
class TunnelBenchProbeTest {
    private val dataDir: File? =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "docs/deck-strava-data") }
            .firstOrNull { it.isDirectory }

    private val enabled: Boolean =
        System.getenv("INTEGRATION") == "1" || System.getProperty("integration") == "true"

    // ── Truth ───────────────────────────────────────────────────────────────────────────────────

    private class Row(
        val id: String,
        val role: String,
        val category: String,
        val n: Int,
        val counts: Map<Char, Int>,
    ) {
        val positive: Boolean get() = role == TUNING || role == HOLDOUT
        val holdout: Boolean get() = role == HOLDOUT || role == HOLDOUT_NEGATIVE
    }

    private class Site(
        val row: Row,
        val lat: DoubleArray,
        val lon: DoubleArray,
        val kind: CharArray,
    ) {
        val size: Int get() = kind.size
    }

    private class Span(
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

    private fun truthDir() = File(dataDir, "analysis/truth/tunnels")

    private fun readIndex(): List<Row> {
        val lines = File(truthDir(), "index.csv").readLines().filter { it.isNotBlank() }
        assertEquals("id,role,category,n,length_m,$KINDS_CSV,place", lines.first())
        val rows =
            lines.drop(1).map { line ->
                val c = line.split(',')
                assertEquals(12, c.size, "tunnels/index.csv: column count in '$line'")
                Row(c[0], c[1], c[2], c[3].toInt(), KINDS.withIndex().associate { (j, k) -> k to c[5 + j].toInt() })
            }
        assertEquals(ROLES, rows.groupingBy { it.role }.eachCount(), "tunnels/index.csv roles")
        assertEquals(rows.size, rows.map { it.id }.toSet().size, "tunnels/index.csv: duplicate id")
        return rows
    }

    private fun readSite(row: Row): Site {
        val lines = File(truthDir(), "${row.id}.csv").readLines()
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
        rows.forEachIndexed { k, r ->
            assertEquals(5, r.size, "${row.id}.csv: column count")
            assertEquals(k * STEP_M, r[0].toDouble(), 1e-9, "${row.id}.csv: s of station $k")
        }
        val site =
            Site(
                row,
                DoubleArray(rows.size) { rows[it][1].toDouble() },
                DoubleArray(rows.size) { rows[it][2].toDouble() },
                CharArray(rows.size) { rows[it][3].single() },
            )
        site.kind.forEach { assertTrue(it in KINDS, "${row.id}.csv: kind '$it'") }
        assertEquals(row.counts, KINDS.associateWith { k -> site.kind.count { it == k } }, "${row.id}.csv: kinds against index.csv")
        return site
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

    private fun readSpans(sites: Map<String, Site>): List<Span> {
        val lines = File(truthDir(), "spans.csv").readLines().filter { it.isNotBlank() }
        assertEquals("id,kind,i0,i1,s0,s1,way,structure,name", lines.first())
        val spans =
            lines.drop(1).map { line ->
                val c = line.split(',')
                assertEquals(9, c.size, "tunnels/spans.csv: column count in '$line'")
                Span(c[0], c[1].single(), c[2].toInt(), c[3].toInt(), c[6].toLong(), c[7], c[8])
            }
        for (sp in spans) {
            if (sp.kind == 'N' || sp.kind == 'X') {
                assertTrue(sp.structure in STRUCTURES, "tunnels/spans.csv: ${sp.id} ${sp.kind} ${sp.i0}: structure '${sp.structure}'")
                assertTrue(sp.way > 0, "tunnels/spans.csv: ${sp.id} ${sp.kind} ${sp.i0}: way")
            } else {
                assertEquals("", sp.structure, "tunnels/spans.csv: ${sp.id} ${sp.kind} ${sp.i0}: structure")
            }
            if (sp.kind == 'N') assertEquals("tunnel", sp.structure, "tunnels/spans.csv: ${sp.id} N ${sp.i0}")
        }
        for ((id, site) in sites) {
            for (k in "NXOL") {
                assertEquals(
                    runsOf(site.kind, k),
                    spans.filter { it.id == id && it.kind == k }.map { it.i0..it.i1 },
                    "tunnels/spans.csv: $id runs of $k",
                )
            }
        }
        assertEquals(sites.keys, spans.map { it.id }.toSet() + sites.keys, "tunnels/spans.csv: unknown id")
        return spans
    }

    private fun readAll(): Triple<List<Row>, Map<String, Site>, List<Span>> {
        val index = readIndex()
        val sites = index.associate { it.id to readSite(it) }
        return Triple(index, sites, readSpans(sites))
    }

    /** Counted `N` spans (≥ 20 m) merged when less than [GROUND_M] apart, as `controls.py`'s `n_groups`. */
    private fun groups(spans: List<Span>): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (sp in spans.filter { it.kind == 'N' && it.lengthM >= 20.0 - 1e-9 }.sortedBy { it.i0 }) {
            val last = out.lastOrNull()
            if (last != null && (sp.i0 - last.last - 1) * STEP_M < GROUND_M) {
                out[out.size - 1] = last.first..sp.i1
            } else {
                out += sp.i0..sp.i1
            }
        }
        return out
    }

    /** `controls.py`'s 5 m resampling of the GPX: station count, first point, point at [atM]. */
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
     * On every `jvmTest`: the tunnel controls parse, keep their pinned counts, their GPX is the same run
     * as their truth, every positive has a counted `N` group with [GROUND_M] of ground (no `N`/`X`)
     * beyond it on both sides, every negative has no `N`, and the tuning set still holds the four
     * Vercors tunnels of `test4`. Reads no tile.
     */
    @Test
    fun tunnel_controls_parse_and_hold_their_guards() {
        assertNotNull(dataDir, "docs/deck-strava-data not found")
        val (index, sites, spans) = readAll()
        assertEquals(GUARDS.keys, sites.keys, "GUARDS ids")
        for (row in index) {
            val site = sites.getValue(row.id)
            val own = spans.filter { it.id == row.id }
            if (row.positive) {
                val gs = groups(own)
                assertTrue(gs.isNotEmpty(), "${row.id}: a tunnel positive needs an N span of 20 m or more")
                if (row.id !in GROUND_WAIVED) {
                    val w = (GROUND_M / STEP_M).toInt()
                    for (g in gs) {
                        assertTrue(g.first >= w && site.size - 1 - g.last >= w, "${row.id}: N group $g within ${GROUND_M} m of a route end")
                        val beyond = (g.first - w until g.first) + (g.last + 1..g.last + w)
                        assertTrue(
                            beyond.none { site.kind[it] == 'N' || site.kind[it] == 'X' },
                            "${row.id}: N group $g without $GROUND_M m of ground",
                        )
                    }
                }
            } else {
                assertEquals(0, row.counts.getValue('N'), "${row.id}: a negative has no N station")
            }
            val actual =
                intArrayOf(
                    site.size,
                    row.counts.getValue('O'),
                    row.counts.getValue('L'),
                    row.counts.getValue('X') + row.counts.getValue('x'),
                    own.count { it.kind == 'N' },
                    row.counts.getValue('N'),
                )
            assertEquals(GUARDS.getValue(row.id).toList(), actual.toList(), "${row.id}: n, O, L, X+x, N spans, N stations against GUARDS")
            val gpx = File(dataDir, "controls/tunnels/${row.id}.gpx")
            assertTrue(gpx.isFile, "${gpx.path} missing")
            val (n, first, last) = resampleGpx(gpx, (site.size - 1) * STEP_M)
            assertTrue(abs(n - site.size) <= 1, "${row.id}: GPX resamples to $n stations, truth has ${site.size}")
            assertEquals(site.lat.first(), first.first, 1e-6, "${row.id}: first station latitude")
            assertEquals(site.lon.first(), first.second, 1e-6, "${row.id}: first station longitude")
            assertEquals(site.lat.last(), last.first, 1e-6, "${row.id}: last station latitude")
            assertEquals(site.lon.last(), last.second, 1e-6, "${row.id}: last station longitude")
        }
        // The rest of the tuning set: test4's tunnels, kind N in its truth (export_truth.py).
        val vercors =
            File(dataDir, "analysis/truth/structures.csv")
                .readLines()
                .map { it.split(',') }
                .filter { it.size > 13 && it[0] == "test4" && it[1] == "tunnel" }
                .map { it[13] }
        assertEquals(VERCORS_TUNNELS, vercors, "test4 tunnels in structures.csv")
        val test4N = File(dataDir, "analysis/truth/test4.csv").readLines().count { it.endsWith(",N") }
        assertTrue(test4N > 0, "test4.csv: no N station")
    }

    // ── Live sampling (both sources) ────────────────────────────────────────────────────────────

    private val cacheDir: File by lazy {
        System.getenv("VCYCLIST_DEM_CACHE")?.let(::File) ?: File(dataDir!!.parentFile.parentFile, "build/dem-cache")
    }
    private val tileHits = AtomicInteger()
    private val tileMisses = AtomicInteger()

    /** [ElevationBenchProbeTest]'s disk layout (the CLI's), so both tests share one cache. */
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
                val tile = decode(bytes, url)
                file.parentFile.mkdirs()
                val tmp = Files.createTempFile(file.parentFile.toPath(), file.name, ".tmp")
                Files.write(tmp, bytes)
                Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                tile
            }
        }
    }

    /** The two readings a tunnel corrector meets: IGN LiDAR HD MNT z15 (no-data kept) and Mapterhorn z12. */
    private fun sources(): Map<String, () -> ElevationProvider> =
        linkedMapOf(
            "IGN MNT z15" to { IgnLidarHdLayer.MNT.provider(15) { cachedFetcher(it) } },
            "mapterhorn z12" to { ElevationSource.MAPTERHORN.provider(null) { cachedFetcher(it) } },
        )

    private fun sample(
        site: Site,
        provider: ElevationProvider,
    ): DoubleArray {
        val stations = site.lat.indices.map { LatLon(site.lat[it], site.lon[it]) }
        return runBlocking { provider.setElevations(stations) }.map { it.elevation }.toDoubleArray()
    }

    /** Grade over 100 m centred on each station (`NaN` within 50 m of an end). */
    private fun grade100(z: DoubleArray): DoubleArray {
        val h = (50.0 / STEP_M).toInt()
        return DoubleArray(z.size) { i -> if (i < h || i + h >= z.size) Double.NaN else (z[i + h] - z[i - h]) / (2 * h * STEP_M) }
    }

    /**
     * Baseline of one site on one source, no detector: on each `N` group, the MNT's largest rise above
     * the portal line (MNT at the stations just outside the group); on `G` stations, how many are
     * steeper than 15 % over 100 m, the spec's tunnel signature, and the steepest.
     */
    private fun baseline(
        tag: String,
        site: Site,
        spans: List<Span>,
        source: String,
        z: DoubleArray,
    ) {
        val nan = z.count { it.isNaN() }
        val g = grade100(z)
        val ground = site.kind.indices.filter { site.kind[it] == 'G' && !g[it].isNaN() }
        val steep = ground.count { abs(g[it]) > STEEP_GRADE }
        val maxG = ground.maxOfOrNull { abs(g[it]) } ?: Double.NaN
        val bumps =
            groups(spans).joinToString("; ") { gr ->
                val a = gr.first - 1
                val b = gr.last + 1
                val line = { k: Int -> z[a] + (z[b] - z[a]) * (k - a) / (b - a) }
                val above = gr.maxOf { z[it] - line(it) }
                val portal = 100.0 * (z[b] - z[a]) / ((b - a) * STEP_M)
                "%.0f m: MNT above line max %+.1f m, portal grade %+.1f %%".fmt((gr.last - gr.first + 1) * STEP_M, above, portal)
            }
        println(
            "$tag   %-26s %-15s NaN %3d | G steep>15%% %4d / %4d, max %5.1f %% | %s".fmt(
                site.row.id,
                source,
                nan,
                steep,
                ground.size,
                100 * maxG,
                bumps.ifEmpty { "—" },
            ),
        )
    }

    /**
     * The calibration set (`tunnel-tuning`, `tunnel-negative`) on both sources, baseline only. The
     * held-out rows are dropped from the index **before** any tile is read.
     */
    @Test
    fun tunnelControls() {
        if (!enabled || dataDir == null) {
            println("[tunnels] set INTEGRATION=1 to sample the tunnel controls on IGN LiDAR HD and Mapterhorn")
            return
        }
        val tag = "[tunnels]"
        val (allIndex, sites, spans) = readAll()
        val index = allIndex.filter { !it.holdout }
        assertEquals(ROLES.getValue(HOLDOUT) + ROLES.getValue(HOLDOUT_NEGATIVE), allIndex.size - index.size, "held-out rows dropped")
        println("$tag tile cache: ${cacheDir.absolutePath}")
        val verdicts = measure(tag, index, sites, spans)
        // E4, pinned (the task doc's *Mesures (E4)*): which tuning tunnels the detector finds, and the
        // only ground any negative loses.
        val found =
            verdicts
                .filter { v -> index.single { it.id == v.id }.role == TUNING && v.groups.any { (b, a, _) -> a < 0.25 * b } }
                .map { "${it.id} ${it.source}" }
                .toSet()
        assertEquals(TUNING_FOUND, found, "tuning tunnels brought within 25 % of their bump")
        val moved = verdicts.filter { it.groundMoved > 0 }.associate { "${it.id} ${it.source}" to it.groundMoved }
        assertEquals(GROUND_MOVED, moved, "ground stations moved by the tunnel correction")
    }

    /**
     * E4 on whole rides, `INTEGRATION=1`: the full pipeline with step 3c on and off, on IGN LiDAR HD
     * (decks included, as shipped) and Mapterhorn. `sample.gpx` is the project's reference Alpine ride;
     * the others are the demo's recordings (Pyrenees, Helsinki, Stelvio — Mapterhorn only outside
     * France). Every tunnel found is printed with its coordinates; a ride with none must come out bit
     * for bit as without the step.
     */
    @Test
    fun tunnelRides() {
        if (!enabled || dataDir == null) {
            println("[tunnel-rides] set INTEGRATION=1 to run the demo rides with and without the tunnel step")
            return
        }
        val tag = "[tunnel-rides]"
        val repo = dataDir.parentFile.parentFile
        val rides =
            listOf("sample", "strava", "movescount", "sports-tracker", "stelvio", "amazfit", "garmin")
                .map { it to File(repo, "demo/public/gpx/$it.gpx") }
        val france = setOf("sample", "strava", "movescount", "sports-tracker")
        val providers: Map<String, () -> ElevationProvider> =
            linkedMapOf(
                "ign-lidar-hd" to { ElevationSource.IGN_LIDAR_HD.provider(null) { cachedFetcher(it) } },
                "mapterhorn" to { ElevationSource.MAPTERHORN.provider(null) { cachedFetcher(it) } },
            )
        var found = 0
        for ((name, file) in rides) {
            val input = GpxParser.parse(file.readText()).firstTrackAsPath()
            for ((source, make) in providers) {
                if (source == "ign-lidar-hd" && name !in france) continue
                val provider = make()
                val dense =
                    runBlocking {
                        var p = PointPerDistance.compute(input, minDistanceM = -1.0, maxDistanceM = Enhancer.PRE_FIX_SPACING_M)
                        p = ElevationStep.fixElevation(p, provider)
                        PointPerDistance.compute(p, minDistanceM = 1.0, maxDistanceM = 2.0)
                    }
                val step = runBlocking { TunnelStep.apply(dense, provider, refreshM = Enhancer.PRE_FIX_SPACING_M) }
                for (miss in NEAR_MISSES.filter { it.ride == name && it.source == source }) {
                    val stations = alongTrackStations(dense, STEP_M)
                    val mnt = runBlocking { provider.setElevations(stations).map { it.elevation }.toDoubleArray() }
                    val profile =
                        TunnelProfile(mnt, stations.map { it.latitude }.toDoubleArray(), stations.map { it.longitude }.toDoubleArray())

                    fun near(spans: List<TunnelSpan>) =
                        spans.any {
                            abs(it.startM - miss.startM) <= NEAR_MISS_TOLERANCE_M &&
                                abs(it.endM - miss.endM) <= NEAR_MISS_TOLERANCE_M
                        }
                    val relaxed = TunnelDetector.detect(profile, miss.relaxed)
                    println(
                        "$tag   near miss %s: %s".fmt(
                            miss.what,
                            relaxed.joinToString {
                                "%.0f–%.0f m h %.0f fall %.1f %%".fmt(
                                    it.startM,
                                    it.endM,
                                    it.heightM,
                                    100 * it.fallGrade,
                                )
                            },
                        ),
                    )
                    assertTrue(near(relaxed), "$name $source: the near miss ${miss.what} is no longer found with ${miss.relaxed}")
                    assertTrue(!near(TunnelDetector.detect(profile)), "$name $source: ${miss.what} is taken for a tunnel")
                    miss.guard?.let { guard ->
                        assertTrue(!near(TunnelDetector.detect(profile, guard)), "$name $source: ${miss.what} passes $guard")
                    }
                }
                val off =
                    runBlocking {
                        Enhancer.enhanceCourse(
                            Enhancer.getDefaultCourse(input),
                            EnhanceOptions.DEFAULT.copy(elevationCorrections = ElevationCorrectionOptions(tunnels = false)),
                            provider,
                        )
                    }
                val on =
                    runBlocking {
                        Enhancer.enhanceCourse(
                            Enhancer.getDefaultCourse(input),
                            EnhanceOptions.DEFAULT.copy(elevationCorrections = ElevationCorrectionOptions(tunnels = true)),
                            provider,
                        )
                    }
                println(
                    "$tag %-15s %-12s %6.1f km | %s, %d tunnel(s) | reported D+ %.1f -> %.1f m | duration %.0f -> %.0f s".fmt(
                        name,
                        source,
                        input.totalDistance / 1000,
                        step.status,
                        step.spans.size,
                        off.reportedElevationGain,
                        on.reportedElevationGain,
                        off.durationMs / 1000,
                        on.durationMs / 1000,
                    ),
                )
                for (t in step.spans) {
                    val i = (0 until dense.size).minBy { abs(dense.distance(it) - t.startM) }
                    println(
                        "$tag   km %.2f–%.2f h %.0f m c %+.1f %% (%.5f, %.5f)".fmt(
                            t.startM / 1000,
                            t.endM / 1000,
                            t.heightM,
                            100 * t.chordGrade,
                            dense.latitudeDeg(i),
                            dense.longitudeDeg(i),
                        ),
                    )
                }
                found += step.spans.size
                if (step.spans.isEmpty()) {
                    assertEquals(off.size, on.size, "$name $source: sizes")
                    for (k in 0 until on.size) {
                        assertEquals(off.elevation(k).toRawBits(), on.elevation(k).toRawBits(), "$name $source: point $k")
                        assertEquals(off.time(k).toRawBits(), on.time(k).toRawBits(), "$name $source: time $k")
                    }
                }
            }
        }
        assertEquals(RIDE_TUNNELS, found, "tunnels found on the demo rides")
    }

    /**
     * The judge of the held-out tunnel series (rule 7 of E4, *Témoins tunnels*). Gated on
     * `INTEGRATION=1` **and** `TUNNEL_JUDGE=1`. Printed, asserted only for data integrity; a failure
     * here is reported in the task doc, never tuned. It prints the baseline and the frozen
     * [TunnelDetector]'s verdict on every held-out site — **running it spends the series**.
     *
     * It has **no pass/fail threshold**: its assertions check the data (row counts), so a green run
     * says the series was read intact, nothing about the detector; the verdict is a reading of what it
     * printed. The series was spent on 2026-10-13 against the detector of `tunnel-judge.sha256`; the
     * face criterion and the no-data guard came after it, so a run now judges nothing. The next
     * held-out series writes its acceptance criteria into its rule before it is built.
     *
     *     INTEGRATION=1 TUNNEL_JUDGE=1 ./gradlew :engine:jvmTest --tests '*TunnelJudge*' --rerun-tasks -i
     */
    @Test
    fun tunnelHoldoutTunnelJudge() {
        if (!enabled || dataDir == null || System.getenv(JUDGE_ENV) != "1") {
            println("[tunnel-judge] set INTEGRATION=1 and $JUDGE_ENV=1 to judge the held-out tunnel series (once)")
            return
        }
        val tag = "[tunnel-judge]"
        val (allIndex, sites, spans) = readAll()
        val judged = allIndex.filter { it.holdout }
        assertEquals(ROLES.getValue(HOLDOUT) + ROLES.getValue(HOLDOUT_NEGATIVE), judged.size, "held-out rows")
        println("$tag tile cache: ${cacheDir.absolutePath}")
        measure(tag, judged, sites, spans)
    }

    /** What [TunnelDetector] did on one site and one source. */
    private class Verdict(
        val id: String,
        val source: String,
        val tunnels: List<TunnelSpan>,
        /** Per counted `N` group: max |terrain − truth line| before and after, and the after RMS. */
        val groups: List<Triple<Double, Double, Double>>,
        /** `G` stations the correction moves by more than 0.5 m, and the largest move. */
        val groundMoved: Int,
        val groundMaxMoveM: Double,
    )

    /**
     * The detector (E4) on one site: [TunnelDetector] on the terrain at the truth's stations, each
     * span replaced by the line between its portals (what [TunnelStep] writes, on the 5 m grid).
     * Truth on an `N` group: the line between the terrain just outside it, as [baseline] reads it.
     */
    private fun judge(
        tag: String,
        site: Site,
        spans: List<Span>,
        source: String,
        z: DoubleArray,
    ): Verdict {
        val found = TunnelDetector.detect(TunnelProfile(z, site.lat, site.lon))
        val y = z.copyOf()
        for (t in found) for (k in t.first..t.last) y[k] = z[t.first] + (z[t.last] - z[t.first]) * (k - t.first) / (t.last - t.first)
        val gs =
            groups(spans).map { gr ->
                val a = gr.first - 1
                val b = gr.last + 1
                val line = { k: Int -> z[a] + (z[b] - z[a]) * (k - a) / (b - a) }
                val before = gr.maxOf { abs(z[it] - line(it)) }
                val after = gr.maxOf { abs(y[it] - line(it)) }
                val rms = sqrt(gr.sumOf { (y[it] - line(it)).let { e -> e * e } } / (gr.last - gr.first + 1))
                Triple(before, after, rms)
            }
        val ground = site.kind.indices.filter { site.kind[it] == 'G' }
        val moved = ground.count { abs(y[it] - z[it]) > 0.5 }
        val maxMove = ground.maxOfOrNull { abs(y[it] - z[it]) } ?: 0.0
        val where =
            found.joinToString("; ") { t ->
                val kinds =
                    (t.first..t.last).groupingBy { site.kind[it] }.eachCount().toSortedMap().entries.joinToString(
                        "",
                    ) { "${it.key}${it.value}" }
                "%.0f–%.0f m h %.0f c %+.1f %% sides %+.0f/%+.0f %% str %.2f [%s]".fmt(
                    t.startM,
                    t.endM,
                    t.heightM,
                    100 * t.chordGrade,
                    100 * t.riseGrade,
                    100 * t.fallGrade,
                    t.straightness,
                    kinds,
                )
            }
        println(
            "$tag   E4 %-26s %-15s %d tunnel(s) | N %s | G moved %d (max %.1f m) | %s".fmt(
                site.row.id,
                source,
                found.size,
                gs.joinToString(", ") { (b, a, r) -> "%.1f -> %.1f (rms %.1f)".fmt(b, a, r) }.ifEmpty { "—" },
                moved,
                maxMove,
                where.ifEmpty { "—" },
            ),
        )
        return Verdict(site.row.id, source, found, gs, moved, maxMove)
    }

    private fun measure(
        tag: String,
        rows: List<Row>,
        sites: Map<String, Site>,
        spans: List<Span>,
    ): List<Verdict> {
        val verdicts = ArrayList<Verdict>()
        for ((source, make) in sources()) {
            println()
            println("$tag $source")
            for (row in rows) {
                val site = sites.getValue(row.id)
                val z = sample(site, make())
                assertEquals(site.size, z.size, "${row.id}: samples")
                val own = spans.filter { it.id == row.id }
                baseline(tag, site, own, source, z)
                verdicts += judge(tag, site, own, source, z)
            }
        }
        println("$tag tiles: %d from disk, %d fetched".fmt(tileHits.get(), tileMisses.get()))
        return verdicts
    }

    companion object {
        const val STEP_M = 5.0
        const val GROUND_M = 150.0
        const val STEEP_GRADE = 0.15

        /** Station kinds, in `index.csv` column order. */
        const val KINDS = "GOLXxN"
        val KINDS_CSV = KINDS.toList().joinToString(",")

        /** `structure` values of an `N`/`X` span. */
        val STRUCTURES = setOf("bridge", "tunnel", "gallery", "layer", "underpass")

        const val TUNING = "tunnel-tuning"
        const val NEGATIVE = "tunnel-negative"
        const val HOLDOUT = "tunnel-holdout"
        const val HOLDOUT_NEGATIVE = "tunnel-holdout-negative"

        /** The env switch of [tunnelHoldoutTunnelJudge], on top of `INTEGRATION=1`. */
        const val JUDGE_ENV = "TUNNEL_JUDGE"

        /**
         * E4: the tuning tunnels whose largest error above the portal line the detector brings under a
         * quarter of the bump on at least one of its `N` groups, per source. Not found: Tuileries
         * (cut-and-cover, 3.7–5.6 m of bump), Cliets on Mapterhorn, Nesque on Mapterhorn (its second
         * tunnel 29.8 → 9.8 m only); Nesque's first tunnel on neither (its 45 m OSM span sits in a
         * 95 m rock cut the detector takes whole, against a truth line drawn on the cut).
         */
        val TUNING_FOUND: Set<String> =
            setOf(
                "tt01-galibier IGN MNT z15",
                "tt01-galibier mapterhorn z12",
                "tt02-croix-rousse IGN MNT z15",
                "tt02-croix-rousse mapterhorn z12",
                "tt03-lioran IGN MNT z15",
                "tt03-lioran mapterhorn z12",
                "tt05-rousset IGN MNT z15",
                "tt05-rousset mapterhorn z12",
                "tt06-nesque IGN MNT z15",
                "tt07-petit-frou IGN MNT z15",
                "tt07-petit-frou mapterhorn z12",
                "tt08-arly-cliets IGN MNT z15",
            )

        /** E4: tunnels [tunnelRides] finds on the demo rides (none of them goes through one). */
        const val RIDE_TUNNELS = 0

        /**
         * A real bump on a demo ride that one threshold alone keeps from being flattened: [relaxed]
         * (the defaults with that threshold loosened) must find it, the defaults must not, and
         * [guard] (when set: the threshold loosened *with* the review's face criterion on) must not
         * either. A threshold change that tips one over fails here before it ships.
         */
        class NearMiss(
            val ride: String,
            val source: String,
            val startM: Double,
            val endM: Double,
            val what: String,
            val relaxed: TunnelDetectorOptions,
            val guard: TunnelDetectorOptions? = null,
        )

        const val NEAR_MISS_TOLERANCE_M = 15.0

        /** Measured with the Python port of the detector in the E4 review; see *Mesures (E4)*. */
        val NEAR_MISSES: List<NearMiss> =
            listOf(
                // A 25 m knoll on an Ariège trail: −26.5 % on its exit, 3.5 points from −30 %; its faces
                // stand 25 / 22 m within 100 m, so the face criterion does not guard it.
                NearMiss(
                    "sports-tracker",
                    "mapterhorn",
                    2_190.0,
                    2_430.0,
                    "trail knoll km 2.19",
                    TunnelDetectorOptions(minSideGrade = 0.26),
                ),
                // D 109 at Héry-sur-Ugine: a 72 m real hill, a 14 m bridged gully at its foot. Before the
                // face criterion only its exit fall (−15 %) refused it; its faces stand 11 m within 100 m.
                NearMiss(
                    "sample",
                    "mapterhorn",
                    18_820.0,
                    20_735.0,
                    "D 109 hill km 18.82",
                    TunnelDetectorOptions(minSideGrade = 0.14, faceReachM = 3_000.0),
                    guard = TunnelDetectorOptions(minSideGrade = 0.14),
                ),
                // A 23 m bump fails only its rise (19 %) without the face criterion; its entry face is 5 m.
                NearMiss(
                    "strava",
                    "mapterhorn",
                    4_925.0,
                    5_945.0,
                    "bump km 4.93",
                    TunnelDetectorOptions(minSideGrade = 0.18, faceReachM = 3_000.0),
                    guard = TunnelDetectorOptions(minSideGrade = 0.18),
                ),
            )

        /** E4: ground (`G`) stations the correction moves by more than 0.5 m, per site and source. */
        val GROUND_MOVED: Map<String, Int> = mapOf("tn08-bourne mapterhorn z12" to 13)

        /** `index.csv` rows per role — the size self-check of its reader. */
        val ROLES: Map<String, Int> = mapOf(TUNING to 8, NEGATIVE to 13, HOLDOUT to 7, HOLDOUT_NEGATIVE to 4)

        /** The four tunnels of `test4` (`structures.csv`), the rest of the tuning set. */
        val VERCORS_TUNNELS = listOf("Route des Petits Goulets", "Tunnel des Grands Goulets", "Tunnel des Moines", "Cirque de Combe Laval")

        /** Tuning sites whose ground margin may hold an `X` (a tunnel among avalanche galleries). */
        val GROUND_WAIVED = setOf("tt08-arly-cliets")

        /** Per site: stations, O, L, X + x, N spans, N stations. A regenerated truth that drifts fails here. */
        val GUARDS: Map<String, IntArray> =
            mapOf(
                "tt01-galibier" to intArrayOf(219, 0, 0, 14, 1, 73),
                "tt02-croix-rousse" to intArrayOf(517, 0, 0, 14, 1, 354),
                "tt03-lioran" to intArrayOf(455, 0, 5, 36, 1, 300),
                "tt04-tuileries" to intArrayOf(342, 12, 0, 24, 1, 171),
                "tt05-rousset" to intArrayOf(309, 0, 0, 14, 1, 140),
                "tt06-nesque" to intArrayOf(209, 0, 0, 28, 2, 31),
                "tt07-petit-frou" to intArrayOf(216, 0, 0, 14, 1, 45),
                "tt08-arly-cliets" to intArrayOf(242, 0, 0, 69, 1, 48),
                "tn01-alpe-huez" to intArrayOf(595, 0, 0, 0, 0, 0),
                "tn02-montvernier" to intArrayOf(397, 0, 0, 17, 0, 0),
                "tn03-galibier-col" to intArrayOf(239, 0, 0, 0, 0, 0),
                "tn04-ventoux" to intArrayOf(596, 0, 0, 0, 0, 0),
                "tn05-montmartre" to intArrayOf(145, 0, 0, 0, 0, 0),
                "tn06-lyon-boucle" to intArrayOf(99, 6, 6, 0, 0, 0),
                "tn07-lyon-st-sebastien" to intArrayOf(316, 0, 7, 44, 0, 0),
                "tn08-bourne" to intArrayOf(598, 0, 0, 116, 0, 0),
                "tn09-verdon-cretes" to intArrayOf(357, 0, 0, 15, 0, 0),
                "tn10-roselend" to intArrayOf(279, 0, 0, 136, 0, 0),
                "tn11-serre-poncon" to intArrayOf(148, 0, 0, 11, 0, 0),
                "tn12-arly-galleries" to intArrayOf(278, 0, 0, 61, 0, 0),
                "tn13-cutting-isere" to intArrayOf(240, 0, 0, 0, 0, 0),
                "th01-cians" to intArrayOf(256, 0, 0, 14, 1, 88),
                "th02-pierre-lys" to intArrayOf(181, 0, 0, 14, 1, 19),
                "th03-tunnel-09" to intArrayOf(200, 0, 0, 14, 1, 43),
                "th08-voie-verte-24" to intArrayOf(300, 0, 0, 14, 1, 94),
                "hn01-aubisque" to intArrayOf(475, 0, 0, 36, 0, 0),
                "hn02-aveyron" to intArrayOf(477, 0, 0, 16, 0, 0),
                "hn03-boulogne" to intArrayOf(139, 0, 0, 33, 0, 0),
                "hn06-bavella" to intArrayOf(596, 0, 0, 15, 0, 0),
                "th09-tunnel-07" to intArrayOf(276, 0, 0, 36, 1, 74),
                "th09-tunnel-2b" to intArrayOf(274, 0, 0, 14, 1, 76),
                "th09-tunnel-81" to intArrayOf(357, 0, 0, 14, 1, 188),
            )
    }
}

private fun String.fmt(vararg args: Any?): String = String.format(Locale.ROOT, this, *args)
