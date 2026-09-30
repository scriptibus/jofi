// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.config

import io.github.scriptibus.jofi.applications.application.DiffDescriptionSnapshotsUseCase
import io.github.scriptibus.jofi.applications.application.GetDescriptionSnapshotUseCase
import io.github.scriptibus.jofi.applications.application.ListDescriptionSnapshotsUseCase
import io.github.scriptibus.jofi.applications.application.RecordDescriptionSnapshotUseCase
import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.DescriptionSnapshotRepositoryPort
import io.github.scriptibus.jofi.applications.config.ApplicationsConfiguration.ApplicationAudit
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The job description history (#86, ADR-0046): recording, listing, reading and diffing versions. */
@Configuration(proxyBeanMethods = false)
class DescriptionSnapshotConfiguration {
    @Bean
    fun recordDescriptionSnapshotUseCase(
        applications: ApplicationRepositoryPort,
        snapshots: DescriptionSnapshotRepositoryPort,
        audit: ApplicationAudit,
    ): RecordDescriptionSnapshotUseCase =
        RecordDescriptionSnapshotUseCase(applications, snapshots, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun listDescriptionSnapshotsUseCase(
        applications: ApplicationRepositoryPort,
        snapshots: DescriptionSnapshotRepositoryPort,
    ): ListDescriptionSnapshotsUseCase = ListDescriptionSnapshotsUseCase(applications, snapshots)

    @Bean
    fun getDescriptionSnapshotUseCase(
        applications: ApplicationRepositoryPort,
        snapshots: DescriptionSnapshotRepositoryPort,
    ): GetDescriptionSnapshotUseCase = GetDescriptionSnapshotUseCase(applications, snapshots)

    @Bean
    fun diffDescriptionSnapshotsUseCase(
        applications: ApplicationRepositoryPort,
        snapshots: DescriptionSnapshotRepositoryPort,
    ): DiffDescriptionSnapshotsUseCase = DiffDescriptionSnapshotsUseCase(applications, snapshots)
}
