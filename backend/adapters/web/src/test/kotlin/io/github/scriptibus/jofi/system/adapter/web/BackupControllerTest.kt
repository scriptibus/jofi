// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.ConfirmationStorePort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import io.github.scriptibus.jofi.system.application.ExportBackupUseCase
import io.github.scriptibus.jofi.system.application.InstallBackupUseCase
import io.github.scriptibus.jofi.system.application.RecoverRestoreUseCase
import io.github.scriptibus.jofi.system.application.RestoreBackupUseCase
import io.github.scriptibus.jofi.system.application.StageBackupUseCase
import io.github.scriptibus.jofi.system.application.VerifyPasswordUseCase
import io.github.scriptibus.jofi.system.application.port.BackupArchivePort
import io.github.scriptibus.jofi.system.application.port.BackupLockPort
import io.github.scriptibus.jofi.system.application.port.BuildInfoPort
import io.github.scriptibus.jofi.system.application.port.DatabaseBackupPort
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.MasterKeyBackupPort
import io.github.scriptibus.jofi.system.application.port.MasterKeyRecordPort
import io.github.scriptibus.jofi.system.application.port.PasswordHasherPort
import io.github.scriptibus.jofi.system.application.port.UserAccountPort
import io.github.scriptibus.jofi.system.domain.AccountId
import io.github.scriptibus.jofi.system.domain.Password
import io.github.scriptibus.jofi.system.domain.PasswordHash
import io.github.scriptibus.jofi.system.domain.SystemStoreResult
import io.github.scriptibus.jofi.system.domain.ThrottleDecision
import io.github.scriptibus.jofi.system.domain.UserAccount
import io.github.scriptibus.jofi.system.domain.UserAccountStoreResult
import io.github.scriptibus.jofi.system.domain.backup.BackupEntry
import io.github.scriptibus.jofi.system.domain.backup.BackupExportResult
import io.github.scriptibus.jofi.system.domain.backup.BackupId
import io.github.scriptibus.jofi.system.domain.backup.BackupManifest
import io.github.scriptibus.jofi.system.domain.backup.BackupProblem
import io.github.scriptibus.jofi.system.domain.backup.BackupUnpackResult
import io.github.scriptibus.jofi.system.domain.backup.BackupWorkspace
import io.github.scriptibus.jofi.system.domain.backup.DatabaseDump
import io.github.scriptibus.jofi.system.domain.backup.EntryDigest
import io.github.scriptibus.jofi.system.domain.backup.RunningSchema
import io.github.scriptibus.jofi.system.domain.backup.SchemaVersion
import io.github.scriptibus.jofi.system.domain.backup.StagedBackup
import io.github.scriptibus.jofi.system.domain.backup.TableDump
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.io.OutputStream
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

