// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application.port

import io.github.scriptibus.jofi.system.domain.SystemStoreResult
import java.time.Instant

/**
 * What the database knows about the master keyset (table `master_key_check`) and whether it holds
 * secrets. Implementations never throw.
 */
interface MasterKeyRecordPort {
    /** The recorded check value, or `null` when no keyset was ever recorded. */
    fun findCheckValue(): SystemStoreResult<ByteArray?>

    /** Records [checkValue] of the keyset now in use, replacing an earlier record. */
    fun saveCheckValue(
        checkValue: ByteArray,
        recordedAt: Instant,
    ): SystemStoreResult<Unit>

    /** Whether the `secret` table holds any row. */
    fun hasSecrets(): SystemStoreResult<Boolean>
}
