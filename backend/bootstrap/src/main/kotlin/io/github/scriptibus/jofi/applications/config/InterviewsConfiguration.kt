// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.config

import io.github.scriptibus.jofi.applications.application.DeleteInterviewUseCase
import io.github.scriptibus.jofi.applications.application.GetInterviewUseCase
import io.github.scriptibus.jofi.applications.application.ListInterviewsUseCase
import io.github.scriptibus.jofi.applications.application.LogInterviewUseCase
import io.github.scriptibus.jofi.applications.application.UpdateInterviewUseCase
import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.InterviewRepositoryPort
import io.github.scriptibus.jofi.applications.config.ApplicationsConfiguration.ApplicationAudit
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.DomainEventPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The interview and call use cases of an application (#91, ADR-0048): log, edit, read, list and delete. */
@Configuration(proxyBeanMethods = false)
class InterviewsConfiguration {
    @Bean
    fun logInterviewUseCase(
        applications: ApplicationRepositoryPort,
        interviews: InterviewRepositoryPort,
        events: DomainEventPort,
        audit: ApplicationAudit,
    ): LogInterviewUseCase =
        LogInterviewUseCase(applications, interviews, events, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun updateInterviewUseCase(
        applications: ApplicationRepositoryPort,
        interviews: InterviewRepositoryPort,
        events: DomainEventPort,
        audit: ApplicationAudit,
    ): UpdateInterviewUseCase =
        UpdateInterviewUseCase(applications, interviews, events, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun getInterviewUseCase(
        applications: ApplicationRepositoryPort,
        interviews: InterviewRepositoryPort,
    ): GetInterviewUseCase = GetInterviewUseCase(applications, interviews)

    @Bean
    fun listInterviewsUseCase(
        applications: ApplicationRepositoryPort,
        interviews: InterviewRepositoryPort,
    ): ListInterviewsUseCase = ListInterviewsUseCase(applications, interviews)

    @Bean
    fun deleteInterviewUseCase(
        applications: ApplicationRepositoryPort,
        interviews: InterviewRepositoryPort,
        confirmation: ConfirmActionUseCase,
        audit: ApplicationAudit,
    ): DeleteInterviewUseCase =
        DeleteInterviewUseCase(
            applications,
            interviews,
            confirmation,
            audit.changelog,
            audit.transactions,
            audit.clock,
        )
}
