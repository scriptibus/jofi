// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.InterviewRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.ListInterviewsPort
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.Interview

/** The interviews and calls of an application in the order they start; `NotFound` for an unknown application. */
class ListInterviewsUseCase(
    private val applications: ApplicationRepositoryPort,
    private val interviews: InterviewRepositoryPort,
) : ListInterviewsPort {
    override fun execute(application: ApplicationId): ApplicationResult<List<Interview>> =
        applications.findById(application).toResult().then { interviews.listByApplication(application).toResult() }
}
