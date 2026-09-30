// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.YearMonth
import java.util.Currency

class CostReportTest {
    private val september = BillingMonth(YearMonth.of(2026, 9))
    private val gpt = ModelKey(ProviderKind.OPENAI, ModelName("gpt-5-mini"))
    private val llama = ModelKey(ProviderKind.OPENAI_COMPATIBLE, ModelName("llama3.1"))

    private fun totals(
        micros: Long,
        unknown: Long = 0,
    ) = CostTotals(unknown + 1, TokenUsage(10, 1), Money.usd(micros), unknown)

    @Test
    fun `totals add up exactly and know how many calls had no price`() {
        totals(5) + totals(7, unknown = 2) shouldBe CostTotals(4, TokenUsage(20, 2), Money.usd(12), 2)
        CostTotals.NONE + totals(5) shouldBe totals(5)
    }

    @Test
    fun `totals refuse impossible counts, foreign currencies and overflow`() {
        shouldThrow<IllegalArgumentException> { CostTotals(-1, TokenUsage(0, 0), Money.usd(0), 0) }
        shouldThrow<IllegalArgumentException> { CostTotals(1, TokenUsage(0, 0), Money.usd(0), 2) }
        shouldThrow<IllegalArgumentException> {
            CostTotals(1, TokenUsage(0, 0), Money(1, Currency.getInstance("EUR")), 0)
        }
        shouldThrow<ArithmeticException> { CostTotals(Long.MAX_VALUE, TokenUsage(0, 0), Money.usd(0), 0) + totals(1) }
    }

    @Test
    fun `a breakdown sums the groups per task, provider kind and model in a fixed order`() {
        val groups =
            listOf(
                CostGroup(AiTask.CHAT, llama, totals(0, unknown = 1)),
                CostGroup(AiTask.CHAT, gpt, totals(30)),
                CostGroup(AiTask.SCANNER_PRE_SCORING, gpt, totals(20)),
            )

        val breakdown = CostBreakdown.of(september, groups)

        breakdown.total shouldBe CostTotals(4, TokenUsage(30, 3), Money.usd(50), 1)
        breakdown.byTask.keys.toList() shouldBe listOf(AiTask.SCANNER_PRE_SCORING, AiTask.CHAT)
        breakdown.byTask[AiTask.CHAT] shouldBe CostTotals(3, TokenUsage(20, 2), Money.usd(30), 1)
        breakdown.byProviderKind.keys.toList() shouldBe listOf(ProviderKind.OPENAI, ProviderKind.OPENAI_COMPATIBLE)
        breakdown.byModel.keys.toList() shouldBe listOf(gpt, llama)
        breakdown.byModel[gpt] shouldBe CostTotals(2, TokenUsage(20, 2), Money.usd(50), 0)
        CostBreakdown.of(september, emptyList()).total shouldBe CostTotals.NONE
    }

    @Test
    fun `months are parsed as YYYY-MM between 2000 and the current month`() {
        BillingMonth.parse("2026-09", september) shouldBe SetupValidation.Valid(september)
        BillingMonth.parse("2000-01", september) shouldBe SetupValidation.Valid(BillingMonth.EARLIEST)
        BillingMonth.parse("2026-10", september) shouldBe invalid(SetupField.MONTH, SetupViolationKind.OUT_OF_RANGE)
        BillingMonth.parse("26-09", september) shouldBe invalid(SetupField.MONTH, SetupViolationKind.INVALID_FORMAT)
        september.toString() shouldBe "2026-09"
        september.minus(9) shouldBe BillingMonth(YearMonth.of(2025, 12))
    }

    @Test
    fun `a history covers one month to two years, oldest first`() {
        CostHistory.months(1, september) shouldBe SetupValidation.Valid(listOf(september))
        (CostHistory.months(CostHistory.MAX_MONTHS, september) as SetupValidation.Valid).value.first() shouldBe
            BillingMonth(YearMonth.of(2024, 10))
        CostHistory.months(0, september) shouldBe invalid(SetupField.MONTHS, SetupViolationKind.OUT_OF_RANGE)
    }

    @Test
    fun `a cap is between one micro and one million dollars`() {
        MonthlyBudget.validate(1) shouldBe SetupValidation.Valid(MonthlyBudget(Money.usd(1)))
        MonthlyBudget.validate(MonthlyBudget.MAX_CAP.micros) shouldBe
            SetupValidation.Valid(MonthlyBudget(MonthlyBudget.MAX_CAP))
        MonthlyBudget.MAX_CAP.amount.toPlainString() shouldBe "1000000.000000"
        MonthlyBudget.validate(0) shouldBe invalid(SetupField.MONTHLY_CAP, SetupViolationKind.OUT_OF_RANGE)
        MonthlyBudget.validate(-1) shouldBe invalid(SetupField.MONTHLY_CAP, SetupViolationKind.OUT_OF_RANGE)
        MonthlyBudget.validate(MonthlyBudget.MAX_CAP.micros + 1) shouldBe
            invalid(SetupField.MONTHLY_CAP, SetupViolationKind.OUT_OF_RANGE)
    }

    @Test
    fun `usage above the cap leaves nothing and pauses only the non-essential tasks until the month ends`() {
        val over = BudgetUsage(september, MonthlyBudget(Money.usd(100)), Money.usd(150))

        over.status shouldBe BudgetStatus.Reached
        over.remaining shouldBe Money.usd(0)
        over.pausedTasks shouldBe MonthlyBudget.NON_ESSENTIAL_TASKS
        september.end shouldBe Instant.parse("2026-10-01T00:00:00Z")

        val within = BudgetUsage(september, MonthlyBudget(Money.usd(100)), Money.usd(99))
        within.remaining shouldBe Money.usd(1)
        within.pausedTasks.shouldBeEmpty()
        shouldThrow<IllegalArgumentException> { BudgetUsage(september, null, Money(1, Currency.getInstance("EUR"))) }
    }

    private fun invalid(
        field: SetupField,
        kind: SetupViolationKind,
    ) = SetupValidation.Invalid(listOf(SetupViolation(field, kind)))
}
