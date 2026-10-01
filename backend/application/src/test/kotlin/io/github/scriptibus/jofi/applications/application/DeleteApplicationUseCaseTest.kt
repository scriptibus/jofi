// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.NOW
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDeleted
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationSource
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewDetails
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.InterviewTime
import io.github.scriptibus.jofi.applications.domain.InterviewType
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.applications.domain.SourceKind
import io.github.scriptibus.jofi.applications.domain.StatusChange
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.EntityRef
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
import java.time.ZoneId
import java.util.UUID

class DeleteApplicationUseCaseTest {
    private val fixtures = ApplicationFixtures()
    private val delete =
        DeleteApplicationUseCase(
            fixtures.repository,
            fixtures.linkedTaskPort,
            fixtures.confirmation,
            fixtures.eventPort,
            fixtures.changelog,
            fixtures.transactions,
            CLOCK,
        )
    private val user = ConfirmationRequester(Actor.User, "session-1")

    private fun firstStep(
        id: ApplicationId,
        requester: ConfirmationRequester = user,
    ): ConfirmationResult.Required {
        val result = delete.execute(id, requester, null).shouldBeInstanceOf<ApplicationResult.Unconfirmed>()
        return result.outcome.shouldBeInstanceOf<ConfirmationResult.Required>()
    }

    /**
     * An application with two linked contacts, one status change after its first entry, one source with
     * three description snapshots, two interviews and two linked tasks.
     */
    private fun appliedWithContacts(): Application {
        val stored = fixtures.application("Backend Engineer")
        val source = ApplicationSource(SourceId(UUID.randomUUID()), stored.id, SourceKind.MANUAL_CHAT, null, NOW)
        val linked =
            stored.copy(
                contacts = setOf(ContactRef(UUID.randomUUID()), ContactRef(UUID.randomUUID())),
                sources = listOf(source),
            )
        fixtures.snapshots[stored.id] = 3
        fixtures.applications[stored.id] = linked
        fixtures.history +=
            StatusChange(stored.id, stored.status, ApplicationStatus.APPLIED, null, null, Actor.User, NOW)
        repeat(2) { interview(stored.id) }
        tasksOf(stored.id, 2)
        return linked
    }

    private fun tasksOf(
        application: ApplicationId,
        count: Int,
    ): List<EntityRef> =
        List(count) { EntityRef("task", UUID.randomUUID().toString()) }.also { fixtures.linkedTasks[application] = it }

    private fun interview(application: ApplicationId): Interview {
        val details = InterviewDetails(InterviewType.HR, InterviewTime(NOW, ZoneId.of("Europe/Berlin")))
        val interview = Interview.log(InterviewId(UUID.randomUUID()), application, details, Actor.User, NOW).interview
        fixtures.interviews[interview.id] = interview
        return interview
    }

    @Test
    fun `the first call only asks, counting the links, status changes, sources, snapshots, interviews and tasks`() {
        val application = appliedWithContacts()

        val required = firstStep(application.id)

        required.action.operation shouldBe Application.DELETE_OPERATION
        required.action.targets shouldBe listOf(application.id.value.toString())
        required.action.effect shouldBe
            ConfirmationEffect(
                "application",
                "Backend Engineer",
                mapOf(
                    "contactLinks" to 2,
                    "statusChanges" to 2,
                    "sources" to 1,
                    "snapshots" to 3,
                    "interviews" to 2,
                    "tasks" to 2,
                ),
            )
        fixtures.applications.size shouldBe 1
        fixtures.entries.shouldBeEmpty()
        fixtures.events.shouldBeEmpty()
    }

    @Test
    fun `the confirmed repeat deletes, records the title and announces the delete`() {
        val application = fixtures.application("Backend Engineer")
        val ai = ConfirmationRequester(Actor.Ai, "chat-7")

        delete.execute(application.id, ai, firstStep(application.id, ai).token) shouldBe ApplicationResult.Success(Unit)

        fixtures.applications.size shouldBe 0
        fixtures.history.shouldBeEmpty()
        val entry = fixtures.entries.single()
        entry.entity shouldBe application.id.toEntityRef()
        entry.actor shouldBe Actor.Ai
        entry.occurredAt shouldBe NOW
        entry.change.description shouldBe "Deleted application"
        entry.change.fieldChanges shouldContainExactly listOf(FieldChange("title", "Backend Engineer", null))
        fixtures.events shouldContainExactly listOf(ApplicationDeleted(application.id, Actor.Ai, NOW))
    }

