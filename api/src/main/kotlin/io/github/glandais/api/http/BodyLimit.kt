package io.github.glandais.api.http

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream

/**
 * Reads the request body, at most [max] bytes.
 *
 * A declared `Content-Length` above [max] is refused before a byte is read. A body without one
 * (chunked) is read with a cap of `max + 1`, so it cannot get past the limit by not announcing
 * its size: reaching the extra byte is the proof it is too large.
 *
 * @throws ApiProblem `payload-too-large`
 */
suspend fun ApplicationCall.receiveBoundedBytes(max: Long): ByteArray {
    requireDeclaredLengthAtMost(max)
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    return receiveChannel().readBounded(max, initialCapacity = declared?.toInt()?.coerceAtMost(DEFAULT_BUFFER)) {
        throw payloadTooLarge(max)
    }
}

/**
 * Refuses a request whose declared `Content-Length` is above [max], before a byte is read.
 *
 * @throws ApiProblem `payload-too-large`
 */
fun ApplicationCall.requireDeclaredLengthAtMost(max: Long) {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > max) {
        throw payloadTooLarge(max)
    }
}

/**
 * Reads this channel to its end, calling [onExceeded] — which must throw — as soon as more than
 * [max] bytes have arrived.
 */
suspend fun ByteReadChannel.readBounded(
    max: Long,
    initialCapacity: Int? = null,
    onExceeded: () -> Nothing,
): ByteArray {
    val out = ByteArrayOutputStream(initialCapacity ?: DEFAULT_BUFFER)
    val buffer = ByteArray(DEFAULT_BUFFER)
    var total = 0L
    while (true) {
        val read = readAvailable(buffer, 0, buffer.size)
        if (read == -1) break
        total += read
        if (total > max) {
            onExceeded()
        }
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private const val DEFAULT_BUFFER: Int = 8 * 1024

/**
 * Whether [e] is Ktor's own report of a body limit — `formFieldLimit` in the multipart parser
 * ("Limit of N bytes exceeded while searching for …", "Multipart content length exceeds limit …").
 * Ktor raises it as a bare [java.io.IOException] from the parser's coroutine, which cancels the
 * call: no `catch` in a handler sees it, only `StatusPages` does.
 */
fun isBodyLimitExceeded(e: java.io.IOException): Boolean {
    val message = e.message ?: return false
    return message.contains("limit", ignoreCase = true) && message.contains("exceed", ignoreCase = true)
}

/** The `payload-too-large` problem for a limit of [max] bytes. */
fun payloadTooLarge(max: Long): ApiProblem = ApiProblem(ProblemKind.PAYLOAD_TOO_LARGE, "Request body exceeds the limit of $max bytes")
