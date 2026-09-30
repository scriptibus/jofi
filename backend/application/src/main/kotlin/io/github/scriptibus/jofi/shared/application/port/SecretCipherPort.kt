// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.application.port

import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import io.github.scriptibus.jofi.shared.domain.secret.SecretResult
import io.github.scriptibus.jofi.shared.domain.secret.SecretValue

/**
 * Authenticated encryption behind [SecretStorePort] (Tink AES-GCM under the master keyset in the
 * data volume, ADR-0017). The secret's id is bound as associated data, so a ciphertext moved to
 * another id does not decrypt. Implementations never throw and never log. Only secret stores use
 * this port; every other caller keeps a [SecretId] and goes through [SecretStorePort].
 */
interface SecretCipherPort {
    /** The ciphertext of [value], bound to [id]. */
    fun encrypt(
        id: SecretId,
        value: SecretValue,
    ): SecretResult<ByteArray>

    /** The clear text, or [SecretResult.Undecryptable] for a tampered, moved or foreign ciphertext. */
    fun decrypt(
        id: SecretId,
        ciphertext: ByteArray,
    ): SecretResult<SecretValue>
}
