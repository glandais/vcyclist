package io.github.glandais.codegen.ts

/**
 * Reads a `*JsApi.kt` façade as **source text** and returns the shapes a TypeScript declaration
 * needs: the `external interface` DTOs and the `@JsExport` top-level functions.
 *
 * Source text rather than reflection or the compiler-emitted `.d.ts`, for the reason
 * `DoorKeyParityTest` gives: nothing on the JVM can reflect a Kotlin/JS declaration, and Kotlin/JS
 * emits **no body** for an `external interface` — which is the whole defect this generator exists
 * to close. Reading `.kt` instead of the emitted `.d.ts` also keeps the generator independent of a
 * JS compilation, so it runs as a plain `JavaExec`.
 *
 * Every extractor here fails **closed**: an unparsable member throws rather than degrading to
 * `any`. A generator that silently emits `any` would reproduce the very defect it replaces.
 */
object KotlinFacadeParser {
    /** A property of an `external interface`, already carrying its rendered TS type. */
    data class Property(
        val name: String,
        val type: TsType,
        val optional: Boolean,
        val doc: String?,
    )

    data class Dto(
        val name: String,
        val doc: String?,
        val properties: List<Property>,
    )

    data class Parameter(
        val name: String,
        val type: TsType,
        val hasDefault: Boolean,
    )

    data class Function(
        val name: String,
        val doc: String?,
        val parameters: List<Parameter>,
        val returnType: TsType,
    )

    /**
     * `external interface Name { … }` blocks, in declaration order.
     *
     * Anchored at column 0 so a nested or commented-out declaration cannot match, and terminated by
     * a closing brace at column 0 — the formatting ktlint enforces on this file.
     */
    fun dtos(
        source: String,
        types: TypeMapper,
    ): List<Dto> =
        Regex("""(?m)^external interface (\w+) \{\n(.*?)\n\}$""", RegexOption.DOT_MATCHES_ALL)
            .findAll(source)
            .map { match ->
                val name = match.groupValues[1]
                Dto(
                    name = name,
                    doc = kdocBefore(source, match.range.first),
                    properties = properties(name, match.groupValues[2], types),
                )
            }.toList()

    private fun properties(
        dto: String,
        body: String,
        types: TypeMapper,
    ): List<Property> {
        val declarations =
            Regex("""(?m)^ {4}val (\w+): (\S.*?)$""")
                .findAll(body)
                .toList()
        require(declarations.isNotEmpty()) {
            "external interface $dto has no `    val name: Type` member — the extractor is broken, " +
                "or the file was reformatted. A regex that matches nothing turns every assertion " +
                "downstream into a tautology."
        }
        return declarations.map { match ->
            val name = match.groupValues[1]
            val doc = kdocBefore(body, match.range.first)
            val kotlinType = match.groupValues[2].trim()
            Property(
                name = name,
                type = types.map(kotlinType, "$dto.$name", doc),
                optional = kotlinType.endsWith("?"),
                doc = doc,
            )
        }
    }

    /**
     * `@JsExport` top-level functions, both the single-line and the wrapped parameter-list shape
     * ktlint produces past its column budget.
     */
    fun functions(
        source: String,
        types: TypeMapper,
    ): List<Function> =
        Regex("""(?m)^@JsExport\n(?:^@\S+\n)*^fun (\w+)\(""")
            .findAll(source)
            .map { match ->
                val name = match.groupValues[1]
                val open = source.indexOf('(', match.range.last - 1)
                val close = matchingParenthesis(source, open, name)
                val parameterList = source.substring(open + 1, close)
                val tail = source.substring(close + 1)
                val returnType =
                    Regex("""^:\s*([^=\n{]+?)\s*(?:=|\{|$)""", RegexOption.MULTILINE)
                        .find(tail)
                        ?.groupValues
                        ?.get(1)
                        ?.trim()
                        ?: error(
                            "$name: no return type after its parameter list. Every @JsExport " +
                                "function must declare one explicitly — an inferred type is not " +
                                "readable from source text.",
                        )
                Function(
                    name = name,
                    doc = kdocBefore(source, match.range.first),
                    parameters = parameters(name, parameterList, types),
                    returnType = types.map(returnType, "$name (return)", null),
                )
            }.toList()

    private fun parameters(
        function: String,
        parameterList: String,
        types: TypeMapper,
    ): List<Parameter> {
        if (parameterList.isBlank()) return emptyList()
        return splitTopLevel(parameterList).map { raw ->
            val declaration = raw.trim().removeSuffix(",").trim()
            val match =
                Regex("""^(\w+):\s*(.+?)(?:\s*=\s*(.+))?$""", RegexOption.DOT_MATCHES_ALL).find(declaration)
                    ?: error("$function: cannot parse the parameter `$declaration`")
            val kotlinType = match.groupValues[2].trim()
            Parameter(
                name = match.groupValues[1],
                type = types.map(kotlinType, "$function.${match.groupValues[1]}", null),
                hasDefault = match.groupValues[3].isNotEmpty(),
            )
        }
    }

    /** Splits a parameter list on commas that are not inside `<…>` or `(…)`. */
    private fun splitTopLevel(parameterList: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (character in parameterList) {
            when (character) {
                '<', '(' -> depth++
                '>', ')' -> depth--
            }
            if (character == ',' && depth == 0) {
                parts += current.toString()
                current.clear()
            } else {
                current.append(character)
            }
        }
        if (current.isNotBlank()) parts += current.toString()
        return parts.filter { it.isNotBlank() }
    }

    private fun matchingParenthesis(
        source: String,
        open: Int,
        function: String,
    ): Int {
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        error("$function: its parameter list is never closed")
    }

    /**
     * The KDoc block immediately above [position], with the comment syntax stripped — `null` when
     * the declaration carries none, or when anything but blank lines and annotations separates
     * them.
     */
    fun kdocBefore(
        source: String,
        position: Int,
    ): String? {
        val preceding = source.substring(0, position)
        val end = preceding.lastIndexOf("*/")
        if (end < 0) return null
        val between = preceding.substring(end + 2)
        if (between.lines().any { it.isNotBlank() && !it.trimStart().startsWith("@") }) return null
        val start = preceding.lastIndexOf("/**", end)
        if (start < 0) return null
        return preceding
            .substring(start + 3, end)
            .lines()
            .joinToString("\n") { it.trim().removePrefix("*").removePrefix(" ") }
            .trim()
            .ifEmpty { null }
    }
}
