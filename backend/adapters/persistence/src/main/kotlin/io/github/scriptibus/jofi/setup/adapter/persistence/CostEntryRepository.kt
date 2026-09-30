// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.persistence

import io.github.scriptibus.jofi.setup.application.port.CostEntryPort
import io.github.scriptibus.jofi.setup.domain.CostEntry
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.Money
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_COST_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.AiCostEntryRecord
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
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
    }
}
