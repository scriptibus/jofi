// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.persistence

import io.github.scriptibus.jofi.setup.domain.BillingMonth
import io.github.scriptibus.jofi.setup.domain.CostEntry
import io.github.scriptibus.jofi.setup.domain.CostGroup
import io.github.scriptibus.jofi.setup.domain.CostTotals
import io.github.scriptibus.jofi.setup.domain.ModelKey
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.Money
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

/** The cost report aggregations (#24) in SQL against a real PostgreSQL. */
class CostReportQueriesTest {
    private lateinit var dsl: DSLContext
    private lateinit var costs: CostEntryRepository

    private val september = BillingMonth(YearMonth.of(2026, 9))
    private val august = BillingMonth(YearMonth.of(2026, 8))
    private val openAi = ProviderId(UUID.randomUUID())
    private val gpt = ModelKey(ProviderKind.OPENAI, ModelName("gpt-5-mini"))

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        costs = CostEntryRepository(dsl)
    }

    private fun meter(
        at: String,
        micros: Long?,
        task: AiTask = AiTask.CHAT,
        provider: ProviderId = openAi,
        model: ModelKey = gpt,
    ) {
        val entry =
            CostEntry(
                task,
                provider,
                model.providerKind,
                model.model,
                TokenUsage(100, 10),
                micros?.let(Money::usd),
                Instant.parse(at),
            )
        costs.append(entry) shouldBe SetupStoreResult.Success(Unit)
    }

    private fun summary(month: BillingMonth): List<CostGroup> =
        (costs.summarizeBetween(month.start, month.end) as SetupStoreResult.Success).value

    private fun totals(
        calls: Long,
        micros: Long,
        unknown: Long = 0,
    ) = CostTotals(calls, TokenUsage(100 * calls, 10 * calls), Money.usd(micros), unknown)

    @Test
    fun `groups per task, provider kind and model sum known costs and count unknown ones`() {
        meter("2026-09-02T08:00:00Z", 1_500)
        meter("2026-09-03T08:00:00Z", 2_500)
        meter("2026-09-04T08:00:00Z", null)
        meter("2026-09-05T08:00:00Z", 700, AiTask.SCANNER_PRE_SCORING)

        summary(september) shouldContainExactlyInAnyOrder
            listOf(
                CostGroup(AiTask.CHAT, gpt, totals(3, 4_000, unknown = 1)),
                CostGroup(AiTask.SCANNER_PRE_SCORING, gpt, totals(1, 700)),
            )
    }

    @Test
    fun `a group whose costs are all unknown has zero cost, not a guess`() {
        meter("2026-09-02T08:00:00Z", null)
        meter("2026-09-03T08:00:00Z", null)

        summary(september).single().totals shouldBe totals(2, 0, unknown = 2)
    }

    @Test
    fun `months are UTC calendar months whatever the database session's time zone`() {
        meter("2026-08-31T23:59:59.999999Z", 1)
        meter("2026-09-01T00:00:00Z", 10)
        meter("2026-09-30T23:59:59.999999Z", 100)
        meter("2026-10-01T00:00:00Z", 1_000)

        // UTC+14: every entry above falls into the next day, and three of them into the next month.
        dsl.execute("SET TIME ZONE 'Pacific/Kiritimati'")
        try {
            summary(september).single().totals shouldBe totals(2, 110)
            summary(august).single().totals shouldBe totals(1, 1)
            costs.totalsByMonth(august, BillingMonth(YearMonth.of(2026, 10))) shouldBe
                SetupStoreResult.Success(
                    mapOf(
                        august to totals(1, 1),
                        september to totals(2, 110),
                        BillingMonth(YearMonth.of(2026, 10)) to totals(1, 1_000),
                    ),
                )
        } finally {
            dsl.execute("RESET TIME ZONE")
        }
    }

    @Test
    fun `the history covers exactly the months asked for and leaves out months without calls`() {
        meter("2026-06-30T23:59:59Z", 5)
        meter("2026-07-01T00:00:00Z", 7)
        meter("2026-07-15T00:00:00Z", null)
        meter("2026-09-10T00:00:00Z", 11)
        meter("2026-10-01T00:00:00Z", 13)

        costs.totalsByMonth(BillingMonth(YearMonth.of(2026, 7)), september) shouldBe
            SetupStoreResult.Success(
                mapOf(
                    BillingMonth(YearMonth.of(2026, 7)) to totals(2, 7, unknown = 1),
                    september to totals(1, 11),
                ),
            )
        costs.totalsByMonth(august, august) shouldBe SetupStoreResult.Success(emptyMap())
    }

    @Test
    fun `costs of a deleted provider stay attributed to its provider kind and model`() {
        val providers = ProviderConfigRepository(dsl)
        val local =
            ProviderConfig(
                ProviderId(UUID.randomUUID()),
                "Ollama",
                ProviderKind.OPENAI_COMPATIBLE,
                null,
                URI("http://ollama:11434/v1"),
            )
        val llama = ModelKey(ProviderKind.OPENAI_COMPATIBLE, ModelName("llama3.1"))
        providers.save(local)
        meter("2026-09-02T08:00:00Z", null, provider = local.id, model = llama)
        meter("2026-09-02T09:00:00Z", 0, provider = local.id, model = llama)

        providers.delete(local.id, ConfirmedProofs.of(ProviderId.DELETE_OPERATION, local.id.value.toString())) shouldBe
            SetupStoreResult.Success(Unit)

        summary(september) shouldBe listOf(CostGroup(AiTask.CHAT, llama, totals(2, 0, unknown = 1)))
    }

    @Test
    fun `the report and the budget check sum the same known costs`() {
        meter("2026-09-02T08:00:00Z", 1_500)
        meter("2026-09-03T08:00:00Z", null)

        costs.totalBetween(september.start, september.end) shouldBe SetupStoreResult.Success(Money.usd(1_500))
        summary(september).single().totals.knownCost shouldBe Money.usd(1_500)
    }

    @Test
    fun `a failing database is a storage failure`() {
        dsl.execute("DROP TABLE ai_cost_entry")

        costs.summarizeBetween(september.start, september.end) shouldBe
            SetupStoreResult.StorageFailure("summarizeBetween")
        costs.totalsByMonth(august, september) shouldBe SetupStoreResult.StorageFailure("totalsByMonth")
    }
}
