// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.InterviewRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.GetInterviewPort
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewId

/** One interview of an application: `NotFound` for an unknown application, `InterviewNotFound` for one it lacks. */
class GetInterviewUseCase(
    private val applications: ApplicationRepositoryPort,
    private val interviews: InterviewRepositoryPort,
) : GetInterviewPort {
    override fun execute(
        application: ApplicationId,
        id: InterviewId,
    ): ApplicationResult<Interview> =
        applications.findById(application).toResult().then { interviews.findById(application, id).interviewResult() }
}
