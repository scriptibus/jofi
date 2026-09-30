// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application.port

import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.system.domain.backup.BackupContents
import io.github.scriptibus.jofi.system.domain.backup.BackupExportResult
import io.github.scriptibus.jofi.system.domain.backup.BackupId
import io.github.scriptibus.jofi.system.domain.backup.BackupUnpackResult
import io.github.scriptibus.jofi.system.domain.backup.BackupWorkspace
import io.github.scriptibus.jofi.system.domain.backup.InterruptedRestore
import io.github.scriptibus.jofi.system.domain.backup.MasterKeysetCopy
import io.github.scriptibus.jofi.system.domain.backup.StagedBackup
import java.io.InputStream
import java.io.OutputStream

/**
 * Backup archives (ADR-0042): work directories in the data volume, the zip format, and the data
 * volume's files (knowledge, documents). Implementations never throw.
 */
interface BackupArchivePort {
    /** A new, empty work directory, or `null` when the data volume cannot hold one. */
    fun newWorkspace(): BackupWorkspace?

    /**
     * Writes the archive: the table dumps in [workspace], the keyset and the data volume's files,
     * each with its checksum, then the manifest. [target] is opened only once the archive starts,
     * so a failure before that leaves the response untouched.
     */
    fun write(
        workspace: BackupWorkspace,
        contents: BackupContents,
        target: () -> OutputStream,
    ): BackupExportResult

    /**
     * Unpacks an uploaded archive into a new work directory under the size, entry and path limits,
     * replacing any backup staged before. Nothing outside that directory is touched.
     */
    fun unpack(upload: InputStream): BackupUnpackResult

    /** Replaces the staged backup with [backup] (e.g. its migrated dumps), only if its id is still the staged one. */
    fun keep(backup: StagedBackup)

    /** The staged backup [id], or `null` when there is none. */
    fun find(id: BackupId): StagedBackup?

    /**
     * Marks [backup]'s work directory as a restore in progress and keeps a copy of the keyset in use
     * ([previousKeyset], owner-only), before any file or the keyset is replaced. `false` when it could not.
     */
    fun beginRestore(
        backup: StagedBackup,
        previousKeyset: MasterKeysetCopy?,
    ): Boolean

    /** Every marked restore that has not ended, or `null` when the work directories cannot be read. */
    fun interruptedRestores(): List<InterruptedRestore>?

    /** Swaps the data volume's files for the backup's; the previous ones are kept until [discard]. */
    fun installFiles(
        backup: StagedBackup,
        proof: ConfirmationResult.Confirmed,
    ): Boolean

    /**
     * Puts the previous files back after a partly or fully done [installFiles] of restore [id], also
     * after a crash in the middle of it. `false` when that failed.
     */
    fun revertFiles(id: BackupId): Boolean

    /** Ends the marked restore [id] after it was rolled back: the marker and the keyset copy go, the upload stays. */
    fun endRestore(id: BackupId)

    /** Removes the work directory [id] and everything in it. */
    fun discard(id: BackupId)
}