// Security is the filter chain's job (bootstrap BackupRoundTripTest); this slice tests the mapping only.
@WebMvcTest(BackupController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(BackupControllerTest.UseCaseConfig::class)
class BackupControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val ports: Ports,
) {
    /** The mocked ports behind the real use cases. */
    class Ports(
        val archive: BackupArchivePort = mockk(relaxed = true),
        val database: DatabaseBackupPort = mockk(),
        val masterKey: MasterKeyBackupPort = mockk(),
        val changelog: ChangelogPort = mockk(),
        val lock: FakeLock = FakeLock(),
    )

    /** Busy on request; otherwise runs the work directly. */
    class FakeLock : BackupLockPort {
        var busy = false

        override fun <T : Any> exclusive(work: () -> T): T? = if (busy) null else work()

        override fun <T : Any> shared(work: () -> T): T? = if (busy) null else work()
    }

    @TestConfiguration
    class UseCaseConfig {
        private val clock = Clock.fixed(Instant.parse("2026-09-30T12:34:56Z"), ZoneOffset.UTC)

        @Bean
        fun ports() = Ports()

        @Bean
        fun verifyPasswordUseCase(): VerifyPasswordUseCase {
            val account = UserAccount(AccountId(UUID.randomUUID()), PasswordHash("hash"), Instant.EPOCH, Instant.EPOCH)
            val users = mockk<UserAccountPort> { every { find() } returns UserAccountStoreResult.Success(account) }
            val hasher =
                mockk<PasswordHasherPort> {
                    every { matches(any(), any()) } answers
                        { firstArg<Password>().reveal() == PASSWORD }
                }
            val throttle =
                mockk<LoginThrottlePort>(relaxed = true) {
                    every { attempt(any(), any()) } returns
                        ThrottleDecision.Allowed
                }
            return VerifyPasswordUseCase(users, hasher, throttle, clock)
        }

        @Bean
        fun exportBackupUseCase(
            ports: Ports,
            verify: VerifyPasswordUseCase,
        ) = ExportBackupUseCase(
            ports.archive,
            ports.database,
            ports.masterKey,
            object : BuildInfoPort {
                override fun applicationVersion() = "0.1.0"
            },
            ports.changelog,
            verify,
            ports.lock,
        )

        @Bean
        fun stageBackupUseCase(ports: Ports) = StageBackupUseCase(ports.archive, ports.database, ports.lock)

        @Bean
        fun restoreBackupUseCase(
            ports: Ports,
            verify: VerifyPasswordUseCase,
        ): RestoreBackupUseCase {
            val install =
                InstallBackupUseCase(
                    ports.archive,
                    ports.database,
                    ports.masterKey,
                    mockk<MasterKeyRecordPort>(),
                    ports.changelog,
                    clock,
                )
            val recover = RecoverRestoreUseCase(ports.archive, ports.masterKey, ports.changelog, ports.database)
            val store =
                object : ConfirmationStorePort {
                    override fun issue(
                        pending: PendingConfirmation,
                        now: Instant,
                    ) = ConfirmationToken("token")

                    override fun redeem(token: ConfirmationToken): PendingConfirmation? = null
                }
            val gate = ConfirmActionUseCase(store, clock, Duration.ofMinutes(5))
            val transactions =
                object : TransactionPort {
                    override fun <T> inTransaction(
                        commitIf: (T) -> Boolean,
                        work: () -> T,
                    ): T = work()
                }
            return RestoreBackupUseCase(ports.archive, install, recover, gate, transactions, verify, ports.lock)
        }
    }

    private val schema = SchemaVersion("20260930064000")
    private val workspace =
        BackupWorkspace(BackupId(UUID.fromString("00000000-0000-0000-0000-0000000000b1")), Path.of("/data/x"))
    private val account = TableDump("user_account", 1)
    private val accountEntry = BackupEntry(account.path, EntryDigest(1, "a".repeat(64)))
    private val manifest =
        BackupManifest(1, "0.1.0", schema, Instant.parse("2026-09-30T10:00:00Z"), listOf(account), listOf(accountEntry))

    @BeforeEach
    fun reset() {
        clearMocks(ports.archive, ports.database, ports.masterKey, ports.changelog)
        ports.lock.busy = false
        every { ports.archive.newWorkspace() } returns workspace
        every { ports.archive.interruptedRestores() } returns emptyList()
        every { ports.database.runningSchema() } returns SystemStoreResult.Success(RunningSchema(schema, emptyList()))
        every { ports.changelog.append(any()) } returns ChangelogResult.Success(Unit)
    }

    private fun export(password: String = PASSWORD) =
        mvc
            .post()
            .uri("/api/system/backup/exports")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"password":"$password"}""")
            .exchange()

    @Test
    fun `the export streams a zip attachment that is never cached`() {
        every { ports.database.dump(any()) } returns
            SystemStoreResult.Success(DatabaseDump(schema, emptyList(), Instant.parse("2026-09-30T12:34:56Z")))
        every { ports.masterKey.copy() } returns SystemStoreResult.Success(null)
        every { ports.archive.write(workspace, any(), any()) } answers {
            thirdArg<() -> OutputStream>().invoke().write("PK".toByteArray())
            BackupExportResult.Exported(manifest)
        }

        val result = export()

        result.response.status shouldBe 200
        result.response.contentType shouldBe "application/zip"
        result.response.getHeader("Content-Disposition") shouldBe
            "attachment; filename=\"jofi-backup-20260930-123456.zip\""
        result.response.getHeader("Cache-Control") shouldBe "no-store"
        result.response.contentAsString shouldBe "PK"
        verify { ports.changelog.append(any()) }
    }

    @Test
    fun `an export needs the current password and is refused while a restore runs`() {
        export("wrong").let {
            it.response.status shouldBe 403
            it.response.contentAsString shouldNotContain "wrong\""
        }
        ports.lock.busy = true
        export().let {
            it.response.status shouldBe 409
            it.response.contentAsString shouldContain BackupProblems.BUSY
        }
        verify(exactly = 0) { ports.archive.newWorkspace() }
    }

    @Test
    fun `a failed export before streaming is a 503 problem`() {
        every { ports.database.dump(any()) } returns SystemStoreResult.StorageFailure("dump")

        val result = export()

        result.response.status shouldBe 503
        result.response.contentAsString shouldContain BackupProblems.UNAVAILABLE
    }

    @Test
    fun `an uploaded backup that passes is described, with nothing restored`() {
        every { ports.archive.unpack(any()) } returns
            BackupUnpackResult.Unpacked(
                StagedBackup(workspace, manifest, null),
                mapOf(accountEntry.path to accountEntry.digest),
            )
        every { ports.database.runningSchema() } returns
            SystemStoreResult.Success(RunningSchema(schema, listOf("user_account")))

        mvc
            .post()
            .uri("/api/system/backup/restores")
            .contentType("application/zip")
            .content(byteArrayOf(1, 2, 3))
            .assertThat()
            .hasStatus(201)
            .bodyJson()
            .isStrictlyEqualTo(
                """
                {"id":"00000000-0000-0000-0000-0000000000b1","createdAt":"2026-09-30T10:00:00Z","appVersion":"0.1.0",
                 "schemaVersion":"20260930064000","rows":1,"files":0,"includesKeyset":false,"migratedFrom":null}
                """.trimIndent(),
            )
        verify(exactly = 0) { ports.database.replaceAll(any(), any()) }
    }

    @Test
    fun `refused or concurrent uploads are problems that name the reason`() {
        every { ports.archive.unpack(any()) } returns BackupUnpackResult.Refused(BackupProblem.UNSAFE_PATH)
        val unsafe = upload()
        unsafe.response.status shouldBe 422
        unsafe.response.contentAsString shouldContain "\"reason\":\"unsafe-path\""
        unsafe.response.contentAsString shouldContain BackupProblems.REFUSED

        every { ports.archive.unpack(any()) } returns BackupUnpackResult.Refused(BackupProblem.TOO_LARGE)
        upload().response.status shouldBe 413

        every { ports.archive.unpack(any()) } returns BackupUnpackResult.StorageFailure
        upload().response.status shouldBe 503

        ports.lock.busy = true
        upload().response.status shouldBe 409
    }

    @Test
    fun `restoring needs the password, an unknown backup is 404, a first step is 428 with the confirmation`() {
        restore(password = "wrong").response.status shouldBe 403

        every { ports.archive.find(any()) } returns null
        restore().response.status shouldBe 404

        every { ports.archive.find(workspace.id) } returns StagedBackup(workspace, manifest, null)
        val first = restore()
        first.response.status shouldBe 428
        first.response.contentAsString shouldContain "\"operation\":\"system.backup.restore\""
        verify(exactly = 0) { ports.database.replaceAll(any(), any()) }

        ports.lock.busy = true
        restore().response.status shouldBe 409
    }

    private fun upload() =
        mvc
            .post()
            .uri("/api/system/backup/restores")
            .contentType("application/zip")
            .content(byteArrayOf(1))
            .exchange()

    private fun restore(password: String = PASSWORD) =
        mvc
            .post()
            .uri("/api/system/backup/restores/${workspace.id}")
            .session(MockHttpSession())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"password":"$password"}""")
            .exchange()

    private companion object {
        const val PASSWORD = "correct horse battery staple"
    }
}
