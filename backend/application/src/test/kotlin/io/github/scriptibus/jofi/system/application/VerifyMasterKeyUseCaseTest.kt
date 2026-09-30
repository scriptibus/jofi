// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.system.application.port.MasterKeyPort
import io.github.scriptibus.jofi.system.application.port.MasterKeyRecordPort
import io.github.scriptibus.jofi.system.domain.MasterKeyCheck
import io.github.scriptibus.jofi.system.domain.MasterKeyCheck.Reason
import io.github.scriptibus.jofi.system.domain.MasterKeyState
import io.github.scriptibus.jofi.system.domain.SystemStoreResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class VerifyMasterKeyUseCaseTest {
    /** A keyset is a number; its check value is that number, so "verifies" means "same keyset". */
    private class FakeKey(
        var keyset: Int? = null,
        var unreadable: Boolean = false,
    ) : MasterKeyPort {
        private var next = 1

        override fun state() =
            when {
                unreadable -> MasterKeyState.UNREADABLE
                keyset == null -> MasterKeyState.MISSING
                else -> MasterKeyState.PRESENT
            }

        override fun generate(): MasterKeyState {
            if (keyset == null) keyset = 100 + next++
            return state()
        }

        override fun newCheckValue() = keyset?.let { byteArrayOf(it.toByte()) }

        override fun verifies(checkValue: ByteArray) =
            keyset?.let { checkValue.contentEquals(byteArrayOf(it.toByte())) } ?: false
    }

    private class FakeRecords(
        var check: ByteArray? = null,
        var secrets: Boolean = false,
        var failing: Boolean = false,
    ) : MasterKeyRecordPort {
        override fun findCheckValue() =
            if (failing) SystemStoreResult.StorageFailure("find") else SystemStoreResult.Success(check)

        override fun saveCheckValue(
            checkValue: ByteArray,
            recordedAt: Instant,
        ): SystemStoreResult<Unit> {
            check = checkValue
            return SystemStoreResult.Success(Unit)
        }

        override fun hasSecrets() = SystemStoreResult.Success(secrets)
    }

    private val key = FakeKey()
    private val records = FakeRecords()
    private val changelog = FakeChangelog()
    private val users = FakeUsers()
    private val useCase =
        VerifyMasterKeyUseCase(key, records, changelog, FakeTransactions(users, changelog), AuthFixtures.clock)

    @Test
    fun `a fresh installation generates and records a keyset`() {
        useCase.execute(acceptLoss = false) shouldBe MasterKeyCheck.Generated

        key.keyset shouldBe 101
        key.verifies(checkNotNull(records.check)) shouldBe true
        changelog.entries.single().actor shouldBe Actor.System("master-key-check")
    }

    @Test
    fun `the recorded keyset passes without changes`() {
        useCase.execute(acceptLoss = false)
        changelog.entries.clear()

        useCase.execute(acceptLoss = false) shouldBe MasterKeyCheck.Ready
        changelog.entries.shouldBeEmpty()
    }

    @Test
    fun `a missing keyset the database was used with is refused, never regenerated silently`() {
        useCase.execute(acceptLoss = false)
        key.keyset = null

        useCase.execute(acceptLoss = false) shouldBe MasterKeyCheck.Refused(Reason.KEYSET_MISSING)
        key.keyset shouldBe null
    }

    @Test
    fun `secrets without any keyset are refused`() {
        records.secrets = true

        useCase.execute(acceptLoss = false) shouldBe MasterKeyCheck.Refused(Reason.SECRETS_WITHOUT_KEYSET)
    }

    @Test
    fun `another keyset than the recorded one is refused`() {
        useCase.execute(acceptLoss = false)
        key.keyset = 7

        useCase.execute(acceptLoss = false) shouldBe MasterKeyCheck.Refused(Reason.KEYSET_MISMATCH)
    }

    @Test
    fun `an explicit acceptance replaces a lost or different keyset and is recorded`() {
        useCase.execute(acceptLoss = false)
        key.keyset = null

        useCase.execute(acceptLoss = true) shouldBe MasterKeyCheck.LossAccepted
        key.verifies(checkNotNull(records.check)) shouldBe true

        key.keyset = 9
        useCase.execute(acceptLoss = true) shouldBe MasterKeyCheck.LossAccepted
        useCase.execute(acceptLoss = false) shouldBe MasterKeyCheck.Ready
        changelog.entries.map { it.change.description }.last() shouldBe
            "Accepted the loss of the previous master keyset (JOFI_ACCEPT_SECRET_LOSS)"
    }

    @Test
    fun `a keyset without a record is adopted`() {
        key.keyset = 5

        useCase.execute(acceptLoss = false) shouldBe MasterKeyCheck.Ready
        key.verifies(checkNotNull(records.check)) shouldBe true
    }

    @Test
    fun `unreadable keysets and storage failures are refused`() {
        key.unreadable = true
        useCase.execute(acceptLoss = true) shouldBe MasterKeyCheck.Refused(Reason.KEYSET_UNREADABLE)

        key.unreadable = false
        records.failing = true
        useCase.execute(acceptLoss = false) shouldBe MasterKeyCheck.Refused(Reason.STORAGE_FAILURE)
    }

    @Test
    fun `without its changelog entry a new keyset is not recorded`() {
        changelog.failing = true

        useCase.execute(acceptLoss = false) shouldBe MasterKeyCheck.Refused(Reason.STORAGE_FAILURE)
    }
}
