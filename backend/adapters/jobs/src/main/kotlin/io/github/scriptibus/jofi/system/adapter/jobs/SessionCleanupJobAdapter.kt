// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.jobs

import io.github.scriptibus.jofi.shared.application.port.JobHandlerPort
import io.github.scriptibus.jofi.shared.domain.job.FailureReason
import io.github.scriptibus.jofi.shared.domain.job.JobOutcome
import io.github.scriptibus.jofi.shared.domain.job.JobType
import io.github.scriptibus.jofi.system.application.CleanUpExpiredSessionsUseCase
import io.github.scriptibus.jofi.system.domain.SessionCleanup
import io.github.scriptibus.jofi.system.domain.SessionCleanupResult
import org.springframework.stereotype.Component

/** Runs the hourly [SessionCleanup] job in the worker. */
@Component
class SessionCleanupJobAdapter(
    private val cleanUp: CleanUpExpiredSessionsUseCase,
) : JobHandlerPort {
    override val type: JobType = SessionCleanup.TYPE

    override fun run(arguments: Map<String, String>): JobOutcome =
        when (cleanUp.execute()) {
            is SessionCleanupResult.Cleaned -> JobOutcome.Done
            SessionCleanupResult.StorageFailure -> JobOutcome.Retry(STORAGE_FAILURE)
        }

    private companion object {
        val STORAGE_FAILURE = FailureReason("storage-failure")
    }
}
