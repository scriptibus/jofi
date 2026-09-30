// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

import io.github.scriptibus.jofi.system.domain.backup.StagedBackup
import java.time.Instant
import java.util.UUID

/** An uploaded, checked backup waiting for the confirmed restore. */
data class StagedBackupResponse(
    val id: UUID,
    val createdAt: Instant,
    val appVersion: String,
    val schemaVersion: String,
    val rows: Long,
    val files: Int,
    val includesKeyset: Boolean,
    /** The schema version the backup was made with, when it was migrated to the running one. */
    val migratedFrom: String?,
) {
    companion object {
        fun from(backup: StagedBackup): StagedBackupResponse {
            val manifest = backup.manifest
            return StagedBackupResponse(
                id = backup.id.value,
                createdAt = manifest.createdAt,
                appVersion = manifest.appVersion,
                schemaVersion = manifest.schemaVersion.value,
                rows = manifest.rowCount,
                files = manifest.dataFileCount,
                includesKeyset = manifest.includesKeyset,
                migratedFrom = backup.migratedFrom?.value,
            )
        }
    }
}
