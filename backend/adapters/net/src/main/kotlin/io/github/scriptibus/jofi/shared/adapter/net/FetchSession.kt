// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import io.github.scriptibus.jofi.shared.domain.http.FetchResult
import io.github.scriptibus.jofi.shared.domain.http.HttpMethod
import io.github.scriptibus.jofi.shared.domain.http.OutboundRequest
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.classic.methods.HttpHead
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.core5.http.HttpHeaders
import org.apache.hc.core5.io.CloseMode
import org.apache.hc.core5.io.ModalCloseable
import org.apache.hc.core5.util.Timeout
import java.net.URI
import java.time.Clock
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/**
 * One fetch: follows redirects by hand so every hop is checked again (scheme here, addresses in the
 * [GuardedDnsResolver] on connect), up to the request's redirect limit and within one deadline.
 */
internal class FetchSession(
    private val client: CloseableHttpClient,
    private val request: OutboundRequest,
    clock: Clock,
) {
    private val deadline = TimeSource.Monotonic.markNow() + request.limits.timeout.toKotlinDuration()
    private val reader = ResponseReader(request, clock, deadline)

    fun run(): FetchResult = follow(request.uri, request.headers, redirects = 0)

    private tailrec fun follow(
        target: URI,
        headers: Map<String, String>,
        redirects: Int,
    ): FetchResult {
        val blocked = FetchTarget.blockReason(target)?.let { HopOutcome.Done(FetchResult.Blocked(it)) }
        return when (val outcome = blocked ?: exchange(FetchTarget.sanitize(target), headers)) {
            is HopOutcome.Done -> {
                outcome.result
            }

            is HopOutcome.Redirect -> {
                if (redirects == request.limits.maxRedirects) {
                    FetchResult.TooManyRedirects(redirects)
                } else {
                    follow(outcome.location, headersFor(target, outcome.location, headers), redirects + 1)
                }
            }
        }
    }

    /** Credentials and cookies the caller meant for one origin never follow a redirect elsewhere. */
    private fun headersFor(
        current: URI,
        next: URI,
        headers: Map<String, String>,
    ): Map<String, String> =
        if (FetchTarget.isSameOrigin(current, next)) {
            headers
        } else {
            headers.filterKeys { it.lowercase(Locale.ROOT) in PORTABLE_HEADERS }
        }

    private fun exchange(
        target: URI,
        headers: Map<String, String>,
    ): HopOutcome {
        if (deadline.hasPassedNow()) return HopOutcome.Done(FetchResult.Timeout)
        val httpRequest = newRequest(target, headers)
        val response = client.executeOpen(null, httpRequest, null)
        try {
            return reader.interpret(target, response)
        } finally {
            // Never drain the rest of a body we stopped reading (size limit, redirect, error page).
            (response as? ModalCloseable)?.close(CloseMode.IMMEDIATE) ?: response.close()
        }
    }

    private fun newRequest(
        target: URI,
        headers: Map<String, String>,
    ): HttpUriRequestBase {
        val httpRequest =
            when (request.method) {
                HttpMethod.GET -> HttpGet(target)
                HttpMethod.HEAD -> HttpHead(target)
            }
        headers
            .filterKeys { it.lowercase(Locale.ROOT) !in CONTROLLED_HEADERS }
            .forEach { (name, value) -> httpRequest.addHeader(name, value) }
        // At least 1 ms: HttpClient reads a zero timeout as "wait forever".
        val remaining = Timeout.of(maxOf(-deadline.elapsedNow(), 1.milliseconds).toJavaDuration())
        httpRequest.config =
            RequestConfig
                .custom()
                .setResponseTimeout(remaining)
                .setConnectionRequestTimeout(remaining)
                .build()
        return httpRequest
    }

    private companion object {
        /** Safe to send to any host after a cross-origin redirect. */
        val PORTABLE_HEADERS = setOf("accept", "accept-language")

        /** Set by the client itself: the host routing, the identifying user agent (T9) and framing. */
        val CONTROLLED_HEADERS =
            listOf(
                HttpHeaders.HOST,
                HttpHeaders.USER_AGENT,
                HttpHeaders.CONTENT_LENGTH,
                HttpHeaders.TRANSFER_ENCODING,
                HttpHeaders.CONNECTION,
            ).map { it.lowercase(Locale.ROOT) }
                .toSet()
    }
}

/** What one request/response exchange means for the redirect loop. */
internal sealed interface HopOutcome {
    data class Done(
        val result: FetchResult,
    ) : HopOutcome

    data class Redirect(
        val location: URI,
    ) : HopOutcome
}
