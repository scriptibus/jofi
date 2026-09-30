// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangeSummary
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.system.application.port.MasterKeyPort
import io.github.scriptibus.jofi.system.application.port.MasterKeyRecordPort
import io.github.scriptibus.jofi.system.domain.MasterKeyCheck
import io.github.scriptibus.jofi.system.domain.MasterKeyCheck.Reason
import io.github.scriptibus.jofi.system.domain.MasterKeyState
import io.github.scriptibus.jofi.system.domain.SystemStoreResult
import java.time.Clock
import java.time.Instant

/**
 * Runs at startup before anything touches a secret (ADR-0035). A new keyset is generated only for a
 * fresh installation. A keyset that is missing or different while the database was used with one is
 * refused, unless the loss is accepted explicitly ([acceptLoss], `JOFI_ACCEPT_SECRET_LOSS`): a silent
 * new key would make every stored secret unreadable.
 */
class VerifyMasterKeyUseCase(
    private val masterKey: MasterKeyPort,
    private val records: MasterKeyRecordPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) {
    fun execute(acceptLoss: Boolean): MasterKeyCheck {
        val recorded = records.findCheckValue()
        if (recorded !is SystemStoreResult.Success) return MasterKeyCheck.Refused(Reason.STORAGE_FAILURE)
        val check = recorded.value
        return when (masterKey.state()) {
            MasterKeyState.UNREADABLE -> MasterKeyCheck.Refused(Reason.KEYSET_UNREADABLE)
            MasterKeyState.MISSING -> whenMissing(check != null, acceptLoss)
            MasterKeyState.PRESENT -> whenPresent(check, acceptLoss)
        }
    }

    private fun whenMissing(
        wasRecorded: Boolean,
        acceptLoss: Boolean,
    ): MasterKeyCheck {
        val secrets = records.hasSecrets()
        if (secrets !is SystemStoreResult.Success) return MasterKeyCheck.Refused(Reason.STORAGE_FAILURE)
        val refusal =
            when {
                wasRecorded -> Reason.KEYSET_MISSING
                secrets.value -> Reason.SECRETS_WITHOUT_KEYSET
                else -> null
            }
        return when {
            refusal == null -> generateAndRecord(MasterKeyCheck.Generated)
            acceptLoss -> generateAndRecord(MasterKeyCheck.LossAccepted)
            else -> MasterKeyCheck.Refused(refusal)
        }
    }

    private fun whenPresent(
        check: ByteArray?,
        acceptLoss: Boolean,
    ): MasterKeyCheck =
        when {
            // A keyset from before the check existed, or one restored together with the data volume.
            check == null -> record(MasterKeyCheck.Ready)

            masterKey.verifies(check) -> MasterKeyCheck.Ready

            acceptLoss -> record(MasterKeyCheck.LossAccepted)

            else -> MasterKeyCheck.Refused(Reason.KEYSET_MISMATCH)
        }

    private fun generateAndRecord(outcome: MasterKeyCheck): MasterKeyCheck =
        if (masterKey.generate() ==
            MasterKeyState.PRESENT
        ) {
            record(outcome)
        } else {
            MasterKeyCheck.Refused(Reason.KEYSET_UNREADABLE)
        }

    private fun record(outcome: MasterKeyCheck): MasterKeyCheck {
        val checkValue = masterKey.newCheckValue() ?: return MasterKeyCheck.Refused(Reason.KEYSET_UNREADABLE)
        val now = clock.instant()
        return transactions.inTransaction({ it == outcome }) {
            val saved = records.saveCheckValue(checkValue, now) is SystemStoreResult.Success
            if (saved && logged(outcome, now)) outcome else MasterKeyCheck.Refused(Reason.STORAGE_FAILURE)
        }
    }

    // Only a new keyset or an accepted loss is a change worth recording; re-recording is not.
    private fun logged(
        outcome: MasterKeyCheck,
        at: Instant,
    ): Boolean {
        val description =
            when (outcome) {
                MasterKeyCheck.Generated -> "Generated the master keyset"
                MasterKeyCheck.LossAccepted -> LOSS_ACCEPTED
                else -> return true
            }
        val entry = ChangelogEntry(MasterKeyCheck.ENTITY, Actor.System(ACTOR), at, ChangeSummary(description))
        return changelog.append(entry) is ChangelogResult.Success
    }

    private companion object {
        const val ACTOR = "master-key-check"
        const val LOSS_ACCEPTED = "Accepted the loss of the previous master keyset (JOFI_ACCEPT_SECRET_LOSS)"
    }
}
