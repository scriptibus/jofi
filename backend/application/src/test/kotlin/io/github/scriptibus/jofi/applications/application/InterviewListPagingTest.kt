// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.domain.ApplicationField
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationViolation
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewDetails
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.InterviewSummary
import io.github.scriptibus.jofi.applications.domain.InterviewTime
import io.github.scriptibus.jofi.applications.domain.InterviewType
import io.github.scriptibus.jofi.applications.domain.SortDirection
import io.github.scriptibus.jofi.shared.domain.ai.FlaggedValue
import io.github.scriptibus.jofi.shared.domain.ai.NotesAudience
import io.github.scriptibus.jofi.shared.domain.paging.PageInput
import io.github.scriptibus.jofi.shared.domain.paging.PageRequest
import io.github.scriptibus.jofi.shared.domain.paging.Paged
import io.github.scriptibus.jofi.shared.domain.text.TextExcerpt
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Paging, direction and note excerpts of the interview list (#236, ADR-0056). */
class InterviewListPagingTest {
    private val fixtures = ApplicationFixtures()
    private val list = ListInterviewsUseCase(fixtures.repository, fixtures.interviewPort, fixtures.redaction)
    private val application = fixtures.application()
    private val first = Instant.parse("2026-10-01T08:00:00Z")

    private fun store(
        index: Int,
        notes: String? = null,
        preparation: String? = null,
    ): Interview {
        val time = InterviewTime(first.plusSeconds(index * 3600L), ZoneId.of("Europe/Berlin"))
        val details = InterviewDetails(InterviewType.PHONE_SCREEN, time, emptySet(), preparation, notes, null)
        val interview = Interview(InterviewId(UUID.randomUUID()), application.id, details, 0, first, first)
        fixtures.interviews[interview.id] = interview
        return interview
    }

    private fun page(
        page: Int,
        size: Int,
        direction: SortDirection,
    ): Paged<InterviewSummary> =
        list
            .execute(application.id, PageInput(page, size), direction, NotesAudience.USER)
            .shouldBeInstanceOf<ApplicationResult.Success<Paged<InterviewSummary>>>()
            .value

    @Test
    fun `120 interviews are reached exactly once over pages of 50, oldest or newest first`() {
        val stored = (1..120).map { store(it) }

        val oldest = (0..2).flatMap { page(it, 50, SortDirection.ASCENDING).items }.map { it.id }
        val newest = (0..2).flatMap { page(it, 50, SortDirection.DESCENDING).items }.map { it.id }

        oldest shouldContainExactly stored.map { it.id }
        newest shouldContainExactly stored.reversed().map { it.id }
        page(0, 50, SortDirection.DESCENDING).info.total shouldBe 120
        (0..2).map { page(it, 50, SortDirection.ASCENDING).info.hasMore } shouldContainExactly
            listOf(true, true, false)
    }

    @Test
    fun `the newest interview is on the first page when newest comes first`() {
        val stored = (1..60).map { store(it) }

        page(0, 5, SortDirection.DESCENDING).items.first().id shouldBe stored.last().id
        page(0, 5, SortDirection.ASCENDING).items.first().id shouldBe stored.first().id
    }

    @Test
    fun `both notes come as excerpts, cut and flagged`() {
        val long = "n".repeat(TextExcerpt.MAX_LENGTH + 7)
        store(1, notes = long, preparation = "short")

        val entry = page(0, 10, SortDirection.ASCENDING).items.single()

        entry.notesExcerpt shouldBe TextExcerpt("n".repeat(TextExcerpt.MAX_LENGTH), true)
        entry.preparationNotesExcerpt shouldBe TextExcerpt("short", false)
    }

    @Test
    fun `a page or size out of range is invalid and names what is wrong, an unknown application is not found`() {
        list.execute(application.id, PageInput(-1, 51), SortDirection.ASCENDING, NotesAudience.USER) shouldBe
            ApplicationResult.Invalid(
                listOf(
                    ApplicationViolation(ApplicationField.PAGE, ApplicationProblem.OUT_OF_RANGE),
                    ApplicationViolation(ApplicationField.SIZE, ApplicationProblem.OUT_OF_RANGE),
                ),
            )
        list.execute(
            ApplicationId(UUID.randomUUID()),
            PageInput(),
            SortDirection.ASCENDING,
            NotesAudience.USER,
        ) shouldBe
            ApplicationResult.NotFound
        PageRequest.MAX_SIZE shouldBe 50
    }

    @Test
    fun `for an AI the flagged values go out of both notes before the cut, so none is left half in`() {
        val phone = "0170 1234567"
        val straddling = "x".repeat(TextExcerpt.MAX_LENGTH - 5) + " " + phone + " end"
        store(1, notes = straddling, preparation = straddling)
        fixtures.flaggedValues = setOf(FlaggedValue(phone))

        val entry = aiPage().items.single()

        listOf(entry.notesExcerpt?.text.orEmpty(), entry.preparationNotesExcerpt?.text.orEmpty()).forEach {
            it.contains("0170") shouldBe false
            it.contains("1234") shouldBe false
            // The marker would be cut in two, so it is left out whole.
            it.contains("[") shouldBe false
        }
        // The user sees their own notes as they are.
        page(0, 10, SortDirection.ASCENDING)
            .items
            .single()
            .notesExcerpt
            ?.text
            .orEmpty()
            .endsWith("0170") shouldBe true
    }

    @Test
    fun `for an AI the list fails closed when the flags cannot be read`() {
        store(1, notes = "n")
        fixtures.flaggedValues = null

        list.execute(application.id, PageInput(), SortDirection.ASCENDING, NotesAudience.AI) shouldBe
            ApplicationResult.StorageFailure("privacy flags")
        page(0, 10, SortDirection.ASCENDING).items.size shouldBe 1
    }

    private fun aiPage() =
        list
            .execute(application.id, PageInput(), SortDirection.ASCENDING, NotesAudience.AI)
            .shouldBeInstanceOf<ApplicationResult.Success<Paged<InterviewSummary>>>()
            .value
}
