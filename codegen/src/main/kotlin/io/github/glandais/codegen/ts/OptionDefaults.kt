package io.github.glandais.codegen.ts

import io.github.glandais.codegen.surface.OptionCatalog

/**
 * The engine defaults a JS caller cannot otherwise reach, rendered as TypeScript constants.
 *
 * Three of the JS door's entry points are **positional functions** — `detectClimbsWithOptions`,
 * `pathToCsv`, `pathToJson`. A caller who wants to change the sixth argument has to name the first
 * five, and there is no options object to omit a field from, so the defaults have to come from
 * somewhere. Until this existed, "somewhere" was `demo/src/composables/useClimbs.ts`, which spelled
 * six of `ClimbOptions`' defaults as literals and said so in a comment — the exact thing CLAUDE.md
 * forbids, and the shape of the bug that once had the façades defending 250 W against the CLI's
 * 280 W.
 *
 * The DTO-shaped doors deliberately get nothing here: omitting a field from `EnhanceOptionsDto`
 * already makes `defaultJsOptions()` supply the default, so publishing the value would create a
 * second place for it to be wrong.
 *
 * Values are read off [OptionCatalog], by reflection against a real instance, so they cannot be
 * restated here either — and `OptionCatalog` derives its own completeness from
 * `primaryConstructor.parameters`, so an option added to one of these classes fails that check
 * before it reaches this one.
 */
object OptionDefaults {
    data class Entry(
        val wireName: String,
        val value: Any?,
    )

    data class Group(
        /** The exported constant's name: `ClimbOptions` → `climbDefaults`. */
        val constant: String,
        val optionsClass: String,
        val jsFunction: String,
        val entries: List<Entry>,
    )

    /**
     * The groups whose JS door is one of [functionNames], so the set is derived from the façade
     * actually being rendered rather than listed a second time.
     */
    fun forFunctions(functionNames: Set<String>): List<Group> =
        OptionCatalog.groups
            .mapNotNull { group ->
                val jsFunction = group.jsFunction ?: return@mapNotNull null
                if (jsFunction !in functionNames) return@mapNotNull null
                val entries =
                    group.options
                        .filter { OptionCatalog.Door.JS in it.doors }
                        .map { Entry(it.wireName, group.defaultOf(it.path)) }
                if (entries.isEmpty()) return@mapNotNull null
                Group(
                    constant = constantName(group.name),
                    optionsClass = group.name,
                    jsFunction = jsFunction,
                    entries = entries,
                )
            }

    /** `ClimbOptions` → `climbDefaults`. */
    private fun constantName(className: String): String = className.removeSuffix("Options").replaceFirstChar { it.lowercase() } + "Defaults"

    /**
     * A Kotlin default as a TypeScript literal.
     *
     * Fails closed on anything it does not recognise, for the same reason [TypeMapper] does: a
     * default rendered wrong is worse than one that is absent, because it looks authoritative.
     */
    fun literal(
        value: Any?,
        site: String,
    ): String =
        when (value) {
            // A legitimate default: `CsvOptions.decimals = null` means "do not round".
            null -> "null"
            is Boolean -> value.toString()
            is Int, is Long -> value.toString()
            is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
            is String -> "'${escape(value)}'"
            is Char -> "'${escape(value.toString())}'"
            is Enum<*> -> "'${escape(wireSpelling(value, site))}'"
            else ->
                error(
                    "$site: no TypeScript literal for a default of type ${value?.let { it::class.simpleName }}. " +
                        "Teach OptionDefaults the type, or drop the option from the emitted defaults — " +
                        "guessing a value here would publish a wrong number that reads as authoritative.",
                )
        }

    /** TypeScript single-quoted string escaping. A CSV delimiter is very often a tab. */
    private fun escape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    /** The spelling the doors parse, off the enum's own catalogue rather than `name`. */
    private fun wireSpelling(
        value: Enum<*>,
        site: String,
    ): String {
        val property =
            value::class.members.firstOrNull { it.name == "wireName" || it.name == "id" }
                ?: error(
                    "$site: ${value::class.simpleName} has no `wireName` or `id`, so its wire spelling " +
                        "cannot be derived. Give it a catalogue like RoadCondition's.",
                )
        return property.call(value) as String
    }

    /** The TypeScript type of a rendered default, for the `.d.ts` side. */
    fun type(
        value: Any?,
        site: String,
    ): String =
        when (value) {
            null -> "null"
            is Boolean -> "boolean"
            is Int, is Long, is Double -> "number"
            // A literal type, not `string`: these are closed sets, and a caller who spreads the
            // constant should still be told when it assigns something else.
            is String, is Char, is Enum<*> -> literal(value, site)
            else -> error("$site: no TypeScript type for ${value?.let { it::class.simpleName }}")
        }
}
