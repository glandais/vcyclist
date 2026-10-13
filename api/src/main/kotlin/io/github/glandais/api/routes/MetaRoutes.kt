package io.github.glandais.api.routes

import io.github.glandais.api.dto.CatalogDto
import io.github.glandais.api.dto.FieldDefinitionDto
import io.github.glandais.api.dto.HealthDto
import io.github.glandais.api.dto.VersionDto
import io.github.glandais.api.http.ProblemKind
import io.github.glandais.api.http.requireOnlyQuery
import io.github.glandais.api.openapi.problemResponses
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe

private const val TAG = "meta"

/** `GET /health`, `/version`, `/catalog`, `/fields` — what a client reads before calling anything. */
fun Route.metaRoutes() {
    get("health") {
        call.requireOnlyQuery()
        call.respond(HealthDto(status = "UP"))
    }.describe {
        operationId = "getHealth"
        summary = "Liveness probe"
        description = "Answers `{\"status\":\"UP\"}` while the server accepts requests. Computes nothing."
        tag(TAG)
        responses {
            HttpStatusCode.OK {
                description = "The server is up"
                schema = jsonSchema<HealthDto>()
            }
            problemResponses(ProblemKind.UNKNOWN_KEY)
        }
    }

    get("version") {
        call.requireOnlyQuery()
        call.respond(VersionDto.current())
    }.describe {
        operationId = "getVersion"
        summary = "Contract and engine versions"
        description =
            "`apiVersion` is the version of this contract, which changes only when the contract does; " +
            "`engineVersion` is the version of the vcyclist engine the server was built with."
        tag(TAG)
        responses {
            HttpStatusCode.OK {
                description = "The versions"
                schema = jsonSchema<VersionDto>()
            }
            problemResponses(ProblemKind.UNKNOWN_KEY)
        }
    }

    get("catalog") {
        call.requireOnlyQuery()
        call.respond(CatalogDto.current())
    }.describe {
        operationId = "getCatalog"
        summary = "Wire values of every enumeration"
        description =
            "The accepted spellings of every enumerated option — power models, road conditions, " +
            "racing-line corridors, elevation-gain presets, GPX power sources, DEM sources — and the " +
            "corner kinds and output formats the API emits. Read from the engine, never restated."
        tag(TAG)
        responses {
            HttpStatusCode.OK {
                description = "The catalogue"
                schema = jsonSchema<CatalogDto>()
            }
            problemResponses(ProblemKind.UNKNOWN_KEY)
        }
    }

    get("fields") {
        call.requireOnlyQuery()
        call.respond(FieldDefinitionDto.all())
    }.describe {
        operationId = "listFields"
        summary = "Per-point fields of a track"
        description =
            "Every per-point field a track carries, in ordinal order, with its unit and category. " +
            "`prop` is the name the CSV and JSON-columns exports accept in their `fields` option."
        tag(TAG)
        responses {
            HttpStatusCode.OK {
                description = "The field definitions"
                schema = jsonSchema<List<FieldDefinitionDto>>()
            }
            problemResponses(ProblemKind.UNKNOWN_KEY)
        }
    }
}
