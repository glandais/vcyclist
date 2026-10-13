package io.github.glandais.api.http

/**
 * The response formats of `/enhance` and `/export`, by their `format` query value.
 *
 * The catalogue publishes [ids]; the format negotiation resolves against [byId].
 */
enum class OutputFormat(
    val id: String,
) {
    SUMMARY("summary"),
    GPX("gpx"),
    FIT("fit"),
    CSV("csv"),
    JSON("json"),
    ;

    companion object {
        val ids: List<String> get() = entries.map { it.id }

        fun byId(id: String): OutputFormat =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown format '$id': expected one of ${ids.joinToString(", ")}")
    }
}
