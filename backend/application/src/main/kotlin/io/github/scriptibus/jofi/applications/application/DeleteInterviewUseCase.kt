// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.InterviewRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.DeleteInterviewPort
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmableAction
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationEffect
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequest
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import java.time.Clock

/**
 * Deletes an interview in two steps (ADR-0039). The interview is read in the transaction of the delete and the
 * confirmation effect (its type and agreed start) is built from that read, so a new type or start between the steps
 * voids the token. The participant rows go by `ON DELETE CASCADE`, the contacts stay. The changelog entry keeps the
 * type, start, zone, outcome and application, never notes or participants.
 */
class DeleteInterviewUseCase(
    private val applications: ApplicationRepositoryPort,
    private val interviews: InterviewRepositoryPort,
    private val confirmation: ConfirmActionUseCase,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : DeleteInterviewPort {
    override fun execute(
        application: ApplicationId,
        id: InterviewId,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): ApplicationResult<Unit> =
        transactions.inApplicationTransaction {
            applications
                .findById(application)
                .toResult()
                .then { interviews.findById(application, id).interviewResult() }
                .then { confirmThenDelete(it, requester, token) }
        }

    private fun confirmThenDelete(
        interview: Interview,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): ApplicationResult<Unit> {
        val time = interview.details.time
        val name = "${interview.details.type.name} ${time.localStart} ${time.zone.id}"
        val effect = ConfirmationEffect(InterviewId.ENTITY_TYPE, name)
        val action = ConfirmableAction(Interview.DELETE_OPERATION, listOf(interview.id.value.toString()), effect)
        return when (val outcome = confirmation.execute(ConfirmationRequest(requester, action, token))) {
            is ConfirmationResult.Confirmed -> {
                interviews
                    .delete(interview.application, interview.id, outcome)
                    .interviewResult()
                    .then { record(interview, requester.actor) }
            }

            is ConfirmationResult.Unconfirmed -> {
                ApplicationResult.Unconfirmed(outcome)
            }
        }
    }

    private fun record(
        interview: Interview,
        actor: Actor,
    ): ApplicationResult<Unit> {
        val fields = listOf(applicationChange(interview.application, null)) + interviewChanges(interview.details, null)
        val recorded =
            changelog.record(interview.id.toEntityRef(), actor, clock.storedNow(), "Deleted interview", fields)
        return Unit.applicationIf(recorded, "changelog")
    }
}
