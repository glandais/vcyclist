package io.github.glandais.parity

import io.github.glandais.elevation.Distance
import io.github.glandais.elevation.DouglasPeucker
import io.github.glandais.elevation.EcefConverter
import io.github.glandais.elevation.ElevationFunctions
import io.github.glandais.elevation.ElevationSmoother
import io.github.glandais.elevation.LatLon
import io.github.glandais.elevation.LatLonElevation
import io.github.glandais.elevation.Vector3D
import java.io.File

/**
 * Kotlin side of the unit-level parity sweep for the `:elevation` module.
 *
 * Reads `cases/units.json`, evaluates each sentinel input and writes a flat
 * `{"key": number}` map. The keys must match `ts/unitElevation.ts` exactly — that is the
 * whole contract; `compare-units.py` reports any key present on only one side.
 *
 * Deliberately targets inputs the end-to-end cascade cannot reach: poles, the
 * antimeridian, Web-Mercator limits, zero-length segments and colinear points.
 */
private class Json(
    text: String,
) {
    // Minimal JSON reader: the cases file is ours, fixed-shape and checked into the repo,
    // so a full parser (and a dependency for it) would be overkill here.
    private var pos = 0
    private val s = text

    fun parse(): Any? {
        skipWs()
        return value()
    }

    private fun skipWs() {
        while (pos < s.length && s[pos].isWhitespace()) pos++
    }

    private fun value(): Any? {
        skipWs()
        return when (s[pos]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> {
                pos += 4
                true
            }
            'f' -> {
                pos += 5
                false
            }
            'n' -> {
                pos += 4
                null
            }
            else -> num()
        }
    }

    private fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        pos++ // {
        skipWs()
        if (s[pos] == '}') {
            pos++
            return m
        }
        while (true) {
            skipWs()
            val k = str()
            skipWs()
            pos++ // :
            m[k] = value()
            skipWs()
            if (s[pos] == ',') {
                pos++
                continue
            }
            pos++ // }
            return m
        }
    }

    private fun arr(): List<Any?> {
        val l = ArrayList<Any?>()
        pos++ // [
        skipWs()
        if (s[pos] == ']') {
            pos++
            return l
        }
        while (true) {
            l.add(value())
            skipWs()
            if (s[pos] == ',') {
                pos++
                continue
            }
            pos++ // ]
            return l
        }
    }

    private fun str(): String {
        val sb = StringBuilder()
        pos++ // opening quote
        while (s[pos] != '"') {
            if (s[pos] == '\\') {
                pos++
                sb.append(
                    when (s[pos]) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        'u' -> {
                            val c = s.substring(pos + 1, pos + 5).toInt(16).toChar()
                            pos += 4
                            c
                        }
                        else -> s[pos]
                    },
                )
            } else {
                sb.append(s[pos])
            }
            pos++
        }
        pos++ // closing quote
        return sb.toString()
    }

    private fun num(): Double {
        val start = pos
        while (pos < s.length && (s[pos].isDigit() || s[pos] in "-+.eE")) pos++
        return s.substring(start, pos).toDouble()
    }
}

@Suppress("UNCHECKED_CAST")
private fun Any?.asList(): List<Any?> = this as List<Any?>

@Suppress("UNCHECKED_CAST")
private fun Any?.asMap(): Map<String, Any?> = this as Map<String, Any?>

private fun Any?.d(): Double = this as Double

private fun Map<String, Any?>.coord(): LatLonElevation = LatLonElevation(this["lat"].d(), this["lon"].d(), this["ele"].d())

/** Render a double the way `JSON.stringify` does, so the two files compare cleanly. */
private fun jsonNum(v: Double): String =
    when {
        !v.isFinite() -> "null"
        v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e15 -> v.toLong().toString()
        else -> v.toString()
    }

