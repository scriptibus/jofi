// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.util.Timeout
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import java.time.Duration

/**
 * Builds every Apache HttpClient Jofi uses, always with the [GuardedDnsResolver]. Redirects,
 * cookies, automatic retries and auth caching are off, and system proxy settings are ignored
 * (no `useSystemProperties()`), so nothing is sent anywhere the guard has not checked.
 */
object GuardedHttpClients {
    /** Generous: a local model may take minutes to answer a long prompt. */
    val AI_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
    val AI_READ_TIMEOUT: Duration = Duration.ofMinutes(5)

    fun create(
        guard: DestinationGuard,
        connectTimeout: Duration,
        readTimeout: Duration,
        userAgent: String,
    ): CloseableHttpClient {
        val connectionConfig =
            ConnectionConfig
                .custom()
                .setConnectTimeout(Timeout.of(connectTimeout))
                .setSocketTimeout(Timeout.of(readTimeout))
                .build()
        val connectionManager =
            PoolingHttpClientConnectionManagerBuilder
                .create()
                .setDnsResolver(GuardedDnsResolver(guard))
                .setDefaultConnectionConfig(connectionConfig)
                .build()
        return HttpClients
            .custom()
            .setConnectionManager(connectionManager)
            .disableRedirectHandling()
            .disableCookieManagement()
            .disableAutomaticRetries()
            .disableAuthCaching()
            .setUserAgent(userAgent)
            .build()
    }

    /**
     * The request factory for the AI provider clients (ADR-0034): the same guard, with the
     * allowlist of configured AI endpoints. Redirects are not followed; an AI API has no reason to
     * redirect. Spring closes the client when the bean is destroyed.
     */
    fun aiRequestFactory(
        allowlist: DestinationAllowlist,
        userAgent: String,
    ): ClientHttpRequestFactory =
        HttpComponentsClientHttpRequestFactory(
            create(DestinationGuard(allowlist), AI_CONNECT_TIMEOUT, AI_READ_TIMEOUT, userAgent),
        )
}
