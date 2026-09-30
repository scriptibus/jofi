// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.system.application.port.UserAccountPort
import io.github.scriptibus.jofi.system.domain.AuthStatus
import io.github.scriptibus.jofi.system.domain.AuthStatusResult
import io.github.scriptibus.jofi.system.domain.UserAccountStoreResult

/** Tells the login screen whether first run (always with the setup token) or login comes next. */
class GetAuthStatusUseCase(
    private val users: UserAccountPort,
) {
    fun execute(): AuthStatusResult =
        when (val account = users.find()) {
            is UserAccountStoreResult.Success -> {
                val setUp = account.value != null
                AuthStatusResult.Success(AuthStatus(setUp, setupTokenRequired = !setUp))
            }

            else -> {
                AuthStatusResult.StorageFailure
            }
        }
}
