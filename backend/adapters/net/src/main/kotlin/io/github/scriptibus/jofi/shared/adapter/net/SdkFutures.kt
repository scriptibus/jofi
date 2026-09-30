// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * The SDK bridges' async path: maps the transport's future to the SDK's response type and failure,
 * and forwards cancellation back. Cancelling a dependent future does not cancel its source, so
 * without this a cancelled SDK call would keep the request running and leak the late response.
 */
internal object SdkFutures {
    fun <T> map(
        source: CompletableFuture<AiResponse>,
        whenDone: () -> Unit,
        ioFailure: (Throwable) -> RuntimeException,
        toSdk: (AiResponse) -> T,
    ): CompletableFuture<T> {
        val mapped =
            source.handle { response, error ->
                whenDone()
                if (error != null) throw CompletionException(ioFailure(error.cause ?: error))
                toSdk(response)
            }
        mapped.whenComplete { _, error ->
            if (error is CancellationException) {
                source.cancel(true)
                // A response that arrived just before the cancellation is aborted too.
                source.thenAccept(AiResponse::close)
            }
        }
        return mapped
    }
}
