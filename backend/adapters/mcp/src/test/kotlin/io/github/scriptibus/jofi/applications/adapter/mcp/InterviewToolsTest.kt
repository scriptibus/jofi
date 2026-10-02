// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.application.ListInterviewsUseCase
import io.github.scriptibus.jofi.applications.application.ListUpcomingInterviewsUseCase
import io.github.scriptibus.jofi.applications.application.LogInterviewUseCase
import io.github.scriptibus.jofi.applications.application.UpdateInterviewUseCase
import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.InterviewRepositoryPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewDetails
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.InterviewOutcome
import io.github.scriptibus.jofi.applications.domain.InterviewTime
import io.github.scriptibus.jofi.applications.domain.InterviewType
import io.github.scriptibus.jofi.applications.domain.UpcomingInterview
import io.github.scriptibus.jofi.shared.adapter.mcp.ArgumentProblem
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolArguments
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolTestPorts
import io.github.scriptibus.jofi.shared.adapter.mcp.Untrusted
import io.github.scriptibus.jofi.shared.application.port.DomainEventPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** The interview tools over the real use cases with mocked repositories: arguments in, results out. */
class InterviewToolsTest {
    private val applications = mockk<ApplicationRepositoryPort>()
    private val interviews = mockk<InterviewRepositoryPort>()
    private val events = mockk<DomainEventPort>()
    private val changelog = ToolTestPorts.RecordingChangelog()
    private val transactions = ToolTestPorts.transactions
    private val clock = ToolTestPorts.clock

    private val log =
        LogInterviewTool(LogInterviewUseCase(applications, interviews, events, changelog, transactions, clock))
    private val update =
        UpdateInterviewTool(UpdateInterviewUseCase(applications, interviews, events, changelog, transactions, clock))
    private val list = ListInterviewsTool(ListInterviewsUseCase(applications, interviews))
    private val upcoming = ListUpcomingInterviewsTool(ListUpcomingInterviewsUseCase(interviews, clock))

    private val applicationId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val interviewId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    private val contact = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val application =
        Application.create(
            ApplicationId(applicationId),
            ApplicationDetails("Backend Engineer", CompanyRef(UUID.randomUUID())),
            at,
        )
    private val time =
        InterviewTime.of(LocalDateTime.of(2026, 10, 5, 10, 0), ZoneId.of("Europe/Berlin")) ?: error("in range")
    private val stored =
        Interview(
            InterviewId(interviewId),
            ApplicationId(applicationId),
            InterviewDetails(InterviewType.TECHNICAL, time, setOf(ContactRef(contact)), notes = "Went well"),
            version = 0,
            createdAt = at,
            updatedAt = at,
        )

    init {
        every { applications.findById(ApplicationId(applicationId)) } returns
            ApplicationStoreResult.Success(application)
        every { events.publish(any()) } returns true
    }

    @Test
    fun `log_interview stores the validated interview, logged with the caller as actor, notes untrusted`() {
        val added = slot<Interview>()
        every { interviews.add(capture(added)) } returns ApplicationStoreResult.Success(Unit)

        val answer = log.call(call(*interviewArguments("applicationId" to "$applicationId")))

        val details = added.captured.details
        details.type shouldBe InterviewType.TECHNICAL
        details.time shouldBe time
        details.participants shouldBe setOf(ContactRef(contact))
        details.preparationNotes shouldBe "Read the posting"
        details.outcome shouldBe InterviewOutcome.PASSED
        changelog.entries.single().actor shouldBe Actor.Ai
        val result = answer.shouldBeInstanceOf<ToolAnswer.Result>().value.shouldBeInstanceOf<InterviewResult>()
        result.version shouldBe 0
        result.localStart shouldBe LocalDateTime.of(2026, 10, 5, 10, 0)
        result.participantIds shouldBe listOf(contact)
        result.interview shouldBe Untrusted(InterviewNotes("Read the posting", "Went well"))
    }

    @Test
    fun `explicit nulls for the optional arguments mean not set`() {
        val added = slot<Interview>()
        every { interviews.add(capture(added)) } returns ApplicationStoreResult.Success(Unit)
        val nulls = listOf("participantIds", "interview", "outcome").map { it to null }.toTypedArray()

        log.call(call("applicationId" to "$applicationId", *nulls, *required())).shouldBeInstanceOf<ToolAnswer.Result>()

        added.captured.details shouldBe InterviewDetails(InterviewType.TECHNICAL, time)
    }

    @Test
    fun `invalid interviews name the arguments and store nothing`() {
        every { interviews.add(any()) } returns ApplicationStoreResult.ContactNotFound
        val application = "applicationId" to "$applicationId"
        val past = arrayOf("type" to "HR", "localStart" to "1999-01-01T10:00")

        problemsOf(call(application, *past, "timeZone" to "Mars")) shouldBe
            listOf(ArgumentProblem("timeZone", "invalid-time-zone"))
        problemsOf(call(application, *past, "timeZone" to "UTC")) shouldBe
            listOf(ArgumentProblem("localStart", "out-of-range"))
        problemsOf(call(application, *required())) shouldBe listOf(ArgumentProblem("participantIds", "not-found"))
        changelog.entries shouldBe emptyList()
    }

    private fun problemsOf(call: ToolCall) = log.call(call).shouldBeInstanceOf<ToolAnswer.Error>().problems

