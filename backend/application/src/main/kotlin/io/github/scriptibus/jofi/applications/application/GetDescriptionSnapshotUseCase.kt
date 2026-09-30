// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.DescriptionSnapshotRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.GetDescriptionSnapshotPort
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.DescriptionSnapshot
import io.github.scriptibus.jofi.applications.domain.SnapshotId

/** One version of one of the application's descriptions, with its text (ADR-0046). */
class GetDescriptionSnapshotUseCase(
    private val applications: ApplicationRepositoryPort,
    private val snapshots: DescriptionSnapshotRepositoryPort,
) : GetDescriptionSnapshotPort {
    override fun execute(
        id: ApplicationId,
        snapshot: SnapshotId,
    ): ApplicationResult<DescriptionSnapshot> =
        applications.findById(id).toResult().then { snapshots.findById(id, snapshot).snapshotResult() }
}
