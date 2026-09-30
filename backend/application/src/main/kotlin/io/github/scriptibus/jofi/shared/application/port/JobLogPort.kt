// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.application.port

import io.github.scriptibus.jofi.shared.domain.job.JobLogPage
import io.github.scriptibus.jofi.shared.domain.job.JobLogQuery
import io.github.scriptibus.jofi.shared.domain.job.JobResult

/** Reads the log of background jobs from the job store (spec §13). Implementations never throw. */
interface JobLogPort {
    /** One page of jobs, most recently changed first. */
    fun list(query: JobLogQuery): JobResult<JobLogPage>
}