    @Test
    fun `an unknown application is not found and arguments of the wrong shape name themselves`() {
        every { applications.findById(ApplicationId(MISSING)) } returns ApplicationStoreResult.NotFound

        log.call(call("applicationId" to "$MISSING", *required())) shouldBe
            ToolAnswer.Error("not-found", "No application has this id.")
        shouldThrow<InvalidToolArgument> { log.call(call(*required())) }.argument shouldBe "applicationId"
        shouldThrow<InvalidToolArgument> {
            log.call(call("applicationId" to "$applicationId", "type" to "HR", "timeZone" to "UTC"))
        }.argument shouldBe "localStart"
        shouldThrow<InvalidToolArgument> { list.call(call()) }.argument shouldBe "applicationId"
    }

    @Test
    fun `update_interview replaces the details based on the version and logs the caller`() {
        val edited = slot<Interview>()
        every { interviews.findById(ApplicationId(applicationId), InterviewId(interviewId)) } returns
            ApplicationStoreResult.Success(stored)
        every { interviews.update(capture(edited)) } returns ApplicationStoreResult.Success(Unit)

        val answer =
            update.call(call(*identity(0), *required(), "outcome" to "REJECTED", "interview" to mapOf("notes" to null)))

        edited.captured.details.outcome shouldBe InterviewOutcome.REJECTED
        edited.captured.details.notes shouldBe null
        edited.captured.version shouldBe 1
        changelog.entries.single().actor shouldBe Actor.Ai
        answer
            .shouldBeInstanceOf<ToolAnswer.Result>()
            .value
            .shouldBeInstanceOf<InterviewResult>()
            .version shouldBe 1
    }

    @Test
    fun `update_interview answers a stale version and a missing interview without storing`() {
        every { interviews.findById(any(), InterviewId(interviewId)) } returns ApplicationStoreResult.Success(stored)
        every { interviews.findById(any(), InterviewId(MISSING)) } returns ApplicationStoreResult.NotFound

        val stale = update.call(call(*identity(5), *required()))
        stale.shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "version-conflict"
        update.call(call("applicationId" to "$applicationId", "id" to "$MISSING", "version" to 0, *required())) shouldBe
            ToolAnswer.Error("not-found", "No such interview.")
        verify(exactly = 0) { interviews.update(any()) }
        changelog.entries shouldBe emptyList()
    }

    @Test
    fun `update_interview refuses an argument that carries the withheld marker`() {
        val answer = update.call(call(*identity(0), *required(), "interview" to mapOf("notes" to "call [withheld]")))

        answer.shouldBeInstanceOf<ToolAnswer.Error>().problems shouldBe
            listOf(ArgumentProblem("interview.notes", "withheld-value"))
        verify(exactly = 0) { interviews.findById(any(), any()) }
    }

    @Test
    fun `log_interview refuses a withheld marker, update and log name the nested argument, store nothing`() {
        val marker = "interview" to mapOf("preparationNotes" to "see [withheld]")
        val answer = log.call(call("applicationId" to "$applicationId", *required(), marker))

        answer.shouldBeInstanceOf<ToolAnswer.Error>().problems shouldBe
            listOf(ArgumentProblem("interview.preparationNotes", "withheld-value"))
        verify(exactly = 0) { interviews.add(any()) }
    }

    @Test
    fun `the update schema requires every property, the log schema only the identifying ones`() {
        val updateRequired = requiredOf(update.inputSchema)
        val logRequired = requiredOf(log.inputSchema)

        updateRequired shouldBe
            setOf(
                "applicationId",
                "id",
                "version",
                "type",
                "localStart",
                "timeZone",
                "participantIds",
                "outcome",
                "interview",
            )
        logRequired shouldBe setOf("applicationId", "type", "localStart", "timeZone")
        update.inputSchema.contains("\"required\": [\"preparationNotes\", \"notes\"]") shouldBe true
    }

    private fun requiredOf(schema: String): Set<String> =
        Regex("\"required\": \\[([^\\]]*)]")
            .let { checkNotNull(it.find(schema)) }
            .groupValues[1]
            .split(",")
            .map { it.trim().trim('"') }
            .toSet()

    @Test
    fun `list_upcoming_interviews answers the soonest first without notes, the title untrusted`() {
        every { interviews.upcoming(any(), Interview.MAX_UPCOMING) } returns
            ApplicationStoreResult.Success(listOf(UpcomingInterview(stored, "Backend Engineer")))

        val result =
            upcoming
                .call(call())
                .shouldBeInstanceOf<ToolAnswer.Result>()
                .value
                .shouldBeInstanceOf<UpcomingInterviewsResult>()

        val first = result.interviews.single()
        first.applicationId shouldBe applicationId
        first.application shouldBe Untrusted(ApplicationTitle("Backend Engineer"))
        first.startsAt shouldBe time.startsAt
    }

    @Test
    fun `a failing store answers unavailable without its operation`() {
        every { interviews.upcoming(any(), any()) } returns ApplicationStoreResult.StorageFailure("secret operation")

        upcoming.call(call()) shouldBe ToolAnswer.Error("unavailable", "Applications cannot be used now.")
    }

    private fun required(): Array<Pair<String, Any?>> =
        arrayOf("type" to "TECHNICAL", "localStart" to "2026-10-05T10:00", "timeZone" to "Europe/Berlin")

    private fun identity(version: Int): Array<Pair<String, Any?>> =
        arrayOf("applicationId" to "$applicationId", "id" to "$interviewId", "version" to version)

    private fun interviewArguments(vararg more: Pair<String, Any?>): Array<Pair<String, Any?>> =
        arrayOf(
            *required(),
            "participantIds" to listOf("$contact"),
            "interview" to mapOf("preparationNotes" to " Read the posting ", "notes" to "Went well"),
            "outcome" to "PASSED",
            *more,
        )

    private fun call(vararg arguments: Pair<String, Any?>) = ToolCall(ToolArguments(mapOf(*arguments)), Actor.Ai)

    private companion object {
        val MISSING: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
    }
}
