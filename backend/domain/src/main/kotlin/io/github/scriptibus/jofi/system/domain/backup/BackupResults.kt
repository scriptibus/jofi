// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.domain.backup

import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmableAction
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationEffect
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.system.domain.PasswordCheckResult
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** Identifies one export or uploaded restore while it is being worked on. */
@JvmInline
value class BackupId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}

/** A work directory in the data volume for one export or restore; [databaseDirectory] holds the table dumps. */
data class BackupWorkspace(
    val id: BackupId,
    val databaseDirectory: Path,
)

/** A copy of the master keyset (Tink JSON). It is key material: [toString] never shows it. */
class MasterKeysetCopy(
    content: ByteArray,
) {
    private val content = content.copyOf()

    init {
        require(content.isNotEmpty()) { "A keyset cannot be empty" }
    }

    fun bytes(): ByteArray = content.copyOf()

    override fun toString(): String = "MasterKeysetCopy(redacted)"
}

/** What an export writes besides the data files: versions, time, table dumps and the keyset. */
data class BackupContents(
    val appVersion: String,
    val schemaVersion: SchemaVersion,
    val createdAt: Instant,
    val tables: List<TableDump>,
    val keyset: MasterKeysetCopy?,
)

/** A database dump from one consistent snapshot, taken at [takenAt] (the database's clock). */
data class DatabaseDump(
    val schemaVersion: SchemaVersion,
    val tables: List<TableDump>,
    val takenAt: Instant,
)

/**
 * A restore that began replacing files or the keyset and has not finished (a crash, a failed commit):
 * its work directory still holds the marker, the swapped-out files and [previousKeyset], the keyset
 * that was in place before (ADR-0042).
 */
data class InterruptedRestore(
    val id: BackupId,
    val previousKeyset: MasterKeysetCopy?,
)

/** What recovering interrupted restores did. */
enum class RestoreRecoveryResult {
    /** No restore was interrupted. */
    NOTHING_TO_DO,

    /** The restore had committed: its files and keyset stay, the leftovers are gone. */
    ROLLED_FORWARD,

    /** The restore had not committed: the previous files and keyset are back. */
    ROLLED_BACK,

    /** Could not be decided or undone; the marker stays and the next start tries again. */
    FAILED,
}

/**
 * An uploaded backup, checked and unpacked, waiting for the user's confirmation to replace all data.
 * [migratedFrom] names the schema it was made with when its dumps were migrated to the running one.
 */
data class StagedBackup(
    val workspace: BackupWorkspace,
    val manifest: BackupManifest,
    val keyset: MasterKeysetCopy?,
    val migratedFrom: SchemaVersion? = null,
) {
    val id: BackupId get() = workspace.id
}

/** The confirmable action "replace all data with this backup" (ADR-0039, ADR-0042). */
object BackupRestore {
    const val OPERATION = "system.backup.restore"
    private const val ENTITY_TYPE = "backup"

    /** A backup's changelog entity. The restore's entry is also its commit marker (ADR-0042). */
    fun entityOf(id: BackupId): EntityRef = EntityRef(ENTITY_TYPE, id.toString())

    /** Built from the staged backup, so the effect the user confirms is exactly what gets restored. */
    fun action(backup: StagedBackup): ConfirmableAction {
        val manifest = backup.manifest
        val counts =
            mapOf(
                "rows" to manifest.rowCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                "files" to manifest.dataFileCount,
                "keyset" to if (manifest.includesKeyset) 1 else 0,
            )
        return ConfirmableAction(
            OPERATION,
            listOf(backup.id.toString()),
            ConfirmationEffect("backup", manifest.createdAt.toString(), counts),
        )
    }

    /** Whether [proof] confirms restoring exactly [backup]; every adapter checks it before replacing anything. */
    fun confirms(
        proof: ConfirmationResult.Confirmed,
        backup: StagedBackup,
    ): Boolean = proof.covers(OPERATION, backup.id.toString()) && proof.action == action(backup)
}

/** Why an uploaded archive cannot be restored. The API names them in kebab case. */
enum class BackupProblem {
    /** Not a readable zip archive. */
    NOT_A_BACKUP,

    /** Larger than the upload or unpacked size limit. */
    TOO_LARGE,

    /** More entries than the limit. */
    TOO_MANY_ENTRIES,

    /** An entry name that is not part of the format or could escape its directory (zip slip). */
    UNSAFE_PATH,

    /** The same entry twice. */
    DUPLICATE_ENTRY,

    /** `manifest.json` is missing or unreadable. */
    MANIFEST_INVALID,

