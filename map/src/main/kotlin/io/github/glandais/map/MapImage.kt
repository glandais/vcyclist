package io.github.glandais.map

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Framing for a raster map covering one or more [Path]s: picks a zoom level, computes the pixel
 * size and the geographic bounds, and converts between coordinates and pixels.
 *
 * It draws no tiles — that is task g14, and the elevation profile is g15. What it owns is the
 * frame: where the image starts in world pixel space ([startX], [startY]), how big it is, and
 * what latitude/longitude each pixel corresponds to.
 *
 * Port of gpx2web's `MapImage`, with one deliberate interface change: it takes a `List<Path>`
 * rather than gpx2web's `GPX` wrapper, which lines up with the multi-track model introduced in
 * task g02.
 *
 * ## Antimeridian
 *
 * **Not supported, same as the reference — and this is a freeze, not an oversight.** Bounds are
 * computed as a plain min/max over longitudes, so a track crossing ±180° (say from +179° to
 * −179°) produces bounds spanning almost the entire globe rather than the two-degree window it
 * should. The resulting image is valid but absurdly zoomed out.
 *
 * Handling it properly means detecting the wrap and rendering in a shifted longitude space,
 * which changes the meaning of every accessor here. gpx2web does not do it, no fixture in this
 * repo crosses the line, and doing it silently differently from the reference would be worse
 * than the documented gap. [MapImageTest] pins the current behaviour so a future fix is a
 * deliberate change rather than an accident.
 */
