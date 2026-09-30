// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.persistence

import io.github.scriptibus.jofi.setup.application.port.CostEntryPort
import io.github.scriptibus.jofi.setup.domain.BillingMonth
import io.github.scriptibus.jofi.setup.domain.CostEntry
import io.github.scriptibus.jofi.setup.domain.CostGroup
import io.github.scriptibus.jofi.setup.domain.CostTotals
import io.github.scriptibus.jofi.setup.domain.ModelKey
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.Money
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_COST_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.AiCostEntryRecord
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.util.Currency

/**
 * The append-only AI cost meter (`ai_cost_entry`; a trigger rejects updates and deletes). A NULL
 * `cost_micros` is an unknown cost (ADR-0043); sums skip it.
 */
@Component
class CostEntryRepository(
    private val dsl: DSLContext,
) : CostEntryPort {
    override fun append(entry: CostEntry): SetupStoreResult<Unit> =
        storeCall(log, "append") {
            dsl
                .insertInto(AI_COST_ENTRY)
                .set(AI_COST_ENTRY.TASK, entry.task.name)
                .set(AI_COST_ENTRY.PROVIDER_ID, entry.provider.value)
                .set(AI_COST_ENTRY.PROVIDER_KIND, entry.providerKind.name)
                .set(AI_COST_ENTRY.MODEL, entry.model.value)
                .set(AI_COST_ENTRY.INPUT_TOKENS, entry.usage.inputTokens)
                .set(AI_COST_ENTRY.OUTPUT_TOKENS, entry.usage.outputTokens)
                .set(AI_COST_ENTRY.COST_MICROS, entry.estimatedCost?.micros)
                .set(AI_COST_ENTRY.CURRENCY, Money.ACCOUNTING_CURRENCY.currencyCode)
                .set(AI_COST_ENTRY.OCCURRED_AT, entry.occurredAt.toUtc())
                .execute()
            SetupStoreResult.Success(Unit)
        }

    override fun findBetween(
        from: Instant,
        until: Instant,
    ): SetupStoreResult<List<CostEntry>> =
        storeCall(log, "findBetween") {
            SetupStoreResult.Success(
                dsl
                    .selectFrom(AI_COST_ENTRY)
                    .where(AI_COST_ENTRY.OCCURRED_AT.ge(from.toUtc()).and(AI_COST_ENTRY.OCCURRED_AT.lt(until.toUtc())))
                    .orderBy(AI_COST_ENTRY.OCCURRED_AT, AI_COST_ENTRY.ID)
                    .fetch()
                    .map(::toDomain),
            )
        }

    override fun totalBetween(
        from: Instant,
        until: Instant,
    ): SetupStoreResult<Money> =
        storeCall(log, "totalBetween") {
            val total =
                dsl
                    .select(DSL.sum(AI_COST_ENTRY.COST_MICROS))
                    .from(AI_COST_ENTRY)
                    .where(AI_COST_ENTRY.OCCURRED_AT.ge(from.toUtc()).and(AI_COST_ENTRY.OCCURRED_AT.lt(until.toUtc())))
                    .fetchOne(0, Long::class.java)
            // SUM skips NULL (unknown) costs and is NULL when no row has a cost.
            SetupStoreResult.Success(Money.usd(total ?: 0))
        }

    override fun summarizeBetween(
        from: Instant,
        until: Instant,
    ): SetupStoreResult<List<CostGroup>> =
        storeCall(log, "summarizeBetween") {
            val groups =
                dsl
                    .select(listOf(AI_COST_ENTRY.TASK, AI_COST_ENTRY.PROVIDER_KIND, AI_COST_ENTRY.MODEL) + TOTALS)
                    .from(AI_COST_ENTRY)
                    .where(between(from, until))
                    .groupBy(AI_COST_ENTRY.TASK, AI_COST_ENTRY.PROVIDER_KIND, AI_COST_ENTRY.MODEL)
                    .fetch { record ->
                        CostGroup(
                            task = AiTask.valueOf(record.get(AI_COST_ENTRY.TASK)),
                            model =
                                ModelKey(
                                    ProviderKind.valueOf(record.get(AI_COST_ENTRY.PROVIDER_KIND)),
                                    ModelName(record.get(AI_COST_ENTRY.MODEL)),
                                ),
                            totals = totalsOf(record),
                        )
                    }
            SetupStoreResult.Success(groups)
        }

    override fun totalsByMonth(
        first: BillingMonth,
        last: BillingMonth,
    ): SetupStoreResult<Map<BillingMonth, CostTotals>> =
        storeCall(log, "totalsByMonth") {
            // The month of the UTC time, whatever the session's time zone (BillingMonth, ADR-0043).
            val month =
                DSL.field(
                    "to_char({0} AT TIME ZONE 'UTC', 'YYYY-MM')",
                    String::class.java,
                    AI_COST_ENTRY.OCCURRED_AT,
                )
            val totals =
                dsl
                    .select(listOf(month) + TOTALS)
                    .from(AI_COST_ENTRY)
                    .where(between(first.start, last.end))
                    .groupBy(month)
                    .fetch()
                    .associate { BillingMonth(YearMonth.parse(it.get(month))) to totalsOf(it) }
            SetupStoreResult.Success(totals)
        }

    private fun between(
        from: Instant,
        until: Instant,
    ): Condition = AI_COST_ENTRY.OCCURRED_AT.ge(from.toUtc()).and(AI_COST_ENTRY.OCCURRED_AT.lt(until.toUtc()))

    private fun totalsOf(record: Record): CostTotals =
        CostTotals(
            calls = record.get(CALLS),
            usage = TokenUsage(exact(record.get(INPUT_TOKENS)), exact(record.get(OUTPUT_TOKENS))),
            // SUM skips NULL (unknown) costs and is NULL when no row of the group has a cost.
            knownCost = Money.usd(exact(record.get(KNOWN_COST))),
            unknownCostCalls = record.get(UNKNOWN_COST_CALLS),
        )

    private fun toDomain(record: AiCostEntryRecord): CostEntry =
        CostEntry(
            task = AiTask.valueOf(record.task),
            provider = ProviderId(record.providerId),
            providerKind = ProviderKind.valueOf(record.providerKind),
            model = ModelName(record.model),
            usage = TokenUsage(record.inputTokens, record.outputTokens),
            estimatedCost = record.costMicros?.let { Money(it, Currency.getInstance(record.currency)) },
            occurredAt = record.occurredAt.toInstant(),
        )

    private companion object {
        val log: Logger = LoggerFactory.getLogger(CostEntryRepository::class.java)

        val CALLS: Field<Long> = DSL.count().cast(SQLDataType.BIGINT).`as`("calls")
        val INPUT_TOKENS: Field<BigDecimal> = DSL.sum(AI_COST_ENTRY.INPUT_TOKENS).`as`("input_tokens")
        val OUTPUT_TOKENS: Field<BigDecimal> = DSL.sum(AI_COST_ENTRY.OUTPUT_TOKENS).`as`("output_tokens")
        val KNOWN_COST: Field<BigDecimal> = DSL.sum(AI_COST_ENTRY.COST_MICROS).`as`("known_cost_micros")
        val UNKNOWN_COST_CALLS: Field<Long> =
            DSL
                .count()
                .filterWhere(AI_COST_ENTRY.COST_MICROS.isNull)
                .cast(SQLDataType.BIGINT)
                .`as`("unknown_cost_calls")
        val TOTALS: List<Field<*>> = listOf(CALLS, INPUT_TOKENS, OUTPUT_TOKENS, KNOWN_COST, UNKNOWN_COST_CALLS)

        /** A SUM as a long; beyond a long it fails the call instead of wrapping around. */
        fun exact(sum: BigDecimal?): Long = sum?.longValueExact() ?: 0
    }
}
