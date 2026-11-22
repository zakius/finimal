package finimal.core

import java.util.concurrent.CancellationException

/** Like the standard library's catch-all, but a cancelled coroutine stays cancelled. */
inline fun <R> catching(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
