@file:JvmName("GpxParserJvm")

package io.github.glandais.engine.gpx

/**
 * Java-callable form of [GpxParser] (task g27). See `GpxWriterJvm` for why these facades exist
 * rather than `@JvmOverloads` on the common declaration.
 *
 * `GpxParser.parse(xml)` is the single most common entry point in the library, and until now Java
 * had to write `parse(xml, true)` — passing a flag it has no reason to know about. The g22 Java
 * tests do exactly that, with a comment pointing here.
 */
@JvmOverloads
fun parse(
    xml: String,
    repairOnFailure: Boolean = true,
): GpxDocument = GpxParser.parse(xml, repairOnFailure)