    @Test
    fun `the confirmed repeat records each task whose link it clears, by id only, as the deleting actor`() {
        val application = appliedWithContacts()
        val tasks = fixtures.linkedTasks.getValue(application.id)
        val client = ConfirmationRequester(Actor.ExternalClient("claude-desktop"), "mcp-1")

        delete.execute(application.id, client, firstStep(application.id, client).token) shouldBe
            ApplicationResult.Success(Unit)

        fixtures.interviews.size shouldBe 0
        fixtures.linkedTasks.size shouldBe 0
        fixtures.entries.map { it.entity } shouldContainExactly listOf(application.id.toEntityRef()) + tasks
        fixtures.entries.map { it.actor }.toSet() shouldBe setOf(Actor.ExternalClient("claude-desktop"))
        fixtures.entries.map { it.occurredAt }.toSet() shouldBe setOf(NOW)
        fixtures.entries.drop(1).forEach {
            it.change.description shouldBe "Cleared the link to a deleted application"
            it.change.fieldChanges shouldContainExactly
                listOf(FieldChange("link", "application:${application.id.value}", null))
            it.change.toString() shouldNotContain "Backend Engineer"
        }
    }

    @Test
    fun `a new task link between the steps voids the token`() {
        val application = fixtures.application()
        val token = firstStep(application.id).token
        tasksOf(application.id, 1)

        rejected(delete.execute(application.id, user, token))
        fixtures.applications.size shouldBe 1
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `unreadable task links delete nothing`() {
        val application = fixtures.application()
        fixtures.linkedTasksAvailable = false

        delete.execute(application.id, user, ConfirmationToken("any")) shouldBe
            ApplicationResult.StorageFailure("read linked tasks")
        fixtures.applications.size shouldBe 1
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `a failing task entry rolls the delete back`() {
        val application = fixtures.application()
        tasksOf(application.id, 1)
        val token = firstStep(application.id).token
        fixtures.failingChangelogFor = "task"

        delete.execute(application.id, user, token) shouldBe ApplicationResult.StorageFailure("changelog")
        fixtures.applications.size shouldBe 1
        fixtures.linkedTasks.size shouldBe 1
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `a token is spent once and binds to its session`() {
        val application = fixtures.application()
        val token = firstStep(application.id).token

        rejected(delete.execute(application.id, ConfirmationRequester(Actor.User, "session-2"), token))
        rejected(delete.execute(application.id, user, token))
        fixtures.applications.size shouldBe 1
    }

    @Test
    fun `an edit between the steps voids the token`() {
        val application = fixtures.application("Backend Engineer")
        val token = firstStep(application.id).token
        fixtures.applications[application.id] =
            application.edit(ApplicationDetails("Staff Engineer", application.details.company), NOW)

        rejected(delete.execute(application.id, user, token))
        fixtures.applications.size shouldBe 1
    }

    @Test
    fun `a status change between the steps voids the token`() {
        val application = fixtures.application()
        val token = firstStep(application.id).token
        fixtures.history +=
            StatusChange(application.id, application.status, ApplicationStatus.APPLIED, null, null, Actor.User, NOW)

        rejected(delete.execute(application.id, user, token))
        fixtures.applications.size shouldBe 1
    }

    @Test
    fun `a new description snapshot between the steps voids the token`() {
        val application = fixtures.application()
        val token = firstStep(application.id).token
        fixtures.snapshots[application.id] = 1

        rejected(delete.execute(application.id, user, token))
        fixtures.applications.size shouldBe 1
    }

    @Test
    fun `a new interview between the steps voids the token`() {
        val application = fixtures.application()
        val token = firstStep(application.id).token
        interview(application.id)

        rejected(delete.execute(application.id, user, token))
        fixtures.applications.size shouldBe 1
    }

    @Test
    fun `an unknown application is not found, before any token`() {
        delete.execute(ApplicationId(UUID.randomUUID()), user, null) shouldBe ApplicationResult.NotFound
        delete.execute(ApplicationId(UUID.randomUUID()), user, ConfirmationToken("forged")) shouldBe
            ApplicationResult.NotFound
    }

    @Test
    fun `a failing changelog or event rolls the delete back`() {
        val application = fixtures.application()
        fixtures.failingChangelog = true
        delete.execute(application.id, user, firstStep(application.id).token) shouldBe
            ApplicationResult.StorageFailure("changelog")
        fixtures.applications.size shouldBe 1

        fixtures.failingChangelog = false
        fixtures.failingEvents = true
        delete.execute(application.id, user, firstStep(application.id).token) shouldBe
            ApplicationResult.StorageFailure("publish event")
        fixtures.applications.size shouldBe 1
        fixtures.history.size shouldBe 1
        fixtures.entries.shouldBeEmpty()
    }

    private fun rejected(result: ApplicationResult<Unit>) {
        val outcome = result.shouldBeInstanceOf<ApplicationResult.Unconfirmed>().outcome
        outcome.shouldBeInstanceOf<ConfirmationResult.Rejected>().reason.shouldBeInstanceOf<ConfirmationRejection>()
    }
}
