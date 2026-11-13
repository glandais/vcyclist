@file:JvmName("GpxToPathJvm")

package io.github.glandais.engine.gpx

import io.github.glandais.engine.path.Path

/**
 * Java-callable form of the `GpxDocument` → `Path` conversions (task g27). See `GpxWriterJvm` for
 * why these facades exist.
 *
 * The `kinds` default matters more here than elsewhere: `ALL_KINDS` is not public, so before this
 * facade a Java caller could not express "the paths in this file" at all — it had to name a set,
 * and the obvious `EnumSet.of(GpxPathKind.TRACK)` silently reinstates the pre-g24 behaviour that
 * drops every `<rte>`. Getting the default back required knowing it was `entries.toSet()`.
 */
@JvmOverloads
fun tracksAsPaths(
    document: GpxDocument,
    kinds: Set<GpxPathKind> = ALL_KINDS,
): List<Path> = document.tracksAsPaths(kinds)

/**
 * `orderSegments` puts each track's segments back end to end first (see `orderedSegments`).
 * Overloads, not a third defaulted parameter above, so `tracksAsPaths(document)` and
 * `tracksAsPaths(document, kinds)` keep their exact bytecode signatures.
 */
@JvmOverloads
fun tracksAsPaths(
    document: GpxDocument,
    kinds: Set<GpxPathKind>,
    orderSegments: Boolean,
    options: SegmentOrderOptions = SegmentOrderOptions.DEFAULT,
): List<Path> = document.tracksAsPaths(kinds, orderSegments, options)

/** All containers, segments put back end to end when [orderSegments] is `true`. */
fun tracksAsPaths(
    document: GpxDocument,
    orderSegments: Boolean,
): List<Path> = document.tracksAsPaths(ALL_KINDS, orderSegments)

@JvmOverloads
fun segmentsAsPaths(
    document: GpxDocument,
    kinds: Set<GpxPathKind> = ALL_KINDS,
): List<Path> = document.segmentsAsPaths(kinds)

@JvmOverloads
fun segmentsAsPaths(
    document: GpxDocument,
    kinds: Set<GpxPathKind>,
    orderSegments: Boolean,
    options: SegmentOrderOptions = SegmentOrderOptions.DEFAULT,
): List<Path> = document.segmentsAsPaths(kinds, orderSegments, options)

fun segmentsAsPaths(
    document: GpxDocument,
    orderSegments: Boolean,
): List<Path> = document.segmentsAsPaths(ALL_KINDS, orderSegments)

fun firstTrackAsPath(document: GpxDocument): Path = document.firstTrackAsPath()

@JvmOverloads
fun firstTrackAsPath(
    document: GpxDocument,
    orderSegments: Boolean,
    options: SegmentOrderOptions = SegmentOrderOptions.DEFAULT,
): Path = document.firstTrackAsPath(orderSegments, options)

fun toPath(track: GpxTrack): Path = track.toPath()

fun toPath(segment: GpxSegment): Path = segment.toPath()