fun main(argv: Array<String>) {
    val args = argv.toList()

    fun arg(name: String): String {
        val i = args.indexOf("--$name")
        require(i >= 0 && i + 1 < args.size) { "missing --$name" }
        return args[i + 1]
    }

    val cases = Json(File(arg("cases")).readText()).parse().asMap()
    val out = LinkedHashMap<String, Double>()

    // --- Distance.haversine -------------------------------------------------
    val coords = cases["coordinates"].asList().map { it.asMap() }
    val byId = coords.associateBy { it["id"] as String }
    for (pair in cases["coordinatePairs"].asList()) {
        val (a, b) = pair.asList().map { it as String }
        out["haversine.$a|$b"] = Distance.haversine(byId[a]!!.coord(), byId[b]!!.coord())
    }

    // --- EcefConverter + Vector3D -------------------------------------------
    for (c in coords) {
        val id = c["id"] as String
        for (z in listOf(1.0, 3.0)) {
            val v = EcefConverter.toEcef(c.coord(), z)
            val zk = "z${z.toInt()}"
            out["ecef.$id.$zk.x"] = v.x
            out["ecef.$id.$zk.y"] = v.y
            out["ecef.$id.$zk.z"] = v.z
            out["ecef.$id.$zk.magnitude"] = v.magnitude()
        }
    }
    run {
        val pts = coords.map { EcefConverter.toEcef(it.coord(), 3.0) }
        for (i in 0 until pts.size - 2) {
            out["vec.distanceTo.$i"] = pts[i].distanceTo(pts[i + 1])
            out["vec.dot.$i"] = pts[i].dot(pts[i + 1])
            out["vec.cross.$i.x"] = pts[i].cross(pts[i + 1]).x
            out["vec.distanceToSegment.$i"] = pts[i + 1].distanceToSegment(pts[i], pts[i + 2])
            out["dist.pointToSegment3D.$i"] =
                Distance.pointToSegment3D(pts[i + 1], pts[i], pts[i + 2])
        }
        val zero = Vector3D.ZERO
        out["vec.degenerateSegment"] = zero.distanceToSegment(zero, zero)
        out["vec.zeroMagnitude"] = zero.magnitude()
    }

    // --- cumulativeDistances -------------------------------------------------
    Distance.cumulativeDistances(coords.map { it.coord() }).forEachIndexed { i, d ->
        out["cumulative.$i"] = d
    }

    // --- ElevationSmoother ---------------------------------------------------
    for (sc in cases["smootherCases"].asList().map { it.asMap() }) {
        val id = sc["id"] as String
        val pts = sc["points"].asList().map { it.asMap().coord() }
        ElevationSmoother.smooth(pts, sc["windowSize"].d()).forEachIndexed { i, p ->
            out["smooth.$id.$i"] = p.elevation
        }
    }

    // --- DouglasPeucker (3D) -------------------------------------------------
    for (dc in cases["douglasPeuckerCases"].asList().map { it.asMap() }) {
        val id = dc["id"] as String
        val pts = dc["points"].asList().map { it.asMap().coord() }
        val res = DouglasPeucker.simplify(pts, dc["tolerance"].d(), dc["zExaggeration"].d())
        out["dp.$id.count"] = res.size.toDouble()
        res.forEachIndexed { i, p ->
            out["dp.$id.$i.lat"] = p.latitude
            out["dp.$id.$i.lon"] = p.longitude
            out["dp.$id.$i.ele"] = p.elevation ?: 0.0
        }
    }

    // --- ElevationFunctions: lat/lon -> tile / pixel --------------------------
    // Keyed by case index, not by the coordinate values : JS and Kotlin disagree on the
    // decimal rendering of a double near 1e-6 ("-0.000001" vs "-1.0E-6"), which would show
    // up as a spurious key mismatch rather than the numeric comparison we actually want.
    cases["tileCases"].asList().map { it.asMap() }.forEachIndexed { ti, tc ->
        val lat = tc["lat"].d()
        val lon = tc["lon"].d()
        val zoom = tc["zoom"].d().toInt()
        val tileSize = tc["tileSize"].d().toInt()
        val key = "tile.$ti.z$zoom"
        val c = LatLon(lat, lon)
        val f = ElevationFunctions.toTileCoordinatesFloat(c, zoom)
        out["$key.xFloat"] = f.xFloat
        out["$key.yFloat"] = f.yFloat
        val t = ElevationFunctions.toTileCoordinates(c, zoom)
        out["$key.x"] = t.x.toDouble()
        out["$key.y"] = t.y.toDouble()
        val p = ElevationFunctions.toPixel(c, zoom, tileSize)
        out["$key.px"] = p.x.toDouble()
        out["$key.py"] = p.y.toDouble()
    }

    // --- Terrarium decode -----------------------------------------------------
    for (t in cases["terrariumCases"].asList()) {
        val (r, g, b) = t.asList().map { it.d() }
        out["terrarium.${r.toInt()}_${g.toInt()}_${b.toInt()}"] = r * 256 + g + b / 256 - 32768
    }

    File(arg("out")).writeText(
        out.entries.joinToString(
            separator = ",\n ",
            prefix = "{\n ",
            postfix = "\n}\n",
        ) { "\"${it.key}\": ${jsonNum(it.value)}" },
    )
    System.err.println("[kt/elevation] ${out.size} values")
}
