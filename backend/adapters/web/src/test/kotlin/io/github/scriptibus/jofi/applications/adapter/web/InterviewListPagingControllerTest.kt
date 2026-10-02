// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewDetails
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.InterviewTime
import io.github.scriptibus.jofi.applications.domain.InterviewType
import io.github.scriptibus.jofi.applications.domain.SortDirection
import io.github.scriptibus.jofi.shared.adapter.web.InvalidParameterAdvice
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.paging.PageInfo
import io.github.scriptibus.jofi.shared.domain.paging.PageRequest
import io.github.scriptibus.jofi.shared.domain.paging.Paged
import io.github.scriptibus.jofi.shared.domain.text.TextExcerpt
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Paging, direction and note excerpts of the interview list (#236, ADR-0056), over the real use cases. */
@WebMvcTest(InterviewController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(InterviewControllerTest.UseCases::class, InvalidParameterAdvice::class)
class InterviewListPagingControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val ports: InterviewControllerTest.Ports,
) {
    private val application =
        Application.create(
            ApplicationId(UUID.fromString("00000000-0000-0000-0000-0000000000a1")),
            ApplicationDetails("Backend Engineer", CompanyRef(UUID.fromString("00000000-0000-0000-0000-00000000000c"))),
            Instant.parse("2026-09-30T08:00:00Z"),
        )
    private val long = "n".repeat(TextExcerpt.MAX_LENGTH + 10)
    private val stored =
        Interview
            .log(
                InterviewId(UUID.fromString("00000000-0000-0000-0000-0000000000f1")),
                application.id,
                InterviewDetails(
                    InterviewType.TECHNICAL,
                    InterviewTime(Instant.parse("2026-10-05T08:00:00Z"), ZoneId.of("Europe/Berlin")),
                    preparationNotes = "short prep",
                    notes = long,
                ),
                Actor.User,
                Instant.parse("2026-09-30T09:00:00Z"),
            ).interview
    private val base = "/api/applications/${application.id.value}/interviews"

    @BeforeEach
    fun storeOne() {
        clearMocks(ports.applications, ports.interviews)
        every { ports.applications.findById(application.id) } returns ApplicationStoreResult.Success(application)
        every { ports.interviews.findById(application.id, stored.id) } returns ApplicationStoreResult.Success(stored)
    }

    @Test
    fun `the list is paged, oldest first by default and newest first on request, with excerpts`() {
        val request = slot<PageRequest>()
        val direction = slot<SortDirection>()
        every { ports.interviews.pageByApplication(application.id, capture(request), capture(direction)) } returns
            ApplicationStoreResult.Success(Paged(listOf(stored), PageInfo(2, 5, 11, true)))

        val body =
            mvc
                .get()
                .uri("$base?page=2&size=5&direction=DESCENDING")
                .assertThat()
                .hasStatusOk()
                .bodyJson()

        request.captured shouldBe PageRequest(2, 5)
        direction.captured shouldBe SortDirection.DESCENDING
        body.extractingPath("page").isEqualTo(mapOf("page" to 2, "size" to 5, "total" to 11, "hasMore" to true))
        body.extractingPath("interviews[0].notesExcerpt").isEqualTo("n".repeat(TextExcerpt.MAX_LENGTH))
        body.extractingPath("interviews[0].notesTruncated").isEqualTo(true)
        body.extractingPath("interviews[0].preparationNotesExcerpt").isEqualTo("short prep")
        body.extractingPath("interviews[0].preparationNotesTruncated").isEqualTo(false)
        body.extractingPath("interviews[0]").asMap().doesNotContainKeys("notes", "preparationNotes")

        mvc
            .get()
            .uri(base)
            .assertThat()
            .hasStatusOk()
        direction.captured shouldBe SortDirection.ASCENDING
    }

    @Test
    fun `reading one interview has the whole notes`() {
        mvc
            .get()
            .uri("$base/${stored.id.value}")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .extractingPath("notes")
            .isEqualTo(long)
    }

    @Test
    fun `a page, size or direction that cannot be read is a 400 with the documented violations, reading nothing`() {
        val unreadable = listOf("page=abc" to "page", "size=1.5" to "size", "direction=SIDEWAYS" to "direction")
        unreadable.forEach { (query, name) ->
            mvc
                .get()
                .uri("$base?$query")
                .assertThat()
                .hasStatus(400)
                .bodyJson()
                .isLenientlyEqualTo("""{"violations":[{"field":"$name","problem":"INVALID"}]}""")
        }
        verify(exactly = 0) { ports.interviews.pageByApplication(any(), any(), any()) }
    }

    @Test
    fun `a page or size out of range is a 400 naming it, reading nothing`() {
        mvc
            .get()
            .uri("$base?page=-1&size=51")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"violations":[{"field":"page","problem":"OUT_OF_RANGE"},{"field":"size","problem":"OUT_OF_RANGE"}]}
                """.trimIndent(),
            )
        verify(exactly = 0) { ports.interviews.pageByApplication(any(), any(), any()) }
    }
}
