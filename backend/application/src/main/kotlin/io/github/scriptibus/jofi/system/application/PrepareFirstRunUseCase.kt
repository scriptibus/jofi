// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.application.port.UserAccountPort
import io.github.scriptibus.jofi.system.domain.AuthSideEffectResult
import io.github.scriptibus.jofi.system.domain.UserAccountStoreResult

/**
 * Runs at startup: while no password is set, issues the one-time setup token into the data volume;
 * once a password exists, removes a leftover token.
 */
class PrepareFirstRunUseCase(
    private val users: UserAccountPort,
    private val setupToken: SetupTokenPort,
) {
    fun execute(): AuthSideEffectResult {
        val account = users.find()
        return when {
            account !is UserAccountStoreResult.Success -> AuthSideEffectResult.Failure
            account.value != null -> setupToken.discard()
            else -> setupToken.issue()
        }
    }
}
