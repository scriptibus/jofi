// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.config.TlsConfig
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.util.Timeout
import java.time.Duration

/**
 * Timeouts evaluated for every new connection, so a fetch can pass its remaining time: [connect]
 * bounds DNS, the TCP connect and the TLS handshake, [read] each socket read.
 */
class ClientTimeouts(
    val connect: () -> Duration,
    val read: () -> Duration,
)

/**
 * Builds every Apache HttpClient Jofi uses, always with the [GuardedDnsResolver]. Redirects,
 * cookies, automatic retries and auth caching are off, and system proxy settings are ignored
 * (no `useSystemProperties()`), so nothing is sent anywhere the guard has not checked.
 */
object GuardedHttpClients {
    /** Generous: a local model may take minutes to answer a long prompt. */
    val AI_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
    val AI_READ_TIMEOUT: Duration = Duration.ofMinutes(5)

    /** Idle AI connections close soon, so a removed provider's pooled connection does not linger. */
    val AI_MAX_IDLE: Duration = Duration.ofSeconds(30)

    /**
     * How long an AI call waits for a pooled connection (Apache's default is 3 minutes). A full pool
     * means calls pile up; failing fast returns `Unavailable` instead of hanging the caller.
     */
    val AI_CONNECTION_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)

    /** Chat, scanner and embedding calls often share one provider (route); Apache's default is 5. */
    const val AI_MAX_CONNECTIONS_PER_ROUTE = 20
    const val AI_MAX_CONNECTIONS = 50

    fun create(
        guard: DestinationGuard,
        timeouts: ClientTimeouts,
        userAgent: String,
    ): CloseableHttpClient = builder(guard, timeouts, userAgent).build()

    /**
     * The client for the AI providers (ADR-0034, ADR-0039; used by [GuardedAiTransport]): the same
     * guard, with the allowlist of configured AI endpoints and the generous AI timeouts. Redirects
     * are not followed; an AI API has no reason to redirect. Response sizes are not capped
     * (streamed completions).
     */
    internal fun aiClientBuilder(
        allowlist: DestinationAllowlist,
        userAgent: String,
    ): HttpClientBuilder {
        val timeouts = ClientTimeouts(connect = { AI_CONNECT_TIMEOUT }, read = { AI_READ_TIMEOUT })
        val pool = PoolLimits(AI_MAX_CONNECTIONS_PER_ROUTE, AI_MAX_CONNECTIONS)
        val waitForConnection = timeoutOf(AI_CONNECTION_REQUEST_TIMEOUT)
        return builder(DestinationGuard(allowlist), timeouts, userAgent, pool)
            .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(waitForConnection).build())
    }

    /** Connection pool sizes; the defaults are Apache HttpClient's. */
    class PoolLimits(
        val perRoute: Int = DEFAULT_PER_ROUTE,
        val total: Int = DEFAULT_TOTAL,
    ) {
        private companion object {
            const val DEFAULT_PER_ROUTE = 5
            const val DEFAULT_TOTAL = 25
        }
    }

    private fun builder(
        guard: DestinationGuard,
        timeouts: ClientTimeouts,
        userAgent: String,
        pool: PoolLimits = PoolLimits(),
    ): HttpClientBuilder {
        val connectionManager =
            PoolingHttpClientConnectionManagerBuilder
                .create()
                .setMaxConnPerRoute(pool.perRoute)
                .setMaxConnTotal(pool.total)
                .setDnsResolver(GuardedDnsResolver(guard, timeouts.connect))
                .setConnectionConfigResolver { _ ->
                    ConnectionConfig
                        .custom()
                        .setConnectTimeout(timeoutOf(timeouts.connect()))
                        .setSocketTimeout(timeoutOf(timeouts.read()))
                        .build()
                }.setTlsConfigResolver { _ ->
                    TlsConfig.custom().setHandshakeTimeout(timeoutOf(timeouts.connect())).build()
                }.build()
        return HttpClients
            .custom()
            .setConnectionManager(connectionManager)
            .disableRedirectHandling()
            .disableCookieManagement()
            .disableAutomaticRetries()
            .disableAuthCaching()
            .setUserAgent(userAgent)
    }

    /** At least 1 ms: HttpClient reads a zero timeout as "wait forever". */
    fun timeoutOf(duration: Duration): Timeout = Timeout.of(maxOf(duration, MIN_TIMEOUT))

    private val MIN_TIMEOUT: Duration = Duration.ofMillis(1)
}
