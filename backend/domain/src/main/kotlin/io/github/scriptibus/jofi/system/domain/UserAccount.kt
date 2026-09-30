// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.domain

import io.github.scriptibus.jofi.shared.domain.EntityRef
import java.time.Instant

/**
 * The one Jofi user (single-user app, ADR-0017): only a password, no user name. It exists once the
 * first-run setup has chosen the password.
 */
data class UserAccount(
    val passwordHash: PasswordHash,
    val createdAt: Instant,
    val passwordChangedAt: Instant,
) {
    init {
        require(!passwordChangedAt.isBefore(createdAt)) { "The password cannot change before the account exists" }
    }

    /** The same account with a new password hash. */
    fun withPassword(
        hash: PasswordHash,
        changedAt: Instant,
    ): UserAccount = copy(passwordHash = hash, passwordChangedAt = changedAt)

    companion object {
        /** How the account appears in the changelog. */
        val ENTITY = EntityRef(type = "user-account", id = "owner")

        /** The principal name of every session (there is only one user). */
        const val PRINCIPAL = "owner"
    }
}

/** Outcome of a user-account store call; failures are values and never carry the hash. */
sealed interface UserAccountStoreResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : UserAccountStoreResult<T>

    /** The account exists already (a second first-run lost the race). */
    data object AlreadyExists : UserAccountStoreResult<Nothing>

    /** There is no account to update. */
    data object NotFound : UserAccountStoreResult<Nothing>

    /** The store could not complete [operation]. */
    data class StorageFailure(
        val operation: String,
    ) : UserAccountStoreResult<Nothing>
}
