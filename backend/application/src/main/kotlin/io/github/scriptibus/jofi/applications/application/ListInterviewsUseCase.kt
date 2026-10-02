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
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewSummary
import io.github.scriptibus.jofi.applications.domain.SortDirection
import io.github.scriptibus.jofi.shared.application.RedactForAiUseCase
import io.github.scriptibus.jofi.shared.domain.ai.AiRedaction
import io.github.scriptibus.jofi.shared.domain.ai.NotesAudience
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
    private val redaction: RedactForAiUseCase,
) : ListInterviewsPort {
    override fun execute(
        application: ApplicationId,
        page: PageInput,
        direction: SortDirection,
        audience: NotesAudience,
    ): ApplicationResult<Paged<InterviewSummary>> =
        page.toResult().then { request ->
            applications.findById(application).toResult().then {
                interviews.pageByApplication(application, request, direction).toResult().then { stored ->
                    summariesOf(stored.items, audience).then { ApplicationResult.Success(Paged(it, stored.info)) }
                }
            }
        }

    /**
     * The entries for [audience]: the user's own notes are cut as they are; for an AI the "never send to AI" values
     * go out of the whole notes first, so no excerpt ends inside one (ADR-0056). Flags that cannot be read fail it.
     */
    private fun summariesOf(
        found: List<Interview>,
        audience: NotesAudience,
    ): ApplicationResult<List<InterviewSummary>> =
        when (audience) {
            NotesAudience.USER -> {
                ApplicationResult.Success(found.map { InterviewSummary.of(it) })
            }

            NotesAudience.AI -> {
                val texts = found.flatMap { listOf(it.details.preparationNotes, it.details.notes) }
                when (val redacted = redaction.execute(texts)) {
                    AiRedaction.Unavailable -> {
                        ApplicationResult.StorageFailure("privacy flags")
                    }

                    is AiRedaction.Redacted -> {
                        ApplicationResult.Success(
                            found.mapIndexed { index, interview ->
                                InterviewSummary.of(interview, redacted.texts[2 * index], redacted.texts[2 * index + 1])
                            },
                        )
                    }
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
