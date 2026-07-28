package io.github.glandais.map

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.util.concurrent.ThreadLocalRandom
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Renders one or more [Path]s over a downloaded tile background and writes a PNG.
 *
 * Port of gpx2web's `TileMapProducer`. Framing is delegated to [MapImage] (task g13); this class
 * adds tile fetching, the disk cache, and drawing.
 *
 * ## The URL pattern is mandatory
 *
 * There is deliberately **no default tile source**. Shipping one is what leads applications to
 * hammer OpenStreetMap's public servers without their authors realising it. Passing a URL is an
 * explicit act, and it carries the obligation to honour that source's usage policy — see
 * [HttpTileFetcher] and `map/README.md`.
 *
 * The pattern accepts `{z}`, `{x}`, `{y}` and, optionally, `{s}` for a subdomain, which is
 * replaced with one of `a`, `b`, `c` at random, as the reference does.
 *
 * ## Cache
 *
 * Tiles land in `{cacheFolder}/{host}/{z}/{x}/{y}.png` and **never expire**. Tiles are immutable
 * in practice, and a render that silently changes because the background was updated between two
 * runs makes regression testing impossible. Clearing the folder is the way to refresh.
 *
 * Note one deliberate difference from the reference: a *failed* fetch is not cached. gpx2web
 * touches a zero-byte file, which makes the failure permanent — a single network blip would
 * blank that tile forever. Here a failure just means no tile this time, and the next render
 * retries. Successful tiles are still cached indefinitely, so reproducibility is unaffected.
 *
 * @param cacheFolder root of the on-disk tile cache.
 * @param fetcher how tiles are retrieved. Injected so tests never touch the network.
 */
