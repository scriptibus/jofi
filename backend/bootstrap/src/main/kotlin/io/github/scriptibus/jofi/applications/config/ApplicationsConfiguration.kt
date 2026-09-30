// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.config

import io.github.scriptibus.jofi.applications.application.ChangeApplicationStatusUseCase
import io.github.scriptibus.jofi.applications.application.CreateApplicationUseCase
import io.github.scriptibus.jofi.applications.application.DeleteApplicationUseCase
import io.github.scriptibus.jofi.applications.application.GetApplicationStatusHistoryUseCase
import io.github.scriptibus.jofi.applications.application.GetApplicationUseCase
import io.github.scriptibus.jofi.applications.application.SetApplicationUnreadUseCase
import io.github.scriptibus.jofi.applications.application.UpdateApplicationUseCase
import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.DescriptionSnapshotRepositoryPort
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.DomainEventPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The application use cases: create, read, edit, read/unread and delete (#82); status change and history (#84).
 * The job description history has its own, [DescriptionSnapshotConfiguration].
 */
@Configuration(proxyBeanMethods = false)
class ApplicationsConfiguration {
    @Bean
    fun createApplicationUseCase(
        applications: ApplicationRepositoryPort,
        audit: ApplicationAudit,
    ): CreateApplicationUseCase =
        CreateApplicationUseCase(applications, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun updateApplicationUseCase(
        applications: ApplicationRepositoryPort,
        audit: ApplicationAudit,
    ): UpdateApplicationUseCase =
        UpdateApplicationUseCase(applications, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun getApplicationUseCase(applications: ApplicationRepositoryPort): GetApplicationUseCase =
        GetApplicationUseCase(applications)

    @Bean
    fun setApplicationUnreadUseCase(
        applications: ApplicationRepositoryPort,
        audit: ApplicationAudit,
    ): SetApplicationUnreadUseCase =
        SetApplicationUnreadUseCase(applications, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun deleteApplicationUseCase(
        applications: ApplicationRepositoryPort,
        confirmation: ConfirmActionUseCase,
        events: DomainEventPort,
        audit: ApplicationAudit,
    ): DeleteApplicationUseCase =
        DeleteApplicationUseCase(applications, confirmation, events, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun changeApplicationStatusUseCase(
        applications: ApplicationRepositoryPort,
        snapshots: DescriptionSnapshotRepositoryPort,
        events: DomainEventPort,
        audit: ApplicationAudit,
    ): ChangeApplicationStatusUseCase =
        ChangeApplicationStatusUseCase(
            applications,
            snapshots,
            events,
            audit.changelog,
            audit.transactions,
            audit.clock,
        )

    @Bean
    fun getApplicationStatusHistoryUseCase(
        applications: ApplicationRepositoryPort,
    ): GetApplicationStatusHistoryUseCase = GetApplicationStatusHistoryUseCase(applications)

    @Bean
    fun applicationAudit(
        changelog: ChangelogPort,
        transactions: TransactionPort,
        clock: Clock,
    ): ApplicationAudit = ApplicationAudit(changelog, transactions, clock)

    /** What every application mutation writes with: the changelog, in one transaction, at the clock's time. */
    class ApplicationAudit(
        val changelog: ChangelogPort,
        val transactions: TransactionPort,
        val clock: Clock,
    )
}
