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

    @Test
    fun `money is exact in micros and adds up within one currency`() {
        val total = Money(150, usd) + Money(3_000_000, usd)

        total shouldBe Money(3_000_150, usd)
        total.amount shouldBe BigDecimal("3.000150")
        Money.zero(usd) shouldBe Money(0, usd)
        (Money(1, usd) < Money(2, usd)) shouldBe true
    }

    @Test
    fun `money is never negative and never mixes currencies`() {
        shouldThrow<IllegalArgumentException> { Money(-1, usd) }
        shouldThrow<IllegalArgumentException> { Money(1, usd) + Money(1, eur) }
        shouldThrow<IllegalArgumentException> { Money(1, usd).compareTo(Money(1, eur)) }
        shouldThrow<ArithmeticException> { Money(Long.MAX_VALUE, usd) + Money(1, usd) }
    }

    @Test
    fun `a cost entry records task, provider, model, tokens, cost and time`() {
        val provider = ProviderId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val at = Instant.parse("2026-09-30T08:00:00Z")

        val entry =
            CostEntry(AiTask.CHAT, provider, ModelName("claude-haiku"), TokenUsage(1200, 300), Money(3600, usd), at)

        entry.usage.totalTokens shouldBe 1500
        entry.estimatedCost.amount shouldBe BigDecimal("0.003600")
        entry.occurredAt shouldBe at
    }

    @Test
    fun `a monthly budget is positive and reached once spending hits the cap`() {
        val budget = MonthlyBudget(Money(20_000_000, eur))

        budget.isReachedBy(Money(19_999_999, eur)) shouldBe false
        budget.isReachedBy(Money(20_000_000, eur)) shouldBe true
        shouldThrow<IllegalArgumentException> { MonthlyBudget(Money.zero(eur)) }
    }

    @ParameterizedTest
    @EnumSource(AiTask::class)
    fun `a reached budget pauses only non-essential tasks`(task: AiTask) {
        val budget = MonthlyBudget(Money(1_000_000, eur))
        val nonEssential = task == AiTask.SCANNER_PRE_SCORING

        budget.pauses(task, spentThisMonth = Money(1_000_000, eur)) shouldBe nonEssential
        budget.pauses(task, spentThisMonth = Money(999_999, eur)) shouldBe false
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
}
