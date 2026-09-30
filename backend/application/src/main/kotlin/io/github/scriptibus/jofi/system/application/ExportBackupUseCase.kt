// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangeSummary
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.system.application.port.BackupArchivePort
import io.github.scriptibus.jofi.system.application.port.BackupLockPort
import io.github.scriptibus.jofi.system.application.port.BuildInfoPort
import io.github.scriptibus.jofi.system.application.port.DatabaseBackupPort
import io.github.scriptibus.jofi.system.application.port.MasterKeyBackupPort
import io.github.scriptibus.jofi.system.domain.PasswordCheckResult
import io.github.scriptibus.jofi.system.domain.PasswordConfirmation
import io.github.scriptibus.jofi.system.domain.SystemStoreResult
import io.github.scriptibus.jofi.system.domain.backup.BackupContents
import io.github.scriptibus.jofi.system.domain.backup.BackupExportResult
import io.github.scriptibus.jofi.system.domain.backup.BackupRestore
import io.github.scriptibus.jofi.system.domain.backup.BackupWorkspace
import java.io.OutputStream
import java.time.Instant

/**
 * One-click backup (spec §3.1, ADR-0042): the database from one consistent snapshot, the master
 * keyset and the data volume's files, as one zip with a manifest. A backup grants full access to
 * everything in Jofi, secrets included, so it needs the current password (throttled like a login) and
 * every export is in the changelog. The database is dumped to the data volume first and then
 * streamed, so it is never held in memory and a failed dump leaves the target unopened. Exports may
 * run together, never next to an upload or a restore.
 */
class ExportBackupUseCase(
    private val archive: BackupArchivePort,
    private val database: DatabaseBackupPort,
    private val masterKey: MasterKeyBackupPort,
    private val buildInfo: BuildInfoPort,
    private val changelog: ChangelogPort,
    private val verifyPassword: VerifyPasswordUseCase,
    private val lock: BackupLockPort,
) {
    /**
     * [actor] exports (the web adapter says who: the logged-in user); [target] receives the creation
     * time, e.g. for the file name, and returns the stream to write to.
     */
    fun execute(
        actor: Actor,
        confirmation: PasswordConfirmation,
        target: (Instant) -> OutputStream,
    ): BackupExportResult {
        val check = verifyPassword.execute(confirmation)
        if (check != PasswordCheckResult.Verified) return BackupExportResult.PasswordRefused(check)
        return lock.shared { export(actor, target) } ?: BackupExportResult.Busy
    }

    private fun export(
        actor: Actor,
        target: (Instant) -> OutputStream,
    ): BackupExportResult {
        val workspace = archive.newWorkspace() ?: return BackupExportResult.Failed
        return try {
            val contents = contents(workspace)
            if (contents != null && recorded(actor, workspace, contents)) {
                archive.write(workspace, contents) { target(contents.createdAt) }
            } else {
                BackupExportResult.Failed
            }
        } finally {
            archive.discard(workspace.id)
        }
    }

    private fun contents(workspace: BackupWorkspace): BackupContents? {
        val dump = database.dump(workspace.databaseDirectory) as? SystemStoreResult.Success
        val keyset = dump?.let { masterKey.copy() as? SystemStoreResult.Success }
        return if (dump == null || keyset == null) {
            null
        } else {
            BackupContents(
                appVersion = buildInfo.applicationVersion(),
                schemaVersion = dump.value.schemaVersion,
                createdAt = dump.value.takenAt,
                tables = dump.value.tables,
                keyset = keyset.value,
            )
        }
    }

    // Recorded before anything leaves: an export that cannot be logged does not happen.
    private fun recorded(
        actor: Actor,
        workspace: BackupWorkspace,
        contents: BackupContents,
    ): Boolean {
        val description = "Exported a backup of ${contents.createdAt} (${contents.tables.sumOf { it.rows }} rows)"
        val entry =
            ChangelogEntry(
                BackupRestore.entityOf(workspace.id),
                actor,
                contents.createdAt,
                ChangeSummary(description),
            )
        return changelog.append(entry) is ChangelogResult.Success
    }
}
