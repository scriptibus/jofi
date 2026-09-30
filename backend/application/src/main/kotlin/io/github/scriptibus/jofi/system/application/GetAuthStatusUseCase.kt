// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.application.port.UserAccountPort
import io.github.scriptibus.jofi.system.domain.AuthStatus
import io.github.scriptibus.jofi.system.domain.AuthStatusResult
import io.github.scriptibus.jofi.system.domain.UserAccountStoreResult

/** Tells the login screen whether to show first run (and whether it needs the setup token) or login. */
class GetAuthStatusUseCase(
    private val users: UserAccountPort,
    private val setupToken: SetupTokenPort,
) {
    fun execute(): AuthStatusResult =
        when (val account = users.find()) {
            is UserAccountStoreResult.Success -> {
                val setUp = account.value != null
                AuthStatusResult.Success(AuthStatus(setUp, setupTokenRequired = !setUp && setupToken.isRequired()))
            }

            else -> {
                AuthStatusResult.StorageFailure
            }
        }
}
