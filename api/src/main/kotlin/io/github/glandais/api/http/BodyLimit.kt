package io.github.glandais.api.http

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
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
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > max) {
        throw tooLarge(max)
    }
    val channel = receiveChannel()
    val out = ByteArrayOutputStream(declared?.toInt()?.coerceAtMost(DEFAULT_BUFFER) ?: DEFAULT_BUFFER)
    val buffer = ByteArray(DEFAULT_BUFFER)
    var total = 0L
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read == -1) break
        total += read
        if (total > max) {
            throw tooLarge(max)
        }
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private const val DEFAULT_BUFFER: Int = 8 * 1024

private fun tooLarge(max: Long): ApiProblem = ApiProblem(ProblemKind.PAYLOAD_TOO_LARGE, "Request body exceeds the limit of $max bytes")
