// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.InterviewRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.UpdateInterviewPort
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewDetails
import io.github.scriptibus.jofi.applications.domain.InterviewEdit
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.InterviewInput
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.DomainEventPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Clock

/**
 * Replaces all details of an interview (ADR-0048). The application and the interview are read, then the version is
 * checked, before the input; unchanged details store nothing, write no changelog entry and publish nothing. A moved
 * start publishes `InterviewRescheduled`; the repository's version check catches an edit that slipped in between.
 */
class UpdateInterviewUseCase(
    private val applications: ApplicationRepositoryPort,
    private val interviews: InterviewRepositoryPort,
    private val events: DomainEventPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : UpdateInterviewPort {
    override fun execute(
        application: ApplicationId,
        id: InterviewId,
        input: InterviewInput,
        basedOnVersion: Long,
        actor: Actor,
    ): ApplicationResult<Interview> =
        transactions.inApplicationTransaction {
            applications
                .findById(application)
                .toResult()
                .then { interviews.findById(application, id).interviewResult() }
                .then { it.basedOn(basedOnVersion) }
                .then { current -> input.validate().toResult().then { edit(current, it, actor) } }
        }

    private fun edit(
        current: Interview,
        details: InterviewDetails,
        actor: Actor,
    ): ApplicationResult<Interview> =
        when (val edit = current.edit(details, actor, clock.storedNow())) {
            InterviewEdit.Unchanged -> ApplicationResult.Success(current)
            is InterviewEdit.Changed -> store(current, edit, actor)
        }

    private fun store(
        current: Interview,
        edit: InterviewEdit.Changed,
        actor: Actor,
    ): ApplicationResult<Interview> {
        val edited = edit.interview
        return interviews
            .update(edited)
            .interviewResult()
            .then {
                val recorded =
                    changelog.record(
                        edited.id.toEntityRef(),
                        actor,
                        edited.updatedAt,
                        describeInterview("Edited interview", current.details, edited.details),
                        interviewChanges(current.details, edited.details),
                    )
                edited.applicationIf(recorded, "changelog")
            }.then { edited.applicationIf(edit.rescheduled?.let(events::publish) ?: true, "publish event") }
    }
}
