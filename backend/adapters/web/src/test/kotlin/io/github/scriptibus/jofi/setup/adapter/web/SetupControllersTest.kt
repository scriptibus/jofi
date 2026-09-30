// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.web

import io.github.scriptibus.jofi.setup.application.AssignTaskModelUseCase
import io.github.scriptibus.jofi.setup.application.CorrectModelCapabilitiesUseCase
import io.github.scriptibus.jofi.setup.application.CreateProviderUseCase
import io.github.scriptibus.jofi.setup.application.DeleteProviderUseCase
import io.github.scriptibus.jofi.setup.application.ListProviderModelsUseCase
import io.github.scriptibus.jofi.setup.application.ListProvidersUseCase
import io.github.scriptibus.jofi.setup.application.ListTaskAssignmentsUseCase
import io.github.scriptibus.jofi.setup.application.RefreshProviderModelsUseCase
import io.github.scriptibus.jofi.setup.application.UpdateProviderUseCase
import io.github.scriptibus.jofi.setup.application.port.ModelAssignmentPort
import io.github.scriptibus.jofi.setup.application.port.ModelCapabilityPort
import io.github.scriptibus.jofi.setup.application.port.ModelCatalogPort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.domain.Capability
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.ModelCapabilities
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.ConfirmationStorePort
import io.github.scriptibus.jofi.shared.application.port.SecretStorePort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import io.github.scriptibus.jofi.shared.domain.secret.SecretResult
import io.github.scriptibus.jofi.shared.domain.secret.SecretValue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.assertj.MockMvcTester
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The setup endpoints over the real use cases with mocked ports: mapping, problem details and the
 * two-step delete. Security (session, CSRF) is the filter chain's job, tested in bootstrap.
 */
