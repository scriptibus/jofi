// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.github.scriptibus.jofi.setup.adapter.ai.AiGatewayAdapter
import io.github.scriptibus.jofi.setup.application.port.CostEntryPort
import io.github.scriptibus.jofi.setup.application.port.ModelAssignmentPort
import io.github.scriptibus.jofi.setup.application.port.ModelCapabilityPort
import io.github.scriptibus.jofi.setup.application.port.MonthlyBudgetPort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.domain.Capability
import io.github.scriptibus.jofi.setup.domain.CapabilitySource
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.ModelCapabilities
import io.github.scriptibus.jofi.setup.domain.ModelCapabilityProfile
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.Money
import io.github.scriptibus.jofi.setup.domain.MonthlyBudget
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.application.port.AiVisibilityPort
import io.github.scriptibus.jofi.shared.application.port.EmbeddingPort
import io.github.scriptibus.jofi.shared.application.port.LlmPort
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.AiVisibility
import io.github.scriptibus.jofi.shared.domain.ai.AiVisibilityResult
import io.github.scriptibus.jofi.shared.domain.ai.ContentPart
import io.github.scriptibus.jofi.shared.domain.ai.ContentSource
import io.github.scriptibus.jofi.shared.domain.ai.ContentSourceType
import io.github.scriptibus.jofi.shared.domain.ai.EmbeddingRequest
import io.github.scriptibus.jofi.shared.domain.ai.FlaggedValue
import io.github.scriptibus.jofi.shared.domain.ai.LlmMessage
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.github.scriptibus.jofi.shared.domain.ai.NeverSendFilter
import io.github.scriptibus.jofi.shared.domain.ai.NeverSendRules
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * End to end through the wired app (ADR-0043): the one `LlmPort`/`EmbeddingPort` bean is the
 * gateway; routing, capabilities, budget and costs come from PostgreSQL; the Spring AI adapter and
 * the guarded transport send to an OpenAI-compatible provider played by WireMock, whose received
 * requests prove that flagged items never arrive. The "never send to AI" source is a fake with one
 * flagged knowledge entry, as the knowledge context will provide it (M2).
 */
