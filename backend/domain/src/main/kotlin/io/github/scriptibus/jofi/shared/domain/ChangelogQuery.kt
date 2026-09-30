// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain

/** Maximum number of changelog entries one read returns, between 1 and [MAXIMUM]. */
@JvmInline
value class ChangelogLimit(
    val value: Int,
) {
    init {
        require(value in 1..MAXIMUM) { "A changelog limit must be between 1 and $MAXIMUM" }
    }

    companion object {
        /** Keeps a single read bounded; callers page with smaller limits. */
        const val MAXIMUM = 500
    }
}

/**
 * Outcome of a changelog operation. Storage failures are expected at the port boundary and are
 * returned instead of thrown, so callers must decide what a failed audit write means for them.
 */
sealed interface ChangelogResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : ChangelogResult<T>

    /** The store could not complete [operation]. Carries no row data, so it is safe to log. */
    data class StorageFailure(
        val operation: String,
    ) : ChangelogResult<Nothing>
}
