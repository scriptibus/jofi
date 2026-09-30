// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.crypto

import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ConfirmationStorePort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmableAction
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequest
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import io.github.scriptibus.jofi.shared.domain.secret.SecretResult
import io.github.scriptibus.jofi.shared.domain.secret.SecretValue
import io.github.scriptibus.jofi.system.domain.MasterKeyState
import io.github.scriptibus.jofi.system.domain.SystemStoreResult
import io.github.scriptibus.jofi.system.domain.backup.BackupId
import io.github.scriptibus.jofi.system.domain.backup.BackupManifest
import io.github.scriptibus.jofi.system.domain.backup.BackupRestore
import io.github.scriptibus.jofi.system.domain.backup.BackupWorkspace
import io.github.scriptibus.jofi.system.domain.backup.MasterKeysetCopy
import io.github.scriptibus.jofi.system.domain.backup.SchemaVersion
import io.github.scriptibus.jofi.system.domain.backup.StagedBackup
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

class TinkMasterKeyBackupAdapterTest {
    @TempDir
    lateinit var dataDirectory: Path

    @TempDir
    lateinit var otherDirectory: Path

    private val secretId = SecretId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val secret = SecretValue("sk-test-api-key-4711")

    private fun cipher(directory: Path = dataDirectory) =
        TinkSecretCipherAdapter(directory.toString()).also { it.generate() shouldBe MasterKeyState.PRESENT }

    private fun backup(directory: Path = dataDirectory) = TinkMasterKeyBackupAdapter(directory.toString())

    private fun copyOf(directory: Path): MasterKeysetCopy =
        requireNotNull((backup(directory).copy() as SystemStoreResult.Success).value)

    private fun staged(keyset: MasterKeysetCopy?) =
        StagedBackup(
            BackupWorkspace(BackupId(UUID.randomUUID()), dataDirectory),
            BackupManifest(1, "test", SchemaVersion("1"), Instant.EPOCH, emptyList(), emptyList()),
            keyset,
        )

    @Test
    fun `no keyset, no copy`() {
        backup().copy() shouldBe SystemStoreResult.Success(null)
    }

    @Test
    fun `a copy verifies the check value of its own keyset only`() {
        val check = requireNotNull(cipher().newCheckValue())
        val foreign = cipher(otherDirectory).let { copyOf(otherDirectory) }

        backup().verifies(copyOf(dataDirectory), check) shouldBe true
        backup().verifies(foreign, check) shouldBe false
        backup().verifies(MasterKeysetCopy("not a keyset".toByteArray()), check) shouldBe false
    }

    @Test
    fun `an installed keyset replaces the file owner-only, and a running cipher switches to it`() {
        val running = cipher()
        val restoredCipher = cipher(otherDirectory)
        val ciphertext = (restoredCipher.encrypt(secretId, secret) as SecretResult.Success).value
        running.decrypt(secretId, ciphertext) shouldBe SecretResult.Undecryptable
        val backup = staged(copyOf(otherDirectory))

        backup().install(backup, confirm(BackupRestore.action(backup))) shouldBe true

        running.decrypt(secretId, ciphertext) shouldBe SecretResult.Success(secret)
        val file = dataDirectory.resolve("secrets/master-keyset.json")
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) shouldBe "rw-------"
        Files.list(file.parent).use { files ->
            files.filter { it.fileName.toString().endsWith(".tmp") }.count()
        } shouldBe
            0
    }

    @Test
    fun `nothing is installed without the confirmation of this backup, or from a broken keyset`() {
        cipher()
        val before = Files.readAllBytes(dataDirectory.resolve("secrets/master-keyset.json"))
        cipher(otherDirectory)
        val backup = staged(copyOf(otherDirectory))
        val other = staged(copyOf(otherDirectory))
        val broken = staged(MasterKeysetCopy("{}".toByteArray()))

        backup().install(backup, confirm(BackupRestore.action(other))) shouldBe false
        backup().install(broken, confirm(BackupRestore.action(broken))) shouldBe false
        backup().install(staged(null), confirm(BackupRestore.action(staged(null)))) shouldBe false

        Files.readAllBytes(dataDirectory.resolve("secrets/master-keyset.json")) shouldBe before
    }

    @Test
    fun `the previous keyset of an undone restore goes back, a broken one never`() {
        val running = cipher()
        val before = copyOf(dataDirectory)
        val ciphertext = (running.encrypt(secretId, secret) as SecretResult.Success).value
        cipher(otherDirectory)
        val backup = staged(copyOf(otherDirectory))
        backup().install(backup, confirm(BackupRestore.action(backup))) shouldBe true
        running.decrypt(secretId, ciphertext) shouldBe SecretResult.Undecryptable

        backup().reinstate(before) shouldBe true

        running.decrypt(secretId, ciphertext) shouldBe SecretResult.Success(secret)
        backup().reinstate(MasterKeysetCopy("{}".toByteArray())) shouldBe false
        running.decrypt(secretId, ciphertext) shouldBe SecretResult.Success(secret)
    }

    @Test
    fun `a copy never shows the key material`() {
        cipher()
        copyOf(dataDirectory).toString().contains("primaryKeyId") shouldBe false
        backup().copy().shouldBeInstanceOf<SystemStoreResult.Success<*>>()
        backup(otherDirectory).copy().let { (it as SystemStoreResult.Success).value }.shouldBeNull()
    }

    private fun confirm(action: ConfirmableAction): ConfirmationResult.Confirmed {
        val store =
            object : ConfirmationStorePort {
                private var pending: PendingConfirmation? = null

                override fun issue(
                    pending: PendingConfirmation,
                    now: Instant,
                ) = ConfirmationToken("token").also { this.pending = pending }

                override fun redeem(token: ConfirmationToken) = pending.also { pending = null }
            }
        val gate = ConfirmActionUseCase(store, Clock.systemUTC(), Duration.ofMinutes(1))
        val requester = ConfirmationRequester(Actor.User, "session")
        val required = gate.execute(ConfirmationRequest(requester, action, null)) as ConfirmationResult.Required
        return gate.execute(ConfirmationRequest(requester, action, required.token)) as ConfirmationResult.Confirmed
    }
}
