// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.setup.application.port.AiProviderPort
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.ResolvedModel
import io.github.scriptibus.jofi.shared.adapter.net.AiRequest
import io.github.scriptibus.jofi.shared.adapter.net.BlockedDestinationException
import io.github.scriptibus.jofi.shared.adapter.net.GuardedAiTransport
import io.github.scriptibus.jofi.shared.application.port.OutboundHttpPort
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.LlmMessage
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.github.scriptibus.jofi.shared.domain.http.BlockReason
import io.github.scriptibus.jofi.shared.domain.http.FetchResult
import io.github.scriptibus.jofi.shared.domain.http.OutboundRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import java.net.URI
import java.util.UUID

/** The SSRF guard as wired in the app: fetches get no allowlist; the AI transport only configured providers. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class OutboundHttpWiringTest(
    @param:Autowired private val outboundHttp: OutboundHttpPort,
    @param:Autowired private val aiTransport: GuardedAiTransport,
    @param:Autowired private val aiProvider: AiProviderPort,
) {
    @Test
    fun `fetches of internal addresses are blocked`() {
        outboundHttp.fetch(OutboundRequest(URI("http://127.0.0.1:8080/actuator/health"))) shouldBe
            FetchResult.Blocked(BlockReason.ADDRESS_NOT_ALLOWED)
        outboundHttp.fetch(OutboundRequest(URI("file:///etc/passwd"))) shouldBe
            FetchResult.Blocked(BlockReason.SCHEME_NOT_ALLOWED)
    }

    @Test
    fun `the AI transport blocks internal addresses no configured provider uses`() {
        val request = AiRequest("GET", URI("http://127.0.0.1:11434/v1/models"), emptyList(), null)

        shouldThrow<BlockedDestinationException> { aiTransport.execute(request) }
    }

    @Test
    fun `the provider adapter calls through the guarded transport`() {
        val local = URI("http://127.0.0.1:11434/v1")
        val provider =
            ProviderConfig(ProviderId(UUID.randomUUID()), "Ollama", ProviderKind.OPENAI_COMPATIBLE, null, local)
        val request = LlmRequest(AiTask.CHAT, listOf(LlmMessage.User("Hallo")))

        // No provider store yet, so the allowlist is empty and the local endpoint is blocked.
        aiProvider.complete(ResolvedModel(provider, ModelName("llama3.1")), request) shouldBe AiResult.Unavailable
    }

    @Test
    fun `the AI libraries do not log, since they would log prompts`() {
        val loggers = listOf("org.springframework.ai.openai.OpenAiChatModel", "com.openai.core", "com.anthropic.core")
        loggers.forEach { name ->
            LoggerFactory.getLogger(name).isErrorEnabled shouldBe false
        }
    }
}
