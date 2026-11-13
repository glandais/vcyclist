@file:JvmName("GpxSegmentOrderJvm")

package io.github.glandais.engine.gpx

/**
 * Java-callable form of the segment-ordering functions of `GpxSegmentOrder.kt`. Java sees no
 * default argument, so without these every call would spell [SegmentOrderOptions] and `maxPieces`
 * out. Names differ from the common functions on purpose where the signature would otherwise be
 * the same: a same-package facade with the same name and parameters shadows the original and
 * calls itself.
 */
@JvmOverloads
fun withOrderedSegments(
    document: GpxDocument,
    options: SegmentOrderOptions = SegmentOrderOptions.DEFAULT,
): GpxDocument = document.withOrderedSegments(options)

@JvmOverloads
fun orderedSegments(
    track: GpxTrack,
    options: SegmentOrderOptions = SegmentOrderOptions.DEFAULT,
): GpxTrack = track.orderedSegments(options)

/** [planSegmentOrder] — `plan(pieces)` caps at [SegmentOrderOptions.DEFAULT_MAX_SEGMENTS]. */
@JvmOverloads
fun plan(
    pieces: List<List<GpxTrackPoint>>,
    maxPieces: Int = SegmentOrderOptions.DEFAULT_MAX_SEGMENTS,
): SegmentOrderPlan = planSegmentOrder(pieces, maxPieces)

/** [segmentOrderGapM]. */
fun gapM(
    pieces: List<List<GpxTrackPoint>>,
    order: List<Int>,
    reversed: List<Boolean>,
): Double = segmentOrderGapM(pieces, order, reversed)

/** A [SegmentOrderOptions] with the defaults of the fields left out. */
@JvmOverloads
fun segmentOrderOptions(
    minGainM: Double = SegmentOrderOptions.DEFAULT_MIN_GAIN_M,
    maxSegments: Int = SegmentOrderOptions.DEFAULT_MAX_SEGMENTS,
): SegmentOrderOptions = SegmentOrderOptions(minGainM, maxSegments)
