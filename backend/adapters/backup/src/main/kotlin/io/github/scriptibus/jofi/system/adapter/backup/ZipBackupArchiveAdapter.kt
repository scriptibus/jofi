// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.backup

import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.system.application.port.BackupArchivePort
import io.github.scriptibus.jofi.system.domain.backup.BackupContents
import io.github.scriptibus.jofi.system.domain.backup.BackupExportResult
import io.github.scriptibus.jofi.system.domain.backup.BackupId
import io.github.scriptibus.jofi.system.domain.backup.BackupPath
import io.github.scriptibus.jofi.system.domain.backup.BackupProblem
import io.github.scriptibus.jofi.system.domain.backup.BackupRestore
import io.github.scriptibus.jofi.system.domain.backup.BackupUnpackResult
import io.github.scriptibus.jofi.system.domain.backup.BackupWorkspace
import io.github.scriptibus.jofi.system.domain.backup.InterruptedRestore
import io.github.scriptibus.jofi.system.domain.backup.MasterKeysetCopy
import io.github.scriptibus.jofi.system.domain.backup.StagedBackup
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.util.unit.DataSize
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.util.zip.ZipException

/**
 * Backup archives in the data volume (ADR-0042, format documented there). At most one uploaded
 * backup waits for its confirmation at a time; a new upload replaces it, and a restart forgets it
 * (its directory is removed as stale later). Logs name operations and exception types only.
 */
