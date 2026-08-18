package io.github.glandais.codegen.ts

import java.io.File

/**
 * Resolves a Kotlin enum name to the spellings it puts on the wire, by reading its `commonMain`
 * source.
 *
 * Derived rather than restated, for the reason `RoadCondition.wireName`'s KDoc gives: a constant
 * added to the enum breaks the exhaustive `when` in `commonMain`, on every target at once. A union
 * transcribed by hand into TypeScript has no such link — the demo's `engine-shim.ts` spelled
 * `'dry' | 'wet'` beside it for four tasks and nothing compared the two.
 */
class EnumCatalog(
    private val sourceRoots: List<File>,
) {
    enum class Style {
        /** The spelling the doors parse: a `when (this)` arm, or a constructor argument. */
        WIRE,

        /** The Kotlin constant name, for a DTO built with `.name`. */
        NAME,
    }

    private val cache = mutableMapOf<Pair<String, Style>, List<String>>()

    fun values(
        enum: String,
        style: Style,
    ): List<String> =
        cache.getOrPut(enum to style) {
            val body = body(enum)
            val values =
                when (style) {
                    Style.NAME -> entryNames(body)
                    Style.WIRE -> wireNames(body).ifEmpty { constructorIds(body) }
                }
            require(values.isNotEmpty()) {
                "enum $enum: found no ${style.name.lowercase()} spellings. Its catalogue is neither a " +
                    "`when (this) { X -> \"x\" }` nor a `X(\"x\")` constructor argument — teach " +
                    "EnumCatalog the new shape rather than transcribing the union by hand."
            }
            values
        }

    private fun body(enum: String): String {
        val declaration = Regex("""(?m)^enum class $enum(?:\(|\s|\{)""")
        val file =
            sourceRoots
                .asSequence()
                .flatMap { it.walkTopDown() }
                .filter { it.isFile && it.extension == "kt" }
                .firstOrNull { declaration.containsMatchIn(it.readText()) }
                ?: error("enum class $enum is in none of $sourceRoots")
        val text = file.readText()
        val start = declaration.find(text)!!.range.first
        val open = text.indexOf('{', start)
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, index)
                }
            }
        }
        error("enum class $enum: its body is never closed")
    }

    /** `X -> "x"` arms of the catalogue `when`, in declaration order. */
    private fun wireNames(body: String): List<String> =
        Regex("""(?m)^\s+[A-Z][A-Z0-9_]* -> "([^"]+)"""")
            .findAll(body)
            .map { it.groupValues[1] }
            .toList()

    /**
     * `X("x"),` — the id-as-constructor-argument shape `PowerModel` uses, and `PointField` over
     * several lines. `\s*` spans the newline ktlint inserts once the argument list is long.
     */
    private fun constructorIds(body: String): List<String> =
        Regex("""(?m)^\s{4}[A-Z][A-Z0-9_]*\(\s*"([^"]+)"""")
            .findAll(body)
            .map { it.groupValues[1] }
            .toList()

    private fun entryNames(body: String): List<String> =
        Regex("""(?m)^\s{4}([A-Z][A-Z0-9_]*)\s*(?:,|\(|$)""")
            .findAll(body)
            .map { it.groupValues[1] }
            .toList()
}
