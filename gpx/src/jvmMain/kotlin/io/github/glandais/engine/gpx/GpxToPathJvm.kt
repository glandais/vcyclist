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

@JvmOverloads
fun segmentsAsPaths(
    document: GpxDocument,
    kinds: Set<GpxPathKind> = ALL_KINDS,
): List<Path> = document.segmentsAsPaths(kinds)

fun firstTrackAsPath(document: GpxDocument): Path = document.firstTrackAsPath()

fun toPath(track: GpxTrack): Path = track.toPath()

fun toPath(segment: GpxSegment): Path = segment.toPath()
