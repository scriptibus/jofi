// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.config

import io.github.scriptibus.jofi.setup.application.ClearModelPriceUseCase
import io.github.scriptibus.jofi.setup.application.GetCostHistoryUseCase
import io.github.scriptibus.jofi.setup.application.GetCostSummaryUseCase
import io.github.scriptibus.jofi.setup.application.GetMonthlyBudgetUseCase
import io.github.scriptibus.jofi.setup.application.ListModelPricesUseCase
import io.github.scriptibus.jofi.setup.application.SetModelPriceUseCase
import io.github.scriptibus.jofi.setup.application.SetMonthlyBudgetUseCase
import io.github.scriptibus.jofi.setup.application.port.CostEntryPort
import io.github.scriptibus.jofi.setup.application.port.ModelPricePort
import io.github.scriptibus.jofi.setup.application.port.MonthlyBudgetPort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** The AI cost reports, the monthly budget cap (#24) and the user's model prices (#142). */
@Configuration(proxyBeanMethods = false)
class AiCostConfiguration {
    @Bean
    fun getCostSummaryUseCase(
        costs: CostEntryPort,
        budgets: MonthlyBudgetPort,
        clock: Clock,
    ): GetCostSummaryUseCase = GetCostSummaryUseCase(costs, budgets, clock)

    @Bean
    fun getCostHistoryUseCase(
        costs: CostEntryPort,
        clock: Clock,
    ): GetCostHistoryUseCase = GetCostHistoryUseCase(costs, clock)

    @Bean
    fun getMonthlyBudgetUseCase(
        costs: CostEntryPort,
        budgets: MonthlyBudgetPort,
        clock: Clock,
    ): GetMonthlyBudgetUseCase = GetMonthlyBudgetUseCase(costs, budgets, clock)

    @Bean
    fun setMonthlyBudgetUseCase(
        budgets: MonthlyBudgetPort,
        costs: CostEntryPort,
        audit: SetupConfiguration.SetupAudit,
    ): SetMonthlyBudgetUseCase =
        SetMonthlyBudgetUseCase(budgets, costs, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun listModelPricesUseCase(
        providers: ProviderConfigPort,
        prices: ModelPricePort,
    ): ListModelPricesUseCase = ListModelPricesUseCase(providers, prices)

    @Bean
    fun setModelPriceUseCase(
        providers: ProviderConfigPort,
        prices: ModelPricePort,
        audit: SetupConfiguration.SetupAudit,
    ): SetModelPriceUseCase = SetModelPriceUseCase(providers, prices, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun clearModelPriceUseCase(
        providers: ProviderConfigPort,
        prices: ModelPricePort,
        audit: SetupConfiguration.SetupAudit,
    ): ClearModelPriceUseCase =
        ClearModelPriceUseCase(providers, prices, audit.changelog, audit.transactions, audit.clock)
}