class MapImage private constructor(
    val mapSpace: MapSpace,
) {
    /** Zoom level chosen for this frame. */
    var zoom: Int = 0
        private set

    var minLon: Double = 0.0
        private set

    var maxLon: Double = 0.0
        private set

    var minLat: Double = 0.0
        private set

    var maxLat: Double = 0.0
        private set

    /** Image width in pixels. */
    var width: Int = 0
        private set

    /** Image height in pixels. */
    var height: Int = 0
        private set

    /** X offset of the image's left edge in world pixel space at [zoom]. */
    var startX: Double = 0.0
        private set

    /** Y offset of the image's top edge in world pixel space at [zoom]. */
    var startY: Double = 0.0
        private set

    private var bufferedImage: BufferedImage? = null

    /** The backing image. Created on first access. */
    val image: BufferedImage
        get() =
            bufferedImage ?: BufferedImage(
                max(width, 1),
                max(height, 1),
                BufferedImage.TYPE_INT_RGB,
            ).also { bufferedImage = it }

    /** A `Graphics2D` on [image]. Callers are responsible for disposing of it if they wish. */
    fun createGraphics(): Graphics2D = image.createGraphics()

    /** Pixel X of [lon] within this image. May fall outside `0..width` for points off-frame. */
    fun getX(lon: Double): Int = (mapSpace.lonToX(lon, zoom) - startX).toInt()

    /** Pixel Y of [lat] within this image. */
    fun getY(lat: Double): Int = (mapSpace.latToY(lat, zoom) - startY).toInt()

    /** Longitude at pixel column [x]. */
    fun getLon(x: Int): Double = mapSpace.xToLon(x + startX, zoom)

    /** Latitude at pixel row [y]. */
    fun getLat(y: Int): Double = mapSpace.yToLat(y + startY, zoom)

    /** Fractional tile index along X for [lon] — what g14 needs to know which tiles to fetch. */
    fun getTileI(lon: Double): Double = mapSpace.lonToTileX(lon, zoom)

    /** Fractional tile index along Y for [lat]. */
    fun getTileJ(lat: Double): Double = mapSpace.latToTileY(lat, zoom)

    /** Write [image] to [file] as a PNG. */
    fun saveImage(file: File) {
        file.absoluteFile.parentFile?.mkdirs()
        ImageIO.write(image, "png", file)
    }

    private fun applyZoom(zoom: Int) {
        this.zoom = zoom
        width = abs(mapSpace.lonToX(maxLon, zoom) - mapSpace.lonToX(minLon, zoom)).roundToInt()
        height = abs(mapSpace.latToY(maxLat, zoom) - mapSpace.latToY(minLat, zoom)).roundToInt()
        startX = mapSpace.lonToX(minLon, zoom)
        startY = mapSpace.latToY(maxLat, zoom)
    }

    companion object {
        /** Zoom the reference uses as a working level while computing bounds and margins. */
        private const val REFERENCE_ZOOM = 16

        /**
         * Frame [paths] so that neither dimension exceeds [maxSize] pixels, with [margin] of
         * padding (a ratio: `0.1` = 10 %).
         *
         * The zoom is found by stepping up until one dimension would exceed [maxSize], then
         * backing off one level — so the result is the most detailed zoom that still fits.
         */
        fun ofMaxSize(
            paths: List<Path>,
            margin: Double = 0.0,
            maxSize: Int = 1024,
            mapSpace: MapSpace = MapSpace.TILE_256,
        ): MapImage {
            require(maxSize > 0) { "maxSize must be positive, got $maxSize" }
            val map = MapImage(mapSpace)
            map.initBounds(paths, margin)

            map.applyZoom(0)
            var zoom = 0
            while (zoom < MapSpace.MAX_ZOOM && map.width < maxSize && map.height < maxSize) {
                zoom++
                map.applyZoom(zoom)
            }
            // Step back to the last level that fitted. Guard the floor: a single-point path is
            // 0x0 at every zoom, so the loop above runs to the top and this must not go negative.
            map.applyZoom(max(zoom - 1, 0))
            return map
        }

        /**
         * Frame [paths] into an image of exactly [width] × [height] pixels, with [margin]
         * padding, choosing the deepest zoom at which the padded bounds still fit.
         */
        fun ofSize(
            paths: List<Path>,
            margin: Double = 0.0,
            width: Int,
            height: Int,
            mapSpace: MapSpace = MapSpace.TILE_256,
        ): MapImage {
            require(width > 0 && height > 0) { "width and height must be positive, got $width x $height" }
            val map = MapImage(mapSpace)
            map.width = width
            map.height = height

            val bounds = boundsOf(paths)
            // Pad in pixel space at the reference zoom, exactly as gpx2web does, then convert
            // back — so the margin is proportional to the track's extent, not to the image.
            val z = REFERENCE_ZOOM
            var xMin = mapSpace.lonToX(bounds.minLon, z)
            var xMax = mapSpace.lonToX(bounds.maxLon, z)
            var yMin = mapSpace.latToY(bounds.maxLat, z)
            var yMax = mapSpace.latToY(bounds.minLat, z)
            val delta = max((xMax - xMin) * margin / 2.0, (yMax - yMin) * margin / 2.0)
            xMin -= delta
            xMax += delta
            yMin -= delta
            yMax += delta

            val lonMin = mapSpace.xToLon(xMin, z)
            val lonMax = mapSpace.xToLon(xMax, z)
            val latMin = mapSpace.yToLat(yMax, z)
            val latMax = mapSpace.yToLat(yMin, z)
            val lonCenter = (lonMin + lonMax) / 2.0
            val latCenter = (latMin + latMax) / 2.0

            // Walk zoom down until the padded bounds fit inside the fixed frame. Zoom 0 always
            // fits (the whole world), so this terminates.
            var zoom = MapSpace.MAX_ZOOM
            while (zoom > 0) {
                map.zoom = zoom
                map.startX = mapSpace.lonToX(lonCenter, zoom) - width / 2.0
                map.startY = mapSpace.latToY(latCenter, zoom) - height / 2.0
                map.minLon = mapSpace.xToLon(map.startX, zoom)
                map.maxLon = mapSpace.xToLon(map.startX + width, zoom)
                map.minLat = mapSpace.yToLat(map.startY + height, zoom)
                map.maxLat = mapSpace.yToLat(map.startY, zoom)

                val fits =
                    lonMin >= map.minLon &&
                        lonMax <= map.maxLon &&
                        latMin >= map.minLat &&
                        latMax <= map.maxLat
                if (fits) break
                zoom--
            }
            if (zoom == 0) {
                map.zoom = 0
                map.startX = mapSpace.lonToX(lonCenter, 0) - width / 2.0
                map.startY = mapSpace.latToY(latCenter, 0) - height / 2.0
                map.minLon = mapSpace.xToLon(map.startX, 0)
                map.maxLon = mapSpace.xToLon(map.startX + width, 0)
                map.minLat = mapSpace.yToLat(map.startY + height, 0)
                map.maxLat = mapSpace.yToLat(map.startY, 0)
            }
            return map
        }

        /** Geographic bounding box of every point of every path. */
        private fun boundsOf(paths: List<Path>): Bounds {
            var minLon = Double.POSITIVE_INFINITY
            var maxLon = Double.NEGATIVE_INFINITY
            var minLat = Double.POSITIVE_INFINITY
            var maxLat = Double.NEGATIVE_INFINITY
            var seen = false
            for (path in paths) {
                for (i in 0 until path.size) {
                    val lon = path.longitude(i) * MathConstants.RAD_TO_DEG
                    val lat = path.latitude(i) * MathConstants.RAD_TO_DEG
                    if (lon.isNaN() || lat.isNaN()) continue
                    minLon = minOf(minLon, lon)
                    maxLon = maxOf(maxLon, lon)
                    minLat = minOf(minLat, lat)
                    maxLat = maxOf(maxLat, lat)
                    seen = true
                }
            }
            require(seen) { "Cannot frame a map: no path contains any point" }
            return Bounds(minLon, maxLon, minLat, maxLat)
        }

        private data class Bounds(
            val minLon: Double,
            val maxLon: Double,
            val minLat: Double,
            val maxLat: Double,
        )
    }

    private fun initBounds(
        paths: List<Path>,
        margin: Double,
    ) {
        val bounds = boundsOf(paths)
        minLon = bounds.minLon
        maxLon = bounds.maxLon
        minLat = bounds.minLat
        maxLat = bounds.maxLat

        // Pad at a fixed working zoom then convert back to degrees, matching the reference.
        applyZoom(REFERENCE_ZOOM)
        val xMin = mapSpace.lonToX(minLon, zoom)
        val xMax = mapSpace.lonToX(maxLon, zoom)
        val yMin = mapSpace.latToY(maxLat, zoom)
        val yMax = mapSpace.latToY(minLat, zoom)
        val delta = max((xMax - xMin) * margin / 2.0, (yMax - yMin) * margin / 2.0)

        // Round each corner OUTWARD. gpx2web truncates both corners toward zero, which shrinks
        // the box by a sub-pixel amount and can leave the extreme point a fraction of a pixel
        // outside the bounds — about 2 m at the working zoom, invisible on a map but enough to
        // make "the bounds contain the track" false. Deliberate, documented deviation: enclosing
        // the track is the whole job of this class.
        minLon = mapSpace.xToLon(floor(xMin - delta), zoom)
        maxLon = mapSpace.xToLon(ceil(xMax + delta), zoom)
        maxLat = mapSpace.yToLat(floor(yMin - delta), zoom)
        minLat = mapSpace.yToLat(ceil(yMax + delta), zoom)
    }
}
