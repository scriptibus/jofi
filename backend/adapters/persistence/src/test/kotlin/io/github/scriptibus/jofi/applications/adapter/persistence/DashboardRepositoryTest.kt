// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows.Companion.NOW
import io.github.scriptibus.jofi.applications.domain.ActivityApplication
import io.github.scriptibus.jofi.applications.domain.ActivityEntry
import io.github.scriptibus.jofi.applications.domain.ActivityQuery
import io.github.scriptibus.jofi.applications.domain.ApplicationFunnel
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.PipelineOverview
import io.github.scriptibus.jofi.shared.adapter.persistence.ChangelogRepository
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_STATUS_CHANGE
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.INTERVIEW
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangeSummary
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The dashboard's queries (#113, ADR-0052) against the real schema: counts, the funnel's edge cases, the activity. */
class DashboardRepositoryTest {
    private val dsl = PostgresTestDatabase.migratedFromZero()
    private val rows = ApplicationRows(dsl)
    private val repository = DashboardRepository(dsl)
    private val changelog = ChangelogRepository(dsl)
    private val company = rows.company()

    @Test
    fun `an empty database has zero everywhere and no rates`() {
        pipeline() shouldBe PipelineOverview.of(emptyMap(), 0, ApplicationFunnel.NONE)
        activity() shouldBe emptyList()
    }

    @Test
    fun `counts the applications per current status and the unread ones`() {
        application("DISCOVERED", "DISCOVERED", unread = true)
        application("DISCOVERED", "DISCOVERED", unread = true)
        application("APPLIED", "DISCOVERED", "APPLIED")
        application("GHOSTED", "APPLIED", "GHOSTED", unread = true)

        val overview = pipeline()

        overview.byStatus.filterValues { it > 0 } shouldBe
            mapOf(ApplicationStatus.DISCOVERED to 2L, ApplicationStatus.APPLIED to 1L, ApplicationStatus.GHOSTED to 1L)
        overview.unread shouldBe 3
    }

    @Test
    fun `the funnel counts what the history reached, each application once, whatever its status now`() {
        application("DISCOVERED", "DISCOVERED")
        application("DECLINED", "DISCOVERED", "DECLINED")
        application("APPLIED", "DISCOVERED", "APPLIED")
        application("WITHDRAWN", "APPLIED", "WITHDRAWN")
        application("GHOSTED", "APPLIED", "GHOSTED")
        // A late answer after Ghosted is a response; a rejection reopened is still one.
        application("REJECTED", "APPLIED", "GHOSTED", "REJECTED")
        application("DISCOVERED", "APPLIED", "REJECTED", "DISCOVERED")
        // Moving back and forth counts once; a skip straight to an offer reaches every stage before it.
        application("INTERVIEWING", "APPLIED", "INTERVIEWING", "APPLIED", "INTERVIEWING")
        application("OFFER", "DISCOVERED", "OFFER")
        application("DECLINED", "APPLIED", "INTERVIEWING", "OFFER", "DECLINED")

        pipeline().funnel shouldBe ApplicationFunnel(applied = 8, interviewed = 3, offered = 2, responded = 5)
    }

    @Test
    fun `the activity is the newest entries of the job search, ties by number, without values or reasons`() {
        val task = UUID.randomUUID().toString()
        record(EntityRef("task", task), "Created task", 1, FieldChange("title", null, "Call Erika Mustermann"))
        record(EntityRef("saved_view", UUID.randomUUID().toString()), "Created saved view", 2)
        record(EntityRef("system", "sessions"), "Deleted 2 expired login session(s)", 2)
        record(EntityRef("company", UUID.randomUUID().toString()), "Created company", 3)
        record(EntityRef("contact", UUID.randomUUID().toString()), "Created contact", 3, reason = "Met at a fair")

        val entries = activity()

        entries.map { it.description } shouldContainExactly listOf("Created contact", "Created company", "Created task")
        entries.last().fields shouldBe listOf("title")
        entries.last().entity shouldBe EntityRef("task", task)
        entries.last().actor shouldBe Actor.User
        entries.first().occurredAt shouldBe AT.plusSeconds(3)
        entries.toString().let {
            it shouldNotContain "Erika"
            it shouldNotContain "fair"
        }
        activity(ActivityQuery(2)).map { it.description } shouldContainExactly
            listOf("Created contact", "Created company")
    }

