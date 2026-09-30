// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application.port

import io.github.scriptibus.jofi.system.domain.SessionCleanupResult
import java.time.Instant

/** Login sessions past their idle timeout. Implementations never throw. */
interface ExpiredSessionsPort {
    /** Deletes every session whose idle timeout ended before [now]; joins the caller's transaction. */
    fun deleteExpired(now: Instant): SessionCleanupResult
}
