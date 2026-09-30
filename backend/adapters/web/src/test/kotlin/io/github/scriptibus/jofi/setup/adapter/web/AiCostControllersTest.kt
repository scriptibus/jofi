// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.web

import io.github.scriptibus.jofi.setup.application.GetCostHistoryUseCase
import io.github.scriptibus.jofi.setup.application.GetCostSummaryUseCase
import io.github.scriptibus.jofi.setup.application.GetMonthlyBudgetUseCase
import io.github.scriptibus.jofi.setup.application.SetMonthlyBudgetUseCase
import io.github.scriptibus.jofi.setup.application.port.CostEntryPort
import io.github.scriptibus.jofi.setup.application.port.MonthlyBudgetPort
import io.github.scriptibus.jofi.setup.domain.BillingMonth
import io.github.scriptibus.jofi.setup.domain.CostGroup
import io.github.scriptibus.jofi.setup.domain.CostTotals
import io.github.scriptibus.jofi.setup.domain.ModelKey
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.Money
import io.github.scriptibus.jofi.setup.domain.MonthlyBudget
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
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
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * The cost and budget endpoints over the real use cases with mocked ports: mapping, validation and
 * problem details. Session and CSRF are the filter chain's job, tested in bootstrap (`AiCostFlowTest`).
 */