    @Test
    fun `entries about an application or its parts name the application while it exists`() {
        val id = application("APPLIED", "APPLIED")
        val interview = interview(id)
        val source = source(id)
        val snapshot = snapshot(source)
        val gone = UUID.randomUUID()
        record(EntityRef("application", id.toString()), "Created application", 1)
        record(EntityRef("interview", interview.toString()), "Logged interview", 2)
        record(EntityRef("application_source", source.toString()), "Added source", 3)
        record(EntityRef("description_snapshot", snapshot.toString()), "Froze job description", 4)
        record(EntityRef("application", gone.toString()), "Deleted application", 5)
        record(EntityRef("application", "not-a-uuid"), "Created application", 6)
        record(EntityRef("company", company.toString()), "Created company", 7)

        val labelled = ActivityApplication(ApplicationId(id), "Backend Engineer")
        activity().map { it.description to it.application } shouldContainExactly
            listOf(
                "Created company" to null,
                "Created application" to null,
                "Deleted application" to null,
                "Froze job description" to labelled,
                "Added source" to labelled,
                "Logged interview" to labelled,
                "Created application" to labelled,
            )
    }

    @Test
    fun `a database it cannot reach is a storage failure, not an exception`() {
        val unreachable = DashboardRepository(DSL.using(SQLDialect.POSTGRES))

        unreachable.pipeline() shouldBe ApplicationStoreResult.StorageFailure("pipeline")
        unreachable.recentActivity(ActivityQuery()) shouldBe ApplicationStoreResult.StorageFailure("recentActivity")
    }

    private fun pipeline(): PipelineOverview = (repository.pipeline() as ApplicationStoreResult.Success).value

    private fun activity(query: ActivityQuery = ActivityQuery()): List<ActivityEntry> =
        (repository.recentActivity(query) as ApplicationStoreResult.Success).value

    /** An application now in [status] whose history holds [history], oldest first. */
    private fun application(
        status: String,
        vararg history: String,
        unread: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        rows.application(id, company) {
            this.status = status
            this.unread = unread
            declineCategory = OTHER.takeIf { status in ENDED_WITH_REASON }
        }
        history.forEachIndexed { index, to ->
            dsl
                .newRecord(APPLICATION_STATUS_CHANGE)
                .apply {
                    applicationId = id
                    fromStatus = history.getOrNull(index - 1)
                    toStatus = to
                    declineCategory = OTHER.takeIf { to in ENDED_WITH_REASON }
                    actorKind = "USER"
                    changedAt = NOW.plus(Duration.ofMinutes(index.toLong()))
                }.insert()
        }
        return id
    }

    private fun interview(application: UUID): UUID {
        val id = UUID.randomUUID()
        dsl
            .newRecord(INTERVIEW)
            .apply {
                this.id = id
                applicationId = application
                kind = "HR"
                startsAt = NOW
                timeZone = "Europe/Berlin"
                createdAt = NOW
                updatedAt = NOW
            }.insert()
        return id
    }

    private fun source(application: UUID): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            "insert into application_source (id, application_id, kind, discovered_at) " +
                "values (?, ?, 'MANUAL_CHAT', now())",
            id,
            application,
        )
        return id
    }

    private fun snapshot(source: UUID): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            "insert into application_description_snapshot (id, source_id, description, content_hash, reason, " +
                "captured_at) values (?, ?, 'Kotlin', encode(sha256(convert_to('Kotlin', 'UTF8')), 'hex'), " +
                "'MANUAL', now())",
            id,
            source,
        )
        return id
    }

    private fun record(
        entity: EntityRef,
        description: String,
        second: Long,
        vararg fields: FieldChange,
        reason: String? = null,
    ) {
        val entry =
            ChangelogEntry(
                entity,
                Actor.User,
                AT.plusSeconds(second),
                ChangeSummary(description, fields.toList()),
                reason,
            )
        changelog.append(entry)
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-09-30T08:00:00Z")
        val ENDED_WITH_REASON = setOf("DECLINED", "REJECTED")
        const val OTHER = "OTHER"
    }
}
