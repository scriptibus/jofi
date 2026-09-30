// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application.port

import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.system.domain.SystemStoreResult
import io.github.scriptibus.jofi.system.domain.backup.MasterKeysetCopy
import io.github.scriptibus.jofi.system.domain.backup.StagedBackup

/**
 * The master keyset travels with a backup of the secrets it encrypts (ADR-0035, ADR-0042).
 * Implementations never throw and never log key material.
 */
interface MasterKeyBackupPort {
    /** The current keyset, or `null` when there is none. */
    fun copy(): SystemStoreResult<MasterKeysetCopy?>

    /** Whether [keyset] made [checkValue] (the `master_key_check` record). */
    fun verifies(
        keyset: MasterKeysetCopy,
        checkValue: ByteArray,
    ): Boolean

    /**
     * Puts back the keyset that was in place before an interrupted restore replaced it
     * ([io.github.scriptibus.jofi.system.domain.backup.InterruptedRestore]). It needs no confirmation
     * proof, so only `RecoverRestoreUseCase` may call it (architecture test `onlyRestoreRecoveryReinstatesTheKeyset`).
     */
    fun reinstate(previous: MasterKeysetCopy): Boolean

    /** Replaces the current keyset with the backup's; `false` when it could not. */
    fun install(
        backup: StagedBackup,
        proof: ConfirmationResult.Confirmed,
    ): Boolean
}
