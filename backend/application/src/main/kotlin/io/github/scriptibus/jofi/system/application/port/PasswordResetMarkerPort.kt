// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application.port

import io.github.scriptibus.jofi.system.domain.AuthSideEffectResult

/**
 * Remembers that the reset requested by the current `JOFI_RESET_PASSWORD=true` was applied, so the
 * flag resets the password once, not at every restart. Cleared at the first start without the flag.
 * Implementations never throw.
 */
interface PasswordResetMarkerPort {
    fun isSet(): Boolean

    fun set(): AuthSideEffectResult

    fun clear(): AuthSideEffectResult
}
