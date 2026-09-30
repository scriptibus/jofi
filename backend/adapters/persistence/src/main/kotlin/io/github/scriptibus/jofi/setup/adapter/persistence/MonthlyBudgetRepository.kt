// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.persistence

import io.github.scriptibus.jofi.setup.application.port.MonthlyBudgetPort
import io.github.scriptibus.jofi.setup.domain.Money
import io.github.scriptibus.jofi.setup.domain.MonthlyBudget
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_MONTHLY_BUDGET
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.Currency

/** The optional monthly budget cap (`ai_monthly_budget`, at most one row). The AI gateway reads it. */
@Component
class MonthlyBudgetRepository(
    private val dsl: DSLContext,
) : MonthlyBudgetPort {
    override fun find(): SetupStoreResult<MonthlyBudget> =
        storeCall(log, "find") {
            dsl
                .selectFrom(AI_MONTHLY_BUDGET)
                .fetchOne()
                ?.let { MonthlyBudget(Money(it.capMicros, Currency.getInstance(it.currency))) }
                .foundOrNotFound()
        }

    override fun save(budget: MonthlyBudget): SetupStoreResult<Unit> =
        storeCall(log, "save") {
            dsl
                .insertInto(AI_MONTHLY_BUDGET)
                .set(AI_MONTHLY_BUDGET.SINGLETON, true)
                .set(AI_MONTHLY_BUDGET.CAP_MICROS, budget.cap.micros)
                .set(AI_MONTHLY_BUDGET.CURRENCY, budget.cap.currency.currencyCode)
                .onConflict(AI_MONTHLY_BUDGET.SINGLETON)
                .doUpdate()
                .set(AI_MONTHLY_BUDGET.CAP_MICROS, budget.cap.micros)
                .set(AI_MONTHLY_BUDGET.CURRENCY, budget.cap.currency.currencyCode)
                .execute()
            SetupStoreResult.Success(Unit)
        }

    override fun clear(): SetupStoreResult<Unit> =
        storeCall(log, "clear") {
            dsl.deleteFrom(AI_MONTHLY_BUDGET).execute()
            SetupStoreResult.Success(Unit)
        }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(MonthlyBudgetRepository::class.java)
    }
}
