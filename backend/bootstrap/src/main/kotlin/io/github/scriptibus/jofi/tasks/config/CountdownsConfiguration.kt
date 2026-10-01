// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.config

import io.github.scriptibus.jofi.applications.application.port.api.FindCountdownFactsPort
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.tasks.application.CreateCountdownUseCase
import io.github.scriptibus.jofi.tasks.application.DeleteCountdownUseCase
import io.github.scriptibus.jofi.tasks.application.ListCountdownsUseCase
import io.github.scriptibus.jofi.tasks.application.ListDashboardCountdownsUseCase
import io.github.scriptibus.jofi.tasks.application.UpdateCountdownUseCase
import io.github.scriptibus.jofi.tasks.application.port.CountdownRepositoryPort
import io.github.scriptibus.jofi.tasks.config.TasksConfiguration.TaskAudit
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The countdown use cases (#112): custom countdowns and the dashboard query, which reads the applications context
 * through its named interface `api`.
 */
@Configuration(proxyBeanMethods = false)
class CountdownsConfiguration {
    @Bean
    fun createCountdownUseCase(
        countdowns: CountdownRepositoryPort,
        audit: TaskAudit,
    ): CreateCountdownUseCase = CreateCountdownUseCase(countdowns, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun updateCountdownUseCase(
        countdowns: CountdownRepositoryPort,
        audit: TaskAudit,
    ): UpdateCountdownUseCase = UpdateCountdownUseCase(countdowns, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun deleteCountdownUseCase(
        countdowns: CountdownRepositoryPort,
        confirmation: ConfirmActionUseCase,
        audit: TaskAudit,
    ): DeleteCountdownUseCase =
        DeleteCountdownUseCase(countdowns, confirmation, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun listCountdownsUseCase(countdowns: CountdownRepositoryPort): ListCountdownsUseCase =
        ListCountdownsUseCase(countdowns)

    @Bean
    fun listDashboardCountdownsUseCase(
        countdowns: CountdownRepositoryPort,
        facts: FindCountdownFactsPort,
        clock: Clock,
    ): ListDashboardCountdownsUseCase = ListDashboardCountdownsUseCase(countdowns, facts, clock)
}
