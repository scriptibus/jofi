// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.persistence

import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows
import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows.Companion.NOW
import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows.Companion.rejects
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.TASK
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.TaskRecord
import io.github.scriptibus.jofi.tasks.domain.ApplicationRef
import io.github.scriptibus.jofi.tasks.domain.BucketSpan
import io.github.scriptibus.jofi.tasks.domain.CompanyRef
import io.github.scriptibus.jofi.tasks.domain.ContactRef
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDetails
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskInput
import io.github.scriptibus.jofi.tasks.domain.TaskLink
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import io.github.scriptibus.jofi.tasks.domain.TaskTimingInput
import io.github.scriptibus.jofi.tasks.domain.TaskValidation
import io.github.scriptibus.jofi.tasks.domain.TimeBucket
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * `task` on a real PostgreSQL (ADR-0041, ADR-0049): its constraints mirror the domain without being stricter, each
 * has a name, every timing, state and origin the domain allows is storable, a rule suggests once per key, and deleting
 * what a task links to clears the link and keeps the task.
 */
class TaskSchemaTest {
    private lateinit var dsl: DSLContext
    private lateinit var rows: ApplicationRows
    private lateinit var company: UUID
    private val application = UUID.randomUUID()
    private val at = NOW.toInstant()

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        rows = ApplicationRows(dsl)
        company = rows.company()
        rows.application(application, company)
    }

    @Test
    fun `every constraint has a name of its own`() {
        rows.constraintsOf("task") shouldBe TASK_CONSTRAINTS
    }

    @Test
    fun `stores every state and origin the domain allows`() {
        val suggestion = TaskOrigin.Suggested("follow-up", "application:$application")
        val open = Task.create(newId(), details(), TaskOrigin.Chat, at)
        val suggested = Task.suggest(newId(), details(), suggestion, at)

        listOf(
            open,
            moved(open.copy(id = newId(), origin = TaskOrigin.Manual), TaskState.DONE),
            suggested,
            moved(suggested.copy(id = newId(), origin = suggestion.copy(key = "a")), TaskState.OPEN),
            moved(suggested.copy(id = newId(), origin = suggestion.copy(key = "b")), TaskState.DISMISSED),
        ).forEach(::store)

        dsl.fetchValues(TASK.STATE).toSet() shouldBe TaskState.entries.map { it.name }.toSet()
        dsl.fetchValues(TASK.ORIGIN).toSet() shouldBe setOf("MANUAL", "CHAT", "SUGGESTED")
    }

    @Test
    fun `stores every kind of link`() {
        listOf(ApplicationRef(application), CompanyRef(company), ContactRef(rows.contact(company)))
            .forEach { store(task(details(link = it))) }

        dsl.fetchCount(TASK, TASK.APPLICATION_ID.isNotNull) shouldBe 1
        dsl.fetchCount(TASK, TASK.COMPANY_ID.isNotNull) shouldBe 1
        dsl.fetchCount(TASK, TASK.CONTACT_ID.isNotNull) shouldBe 1
    }

    @Test
    fun `stores every bucket and every time zone Java knows`() {
        val buckets = TimeBucket.entries.mapNotNull { it.on(LocalDate.parse("2026-09-30")) }
        val zones = ZoneId.getAvailableZoneIds() + listOf("Z", "+14:00", "-12:00", "UTC+05:30", "GMT-03:00")

        buckets.forEach { store(task(details(timing = it))) }
        zones.forEach { store(task(details(timing = TaskTiming.Exact(at, ZoneId.of(it))))) }

        dsl.fetchValues(TASK.BUCKET_SPAN).filterNotNull().toSet() shouldBe BucketSpan.entries.map { it.name }.toSet()
        dsl.fetchValues(TASK.TIME_ZONE).filterNotNull().toSet() shouldBe zones.map { ZoneId.of(it).id }.toSet()
    }

    @Test
    fun `stores whatever the domain accepts, at exactly its limits`() {
        // Exactly the limits in UTF-16 units, which the domain counts (the rocket is two).
        val title = "ü".repeat(TaskDetails.MAX_TITLE_LENGTH - 3) + " 🚀"
        val sample = "# İstanbul\n\n Straße, \"Kotlin\"; 𝔘𝔫𝔦𝔠𝔬𝔡𝔢 ☕ "
        val notes = sample + "x".repeat(TaskDetails.MAX_NOTES_LENGTH - sample.length)
        val suggestion = TaskOrigin.Suggested("r".repeat(TaskOrigin.Suggested.MAX_RULE_LENGTH), "ü".repeat(200))
        val inputs =
            listOf(
                TaskInput(
                    title,
                    TaskTimingInput("UTC", localDue = TaskTiming.EARLIEST.atOffset(ZoneOffset.UTC).toLocalDateTime()),
                ),
                TaskInput(title, TaskTimingInput("UTC", localDue = latestDue()), notes = notes),
            )

        inputs.forEach { input ->
            store(task(input.validate(at).shouldBeInstanceOf<TaskValidation.Valid<TaskDetails>>().value))
        }
        store(Task.suggest(TaskId(UUID.randomUUID()), details(), suggestion, at))
        storeBucket(BucketSpan.DAY, TaskTiming.EARLIEST_DAY)
        storeBucket(BucketSpan.DAY, TaskTiming.LATEST_DAY.minusDays(1))
        storeBucket(BucketSpan.MONTH, LocalDate.parse("2099-12-01"))

        dsl.fetchCount(TASK) shouldBe 6
    }

    @Test
    fun `rejects tasks the domain rejects`() {
        rejects("task_title_valid") { insert { title = " Call" } }
        rejects("task_title_valid") { insert { title = "x".repeat(TaskDetails.MAX_TITLE_LENGTH + 1) } }
        rejects("task_notes_valid") { insert { notes = " \n" } }
        rejects("task_notes_valid") { insert { notes = "x".repeat(TaskDetails.MAX_NOTES_LENGTH + 1) } }
        rejects("task_time_zone_valid") { insert { exact("Europe/ Berlin") } }
        rejects("task_time_zone_valid") { insert { exact("x".repeat(TaskTiming.MAX_ZONE_ID_LENGTH + 1)) } }
        rejects("task_bucket_span_valid") { insert { bucketSpan = "YEAR" } }
        rejects("task_origin_valid") { insert { origin = "manual" } }
        rejects("task_state_valid") {
            insert {
                suggest()
                state = "open"
            }
        }
        rejects("task_version_valid") { insert { version = -1L } }
        rejects("task_updated_after_created") { insert { updatedAt = NOW.minusSeconds(1) } }
        rejects("task_application_fk") { insert { applicationId = UUID.randomUUID() } }
        rejects("task_company_fk") { insert { companyId = UUID.randomUUID() } }
        rejects("task_contact_fk") { insert { contactId = UUID.randomUUID() } }
        dsl.fetchCount(TASK) shouldBe 0
    }

    @Test
    fun `a task has exactly one timing, and buckets start where they must`() {
        rejects("task_timing_valid") { insert { bucketSpan = null } }
        rejects("task_timing_valid") { insert { exact("UTC") } }
        rejects("task_timing_valid") { insert { bucket(null, null).also { dueAt = NOW } } }
        rejects("task_timing_valid") { insert { bucket("DAY", null) } }
        rejects("task_timing_valid") { insert { bucket("SOMEDAY", "2026-10-05") } }
        rejects("task_bucket_start_valid") { insert { bucket("WEEK", "2026-10-06") } }
        rejects("task_bucket_start_valid") { insert { bucket("MONTH", "2026-10-05") } }
        dsl.fetchCount(TASK) shouldBe 0
    }

    @Test
    fun `a task links to one thing at most`() {
        insert { applicationId = application }
        rejects("task_single_link") { insert { link(application, company) } }
        dsl.fetchCount(TASK) shouldBe 1
    }

    @Test
    fun `origins, states and suggestions stay consistent`() {
        rejects("task_suggestion_matches_origin") { insert { origin = "SUGGESTED" } }
        rejects("task_suggestion_matches_origin") { insert { suggestion("follow-up", "a") } }
        rejects("task_suggestion_matches_origin") { insert { suggest().also { suggestionKey = null } } }
        rejects("task_suggestion_rule_valid") { insert { suggest().also { suggestionRule = "Follow_up" } } }
        rejects("task_suggestion_key_valid") { insert { suggest().also { suggestionKey = "a b" } } }
        rejects("task_state_matches_origin") { insert { state = "DISMISSED" } }
        rejects("task_completed_matches_state") { insert { state = "DONE" } }
        rejects("task_completed_matches_state") { insert { completedAt = NOW } }
        dsl.fetchCount(TASK) shouldBe 0
    }

    @Test
    fun `a rule suggests once per key, and direct tasks never collide`() {
        insert { suggest() }
        insert {
            suggest()
            suggestionKey = "application:other"
        }
        insert()
        insert()

        rejects("task_suggestion_unique") { insert { suggest() } }
        dsl.fetchCount(TASK) shouldBe 4
    }

    @Test
    fun `PostgreSQL cannot store U+0000, which is why the domain rejects it`() {
        shouldThrow<DataAccessException> { insert { notes = "Call\u0000back" } }
        TaskInput("Call\u0000back", TaskTimingInput("UTC", TimeBucket.TODAY))
            .validate(at)
            .shouldBeInstanceOf<TaskValidation.Invalid>()
    }

    @Test
    fun `deleting what a task links to clears the link and keeps the task`() {
        val contact = rows.contact(company)
        val otherCompany = rows.company()
        val colleague = rows.contact(otherCompany)
        val tasks =
            listOf(
                insert { applicationId = application },
                insert { contactId = contact },
                insert { companyId = otherCompany },
                insert { contactId = colleague },
            )

        dsl.deleteFrom(COMPANY).where(COMPANY.ID.eq(otherCompany)).execute()
        dsl.deleteFrom(CONTACT).where(CONTACT.ID.eq(contact)).execute()
        dsl.deleteFrom(APPLICATION).where(APPLICATION.ID.eq(application)).execute()

        dsl.fetchValues(TASK.ID).toSet() shouldBe tasks.toSet()
        dsl.fetchCount(
            TASK,
            TASK.APPLICATION_ID.isNotNull
                .or(TASK.COMPANY_ID.isNotNull)
                .or(TASK.CONTACT_ID.isNotNull),
        ) shouldBe
            0
    }

    private fun details(
        timing: TaskTiming = TaskTiming.Bucket.SOMEDAY,
        link: TaskLink? = null,
    ): TaskDetails = TaskDetails("Call back", timing, link)

    private fun task(details: TaskDetails): Task =
        Task.create(TaskId(UUID.randomUUID()), details, TaskOrigin.Manual, at)

    private fun moved(
        task: Task,
        state: TaskState,
    ): Task = task.copy(state = state, completedAt = at.takeIf { state == TaskState.DONE }, version = 1)

    private fun latestDue() =
        TaskTiming.LATEST
            .minus(1, ChronoUnit.MICROS)
            .atOffset(ZoneOffset.UTC)
            .toLocalDateTime()

    private fun storeBucket(
        span: BucketSpan,
        start: LocalDate,
    ) = store(task(details(timing = TaskTiming.Bucket(span, start))))

    // What the repository (#93) will write for a domain task.
    private fun store(task: Task) {
        val details = task.details
        val timing = details.timing
        val origin = task.origin
        insert {
            id = task.id.value
            title = details.title
            notes = details.notes
            dueAt = (timing as? TaskTiming.Exact)?.dueAt?.atOffset(ZoneOffset.UTC)
            timeZone = (timing as? TaskTiming.Exact)?.zone?.id
            bucketSpan = (timing as? TaskTiming.Bucket)?.span?.name
            bucketStartsOn = (timing as? TaskTiming.Bucket)?.startsOn
            applicationId = (details.link as? ApplicationRef)?.value
            companyId = (details.link as? CompanyRef)?.value
            contactId = (details.link as? ContactRef)?.value
            this.origin = originName(origin)
            suggestionRule = (origin as? TaskOrigin.Suggested)?.rule
            suggestionKey = (origin as? TaskOrigin.Suggested)?.key
            state = task.state.name
            completedAt = task.completedAt?.atOffset(ZoneOffset.UTC)
            version = task.version
            updatedAt = task.updatedAt.atOffset(ZoneOffset.UTC)
        }
    }

    private fun originName(origin: TaskOrigin): String =
        when (origin) {
            TaskOrigin.Manual -> "MANUAL"
            TaskOrigin.Chat -> "CHAT"
            is TaskOrigin.Suggested -> "SUGGESTED"
        }

    private fun newId(): TaskId = TaskId(UUID.randomUUID())

    private fun TaskRecord.bucket(
        span: String?,
        start: String?,
    ) {
        bucketSpan = span
        bucketStartsOn = start?.let(LocalDate::parse)
    }

    private fun TaskRecord.link(
        application: UUID,
        company: UUID,
    ) {
        applicationId = application
        companyId = company
    }

    private fun TaskRecord.suggestion(
        rule: String,
        key: String,
    ) {
        suggestionRule = rule
        suggestionKey = key
    }

    private fun TaskRecord.exact(zone: String) {
        dueAt = NOW
        timeZone = zone
    }

    private fun TaskRecord.suggest() {
        origin = "SUGGESTED"
        state = "SUGGESTED"
        suggestionRule = "follow-up"
        suggestionKey = "application:$application"
    }

    private fun insert(customize: TaskRecord.() -> Unit = {}): UUID {
        val record =
            dsl.newRecord(TASK).apply {
                id = UUID.randomUUID()
                title = "Call back"
                bucketSpan = "SOMEDAY"
                origin = "MANUAL"
                state = "OPEN"
                createdAt = NOW
                updatedAt = NOW
                customize()
            }
        record.insert()
        return record.id
    }

    private companion object {
        val TASK_CONSTRAINTS =
            listOf(
                "task_application_fk",
                "task_bucket_span_valid",
                "task_bucket_start_valid",
                "task_company_fk",
                "task_completed_matches_state",
                "task_contact_fk",
                "task_notes_valid",
                "task_origin_valid",
                "task_pk",
                "task_single_link",
                "task_state_matches_origin",
                "task_state_valid",
                "task_suggestion_key_valid",
                "task_suggestion_matches_origin",
                "task_suggestion_rule_valid",
                "task_suggestion_unique",
                "task_time_zone_valid",
                "task_timing_valid",
                "task_title_valid",
                "task_updated_after_created",
                "task_version_valid",
            )
    }
}
