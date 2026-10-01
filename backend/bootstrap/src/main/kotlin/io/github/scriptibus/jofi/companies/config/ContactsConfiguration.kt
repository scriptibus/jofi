// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.config

import io.github.scriptibus.jofi.companies.application.CreateContactUseCase
import io.github.scriptibus.jofi.companies.application.DeleteContactUseCase
import io.github.scriptibus.jofi.companies.application.FindContactLinksUseCase
import io.github.scriptibus.jofi.companies.application.GetContactUseCase
import io.github.scriptibus.jofi.companies.application.SearchContactsUseCase
import io.github.scriptibus.jofi.companies.application.UpdateContactUseCase
import io.github.scriptibus.jofi.companies.application.port.ContactRepositoryPort
import io.github.scriptibus.jofi.companies.application.port.spi.LinkedApplicationsPort
import io.github.scriptibus.jofi.companies.application.port.spi.TaskLinksPort
import io.github.scriptibus.jofi.companies.config.CompaniesConfiguration.CompanyAudit
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.DomainEventPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The contact use cases (#89), writing with the companies context's [CompanyAudit].
 * `LinkedApplicationsPort` comes from the applications context, `TaskLinksPort` from the tasks context (ADR-0041).
 */
@Configuration(proxyBeanMethods = false)
class ContactsConfiguration {
    @Bean
    fun createContactUseCase(
        contacts: ContactRepositoryPort,
        audit: CompanyAudit,
    ): CreateContactUseCase = CreateContactUseCase(contacts, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun updateContactUseCase(
        contacts: ContactRepositoryPort,
        audit: CompanyAudit,
    ): UpdateContactUseCase = UpdateContactUseCase(contacts, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun getContactUseCase(contacts: ContactRepositoryPort): GetContactUseCase = GetContactUseCase(contacts)

    @Bean
    fun searchContactsUseCase(contacts: ContactRepositoryPort): SearchContactsUseCase = SearchContactsUseCase(contacts)

    @Bean
    fun findContactLinksUseCase(
        applications: LinkedApplicationsPort,
        tasks: TaskLinksPort,
    ): FindContactLinksUseCase = FindContactLinksUseCase(applications, tasks)

    @Bean
    fun deleteContactUseCase(
        contacts: ContactRepositoryPort,
        links: FindContactLinksUseCase,
        confirmation: ConfirmActionUseCase,
        events: DomainEventPort,
        audit: CompanyAudit,
    ): DeleteContactUseCase =
        DeleteContactUseCase(
            contacts,
            links,
            confirmation,
            events,
            audit.changelog,
            audit.transactions,
            audit.clock,
        )
}
