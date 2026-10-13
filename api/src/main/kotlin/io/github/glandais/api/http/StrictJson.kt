package io.github.glandais.api.http

import io.github.glandais.api.apiServices
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Decodes [text] with [serializer], reporting **every** unknown key before anything else.
 *
 * Plain `Json { ignoreUnknownKeys = false }` stops at the first unknown key, and only says its
 * name. Here the text is parsed to a [JsonElement] first (malformed → `invalid-body`), then walked
 * along the serializer's descriptor; each key no class declares is collected as a JSON pointer
 * (`/options/simplifyTolerance`). Only when there is none is the element decoded with [ApiJson],
 * whose type errors are `invalid-body` too. It is the HTTP equivalent of "validate the keys
 * outside `GlobalScope.promise`": the 400 leaves before the compute gate is taken.
 *
 * @throws ApiProblem `invalid-body` or `unknown-key`
 */
fun <T> decodeStrict(
    serializer: KSerializer<T>,
    text: String,
): T {
    val element =
        try {
            ApiJson.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw ApiProblem(ProblemKind.INVALID_BODY, "Malformed JSON: ${e.message}", cause = e)
        }
    val unknown = mutableListOf<String>()
    collectUnknownKeys(serializer.descriptor, element, "", unknown)
    if (unknown.isNotEmpty()) {
        throw ApiProblem(
            ProblemKind.UNKNOWN_KEY,
            "Unknown key(s) in request body: ${unknown.joinToString(", ")}",
            unknownKeys = unknown,
        )
    }
    return try {
        ApiJson.decodeFromJsonElement(serializer, element)
    } catch (e: SerializationException) {
        throw ApiProblem(ProblemKind.INVALID_BODY, e.message, cause = e)
    } catch (e: IllegalArgumentException) {
        // An `init { require(…) }` of a DTO: a value out of domain, not a malformed body.
        throw ApiProblem(ProblemKind.INVALID_OPTION, e.message, cause = e)
    }
}

/** Reads the body (bounded by `maxBodyBytes`) as UTF-8 and [decodeStrict]s it. */
suspend fun <T> ApplicationCall.receiveStrict(serializer: KSerializer<T>): T {
    val bytes = receiveBoundedBytes(apiServices.config.maxBodyBytes)
    return decodeStrict(serializer, bytes.decodeToString())
}

/**
 * [receiveStrict] for a route whose body is JSON: any other `Content-Type` is
 * `unsupported-media-type`, before a byte is read.
 *
 * @throws ApiProblem `unsupported-media-type`, `payload-too-large`, `invalid-body`, `unknown-key`
 */
suspend fun <T> ApplicationCall.receiveJson(serializer: KSerializer<T>): T {
    val contentType = request.contentType().withoutParameters()
    if (!contentType.match(ContentType.Application.Json)) {
        throw ApiProblem(
            ProblemKind.UNSUPPORTED_MEDIA_TYPE,
            "Content-Type '$contentType' is not accepted: send ${ContentType.Application.Json}",
        )
    }
    return receiveStrict(serializer)
}

/**
 * Walks [element] along [descriptor]. Type mismatches are not reported here — decoding reports
 * them — so a wrong shape simply stops the walk.
 */
@OptIn(ExperimentalSerializationApi::class)
private fun collectUnknownKeys(
    descriptor: SerialDescriptor,
    element: JsonElement,
    pointer: String,
    unknown: MutableList<String>,
) {
    if (descriptor.isInline) {
        collectUnknownKeys(descriptor.getElementDescriptor(0), element, pointer, unknown)
        return
    }
    when (descriptor.kind) {
        StructureKind.CLASS, StructureKind.OBJECT -> {
            if (element !is JsonObject) return
            for ((key, value) in element) {
                val index = descriptor.getElementIndex(key)
                val child = "$pointer/${escapePointer(key)}"
                if (index == CompositeDecoder.UNKNOWN_NAME) {
                    unknown += child
                } else {
                    collectUnknownKeys(descriptor.getElementDescriptor(index), value, child, unknown)
                }
            }
        }

        StructureKind.LIST -> {
            if (element !is JsonArray) return
            val itemDescriptor = descriptor.getElementDescriptor(0)
            element.forEachIndexed { i, item -> collectUnknownKeys(itemDescriptor, item, "$pointer/$i", unknown) }
        }

        StructureKind.MAP -> {
            if (element !is JsonObject) return
            val valueDescriptor = descriptor.getElementDescriptor(1)
            for ((key, value) in element) {
                collectUnknownKeys(valueDescriptor, value, "$pointer/${escapePointer(key)}", unknown)
            }
        }

        else -> Unit
    }
}

/** RFC 6901: `~` → `~0`, `/` → `~1`. */
private fun escapePointer(key: String): String = key.replace("~", "~0").replace("/", "~1")