@WebMvcTest(
    AiCostController::class,
    MonthlyBudgetController::class,
    properties = ["spring.mvc.problemdetails.enabled=true"],
)
@AutoConfigureMockMvc(addFilters = false)
@Import(AiCostControllersTest.UseCases::class)
class AiCostControllersTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val ports: Ports,
) {
    class Ports {
        val costs = mockk<CostEntryPort>()
        val budgets = mockk<MonthlyBudgetPort>()
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
        private val clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC)

        @Bean
        fun ports() = Ports()

        @Bean
        fun summary(ports: Ports) = GetCostSummaryUseCase(ports.costs, ports.budgets, clock)

        @Bean
        fun history(ports: Ports) = GetCostHistoryUseCase(ports.costs, clock)

        @Bean
        fun budget(ports: Ports) = GetMonthlyBudgetUseCase(ports.costs, ports.budgets, clock)

        @Bean
        fun setBudget(ports: Ports) =
            SetMonthlyBudgetUseCase(ports.budgets, ports.costs, ports.changelog, ports.transactions, clock)
    }

    private val json = JsonMapper.builder().build()
    private val september = BillingMonth(YearMonth.of(2026, 9))
    private val gpt = ModelKey(ProviderKind.OPENAI, ModelName("gpt-5-mini"))
    private val llama = ModelKey(ProviderKind.OPENAI_COMPATIBLE, ModelName("llama3.1"))

    @BeforeEach
    fun answer() {
        clearMocks(ports.costs, ports.budgets, ports.changelog)
        every { ports.costs.summarizeBetween(any(), any()) } returns
            SetupStoreResult.Success(
                listOf(
                    CostGroup(AiTask.CHAT, gpt, CostTotals(2, TokenUsage(200, 20), Money.usd(3_000_000), 0)),
                    CostGroup(AiTask.SCANNER_PRE_SCORING, llama, CostTotals(5, TokenUsage(500, 50), Money.usd(0), 5)),
                ),
            )
        every { ports.costs.totalBetween(any(), any()) } returns SetupStoreResult.Success(Money.usd(3_000_000))
        every { ports.costs.totalsByMonth(any(), any()) } returns
            SetupStoreResult.Success(mapOf(september to CostTotals(1, TokenUsage(1, 1), Money.usd(7), 0)))
        every { ports.budgets.find() } returns SetupStoreResult.Success(MonthlyBudget(Money.usd(2_000_000)))
        every { ports.budgets.save(any()) } returns SetupStoreResult.Success(Unit)
        every { ports.budgets.clear() } returns SetupStoreResult.Success(Unit)
        every { ports.changelog.append(any()) } returns ChangelogResult.Success(Unit)
    }

    private fun body(result: MvcTestResult): JsonNode = json.readTree(result.response.contentAsString)

    private fun absent(
        node: JsonNode,
        field: String,
    ): Boolean = !node.has(field) || node[field].isNull

    private fun violation(result: MvcTestResult): Pair<String, String> {
        result.response.status shouldBe 400
        result.response.contentType shouldBe MediaType.APPLICATION_PROBLEM_JSON_VALUE
        val problem = body(result)
        problem["type"].asString() shouldBe SetupProblems.INVALID
        val first = problem["violations"][0]
        return first["field"].asString() to first["problem"].asString()
    }

    @Test
    fun `the current month's summary has every breakdown, unknown-cost calls and the budget`() {
        val result = mvc.get().uri("/api/setup/costs").exchange()

        result.response.status shouldBe 200
        val summary = body(result)
        summary["month"].asString() shouldBe "2026-09"
        summary["currency"].asString() shouldBe "USD"
        summary["total"]["calls"].asLong() shouldBe 7
        summary["total"]["knownCostMicros"].asLong() shouldBe 3_000_000
        summary["total"]["unknownCostCalls"].asLong() shouldBe 5
        summary["byTask"][0]["task"].asString() shouldBe "SCANNER_PRE_SCORING"
        summary["byProviderKind"][1]["providerKind"].asString() shouldBe "OPENAI_COMPATIBLE"
        summary["byModel"][0]["model"].asString() shouldBe "gpt-5-mini"
        val budget = summary["budget"]
        budget["capMicros"].asLong() shouldBe 2_000_000
        budget["remainingMicros"].asLong() shouldBe 0
        budget["state"].asString() shouldBe "REACHED"
        budget["pausedTasks"][0].asString() shouldBe "SCANNER_PRE_SCORING"
        budget["pausedUntil"].asString() shouldBe "2026-10-01T00:00:00Z"
    }

    @Test
    fun `a past month's summary carries no budget`() {
        val result = mvc.get().uri("/api/setup/costs?month=2026-08").exchange()

        result.response.status shouldBe 200
        body(result)["month"].asString() shouldBe "2026-08"
        absent(body(result), "budget") shouldBe true
        verify(exactly = 0) { ports.budgets.find() }
    }

    @Test
    fun `a malformed or future month is a validation problem`() {
        violation(mvc.get().uri("/api/setup/costs?month=2026-13").exchange()) shouldBe ("month" to "INVALID_FORMAT")
        violation(mvc.get().uri("/api/setup/costs?month=2026-10").exchange()) shouldBe ("month" to "OUT_OF_RANGE")
    }

    @Test
    fun `the history defaults to twelve months, oldest first, and refuses more than two years`() {
        val result = mvc.get().uri("/api/setup/costs/history").exchange()

        result.response.status shouldBe 200
        val months: Iterable<JsonNode> = body(result)
        months.map { it["month"].asString() }.let {
            it.size shouldBe 12
            it.first() shouldBe "2025-10"
            it.last() shouldBe "2026-09"
        }
        months.last()["totals"]["knownCostMicros"].asLong() shouldBe 7
        violation(mvc.get().uri("/api/setup/costs/history?months=25").exchange()) shouldBe ("months" to "OUT_OF_RANGE")
        mvc
            .get()
            .uri("/api/setup/costs/history?months=many")
            .exchange()
            .response.status shouldBe 400
    }

    @Test
    fun `the budget shows spending, remaining amount and the pause`() {
        every { ports.budgets.find() } returns SetupStoreResult.NotFound

        val result = mvc.get().uri("/api/setup/budget").exchange()

        result.response.status shouldBe 200
        val budget = body(result)
        budget["state"].asString() shouldBe "NO_CAP"
        budget["spentMicros"].asLong() shouldBe 3_000_000
        absent(budget, "capMicros") shouldBe true
        budget["pausedTasks"].size() shouldBe 0
    }

    @Test
    fun `setting the cap stores it as the user and answers the new usage`() {
        every { ports.budgets.find() } returnsMany
            listOf(SetupStoreResult.NotFound, SetupStoreResult.Success(MonthlyBudget(Money.usd(25_000_000))))

        val result =
            mvc
                .put()
                .uri("/api/setup/budget")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"capMicros":25000000}""")
                .exchange()

        result.response.status shouldBe 200
        body(result)["state"].asString() shouldBe "WITHIN_BUDGET"
        body(result)["remainingMicros"].asLong() shouldBe 22_000_000
        verify { ports.budgets.save(MonthlyBudget(Money.usd(25_000_000))) }
        verify { ports.changelog.append(match { it.actor == Actor.User && it.entity == MonthlyBudget.ENTITY_REF }) }
    }

    @Test
    fun `a null cap removes it`() {
        every { ports.budgets.find() } returnsMany
            listOf(SetupStoreResult.Success(MonthlyBudget(Money.usd(5))), SetupStoreResult.NotFound)

        val result =
            mvc
                .put()
                .uri("/api/setup/budget")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"capMicros":null}""")
                .exchange()

        result.response.status shouldBe 200
        body(result)["state"].asString() shouldBe "NO_CAP"
        verify { ports.budgets.clear() }
    }

    @Test
    fun `a body without capMicros is refused and never clears the cap`() {
        listOf("{}", "{\"cap\":null}").forEach { request ->
            val result =
                mvc
                    .put()
                    .uri("/api/setup/budget")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request)
                    .exchange()
            violation(result) shouldBe ("capMicros" to "REQUIRED")
        }
        verify(exactly = 0) { ports.budgets.clear() }
        verify(exactly = 0) { ports.budgets.save(any()) }
        verify(exactly = 0) { ports.changelog.append(any()) }
    }

    @Test
    fun `a negative, zero or absurd cap is a validation problem and changes nothing`() {
        listOf("-1", "0", "1000000000001").forEach { cap ->
            val result =
                mvc
                    .put()
                    .uri("/api/setup/budget")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"capMicros":$cap}""")
                    .exchange()
            violation(result) shouldBe ("capMicros" to "OUT_OF_RANGE")
        }
        val tooBig =
            mvc
                .put()
                .uri("/api/setup/budget")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"capMicros":1e40}""")
                .exchange()
        tooBig.response.status shouldBe 400
        tooBig.response.contentType shouldBe MediaType.APPLICATION_PROBLEM_JSON_VALUE
        verify(exactly = 0) { ports.budgets.save(any()) }
        verify(exactly = 0) { ports.changelog.append(any()) }
    }

    @Test
    fun `an unreadable store answers 503 without details`() {
        every { ports.costs.summarizeBetween(any(), any()) } returns SetupStoreResult.StorageFailure("summarizeBetween")

        val result = mvc.get().uri("/api/setup/costs").exchange()

        result.response.status shouldBe 503
        body(result)["type"].asString() shouldBe SetupProblems.UNAVAILABLE
        result.response.contentAsString.contains("summarizeBetween") shouldBe false
    }
}
