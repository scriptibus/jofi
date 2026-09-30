// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.SetupFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.setup.application.SetupFixtures.Companion.NOW
import io.github.scriptibus.jofi.setup.domain.BillingMonth
import io.github.scriptibus.jofi.setup.domain.BudgetStatus
import io.github.scriptibus.jofi.setup.domain.BudgetUsage
import io.github.scriptibus.jofi.setup.domain.CostSummary
import io.github.scriptibus.jofi.setup.domain.CostTotals
import io.github.scriptibus.jofi.setup.domain.ModelKey
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.Money
import io.github.scriptibus.jofi.setup.domain.MonthlyBudget
import io.github.scriptibus.jofi.setup.domain.MonthlyCostTotals
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.SetupField
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.setup.domain.SetupViolation
import io.github.scriptibus.jofi.setup.domain.SetupViolationKind
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

class CostUseCasesTest {
    private val setup = SetupFixtures()
    private val september = BillingMonth(YearMonth.of(2026, 9))

    private fun summary(clock: Clock = CLOCK) = GetCostSummaryUseCase(setup.costPort, setup.budgetPort, clock)

    private fun budget(clock: Clock = CLOCK) = GetMonthlyBudgetUseCase(setup.costPort, setup.budgetPort, clock)

    private val history = GetCostHistoryUseCase(setup.costPort, CLOCK)
    private val setBudget =
        SetMonthlyBudgetUseCase(setup.budgetPort, setup.costPort, setup.changelog, setup.transactions, CLOCK)

    private fun <T> success(result: SetupResult<T>): T = result.shouldBeInstanceOf<SetupResult.Success<T>>().value

    @Test
    fun `the current month sums per task, provider kind and model and counts unknown costs without pricing them`() {
        setup.cost(Instant.parse("2026-09-01T00:00:00Z"), 1_000, AiTask.CHAT, ProviderKind.OPENAI, "gpt-5-mini")
        setup.cost(Instant.parse("2026-09-15T10:00:00Z"), 2_000, AiTask.CHAT, ProviderKind.ANTHROPIC, "claude-x")
        setup.cost(NOW, null, AiTask.SCANNER_PRE_SCORING, ProviderKind.OPENAI_COMPATIBLE, "llama3.1")
        setup.cost(Instant.parse("2026-08-31T23:59:59.999999Z"), 5_000)
        setup.budget = MonthlyBudget(Money.usd(10_000))

        val report = success(summary().execute(null))

        report.costs.month shouldBe september
        report.costs.total shouldBe CostTotals(3, TokenUsage(300, 30), Money.usd(3_000), 1)
        report.costs.byTask.keys
            .toList() shouldBe listOf(AiTask.SCANNER_PRE_SCORING, AiTask.CHAT)
        report.costs.byTask[AiTask.SCANNER_PRE_SCORING] shouldBe CostTotals(1, TokenUsage(100, 10), Money.usd(0), 1)
        report.costs.byProviderKind.keys
            .toList() shouldBe
            listOf(ProviderKind.ANTHROPIC, ProviderKind.OPENAI, ProviderKind.OPENAI_COMPATIBLE)
        report.costs.byModel[ModelKey(ProviderKind.OPENAI, ModelName("gpt-5-mini"))]?.knownCost shouldBe
            Money.usd(1_000)
        report.budget shouldBe BudgetUsage(september, MonthlyBudget(Money.usd(10_000)), Money.usd(3_000))
    }

    @Test
    fun `a past month has its costs but no budget, since the cap has no history`() {
        setup.cost(Instant.parse("2026-08-31T23:59:59Z"), 5_000)
        setup.budget = MonthlyBudget(Money.usd(1))

        val report = success(summary().execute("2026-08"))

        report shouldBe
            CostSummary(
                report.costs,
                null,
            )
        report.costs.total.knownCost shouldBe Money.usd(5_000)
    }

    @ParameterizedTest
    @MethodSource("badMonths")
    fun `a month that is malformed, in the future or before 2000 is refused`(
        month: String,
        problem: SetupViolationKind,
    ) {
        summary().execute(month) shouldBe SetupResult.Invalid(listOf(SetupViolation(SetupField.MONTH, problem)))
    }

    @Test
    fun `a store failure is reported, not an empty report`() {
        setup.failingCostReads = true

        summary().execute(null).shouldBeInstanceOf<SetupResult.StorageFailure>()
        history.execute(3).shouldBeInstanceOf<SetupResult.StorageFailure>()
        budget().execute().shouldBeInstanceOf<SetupResult.StorageFailure>()
    }

