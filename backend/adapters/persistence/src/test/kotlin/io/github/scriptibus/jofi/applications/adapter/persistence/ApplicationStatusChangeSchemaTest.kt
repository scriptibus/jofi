// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows.Companion.NOW
import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows.Companion.rejects
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.DeclineCategory
import io.github.scriptibus.jofi.applications.domain.StatusChange
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_STATUS_CHANGE
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.ApplicationStatusChangeRecord
import io.github.scriptibus.jofi.shared.domain.Actor
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `application_status_change` on a real PostgreSQL (ADR-0041, ADR-0044): its constraints mirror
 * `StatusChange` without being stricter, each has a name, and the history goes with its application.
 */
class ApplicationStatusChangeSchemaTest {
    private lateinit var dsl: DSLContext
    private lateinit var rows: ApplicationRows
    private val application = UUID.randomUUID()
    private val at = NOW.toInstant()

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        rows = ApplicationRows(dsl)
        rows.application(application, rows.company())
    }

    @Test
    fun `every constraint has a name of its own`() {
        rows.constraintsOf("application_status_change") shouldBe CONSTRAINTS
    }

    @Test
    fun `stores every status and decline category the domain has`() {
        ApplicationStatus.entries.forEach { status ->
            insert {
                fromStatus = status.name
                toStatus = status.name
                declineCategory = if (status.takesDeclineReason) DeclineCategory.OTHER.name else null
            }
        }
        DeclineCategory.entries.forEach { category ->
            insert {
                toStatus = ApplicationStatus.REJECTED.name
                declineCategory = category.name
            }
        }

        dsl.fetchCount(APPLICATION_STATUS_CHANGE) shouldBe ApplicationStatus.entries.size + DeclineCategory.entries.size
    }

    @Test
    fun `stores every kind of actor`() {
        listOf(Actor.User, Actor.Ai, Actor.Scanner("Feed"), Actor.ExternalClient("Claude"), Actor.System("rule"))
            .forEach { actor ->
                store(StatusChange(ApplicationId(application), null, ApplicationStatus.APPLIED, null, null, actor, at))
            }

        dsl.fetchValues(APPLICATION_STATUS_CHANGE.ACTOR_KIND) shouldBe
            listOf("USER", "AI", "SCANNER", "EXTERNAL_CLIENT", "SYSTEM")
    }

    @Test
    fun `stores whatever the domain accepts, at exactly its limits`() {
        val changes =
            listOf(
                StatusChange(
                    ApplicationId(application),
                    ApplicationStatus.OFFER,
                    ApplicationStatus.DECLINED,
                    "r".repeat(StatusChange.MAX_REASON_LENGTH),
                    DeclineCategory.OTHER_OFFER,
                    Actor.ExternalClient("Claude\u00a0Desktop \"MCP\""),
                    NOW.toInstant(),
                ),
                StatusChange(
                    ApplicationId(application),
                    ApplicationStatus.GHOSTED,
                    ApplicationStatus.INTERVIEWING,
                    "Späte Antwort aus İstanbul 🚀\n\n# Notiz",
                    null,
                    Actor.Scanner("x"),
                    NOW.toInstant(),
                ),
            )
        changes.forEach(::store)

        dsl.fetchValues(APPLICATION_STATUS_CHANGE.REASON) shouldBe changes.map { it.reason }
    }

    @Test
    fun `rejects what the domain rejects`() {
        rejects("application_status_change_to_status_valid") { insert { toStatus = "applied" } }
        rejects("application_status_change_from_status_valid") { insert { fromStatus = "NEW" } }
        rejects("application_status_change_reason_valid") { insert { reason = " " } }
        rejects("application_status_change_reason_valid") { insert { reason = "Why " } }
        rejects("application_status_change_reason_valid") {
            insert { reason = "x".repeat(StatusChange.MAX_REASON_LENGTH + 1) }
        }
        rejects("application_status_change_decline_category_valid") {
            insert {
                toStatus = "DECLINED"
                declineCategory = "salary"
            }
        }
        rejects("application_status_change_decline_category_matches_status") { insert { toStatus = "DECLINED" } }
        rejects("application_status_change_decline_category_matches_status") { insert { declineCategory = "SALARY" } }
        rejects("application_status_change_actor_kind_valid") { insert { actorKind = "ROBOT" } }
        rejects("application_status_change_actor_name_matches_kind") { insert { actorName = "me" } }
        rejects("application_status_change_actor_name_matches_kind") { insert { actorKind = "SCANNER" } }
        rejects("application_status_change_actor_name_valid") {
            insert {
                actorKind = "SYSTEM"
                actorName = " \t"
            }
        }
        rejects("application_status_change_application_fk") { insert(UUID.randomUUID()) }
        dsl.fetchCount(APPLICATION_STATUS_CHANGE) shouldBe 0
    }

    @Test
    fun `the history keeps its order and goes with its application`() {
        insert { toStatus = "DISCOVERED" }
        insert { toStatus = "APPLIED" }
        insert { toStatus = "INTERVIEWING" }

        dsl
            .select(APPLICATION_STATUS_CHANGE.TO_STATUS)
            .from(APPLICATION_STATUS_CHANGE)
            .orderBy(APPLICATION_STATUS_CHANGE.ID)
            .fetch(APPLICATION_STATUS_CHANGE.TO_STATUS) shouldBe listOf("DISCOVERED", "APPLIED", "INTERVIEWING")

        dsl.deleteFrom(APPLICATION).where(APPLICATION.ID.eq(application)).execute()
        dsl.fetchCount(APPLICATION_STATUS_CHANGE) shouldBe 0
    }

    private fun insert(
        applicationId: UUID = application,
        customize: ApplicationStatusChangeRecord.() -> Unit = {},
    ) {
        dsl
            .newRecord(APPLICATION_STATUS_CHANGE)
            .apply {
                this.applicationId = applicationId
                toStatus = "APPLIED"
                actorKind = "USER"
                changedAt = NOW
                customize()
            }.insert()
    }

    // What the repository (#84) will write for a domain entry.
    private fun store(change: StatusChange) {
        val (kind, name) = actorColumns(change.actor)
        insert(change.application.value) {
            fromStatus = change.from?.name
            toStatus = change.to.name
            reason = change.reason
            declineCategory = change.declineCategory?.name
            actorKind = kind
            actorName = name
        }
    }

    private fun actorColumns(actor: Actor): Pair<String, String?> =
        when (actor) {
            Actor.User -> "USER" to null
            Actor.Ai -> "AI" to null
            is Actor.Scanner -> "SCANNER" to actor.name
            is Actor.ExternalClient -> "EXTERNAL_CLIENT" to actor.name
            is Actor.System -> "SYSTEM" to actor.name
        }

    private companion object {
        val CONSTRAINTS =
            listOf(
                "application_status_change_actor_kind_valid",
                "application_status_change_actor_name_matches_kind",
                "application_status_change_actor_name_valid",
                "application_status_change_application_fk",
                "application_status_change_decline_category_matches_status",
                "application_status_change_decline_category_valid",
                "application_status_change_from_status_valid",
                "application_status_change_pk",
                "application_status_change_reason_valid",
                "application_status_change_to_status_valid",
            )
    }
}
