// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.setup.application.port.ModelCatalogPort
import io.github.scriptibus.jofi.setup.domain.Capability
import io.github.scriptibus.jofi.setup.domain.CapabilitySource
import io.github.scriptibus.jofi.setup.domain.CostEntry
import io.github.scriptibus.jofi.setup.domain.LongPromptPrice
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.ModelCapabilities
import io.github.scriptibus.jofi.setup.domain.ModelCapabilityProfile
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ModelPrice
import io.github.scriptibus.jofi.setup.domain.Money
import io.github.scriptibus.jofi.setup.domain.MonthlyBudget
import io.github.scriptibus.jofi.setup.domain.PriceTable
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.ResolvedModel
import io.github.scriptibus.jofi.setup.domain.TokenPrice
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.AiTaskKind
import io.github.scriptibus.jofi.shared.domain.ai.AiVisibility
import io.github.scriptibus.jofi.shared.domain.ai.ContentPart
import io.github.scriptibus.jofi.shared.domain.ai.ContentSource
import io.github.scriptibus.jofi.shared.domain.ai.ContentSourceType
import io.github.scriptibus.jofi.shared.domain.ai.Embedding
import io.github.scriptibus.jofi.shared.domain.ai.EmbeddingRequest
import io.github.scriptibus.jofi.shared.domain.ai.EmbeddingResponse
import io.github.scriptibus.jofi.shared.domain.ai.FlaggedValue
import io.github.scriptibus.jofi.shared.domain.ai.LlmMessage
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.github.scriptibus.jofi.shared.domain.ai.NeverSendFilter
import io.github.scriptibus.jofi.shared.domain.ai.NeverSendRules
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import io.github.scriptibus.jofi.shared.domain.ai.ToolDefinition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** The gateway's steps with a spy provider: routing, capability check, budget, filter, metering. */
class AiGatewayAdapterTest {
    private val setup = InMemorySetup()
    private val visibility = FakeVisibility()
    private val provider = SpyProvider()
    private var now = Instant.parse("2026-09-30T12:00:00Z")
    private val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?): Clock = this

            override fun instant(): Instant = now
        }
    private val catalog =
        object : ModelCatalogPort {
            override fun detect(provider: ProviderConfig) = AiResult.Unavailable

            override fun knownCapabilities(
                kind: ProviderKind,
                model: ModelName,
            ) = if (model == KNOWN_MODEL) ModelCapabilities(setOf(Capability.ToolUse)) else ModelCapabilities.NONE
        }
    private val prices =
        PriceTable(
            listOf(
                ModelPrice(
                    ProviderKind.OPENAI,
                    MODEL,
                    TokenPrice(BigDecimal("0.15"), BigDecimal("0.60")),
                    LongPromptPrice(1_000_000, TokenPrice(BigDecimal("1"), BigDecimal("1"))),
                    LocalDate.parse("2026-09-30"),
                    URI("https://example.com/pricing"),
                ),
            ),
        )
    private val gateway =
        AiGatewayAdapter(
            provider,
            AiRouter(setup.assignmentPort, setup.providerPort, setup.capabilityPort, catalog),
            NeverSendGuard(visibility),
            AiMeter(setup.costPort, setup.budgetPort, prices, clock),
        )

    private fun assign(
        task: AiTask,
        model: ModelName = MODEL,
        capabilities: Set<Capability>? = setOf(Capability.ToolUse, Capability.Streaming, Capability.Embedding),
    ) {
        setup.providers[PROVIDER.id] = PROVIDER
        setup.assignments[task] = ModelAssignment(task, PROVIDER.id, model)
        if (capabilities != null) {
            setup.profiles[PROVIDER.id to model] =
                ModelCapabilityProfile(PROVIDER.id, model, ModelCapabilities(capabilities), CapabilitySource.USER, now)
        }
    }

    private fun chat(
        task: AiTask = AiTask.CHAT,
        text: String = "Hallo",
    ) = LlmRequest(task, listOf(LlmMessage.User(text)))

    // Routing

    @Test
    fun `a call goes to the task's assigned provider and model`() {
        assign(AiTask.CHAT)

        gateway.complete(chat()).shouldBeInstanceOf<AiResult.Success<*>>()

        provider.llmRequests.single().first shouldBe ResolvedModel(PROVIDER, MODEL)
    }

    @Test
    fun `a task without an assignment is not configured and nothing is sent`() {
        gateway.complete(chat()) shouldBe AiResult.NotConfigured(AiTask.CHAT)
        gateway.embed(EmbeddingRequest.ofTexts(listOf("x"))) shouldBe AiResult.NotConfigured(AiTask.EMBEDDING)

        provider.calls shouldBe 0
        visibility.asked.shouldBeEmpty()
        setup.costs.shouldBeEmpty()
    }

    @Test
    fun `an assignment whose provider is gone is not configured, and a broken store is unavailable`() {
        setup.assignments[AiTask.CHAT] = ModelAssignment(AiTask.CHAT, ProviderId(UUID.randomUUID()), MODEL)
        gateway.complete(chat()) shouldBe AiResult.NotConfigured(AiTask.CHAT)

        assign(AiTask.CHAT)
        setup.failing = true
        gateway.complete(chat()) shouldBe AiResult.Unavailable
        provider.calls shouldBe 0
    }

    // Capabilities

    @Test
    fun `a call needing a capability the model lacks returns CapabilityMissing without calling the provider`() {
        assign(AiTask.CHAT, capabilities = setOf(Capability.Streaming))
        val withTools = chat().copy(tools = listOf(ToolDefinition("search", "Searches", """{"type":"object"}""")))

        gateway.complete(withTools) shouldBe AiResult.CapabilityMissing(AiTask.CHAT)
        assign(AiTask.KNOWLEDGE_INTERVIEW, capabilities = setOf(Capability.ToolUse))
        gateway.stream(chat(AiTask.KNOWLEDGE_INTERVIEW)) {} shouldBe
            AiResult.CapabilityMissing(AiTask.KNOWLEDGE_INTERVIEW)
        assign(AiTask.EMBEDDING, capabilities = setOf(Capability.ToolUse))
        gateway.embed(EmbeddingRequest.ofTexts(listOf("x"))) shouldBe AiResult.CapabilityMissing(AiTask.EMBEDDING)

        provider.calls shouldBe 0
        // A plain call does not need tools or streaming, so the same model serves it.
        gateway.complete(chat()).shouldBeInstanceOf<AiResult.Success<*>>()
    }

    @Test
    fun `without a stored profile the known capabilities of the model decide`() {
        assign(AiTask.CHAT, model = KNOWN_MODEL, capabilities = null)
        val withTools = chat().copy(tools = listOf(ToolDefinition("search", "Searches", """{"type":"object"}""")))

        gateway.complete(withTools).shouldBeInstanceOf<AiResult.Success<*>>()
        gateway.stream(withTools) {} shouldBe AiResult.CapabilityMissing(AiTask.CHAT)
        provider.calls shouldBe 1
    }

    // Budget

    @Test
    fun `at the monthly cap scanner pre-scoring is paused without calling the provider`() {
        assign(AiTask.SCANNER_PRE_SCORING)
        setup.budget = MonthlyBudget(Money.usd(1_000))
        setup.costs += cost(Money.usd(1_000), Instant.parse("2026-09-01T00:00:00Z"))

        gateway.complete(chat(AiTask.SCANNER_PRE_SCORING)) shouldBe AiResult.BudgetExceeded(AiTask.SCANNER_PRE_SCORING)
        provider.calls shouldBe 0
    }

    @ParameterizedTest
    @EnumSource(AiTask::class)
    fun `at the cap only the documented non-essential tasks pause`(task: AiTask) {
        assign(task)
        setup.budget = MonthlyBudget(Money.usd(1_000))
        setup.costs += cost(Money.usd(5_000), now)

        val result =
            when (task.kind) {
                AiTaskKind.TEXT_GENERATION -> gateway.complete(chat(task))
                AiTaskKind.EMBEDDING -> gateway.embed(EmbeddingRequest.ofTexts(listOf("x"), task))
                else -> return
            }

        // The documented list (spec §3.2): only background work the user did not trigger pauses.
        MonthlyBudget.NON_ESSENTIAL_TASKS shouldBe setOf(AiTask.SCANNER_PRE_SCORING)
        if (task == AiTask.SCANNER_PRE_SCORING) {
            result shouldBe AiResult.BudgetExceeded(task)
        } else {
            result.shouldBeInstanceOf<AiResult<*>>()
            (result is AiResult.BudgetExceeded) shouldBe false
            provider.calls shouldBe 1
        }
    }

    @Test
    fun `the pause lifts when the month rolls over in UTC`() {
        assign(AiTask.SCANNER_PRE_SCORING)
        setup.budget = MonthlyBudget(Money.usd(1_000))
        now = Instant.parse("2026-09-30T23:59:59Z")
        setup.costs += cost(Money.usd(1_000), now)
        gateway.complete(chat(AiTask.SCANNER_PRE_SCORING)) shouldBe AiResult.BudgetExceeded(AiTask.SCANNER_PRE_SCORING)

        now = Instant.parse("2026-10-01T00:00:00Z")

        gateway.complete(chat(AiTask.SCANNER_PRE_SCORING)).shouldBeInstanceOf<AiResult.Success<*>>()
    }

    @Test
    fun `below the cap, without a cap and with unknown costs scanner pre-scoring runs`() {
        assign(AiTask.SCANNER_PRE_SCORING)
        gateway.complete(chat(AiTask.SCANNER_PRE_SCORING)).shouldBeInstanceOf<AiResult.Success<*>>()

        setup.budget = MonthlyBudget(Money.usd(1_000))
        setup.costs += cost(Money.usd(900), now)
        setup.costs += cost(null, now)
        gateway.complete(chat(AiTask.SCANNER_PRE_SCORING)).shouldBeInstanceOf<AiResult.Success<*>>()
    }

    @Test
    fun `a budget that cannot be read stops non-essential work only`() {
        assign(AiTask.SCANNER_PRE_SCORING)
        val meter = AiMeter(setup.costPort, setup.budgetPort, prices, clock)
        setup.failing = true

        meter.admit(AiTask.SCANNER_PRE_SCORING) shouldBe AiResult.Unavailable
        meter.admit(AiTask.CHAT) shouldBe null
    }

    // Never send to AI

    @Test
    fun `flagged content never reaches the provider, in any message part`() {
        assign(AiTask.CHAT)
        visibility.rules = RULES
        val request = flaggedConversation()

        gateway.complete(request).shouldBeInstanceOf<AiResult.Success<*>>()
        gateway.stream(request) {}.shouldBeInstanceOf<AiResult.Success<*>>()

        provider.llmRequests shouldHaveSize 2
        provider.llmRequests.forEach { (_, sent) ->
            val wire = sent.toString() + sent.messages.joinToString { wireText(it) }
            wire shouldNotContain "Musterstraße"
            wire shouldNotContain "1234567"
            wire shouldContain NeverSendFilter.REDACTION
        }
        visibility.asked.first() shouldBe setOf(ADDRESS)
    }

    private fun flaggedConversation(): LlmRequest {
        val flagged = ContentPart.Sourced("Musterstraße 5", ADDRESS)
        return LlmRequest(
            AiTask.CHAT,
            listOf(
                LlmMessage.System(listOf(ContentPart.Plain("Profile: "), flagged)),
                LlmMessage.User("Call me at 0170 1234567"),
                LlmMessage.Assistant(
                    "",
                    listOf(
                        io.github.scriptibus.jofi.shared.domain.ai
                            .ToolCall("c", "note", """{"n":"0170 1234567"}"""),
                    ),
                ),
                LlmMessage.ToolResult("c", listOf(flagged)),
            ),
        )
    }

    @Test
    fun `when the filter cannot decide the call is refused and nothing is sent`() {
        assign(AiTask.CHAT)
        assign(AiTask.EMBEDDING)
        val unknown = ContentSource(ContentSourceType.KNOWLEDGE_ENTRY, "not-indexed-yet")
        val quoting = LlmRequest(AiTask.CHAT, listOf(LlmMessage.User(listOf(ContentPart.Sourced("x", unknown)))))

        gateway.complete(quoting) shouldBe AiResult.PrivacyFilterFailed(AiTask.CHAT)
        visibility.unavailable = true
        gateway.complete(chat()) shouldBe AiResult.PrivacyFilterFailed(AiTask.CHAT)
        gateway.stream(chat()) {} shouldBe AiResult.PrivacyFilterFailed(AiTask.CHAT)
        gateway.embed(EmbeddingRequest.ofTexts(listOf("x"))) shouldBe AiResult.PrivacyFilterFailed(AiTask.EMBEDDING)
        visibility.unavailable = false
        visibility.throwing = true
        gateway.complete(chat()) shouldBe AiResult.PrivacyFilterFailed(AiTask.CHAT)

        provider.calls shouldBe 0
        setup.costs.shouldBeEmpty()
    }

    @Test
    fun `an embedding of a flagged item is withheld, other inputs are sent redacted`() {
        assign(AiTask.EMBEDDING)
        visibility.rules = RULES
        provider.embedding = AiResult.Success(EmbeddingResponse(listOf(Embedding(listOf(1f))), TokenUsage(5, 0)))

        gateway.embed(EmbeddingRequest(listOf(ContentPart.Sourced("Musterstraße 5", ADDRESS)))) shouldBe
            AiResult.Withheld(AiTask.EMBEDDING)
        provider.calls shouldBe 0

        gateway.embed(EmbeddingRequest.ofTexts(listOf("ruf 0170 1234567 an"))).shouldBeInstanceOf<AiResult.Success<*>>()
        provider.embeddingRequests
            .single()
            .second.texts shouldBe listOf("ruf ${NeverSendFilter.REDACTION} an")
    }

    // Metering

    @Test
    fun `every completed call records task, provider, kind, model, tokens and the priced cost`() {
        assign(AiTask.CHAT)

        gateway.complete(chat())

        setup.costs.single() shouldBe
            CostEntry(AiTask.CHAT, PROVIDER.id, ProviderKind.OPENAI, MODEL, TokenUsage(100, 20), Money.usd(27), now)
    }

    @Test
    fun `a model without a price is metered with its tokens and an unknown cost`() {
        assign(AiTask.CHAT, model = ModelName("unpriced"))

        gateway.complete(chat())

        setup.costs.single().usage shouldBe TokenUsage(100, 20)
        setup.costs.single().estimatedCost shouldBe null
    }

    @Test
    fun `a cancelled stream is metered with the usage reported before the cancel`() {
        assign(AiTask.CHAT)
        provider.answer = AiResult.Cancelled
        provider.streamUsage = listOf(TokenUsage(40, 0), TokenUsage(40, 7))

        gateway.stream(chat()) {} shouldBe AiResult.Cancelled

        setup.costs.single().usage shouldBe TokenUsage(40, 7)
        setup.costs.single().estimatedCost shouldBe Money.usd(10)
    }

    @Test
    fun `a stream cancelled before any usage is metered with an unknown cost`() {
        assign(AiTask.CHAT)
        provider.answer = AiResult.Cancelled

        gateway.stream(chat()) {} shouldBe AiResult.Cancelled

        setup.costs.single().usage shouldBe TokenUsage.NONE
        setup.costs.single().estimatedCost shouldBe null
    }

    @Test
    fun `a failed call is metered only when the provider already reported usage`() {
        assign(AiTask.CHAT)
        provider.answer = AiResult.Unavailable

        gateway.complete(chat()) shouldBe AiResult.Unavailable
        setup.costs.shouldBeEmpty()

        provider.streamUsage = listOf(TokenUsage(30, 3))
        gateway.stream(chat()) {} shouldBe AiResult.Unavailable
        setup.costs.single().usage shouldBe TokenUsage(30, 3)
    }

    @Test
    fun `an answer survives a meter that cannot store`() {
        assign(AiTask.CHAT)
        val brokenMeter = InMemorySetup(failing = true)
        val gateway =
            AiGatewayAdapter(
                provider,
                AiRouter(setup.assignmentPort, setup.providerPort, setup.capabilityPort, catalog),
                NeverSendGuard(visibility),
                AiMeter(brokenMeter.costPort, brokenMeter.budgetPort, prices, clock),
            )

        gateway.complete(chat()).shouldBeInstanceOf<AiResult.Success<*>>()
    }

    @Test
    fun `a throwing provider ends as unavailable instead of crossing the port`() {
        assign(AiTask.CHAT)
        val throwing =
            object : io.github.scriptibus.jofi.setup.application.port.AiProviderPort by provider {
                override fun complete(
                    target: ResolvedModel,
                    request: LlmRequest,
                ) = error("bug")
            }
        val gateway =
            AiGatewayAdapter(
                throwing,
                AiRouter(setup.assignmentPort, setup.providerPort, setup.capabilityPort, catalog),
                NeverSendGuard(visibility),
                AiMeter(setup.costPort, setup.budgetPort, prices, clock),
            )

        gateway.complete(chat()) shouldBe AiResult.Unavailable
    }

    private fun cost(
        amount: Money?,
        at: Instant,
    ) = CostEntry(AiTask.CHAT, PROVIDER.id, ProviderKind.OPENAI, MODEL, TokenUsage(1, 1), amount, at)

    private fun wireText(message: LlmMessage): String =
        when (message) {
            is LlmMessage.System -> message.text
            is LlmMessage.User -> message.text
            is LlmMessage.ToolResult -> message.content
            is LlmMessage.Assistant -> message.text + message.toolCalls.joinToString { it.arguments }
        }

    private companion object {
        val MODEL = ModelName("gpt-4o-mini")
        val KNOWN_MODEL = ModelName("known-model")
        val PROVIDER =
            ProviderConfig(
                ProviderId(UUID.randomUUID()),
                "OpenAI",
                ProviderKind.OPENAI,
                io.github.scriptibus.jofi.shared.domain.secret
                    .SecretId(UUID.randomUUID()),
            )
        val ADDRESS = ContentSource(ContentSourceType.KNOWLEDGE_ENTRY, "profile/address")
        val RULES =
            NeverSendRules(
                mapOf(ADDRESS to AiVisibility.NEVER_SEND),
                setOf(FlaggedValue("Musterstraße 5"), FlaggedValue("0170 1234567")),
            )
    }
}
