// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.config

import io.github.scriptibus.jofi.companies.application.CreateCompanyUseCase
import io.github.scriptibus.jofi.companies.application.DeleteCompanyUseCase
import io.github.scriptibus.jofi.companies.application.FindCompanyLinksUseCase
import io.github.scriptibus.jofi.companies.application.GetCompanyUseCase
import io.github.scriptibus.jofi.companies.application.MatchCompanyUseCase
import io.github.scriptibus.jofi.companies.application.SearchCompaniesUseCase
import io.github.scriptibus.jofi.companies.application.SetCompanyPreferenceUseCase
import io.github.scriptibus.jofi.companies.application.UpdateCompanyUseCase
import io.github.scriptibus.jofi.companies.application.port.CompanyRepositoryPort
import io.github.scriptibus.jofi.companies.application.port.spi.ApplicationCountsPort
import io.github.scriptibus.jofi.companies.application.port.spi.LinkedApplicationsPort
import io.github.scriptibus.jofi.companies.application.port.spi.TaskLinksPort
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.DomainEventPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The company use cases (#88). `ApplicationCountsPort` comes from the applications context, `TaskLinksPort` from the
 * tasks context (ADR-0041).
 */
@Configuration(proxyBeanMethods = false)
class CompaniesConfiguration {
    @Bean
    fun createCompanyUseCase(
        companies: CompanyRepositoryPort,
        audit: CompanyAudit,
    ): CreateCompanyUseCase = CreateCompanyUseCase(companies, audit.changelog, audit.transactions, audit.clock)

    /** For the posting import of the applications context (named interface `api`, #96). */
    @Bean
    fun matchCompanyUseCase(
        companies: CompanyRepositoryPort,
        create: CreateCompanyUseCase,
    ): MatchCompanyUseCase = MatchCompanyUseCase(companies, create)

    @Bean
    fun updateCompanyUseCase(
        companies: CompanyRepositoryPort,
        applications: ApplicationCountsPort,
        audit: CompanyAudit,
    ): UpdateCompanyUseCase =
        UpdateCompanyUseCase(companies, applications, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun getCompanyUseCase(
        companies: CompanyRepositoryPort,
        applications: ApplicationCountsPort,
    ): GetCompanyUseCase = GetCompanyUseCase(companies, applications)

    @Bean
    fun searchCompaniesUseCase(
        companies: CompanyRepositoryPort,
        applications: ApplicationCountsPort,
    ): SearchCompaniesUseCase = SearchCompaniesUseCase(companies, applications)

    @Bean
    fun setCompanyPreferenceUseCase(
        companies: CompanyRepositoryPort,
        applications: ApplicationCountsPort,
        events: DomainEventPort,
        audit: CompanyAudit,
    ): SetCompanyPreferenceUseCase =
        SetCompanyPreferenceUseCase(companies, applications, events, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun findCompanyLinksUseCase(
        applications: ApplicationCountsPort,
        tasks: TaskLinksPort,
        linkedApplications: LinkedApplicationsPort,
    ): FindCompanyLinksUseCase = FindCompanyLinksUseCase(applications, tasks, linkedApplications)

    @Bean
    fun deleteCompanyUseCase(
        companies: CompanyRepositoryPort,
        links: FindCompanyLinksUseCase,
        confirmation: ConfirmActionUseCase,
        events: DomainEventPort,
        audit: CompanyAudit,
    ): DeleteCompanyUseCase =
        DeleteCompanyUseCase(
            companies,
            links,
            confirmation,
            events,
            audit.changelog,
            audit.transactions,
            audit.clock,
        )

    @Bean
    fun companyAudit(
        changelog: ChangelogPort,
        transactions: TransactionPort,
        clock: Clock,
    ): CompanyAudit = CompanyAudit(changelog, transactions, clock)

    /**
     * What every company and contact mutation writes with: the changelog, in one transaction, at the
     * clock's time.
     */
    class CompanyAudit(
        val changelog: ChangelogPort,
        val transactions: TransactionPort,
        val clock: Clock,
    )
}