    @Test
    fun `the history lists every month up to the current one, oldest first, empty months as zero`() {
        setup.cost(Instant.parse("2026-07-10T00:00:00Z"), 700)
        setup.cost(Instant.parse("2026-07-11T00:00:00Z"), null)
        setup.cost(NOW, 900)

        val months = success(history.execute(3))

        months.map { it.month.toString() } shouldBe listOf("2026-07", "2026-08", "2026-09")
        months[0].totals shouldBe CostTotals(2, TokenUsage(200, 20), Money.usd(700), 1)
        months[1] shouldBe MonthlyCostTotals(BillingMonth(YearMonth.of(2026, 8)), CostTotals.NONE)
        months[2].totals.knownCost shouldBe Money.usd(900)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1, 25, Int.MAX_VALUE])
    fun `a history of no months or more than two years is refused`(months: Int) {
        history.execute(months) shouldBe
            SetupResult.Invalid(listOf(SetupViolation(SetupField.MONTHS, SetupViolationKind.OUT_OF_RANGE)))
    }

    @Test
    fun `reaching the cap pauses scanner scoring until the month rolls over`() {
        setup.budget = MonthlyBudget(Money.usd(1_000))
        setup.cost(Instant.parse("2026-09-30T23:00:00Z"), 1_000, AiTask.SCANNER_PRE_SCORING)
        val lastSecond = Clock.fixed(Instant.parse("2026-09-30T23:59:59.999Z"), ZoneOffset.UTC)
        val nextMonth = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)

        val reached = success(budget(lastSecond).execute())
        reached.status shouldBe BudgetStatus.Reached
        reached.remaining shouldBe Money.usd(0)
        reached.pausedTasks shouldBe setOf(AiTask.SCANNER_PRE_SCORING)

        val lifted = success(budget(nextMonth).execute())
        lifted.month shouldBe BillingMonth(YearMonth.of(2026, 10))
        lifted.status shouldBe BudgetStatus.WithinBudget
        lifted.pausedTasks.shouldBeEmpty()
        success(summary(nextMonth).execute(null)).budget?.pausedTasks?.shouldBeEmpty()
    }

    @Test
    fun `without a cap nothing is paused`() {
        setup.cost(NOW, 1_000_000)

        val usage = success(budget().execute())

        usage shouldBe BudgetUsage(september, null, Money.usd(1_000_000))
        usage.status shouldBe null
        usage.remaining shouldBe null
        usage.pausedTasks.shouldBeEmpty()
    }

    @Test
    fun `the user sets, lowers and clears the cap, each change in the changelog`() {
        setup.cost(NOW, 2_000_000)

        val set = success(setBudget.execute(25_000_000, Actor.User))
        set.budget shouldBe MonthlyBudget(Money.usd(25_000_000))
        set.remaining shouldBe Money.usd(23_000_000)
        val lowered = success(setBudget.execute(1_000_000, Actor.User))
        lowered.pausedTasks shouldBe setOf(AiTask.SCANNER_PRE_SCORING)
        success(setBudget.execute(null, Actor.User)).budget shouldBe null

        setup.budget shouldBe null
        setup.entries.map { it.change.description } shouldBe
            listOf("Set the monthly AI budget", "Set the monthly AI budget", "Removed the monthly AI budget")
        setup.entries.map { it.change.fieldChanges.single() } shouldBe
            listOf(
                FieldChange("capUsd", null, "25.000000"),
                FieldChange("capUsd", "25.000000", "1.000000"),
                FieldChange("capUsd", "1.000000", null),
            )
        setup.entries.forEach {
            it.actor shouldBe Actor.User
            it.entity shouldBe MonthlyBudget.ENTITY_REF
            it.occurredAt shouldBe NOW
        }
    }

    @Test
    fun `an unchanged cap and clearing no cap write nothing`() {
        success(setBudget.execute(null, Actor.User))
        setup.budget = MonthlyBudget(Money.usd(5))
        success(setBudget.execute(5, Actor.User))

        setup.entries.shouldBeEmpty()
    }

    @ParameterizedTest
    @MethodSource("strangers")
    fun `only the user changes the cap`(actor: Actor) {
        setup.budget = MonthlyBudget(Money.usd(5))

        setBudget.execute(null, actor) shouldBe SetupResult.Forbidden
        setBudget.execute(1_000_000_000, actor) shouldBe SetupResult.Forbidden

        setup.budget shouldBe MonthlyBudget(Money.usd(5))
        setup.entries.shouldBeEmpty()
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1, -25_000_000, Long.MIN_VALUE, 1_000_000_000_001, Long.MAX_VALUE])
    fun `a cap that is not positive or above one million dollars is refused`(capMicros: Long) {
        setBudget.execute(capMicros, Actor.User) shouldBe
            SetupResult.Invalid(listOf(SetupViolation(SetupField.MONTHLY_CAP, SetupViolationKind.OUT_OF_RANGE)))
        setup.budget shouldBe null
    }

    @Test
    fun `the largest and the smallest cap are accepted`() {
        success(setBudget.execute(MonthlyBudget.MAX_CAP.micros, Actor.User)).budget?.cap shouldBe MonthlyBudget.MAX_CAP
        success(setBudget.execute(1, Actor.User)).budget?.cap shouldBe Money.usd(1)
    }

    @Test
    fun `a failing changelog rolls the cap back`() {
        setup.failingChangelog = true

        setBudget.execute(25_000_000, Actor.User) shouldBe SetupResult.StorageFailure("changelog")

        setup.budget shouldBe null
    }

    @Test
    fun `a failing store changes nothing and writes no changelog entry`() {
        setup.failingWrites = true

        setBudget.execute(25_000_000, Actor.User) shouldBe SetupResult.StorageFailure("save budget")

        setup.entries.shouldBeEmpty()
    }

    companion object {
        @JvmStatic
        fun badMonths() =
            listOf(
                Arguments.of("2026-9", SetupViolationKind.INVALID_FORMAT),
                Arguments.of("2026-13", SetupViolationKind.INVALID_FORMAT),
                Arguments.of("September", SetupViolationKind.INVALID_FORMAT),
                Arguments.of("", SetupViolationKind.INVALID_FORMAT),
                Arguments.of("+999999999-01", SetupViolationKind.INVALID_FORMAT),
                Arguments.of("2026-10", SetupViolationKind.OUT_OF_RANGE),
                Arguments.of("9999-12", SetupViolationKind.OUT_OF_RANGE),
                Arguments.of("1999-12", SetupViolationKind.OUT_OF_RANGE),
            )

        @JvmStatic
        fun strangers() =
            listOf(
                Actor.Ai,
                Actor.Scanner("stepstone-rss"),
                Actor.ExternalClient("claude-desktop"),
                Actor.System("job"),
            )
    }
}
