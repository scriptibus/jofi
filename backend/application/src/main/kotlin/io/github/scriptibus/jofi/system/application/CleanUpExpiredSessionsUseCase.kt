// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangeSummary
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.system.application.port.ExpiredSessionsPort
import io.github.scriptibus.jofi.system.domain.SessionCleanup
import io.github.scriptibus.jofi.system.domain.SessionCleanupResult
import java.time.Clock
import java.time.Instant

/**
 * The hourly session cleanup (a worker job): deletes sessions past their idle timeout and records
 * how many in the changelog, with the job as actor ([Actor.System] `session-cleanup`), in one
 * transaction. A run that finds nothing to delete changes nothing and writes no entry.
 */
class CleanUpExpiredSessionsUseCase(
    private val sessions: ExpiredSessionsPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) {
    fun execute(): SessionCleanupResult =
        transactions.inTransaction({ it is SessionCleanupResult.Cleaned }) {
            val now = clock.instant()
            val result = sessions.deleteExpired(now)
            if (result is SessionCleanupResult.Cleaned && result.deleted > 0) recorded(result, now) else result
        }

    private fun recorded(
        result: SessionCleanupResult.Cleaned,
        now: Instant,
    ): SessionCleanupResult {
        val entry =
            ChangelogEntry(
                entity = ENTITY,
                actor = Actor.System(SessionCleanup.ACTOR_NAME),
                occurredAt = now,
                change = ChangeSummary("Deleted ${result.deleted} expired login session(s)"),
            )
        return when (changelog.append(entry)) {
            is ChangelogResult.Success -> result
            is ChangelogResult.StorageFailure -> SessionCleanupResult.StorageFailure
        }
    }

    private companion object {
        /** Sessions are credentials: the entry names what was cleaned up, never a session id. */
        val ENTITY = EntityRef(type = "login-session", id = "expired")
    }
}
