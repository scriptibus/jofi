// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.config

import io.github.scriptibus.jofi.applications.application.AddApplicationSourceUseCase
import io.github.scriptibus.jofi.applications.application.AddDiscoveredApplicationUseCase
import io.github.scriptibus.jofi.applications.application.FetchPostingTextUseCase
import io.github.scriptibus.jofi.applications.application.GetPostingImportUseCase
import io.github.scriptibus.jofi.applications.application.ResolveUrlImportUseCase
import io.github.scriptibus.jofi.applications.application.RetryPostingImportUseCase
import io.github.scriptibus.jofi.applications.application.RunPostingImportUseCase
import io.github.scriptibus.jofi.applications.application.StartPostingImportUseCase
import io.github.scriptibus.jofi.applications.application.StartUrlImportUseCase
import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.ApplicationSourceRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.PostingExtractionPort
import io.github.scriptibus.jofi.applications.application.port.PostingImportRepositoryPort
import io.github.scriptibus.jofi.applications.config.ApplicationsConfiguration.ApplicationAudit
import io.github.scriptibus.jofi.companies.application.port.api.MatchCompanyPort
import io.github.scriptibus.jofi.setup.application.port.api.CheckAiTaskAssignedPort
import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.shared.application.port.KeyedLockPort
import io.github.scriptibus.jofi.shared.application.port.OutboundHttpPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * Sources and the posting import (#96, #97): adding a source, a discovered application with its source, and the
 * import from pasted text or a URL. `MatchCompanyPort` and `CheckAiTaskAssignedPort` come from the companies and
 * setup contexts' named interfaces `api`; `OutboundHttpPort` from `adapters/net` (the SSRF guard).
 */
@Configuration(proxyBeanMethods = false)
class PostingImportConfiguration {
    @Bean
    fun addApplicationSourceUseCase(
        applications: ApplicationRepositoryPort,
        sources: ApplicationSourceRepositoryPort,
        audit: ApplicationAudit,
    ): AddApplicationSourceUseCase =
        AddApplicationSourceUseCase(applications, sources, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun addDiscoveredApplicationUseCase(
        applications: ApplicationRepositoryPort,
        sources: ApplicationSourceRepositoryPort,
        audit: ApplicationAudit,
    ): AddDiscoveredApplicationUseCase =
        AddDiscoveredApplicationUseCase(applications, sources, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun startPostingImportUseCase(
        imports: PostingImportRepositoryPort,
        ai: CheckAiTaskAssignedPort,
        jobs: JobSchedulerPort,
        audit: ApplicationAudit,
    ): StartPostingImportUseCase =
        StartPostingImportUseCase(imports, ai, jobs, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun fetchPostingTextUseCase(
        ai: CheckAiTaskAssignedPort,
        http: OutboundHttpPort,
        @Value("\${jofi.import.fetch-timeout:PT20S}") timeout: Duration,
    ): FetchPostingTextUseCase = FetchPostingTextUseCase(ai, http, timeout)

    @Bean
    fun resolveUrlImportUseCase(
        imports: PostingImportRepositoryPort,
        sources: ApplicationSourceRepositoryPort,
        fetch: FetchPostingTextUseCase,
        audit: ApplicationAudit,
    ): ResolveUrlImportUseCase =
        ResolveUrlImportUseCase(imports, sources, fetch, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun startUrlImportUseCase(
        resolve: ResolveUrlImportUseCase,
        locks: KeyedLockPort,
        imports: PostingImportRepositoryPort,
        jobs: JobSchedulerPort,
        audit: ApplicationAudit,
    ): StartUrlImportUseCase =
        StartUrlImportUseCase(resolve, locks, imports, jobs, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun getPostingImportUseCase(imports: PostingImportRepositoryPort): GetPostingImportUseCase =
        GetPostingImportUseCase(imports)

    @Bean
    fun retryPostingImportUseCase(
        imports: PostingImportRepositoryPort,
        ai: CheckAiTaskAssignedPort,
        jobs: JobSchedulerPort,
        audit: ApplicationAudit,
    ): RetryPostingImportUseCase =
        RetryPostingImportUseCase(imports, ai, jobs, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun runPostingImportUseCase(
        imports: PostingImportRepositoryPort,
        extraction: PostingExtractionPort,
        companies: MatchCompanyPort,
        discovered: AddDiscoveredApplicationUseCase,
        audit: ApplicationAudit,
    ): RunPostingImportUseCase =
        RunPostingImportUseCase(
            imports,
            extraction,
            companies,
            discovered,
            audit.changelog,
            audit.transactions,
            audit.clock,
        )
}
