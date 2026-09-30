// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.secret

import java.util.UUID

/** Identifies a stored secret (e.g. an AI provider's API key). Owners keep the id, never the value. */
@JvmInline
value class SecretId(
    val value: UUID,
)

/**
 * A secret in clear text, e.g. an API key on its way to the store or to a provider call.
 * [toString] never shows it, so a secret that slips into a log line or an error message stays
 * hidden (threat model T4). Not a data class on purpose: data classes print their values.
 */
class SecretValue(
    private val value: String,
) {
    init {
        require(value.isNotBlank()) { "A secret must not be blank" }
    }

    /** The clear text. Call it only where the secret is handed to its consumer. */
    fun reveal(): String = value

    override fun equals(other: Any?): Boolean = other is SecretValue && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "SecretValue(***)"
}

/** Outcome of a secret-store call; failures are values, not exceptions, and never carry the secret. */
sealed interface SecretResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : SecretResult<T>

    /** No secret is stored under the id. */
    data object NotFound : SecretResult<Nothing>

    /** The stored ciphertext cannot be decrypted, e.g. the master key changed or the row was tampered with. */
    data object Undecryptable : SecretResult<Nothing>

    /** The store could not complete [operation]. */
    data class StorageFailure(
        val operation: String,
    ) : SecretResult<Nothing>
}
