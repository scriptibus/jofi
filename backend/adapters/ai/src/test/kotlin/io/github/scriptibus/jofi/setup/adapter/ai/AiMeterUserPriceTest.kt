// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.setup.application.port.ModelCatalogPort
import io.github.scriptibus.jofi.setup.application.port.ModelPricePort
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.ModelCapabilities
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ModelPriceOverride
import io.github.scriptibus.jofi.setup.domain.Money
import io.github.scriptibus.jofi.setup.domain.MonthlyBudget
import io.github.scriptibus.jofi.setup.domain.PriceTable
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.ResolvedModel
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.LlmMessage
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The user's price of an OpenAI-compatible model prices the calls recorded after it was set (#142,
 * ADR-0055): the meter uses it before the price table, a price of zero is a known cost, and earlier entries
 * keep what they recorded.
 */
class AiMeterUserPriceTest {
    private val setup = InMemorySetup()
    private val provider = SpyProvider()
    private val clock = Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC)
    private val local =
        ProviderConfig(
            ProviderId(UUID.randomUUID()),
            "Ollama",
            ProviderKind.OPENAI_COMPATIBLE,
            null,
            URI("http://ollama:11434/v1"),
        )
    private val llama = ModelName("llama3.1:8b")
    private val catalog =
        object : ModelCatalogPort {
            override fun detect(provider: ProviderConfig) = AiResult.Unavailable

            override fun knownCapabilities(
                kind: ProviderKind,
                model: ModelName,
            ) = ModelCapabilities.NONE
        }
    private val meter = AiMeter(setup.costPort, setup.budgetPort, PriceTable.EMPTY, setup.pricePort, clock)
    private val gateway =
        AiGatewayAdapter(
            provider,
            AiRouter(setup.assignmentPort, setup.providerPort, setup.capabilityPort, catalog),
            NeverSendGuard(FakeVisibility()),
            meter,
        )

    private fun assign(task: AiTask = AiTask.CHAT) {
        setup.providers[local.id] = local
        setup.assignments[task] = ModelAssignment(task, local.id, llama)
    }

    private fun price(
        input: Long,
        output: Long,
    ) {
        setup.prices[local.id to llama] = ModelPriceOverride(local.id, llama, input, output, clock.instant())
    }

    private fun chat(task: AiTask = AiTask.CHAT) = LlmRequest(task, listOf(LlmMessage.User("Hallo")))

    @Test
    fun `a call on a priced model gets a cost from the user's price`() {
        assign()
        price(150_000, 600_000)

        gateway.complete(chat()).shouldBeInstanceOf<AiResult.Success<*>>()

        // 100 input tokens at 0.15 USD and 20 output tokens at 0.60 USD per million: 15 + 12 micros.
        setup.costs.single().estimatedCost shouldBe Money.usd(27)
        setup.costs.single().providerKind shouldBe ProviderKind.OPENAI_COMPATIBLE
    }

    @Test
    fun `a price of zero is a known cost and not an unknown one`() {
        assign()
        price(0, 0)

        gateway.complete(chat())

        setup.costs.single().estimatedCost shouldBe Money.usd(0)
    }

    @Test
    fun `a model without a price keeps an unknown cost`() {
        assign()

        gateway.complete(chat())

        setup.costs.single().estimatedCost shouldBe null
    }

    @Test
    fun `a price applies to the calls after it, earlier entries keep their cost`() {
        assign()
        gateway.complete(chat())

        price(150_000, 600_000)
        gateway.complete(chat())

        setup.costs.map { it.estimatedCost } shouldBe listOf(null, Money.usd(27))
    }

    @Test
    fun `a price is per provider, another provider's model of the same name stays unknown`() {
        assign()
        val other = local.copy(id = ProviderId(UUID.randomUUID()), displayName = "OpenRouter")
        setup.prices[other.id to llama] = ModelPriceOverride(other.id, llama, 1_000_000, 1_000_000, clock.instant())

        gateway.complete(chat())

        setup.costs.single().estimatedCost shouldBe null
    }

    @Test
    fun `priced calls count toward the cap that pauses scanner pre-scoring`() {
        assign(AiTask.SCANNER_PRE_SCORING)
        setup.budget = MonthlyBudget(Money.usd(40))
        price(150_000, 600_000)

        gateway.complete(chat(AiTask.SCANNER_PRE_SCORING)).shouldBeInstanceOf<AiResult.Success<*>>()
        gateway.complete(chat(AiTask.SCANNER_PRE_SCORING)).shouldBeInstanceOf<AiResult.Success<*>>()

        gateway.complete(chat(AiTask.SCANNER_PRE_SCORING)) shouldBe AiResult.BudgetExceeded(AiTask.SCANNER_PRE_SCORING)
    }

    @Test
    fun `an unpriced model never reaches the cap`() {
        assign(AiTask.SCANNER_PRE_SCORING)
        setup.budget = MonthlyBudget(Money.usd(1))

        repeat(3) { gateway.complete(chat(AiTask.SCANNER_PRE_SCORING)).shouldBeInstanceOf<AiResult.Success<*>>() }
    }

    @Test
    fun `a stray price of a cloud provider's model never replaces the verified table`() {
        val cloud =
            ProviderConfig(ProviderId(UUID.randomUUID()), "OpenAI", ProviderKind.OPENAI, SecretId(UUID.randomUUID()))
        setup.prices[cloud.id to llama] = ModelPriceOverride(cloud.id, llama, 5_000_000, 5_000_000, clock.instant())

        meter.record(AiTask.CHAT, ResolvedModel(cloud, llama), TokenUsage(100, 20))

        setup.costs.single().estimatedCost shouldBe null
    }

    @Test
    fun `a call without reported usage has an unknown cost even at a price`() {
        price(150_000, 600_000)

        meter.record(AiTask.CHAT, ResolvedModel(local, llama), TokenUsage.NONE)

        setup.costs.single().estimatedCost shouldBe null
    }

    @Test
    fun `a price that cannot be read leaves the cost unknown and the call metered`() {
        val broken =
            object : ModelPricePort by setup.pricePort {
                override fun find(
                    provider: ProviderId,
                    model: ModelName,
                ) = SetupStoreResult.StorageFailure("find")
            }
        val brokenMeter = AiMeter(setup.costPort, setup.budgetPort, PriceTable.EMPTY, broken, clock)

        brokenMeter.record(AiTask.CHAT, ResolvedModel(local, llama), TokenUsage(100, 20))

        setup.costs.single().estimatedCost shouldBe null
        setup.costs.single().usage shouldBe TokenUsage(100, 20)
    }
}
