// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import io.github.scriptibus.jofi.shared.application.port.OutboundHttpPort
import io.github.scriptibus.jofi.shared.domain.http.BlockReason
import io.github.scriptibus.jofi.shared.domain.http.FetchResult
import io.github.scriptibus.jofi.shared.domain.http.OutboundRequest
import org.apache.hc.core5.io.CloseMode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Clock

/**
 * The SSRF-guarded [OutboundHttpPort] (threat model T1, ADR-0034). Each fetch gets its own client,
 * so the request's timeout applies to connecting too and no connection outlives the fetch.
 * Logs carry only the target host and the result kind, never headers, paths or bodies (T4).
 */
class OutboundHttpAdapter(
    private val guard: DestinationGuard,
    private val userAgent: String,
    private val clock: Clock = Clock.systemUTC(),
) : OutboundHttpPort {
    override fun fetch(request: OutboundRequest): FetchResult {
        val result =
            try {
                fetchGuarded(request)
            } catch (exception: IOException) {
                failureOf(exception)
            } catch (exception: RuntimeException) {
                // The port never throws; an unexpected client failure is reported as unreachable.
                log.warn("Outbound fetch to {} failed: {}", request.uri.host, exception.javaClass.name)
                FetchResult.Unreachable
            }
        log.debug("Outbound fetch to {}: {}", request.uri.host, result.javaClass.simpleName)
        return result
    }

    private fun fetchGuarded(request: OutboundRequest): FetchResult {
        val timeout = request.limits.timeout
        val client = GuardedHttpClients.create(guard, timeout, timeout, userAgent)
        try {
            return FetchSession(client, request, clock).run()
        } finally {
            client.close(CloseMode.IMMEDIATE)
        }
    }

    private fun failureOf(exception: IOException): FetchResult {
        val causes = generateSequence<Throwable>(exception) { it.cause }.toList()
        return when {
            causes.any { it is BlockedDestinationException } -> FetchResult.Blocked(BlockReason.ADDRESS_NOT_ALLOWED)
            causes.any { it is InterruptedIOException } -> FetchResult.Timeout
            else -> FetchResult.Unreachable
        }
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(OutboundHttpAdapter::class.java)
    }
}
