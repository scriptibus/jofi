// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.domain.ApplicationSearchInput
import io.github.scriptibus.jofi.applications.domain.ApplicationSortKey
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.SavedView
import io.github.scriptibus.jofi.applications.domain.SavedViewFilter
import io.github.scriptibus.jofi.applications.domain.SavedViewId
import io.github.scriptibus.jofi.applications.domain.SavedViewInput
import io.github.scriptibus.jofi.applications.domain.SavedViewValidation
import io.github.scriptibus.jofi.applications.domain.SortDirection
import io.github.scriptibus.jofi.applications.domain.SourceKind
import io.github.scriptibus.jofi.setup.adapter.persistence.ConfirmedProofs
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SAVED_VIEW
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.JSONB
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset.UTC
import java.util.UUID

/** Saved views (#99, ADR-0050) against the real schema: every filter round trip, the tolerant reader, names. */
class SavedViewRepositoryTest {
    private val dsl = PostgresTestDatabase.migratedFromZero()
    private val repository = SavedViewRepository(dsl)

    @Test
    fun `every filter field comes back as saved, and the list is by name`() {
        val fullest = view("Zeta", fullest())
        val empty = view("Alpha", ApplicationSearchInput())

        repository.add(fullest) shouldBe ApplicationStoreResult.Success(Unit)
        repository.add(empty) shouldBe ApplicationStoreResult.Success(Unit)

        repository.findById(fullest.id) shouldBe ApplicationStoreResult.Success(fullest)
        repository.list().value() shouldContainExactly listOf(empty, fullest)
        repository.findById(SavedViewId(UUID.randomUUID())) shouldBe ApplicationStoreResult.NotFound
    }

    @Test
    fun `an update needs the version below its own`() {
        val saved = view("Offers", ApplicationSearchInput())
        repository.add(saved)
        val renamed = saved.edit(saved.details.copy(name = "Good offers"), AT.plusSeconds(1))

        repository.update(renamed) shouldBe ApplicationStoreResult.Success(Unit)
        repository.update(renamed) shouldBe ApplicationStoreResult.VersionConflict
        repository.update(renamed.copy(id = SavedViewId(UUID.randomUUID()))) shouldBe ApplicationStoreResult.NotFound
        repository.findById(saved.id) shouldBe ApplicationStoreResult.Success(renamed)
    }

    @Test
    fun `an older document with an unknown key and a removed status reads adjusted, and a save stores it cleaned`() {
        val id = UUID.randomUUID()
        insertRaw(id, """{"status": ["APPLIED", "ON_HOLD"], "pinned": true, "wantMin": 4, "wantMax": 3}""", 1)

        val restored = repository.findById(SavedViewId(id)).value()

        restored.adjusted shouldBe true
        restored.details.filter shouldBe SavedViewFilter(statuses = setOf(ApplicationStatus.APPLIED))
        repository.update(restored.edit(restored.details, AT.plusSeconds(1))) shouldBe
            ApplicationStoreResult.Success(Unit)
        val stored = dsl.fetchSingle(SAVED_VIEW).filter.data()
        stored shouldNotContain "ON_HOLD"
        stored shouldNotContain "pinned"
        stored shouldNotContain "want"
        repository.findById(SavedViewId(id)).value().adjusted shouldBe false
    }

    @Test
    fun `a document it cannot read fails the read instead of showing another filter`() {
        insertRaw(UUID.randomUUID(), "{}", 2)
        val id = UUID.randomUUID()
        insertRaw(id, """{"companyId": "not a uuid"}""", 1)

        repository.findById(SavedViewId(id)) shouldBe ApplicationStoreResult.StorageFailure("read view filter")
        repository.list() shouldBe ApplicationStoreResult.StorageFailure("read view filter")
    }

    @Test
    fun `exactly equal names are refused by the constraint, other case is left to the domain`() {
        repository.add(view("Offers", ApplicationSearchInput()))

        repository.add(view("Offers", ApplicationSearchInput())) shouldBe ApplicationStoreResult.ViewNameTaken
        repository.add(view("offers", ApplicationSearchInput())) shouldBe ApplicationStoreResult.Success(Unit)
        val other = view("Other", ApplicationSearchInput())
        repository.add(other)
        repository.update(other.edit(other.details.copy(name = "Offers"), AT)) shouldBe
            ApplicationStoreResult.ViewNameTaken
    }

