// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.application.GetInterviewUseCase
import io.github.scriptibus.jofi.applications.application.ListInterviewsUseCase
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
import io.github.scriptibus.jofi.applications.domain.InterviewTime
import io.github.scriptibus.jofi.applications.domain.InterviewType
import io.github.scriptibus.jofi.applications.domain.SortDirection
import io.github.scriptibus.jofi.shared.adapter.mcp.ArgumentProblem
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolArguments
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolTestPorts
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.paging.PageInfo
import io.github.scriptibus.jofi.shared.domain.paging.PageRequest
import io.github.scriptibus.jofi.shared.domain.paging.Paged
import io.github.scriptibus.jofi.shared.domain.text.TextExcerpt
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

/** The interview list and read tools over the real use cases with mocked repositories (#236, ADR-0056). */
class InterviewListToolsTest {
    private val applications = mockk<ApplicationRepositoryPort>()
    private val interviews = mockk<InterviewRepositoryPort>()
    private val list = ListInterviewsTool(ListInterviewsUseCase(applications, interviews, ToolTestPorts.redaction))
    private val get = GetInterviewTool(GetInterviewUseCase(applications, interviews))

    private val applicationId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val interviewId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    private val contact = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val long = "n".repeat(TextExcerpt.MAX_LENGTH + 5)
    private val time =
        InterviewTime.of(LocalDateTime.of(2026, 10, 5, 10, 0), ZoneId.of("Europe/Berlin")) ?: error("in range")
    private val stored =
        Interview(
            InterviewId(interviewId),
            ApplicationId(applicationId),
            InterviewDetails(
                InterviewType.TECHNICAL,
                time,
                setOf(ContactRef(contact)),
                preparationNotes = "short prep",
                notes = long,
            ),
            version = 4,
            createdAt = at,
            updatedAt = at,
        )

    init {
        every { applications.findById(ApplicationId(applicationId)) } returns
            ApplicationStoreResult.Success(
                Application.create(
                    ApplicationId(applicationId),
                    ApplicationDetails("Backend Engineer", CompanyRef(UUID.randomUUID())),
                    at,
                ),
            )
        every { interviews.findById(ApplicationId(applicationId), InterviewId(interviewId)) } returns
            ApplicationStoreResult.Success(stored)
    }

    private fun listed(vararg arguments: Pair<String, Any?>) =
        list
            .call(call("applicationId" to "$applicationId", *arguments))
            .shouldBeInstanceOf<ToolAnswer.Result>()
            .value
            .shouldBeInstanceOf<InterviewListResult>()

    @Test
    fun `list_interviews answers a page of excerpts under their own keys, with where the page sits`() {
        val request = slot<PageRequest>()
        every { interviews.pageByApplication(any(), capture(request), any()) } returns
            ApplicationStoreResult.Success(Paged(listOf(stored), PageInfo(1, 5, 11, true)))

        val result = listed("page" to 1, "size" to 5)

        request.captured shouldBe PageRequest(1, 5)
        listOf(result.page, result.size, result.total, result.hasMore) shouldBe listOf(1, 5, 11, true)
        val entry = result.interviews.single()
        entry.id shouldBe interviewId
        entry.version shouldBe 4
        entry.interview.content.notesExcerpt shouldBe "n".repeat(TextExcerpt.MAX_LENGTH)
        entry.interview.content.notesTruncated shouldBe true
        entry.interview.content.preparationNotesExcerpt shouldBe "short prep"
        entry.interview.content.preparationNotesTruncated shouldBe false
    }

    @Test
    fun `list_interviews is newest first unless told otherwise`() {
        val direction = slot<SortDirection>()
        every { interviews.pageByApplication(any(), any(), capture(direction)) } returns
            ApplicationStoreResult.Success(Paged(emptyList(), PageInfo(0, 20, 0, false)))

        listed()
        direction.captured shouldBe SortDirection.DESCENDING
        listed("direction" to "ASCENDING")
        direction.captured shouldBe SortDirection.ASCENDING
        shouldThrow<InvalidToolArgument> { listed("direction" to "SIDEWAYS") }.argument shouldBe "direction"
    }

    @Test
    fun `a page or size out of range is invalid, named by argument, and reads nothing`() {
        list.call(call("applicationId" to "$applicationId", "page" to -1, "size" to 51)) shouldBe
            ToolAnswer.Error(
                "invalid-arguments",
                "The arguments are invalid.",
                listOf(ArgumentProblem("page", "out-of-range"), ArgumentProblem("size", "out-of-range")),
            )
        verify(exactly = 0) { interviews.pageByApplication(any(), any(), any()) }
    }

    @Test
    fun `get_interview has the whole notes, the participants and the version, and is read only`() {
        val result =
            get
                .call(call("applicationId" to "$applicationId", "id" to "$interviewId"))
                .shouldBeInstanceOf<ToolAnswer.Result>()
                .value
                .shouldBeInstanceOf<InterviewResult>()

        result.interview.content shouldBe InterviewNotes("short prep", long)
        result.participantIds shouldBe listOf(contact)
        result.version shouldBe 4
        get.readOnly shouldBe true
        list.readOnly shouldBe true
        shouldThrow<InvalidToolArgument> { get.call(call("applicationId" to "$applicationId")) }.argument shouldBe "id"
    }

    private fun call(vararg arguments: Pair<String, Any?>) = ToolCall(ToolArguments(mapOf(*arguments)), Actor.Ai)
}
