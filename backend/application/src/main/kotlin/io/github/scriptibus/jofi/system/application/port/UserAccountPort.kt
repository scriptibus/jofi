// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application.port

import io.github.scriptibus.jofi.system.domain.UserAccount
import io.github.scriptibus.jofi.system.domain.UserAccountStoreResult

/** Stores the one user account (table `user_account`). Implementations never throw. */
interface UserAccountPort {
    /** The account, or `null` before first run. */
    fun find(): UserAccountStoreResult<UserAccount?>

    /** Creates the account; [UserAccountStoreResult.AlreadyExists] when there is one. */
    fun create(account: UserAccount): UserAccountStoreResult<Unit>

    /** Replaces the stored account; [UserAccountStoreResult.NotFound] before first run. */
    fun update(account: UserAccount): UserAccountStoreResult<Unit>

    /** Removes the account (password reset); [UserAccountStoreResult.NotFound] when there is none. */
    fun delete(): UserAccountStoreResult<Unit>
}
