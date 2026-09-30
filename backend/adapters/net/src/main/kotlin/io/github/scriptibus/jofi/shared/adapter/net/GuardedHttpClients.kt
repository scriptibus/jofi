// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.TlsConfig
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.util.TimeValue
import org.apache.hc.core5.util.Timeout
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
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

    fun create(
        guard: DestinationGuard,
        timeouts: ClientTimeouts,
        userAgent: String,
    ): CloseableHttpClient = builder(guard, timeouts, userAgent).build()

    /**
     * The request factory for the AI provider clients (ADR-0034): the same guard, with the
     * allowlist of configured AI endpoints. Redirects are not followed; an AI API has no reason to
     * redirect. Response sizes are not capped (streamed completions). Spring closes the client when
     * the bean is destroyed.
     */
    fun aiRequestFactory(
        allowlist: DestinationAllowlist,
        userAgent: String,
    ): ClientHttpRequestFactory {
        val timeouts = ClientTimeouts(connect = { AI_CONNECT_TIMEOUT }, read = { AI_READ_TIMEOUT })
        val client =
            builder(DestinationGuard(allowlist), timeouts, userAgent)
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.of(AI_MAX_IDLE))
                .build()
        return HttpComponentsClientHttpRequestFactory(client)
    }

    private fun builder(
        guard: DestinationGuard,
        timeouts: ClientTimeouts,
        userAgent: String,
    ): HttpClientBuilder {
        val connectionManager =
            PoolingHttpClientConnectionManagerBuilder
                .create()
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
