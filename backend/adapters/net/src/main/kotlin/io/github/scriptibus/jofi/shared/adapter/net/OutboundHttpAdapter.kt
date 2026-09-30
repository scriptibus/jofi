// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import io.github.scriptibus.jofi.shared.application.port.OutboundHttpPort
import io.github.scriptibus.jofi.shared.domain.http.BlockReason
import io.github.scriptibus.jofi.shared.domain.http.FetchResult
import io.github.scriptibus.jofi.shared.domain.http.OutboundRequest
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.core5.io.CloseMode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/**
 * The SSRF-guarded [OutboundHttpPort] (threat model T1, ADR-0034). Each fetch gets its own client,
 * so its remaining time bounds DNS, connect, TLS and reads, and no connection outlives the fetch.
 *
 * Watchdog: the fetch runs on a virtual thread while the caller waits at most the request's
 * timeout. Then the client is closed immediately (aborting sockets mid-connect or mid-read) and the
 * caller gets [FetchResult.Timeout], however slowly a server or resolver answers.
 * Logs carry only the target host and the result kind, never headers, paths or bodies (T4).
 */
class OutboundHttpAdapter(
    private val guard: DestinationGuard,
    private val userAgent: String,
    private val clock: Clock = Clock.systemUTC(),
) : OutboundHttpPort {
    override fun fetch(request: OutboundRequest): FetchResult {
        val timeout = request.limits.timeout
        val deadline = TimeSource.Monotonic.markNow() + timeout.toKotlinDuration()
        val remaining = { (-deadline.elapsedNow()).toJavaDuration() }
        val client = GuardedHttpClients.create(guard, ClientTimeouts(remaining, remaining), userAgent)
        val fetch = CompletableFuture.supplyAsync({ fetchGuarded(client, request, deadline) }, WORKERS)
        val result =
            try {
                fetch.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                FetchResult.Timeout
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                FetchResult.Timeout
            } catch (exception: ExecutionException) {
                unexpected(request, exception.cause ?: exception)
            } finally {
                client.close(CloseMode.IMMEDIATE)
                fetch.cancel(true)
            }
        log.debug("Outbound fetch to {}: {}", request.uri.host, result.javaClass.simpleName)
        return result
    }

    private fun fetchGuarded(
        client: CloseableHttpClient,
        request: OutboundRequest,
        deadline: TimeMark,
    ): FetchResult =
        try {
            FetchSession(client, request, clock, deadline).run()
        } catch (exception: IOException) {
            failureOf(exception)
        }

    private fun failureOf(exception: IOException): FetchResult {
        val causes = generateSequence<Throwable>(exception) { it.cause }.toList()
        return when {
            causes.any { it is BlockedDestinationException } -> FetchResult.Blocked(BlockReason.ADDRESS_NOT_ALLOWED)
            causes.any { it is InterruptedIOException || it is DnsTimeoutException } -> FetchResult.Timeout
            else -> FetchResult.Unreachable
        }
    }

    /** The port never throws; an unexpected client failure is reported as unreachable. */
    private fun unexpected(
        request: OutboundRequest,
        cause: Throwable,
    ): FetchResult {
        log.warn("Outbound fetch to {} failed: {}", request.uri.host, cause.javaClass.name)
        return FetchResult.Unreachable
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(OutboundHttpAdapter::class.java)
        val WORKERS: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    }
}
