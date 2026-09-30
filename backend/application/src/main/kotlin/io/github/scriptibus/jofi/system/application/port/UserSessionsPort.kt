// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application.port

import io.github.scriptibus.jofi.system.domain.AuthSideEffectResult
import io.github.scriptibus.jofi.system.domain.SessionRef

/** The user's login sessions (stored in PostgreSQL). Implementations never throw. */
interface UserSessionsPort {
    /** Ends every session of the user except [keep], e.g. after a password change. */
    fun endAllExcept(keep: SessionRef): AuthSideEffectResult
}
