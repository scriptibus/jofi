// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.UUID

class CostAndBudgetTest {
    private val usd = Currency.getInstance("USD")
    private val eur = Currency.getInstance("EUR")
    private val provider = ProviderId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val at = Instant.parse("2026-09-30T08:00:00Z")

    @Test
    fun `money is exact in micros and adds up within one currency`() {
        val total = Money.usd(150) + Money.usd(3_000_000)

        total shouldBe Money(3_000_150, usd)
        total.amount shouldBe BigDecimal("3.000150")
        Money.zero(usd) shouldBe Money.usd(0)
        (Money.usd(1) < Money.usd(2)) shouldBe true
        Money.ACCOUNTING_CURRENCY shouldBe usd
        Money.usd(1).isAccountingCurrency shouldBe true
        Money(1, eur).isAccountingCurrency shouldBe false
    }

    @Test
    fun `money is never negative and never mixes currencies`() {
        shouldThrow<IllegalArgumentException> { Money(-1, usd) }
        shouldThrow<IllegalArgumentException> { Money.usd(1) + Money(1, eur) }
        shouldThrow<IllegalArgumentException> { Money.usd(1).compareTo(Money(1, eur)) }
        shouldThrow<ArithmeticException> { Money.usd(Long.MAX_VALUE) + Money.usd(1) }
    }

    @Test
    fun `a cost entry records task, provider and kind, model, tokens, cost in USD and time`() {
        val entry = cost(Money.usd(3600))

        entry.providerKind shouldBe ProviderKind.ANTHROPIC
        entry.usage.totalTokens shouldBe 1500
        entry.estimatedCost.amount shouldBe BigDecimal("0.003600")
        entry.occurredAt shouldBe at
        shouldThrow<IllegalArgumentException> { cost(Money(3600, eur)) }
    }

    @Test
    fun `a monthly budget is a positive USD amount`() {
        MonthlyBudget(Money.usd(20_000_000)).cap shouldBe Money.usd(20_000_000)
        shouldThrow<IllegalArgumentException> { MonthlyBudget(Money.usd(0)) }
        shouldThrow<IllegalArgumentException> { MonthlyBudget(Money(20_000_000, eur)) }
    }

    @Test
    fun `the budget is reached once spending hits the cap`() {
        val budget = MonthlyBudget(Money.usd(20_000_000))

        budget.status(Money.usd(19_999_999)) shouldBe BudgetStatus.WithinBudget
        budget.status(Money.usd(20_000_000)) shouldBe BudgetStatus.Reached
    }

    @Test
    fun `spending in another currency is reported instead of thrown`() {
        val budget = MonthlyBudget(Money.usd(1_000_000))

        budget.status(Money(5, eur)) shouldBe BudgetStatus.CurrencyMismatch(eur)
        budget.decide(AiTask.SCANNER_PRE_SCORING, Money(5, eur)) shouldBe BudgetDecision.CurrencyMismatch(eur)
    }

    @ParameterizedTest
    @EnumSource(AiTask::class)
    fun `a reached budget pauses only non-essential tasks`(task: AiTask) {
        val budget = MonthlyBudget(Money.usd(1_000_000))
        val atCap = if (task == AiTask.SCANNER_PRE_SCORING) BudgetDecision.Pause else BudgetDecision.Run

        budget.decide(task, spentThisMonth = Money.usd(1_000_000)) shouldBe atCap
        budget.decide(task, spentThisMonth = Money.usd(999_999)) shouldBe BudgetDecision.Run
    }

    @Test
    fun `every setup store outcome is a value`() {
        val results: List<SetupStoreResult<Int>> =
            listOf(
                SetupStoreResult.Success(1),
                SetupStoreResult.NotFound,
                SetupStoreResult.InUse,
                SetupStoreResult.StorageFailure("save"),
            )

        val described =
            results.map {
                when (it) {
                    is SetupStoreResult.Success -> "ok ${it.value}"
                    SetupStoreResult.NotFound -> "not found"
                    SetupStoreResult.InUse -> "in use"
                    is SetupStoreResult.StorageFailure -> it.operation
                }
            }

        described shouldBe listOf("ok 1", "not found", "in use", "save")
    }

    private fun cost(estimated: Money) =
        CostEntry(
            task = AiTask.CHAT,
            provider = provider,
            providerKind = ProviderKind.ANTHROPIC,
            model = ModelName("claude-haiku"),
            usage = TokenUsage(1200, 300),
            estimatedCost = estimated,
            occurredAt = at,
        )
}
