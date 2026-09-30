// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The exchanges of one AI call. Closing the scope cancels requests still waiting for headers and
 * aborts responses still open, right at the transport. The SDKs close a cancelled stream through a
 * `BufferedReader` that waits for the read in progress, so a stalled provider would otherwise keep
 * the connection until the read timeout; and the Anthropic SDK does not pass cancellation on to a
 * pending request at all.
 */
internal class ExchangeScope : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val pending: MutableSet<CompletableFuture<AiResponse>> = ConcurrentHashMap.newKeySet()
    private val open: MutableSet<AiResponse> = ConcurrentHashMap.newKeySet()

    fun track(response: AiResponse): AiResponse {
        open += response
        if (closed.get()) response.close()
        return response
    }

    fun track(request: CompletableFuture<AiResponse>): CompletableFuture<AiResponse> {
        pending += request
        request.whenComplete { response, _ ->
            pending -= request
            response?.let(::track)
        }
        if (closed.get()) request.cancel(true)
        return request
    }

    fun forget(response: AiResponse) {
        open -= response
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.forEach { it.cancel(true) }
        open.forEach(AiResponse::close)
    }
}
