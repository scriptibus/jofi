// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application.port

import io.github.scriptibus.jofi.system.domain.AuthSideEffectResult

/**
 * The one-time setup token that guards first run (ADR-0035): whoever chooses the first password must
 * be able to read the data volume, however the instance is reached (a proxy or `tailscale serve` in
 * front of localhost, DNS rebinding). Implementations never throw and never log the token.
 */
interface SetupTokenPort {
    /** Makes sure a token exists in the data volume (keeps an existing one) and says where to read it. */
    fun issue(): AuthSideEffectResult

    /** Whether a token is issued and waiting for first run. */
    fun isIssued(): Boolean

    /** Whether [candidate] is the issued token. Constant time; `false` when none is issued. */
    fun matches(candidate: String): Boolean

    /** Removes the token once first run is done. */
    fun discard(): AuthSideEffectResult
}
