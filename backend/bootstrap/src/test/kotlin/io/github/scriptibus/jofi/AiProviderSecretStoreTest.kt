// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.github.scriptibus.jofi.setup.application.port.AiProviderPort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.ResolvedModel
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.application.port.SecretStorePort
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.LlmMessage
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import io.github.scriptibus.jofi.shared.domain.secret.SecretResult
import io.github.scriptibus.jofi.shared.domain.secret.SecretValue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.net.URI
import java.util.UUID

/**
 * The wired AI provider adapter reads the key through the real secret store (Tink over PostgreSQL)
 * and sends it to the configured endpoint, which the provider store also puts on the allowlist.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class, AiProviderSecretStoreTest.LocalProvider::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiProviderSecretStoreTest(
    @param:Autowired private val aiProvider: AiProviderPort,
    @param:Autowired private val secrets: SecretStorePort,
) {
    @AfterAll
    fun stop() {
        LOCAL_MODEL.stop()
    }

    @Test
    fun `a key stored through the secret store reaches the provider`() {
        LOCAL_MODEL.stubFor(
            post(
                "/v1/chat/completions",
            ).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(ANSWER)),
        )
        secrets.put(KEY_ID, SecretValue(KEY)) shouldBe SecretResult.Success(Unit)

        val result = aiProvider.complete(ResolvedModel(PROVIDER, ModelName("llama3.1")), request())

        result.shouldBeInstanceOf<AiResult.Success<*>>()
        LOCAL_MODEL.verify(
            postRequestedFor(urlEqualTo("/v1/chat/completions")).withHeader("Authorization", equalTo("Bearer $KEY")),
        )
    }

    @Test
    fun `a key missing from the secret store fails before any request`() {
        val unknownKey = PROVIDER.copy(apiKey = SecretId(UUID.randomUUID()))
        LOCAL_MODEL.resetRequests()

        aiProvider.complete(ResolvedModel(unknownKey, ModelName("llama3.1")), request()) shouldBe
            AiResult.AuthenticationFailed
        LOCAL_MODEL.verify(0, anyRequestedFor(anyUrl()))
    }

    private fun request() = LlmRequest(AiTask.CHAT, listOf(LlmMessage.User("Hallo")))

    /** One configured OpenAI-compatible provider on loopback, as the provider store would list it. */
    @TestConfiguration(proxyBeanMethods = false)
    class LocalProvider {
        @Bean
        fun providerConfigPort(): ProviderConfigPort =
            object : ProviderConfigPort {
                override fun findAll(): SetupStoreResult<List<ProviderConfig>> =
                    SetupStoreResult.Success(listOf(PROVIDER))

                override fun findById(id: ProviderId): SetupStoreResult<ProviderConfig> =
                    SetupStoreResult.Success(PROVIDER)

                override fun save(config: ProviderConfig): SetupStoreResult<Unit> =
                    SetupStoreResult.StorageFailure("read-only")

                override fun delete(id: ProviderId): SetupStoreResult<Unit> =
                    SetupStoreResult.StorageFailure("read-only")
            }
    }

    private companion object {
        val LOCAL_MODEL: WireMockServer =
            WireMockServer(
                wireMockConfig().dynamicPort().bindAddress("127.0.0.1"),
            ).apply {
                start()
            }
        val KEY_ID = SecretId(UUID.randomUUID())
        const val KEY = "local-model-key"
        val PROVIDER =
            ProviderConfig(
                ProviderId(UUID.randomUUID()),
                "Local model",
                ProviderKind.OPENAI_COMPATIBLE,
                KEY_ID,
                URI("http://127.0.0.1:${LOCAL_MODEL.port()}/v1"),
            )
        const val ANSWER =
            """{"id":"c","object":"chat.completion","created":1,"model":"llama3.1",""" +
                """"choices":[{"index":0,"message":{"role":"assistant","content":"Hallo"},"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}"""
    }
}
