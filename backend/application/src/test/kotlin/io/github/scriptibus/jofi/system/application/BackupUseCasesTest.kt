// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.ConfirmationStorePort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangeSummary
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogLimit
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import io.github.scriptibus.jofi.system.application.AuthFixtures.PASSWORD
import io.github.scriptibus.jofi.system.application.AuthFixtures.client
import io.github.scriptibus.jofi.system.application.port.BackupArchivePort
import io.github.scriptibus.jofi.system.application.port.BackupLockPort
import io.github.scriptibus.jofi.system.application.port.BuildInfoPort
import io.github.scriptibus.jofi.system.application.port.DatabaseBackupPort
import io.github.scriptibus.jofi.system.application.port.MasterKeyBackupPort
import io.github.scriptibus.jofi.system.application.port.MasterKeyRecordPort
import io.github.scriptibus.jofi.system.domain.PasswordCheckResult
import io.github.scriptibus.jofi.system.domain.PasswordConfirmation
import io.github.scriptibus.jofi.system.domain.SystemStoreResult
import io.github.scriptibus.jofi.system.domain.ThrottleDecision
import io.github.scriptibus.jofi.system.domain.backup.BackupContents
import io.github.scriptibus.jofi.system.domain.backup.BackupEntry
import io.github.scriptibus.jofi.system.domain.backup.BackupExportResult
import io.github.scriptibus.jofi.system.domain.backup.BackupId
import io.github.scriptibus.jofi.system.domain.backup.BackupManifest
import io.github.scriptibus.jofi.system.domain.backup.BackupPath
import io.github.scriptibus.jofi.system.domain.backup.BackupProblem
import io.github.scriptibus.jofi.system.domain.backup.BackupRestore
import io.github.scriptibus.jofi.system.domain.backup.BackupRestoreResult
import io.github.scriptibus.jofi.system.domain.backup.BackupStageResult
import io.github.scriptibus.jofi.system.domain.backup.BackupUnpackResult
import io.github.scriptibus.jofi.system.domain.backup.BackupWorkspace
import io.github.scriptibus.jofi.system.domain.backup.DatabaseDump
import io.github.scriptibus.jofi.system.domain.backup.DatabaseMigrationResult
import io.github.scriptibus.jofi.system.domain.backup.DatabaseRestoreResult
import io.github.scriptibus.jofi.system.domain.backup.EntryDigest
import io.github.scriptibus.jofi.system.domain.backup.InterruptedRestore
import io.github.scriptibus.jofi.system.domain.backup.MasterKeysetCopy
import io.github.scriptibus.jofi.system.domain.backup.RestoreRecoveryResult
import io.github.scriptibus.jofi.system.domain.backup.RunningSchema
import io.github.scriptibus.jofi.system.domain.backup.SchemaVersion
import io.github.scriptibus.jofi.system.domain.backup.StagedBackup
import io.github.scriptibus.jofi.system.domain.backup.TableDump
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class BackupUseCasesTest {
    private val now = Instant.parse("2026-09-30T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val schema = SchemaVersion("20260930064000")
    private val tables = listOf(TableDump("secret", 1), TableDump("master_key_check", 1), TableDump("user_account", 1))
    private val keyset = MasterKeysetCopy("keyset".toByteArray())
    private val previousKeyset = MasterKeysetCopy("previous".toByteArray())
    private val workspace = BackupWorkspace(BackupId(UUID.randomUUID()), Path.of("/data/backup-work/x/database"))
    private val digest = EntryDigest(1, "a".repeat(64))
    private val manifest =
        BackupManifest(
            BackupManifest.FORMAT_VERSION,
            "0.1.0",
            schema,
            now.minusSeconds(3600),
            tables,
            tables.map { BackupEntry(it.path, digest) } + BackupEntry(BackupPath.KEYSET, digest),
        )
    private val staged = StagedBackup(workspace, manifest, keyset)

    private val archive = mockk<BackupArchivePort>(relaxed = true)
    private val database = mockk<DatabaseBackupPort>()
    private val masterKey = mockk<MasterKeyBackupPort>()
    private val keyRecords = mockk<MasterKeyRecordPort>()
    private val changelog = FakeChangelog()
    private val throttle = FakeThrottle()
    private val lock = FakeLock()
    private val verifyPassword = VerifyPasswordUseCase(FakeUsers(AuthFixtures.account()), FakeHasher, throttle, clock)
    private val password = PasswordConfirmation(PASSWORD, client)
    private val wrongPassword = PasswordConfirmation("not the password", client)
    private val buildInfo =
        object : BuildInfoPort {
            override fun applicationVersion() = "0.2.0"
        }

    @Nested
    inner class Export {
        private val useCase =
            ExportBackupUseCase(archive, database, masterKey, buildInfo, changelog, verifyPassword, lock)

        private fun exportable() {
            every { archive.newWorkspace() } returns workspace
            every { database.dump(workspace.databaseDirectory) } returns
                SystemStoreResult.Success(DatabaseDump(schema, tables, now))
            every { masterKey.copy() } returns SystemStoreResult.Success(keyset)
        }

        @Test
        fun `dump, keyset and versions go into the archive, logged first, and the workspace goes`() {
            exportable()
            val contents = slot<BackupContents>()
            val target = ByteArrayOutputStream()
            every { archive.write(workspace, capture(contents), any()) } answers {
                changelog.entries.single().actor shouldBe Actor.User
                thirdArg<() -> OutputStream>().invoke().write(1)
                BackupExportResult.Exported(manifest)
            }
            var openedAt: Instant? = null

            val result =
                useCase.execute(password) {
                    openedAt = it
                    target
                }

            result shouldBe BackupExportResult.Exported(manifest)
            contents.captured shouldBe BackupContents("0.2.0", schema, now, tables, keyset)
            openedAt shouldBe now
            target.size() shouldBe 1
            changelog.entries.single().entity shouldBe BackupRestore.entityOf(workspace.id)
            verify { archive.discard(workspace.id) }
        }

        @Test
        fun `a wrong or throttled password exports nothing`() {
            exportable()

            useCase.execute(wrongPassword) { ByteArrayOutputStream() } shouldBe
                BackupExportResult.PasswordRefused(PasswordCheckResult.Wrong)
            throttle.throttled = ThrottleDecision.Throttled(Duration.ofSeconds(8))
            useCase.execute(password) { ByteArrayOutputStream() } shouldBe
                BackupExportResult.PasswordRefused(PasswordCheckResult.Throttled(Duration.ofSeconds(8)))

            verify(exactly = 0) { archive.newWorkspace() }
            changelog.entries.shouldBeEmpty()
        }

        @Test
        fun `an export next to an upload or restore is busy`() {
            exportable()
            lock.busy = true

            useCase.execute(password) { ByteArrayOutputStream() } shouldBe BackupExportResult.Busy
            verify(exactly = 0) { archive.newWorkspace() }
        }

        @Test
        fun `a failed dump or changelog opens no target and still removes the workspace`() {
            exportable()
            every { database.dump(any()) } returns SystemStoreResult.StorageFailure("dump")
            var opened = false
            val target = { _: Instant ->
                opened = true
                ByteArrayOutputStream()
            }

            useCase.execute(password, target) shouldBe BackupExportResult.Failed
            exportable()
            changelog.failing = true
            useCase.execute(password, target) shouldBe BackupExportResult.Failed

            opened shouldBe false
            verify(exactly = 0) { archive.write(any(), any(), any()) }
            verify(exactly = 2) { archive.discard(workspace.id) }
        }

        @Test
        fun `an unreadable keyset or no workspace fails the export`() {
            exportable()
            every { masterKey.copy() } returns SystemStoreResult.StorageFailure("copy")
            useCase.execute(password) { ByteArrayOutputStream() } shouldBe BackupExportResult.Failed

            every { archive.newWorkspace() } returns null
            useCase.execute(password) { ByteArrayOutputStream() } shouldBe BackupExportResult.Failed
        }
    }

    @Nested
    inner class Stage {
        private val useCase = StageBackupUseCase(archive, database, lock)
        private val running = RunningSchema(schema, tables.map { it.name })
        private val found = manifest.entries.associate { it.path to it.digest }

        private fun upload() = useCase.execute(ByteArrayInputStream(ByteArray(0)))

        @Test
        fun `a backup that fits the running schema is staged`() {
            every { database.runningSchema() } returns SystemStoreResult.Success(running)
            every { archive.unpack(any()) } returns BackupUnpackResult.Unpacked(staged, found)

            upload() shouldBe BackupStageResult.Staged(staged)
            verify(exactly = 0) { archive.discard(any()) }
        }

        @Test
        fun `a backup that does not fit is refused and removed`() {
            every { database.runningSchema() } returns
                SystemStoreResult.Success(running.copy(version = SchemaVersion("20200101000000")))
            every { archive.unpack(any()) } returns BackupUnpackResult.Unpacked(staged, found)

            upload() shouldBe BackupStageResult.Refused(BackupProblem.SCHEMA_NEWER)
            verify { archive.discard(staged.id) }
        }

        @Test
        fun `an older backup is migrated, kept with its new dumps, and says where it came from`() {
            val newer = SchemaVersion("20270101000000")
            val migratedTables = tables + TableDump("company", 0)
            every { database.runningSchema() } returns
                SystemStoreResult.Success(RunningSchema(newer, migratedTables.map { it.name }))
            every { archive.unpack(any()) } returns BackupUnpackResult.Unpacked(staged, found)
            every { database.migrate(staged) } returns
                DatabaseMigrationResult.Migrated(DatabaseDump(newer, migratedTables, now))

            val result = upload().shouldBeInstanceOf<BackupStageResult.Staged>()

            result.backup.migratedFrom shouldBe schema
            result.backup.manifest.schemaVersion shouldBe newer
            result.backup.manifest.tables shouldBe migratedTables
            verify { archive.keep(result.backup) }
            verify(exactly = 0) { archive.discard(any()) }
        }

        @Test
        fun `an older backup that cannot be migrated is refused and removed`() {
            every { database.runningSchema() } returns
                SystemStoreResult.Success(RunningSchema(SchemaVersion("20270101000000"), tables.map { it.name }))
            every { archive.unpack(any()) } returns BackupUnpackResult.Unpacked(staged, found)
            every { database.migrate(staged) } returns DatabaseMigrationResult.Unavailable
            upload() shouldBe BackupStageResult.Refused(BackupProblem.SCHEMA_OLDER)

            every { database.migrate(staged) } returns DatabaseMigrationResult.Refused(BackupProblem.DATA_INVALID)
            upload() shouldBe BackupStageResult.Refused(BackupProblem.DATA_INVALID)
            verify(exactly = 2) { archive.discard(staged.id) }
        }

        @Test
        fun `refusals, failures and a busy lock pass through`() {
            every { database.runningSchema() } returns SystemStoreResult.Success(running)
            every { archive.unpack(any()) } returns BackupUnpackResult.Refused(BackupProblem.UNSAFE_PATH)
            upload() shouldBe BackupStageResult.Refused(BackupProblem.UNSAFE_PATH)

            every { archive.unpack(any()) } returns BackupUnpackResult.StorageFailure
            upload() shouldBe BackupStageResult.StorageFailure

            every { database.runningSchema() } returns SystemStoreResult.StorageFailure("schema")
            upload() shouldBe BackupStageResult.StorageFailure

            lock.busy = true
            upload() shouldBe BackupStageResult.Busy
        }
    }

    @Nested
    inner class Restore {
        private val transactions = ChangelogTransactions(changelog)
        private val confirmAction = ConfirmActionUseCase(MapStore(), clock, Duration.ofMinutes(5))
        private val install = InstallBackupUseCase(archive, database, masterKey, keyRecords, changelog, clock)
        private val recover = RecoverRestoreUseCase(archive, masterKey, changelog, database)
        private val useCase =
            RestoreBackupUseCase(archive, install, recover, confirmAction, transactions, verifyPassword, lock)
        private val user = ConfirmationRequester(Actor.User, "session-a")
        private val checkValue = byteArrayOf(1, 2, 3)
        private val interrupted = InterruptedRestore(staged.id, previousKeyset)

        private fun attempt(
            token: ConfirmationToken?,
            confirmation: PasswordConfirmation = password,
        ) = useCase.execute(staged.id, user, confirmation, token)

        private fun firstStep(): ConfirmationToken =
            attempt(null)
                .shouldBeInstanceOf<BackupRestoreResult.NotConfirmed>()
                .outcome
                .shouldBeInstanceOf<ConfirmationResult.Required>()
                .token

        private fun installable() {
            every { archive.find(staged.id) } returns staged
            every { database.replaceAll(staged, any()) } returns DatabaseRestoreResult.Restored
            every { keyRecords.findCheckValue() } returns SystemStoreResult.Success(checkValue)
            every { masterKey.verifies(keyset, checkValue) } returns true
            every { masterKey.copy() } returns SystemStoreResult.Success(previousKeyset)
            every { archive.beginRestore(staged, previousKeyset) } returns true
            every { archive.installFiles(staged, any()) } returns true
            every { masterKey.install(staged, any()) } returns true
            every { masterKey.reinstate(previousKeyset) } returns true
            every { archive.interruptedRestores() } returns emptyList()
            every { archive.revertFiles(staged.id) } returns true
            every { database.dropScratchDatabases() } returns true
        }

        // What the archive reports once beginRestore ran.
        private fun begun() {
            every { archive.interruptedRestores() } returns listOf(interrupted)
        }

        @Test
        fun `the first step replaces nothing and shows what the backup holds`() {
            installable()

            val required = attempt(null).shouldBeInstanceOf<BackupRestoreResult.NotConfirmed>()

            val action = required.outcome.shouldBeInstanceOf<ConfirmationResult.Required>().action
            action.operation shouldBe "system.backup.restore"
            action.effect.counts shouldBe mapOf("rows" to 3, "files" to 0, "keyset" to 1)
            verify(exactly = 0) { database.replaceAll(any(), any()) }
            transactions.committed shouldBe 0
        }

        @Test
        fun `the confirmed restore replaces tables, logs it as the user, marks, then files and keyset, and commits`() {
            installable()

            attempt(firstStep()) shouldBe BackupRestoreResult.Restored(manifest)

            verifyOrder {
                database.replaceAll(staged, any())
                archive.beginRestore(staged, previousKeyset)
                archive.installFiles(staged, any())
                masterKey.install(staged, any())
                archive.discard(staged.id)
            }
            changelog.entries.single().let {
                it.actor shouldBe Actor.User
                it.entity shouldBe BackupRestore.entityOf(staged.id)
                it.occurredAt shouldBe now
            }
            transactions.committed shouldBe 1
            verify(exactly = 0) { archive.revertFiles(any()) }
        }

        @Test
        fun `every restore needs the current password, also for the first step`() {
            installable()

            attempt(null, wrongPassword) shouldBe BackupRestoreResult.PasswordRefused(PasswordCheckResult.Wrong)
            throttle.throttled = ThrottleDecision.Throttled(Duration.ofSeconds(4))
            attempt(null) shouldBe
                BackupRestoreResult.PasswordRefused(PasswordCheckResult.Throttled(Duration.ofSeconds(4)))

            verify(exactly = 0) { archive.find(any()) }
        }

        @Test
        fun `a restore next to an upload, a restore or an export is busy`() {
            installable()
            lock.busy = true

            attempt(null) shouldBe BackupRestoreResult.Busy
            verify(exactly = 0) { archive.find(any()) }
        }

        @Test
        fun `an unknown backup and a replayed token change nothing`() {
            installable()
            val token = firstStep()
            attempt(token).shouldBeInstanceOf<BackupRestoreResult.Restored>()

            attempt(token).shouldBeInstanceOf<BackupRestoreResult.NotConfirmed>()
            every { archive.find(any()) } returns null
            useCase.execute(BackupId(UUID.randomUUID()), user, password, null) shouldBe BackupRestoreResult.NotFound
            verify(exactly = 1) { database.replaceAll(any(), any()) }
        }

        @Test
        fun `dumps the database refuses roll everything back before any file changes`() {
            installable()
            every { database.replaceAll(staged, any()) } returns DatabaseRestoreResult.DataInvalid

            attempt(firstStep()) shouldBe BackupRestoreResult.Refused(BackupProblem.DATA_INVALID)

            transactions.rolledBack shouldBe 2
            verify(exactly = 0) { archive.beginRestore(any(), any()) }
            verify(exactly = 0) { archive.discard(any()) }
        }

        @Test
        fun `a keyset that did not encrypt the backup's secrets is refused before files change`() {
            installable()
            every { masterKey.verifies(keyset, checkValue) } returns false

            attempt(firstStep()) shouldBe BackupRestoreResult.Refused(BackupProblem.KEYSET_MISMATCH)

            verify(exactly = 0) { archive.installFiles(any(), any()) }
            verify(exactly = 0) { masterKey.install(any(), any()) }
        }

        @Test
        fun `a failed keyset install rolls back and puts the previous files and keyset back`() {
            installable()
            every { masterKey.install(staged, any()) } answers {
                begun()
                false
            }

            attempt(firstStep()) shouldBe BackupRestoreResult.StorageFailure

            verifyOrder {
                archive.revertFiles(staged.id)
                masterKey.reinstate(previousKeyset)
                archive.endRestore(staged.id)
            }
            transactions.committed shouldBe 0
            changelog.entries.shouldBeEmpty()
        }

        @Test
        fun `a restore that cannot be undone is reported as incomplete and keeps its marker`() {
            installable()
            every { archive.installFiles(staged, any()) } answers {
                begun()
                false
            }
            every { archive.revertFiles(staged.id) } returns false

            attempt(firstStep()) shouldBe BackupRestoreResult.Inconsistent

            verify(exactly = 0) { archive.endRestore(any()) }
            verify(exactly = 0) { archive.discard(any()) }
        }

        @Test
        fun `a failed commit before it took effect is undone`() {
            installable()
            every { masterKey.install(staged, any()) } answers {
                begun()
                true
            }
            val token = firstStep()
            transactions.commitFails = true

            shouldThrow<IllegalStateException> { attempt(token) }

            changelog.entries.shouldBeEmpty()
            verify { archive.revertFiles(staged.id) }
            verify { masterKey.reinstate(previousKeyset) }
            verify(exactly = 0) { archive.discard(any()) }
        }

        @Test
        fun `a failed commit that took effect anyway rolls forward`() {
            installable()
            every { masterKey.install(staged, any()) } answers {
                begun()
                true
            }
            val token = firstStep()
            transactions.commitFails = true
            transactions.committedDespiteFailure = true

            shouldThrow<IllegalStateException> { attempt(token) }

            changelog.entries.single().entity shouldBe BackupRestore.entityOf(staged.id)
            verify(exactly = 0) { archive.revertFiles(any()) }
            verify { archive.discard(staged.id) }
        }
    }

    @Nested
    inner class Recover {
        private val useCase = RecoverRestoreUseCase(archive, masterKey, changelog, database)
        private val first = InterruptedRestore(BackupId(UUID.randomUUID()), previousKeyset)
        private val second = InterruptedRestore(BackupId(UUID.randomUUID()), null)

        private fun committed(restore: InterruptedRestore) {
            changelog.append(
                ChangelogEntry(BackupRestore.entityOf(restore.id), Actor.User, now, ChangeSummary("Restored")),
            )
        }

        private fun recoverable() {
            every { database.dropScratchDatabases() } returns true
            every { archive.interruptedRestores() } returns listOf(first, second)
            every { archive.revertFiles(any()) } returns true
            every { masterKey.reinstate(previousKeyset) } returns true
        }

        @Test
        fun `nothing interrupted, nothing to do, but leftover scratch databases go`() {
            recoverable()
            every { archive.interruptedRestores() } returns emptyList()

            useCase.execute() shouldBe RestoreRecoveryResult.NOTHING_TO_DO
            verify { database.dropScratchDatabases() }
        }

        @Test
        fun `a committed restore rolls forward, one that did not commit rolls back`() {
            recoverable()
            committed(first)

            useCase.execute() shouldBe RestoreRecoveryResult.ROLLED_BACK

            verify { archive.discard(first.id) }
            verify(exactly = 0) { archive.revertFiles(first.id) }
            verify { archive.revertFiles(second.id) }
            verify { archive.endRestore(second.id) }
            verify(exactly = 0) { masterKey.reinstate(any()) }
        }

        @Test
        fun `only the named restore is recovered`() {
            recoverable()

            useCase.execute(only = first.id) shouldBe RestoreRecoveryResult.ROLLED_BACK

            verify { masterKey.reinstate(previousKeyset) }
            verify(exactly = 0) { archive.revertFiles(second.id) }
            verify(exactly = 0) { database.dropScratchDatabases() }
        }

        @Test
        fun `what cannot be decided or undone fails and stays marked`() {
            recoverable()
            every { masterKey.reinstate(previousKeyset) } returns false
            useCase.execute() shouldBe RestoreRecoveryResult.FAILED
            verify(exactly = 0) { archive.endRestore(first.id) }

            recoverable()
            val unreadable =
                mockk<ChangelogPort> {
                    every { listByEntity(any(), ChangelogLimit(1)) } returns
                        ChangelogResult.StorageFailure("read")
                }
            RecoverRestoreUseCase(archive, masterKey, unreadable, database).execute() shouldBe
                RestoreRecoveryResult.FAILED

            every { archive.interruptedRestores() } returns null
            useCase.execute() shouldBe RestoreRecoveryResult.FAILED

            every { archive.interruptedRestores() } returns emptyList()
            every { database.dropScratchDatabases() } returns false
            useCase.execute() shouldBe RestoreRecoveryResult.FAILED
        }
    }

    /**
     * Keeps changelog entries only when the transaction commits. With [commitFails] the commit throws
     * after deciding, and [committedDespiteFailure] says whether it took effect anyway.
     */
    private class ChangelogTransactions(
        private val changelog: FakeChangelog,
    ) : TransactionPort {
        var committed = 0
        var rolledBack = 0
        var commitFails = false
        var committedDespiteFailure = false

        override fun <T> inTransaction(
            commitIf: (T) -> Boolean,
            work: () -> T,
        ): T {
            val before = changelog.entries.size
            val result = work()
            val commit = commitIf(result)
            if (!commit || (commitFails && !committedDespiteFailure)) {
                while (changelog.entries.size > before) changelog.entries.removeLast()
            }
            if (commit && commitFails) error("commit failed")
            if (commit) committed++ else rolledBack++
            return result
        }
    }

    /** Busy on request; otherwise runs the work directly. */
    private class FakeLock : BackupLockPort {
        var busy = false

        override fun <T : Any> exclusive(work: () -> T): T? = if (busy) null else work()

        override fun <T : Any> shared(work: () -> T): T? = if (busy) null else work()
    }

    private class MapStore : ConfirmationStorePort {
        private val pending = mutableMapOf<String, PendingConfirmation>()

        override fun issue(
            pending: PendingConfirmation,
            now: Instant,
        ): ConfirmationToken = ConfirmationToken(UUID.randomUUID().toString()).also { this.pending[it.value] = pending }

        override fun redeem(token: ConfirmationToken): PendingConfirmation? = pending.remove(token.value)
    }
}
