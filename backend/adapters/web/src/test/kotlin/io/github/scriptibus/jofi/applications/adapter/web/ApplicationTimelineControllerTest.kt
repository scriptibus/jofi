// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.application.GetApplicationTimelineUseCase
import io.github.scriptibus.jofi.applications.application.port.ApplicationTimelineRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.spi.LinkedTasksPort
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.DeclineCategory
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.InterviewOutcome
import io.github.scriptibus.jofi.applications.domain.InterviewType
import io.github.scriptibus.jofi.applications.domain.SnapshotId
import io.github.scriptibus.jofi.applications.domain.SnapshotReason
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.applications.domain.TimelineEntry
import io.github.scriptibus.jofi.applications.domain.TimelineEntryKind
import io.github.scriptibus.jofi.applications.domain.TimelinePosition
import io.github.scriptibus.jofi.applications.domain.TimelineQuery
import io.github.scriptibus.jofi.shared.domain.Actor
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * `GET /api/applications/{id}/timeline` (#87) over the real use case with mocked sources: every entry kind in its
 * API shape, the cursor round trip, 400 for a foreign cursor or a limit out of range, 404 and 503. Security is tested
 * in bootstrap.
 */
@WebMvcTest(ApplicationTimelineController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(ApplicationTimelineControllerTest.UseCases::class)
class ApplicationTimelineControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val repository: ApplicationTimelineRepositoryPort,
    @param:Autowired private val tasks: LinkedTasksPort,
) {
    private val id = ApplicationId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val path = "/api/applications/${id.value}/timeline"
    private val task = UUID.fromString("00000000-0000-0000-0000-0000000000f1")

    /** What [ownEntries] and the linked task look like in the API, newest first. */
    private val expectedEntries =
        listOf(
            entry("TASK", "$task", "04", "task", """{"title":"Call Erika","completedAt":"2026-09-30T08:00:05Z"}"""),
            entry(
                "INTERVIEW",
                "00000000-0000-0000-0000-0000000000e1",
                "03",
                "interview",
                """{"type":"HR","localStart":"2026-09-30T10:00:03","timeZone":"Europe/Berlin",""" +
                    """"outcome":"PASSED"}""",
            ),
            entry(
                "DESCRIPTION_SNAPSHOT",
                "00000000-0000-0000-0000-0000000000d1",
                "02",
                "descriptionSnapshot",
                """{"sourceId":"00000000-0000-0000-0000-0000000000b1","reason":"DISCOVERY","frozenAt":null}""",
            ),
            entry(
                "STATUS_CHANGE",
                "7",
                "01",
                "statusChange",
                """{"actor":{"kind":"SCANNER","name":"arbeitsagentur"},"from":"APPLIED","to":"REJECTED",""" +
                    """"declineCategory":"SKILLS"}""",
            ),
            entry(
                "CHANGE",
                "3",
                "00",
                "change",
                """{"actor":{"kind":"USER","name":null},"fields":["title","location"]}""",
            ),
        )

    @BeforeEach
    fun sources() {
        clearMocks(repository, tasks)
        every { repository.entries(any(), any()) } returns ApplicationStoreResult.NotFound
        every { tasks.linkedTasks(any(), any(), any()) } returns LinkedTasksPort.Tasks.Listed(emptyList())
    }

    @Test
    fun `every kind of entry has its own detail property, newest first`() {
        every { repository.entries(id, TimelineQuery()) } returns ApplicationStoreResult.Success(ownEntries())
        every { tasks.linkedTasks(id.value, null, TimelineQuery.DEFAULT_LIMIT + 1) } returns
            LinkedTasksPort.Tasks.Listed(
                listOf(LinkedTasksPort.LinkedTask(task, "Call Erika", AT.plusSeconds(4), AT.plusSeconds(5))),
            )

        mvc
            .get()
            .uri(path)
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isStrictlyEqualTo("""{"nextCursor":null,"entries":[${expectedEntries.joinToString(",")}]}""")
    }

    /** One entry as JSON: [detail] set under [property], every other detail property null. */
    private fun entry(
        kind: String,
        id: String,
        second: String,
        property: String,
        detail: String,
    ): String {
        val details =
            listOf("change", "statusChange", "descriptionSnapshot", "interview", "task").joinToString(",") {
                "\"$it\":${if (it == property) detail else "null"}"
            }
        return """{"kind":"$kind","id":"$id","occurredAt":"2026-09-30T08:00:${second}Z",$details}"""
    }

    @Test
    fun `a full page names the next one, whose cursor continues after its last entry`() {
        val newer = TimelineEntry.Change(4, AT.plusSeconds(1), Actor.User, listOf("title"))
        val older = TimelineEntry.Change(3, AT, Actor.User, listOf("title"))
        every { repository.entries(id, TimelineQuery(null, 1)) } returns
            ApplicationStoreResult.Success(listOf(newer, older))
        every { repository.entries(id, TimelineQuery(newer.position, 1)) } returns
            ApplicationStoreResult.Success(listOf(older))

        val cursor = newer.position.token()
        mvc
            .get()
            .uri(
                "$path?limit=1",
            ).assertThat()
            .hasStatusOk()
            .bodyJson()
            .extractingPath("nextCursor")
            .isEqualTo(cursor)
        mvc
            .get()
            .uri("$path?limit=1&cursor=$cursor")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"entries":[{"kind":"CHANGE","id":"3"}],"nextCursor":null}""")
    }

    @Test
    fun `a cursor this timeline did not give out and a limit out of range are a 400 naming both`() {
        mvc
            .get()
            .uri("$path?cursor=bm9wZQ&limit=101")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"type":"${ApplicationProblems.INVALID_TIMELINE_QUERY}",
                 "violations":[{"field":"cursor","problem":"INVALID_CURSOR"},{"field":"limit","problem":"OUT_OF_RANGE"}]}
                """.trimIndent(),
            )
        mvc
            .get()
            .uri("$path?limit=0")
            .assertThat()
            .hasStatus(400)
        verify(exactly = 0) { repository.entries(any(), any()) }
    }

    @Test
    fun `an unknown application is a 404, an unreadable source a 503`() {
        mvc
            .get()
            .uri(path)
            .assertThat()
            .hasStatus(404)

        every { repository.entries(id, any()) } returns ApplicationStoreResult.Success(emptyList())
        every { tasks.linkedTasks(id.value, any(), any()) } returns LinkedTasksPort.Tasks.Unavailable
        mvc
            .get()
            .uri(path)
            .assertThat()
            .hasStatus(503)
    }

    @Test
    fun `the API's kinds are the domain's`() {
        TimelineKind.entries.map { it.name } shouldBe TimelineEntryKind.entries.map { it.name }
    }

    private fun ownEntries(): List<TimelineEntry> =
        listOf(
            TimelineEntry.InterviewPlanned(
                InterviewId(UUID.fromString("00000000-0000-0000-0000-0000000000e1")),
                AT.plusSeconds(3),
                InterviewType.HR,
                ZoneId.of("Europe/Berlin"),
                InterviewOutcome.PASSED,
            ),
            TimelineEntry.DescriptionCaptured(
                SnapshotId(UUID.fromString("00000000-0000-0000-0000-0000000000d1")),
                AT.plusSeconds(2),
                SourceId(UUID.fromString("00000000-0000-0000-0000-0000000000b1")),
                SnapshotReason.DISCOVERY,
                null,
            ),
            TimelineEntry.StatusChanged(
                7,
                AT.plusSeconds(1),
                Actor.Scanner("arbeitsagentur"),
                ApplicationStatus.APPLIED,
                ApplicationStatus.REJECTED,
                DeclineCategory.SKILLS,
            ),
            TimelineEntry.Change(3, AT, Actor.User, listOf("title", "location")),
        )

    class UseCases {
        @Bean
        fun repository() = mockk<ApplicationTimelineRepositoryPort>()

        @Bean
        fun tasks() = mockk<LinkedTasksPort>()

        @Bean
        fun timeline(
            repository: ApplicationTimelineRepositoryPort,
            tasks: LinkedTasksPort,
        ) = GetApplicationTimelineUseCase(repository, tasks)
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-09-30T08:00:00Z")
    }
}
