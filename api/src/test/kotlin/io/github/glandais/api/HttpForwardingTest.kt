package io.github.glandais.api

import io.github.glandais.api.dto.CyclistDto
import io.github.glandais.api.dto.EnhanceOptionsDto
import io.github.glandais.api.http.decodeStrict
import io.github.glandais.api.mapping.HttpDefaults
import io.github.glandais.api.mapping.demSource
import io.github.glandais.api.mapping.demZoom
import io.github.glandais.api.mapping.toCyclist
import io.github.glandais.codegen.surface.OptionCatalog
import io.github.glandais.codegen.surface.OptionCatalog.Door
import io.github.glandais.elevation.ElevationSource
import io.github.glandais.engine.RoadCondition
import io.github.glandais.engine.applyTo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **An accepted key is a used key** — for every option the catalog gives [Door.HTTP].
 *
 * `HttpDoorParityTest` proves the DTO *accepts* each key; that is the `requireOnly` half, which
 * never sees a mapper that forgot to forward one (`vcWriteGpxTracks` once parsed two keys and
 * dropped both). Here each key is sent **alone**, with a sentinel that differs from the door's
 * default, through strict decoding and the door's own mapper; the catalog's `path` is then resolved
 * on the core object the mapper built, and must have moved off [HttpDefaults].
 *
 * The sentinel is derived from the default's type, so a new option needs nothing here unless its
 * domain refuses `default + 1` — then it gets an entry in [sentinelOverrides], whose keys are
 * themselves checked against the catalog.
 */
class HttpForwardingTest {
    /**
     * Values for keys where the derived sentinel would be invalid or meaningless, by
     * `"Group.wireName"`. A `null` default has no type to derive from (`decimals`, `fields`).
     */
    private val sentinelOverrides: Map<String, JsonElement> =
        mapOf(
            "CsvOptions.decimals" to JsonPrimitive(3),
            "CsvOptions.fields" to JsonArray(listOf(JsonPrimitive("speed"))),
            "CsvOptions.lineSeparator" to JsonPrimitive(if (HttpDefaults.csv.lineSeparator == "\r\n") "\n" else "\r\n"),
            "JsonOptions.decimals" to JsonPrimitive(3),
            "JsonOptions.fields" to JsonArray(listOf(JsonPrimitive("speed"))),
            // A ratio in (0, 1]: default + 1 is physically meaningless (Bike does not refuse it).
            "Bike.efficiency" to JsonPrimitive(HttpDefaults.bike.efficiency / 2),
        )

    private fun sentinel(
        site: String,
        default: Any?,
    ): JsonElement =
        sentinelOverrides[site] ?: when (default) {
            is Boolean -> JsonPrimitive(!default)
            is Double -> JsonPrimitive(default + 1.0)
            is Int -> JsonPrimitive(default + 1)
            is Long -> JsonPrimitive(default + 1)
            is Char -> JsonPrimitive(if (default == ';') "," else ";")
            is Enum<*> -> JsonPrimitive(otherWireValue(default, site))
            else -> fail("$site: no sentinel for a default of ${default?.let { it::class.simpleName }}; add one to sentinelOverrides")
        }

    /** Another entry of the same enum, spelled through its own wire catalogue (`id` or `wireName`). */
    private fun otherWireValue(
        default: Enum<*>,
        site: String,
    ): String {
        val other =
            default.javaClass.enumConstants.firstOrNull { it != default }
                ?: fail("$site: ${default.javaClass.simpleName} has a single entry, nothing to switch to")
        val spelling =
            other::class.members.firstOrNull { it.name == "id" || it.name == "wireName" }
                ?: fail("$site: ${other.javaClass.simpleName} has no `id` or `wireName` wire catalogue")
        return spelling.call(other) as String
    }

    @Test
    fun `the sentinel overrides name options that exist`() {
        val sites =
            OptionCatalog.groups
                .flatMap { group -> group.options.filter { Door.HTTP in it.doors }.map { "${group.name}.${it.wireName}" } }
                .toSet()
        assertEquals(emptySet(), sentinelOverrides.keys - sites, "a stale override protects nothing")
    }

    @Test
    fun `every HTTP option reaches the core object`() {
        val notForwarded = mutableListOf<String>()
        var checked = 0
        for (group in OptionCatalog.groups) {
            val binding = HTTP_DOOR.getValue(group.httpDto!!)
            for (option in group.options.filter { Door.HTTP in it.doors }) {
                val site = "${group.name}.${option.wireName}"
                val default = resolvePath(binding.defaults, option.path)
                val json = buildJsonObject { put(option.wireName, sentinel(site, default)) }.toString()
                val mapped =
                    try {
                        binding.decodeAndMap(json)
                    } catch (e: Exception) {
                        fail("$site: the sentinel $json was refused (${e.message}); give it a valid one in sentinelOverrides", e)
                    }
                val actual = resolvePath(mapped, option.path)
                if (actual == default) notForwarded += "$site ($json left ${option.path} at $actual)"
                checked++
            }
        }
        assertEquals(emptyList(), notForwarded, "the DTO accepts these keys and the mapper drops them")
        // The size self-check: an empty loop would pass the assertion above for the wrong reason.
        val catalogued = OptionCatalog.groups.sumOf { g -> g.options.count { Door.HTTP in it.doors } }
        assertEquals(catalogued, checked)
        assertTrue(checked >= 50, "only $checked HTTP options checked — the catalog must have shrunk")
    }

    /**
     * The wire keys with no field behind them (`OptionCatalog.WireOnly`) cannot be resolved by
     * path; each has its own behavioural assertion, and the set of them is checked against the
     * catalog so a new one cannot slip past untested.
     */
    @Test
    fun `every wire-only HTTP key has a behavioural check`() {
        val checks: Map<String, () -> Unit> =
            mapOf(
                "EnhanceOptions.demZoom" to {
                    assertNull(EnhanceOptionsDto().demZoom(), "the default is the source's own zoom")
                    val dto = decodeStrict(EnhanceOptionsDto.serializer(), """{"demZoom":13}""")
                    assertEquals(13, dto.demZoom())
                },
                "EnhanceOptions.demSource" to {
                    val other = ElevationSource.entries.first { it != HttpDefaults.elevationSource }
                    val dto = decodeStrict(EnhanceOptionsDto.serializer(), """{"demSource":"${other.id}"}""")
                    assertEquals(other, dto.demSource())
                },
                "Cyclist.roadCondition" to {
                    val condition =
                        RoadCondition.entries.first { it.applyTo(HttpDefaults.cyclist) != HttpDefaults.cyclist }
                    val dto = decodeStrict(CyclistDto.serializer(), """{"roadCondition":"${condition.wireName}"}""")
                    val (cyclist, _) = dto.toCyclist()
                    assertEquals(condition.applyTo(HttpDefaults.cyclist), cyclist)
                    assertNotEquals(HttpDefaults.cyclist, cyclist)
                },
            )
        val catalogued =
            OptionCatalog.groups
                .flatMap { group -> group.wireOnly.filter { Door.HTTP in it.doors }.map { "${group.name}.${it.wireName}" } }
                .toSet()
        assertEquals(catalogued, checks.keys, "each wire-only HTTP key needs exactly one behavioural check")
        checks.values.forEach { it() }
    }
}