class TileMapProducer(
    private val cacheFolder: File,
    private val fetcher: TileFetcher = HttpTileFetcher(),
) {
    /**
     * Render [paths] over tiles from [urlPattern] and write a PNG to [file].
     *
     * Exactly one framing mode must be chosen, mirroring the reference's three overloads:
     *
     * - [maxSize] — deepest zoom at which neither dimension exceeds it;
     * - [width] and [height] — an image of exactly that size;
     * - [zoom] — an explicit zoom level, size follows from the track.
     *
     * @param colors one colour per path, cycled if there are more paths than colours.
     */
    @JvmOverloads
    fun createTileMap(
        file: File,
        paths: List<Path>,
        urlPattern: String,
        margin: Double = DEFAULT_MARGIN,
        maxSize: Int? = null,
        width: Int? = null,
        height: Int? = null,
        zoom: Int? = null,
        colors: List<Color> = listOf(DEFAULT_TRACK_COLOR),
    ): MapImage {
        require(urlPattern.isNotBlank()) { "A tile URL pattern is required — there is no default source" }
        require(colors.isNotEmpty()) { "At least one track colour is required" }

        val map = frame(paths, margin, maxSize, width, height, zoom)
        drawTiles(map, urlPattern)
        for ((i, path) in paths.withIndex()) {
            drawPath(map, path, colors[i % colors.size])
        }
        map.saveImage(file)
        return map
    }

    private fun frame(
        paths: List<Path>,
        margin: Double,
        maxSize: Int?,
        width: Int?,
        height: Int?,
        zoom: Int?,
    ): MapImage {
        val modes = listOfNotNull(maxSize?.let { "maxSize" }, width?.let { "width/height" }, zoom?.let { "zoom" })
        require(modes.size == 1) {
            "Choose exactly one framing mode (maxSize, width+height, or zoom); got ${modes.ifEmpty { listOf("none") }}"
        }
        return when {
            maxSize != null -> MapImage.ofMaxSize(paths, margin, maxSize)
            zoom != null -> MapImage.ofZoom(paths, margin, zoom)
            else -> {
                requireNotNull(width) { "width is required alongside height" }
                requireNotNull(height) { "height is required alongside width" }
                MapImage.ofSize(paths, margin, width, height)
            }
        }
    }

    /**
     * Draw every tile overlapping the frame. A tile that cannot be obtained is skipped, leaving
     * background — a partly-drawn map beats an exception.
     */
    private fun drawTiles(
        map: MapImage,
        urlPattern: String,
    ) {
        val graphics = map.createGraphics()
        try {
            val tileSize = map.mapSpace.tileSize
            val iMin = floor(map.getTileI(map.minLon)).toInt()
            val iMax = ceil(map.getTileI(map.maxLon)).toInt()
            val jMin = floor(map.getTileJ(map.maxLat)).toInt()
            val jMax = ceil(map.getTileJ(map.minLat)).toInt()

            for (i in iMin until iMax) {
                for (j in jMin until jMax) {
                    val tile = tile(urlPattern, map.zoom, i, j) ?: continue
                    val x = (i.toDouble() * tileSize - map.startX).toInt()
                    val y = (j.toDouble() * tileSize - map.startY).toInt()
                    graphics.drawImage(tile, x, y, null)
                }
            }
        } finally {
            graphics.dispose()
        }
    }

    /** Cached tile, fetching it if absent. `null` when it cannot be obtained or decoded. */
    private fun tile(
        urlPattern: String,
        zoom: Int,
        x: Int,
        y: Int,
    ): BufferedImage? {
        val cached = cacheFile(urlPattern, zoom, x, y)
        if (cached.isFile && cached.length() > 0) {
            return runCatching { ImageIO.read(cached) }.getOrNull()
        }

        val url = expand(urlPattern, zoom, x, y)
        val bytes = fetcher.fetch(url) ?: return null
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null

        // Only write once the bytes are known to decode, so the cache never holds an error page.
        runCatching {
            cached.parentFile?.mkdirs()
            cached.writeBytes(bytes)
        }
        return image
    }

    /**
     * `{cacheFolder}/{host}/{z}/{x}/{y}.png`. Keying on the host keeps two tile sources apart
     * while staying readable — gpx2web uses a hash of the URL pattern, which is opaque when you
     * are trying to work out why a render looks wrong.
     */
    private fun cacheFile(
        urlPattern: String,
        zoom: Int,
        x: Int,
        y: Int,
    ): File {
        val host =
            runCatching { URI.create(expand(urlPattern, zoom, x, y)).host }
                .getOrNull()
                ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                ?: "unknown"
        return File(cacheFolder, "$host/$zoom/$x/$y.png")
    }

    private fun expand(
        urlPattern: String,
        zoom: Int,
        x: Int,
        y: Int,
    ): String =
        urlPattern
            .replace("{z}", zoom.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
            // Spread requests over the source's subdomains, as the reference does. The cache is
            // keyed on z/x/y, so which subdomain served a tile does not affect cache hits.
            .replace("{s}", SUBDOMAINS[ThreadLocalRandom.current().nextInt(SUBDOMAINS.length)].toString())

    private fun drawPath(
        map: MapImage,
        path: Path,
        color: Color,
    ) {
        if (path.size == 0) return
        val graphics = map.createGraphics()
        try {
            graphics.stroke = BasicStroke(STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, TRACK_ALPHA)
            graphics.color = color

            val xs = IntArray(path.size)
            val ys = IntArray(path.size)
            for (i in 0 until path.size) {
                xs[i] = map.getX(path.longitude(i) * MathConstants.RAD_TO_DEG)
                ys[i] = map.getY(path.latitude(i) * MathConstants.RAD_TO_DEG)
            }
            graphics.drawPolyline(xs, ys, path.size)
        } finally {
            graphics.dispose()
        }
    }

    companion object {
        /** Subdomains substituted for `{s}`, as in the reference. */
        private const val SUBDOMAINS = "abc"

        private const val STROKE_WIDTH = 5.0f

        /** Semi-transparent so the map underneath stays legible. From the reference. */
        private const val TRACK_ALPHA = 0.6f

        /** Default padding around the track, as a ratio of its extent. */
        const val DEFAULT_MARGIN: Double = 0.1

        val DEFAULT_TRACK_COLOR: Color = Color.RED
    }
}
