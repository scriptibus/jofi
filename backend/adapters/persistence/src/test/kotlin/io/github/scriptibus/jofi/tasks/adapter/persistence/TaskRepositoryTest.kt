// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.persistence

import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows
import io.github.scriptibus.jofi.setup.adapter.persistence.ConfirmedProofs
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT
import io.github.scriptibus.jofi.shared.domain.paging.PageRequest
import io.github.scriptibus.jofi.shared.domain.paging.Paged
import io.github.scriptibus.jofi.tasks.domain.ApplicationRef
import io.github.scriptibus.jofi.tasks.domain.BucketSpan
import io.github.scriptibus.jofi.tasks.domain.CompanyRef
import io.github.scriptibus.jofi.tasks.domain.ContactRef
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDetails
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskLink
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskStateChange
import io.github.scriptibus.jofi.tasks.domain.TaskStoreResult
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import io.github.scriptibus.jofi.tasks.domain.TaskTransition
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * `TaskRepository` on a real PostgreSQL migrated from zero: round trips of every timing, link, origin and state,
 * versioned updates, foreign keys and the suggestion key mapped by constraint name, the confirmed delete, and the link
 * cleared (`ON DELETE SET NULL`) when what it points to is deleted.
 */
class TaskRepositoryTest {
    private lateinit var dsl: DSLContext
    private lateinit var repository: TaskRepository
    private lateinit var rows: ApplicationRows
    private lateinit var company: UUID
    private lateinit var contact: UUID
    private val application = UUID.randomUUID()

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        repository = TaskRepository(dsl)
        rows = ApplicationRows(dsl)
        company = rows.company()
        contact = rows.contact(company)
        rows.application(application, company)
    }

    @Test
    fun `stores and reads every timing, link, origin and state`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val due = Instant.parse("2026-10-05T08:00:00.123456Z")
        val suggestion = TaskOrigin.Suggested("follow-up", "application:$application")
        val tasks =
            listOf(
                open(TaskDetails("Call back", TaskTiming.Exact(due, berlin))),
                open(details(bucket(BucketSpan.DAY, "2026-09-30"), ApplicationRef(application))),
                open(details(bucket(BucketSpan.WEEK, "2026-09-28"), CompanyRef(company))),
                open(details(bucket(BucketSpan.MONTH, "2026-10-01"), ContactRef(contact))),
                open(TaskDetails("Read *this*", TaskTiming.Bucket.SOMEDAY, notes = "Line one\r\nLine \"two\"")),
                Task.create(newId(), details(), TaskOrigin.Chat, CREATED),
                done(open(details())),
                Task.suggest(newId(), details(), suggestion, CREATED),
            )

        tasks.forEach { repository.add(it) shouldBe TaskStoreResult.Success(Unit) }

        tasks.forEach { repository.findById(it.id) shouldBe TaskStoreResult.Success(it) }
    }

    @Test
    fun `an unknown task is not found`() {
        repository.findById(newId()) shouldBe TaskStoreResult.NotFound
        repository.update(open(details()).copy(version = 1)) shouldBe TaskStoreResult.NotFound
    }

    @Test
    fun `updates store everything, but only on top of the version they were based on`() {
        val task = open(details())
        repository.add(task)
        val edited = task.edit(details(TaskTiming.Bucket.SOMEDAY, ContactRef(contact)), LATER)
        val completed = done(edited)

        repository.update(edited) shouldBe TaskStoreResult.Success(Unit)
        repository.update(completed) shouldBe TaskStoreResult.Success(Unit)

        repository.findById(task.id) shouldBe TaskStoreResult.Success(completed)
        repository.update(edited.edit(details(), LATER)) shouldBe TaskStoreResult.VersionConflict
        repository.findById(task.id) shouldBe TaskStoreResult.Success(completed)
    }

    @Test
    fun `a link to something that does not exist is recognised by its foreign key`() {
        listOf(ApplicationRef(UUID.randomUUID()), CompanyRef(UUID.randomUUID()), ContactRef(UUID.randomUUID()))
            .forEach { missing ->
                repository.add(open(details(link = missing))) shouldBe TaskStoreResult.LinkNotFound
            }
        val task = open(details())
        repository.add(task)
        repository.update(task.edit(details(link = ContactRef(UUID.randomUUID())), LATER)) shouldBe
            TaskStoreResult.LinkNotFound
    }

    @Test
    fun `a rule suggests once per key`() {
        val origin = TaskOrigin.Suggested("follow-up", "application:$application")
        repository.add(Task.suggest(newId(), details(), origin, CREATED))

        repository.add(Task.suggest(newId(), details(), origin, CREATED)) shouldBe TaskStoreResult.SuggestionExists
    }

    @Test
    fun `lists by state and by link, oldest first`() {
        val older = open(details(link = ApplicationRef(application)))
        val newer = open(details(link = ApplicationRef(application))).copy(createdAt = LATER, updatedAt = LATER)
        val completed = done(open(details(link = CompanyRef(company))))
        listOf(newer, completed, older).forEach { repository.add(it) }

        repository.listByState(TaskState.OPEN) shouldBe TaskStoreResult.Success(listOf(older, newer))
        repository.listByState(TaskState.DONE) shouldBe TaskStoreResult.Success(listOf(completed))
        repository.listByState(TaskState.SUGGESTED) shouldBe TaskStoreResult.Success(emptyList())
        repository.listByLink(ApplicationRef(application)) shouldBe TaskStoreResult.Success(listOf(older, newer))
        repository.listByLink(CompanyRef(company)) shouldBe TaskStoreResult.Success(listOf(completed))
        repository.listByLink(ContactRef(contact)) shouldBe TaskStoreResult.Success(emptyList())
    }

    @Test
    fun `a page of suggestions is newest first with the total, and paging reaches every one exactly once`() {
        val suggested =
            (1..7).map { index ->
                val origin = TaskOrigin.Suggested("follow-up", "application:$index")
                Task.suggest(newId(), details(), origin, CREATED.plusSeconds(index.toLong()))
            }
        suggested.forEach { repository.add(it) }
        repository.add(open(details()))

        val pages = (0..2).map { page(it, 3) }

        pages.map { it.info.total } shouldBe listOf(7, 7, 7)
        pages.map { it.info.hasMore } shouldBe listOf(true, true, false)
        pages.flatMap { it.items }.map { it.id } shouldBe suggested.reversed().map { it.id }
        page(3, 3).items shouldBe emptyList()
    }

    @Test
    fun `tasks created in the same instant keep one order over every page, by id`() {
        val same =
            (1..5).map { index ->
                val origin = TaskOrigin.Suggested("follow-up", "application:$index")
                Task.suggest(newId(), details(), origin, CREATED)
            }
        same.forEach { repository.add(it) }

        val ids = (0..2).flatMap { page(it, 2).items }.map { it.id.value.toString() }

        // PostgreSQL orders uuids bytewise, which is the order of their lower-case text (not of `UUID.compareTo`).
        ids shouldBe same.map { it.id.value.toString() }.sortedDescending()
    }

    @Test
    fun `a task added between two page reads can shift the next page, which the changed total shows`() {
        val first =
            (1..4).map {
                Task.suggest(newId(), details(), TaskOrigin.Suggested("r", "k$it"), CREATED.plusSeconds(it.toLong()))
            }
        first.forEach { repository.add(it) }
        val before = page(0, 2)

        val newest = Task.suggest(newId(), details(), TaskOrigin.Suggested("r", "new"), LATER)
        repository.add(newest)
        val after = page(1, 2)

        before.info.total shouldBe 4
        after.info.total shouldBe 5
        // The new one pushed the last item of page 0 onto page 1: a repeat, never a gap.
        after.items.first().id shouldBe before.items.last().id
    }

    private fun page(
        page: Int,
        size: Int,
    ): Paged<Task> =
        repository
            .pageByStateNewestFirst(TaskState.SUGGESTED, PageRequest(page, size))
            .shouldBeInstanceOf<TaskStoreResult.Success<Paged<Task>>>()
            .value

    @Test
    fun `deleting needs the proof for exactly this task`() {
        val task = open(details())
        val other = open(details())
        listOf(task, other).forEach { repository.add(it) }

        repository.delete(task.id, proofFor(other.id)) shouldBe TaskStoreResult.NotConfirmed
        repository.delete(task.id, ConfirmedProofs.of("countdowns.delete", task.id.value.toString())) shouldBe
            TaskStoreResult.NotConfirmed
        repository.delete(task.id, proofFor(task.id)) shouldBe TaskStoreResult.Success(Unit)

        repository.findById(task.id) shouldBe TaskStoreResult.NotFound
        repository.findById(other.id).shouldBeInstanceOf<TaskStoreResult.Success<Task>>()
        repository.delete(task.id, proofFor(task.id)) shouldBe TaskStoreResult.NotFound
    }

    @Test
    fun `deleting a linked application, contact or company clears the link and keeps the task and its version`() {
        val otherCompany = rows.company()
        val linked =
            listOf(ApplicationRef(application), ContactRef(contact), CompanyRef(otherCompany))
                .map { open(details(link = it)) }
        linked.forEach { repository.add(it) }

        dsl.deleteFrom(APPLICATION).where(APPLICATION.ID.eq(application)).execute()
        dsl.deleteFrom(CONTACT).where(CONTACT.ID.eq(contact)).execute()
        dsl.deleteFrom(COMPANY).where(COMPANY.ID.eq(otherCompany)).execute()

        linked.forEach { task ->
            val kept = repository.findById(task.id).shouldBeInstanceOf<TaskStoreResult.Success<Task>>().value
            kept shouldBe task.copy(details = task.details.copy(link = null))
        }
        repository.listByState(TaskState.OPEN).shouldBeInstanceOf<TaskStoreResult.Success<List<Task>>>().value.map {
            it.id
        } shouldContainExactlyInAnyOrder linked.map { it.id }
    }

    private fun details(
        timing: TaskTiming = TaskTiming.Bucket.SOMEDAY,
        link: TaskLink? = null,
    ): TaskDetails = TaskDetails("Call back", timing, link)

    private fun bucket(
        span: BucketSpan,
        start: String,
    ) = TaskTiming.Bucket(span, LocalDate.parse(start))

    private fun open(details: TaskDetails): Task = Task.create(newId(), details, TaskOrigin.Manual, CREATED)

    private fun done(task: Task): Task =
        task
            .apply(TaskTransition.COMPLETE, LATER)
            .shouldBeInstanceOf<TaskStateChange.Changed>()
            .task

    private fun newId(): TaskId = TaskId(UUID.randomUUID())

    private fun proofFor(id: TaskId) = ConfirmedProofs.of(Task.DELETE_OPERATION, id.value.toString())

    private companion object {
        val CREATED: Instant = Instant.parse("2026-09-30T08:00:00.123456Z")
        val LATER: Instant = Instant.parse("2026-09-30T09:30:00Z")
    }
}