@SpringBootTest
@Import(PostgresTestConfiguration::class, AiGatewayPrivacyTest.FlaggedKnowledge::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiGatewayPrivacyTest(
    @param:Autowired private val context: ApplicationContext,
    @param:Autowired private val llm: LlmPort,
    @param:Autowired private val providers: ProviderConfigPort,
    @param:Autowired private val assignments: ModelAssignmentPort,
    @param:Autowired private val capabilities: ModelCapabilityPort,
    @param:Autowired private val budgets: MonthlyBudgetPort,
    @param:Autowired private val costs: CostEntryPort,
) {
    private val embeddings: EmbeddingPort get() = context.getBean(EmbeddingPort::class.java)

    @BeforeAll
    fun configureFakeProvider() {
        providers.save(PROVIDER) shouldBe SetupStoreResult.Success(Unit)
        listOf(AiTask.CHAT, AiTask.SCANNER_PRE_SCORING, AiTask.EMBEDDING).forEach { task ->
            val model = ModelName("fake-${task.name.lowercase()}")
            assignments.save(ModelAssignment(task, PROVIDER.id, model)) shouldBe SetupStoreResult.Success(Unit)
            capabilities.save(
                ModelCapabilityProfile(
                    PROVIDER.id,
                    model,
                    ModelCapabilities(setOf(Capability.ToolUse, Capability.Streaming, Capability.Embedding)),
                    CapabilitySource.USER,
                    Instant.now(),
                ),
            )
        }
        FAKE_AI.stubFor(post("/v1/chat/completions").willReturn(json(ANSWER)))
        FAKE_AI.stubFor(post("/v1/embeddings").willReturn(json(EMBEDDINGS)))
    }

    @BeforeEach
    fun reset() {
        FAKE_AI.resetRequests()
        budgets.clear()
    }

    @AfterAll
    fun stop() {
        FAKE_AI.stop()
    }

    @Test
    fun `the gateway is the only LLM and embedding port`() {
        context
            .getBeansOfType(LlmPort::class.java)
            .values
            .single()
            .shouldBeInstanceOf<AiGatewayAdapter>()
        context
            .getBeansOfType(EmbeddingPort::class.java)
            .values
            .single()
            .shouldBeInstanceOf<AiGatewayAdapter>()
    }

    @Test
    fun `a flagged knowledge entry never reaches the provider, and the call is metered`() {
        val before = Instant.now()
        val flagged = ContentPart.Sourced(ADDRESS, ADDRESS_ENTRY)
        val request =
            LlmRequest(
                AiTask.CHAT,
                listOf(
                    LlmMessage.System(listOf(ContentPart.Plain("Profil: "), flagged)),
                    LlmMessage.User("Schick die Unterlagen an $ADDRESS"),
                    LlmMessage.ToolResult("call_1", listOf(flagged)),
                ),
            )

        llm.complete(request).shouldBeInstanceOf<AiResult.Success<*>>()
        llm.stream(request.copy(messages = request.messages.take(2))) {}.shouldBeInstanceOf<AiResult<*>>()

        val received = FAKE_AI.findAll(anyRequestedFor(anyUrl()))
        received shouldHaveSize 2
        received.forEach {
            it.bodyAsString shouldNotContain "Musterstra"
            it.bodyAsString shouldContain NeverSendFilter.REDACTION
        }
        val metered = (costs.findBetween(before, Instant.now().plusSeconds(1)) as SetupStoreResult.Success).value
        metered.first().usage shouldBe TokenUsage(12, 3)
        // An OpenAI-compatible endpoint has no list price: tokens are kept, the cost is unknown.
        metered.first().estimatedCost shouldBe null
    }

    @Test
    fun `a flagged entry is never embedded`() {
        embeddings.embed(EmbeddingRequest(listOf(ContentPart.Sourced(ADDRESS, ADDRESS_ENTRY)))) shouldBe
            AiResult.Withheld(AiTask.EMBEDDING)
        embeddings.embed(EmbeddingRequest.ofTexts(listOf("Kotlin"))).shouldBeInstanceOf<AiResult.Success<*>>()

        FAKE_AI.findAll(anyRequestedFor(anyUrl())).single().bodyAsString shouldNotContain "Musterstra"
    }

    @Test
    fun `an entry the source does not know is refused before any request`() {
        val unknown = ContentSource(ContentSourceType.KNOWLEDGE_ENTRY, "not-indexed")

        llm.complete(
            LlmRequest(AiTask.CHAT, listOf(LlmMessage.User(listOf(ContentPart.Sourced("x", unknown))))),
        ) shouldBe
            AiResult.PrivacyFilterFailed(AiTask.CHAT)
        FAKE_AI.findAll(anyRequestedFor(anyUrl())) shouldHaveSize 0
    }

    @Test
    fun `at the budget cap scanner pre-scoring pauses while chat continues`() {
        budgets.save(MonthlyBudget(Money.usd(1))) shouldBe SetupStoreResult.Success(Unit)
        costs.append(
            io.github.scriptibus.jofi.setup.domain.CostEntry(
                AiTask.SCANNER_PRE_SCORING,
                PROVIDER.id,
                ProviderKind.OPENAI,
                ModelName("gpt-4o-mini"),
                TokenUsage(10, 10),
                Money.usd(5),
                Instant.now(),
            ),
        )

        llm.complete(LlmRequest(AiTask.SCANNER_PRE_SCORING, listOf(LlmMessage.User("Score this")))) shouldBe
            AiResult.BudgetExceeded(AiTask.SCANNER_PRE_SCORING)
        FAKE_AI.findAll(anyRequestedFor(anyUrl())) shouldHaveSize 0
        llm
            .complete(
                LlmRequest(AiTask.CHAT, listOf(LlmMessage.User("Hallo"))),
            ).shouldBeInstanceOf<AiResult.Success<*>>()
    }

    /** The knowledge context's future answer: one entry flagged "never send to AI". */
    @TestConfiguration(proxyBeanMethods = false)
    class FlaggedKnowledge {
        @Bean
        @Primary
        fun flaggedKnowledgeSource(): AiVisibilityPort =
            object : AiVisibilityPort {
                override fun rulesFor(sources: Set<ContentSource>): AiVisibilityResult =
                    AiVisibilityResult.Known(
                        NeverSendRules(
                            mapOf(ADDRESS_ENTRY to AiVisibility.NEVER_SEND).filterKeys { it in sources },
                            setOf(FlaggedValue(ADDRESS)),
                        ),
                    )
            }
    }

    private companion object {
        const val ADDRESS = "Musterstraße 5, 12345 Berlin"
        val ADDRESS_ENTRY = ContentSource(ContentSourceType.KNOWLEDGE_ENTRY, "profile/address")
        val FAKE_AI: WireMockServer =
            WireMockServer(wireMockConfig().dynamicPort().bindAddress("127.0.0.1")).apply { start() }
        val PROVIDER =
            ProviderConfig(
                ProviderId(UUID.randomUUID()),
                "Fake AI",
                ProviderKind.OPENAI_COMPATIBLE,
                null,
                URI("http://127.0.0.1:${FAKE_AI.port()}/v1"),
            )

        fun json(body: String): ResponseDefinitionBuilder =
            aResponse().withHeader("Content-Type", "application/json").withBody(body)

        const val ANSWER =
            """{"id":"c","object":"chat.completion","created":1,"model":"fake-chat",""" +
                """"choices":[{"index":0,"message":{"role":"assistant","content":"Erledigt"},""" +
                """"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}}"""
        const val EMBEDDINGS =
            """{"object":"list","model":"fake-embedding",""" +
                """"data":[{"object":"embedding","index":0,"embedding":[0.1,0.2]}],""" +
                """"usage":{"prompt_tokens":2,"total_tokens":2}}"""
    }
}
