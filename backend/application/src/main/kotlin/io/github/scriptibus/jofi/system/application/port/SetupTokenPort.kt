// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application.port

import io.github.scriptibus.jofi.system.domain.AuthSideEffectResult

/**
 * The one-time setup token that guards first run while Jofi is exposed on a network: whoever
 * reaches the instance first must also be able to read the data volume. Implementations never
 * throw and never log the token.
 */
interface SetupTokenPort {
    /** Whether first run needs the token (Jofi is bound to a non-loopback address). */
    fun isRequired(): Boolean

    /** Makes sure a token exists in the data volume (keeps an existing one). */
    fun issue(): AuthSideEffectResult

    /** Whether [candidate] is the issued token. Constant time; `false` when none is issued. */
    fun matches(candidate: String): Boolean

    /** Removes the token once first run is done. */
    fun discard(): AuthSideEffectResult
}
