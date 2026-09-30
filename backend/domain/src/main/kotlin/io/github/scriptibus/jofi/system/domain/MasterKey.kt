// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.domain

import io.github.scriptibus.jofi.shared.domain.EntityRef

/** Whether the master keyset file in the data volume can be used. */
enum class MasterKeyState {
    MISSING,
    PRESENT,

    /** The file exists but is not a keyset (damaged, wrong format, no access). */
    UNREADABLE,
}

/**
 * The startup check of the master keyset against the check value recorded in the database (ADR-0035):
 * a lost keyset must never silently become a new one, because every stored secret would be lost.
 */
sealed interface MasterKeyCheck {
    /** The keyset matches the database. */
    data object Ready : MasterKeyCheck

    /** A fresh installation: a new keyset was generated and recorded. */
    data object Generated : MasterKeyCheck

    /** The loss of the old keyset was accepted explicitly (`JOFI_ACCEPT_SECRET_LOSS`); secrets must be re-entered. */
    data object LossAccepted : MasterKeyCheck

    data class Refused(
        val reason: Reason,
    ) : MasterKeyCheck

    enum class Reason {
        /** The database was used with a keyset that is no longer in the data volume. */
        KEYSET_MISSING,

        /** The keyset in the data volume is not the one the database was used with. */
        KEYSET_MISMATCH,

        /** Secrets are stored, but there is neither a keyset nor a record of one. */
        SECRETS_WITHOUT_KEYSET,

        /** The keyset file exists but cannot be read. */
        KEYSET_UNREADABLE,

        /** The database could not be read or written. */
        STORAGE_FAILURE,
    }

    companion object {
        /** How keyset changes appear in the changelog. */
        val ENTITY = EntityRef(type = "master-key", id = "current")
    }
}

/** Outcome of a `system` store call that has no other failure than storage. */
sealed interface SystemStoreResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : SystemStoreResult<T>

    data class StorageFailure(
        val operation: String,
    ) : SystemStoreResult<Nothing>
}
