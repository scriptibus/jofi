// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.system.application.port.UserAccountPort
import io.github.scriptibus.jofi.system.domain.AccountLookup
import io.github.scriptibus.jofi.system.domain.UserAccountStoreResult

/**
 * The account every session must belong to. A session bound to another (deleted or reset) account is
 * ended, so a password reset reaches every session, also ones on other devices (ADR-0035).
 */
class GetSessionAccountUseCase(
    private val users: UserAccountPort,
) {
    fun execute(): AccountLookup =
        when (val found = users.find()) {
            is UserAccountStoreResult.Success -> {
                found.value?.let { AccountLookup.Found(it.accountId) }
                    ?: AccountLookup.None
            }

            else -> {
                AccountLookup.StorageFailure
            }
        }
}
