// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

/** Outcome of a `setup` repository call; storage failures are values, not exceptions. */
sealed interface SetupStoreResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : SetupStoreResult<T>

    /** Nothing is stored under the requested key. */
    data object NotFound : SetupStoreResult<Nothing>

    /** The row cannot be removed while others reference it (e.g. a provider with assigned tasks). */
    data object InUse : SetupStoreResult<Nothing>

    /** The confirmation proof does not cover this removal; nothing was removed (ADR-0039). */
    data object NotConfirmed : SetupStoreResult<Nothing>

    /** The store could not complete [operation]. Carries no row data, so it is safe to log. */
    data class StorageFailure(
        val operation: String,
    ) : SetupStoreResult<Nothing>
}
