// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpMethod
import java.net.URI

/** The guarded request factory for the AI adapter: a local model endpoint works only when allowlisted. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiRequestFactoryTest {
    private val ollama = WireMockServer(wireMockConfig().dynamicPort().bindAddress("127.0.0.1")).apply { start() }
    private val chatUri = URI("http://127.0.0.1:${ollama.port()}/api/chat")

    @AfterAll
    fun stop() {
        ollama.stop()
    }

    @Test
    fun `posts to an allowlisted local endpoint`() {
        ollama.stubFor(post("/api/chat").willReturn(aResponse().withBody("""{"done":true}""")))
        val factory =
            GuardedHttpClients.aiRequestFactory(
                DestinationAllowlist.of(listOf(Destination.of("127.0.0.1", ollama.port()))),
                "Jofi/test",
            )

        val request = factory.createRequest(chatUri, HttpMethod.POST)
        request.body.write("""{"model":"llama"}""".toByteArray())
        val status = request.execute().use { it.statusCode.value() }

        status shouldBe 200
        ollama.verify(postRequestedFor(urlEqualTo("/api/chat")).withHeader("User-Agent", equalTo("Jofi/test")))
    }

    @Test
    fun `refuses a local endpoint that is not allowlisted`() {
        val factory = GuardedHttpClients.aiRequestFactory(DestinationAllowlist.NONE, "Jofi/test")

        val request = factory.createRequest(chatUri, HttpMethod.POST)

        shouldThrow<BlockedDestinationException> { request.execute() }.addressClass shouldBe AddressClass.LOOPBACK
        ollama.verify(0, anyRequestedFor(anyUrl()))
    }
}
