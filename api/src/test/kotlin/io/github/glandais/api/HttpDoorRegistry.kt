package io.github.glandais.api

import io.github.glandais.api.dto.BikeDto
import io.github.glandais.api.dto.ClimbOptionsDto
import io.github.glandais.api.dto.CsvOptionsDto
import io.github.glandais.api.dto.CyclistDto
import io.github.glandais.api.dto.EnhanceOptionsDto
import io.github.glandais.api.dto.JsonOptionsDto
import io.github.glandais.api.dto.PowerProviderDto
import io.github.glandais.api.http.decodeStrict
import io.github.glandais.api.mapping.HttpDefaults
import io.github.glandais.api.mapping.toBike
import io.github.glandais.api.mapping.toClimbOptions
import io.github.glandais.api.mapping.toCsvOptions
import io.github.glandais.api.mapping.toCyclist
import io.github.glandais.api.mapping.toEnhanceOptions
import io.github.glandais.api.mapping.toJsonOptions
import io.github.glandais.api.mapping.toPowerSpec
import kotlinx.serialization.KSerializer
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

/**
 * One `OptionCatalog` group's HTTP door: the `:api` DTO that carries it, the reader that maps it
 * onto the core class, and the object [HttpDefaults] applies when nothing is said.
 */
class HttpBinding<D>(
    val serializer: KSerializer<D>,
    private val reader: (D) -> Any,
    val defaults: Any,
) {
    /** Strict decoding (unknown keys are an error, as on the wire), then the door's own mapper. */
    fun decodeAndMap(json: String): Any = reader(decodeStrict(serializer, json))
}

/**
 * The HTTP door's registry, keyed by the DTO's `@SerialName` — the name `OptionGroup.httpDto`
 * holds. Hand-written on purpose: it is what the parity test compares the catalog *against*, so
 * deriving it from the catalog would make the comparison a tautology. Its size is checked against
 * `OptionCatalog.groups` instead (HttpDoorParityTest).
 */
val HTTP_DOOR: Map<String, HttpBinding<*>> =
    mapOf(
        "ClimbOptions" to HttpBinding(ClimbOptionsDto.serializer(), { it.toClimbOptions() }, HttpDefaults.climb),
        "CsvOptions" to HttpBinding(CsvOptionsDto.serializer(), { it.toCsvOptions() }, HttpDefaults.csv),
        "EnhanceOptions" to HttpBinding(EnhanceOptionsDto.serializer(), { it.toEnhanceOptions() }, HttpDefaults.enhance),
        "Cyclist" to HttpBinding(CyclistDto.serializer(), { it.toCyclist().first }, HttpDefaults.cyclist),
        "Bike" to HttpBinding(BikeDto.serializer(), { it.toBike() }, HttpDefaults.bike),
        "PowerProvider" to HttpBinding(PowerProviderDto.serializer(), { it.toPowerSpec() }, HttpDefaults.power),
        "JsonOptions" to HttpBinding(JsonOptionsDto.serializer(), { it.toJsonOptions() }, HttpDefaults.json),
    )

/**
 * Resolves a catalog `path` (`"simplifyPath.toleranceM"`) on [root] by reflection — the same walk
 * as `OptionGroup.defaultOf`, but on the object the door produced rather than on a fresh instance.
 */
fun resolvePath(
    root: Any,
    path: String,
): Any? {
    var current: Any? = root
    for (segment in path.split('.')) {
        val owner = current ?: error("$path: null before '$segment'")

        @Suppress("UNCHECKED_CAST")
        val property =
            owner::class.memberProperties.firstOrNull { it.name == segment } as? KProperty1<Any, *>
                ?: error("$path: no property '$segment' on ${owner::class.simpleName}")
        current = property.get(owner)
    }
    return current
}
