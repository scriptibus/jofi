// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.application.port

import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import io.github.scriptibus.jofi.shared.domain.secret.SecretResult
import io.github.scriptibus.jofi.shared.domain.secret.SecretValue

/**
 * Encrypted storage for secrets such as API keys (ADR-0017; Tink AES-GCM in #16, table `secret`).
 * Values are encrypted before they reach the database and never logged. Implementations never
 * throw. Use cases record changes to a secret in the changelog without its value.
 */
interface SecretStorePort {
    /** Stores [value] under [id], replacing an existing secret. */
    fun put(
        id: SecretId,
        value: SecretValue,
    ): SecretResult<Unit>

    /** The decrypted secret, [SecretResult.NotFound] or [SecretResult.Undecryptable]. */
    fun get(id: SecretId): SecretResult<SecretValue>

    /** Removes the secret; [SecretResult.NotFound] when there is none. */
    fun delete(id: SecretId): SecretResult<Unit>
}
