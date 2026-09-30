// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.DescriptionSnapshotRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.ListDescriptionSnapshotsPort
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.SnapshotSummary
import io.github.scriptibus.jofi.applications.domain.SourceId

/** The versions of one of the application's sources, oldest first, without their texts (ADR-0046). */
class ListDescriptionSnapshotsUseCase(
    private val applications: ApplicationRepositoryPort,
    private val snapshots: DescriptionSnapshotRepositoryPort,
) : ListDescriptionSnapshotsPort {
    override fun execute(
        id: ApplicationId,
        source: SourceId,
    ): ApplicationResult<List<SnapshotSummary>> =
        applications
            .findById(id)
            .toResult()
            .then { it.withSource(source) }
            .then { snapshots.listBySource(source).toResult() }
}
