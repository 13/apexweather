package it.apexweather.data

import kotlin.coroutines.cancellation.CancellationException

/**
 * `runCatching` for code that suspends: a failure becomes a `Result`, but cancellation is rethrown.
 *
 * Plain `runCatching` catches `CancellationException` too, so a fetch loop whose caller has gone
 * away — the reader left the map tab — would carry on downloading every remaining tile instead of
 * stopping.
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
