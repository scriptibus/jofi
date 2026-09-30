// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.application.GetApplicationTimelineUseCase
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.InterviewType
import io.github.scriptibus.jofi.applications.domain.TimelineEntry
import io.github.scriptibus.jofi.applications.domain.TimelineEntryKind
import io.github.scriptibus.jofi.applications.domain.TimelinePage
import io.github.scriptibus.jofi.applications.domain.TimelinePosition
import io.github.scriptibus.jofi.applications.domain.TimelineQuery
import io.github.scriptibus.jofi.shared.adapter.persistence.ChangelogRepository
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_STATUS_CHANGE
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.INTERVIEW
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangeSummary
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.github.scriptibus.jofi.tasks.adapter.persistence.LinkedTasksRepository
import io.github.scriptibus.jofi.tasks.adapter.persistence.TaskRepository
import io.github.scriptibus.jofi.tasks.domain.ApplicationRef
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDetails
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskStateChange
import io.github.scriptibus.jofi.tasks.domain.TaskStoreResult
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import io.github.scriptibus.jofi.tasks.domain.TaskTransition
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.jooq.ExecuteContext
import org.jooq.ExecuteListener
import org.jooq.impl.DSL
import org.jooq.impl.DefaultExecuteListenerProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The timeline over its real sources on PostgreSQL migrated from zero (#87): the applications context's own
 * (`ApplicationTimelineRepository`) and the tasks context's (`LinkedTasksRepository`), merged by the use case newest
 * first, ties at one instant by kind and id, paged by cursor without gaps or repeats, one query per source.
 */
class ApplicationTimelineOnPostgresTest {
    private lateinit var dsl: DSLContext
    private lateinit var timeline: GetApplicationTimelineUseCase
    private lateinit var tasks: TaskRepository
    private var queries = 0
    private var application = ApplicationId(UUID(0, 0))
    private var other = ApplicationId(UUID(0, 0))
    private var source = UUID(0, 0)

    @BeforeEach
    fun migrateFromZero() {
        val migrated = PostgresTestDatabase.migratedFromZero()
        val counter =
            object : ExecuteListener {
                override fun executeStart(ctx: ExecuteContext) {
                    queries++
                }
            }
        dsl = migrated.configuration().derive(DefaultExecuteListenerProvider(counter)).dsl()
        timeline = GetApplicationTimelineUseCase(ApplicationTimelineRepository(dsl), LinkedTasksRepository(dsl))
        tasks = TaskRepository(dsl)
        val rows = ApplicationRows(dsl)
        val company = rows.company()
        application = ApplicationId(UUID.randomUUID()).also { rows.application(it.value, company) }
        other = ApplicationId(UUID.randomUUID()).also { rows.application(it.value, company) }
        source = UUID.randomUUID()
        dsl.execute(
            "insert into application_source (id, application_id, kind, discovered_at) " +
                "values (?, ?, 'MANUAL_CHAT', ?::timestamptz)",
            source,
            application.value,
            CREATED.atOffset(ZoneOffset.UTC),
        )
    }

    /** Every source at one instant plus older and newer entries; returns them in the order the timeline shows. */
    private fun seed(): List<TimelinePosition> {
        val created =
            change(CREATED, FieldChange("title", null, "Backend Engineer"), FieldChange("company", null, "c"))
        val initial = status(CREATED, null, ApplicationStatus.DISCOVERED)
        val snapshot = snapshot(CREATED)
        val interviewAtCreation = interview(CREATED)
        val lowTask = task(CREATED, LOW)
        val highTask = task(CREATED, HIGH)
        val edited = change(LATER, FieldChange("location", null, "Berlin"))
        val applied = status(LATER, ApplicationStatus.DISCOVERED, ApplicationStatus.APPLIED)
        change(LATER, FieldChange("status", "DISCOVERED", "APPLIED"))
        val done = task(LATER, UUID.randomUUID(), done = true)
        val upcoming = interview(LATEST)
        suggestion(LATEST)
        task(LATEST, UUID.randomUUID(), link = other)
        change(LATEST, FieldChange("title", null, "Elsewhere"), entity = other)
        // Unsigned (as PostgreSQL orders uuid) HIGH is above LOW; signed, as UUID.compareTo, it would be below.
        return listOf(
            upcoming,
            done,
            applied,
            edited,
            highTask,
            lowTask,
            interviewAtCreation,
            snapshot,
            initial,
            created,
        )
    }

    @Test
    fun `merges every source newest first, one instant by kind and id, without status-only changes or suggestions`() {
        val expected = seed()

        page(TimelineQuery()).entries.map { it.position } shouldBe expected
    }

    @Test
    fun `pages of any size walk the whole timeline without gaps or repeats`() {
        val expected = seed()

        (1..4).forEach { size -> walk(size) shouldBe expected }
    }

    @Test
    fun `a change names its fields but none of their values, a task has its title and completion`() {
        seed()

        val entries = page(TimelineQuery()).entries
        val created = entries.last().shouldBeInstanceOf<TimelineEntry.Change>()
        created.fields shouldBe listOf("title", "company")
        created.actor shouldBe Actor.User
        created.toString() shouldNotContain "Backend Engineer"
        val done = entries[1].shouldBeInstanceOf<TimelineEntry.TaskAdded>()
        done.title shouldBe "Send the portfolio"
        done.completedAt shouldBe LATER
        entries.first().shouldBeInstanceOf<TimelineEntry.InterviewPlanned>().type shouldBe InterviewType.TECHNICAL
    }

    @Test
    fun `one query per source and one for the application, however many entries`() {
        repeat(MANY) { change(CREATED.plusSeconds(it.toLong()), FieldChange("location", null, "x")) }
        repeat(MANY) { task(CREATED.plusSeconds(it.toLong()), UUID.randomUUID()) }
        queries = 0

        page(TimelineQuery(limit = TimelineQuery.MAX_LIMIT)).entries.size shouldBe 2 * MANY
        queries shouldBe 6
    }

    @Test
    fun `an unknown application is not found, a failing source a storage failure`() {
        timeline.execute(ApplicationId(UUID.randomUUID()), TimelineQuery()) shouldBe ApplicationResult.NotFound

        dsl.execute("drop table task")
        timeline.execute(application, TimelineQuery()) shouldBe ApplicationResult.StorageFailure("linkedTasks")
        dsl.execute("drop table interview cascade")
        timeline.execute(application, TimelineQuery()) shouldBe ApplicationResult.StorageFailure("timeline")
    }

    /** Follows the cursors with pages of [size] and returns every position seen. */
    private fun walk(size: Int): List<TimelinePosition> {
        val seen = mutableListOf<TimelinePosition>()
        var next: TimelinePosition? = null
        do {
            val page = page(TimelineQuery(next, size))
            if (page.next != null) page.entries.size shouldBe size
            seen += page.entries.map { it.position }
            next = page.next?.let { TimelinePosition.parse(it.token()) }
        } while (next != null)
        return seen
    }

    private fun page(query: TimelineQuery): TimelinePage =
        timeline.execute(application, query).shouldBeInstanceOf<ApplicationResult.Success<TimelinePage>>().value

    private fun change(
        at: Instant,
        vararg fields: FieldChange,
        entity: ApplicationId = application,
    ): TimelinePosition {
        val summary = ChangeSummary("Edited application", fields.toList())
        val entry = ChangelogEntry(entity.toEntityRef(), Actor.User, at, summary)
        ChangelogRepository(dsl).append(entry)
        val id =
            dsl
                .select(DSL.max(CHANGELOG_ENTRY.ID))
                .from(CHANGELOG_ENTRY)
                .fetchSingle()
                .value1()
        return TimelinePosition(at, TimelineEntryKind.CHANGE, id.toString())
    }

    private fun status(
        at: Instant,
        from: ApplicationStatus?,
        to: ApplicationStatus,
    ): TimelinePosition {
        val id =
            dsl
                .insertInto(APPLICATION_STATUS_CHANGE)
                .set(APPLICATION_STATUS_CHANGE.APPLICATION_ID, application.value)
                .set(APPLICATION_STATUS_CHANGE.FROM_STATUS, from?.name)
                .set(APPLICATION_STATUS_CHANGE.TO_STATUS, to.name)
                .set(APPLICATION_STATUS_CHANGE.ACTOR_KIND, "USER")
                .set(APPLICATION_STATUS_CHANGE.CHANGED_AT, at.atOffset(ZoneOffset.UTC))
                .returning(APPLICATION_STATUS_CHANGE.ID)
                .fetchSingle()
                .id
        return TimelinePosition(at, TimelineEntryKind.STATUS_CHANGE, id.toString())
    }

    private fun snapshot(at: Instant): TimelinePosition {
        val id = UUID.randomUUID()
        dsl.execute(
            "insert into application_description_snapshot (id, source_id, description, content_hash, reason, " +
                "captured_at) values (?, ?, 'Kotlin', encode(sha256(convert_to('Kotlin', 'UTF8')), 'hex'), " +
                "'DISCOVERY', ?::timestamptz)",
            id,
            source,
            at.atOffset(ZoneOffset.UTC),
        )
        return TimelinePosition(at, TimelineEntryKind.DESCRIPTION_SNAPSHOT, id.toString())
    }

    private fun interview(startsAt: Instant): TimelinePosition {
        val record =
            dsl.newRecord(INTERVIEW).apply {
                id = UUID.randomUUID()
                applicationId = application.value
                kind = InterviewType.TECHNICAL.name
                this.startsAt = startsAt.atOffset(ZoneOffset.UTC)
                timeZone = "Europe/Berlin"
                notes = "Talked to Erika"
                createdAt = CREATED.atOffset(ZoneOffset.UTC)
                updatedAt = CREATED.atOffset(ZoneOffset.UTC)
            }
        record.insert()
        return TimelinePosition(startsAt, TimelineEntryKind.INTERVIEW, record.id.toString())
    }

    private fun task(
        createdAt: Instant,
        id: UUID,
        done: Boolean = false,
        link: ApplicationId = application,
    ): TimelinePosition {
        val details = TaskDetails("Send the portfolio", TaskTiming.Bucket.SOMEDAY, ApplicationRef(link.value))
        val task = Task.create(TaskId(id), details, TaskOrigin.Manual, createdAt)
        tasks.add(task) shouldBe TaskStoreResult.Success(Unit)
        if (done) {
            val completed = task.apply(TaskTransition.COMPLETE, LATER).shouldBeInstanceOf<TaskStateChange.Changed>()
            tasks.update(completed.task) shouldBe TaskStoreResult.Success(Unit)
        }
        return TimelinePosition(createdAt, TimelineEntryKind.TASK, id.toString())
    }

    private fun suggestion(createdAt: Instant) {
        val details = TaskDetails("Follow up", TaskTiming.Bucket.SOMEDAY, ApplicationRef(application.value))
        val origin = TaskOrigin.Suggested("follow-up", "application:${application.value}")
        val suggested = Task.suggest(TaskId(UUID.randomUUID()), details, origin, createdAt)
        tasks.add(suggested) shouldBe TaskStoreResult.Success(Unit)
    }

    private companion object {
        const val MANY = 25
        val CREATED: Instant = Instant.parse("2026-09-01T08:00:00.123456Z")
        val LATER: Instant = Instant.parse("2026-09-10T08:00:00Z")
        val LATEST: Instant = Instant.parse("2026-10-05T08:00:00Z")
        val LOW: UUID = UUID.fromString("10000000-0000-4000-8000-000000000000")
        val HIGH: UUID = UUID.fromString("80000000-0000-4000-8000-000000000000")
    }
}
