// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.config

import io.github.scriptibus.jofi.shared.adapter.jobs.AllowlistJobMapper
import io.github.scriptibus.jofi.shared.adapter.jobs.JobStore
import org.jobrunr.jobs.mappers.JobMapper
import org.jobrunr.storage.StorageProvider
import org.jobrunr.utils.mapper.JsonMapper
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/**
 * JobRunr's job store (ADR-0010, ADR-0038). These beans replace the starter's defaults: the Jackson 3
 * mapper with the class allowlist, the job mapper that quarantines foreign job details, and the
 * PostgreSQL storage on the tables of our Flyway migration. Whether jobs run here is the
 * `jobrunr.background-job-server.enabled` property: only the `worker` profile sets it.
 */
@Configuration(proxyBeanMethods = false)
class JobsConfiguration {
    @Bean
    fun jobRunrJsonMapper(): JsonMapper = JobStore.jsonMapper()

    @Bean
    fun jobMapper(jobRunrJsonMapper: JsonMapper): JobMapper = AllowlistJobMapper(jobRunrJsonMapper)

    @Bean(destroyMethod = "close")
    @DependsOnDatabaseInitialization
    fun storageProvider(
        dataSource: DataSource,
        jobMapper: JobMapper,
    ): StorageProvider = JobStore.storageProvider(dataSource, jobMapper)
}
