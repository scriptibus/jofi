// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.InterviewRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.ListInterviewsPort
import io.github.scriptibus.jofi.applications.domain.ApplicationField
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationViolation
import io.github.scriptibus.jofi.applications.domain.InterviewSummary
import io.github.scriptibus.jofi.applications.domain.SortDirection
import io.github.scriptibus.jofi.shared.domain.paging.PageInput
import io.github.scriptibus.jofi.shared.domain.paging.PageRequest
import io.github.scriptibus.jofi.shared.domain.paging.PageValidation
import io.github.scriptibus.jofi.shared.domain.paging.Paged

/**
 * One page of the interviews and calls of an application in the order they start, oldest or newest first
 * (ADR-0056), as summaries with an excerpt of both notes; `NotFound` for an unknown application. The page and its
 * size are limited here, not in an adapter.
 */
class ListInterviewsUseCase(
    private val applications: ApplicationRepositoryPort,
    private val interviews: InterviewRepositoryPort,
) : ListInterviewsPort {
    override fun execute(
        application: ApplicationId,
        page: PageInput,
        direction: SortDirection,
    ): ApplicationResult<Paged<InterviewSummary>> =
        page.toResult().then { request ->
            applications.findById(application).toResult().then {
                interviews.pageByApplication(application, request, direction).toResult().then { stored ->
                    ApplicationResult.Success(Paged(stored.items.map(InterviewSummary::of), stored.info))
                }
            }
        }
}

/** The page asked for, or the violations of the page and size that are out of range. */
internal fun PageInput.toResult(): ApplicationResult<PageRequest> =
    when (val validation = validate()) {
        is PageValidation.Valid -> {
            ApplicationResult.Success(validation.request)
        }

        is PageValidation.Invalid -> {
            ApplicationResult.Invalid(
                listOfNotNull(
                    ApplicationViolation(ApplicationField.PAGE, ApplicationProblem.OUT_OF_RANGE)
                        .takeIf { validation.pageOutOfRange },
                    ApplicationViolation(ApplicationField.SIZE, ApplicationProblem.OUT_OF_RANGE)
                        .takeIf { validation.sizeOutOfRange },
                ),
            )
        }
    }
