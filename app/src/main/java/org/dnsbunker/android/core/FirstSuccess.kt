package org.dnsbunker.android.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

suspend fun <T> firstSuccess(attempts: List<suspend () -> T>, staggerMillis: Long = 200): T = supervisorScope {
    require(attempts.isNotEmpty())
    val results=Channel<Result<T>>(attempts.size)
    val jobs=attempts.mapIndexed { index, attempt -> launch {
        delay(index*staggerMillis)
        val result=try {Result.success(attempt())} catch(c:CancellationException){if(!currentCoroutineContext().isActive)throw c else Result.failure(c)} catch(e:Throwable){Result.failure(e)}
        results.send(result)
    }}
    try {
        var last:Throwable?=null
        repeat(attempts.size) {
            val result=results.receive()
            if(result.isSuccess)return@supervisorScope result.getOrThrow()
            last=result.exceptionOrNull()
        }
        throw last ?: java.io.IOException("No address available")
    } finally {jobs.forEach {it.cancel()}}
}
