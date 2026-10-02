// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.web

import io.github.scriptibus.jofi.setup.application.ClearModelPriceUseCase
import io.github.scriptibus.jofi.setup.application.ListModelPricesUseCase
import io.github.scriptibus.jofi.setup.application.SetModelPriceUseCase
import io.github.scriptibus.jofi.setup.application.port.ModelPricePort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ModelPriceOverride
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The model price endpoints over the real use cases with mocked ports: mapping, validation and problem
 * details. Session and CSRF are the filter chain's job, tested in bootstrap (`ModelPriceFlowTest`).
 */
@WebMvcTest(ModelPriceController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(ModelPriceControllerTest.UseCases::class)
class ModelPriceControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val ports: Ports,
) {
    class Ports {
        val providers = mockk<ProviderConfigPort>()
        val prices = mockk<ModelPricePort>()
        val changelog = mockk<ChangelogPort>()
        val transactions =
            object : TransactionPort {
                override fun <T> inTransaction(
                    commitIf: (T) -> Boolean,
                    work: () -> T,
                ): T = work()
            }
    }

    @TestConfiguration
    class UseCases {
        private val clock = Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC)

        @Bean
        fun ports() = Ports()

        @Bean
        fun list(ports: Ports) = ListModelPricesUseCase(ports.providers, ports.prices)

        @Bean
        fun set(ports: Ports) =
            SetModelPriceUseCase(ports.providers, ports.prices, ports.changelog, ports.transactions, clock)

        @Bean
        fun clear(ports: Ports) =
            ClearModelPriceUseCase(ports.providers, ports.prices, ports.changelog, ports.transactions, clock)
    }

    private val json = JsonMapper.builder().build()
    private val local =
        ProviderConfig(
            ProviderId(UUID.randomUUID()),
            "Ollama",
            ProviderKind.OPENAI_COMPATIBLE,
            null,
            URI("http://ollama:11434/v1"),
        )
    private val cloud =
        ProviderConfig(ProviderId(UUID.randomUUID()), "OpenAI", ProviderKind.OPENAI, SecretId(UUID.randomUUID()))
    private val at = Instant.parse("2026-10-02T12:00:00Z")
    private val llama = ModelName("meta-llama/llama-3.1-8b")

    @BeforeEach
    fun answer() {
        clearMocks(ports.providers, ports.prices, ports.changelog)
        every { ports.providers.findById(local.id) } returns SetupStoreResult.Success(local)
        every { ports.providers.findById(cloud.id) } returns SetupStoreResult.Success(cloud)
        every { ports.prices.find(any(), any()) } returns SetupStoreResult.NotFound
        every { ports.prices.save(any()) } returns SetupStoreResult.Success(Unit)
        every { ports.prices.clear(any(), any()) } returns SetupStoreResult.Success(Unit)
        every { ports.changelog.append(any()) } returns ChangelogResult.Success(Unit)
    }

    private fun body(result: MvcTestResult): JsonNode = json.readTree(result.response.contentAsString)

    private fun put(
        provider: ProviderConfig,
        content: String,
    ) = mvc
        .put()
        .uri("/api/setup/providers/${provider.id.value}/model-prices")
        .contentType(MediaType.APPLICATION_JSON)
        .content(content)
        .exchange()

    private fun violations(result: MvcTestResult): List<Pair<String, String>> {
        result.response.status shouldBe 400
        result.response.contentType shouldBe MediaType.APPLICATION_PROBLEM_JSON_VALUE
        body(result)["type"].asString() shouldBe SetupProblems.INVALID
        val found: Iterable<JsonNode> = body(result)["violations"]
        return found.map { it["field"].asString() to it["problem"].asString() }
    }

    @Test
    fun `setting a price stores it as the user, logs it and answers it`() {
        val result =
            put(
                local,
                """{"model":"meta-llama/llama-3.1-8b","inputMicrosPerMillion":150000,""" +
                    """"outputMicrosPerMillion":600000}""",
            )

        result.response.status shouldBe 200
        body(result)["model"].asString() shouldBe "meta-llama/llama-3.1-8b"
        body(result)["inputMicrosPerMillion"].asLong() shouldBe 150_000
        body(result)["outputMicrosPerMillion"].asLong() shouldBe 600_000
        verify { ports.prices.save(ModelPriceOverride(local.id, llama, 150_000, 600_000, at)) }
        verify { ports.changelog.append(match { it.actor == Actor.User && it.entity == local.id.toEntityRef() }) }
    }

    @Test
    fun `a price of zero is accepted`() {
        put(
            local,
            """{"model":"llama3.1","inputMicrosPerMillion":0,"outputMicrosPerMillion":0}""",
        ).response.status shouldBe
            200
    }

    @Test
    fun `invalid prices and model names are field violations and store nothing`() {
        violations(
            put(local, """{"model":"llama","inputMicrosPerMillion":-1,"outputMicrosPerMillion":10000000001}"""),
        ) shouldBe
            listOf("inputMicrosPerMillion" to "OUT_OF_RANGE", "outputMicrosPerMillion" to "OUT_OF_RANGE")
        violations(put(local, """{"model":" ","inputMicrosPerMillion":1,"outputMicrosPerMillion":1}""")) shouldBe
            listOf("model" to "REQUIRED")
        violations(put(local, "{}")) shouldBe
            listOf("model" to "REQUIRED", "inputMicrosPerMillion" to "REQUIRED", "outputMicrosPerMillion" to "REQUIRED")
        verify(exactly = 0) { ports.prices.save(any()) }
    }

    @Test
    fun `a cloud provider takes no price, an unknown provider is 404`() {
        val refused = put(cloud, """{"model":"gpt","inputMicrosPerMillion":1,"outputMicrosPerMillion":1}""")
        refused.response.status shouldBe 409
        body(refused)["type"].asString() shouldBe SetupProblems.PRICE_NOT_ALLOWED

        val unknown = ProviderId(UUID.randomUUID())
        every { ports.providers.findById(unknown) } returns SetupStoreResult.NotFound
        mvc
            .get()
            .uri("/api/setup/providers/${unknown.value}/model-prices")
            .exchange()
            .response.status shouldBe 404
        verify(exactly = 0) { ports.prices.save(any()) }
    }

    @Test
    fun `a price that is not a whole number of micros is refused at its field, never rounded or coerced`() {
        listOf(
            """{"model":"llama","inputMicrosPerMillion":1.9,"outputMicrosPerMillion":1}""" to "inputMicrosPerMillion",
            """{"model":"llama","inputMicrosPerMillion":0.15,"outputMicrosPerMillion":1}""" to "inputMicrosPerMillion",
            """{"model":"llama","inputMicrosPerMillion":1,"outputMicrosPerMillion":1e3}""" to "outputMicrosPerMillion",
            """{"model":"llama","inputMicrosPerMillion":"150000","outputMicrosPerMillion":1}""" to
                "inputMicrosPerMillion",
            """{"model":"llama","inputMicrosPerMillion":1,"outputMicrosPerMillion":true}""" to "outputMicrosPerMillion",
            """{"model":"llama","inputMicrosPerMillion":1,"outputMicrosPerMillion":9223372036854775808}""" to
                "outputMicrosPerMillion",
            """{"model":5,"inputMicrosPerMillion":1,"outputMicrosPerMillion":1}""" to "model",
        ).forEach { (request, field) ->
            violations(put(local, request)) shouldBe listOf(field to "INVALID_FORMAT")
        }
        verify(exactly = 0) { ports.prices.save(any()) }
    }

    @Test
    fun `a malformed body is a plain 400`() {
        put(local, "{").response.status shouldBe 400
        verify(exactly = 0) { ports.prices.save(any()) }
    }

    @Test
    fun `a provider deleted between the check and the write is 404`() {
        every { ports.prices.save(any()) } returns SetupStoreResult.NotFound

        put(
            local,
            """{"model":"llama","inputMicrosPerMillion":1,"outputMicrosPerMillion":1}""",
        ).response.status shouldBe
            404
    }

    @Test
    fun `the list shows the provider's prices`() {
        every { ports.prices.findByProvider(local.id) } returns
            SetupStoreResult.Success(listOf(ModelPriceOverride(local.id, llama, 0, 0, at)))

        val result = mvc.get().uri("/api/setup/providers/${local.id.value}/model-prices").exchange()

        result.response.status shouldBe 200
        body(result)[0]["model"].asString() shouldBe "meta-llama/llama-3.1-8b"
        body(result)[0]["inputMicrosPerMillion"].asLong() shouldBe 0
        body(result)[0]["updatedAt"].asString() shouldBe "2026-10-02T12:00:00Z"
    }

    @Test
    fun `removing a price takes the model from the query, since names contain slashes`() {
        every { ports.prices.find(local.id, llama) } returns
            SetupStoreResult.Success(ModelPriceOverride(local.id, llama, 1, 2, at))

        val result =
            mvc
                .delete()
                .uri("/api/setup/providers/${local.id.value}/model-prices?model=meta-llama/llama-3.1-8b")
                .exchange()

        result.response.status shouldBe 204
        verify { ports.prices.clear(local.id, llama) }
        verify { ports.changelog.append(match { it.actor == Actor.User }) }
    }

    @Test
    fun `removing a model without a price is still 204 and logs nothing`() {
        mvc
            .delete()
            .uri("/api/setup/providers/${local.id.value}/model-prices?model=missing")
            .exchange()
            .response.status shouldBe 204
        verify(exactly = 0) { ports.changelog.append(any()) }
    }
}
