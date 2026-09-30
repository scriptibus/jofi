// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.Actor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

class ApplicationTimelineTest {
    @Test
    fun `a position's token parses back to it, for numbered and uuid kinds`() {
        val numbered = TimelinePosition(AT, TimelineEntryKind.CHANGE, "42")
        val uuid = TimelinePosition(AT, TimelineEntryKind.TASK, HIGH.toString())

        TimelinePosition.parse(numbered.token()) shouldBe numbered
        TimelinePosition.parse(uuid.token()) shouldBe uuid
        numbered.number() shouldBe 42L
        uuid.uuid() shouldBe HIGH
    }

    @Test
    fun `a token this timeline did not give out parses to nothing`() {
        fun token(text: String) = Base64.getUrlEncoder().encodeToString(text.toByteArray())

        listOf(
            "not base64!",
            token("$AT|CHANGE"),
            token("yesterday|CHANGE|1"),
            token("$AT|SECRET|1"),
            token("$AT|CHANGE|${UUID.randomUUID()}"),
            token("$AT|TASK|1"),
            token("$AT|CHANGE|01"),
            token("$AT|TASK|ABCDEF00-0000-4000-8000-000000000000"),
            token("$AT|CHANGE|1|2"),
        ).forEach { TimelinePosition.parse(it).shouldBeNull() }
        shouldThrow<IllegalArgumentException> { TimelinePosition(AT, TimelineEntryKind.TASK, "1") }
    }

    @Test
    fun `positions order by time, then kind, then id as PostgreSQL does, unsigned for uuids`() {
        val later = AT.plusSeconds(1)
        val sorted =
            listOf(
                TimelinePosition(AT, TimelineEntryKind.CHANGE, "9"),
                TimelinePosition(AT, TimelineEntryKind.CHANGE, "10"),
                TimelinePosition(AT, TimelineEntryKind.STATUS_CHANGE, "1"),
                TimelinePosition(AT, TimelineEntryKind.TASK, LOW.toString()),
                TimelinePosition(AT, TimelineEntryKind.TASK, HIGH.toString()),
                TimelinePosition(later, TimelineEntryKind.CHANGE, "1"),
            )

        sorted.reversed().sorted() shouldContainExactly sorted
        (HIGH < LOW) shouldBe true
    }

    @Test
    fun `merging keeps the newest entries of all sources up to the limit and names where the next page starts`() {
        val change = TimelineEntry.Change(1, AT, Actor.User, listOf(TimelineEntry.ChangedField("title", null, null)))
        val status = TimelineEntry.StatusChanged(1, AT, Actor.Ai, null, ApplicationStatus.DISCOVERED, null)
        val task = TimelineEntry.TaskAdded(HIGH, AT.plusSeconds(1), "Call Erika", null)
        val interview =
            TimelineEntry.InterviewPlanned(
                InterviewId(LOW),
                AT.plusSeconds(2),
                InterviewType.HR,
                ZoneId.of("Europe/Berlin"),
                null,
            )

        val page = TimelinePage.merge(TimelineQuery(limit = 3), listOf(status, change), listOf(task), listOf(interview))

        page.entries shouldContainExactly listOf(interview, task, status)
        page.next shouldBe status.position
        TimelinePage
            .merge(
                TimelineQuery(limit = 4),
                listOf(status, change),
                listOf(task, interview),
            ).next
            .shouldBeNull()
    }

    @Test
    fun `a query holds 1 to 100 entries and reads one more, a task prints no title`() {
        TimelineQuery().limit shouldBe TimelineQuery.DEFAULT_LIMIT
        TimelineQuery(limit = TimelineQuery.MAX_LIMIT).fetchSize shouldBe TimelineQuery.MAX_LIMIT + 1
        shouldThrow<IllegalArgumentException> { TimelineQuery(limit = 0) }
        shouldThrow<IllegalArgumentException> { TimelineQuery(limit = TimelineQuery.MAX_LIMIT + 1) }
        TimelineEntry.TaskAdded(HIGH, AT, "Call Erika", null).toString() shouldNotContain "Erika"
    }

    @Test
    fun `every entry names its own kind in its position`() {
        val snapshot =
            TimelineEntry.DescriptionCaptured(SnapshotId(LOW), AT, SourceId(HIGH), SnapshotReason.MANUAL, null)

        snapshot.position shouldBe TimelinePosition(AT, TimelineEntryKind.DESCRIPTION_SNAPSHOT, LOW.toString())
        TimelineEntry.Change(7, AT, Actor.User, emptyList()).position.kind shouldBe TimelineEntryKind.CHANGE
        TimelineEntryKind.entries.last() shouldBe TimelineEntryKind.TASK
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-09-30T08:00:00.123456Z")
        val LOW: UUID = UUID.fromString("10000000-0000-4000-8000-000000000000")
        val HIGH: UUID = UUID.fromString("80000000-0000-4000-8000-000000000000")
    }
}
