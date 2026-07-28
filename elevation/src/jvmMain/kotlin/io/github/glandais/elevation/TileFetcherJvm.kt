/**
 * Java-callable form of the tile transport split by task g21 (task g22 for the blocking shape,
 * task g27 for the default argument).
 *
 * All three functions are `suspend` in `commonMain` — they do network I/O, or decode through an
 * asynchronous browser API on the other targets — so Java needs the same bridges as
 * `ElevationProviderJvm`, and the same rules apply: `…Blocking` parks the calling thread, `…Async`
 * hands back a `CompletableFuture` whose cancellation cancels the work.
 *
 * This is the natural place for a caller-owned cache to plug in: fetch the bytes your way, decode
 * them with [decodeTileBytesBlocking]. See `elevation/README.md`.
 */
@file:JvmName("TileFetcherJvm")

package io.github.glandais.elevation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

fun fetchTileBytesBlocking(url: String): ByteArray = runBlocking { fetchTileBytes(url) }

@JvmOverloads
fun decodeTileBytesBlocking(
    bytes: ByteArray,
    sourceUrl: String = "",
): RawTile = runBlocking { decodeTileBytes(bytes, sourceUrl) }

fun fetchAndDecodeTileBlocking(url: String): RawTile = runBlocking { fetchAndDecodeTile(url) }

@JvmOverloads
fun fetchTileBytesAsync(
    url: String,
    executor: Executor? = null,
): CompletableFuture<ByteArray> = jvmFuture(executor) { fetchTileBytes(url) }

@JvmOverloads
fun decodeTileBytesAsync(
    bytes: ByteArray,
    sourceUrl: String = "",
    executor: Executor? = null,
): CompletableFuture<RawTile> = jvmFuture(executor) { decodeTileBytes(bytes, sourceUrl) }

@JvmOverloads
fun fetchAndDecodeTileAsync(
    url: String,
    executor: Executor? = null,
): CompletableFuture<RawTile> = jvmFuture(executor) { fetchAndDecodeTile(url) }

/** See the note on `ElevationProviderJvm.jvmFuture` — deliberately duplicated per file. */
private fun <T> jvmFuture(
    executor: Executor?,
    block: suspend CoroutineScope.() -> T,
): CompletableFuture<T> {
    val dispatcher = executor?.asCoroutineDispatcher() ?: Dispatchers.IO
    return CoroutineScope(dispatcher + SupervisorJob()).future(block = block)
}
