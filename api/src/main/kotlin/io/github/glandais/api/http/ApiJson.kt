package io.github.glandais.api.http

import kotlinx.serialization.json.Json

/**
 * The one [Json] instance of the API, for reading and for writing.
 *
 * Strict on input — no unknown key, no lenient literal, no coercion of a wrong value into a
 * default — because a key the server silently drops is the defect class the door-parity tests
 * exist to catch. Explicit on output: `encodeDefaults` and `explicitNulls` make every field of a
 * response DTO appear, so the schema in the contract is what a client sees.
 */
val ApiJson: Json =
    Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        encodeDefaults = true
        explicitNulls = true
    }