    @Test
    fun `of two saves racing past the name check the later one is taken`() {
        val database = PostgresTestDatabase.container
        DSL.using(database.jdbcUrl, database.username, database.password).use { second ->
            val concurrent = SavedViewRepository(second)
            dsl.transaction { configuration ->
                SavedViewRepository(configuration.dsl()).add(view("Offers", ApplicationSearchInput())) shouldBe
                    ApplicationStoreResult.Success(Unit)
                concurrent.list() shouldBe ApplicationStoreResult.Success(emptyList())
            }

            concurrent.add(view("Offers", ApplicationSearchInput())) shouldBe ApplicationStoreResult.ViewNameTaken
        }
        dsl.fetchCount(SAVED_VIEW) shouldBe 1
    }

    @Test
    fun `a delete needs a proof for exactly this view`() {
        val saved = view("Offers", ApplicationSearchInput())
        repository.add(saved)

        repository.delete(
            saved.id,
            ConfirmedProofs.of(SavedView.DELETE_OPERATION, UUID.randomUUID().toString()),
        ) shouldBe
            ApplicationStoreResult.NotConfirmed
        repository.delete(saved.id, ConfirmedProofs.of("applications.delete", saved.id.value.toString())) shouldBe
            ApplicationStoreResult.NotConfirmed
        repository.delete(saved.id, proofFor(saved.id)) shouldBe ApplicationStoreResult.Success(Unit)
        repository.delete(saved.id, proofFor(saved.id)) shouldBe ApplicationStoreResult.NotFound
        dsl.fetchCount(SAVED_VIEW) shouldBe 0
    }

    @Test
    fun `a database it cannot reach is a storage failure, not an exception`() {
        val broken = SavedViewRepository(DSL.using(SQLDialect.POSTGRES))
        val saved = view("Offers", ApplicationSearchInput())

        broken.add(saved) shouldBe ApplicationStoreResult.StorageFailure("add view")
        broken.list() shouldBe ApplicationStoreResult.StorageFailure("list views")
        broken.findById(saved.id) shouldBe ApplicationStoreResult.StorageFailure("find view")
        broken.delete(saved.id, proofFor(saved.id)) shouldBe ApplicationStoreResult.StorageFailure("delete view")
    }

    private fun view(
        name: String,
        filter: ApplicationSearchInput,
    ): SavedView {
        val details = SavedViewInput(name, filter).validate().shouldBeInstanceOf<SavedViewValidation.Valid>().details
        return SavedView.create(SavedViewId(UUID.randomUUID()), details, AT)
    }

    private fun fullest(): ApplicationSearchInput =
        ApplicationSearchInput(
            text = "Kotlin \"Backend\"; ☕",
            company = CompanyRef(UUID.randomUUID()),
            contact = ContactRef(UUID.randomUUID()),
            statuses = setOf(ApplicationStatus.APPLIED, ApplicationStatus.OFFER),
            unread = true,
            languages = listOf("de", "en-GB"),
            sourceKinds = SourceKind.entries.toSet(),
            createdFrom = Instant.parse("2026-01-01T00:00:00.000001Z"),
            createdTo = Instant.parse("2026-12-31T00:00:00Z"),
            updatedFrom = Instant.parse("2026-06-01T00:00:00Z"),
            updatedTo = Instant.parse("2026-09-30T10:00:00.5Z"),
            wantMin = BigDecimal("2.5"),
            wantMax = BigDecimal("5"),
            fitMin = BigDecimal("0"),
            fitMax = BigDecimal("4.5"),
            sort = ApplicationSortKey.DEADLINE,
            direction = SortDirection.DESCENDING,
        )

    private fun insertRaw(
        id: UUID,
        document: String,
        format: Int,
    ) {
        dsl
            .newRecord(SAVED_VIEW)
            .apply {
                this.id = id
                name = "Stored $id"
                filter = JSONB.valueOf(document)
                filterVersion = format
                createdAt = AT.atOffset(UTC)
                updatedAt = AT.atOffset(UTC)
            }.insert()
    }

    private fun proofFor(id: SavedViewId) = ConfirmedProofs.of(SavedView.DELETE_OPERATION, id.value.toString())

    private fun <T> ApplicationStoreResult<T>.value(): T = shouldBeInstanceOf<ApplicationStoreResult.Success<T>>().value

    private companion object {
        val AT: Instant = Instant.parse("2026-09-30T08:00:00.123456Z")
    }
}