@WebMvcTest(
    AiProviderController::class,
    TaskAssignmentController::class,
    properties = ["spring.mvc.problemdetails.enabled=true"],
)
@AutoConfigureMockMvc(addFilters = false)
@Import(SetupControllersTest.UseCases::class)
class SetupControllersTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val ports: Ports,
) {
    /** The mocked ports behind the real use cases. */
    class Ports {
        val providers = mockk<ProviderConfigPort>()
        val assignments = mockk<ModelAssignmentPort>()
        val profiles = mockk<ModelCapabilityPort>()
        val catalog = mockk<ModelCatalogPort>()
        val secrets = mockk<SecretStorePort>()
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
        private val clock = Clock.systemUTC()

        @Bean
        fun ports() = Ports()

        @Bean
        fun confirmation() = ConfirmActionUseCase(MapStore(), clock, Duration.ofMinutes(5))

        @Bean
        fun listProviders(ports: Ports) = ListProvidersUseCase(ports.providers)

        @Bean
        fun createProvider(ports: Ports) =
            CreateProviderUseCase(ports.providers, ports.secrets, ports.changelog, ports.transactions, clock)

        @Bean
        fun updateProvider(ports: Ports) =
            UpdateProviderUseCase(ports.providers, ports.secrets, ports.changelog, ports.transactions, clock)

        @Bean
        fun deleteProvider(
            ports: Ports,
            confirmation: ConfirmActionUseCase,
        ) = DeleteProviderUseCase(
            ports.providers,
            ports.assignments,
            ports.secrets,
            confirmation,
            ports.changelog,
            ports.transactions,
            clock,
        )

        @Bean
        fun refresh(ports: Ports) =
            RefreshProviderModelsUseCase(
                ports.providers,
                ports.catalog,
                ports.profiles,
                ports.changelog,
                ports.transactions,
                clock,
            )

        @Bean
        fun listModels(ports: Ports) = ListProviderModelsUseCase(ports.providers, ports.profiles)

        @Bean
        fun correct(ports: Ports) =
            CorrectModelCapabilitiesUseCase(ports.providers, ports.profiles, ports.changelog, ports.transactions, clock)

        @Bean
        fun listAssignments(ports: Ports) =
            ListTaskAssignmentsUseCase(ports.assignments, ports.providers, ports.profiles, ports.catalog)

        @Bean
        fun assign(ports: Ports) =
            AssignTaskModelUseCase(
                ports.providers,
                ports.assignments,
                ports.profiles,
                ports.catalog,
                ports.changelog,
                ports.transactions,
                clock,
            )
    }

    private class MapStore : ConfirmationStorePort {
        private val pending = mutableMapOf<String, PendingConfirmation>()

        override fun issue(
            pending: PendingConfirmation,
            now: Instant,
        ) = ConfirmationToken(UUID.randomUUID().toString()).also { this.pending[it.value] = pending }

        override fun redeem(token: ConfirmationToken) = pending.remove(token.value)
    }

    private val json = JsonMapper.builder().build()
    private val keyId = SecretId(UUID.fromString("00000000-0000-0000-0000-0000000000cc"))
    private val openAi =
        ProviderConfig(
            ProviderId(UUID.fromString("00000000-0000-0000-0000-00000000000a")),
            "OpenAI",
            ProviderKind.OPENAI,
            keyId,
        )
    private val ollama =
        ProviderConfig(
            ProviderId(UUID.fromString("00000000-0000-0000-0000-00000000000b")),
            "Ollama",
            ProviderKind.OPENAI_COMPATIBLE,
            null,
            URI("http://localhost:11434/v1"),
        )

    private val router =
        ProviderConfig(
            ProviderId(UUID.fromString("00000000-0000-0000-0000-00000000000d")),
            "OpenRouter",
            ProviderKind.OPENAI_COMPATIBLE,
            keyId,
            URI("https://openrouter.ai/api/v1"),
        )

    @BeforeEach
    fun answer() {
        clearMocks(ports.providers, ports.assignments, ports.profiles, ports.catalog, ports.secrets, ports.changelog)
        every { ports.providers.findAll() } returns SetupStoreResult.Success(listOf(openAi, ollama))
        every { ports.providers.findById(any()) } returns SetupStoreResult.NotFound
        every { ports.providers.findById(openAi.id) } returns SetupStoreResult.Success(openAi)
        every { ports.providers.findById(ollama.id) } returns SetupStoreResult.Success(ollama)
        every { ports.providers.save(any()) } returns SetupStoreResult.Success(Unit)
        every { ports.providers.update(any()) } returns SetupStoreResult.Success(Unit)
        every { ports.providers.findById(router.id) } returns SetupStoreResult.Success(router)
        every { ports.providers.delete(any(), any()) } returns SetupStoreResult.Success(Unit)
        every { ports.assignments.findAll() } returns SetupStoreResult.Success(emptyList())
        every { ports.assignments.findByTask(any()) } returns SetupStoreResult.NotFound
        every { ports.assignments.save(any()) } returns SetupStoreResult.Success(Unit)
        every { ports.profiles.find(any(), any()) } returns SetupStoreResult.NotFound
        every { ports.profiles.findByProvider(any()) } returns SetupStoreResult.Success(emptyList())
        every { ports.profiles.save(any()) } returns SetupStoreResult.Success(Unit)
        every { ports.catalog.knownCapabilities(any(), any()) } returns ModelCapabilities.NONE
        every { ports.secrets.put(any(), any()) } returns SecretResult.Success(Unit)
        every { ports.secrets.delete(any()) } returns SecretResult.Success(Unit)
        every { ports.changelog.append(any()) } returns ChangelogResult.Success(Unit)
    }

    // JsonNode has a member `map` of its own, so the elements go through Iterable explicitly.
    private fun elements(body: String): List<JsonNode> {
        val array: Iterable<JsonNode> = json.readTree(body)
        return array.toList()
    }

    private fun MockMvcTester.MockMvcRequestBuilder.json(body: String) =
        contentType(MediaType.APPLICATION_JSON).content(body)

    @Test
    fun `a new provider answers 201 with apiKeySet, and the key never comes back`() {
        val result =
            mvc
                .post()
                .uri("/api/setup/providers")
                .json("""{"kind":"ANTHROPIC","displayName":"Claude","apiKey":"sk-ant-secret"}""")
                .exchange()

        result.response.status shouldBe 201
        val body = json.readTree(result.response.contentAsString)
        body["kind"].asString() shouldBe "ANTHROPIC"
        body["displayName"].asString() shouldBe "Claude"
        body["apiKeySet"].asBoolean() shouldBe true
        result.response.contentAsString shouldNotContain "sk-ant-secret"
        verify { ports.secrets.put(any(), SecretValue("sk-ant-secret")) }
    }

    @Test
    fun `the list shows whether a key is set, never the key or its secret id`() {
        val result = mvc.get().uri("/api/setup/providers").exchange()

        result.response.status shouldBe 200
        val body = result.response.contentAsString
        elements(body).map { it["apiKeySet"].asBoolean() } shouldBe listOf(true, false)
        body shouldNotContain keyId.value.toString()
        body shouldNotContain "sk-"
    }

    @Test
    fun `invalid input is a 400 naming each request field`() {
        mvc
            .post()
            .uri("/api/setup/providers")
            .json("""{"kind":"OPENAI_COMPATIBLE","displayName":" ","baseUrl":"http://user:pw@host/v1"}""")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"type":"${SetupProblems.INVALID}",
                 "violations":[{"field":"displayName","problem":"REQUIRED"},{"field":"baseUrl","problem":"INVALID_URL"}]}
                """.trimIndent(),
            )
    }

    @Test
    fun `an update of an unknown provider is a 404, a storage failure a 503`() {
        mvc
            .put()
            .uri("/api/setup/providers/${UUID.randomUUID()}")
            .json("""{"displayName":"X"}""")
            .assertThat()
            .hasStatus(404)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(SetupProblems.NOT_FOUND)
        every { ports.providers.update(any()) } returns SetupStoreResult.StorageFailure("update")
        mvc
            .put()
            .uri("/api/setup/providers/${ollama.id.value}")
            .json("""{"displayName":"Local","baseUrl":"http://localhost:11434/v1"}""")
            .assertThat()
            .hasStatus(503)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(SetupProblems.UNAVAILABLE)
    }

    @Test
    fun `moving a stored key to another origin needs the key again`() {
        val path = "/api/setup/providers/${router.id.value}"
        mvc
            .put()
            .uri(path)
            .json("""{"displayName":"OpenRouter","baseUrl":"http://openrouter.ai/api/v1"}""")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """{"type":"${SetupProblems.INVALID}","violations":[{"field":"apiKey","problem":"REQUIRED"}]}""",
            )
        verify(exactly = 0) { ports.providers.update(any()) }

        mvc
            .put()
            .uri(path)
            .json("""{"displayName":"OpenRouter","baseUrl":"https://other.example/v1","apiKey":"sk-other"}""")
            .assertThat()
            .hasStatusOk()
        verify { ports.secrets.put(keyId, SecretValue("sk-other")) }
    }

    private fun deleteOpenAi(
        session: MockHttpSession,
        token: String? = null,
    ) = mvc
        .delete()
        .uri("/api/setup/providers/${openAi.id.value}")
        .session(session)
        .apply { if (token != null) header(Confirmations.HEADER, token) }
        .exchange()

    @Test
    fun `deleting takes two steps with the token in the confirmation header`() {
        val session = MockHttpSession()
        val first = deleteOpenAi(session)

        first.response.status shouldBe 428
        val problem = json.readTree(first.response.contentAsString)
        problem["type"].asString() shouldBe Confirmations.REQUIRED
        problem["effect"].toString() shouldBe """{"kind":"ai_provider","name":"OpenAI","counts":{}}"""
        verify(exactly = 0) { ports.providers.delete(any(), any()) }

        val token = problem["confirmationToken"].asString()
        deleteOpenAi(session, token).response.status shouldBe 204
        verify { ports.providers.delete(openAi.id, any()) }
        verify { ports.secrets.delete(keyId) }
        // The token is spent: a replay is refused.
        deleteOpenAi(session, token).response.status shouldBe 412
    }

    @Test
    fun `a provider with assigned tasks cannot be deleted`() {
        every { ports.assignments.findAll() } returns
            SetupStoreResult.Success(listOf(ModelAssignment(AiTask.CHAT, openAi.id, ModelName("gpt-5"))))

        mvc
            .delete()
            .uri("/api/setup/providers/${openAi.id.value}")
            .session(MockHttpSession())
            .assertThat()
            .hasStatus(409)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(SetupProblems.IN_USE)
    }

    @Test
    fun `a refresh lists the models, a rejected key is a 502 with its own type`() {
        every { ports.catalog.detect(ollama) } returns AiResult.Success(emptyList())
        mvc
            .post()
            .uri("/api/setup/providers/${ollama.id.value}/models/refresh")
            .assertThat()
            .hasStatusOk()

        every { ports.catalog.detect(openAi) } returns AiResult.AuthenticationFailed
        mvc
            .post()
            .uri("/api/setup/providers/${openAi.id.value}/models/refresh")
            .assertThat()
            .hasStatus(502)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(SetupProblems.AUTHENTICATION_FAILED)
    }

    @Test
    fun `a capability correction answers the stored USER profile`() {
        mvc
            .put()
            .uri("/api/setup/providers/${ollama.id.value}/models")
            .json("""{"model":"llama3.1","features":["STREAMING"],"contextWindowTokens":8192}""")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo(
                """{"model":"llama3.1","features":["STREAMING"],"contextWindowTokens":8192,"origin":"USER"}""",
            )
    }

    @Test
    fun `an assignment answers what the task needs and what the model lacks`() {
        every { ports.catalog.knownCapabilities(ProviderKind.OPENAI_COMPATIBLE, ModelName("llama3.2:1b")) } returns
            ModelCapabilities(setOf(Capability.Streaming, Capability.ContextSize(8_192)))

        mvc
            .put()
            .uri("/api/setup/assignments/CHAT")
            .json("""{"providerId":"${ollama.id.value}","model":"llama3.2:1b"}""")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"task":"CHAT","providerId":"${ollama.id.value}","model":"llama3.2:1b",
                 "needs":{"features":["TOOL_USE","STREAMING"],"minContextWindowTokens":32768},
                 "missing":{"features":["TOOL_USE"],"minContextWindowTokens":32768}}
                """.trimIndent(),
            )
    }

    @Test
    fun `the assignment list has every task, an unknown task is a 400`() {
        val result = mvc.get().uri("/api/setup/assignments").exchange()

        elements(result.response.contentAsString).map { it["task"].asString() } shouldBe
            AiTask.entries.map { it.name }
        mvc
            .put()
            .uri("/api/setup/assignments/NOT_A_TASK")
            .json("""{"providerId":"${ollama.id.value}","model":"x"}""")
            .assertThat()
            .hasStatus(400)
    }
}
