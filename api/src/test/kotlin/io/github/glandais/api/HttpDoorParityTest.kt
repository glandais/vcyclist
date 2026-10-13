package io.github.glandais.api

import io.github.glandais.codegen.surface.OptionCatalog
import io.github.glandais.codegen.surface.OptionCatalog.Door
import kotlinx.serialization.descriptors.elementNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The HTTP door's half of `DoorParityTest`: `OptionCatalog` claims which keys cross [Door.HTTP],
 * and this checks the claim against the `:api` DTOs **by reflection** — their serial descriptors,
 * not their source text. `:codegen` cannot do it from its side (it may not depend on `:api`), so
 * `:codegen`'s `DoorParityTest` only demands that a group claiming HTTP names its DTO (`httpDto`).
 */
class HttpDoorParityTest {
    @Test
    fun `the registry has one DTO per catalogued group`() {
        // The size self-check. Without it, a registry that lost an entry would make every
        // per-group comparison below skip that group and pass — `assertEquals(empty, empty)`.
        assertEquals(OptionCatalog.groups.size, HTTP_DOOR.size, "HTTP_DOOR and OptionCatalog.groups disagree in size")
        for ((name, binding) in HTTP_DOOR) {
            assertEquals(name, binding.serializer.descriptor.serialName, "the registry key must be the DTO's @SerialName")
        }
    }

    @Test
    fun `every group names a DTO of the registry, and every DTO is some group's`() {
        val named =
            OptionCatalog.groups.map { group ->
                assertNotNull(group.httpDto, "${group.name} has no httpDto")
            }
        assertEquals(named.toSet(), HTTP_DOOR.keys, "a DTO no group references is a door the catalog does not describe")
        assertEquals(named.size, named.toSet().size, "two groups name the same DTO: $named")
    }

    @Test
    fun `each DTO accepts exactly the keys the catalog gives the HTTP door`() {
        // Accumulated, so one drift does not hide the next.
        val drift =
            OptionCatalog.groups.mapNotNull { group ->
                val binding = HTTP_DOOR.getValue(group.httpDto!!)
                val accepted =
                    binding.serializer.descriptor.elementNames
                        .toSet()
                val claimed = group.wireNames(Door.HTTP)
                if (accepted == claimed) {
                    null
                } else {
                    "${group.name} (${group.httpDto}): DTO-only ${(accepted - claimed).sorted()}, " +
                        "catalog-only ${(claimed - accepted).sorted()}"
                }
            }
        assertEquals(
            emptyList(),
            drift,
            "a DTO-only key is accepted by HTTP and described by nothing; a catalog-only key is a " +
                "documented option the API rejects as unknown-key.",
        )
    }

    @Test
    fun `the HTTP door accepts every key the JS and WASI doors accept`() {
        // No deliberate exception today: HTTP is a superset (it adds `fields`). If one ever becomes
        // necessary, list it here by "Group.key" with its reason, rather than weakening the check.
        val exceptions = emptySet<String>()
        val missing =
            OptionCatalog.groups.flatMap { group ->
                (group.wireNames(Door.JS) + group.wireNames(Door.WASI) - group.wireNames(Door.HTTP))
                    .map { "${group.name}.$it" }
                    .filterNot { it in exceptions }
            }
        assertEquals(emptyList(), missing, "a caller moving from the JS or WASI door would get unknown-key for these")
    }
}
