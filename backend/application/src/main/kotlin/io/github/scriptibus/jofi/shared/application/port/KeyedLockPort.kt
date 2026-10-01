// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.application.port

/**
 * Lets one caller at a time run work for a key, without holding a database connection while it waits or works:
 * for slow work that must not run twice for the same thing, such as fetching one link (#97). Only the `app`
 * container serves requests (as `ConfirmationStorePort`); a store-level check still guards what gets stored.
 */
interface KeyedLockPort {
    /** Runs [work] once no other call holds [key]; calls for other keys never wait for each other. */
    fun <T> withLock(
        key: String,
        work: () -> T,
    ): T
}