@Component
class ZipBackupArchiveAdapter(
    @Value("\${jofi.data-dir}") dataDirectory: String,
    @Value("\${jofi.backup.max-upload-size}") maxUploadSize: DataSize,
    @Value("\${jofi.backup.max-unpacked-size}") maxUnpackedSize: DataSize,
    @Value("\${jofi.backup.max-entries}") maxEntries: Int,
    clock: Clock,
) : BackupArchivePort {
    private val dataDirectory: Path = Path.of(dataDirectory.trim())
    private val work = WorkDirectories(this.dataDirectory, clock)
    private val reader = BackupZipReader(BackupLimits(maxUploadSize.toBytes(), maxUnpackedSize.toBytes(), maxEntries))
    private val writer = BackupZipWriter(this.dataDirectory)
    private val files = DataFileSwap(this.dataDirectory)

    @Volatile
    private var staged: StagedBackup? = null

    init {
        require(this.dataDirectory.isAbsolute) { "JOFI_DATA_DIR must be an absolute path" }
    }

    override fun newWorkspace(): BackupWorkspace? =
        try {
            work.create()
        } catch (exception: IOException) {
            failed("create a work directory", exception)
            null
        }

    // Any failure while streaming (client gone, disk, a file vanishing) ends the archive unfinished.
    override fun write(
        workspace: BackupWorkspace,
        contents: BackupContents,
        target: () -> OutputStream,
    ): BackupExportResult =
        try {
            BackupExportResult.Exported(writer.write(workspace, contents, target()))
        } catch (exception: Exception) {
            failed("write the archive", exception)
            BackupExportResult.Failed
        }

    @Synchronized
    override fun unpack(upload: InputStream): BackupUnpackResult {
        staged?.let { discard(it.id) }
        val workspace = newWorkspace() ?: return BackupUnpackResult.StorageFailure
        val result =
            try {
                work.unpackInto(reader, upload, workspace)
            } catch (refusal: BackupRefusal) {
                BackupUnpackResult.Refused(refusal.problem)
            } catch (_: FileAlreadyExistsException) {
                BackupUnpackResult.Refused(BackupProblem.DUPLICATE_ENTRY)
            } catch (_: ZipException) {
                BackupUnpackResult.Refused(BackupProblem.NOT_A_BACKUP)
            } catch (_: EOFException) {
                BackupUnpackResult.Refused(BackupProblem.NOT_A_BACKUP)
            } catch (_: IllegalArgumentException) {
                // ZipInputStream's answer to entry names that are not valid UTF-8; or an empty keyset.
                BackupUnpackResult.Refused(BackupProblem.NOT_A_BACKUP)
            } catch (exception: IOException) {
                failed("unpack an upload", exception)
                BackupUnpackResult.StorageFailure
            }
        if (result is BackupUnpackResult.Unpacked) staged = result.backup else discard(workspace.id)
        return result
    }

    @Synchronized
    override fun keep(backup: StagedBackup) {
        if (staged?.id == backup.id) staged = backup
    }

    override fun find(id: BackupId): StagedBackup? = staged?.takeIf { it.id == id }

    override fun installFiles(
        backup: StagedBackup,
        proof: ConfirmationResult.Confirmed,
    ): Boolean {
        if (!BackupRestore.confirms(proof, backup)) return false
        return try {
            files.install(work.of(backup.id))
            true
        } catch (exception: IOException) {
            failed("install the restored files", exception)
            false
        }
    }

    override fun beginRestore(
        backup: StagedBackup,
        previousKeyset: MasterKeysetCopy?,
    ): Boolean =
        try {
            val directory = work.of(backup.id)
            // The keyset copy first: the marker promises that it is complete.
            previousKeyset?.let { work.writeOwnerOnly(directory.resolve(WorkDirectories.PREVIOUS_KEYSET), it.bytes()) }
            Files.writeString(directory.resolve(WorkDirectories.RESTORE_MARKER), backup.id.toString())
            true
        } catch (exception: IOException) {
            failed("mark a restore in progress", exception)
            false
        }

    override fun interruptedRestores(): List<InterruptedRestore>? =
        try {
            work.marked().map { directory ->
                val keyset = directory.resolve(WorkDirectories.PREVIOUS_KEYSET)
                InterruptedRestore(
                    BackupId(UUID.fromString(directory.fileName.toString())),
                    if (Files.exists(keyset)) MasterKeysetCopy(Files.readAllBytes(keyset)) else null,
                )
            }
        } catch (exception: IOException) {
            failed("find interrupted restores", exception)
            null
        }

    override fun revertFiles(id: BackupId): Boolean =
        try {
            files.revert(work.of(id))
            true
        } catch (exception: IOException) {
            failed("put the previous files back", exception)
            false
        }

    override fun endRestore(id: BackupId) {
        try {
            val directory = work.of(id)
            Files.deleteIfExists(directory.resolve(WorkDirectories.PREVIOUS_KEYSET))
            Files.deleteIfExists(directory.resolve(WorkDirectories.RESTORE_MARKER))
        } catch (exception: IOException) {
            failed("end a restore", exception)
        }
    }

    @Synchronized
    override fun discard(id: BackupId) {
        if (staged?.id == id) staged = null
        try {
            work.delete(work.of(id))
        } catch (exception: IOException) {
            failed("remove a work directory", exception)
        }
    }
}

private val logger: Logger = LoggerFactory.getLogger(ZipBackupArchiveAdapter::class.java)

// Only the operation and the exception type: file names and contents are personal data.
private fun failed(
    operation: String,
    exception: Exception,
) {
    logger.error("Backup: could not {}: {}", operation, exception.javaClass.name)
}

private fun WorkDirectories.unpackInto(
    reader: BackupZipReader,
    upload: InputStream,
    workspace: BackupWorkspace,
): BackupUnpackResult {
    val directory = of(workspace.id)
    val found = reader.unpack(upload, directory)
    if (found.isEmpty()) throw BackupRefusal(BackupProblem.NOT_A_BACKUP)
    if (found.remove(BackupPath.MANIFEST) == null) throw BackupRefusal(BackupProblem.MANIFEST_INVALID)
    val manifest = Files.newInputStream(directory.resolve(BackupPath.MANIFEST.value)).use(ManifestJson::read)
    val keyset =
        if (BackupPath.KEYSET in found) {
            MasterKeysetCopy(Files.readAllBytes(directory.resolve(BackupPath.KEYSET.value)))
        } else {
            null
        }
    return BackupUnpackResult.Unpacked(StagedBackup(workspace, manifest, keyset), found)
}
