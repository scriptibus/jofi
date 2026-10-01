// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.InterviewRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.LogInterviewPort
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewDetails
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.InterviewInput
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.DomainEventPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Clock
import java.util.UUID

/**
 * Logs an interview or call of an application (ADR-0048). The application is read first (unknown: `NotFound`), then
 * the input validated; the interview, its participants, its changelog entry and the published `InterviewScheduled`
 * go together or not at all. The application row is not written, so its version stays.
 */
class LogInterviewUseCase(
    private val applications: ApplicationRepositoryPort,
    private val interviews: InterviewRepositoryPort,
    private val events: DomainEventPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : LogInterviewPort {
    override fun execute(
        application: ApplicationId,
        input: InterviewInput,
        actor: Actor,
    ): ApplicationResult<Interview> =
        transactions.inApplicationTransaction {
            applications
                .findById(application)
                .toResult()
                .then { input.validate().toResult() }
                .then { log(application, it, actor) }
        }

    private fun log(
        application: ApplicationId,
        details: InterviewDetails,
        actor: Actor,
    ): ApplicationResult<Interview> {
        val logged = Interview.log(InterviewId(UUID.randomUUID()), application, details, actor, clock.storedNow())
        val interview = logged.interview
        return interviews
            .add(interview)
            .withParticipants()
            .then {
                val recorded =
                    changelog.record(
                        interview.id.toEntityRef(),
                        actor,
                        interview.createdAt,
                        describeInterview("Logged interview", null, details),
                        listOf(applicationChange(null, application)) + interviewChanges(null, details),
                    )
                interview.applicationIf(recorded, "changelog")
            }.then { interview.applicationIf(events.publish(logged.event), "publish event") }
    }
}
