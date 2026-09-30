// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.NOW
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewDetails
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.InterviewOutcome
import io.github.scriptibus.jofi.applications.domain.InterviewTime
import io.github.scriptibus.jofi.applications.domain.InterviewType
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationEffect
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRejection
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class DeleteInterviewUseCaseTest {
    private val fixtures = ApplicationFixtures()
    private val delete =
        DeleteInterviewUseCase(
            fixtures.repository,
            fixtures.interviewPort,
            fixtures.confirmation,
            fixtures.changelog,
            fixtures.transactions,
            CLOCK,
        )
    private val user = ConfirmationRequester(Actor.User, "session-1")
    private val application = fixtures.application()
    private val stored = interview()

    private fun interview(): Interview {
        val details =
            InterviewDetails(
                InterviewType.TECHNICAL,
                InterviewTime(Instant.parse("2026-10-05T08:00:00Z"), ZoneId.of("Europe/Berlin")),
                participants = setOf(ContactRef(UUID.randomUUID())),
                notes = "Went well",
                outcome = InterviewOutcome.PASSED,
            )
        val interview =
            Interview
                .log(
                    InterviewId(UUID.randomUUID()),
                    application.id,
                    details,
                    Actor.User,
                    NOW,
                ).interview
        fixtures.interviews[interview.id] = interview
        return interview
    }

    private fun firstStep(requester: ConfirmationRequester = user): ConfirmationResult.Required {
        val result = delete.execute(application.id, stored.id, requester, null)
        return result
            .shouldBeInstanceOf<ApplicationResult.Unconfirmed>()
            .outcome
            .shouldBeInstanceOf<ConfirmationResult.Required>()
    }

    @Test
    fun `the first call only asks, naming the type and the agreed start`() {
        val required = firstStep()

        required.action.operation shouldBe Interview.DELETE_OPERATION
        required.action.targets shouldBe listOf(stored.id.value.toString())
        required.action.effect shouldBe ConfirmationEffect("interview", "TECHNICAL 2026-10-05T10:00 Europe/Berlin")
        fixtures.interviews.size shouldBe 1
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `the confirmed repeat deletes and records type, start, zone, outcome and application only`() {
        val ai = ConfirmationRequester(Actor.Ai, "chat-7")

        delete.execute(application.id, stored.id, ai, firstStep(ai).token) shouldBe ApplicationResult.Success(Unit)

        fixtures.interviews.size shouldBe 0
        val entry = fixtures.entries.single()
        entry.entity shouldBe stored.id.toEntityRef()
        entry.actor shouldBe Actor.Ai
        entry.occurredAt shouldBe NOW
        entry.change.description shouldBe "Deleted interview"
        entry.change.fieldChanges shouldContainExactly
            listOf(
                FieldChange("application", application.id.value.toString(), null),
                FieldChange("type", "TECHNICAL", null),
                FieldChange("startsAt", "2026-10-05T08:00:00Z", null),
                FieldChange("timeZone", "Europe/Berlin", null),
                FieldChange("outcome", "PASSED", null),
            )
        entry.toString() shouldNotContain "Went well"
    }

    @Test
    fun `a token is spent once, binds to its session and is voided by a reschedule`() {
        val token = firstStep().token
        rejected(delete.execute(application.id, stored.id, ConfirmationRequester(Actor.User, "session-2"), token))
        rejected(delete.execute(application.id, stored.id, user, token))

        val next = firstStep().token
        val moved = stored.details.copy(time = InterviewTime(Instant.parse("2026-10-06T08:00:00Z"), ZoneId.of("UTC")))
        fixtures.interviews[stored.id] = stored.copy(details = moved, version = 1)
        rejected(delete.execute(application.id, stored.id, user, next))

        fixtures.interviews.size shouldBe 1
    }

    @Test
    fun `an unknown application or interview is not found, before any token`() {
        delete.execute(ApplicationId(UUID.randomUUID()), stored.id, user, null) shouldBe ApplicationResult.NotFound
        delete.execute(application.id, InterviewId(UUID.randomUUID()), user, ConfirmationToken("forged")) shouldBe
            ApplicationResult.InterviewNotFound
        val other = fixtures.application("Staff Engineer")
        delete.execute(other.id, stored.id, user, null) shouldBe ApplicationResult.InterviewNotFound
    }

    @Test
    fun `a failing changelog rolls the delete back`() {
        fixtures.failingChangelog = true

        delete.execute(application.id, stored.id, user, firstStep().token) shouldBe
            ApplicationResult.StorageFailure("changelog")

        fixtures.interviews.size shouldBe 1
    }

    private fun rejected(result: ApplicationResult<Unit>) {
        val outcome = result.shouldBeInstanceOf<ApplicationResult.Unconfirmed>().outcome
        outcome.shouldBeInstanceOf<ConfirmationResult.Rejected>().reason.shouldBeInstanceOf<ConfirmationRejection>()
    }
}
