// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.adapter.net.BlockedDestinationException
import io.github.scriptibus.jofi.shared.application.port.OutboundHttpPort
import io.github.scriptibus.jofi.shared.domain.http.BlockReason
import io.github.scriptibus.jofi.shared.domain.http.FetchResult
import io.github.scriptibus.jofi.shared.domain.http.OutboundRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.client.ClientHttpRequestFactory
import java.net.URI

/** The SSRF guard as wired in the app: fetches get no allowlist; the AI client only configured providers. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class OutboundHttpWiringTest(
    @param:Autowired private val outboundHttp: OutboundHttpPort,
    @param:Autowired @param:Qualifier("aiHttpRequestFactory") private val aiHttp: ClientHttpRequestFactory,
) {
    @Test
    fun `fetches of internal addresses are blocked`() {
        outboundHttp.fetch(OutboundRequest(URI("http://127.0.0.1:8080/actuator/health"))) shouldBe
            FetchResult.Blocked(BlockReason.ADDRESS_NOT_ALLOWED)
        outboundHttp.fetch(OutboundRequest(URI("file:///etc/passwd"))) shouldBe
            FetchResult.Blocked(BlockReason.SCHEME_NOT_ALLOWED)
    }

    @Test
    fun `the AI client blocks internal addresses no configured provider uses`() {
        val request = aiHttp.createRequest(URI("http://127.0.0.1:11434/api/tags"), HttpMethod.GET)

        shouldThrow<BlockedDestinationException> { request.execute() }
    }
}
