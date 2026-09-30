// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.ChangelogEntryRecord
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangeSummary
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogLimit
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.time.temporal.ChronoUnit

/** jOOQ round-trips against a real PostgreSQL migrated from zero. */
class ChangelogRepositoryTest {
    private lateinit var dsl: DSLContext
    private lateinit var repository: ChangelogRepository

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        repository = ChangelogRepository(dsl)
    }

    @ParameterizedTest
    @MethodSource("everyActor")
    fun `round-trips every actor variant`(actor: Actor) {
        val entry = entry(actor = actor)

        repository.append(entry) shouldBe ChangelogResult.Success(Unit)

        repository.listByEntity(entry.entity, ChangelogLimit(10)) shouldBe ChangelogResult.Success(listOf(entry))
    }

    @Test
    fun `round-trips field changes, missing values and the reason`() {
        val entry =
            entry(
                change =
                    ChangeSummary(
                        description = "Status changed, note removed, contact added",
                        fieldChanges =
                            listOf(
                                FieldChange("status", before = "Applied", after = "Interview"),
                                FieldChange("note", before = "call back \"Mon\"", after = null),
                                FieldChange("contact", before = null, after = "Ada Lovelace"),
                            ),
                    ),
                reason = "Recruiter replied by email",
            )

        repository.append(entry)

        repository.listRecent(ChangelogLimit(1)) shouldBe ChangelogResult.Success(listOf(entry))
    }

    @Test
    fun `lists the latest entries of one entity, oldest first`() {
        val first = entry(occurredAt = at(1))
        val second = entry(occurredAt = at(2))
        val third = entry(occurredAt = at(3))
        val otherEntity = entry(entity = EntityRef("company", "7"), occurredAt = at(4))
        listOf(third, otherEntity, first, second).forEach(repository::append)

        repository.listByEntity(APPLICATION, ChangelogLimit(2)) shouldBe ChangelogResult.Success(listOf(second, third))
    }

    @Test
    fun `lists recent entries across entities, newest first, insertion order breaking ties`() {
        val older = entry(occurredAt = at(1))
        val tieFirst = entry(entity = EntityRef("company", "7"), occurredAt = at(2))
        val tieSecond = entry(occurredAt = at(2), actor = Actor.Ai)
        listOf(older, tieFirst, tieSecond).forEach(repository::append)

        repository.listRecent(ChangelogLimit(3)) shouldBe ChangelogResult.Success(listOf(tieSecond, tieFirst, older))
    }

    @Test
    fun `the table is append-only`() {
        repository.append(entry())

        shouldThrow<DataAccessException> { dsl.update(CHANGELOG_ENTRY).set(CHANGELOG_ENTRY.REASON, "x").execute() }
            .message shouldContain "append-only"
        shouldThrow<DataAccessException> { dsl.deleteFrom(CHANGELOG_ENTRY).execute() }
            .message shouldContain "append-only"
    }

    @Test
    fun `the schema rejects a named user and a nameless scanner`() {
        val namedUser = ChangelogRecordMapper.toRecord(entry(actor = Actor.User)).apply { actorName = "someone" }
        val namelessScanner =
            ChangelogRecordMapper
                .toRecord(
                    entry(actor = Actor.Scanner("x")),
                ).apply { actorName = null }

        shouldThrow<DataAccessException> { dsl.executeInsert(namedUser) }
        shouldThrow<DataAccessException> { dsl.executeInsert(namelessScanner) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "\t\n"])
    fun `the schema rejects blank text where the domain requires text`(blank: String) {
        val rows =
            listOf<(ChangelogEntryRecord) -> Unit>(
                { it.actorName = blank },
                { it.reason = blank },
                { it.entityType = blank },
                { it.entityId = blank },
                { it.description = blank },
            ).map { blankOut -> ChangelogRecordMapper.toRecord(entry(actor = Actor.Scanner("x"))).apply(blankOut) }

        rows.forEach { row -> shouldThrow<DataAccessException> { dsl.executeInsert(row) } }
        dsl.fetchCount(CHANGELOG_ENTRY) shouldBe 0
    }

    @Test
    fun `storage errors come back as a failure result instead of an exception`() {
        val disconnected = ChangelogRepository(DSL.using(SQLDialect.POSTGRES))

        disconnected.append(entry()) shouldBe ChangelogResult.StorageFailure("append")
        disconnected.listByEntity(APPLICATION, ChangelogLimit(1)) shouldBe
            ChangelogResult.StorageFailure("listByEntity")
        disconnected.listRecent(ChangelogLimit(1)) shouldBe ChangelogResult.StorageFailure("listRecent")
    }

    companion object {
        private val APPLICATION = EntityRef("application", "42")
        private val START: Instant = Instant.parse("2026-09-30T08:00:00.123456Z")

        @JvmStatic
        fun everyActor(): List<Actor> =
            listOf(
                Actor.User,
                Actor.Ai,
                Actor.Scanner("bundesagentur"),
                Actor.ExternalClient("Claude Desktop"),
                Actor.System("follow-up-reminder"),
            )

        private fun at(minutes: Long): Instant = START.plus(minutes, ChronoUnit.MINUTES)

        private fun entry(
            entity: EntityRef = APPLICATION,
            actor: Actor = Actor.User,
            occurredAt: Instant = START,
            change: ChangeSummary = ChangeSummary("Created"),
            reason: String? = null,
        ) = ChangelogEntry(entity, actor, occurredAt, change, reason)
    }
}
