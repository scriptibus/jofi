// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.persistence

import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows
import io.github.scriptibus.jofi.applications.application.port.spi.LinkedTasksPort
import io.github.scriptibus.jofi.companies.application.port.spi.TaskLinksPort
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.tasks.domain.ApplicationRef
import io.github.scriptibus.jofi.tasks.domain.CompanyRef
import io.github.scriptibus.jofi.tasks.domain.ContactRef
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDetails
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskLink
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskStateChange
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import io.github.scriptibus.jofi.tasks.domain.TaskTransition
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The SPI adapters the deletes of other contexts read before they delete (#168) on a real PostgreSQL migrated from
 * zero: `TaskLinksRepository` (companies' `TaskLinksPort`) and `LinkedTasksRepository.linkedTo` (applications'
 * `LinkedTasksPort`) answer every task linked to the targets, in every state, as `task` changelog references in id
 * order (PostgreSQL's `uuid` order, that of the lower-case text), and nothing else.
 */
class TaskLinksRepositoryTest {
    private lateinit var tasks: TaskRepository
    private lateinit var links: TaskLinksRepository
    private lateinit var applicationLinks: LinkedTasksRepository
    private lateinit var rows: ApplicationRows
    private lateinit var company: UUID
    private lateinit var contact: UUID
    private val application = UUID.randomUUID()

    @BeforeEach
    fun migrateFromZero() {
        val dsl: DSLContext = PostgresTestDatabase.migratedFromZero()
        tasks = TaskRepository(dsl)
        links = TaskLinksRepository(dsl)
        applicationLinks = LinkedTasksRepository(dsl)
        rows = ApplicationRows(dsl)
        company = rows.company()
        contact = rows.contact(company)
        rows.application(application, company)
    }

    @Test
    fun `answers the tasks linked to the companies and the contacts, in every state, by target and id`() {
        val otherContact = rows.contact(company)
        val toCompany = listOf(open(CompanyRef(company)), suggested(CompanyRef(company)))
        val toContact = listOf(done(ContactRef(contact)), open(ContactRef(contact)), open(ContactRef(otherContact)))
        open(ApplicationRef(application))
        open(null)
        open(CompanyRef(rows.company()))

        val found = links.linkedTo(setOf(company), setOf(contact, otherContact))

        found shouldBe
            TaskLinksPort.Links.Found(
                linkedTo(company, toCompany),
                (linkedTo(contact, toContact.take(2)) + linkedTo(otherContact, toContact.drop(2)))
                    .sortedBy { it.target.toString() },
            )
        found.shouldBeInstanceOf<TaskLinksPort.Links.Found>().count shouldBe 5
    }

    @Test
    fun `answers no tasks for targets without links or without targets`() {
        open(CompanyRef(company))

        links.linkedTo(emptySet(), emptySet()) shouldBe TaskLinksPort.Links.Found(emptyList(), emptyList())
        links.linkedTo(setOf(UUID.randomUUID()), setOf(contact)) shouldBe
            TaskLinksPort.Links.Found(emptyList(), emptyList())
    }

    @Test
    fun `answers every task linked to an application, in id order`() {
        val linked = listOf(open(ApplicationRef(application)), suggested(ApplicationRef(application)))
        open(CompanyRef(company))

        applicationLinks.linkedTo(application) shouldBe
            LinkedTasksPort.Linked.Found(linked.sortedBy { it.value.toString() }.map { it.toEntityRef() })
        applicationLinks.linkedTo(UUID.randomUUID()) shouldBe LinkedTasksPort.Linked.Found(emptyList())
    }

    private fun linkedTo(
        target: UUID,
        ids: List<TaskId>,
    ): List<TaskLinksPort.LinkedTask> =
        ids.sortedBy { it.value.toString() }.map { TaskLinksPort.LinkedTask(target, it.toEntityRef()) }

    private fun open(link: TaskLink?): TaskId = store(Task.create(newId(), details(link), TaskOrigin.Manual, CREATED))

    private fun done(link: TaskLink?): TaskId {
        val task = Task.create(newId(), details(link), TaskOrigin.Manual, CREATED)
        val completed = task.apply(TaskTransition.COMPLETE, CREATED).shouldBeInstanceOf<TaskStateChange.Changed>()
        return store(completed.task)
    }

    private fun suggested(link: TaskLink?): TaskId {
        val origin = TaskOrigin.Suggested("follow-up", "test:${UUID.randomUUID()}")
        return store(Task.suggest(newId(), details(link), origin, CREATED))
    }

    private fun store(task: Task): TaskId {
        tasks.add(task)
        return task.id
    }

    private fun details(link: TaskLink?) = TaskDetails("Call back", TaskTiming.Bucket.SOMEDAY, link)

    private fun newId(): TaskId = TaskId(UUID.randomUUID())

    private companion object {
        val CREATED: Instant = Instant.parse("2026-09-30T08:00:00.123456Z")
    }
}
