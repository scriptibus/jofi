// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.DescriptionSnapshotRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.DiffDescriptionSnapshotsPort
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.DescriptionDiff
import io.github.scriptibus.jofi.applications.domain.SnapshotId

/**
 * The line diff between two versions of the application's descriptions, of one source or of two (ADR-0046).
 * Reads only, outside any transaction; the diff's work is bounded ([DescriptionDiff.between]).
 */
class DiffDescriptionSnapshotsUseCase(
    private val applications: ApplicationRepositoryPort,
    private val snapshots: DescriptionSnapshotRepositoryPort,
) : DiffDescriptionSnapshotsPort {
    override fun execute(
        id: ApplicationId,
        from: SnapshotId,
        to: SnapshotId,
    ): ApplicationResult<DescriptionDiff> =
        applications.findById(id).toResult().then {
            snapshots.findById(id, from).snapshotResult().then { old ->
                snapshots.findById(id, to).snapshotResult().then { new ->
                    ApplicationResult.Success(DescriptionDiff.between(old, new))
                }
            }
        }
}
