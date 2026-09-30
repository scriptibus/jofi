// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.shared.application.port.JobLogPort
import io.github.scriptibus.jofi.shared.domain.job.JobLogPage
import io.github.scriptibus.jofi.shared.domain.job.JobLogQuery
import io.github.scriptibus.jofi.shared.domain.job.JobResult

/** The job log the user sees: background jobs with status, attempts and failure reason (spec §13). */
class ListJobsUseCase(
    private val jobLog: JobLogPort,
) {
    fun execute(query: JobLogQuery): JobResult<JobLogPage> = jobLog.list(query)
}