    /** A format version this Jofi cannot read. */
    UNSUPPORTED_FORMAT,

    /**
     * Made with an older database schema that could not be migrated here (e.g. the database user may
     * not create the scratch database): restore it with the Jofi version it names, then upgrade.
     */
    SCHEMA_OLDER,

    /** Made with a newer database schema: upgrade Jofi first. */
    SCHEMA_NEWER,

    /** Entries missing, extra, or with another size or checksum than the manifest says. */
    CONTENT_MISMATCH,

    /** The table dumps are not the tables this Jofi backs up. */
    TABLES_MISMATCH,

    /** Secrets without the master keyset that encrypts them. */
    KEYSET_MISSING,

    /** The keyset in the backup did not encrypt the secrets in it. */
    KEYSET_MISMATCH,

    /** A table dump the database refuses (wrong columns, broken values, violated constraints). */
    DATA_INVALID,

    /** Not exactly one user account: a restore would leave nobody who can log in. */
    ACCOUNT_MISSING,
}

/** Outcome of writing a backup. */
sealed interface BackupExportResult {
    data class Exported(
        val manifest: BackupManifest,
    ) : BackupExportResult

    /** Nothing or only part of the archive was written; the reason is in the server log. */
    data object Failed : BackupExportResult

    /** The current password was not confirmed; nothing was exported. */
    data class PasswordRefused(
        val check: PasswordCheckResult,
    ) : BackupExportResult

    /** A restore or upload is running. */
    data object Busy : BackupExportResult
}

/** Outcome of unpacking an uploaded archive, before its content is checked against the running schema. */
sealed interface BackupUnpackResult {
    /** Unpacked; [found] is every entry but the manifest with its real size and checksum. */
    data class Unpacked(
        val backup: StagedBackup,
        val found: Map<BackupPath, EntryDigest>,
    ) : BackupUnpackResult

    data class Refused(
        val problem: BackupProblem,
    ) : BackupUnpackResult

    data object StorageFailure : BackupUnpackResult
}

/** Outcome of uploading a backup for restore: nothing is replaced yet. */
sealed interface BackupStageResult {
    data class Staged(
        val backup: StagedBackup,
    ) : BackupStageResult

    data class Refused(
        val problem: BackupProblem,
    ) : BackupStageResult

    data object StorageFailure : BackupStageResult

    /** Another upload, a restore or an export is running. */
    data object Busy : BackupStageResult
}

/** Outcome of replacing all data with a staged backup. */
sealed interface BackupRestoreResult {
    data class Restored(
        val manifest: BackupManifest,
    ) : BackupRestoreResult

    /** No staged backup with this id (never uploaded, replaced by a newer upload, or restored already). */
    data object NotFound : BackupRestoreResult

    /** Not confirmed (yet): nothing was replaced. */
    data class NotConfirmed(
        val outcome: ConfirmationResult.Unconfirmed,
    ) : BackupRestoreResult

    /** The backup's content does not fit; everything was rolled back. */
    data class Refused(
        val problem: BackupProblem,
    ) : BackupRestoreResult

    /** Database or data volume failed; everything was rolled back. */
    data object StorageFailure : BackupRestoreResult

    /**
     * The restore failed and could not be undone completely: the marker stays, and the next start
     * finishes undoing it before anything else runs.
     */
    data object Inconsistent : BackupRestoreResult

    /** The current password was not confirmed; nothing was replaced. */
    data class PasswordRefused(
        val check: PasswordCheckResult,
    ) : BackupRestoreResult

    /** Another upload, a restore or an export is running. */
    data object Busy : BackupRestoreResult
}

/** Outcome of migrating an older backup's table dumps to the running schema. */
sealed interface DatabaseMigrationResult {
    /** The dumps in the workspace now have the running schema; [dump] names it and the new row counts. */
    data class Migrated(
        val dump: DatabaseDump,
    ) : DatabaseMigrationResult

    /** The dumps do not fit the schema they claim ([BackupProblem.DATA_INVALID], [BackupProblem.TABLES_MISMATCH]). */
    data class Refused(
        val problem: BackupProblem,
    ) : DatabaseMigrationResult

    /** Migrating was not possible here ([BackupProblem.SCHEMA_OLDER]). */
    data object Unavailable : DatabaseMigrationResult
}

/** Outcome of loading the table dumps into the database. */
sealed interface DatabaseRestoreResult {
    data object Restored : DatabaseRestoreResult

    /** The database refused the dumps ([BackupProblem.DATA_INVALID]). */
    data object DataInvalid : DatabaseRestoreResult

    data object StorageFailure : DatabaseRestoreResult
}
