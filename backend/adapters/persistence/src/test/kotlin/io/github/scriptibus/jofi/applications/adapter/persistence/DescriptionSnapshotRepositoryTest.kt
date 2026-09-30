// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.DescriptionSnapshot
import io.github.scriptibus.jofi.applications.domain.DescriptionText
import io.github.scriptibus.jofi.applications.domain.SnapshotId
import io.github.scriptibus.jofi.applications.domain.SnapshotReason
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_DESCRIPTION_SNAPSHOT
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** `DescriptionSnapshotRepository` on a real PostgreSQL migrated from zero (ADR-0046). */
class DescriptionSnapshotRepositoryTest {
    private lateinit var dsl: DSLContext
    private lateinit var repository: DescriptionSnapshotRepository
    private var application = ApplicationId(UUID(0, 0))
    private var other = ApplicationId(UUID(0, 0))

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        repository = DescriptionSnapshotRepository(dsl)
        val rows = ApplicationRows(dsl)
        val company = rows.company()
        application = ApplicationId(UUID.randomUUID()).also { rows.application(it.value, company) }
        other = ApplicationId(UUID.randomUUID()).also { rows.application(it.value, company) }
    }

    private fun freeze(
        id: ApplicationId,
        asOf: Instant,
    ): List<SnapshotId> =
        repository.freeze(id, asOf).shouldBeInstanceOf<ApplicationStoreResult.Success<List<SnapshotId>>>().value

    @Test
    fun `freezing picks per source the newest snapshot captured by then, and only of this application`() {
        val first = source(application)
        val older = snapshot(first, "v1", DAY_1)
        val newest = snapshot(first, "v2", DAY_2)
        val later = snapshot(first, "v3", DAY_4)
        val second = source(application)
        val onlyOne = snapshot(second, "only", DAY_1)
        val elsewhere = snapshot(source(other), "other", DAY_1)

        freeze(application, DAY_3) shouldContainExactlyInAnyOrder listOf(newest, onlyOne)

        frozenAt(newest) shouldBe DAY_3
        frozenAt(onlyOne) shouldBe DAY_3
        listOf(older, later, elsewhere).forEach { frozenAt(it) shouldBe null }
    }

    @Test
    fun `only the first freeze counts, also after a reopening`() {
        val source = source(application)
        val applied = snapshot(source, "v1", DAY_1)
        freeze(application, DAY_2) shouldBe listOf(applied)
        snapshot(source, "v2", DAY_3)

        freeze(application, DAY_4).shouldBeEmpty()

        frozenAt(applied) shouldBe DAY_2
    }

    @Test
    fun `a source found later is frozen on the next freeze, without its own snapshots nothing is`() {
        freeze(application, DAY_1).shouldBeEmpty()
        val source = source(application)
        val found = snapshot(source, "v1", DAY_2)

        freeze(application, DAY_3) shouldBe listOf(found)
    }

    @Test
    fun `a failing statement is a storage failure, not an exception`() {
        dsl.execute("drop table application_description_snapshot")

        repository.freeze(application, DAY_1) shouldBe ApplicationStoreResult.StorageFailure("freeze")
    }

    private fun <T> ApplicationStoreResult<T>.value(): T = shouldBeInstanceOf<ApplicationStoreResult.Success<T>>().value

    private fun recorded(
        source: UUID,
        text: String,
        at: Instant,
        frozenAt: Instant? = null,
    ): DescriptionSnapshot =
        DescriptionSnapshot(
            SnapshotId(UUID.randomUUID()),
            SourceId(source),
            DescriptionText(text),
            SnapshotReason.MANUAL,
            at,
            frozenAt,
        ).also { repository.add(it) shouldBe ApplicationStoreResult.Success(Unit) }

    @Test
    fun `added snapshots round-trip, the newest is the latest, and the list has no texts, oldest first`() {
        val source = source(application)
        val first = recorded(source, "Kotlin\nBerlin \uD83D\uDE80", DAY_1, frozenAt = DAY_1)
        val second = recorded(source, "Kotlin\nHamburg", DAY_2)
        recorded(source(application), "elsewhere", DAY_3)

        repository.latest(SourceId(source)).value() shouldBe second
        repository.latest(SourceId(UUID.randomUUID())).value() shouldBe null
        repository.findById(application, first.id).value() shouldBe first
        repository.listBySource(SourceId(source)).value() shouldBe listOf(first.summary(), second.summary())
        repository.listBySource(SourceId(UUID.randomUUID())).value().shouldBeEmpty()
    }

    @Test
    fun `a snapshot is found only through its own application, and one of a gone source is not stored`() {
        val snapshot = recorded(source(application), "Kotlin", DAY_1)
        val orphan =
            DescriptionSnapshot(
                SnapshotId(UUID.randomUUID()),
                SourceId(UUID.randomUUID()),
                DescriptionText("Kotlin"),
                SnapshotReason.MANUAL,
                DAY_1,
            )

        repository.findById(other, snapshot.id) shouldBe ApplicationStoreResult.NotFound
        repository.findById(application, SnapshotId(UUID.randomUUID())) shouldBe ApplicationStoreResult.NotFound
        repository.add(orphan) shouldBe ApplicationStoreResult.NotFound
    }

    @Test
    fun `reading the latest locks the source against other recordings, not against snapshot inserts`() {
        val source = source(application)
        val database = PostgresTestDatabase.container
        DSL.using(database.jdbcUrl, database.username, database.password).use { first ->
            DSL.using(database.jdbcUrl, database.username, database.password).use { second ->
                first.transaction { configuration ->
                    DescriptionSnapshotRepository(configuration.dsl()).latest(SourceId(source)).value() shouldBe null
                    second.execute("set lock_timeout = '200ms'")
                    val concurrent = DescriptionSnapshotRepository(second)

                    concurrent.latest(SourceId(source)) shouldBe
                        ApplicationStoreResult.StorageFailure("latest snapshot")
                    concurrent.add(
                        DescriptionSnapshot(
                            SnapshotId(UUID.randomUUID()),
                            SourceId(source),
                            DescriptionText("Kotlin"),
                            SnapshotReason.MANUAL,
                            DAY_1,
                        ),
                    ) shouldBe ApplicationStoreResult.Success(Unit)
                }
            }
        }
    }

    @Test
    fun `failing statements are storage failures, not exceptions`() {
        dsl.execute("drop table application_description_snapshot")
        val source = SourceId(UUID.randomUUID())

        repository.latest(source) shouldBe ApplicationStoreResult.StorageFailure("latest snapshot")
        repository.listBySource(source) shouldBe ApplicationStoreResult.StorageFailure("list snapshots")
        repository.findById(application, SnapshotId(UUID.randomUUID())) shouldBe
            ApplicationStoreResult.StorageFailure("find snapshot")
    }

    private fun source(application: ApplicationId): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            "insert into application_source (id, application_id, kind, discovered_at) " +
                "values (?, ?, 'MANUAL_CHAT', ?::timestamptz)",
            id,
            application.value,
            DAY_1.atOffset(ZoneOffset.UTC),
        )
        return id
    }

    private fun snapshot(
        source: UUID,
        text: String,
        capturedAt: Instant,
    ): SnapshotId {
        val id = UUID.randomUUID()
        dsl.execute(
            "insert into application_description_snapshot " +
                "(id, source_id, description, content_hash, reason, captured_at) values " +
                "(?, ?, ?, encode(sha256(convert_to(?, 'UTF8')), 'hex'), 'MANUAL', ?::timestamptz)",
            id,
            source,
            text,
            text,
            capturedAt.atOffset(ZoneOffset.UTC),
        )
        return SnapshotId(id)
    }

    private fun frozenAt(snapshot: SnapshotId): Instant? =
        dsl
            .select(APPLICATION_DESCRIPTION_SNAPSHOT.FROZEN_AT)
            .from(APPLICATION_DESCRIPTION_SNAPSHOT)
            .where(APPLICATION_DESCRIPTION_SNAPSHOT.ID.eq(snapshot.value))
            .fetchSingle()
            .value1()
            ?.toInstant()

    private companion object {
        val DAY_1: Instant = Instant.parse("2026-09-01T08:00:00Z")
        val DAY_2: Instant = Instant.parse("2026-09-02T08:00:00Z")
        val DAY_3: Instant = Instant.parse("2026-09-03T08:00:00Z")
        val DAY_4: Instant = Instant.parse("2026-09-04T08:00:00Z")
    }
}
