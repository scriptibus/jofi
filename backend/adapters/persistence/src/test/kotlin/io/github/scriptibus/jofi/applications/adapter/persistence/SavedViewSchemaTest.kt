// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows.Companion.NOW
import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows.Companion.rejects
import io.github.scriptibus.jofi.applications.domain.ApplicationSearch
import io.github.scriptibus.jofi.applications.domain.ApplicationSearchInput
import io.github.scriptibus.jofi.applications.domain.ApplicationSortKey
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.SavedViewDetails
import io.github.scriptibus.jofi.applications.domain.SavedViewFilter
import io.github.scriptibus.jofi.applications.domain.SavedViewInput
import io.github.scriptibus.jofi.applications.domain.SavedViewValidation
import io.github.scriptibus.jofi.applications.domain.SortDirection
import io.github.scriptibus.jofi.applications.domain.SourceKind
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SAVED_VIEW
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.SavedViewRecord
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * `saved_view` on a real PostgreSQL (ADR-0041, ADR-0050): named constraints mirroring `SavedViewDetails`, never
 * stricter, and the filter document (`SavedViewDocument`) read back as stored, tolerantly.
 */
class SavedViewSchemaTest {
    private lateinit var dsl: DSLContext
    private lateinit var rows: ApplicationRows

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        rows = ApplicationRows(dsl)
    }

    @Test
    fun `every constraint has a name of its own`() {
        rows.constraintsOf("saved_view") shouldBe
            listOf(
                "saved_view_filter_valid",
                "saved_view_filter_version_valid",
                "saved_view_name_unique",
                "saved_view_name_valid",
                "saved_view_pk",
                "saved_view_updated_after_created",
                "saved_view_version_valid",
            )
    }

    @Test
    fun `stores whatever the domain accepts, at exactly its limits, and reads the filter back as it was`() {
        val company = rows.company()
        val contact = rows.contact(company)
        val views =
            listOf(
                // Exactly the limit in UTF-16 units, which the domain counts (the rocket is two).
                valid("ü".repeat(SavedViewDetails.MAX_NAME_LENGTH - 3) + " 🚀", fullest(company, contact)),
                valid("İstanbul, \"Angebote\";\nalle ☕", ApplicationSearchInput()),
                valid("istanbul, \"angebote\";\nalle ☕", ApplicationSearchInput(sort = ApplicationSortKey.TITLE)),
            )

        views.forEach { details ->
            insert {
                name = details.name
                filter = SavedViewDocument.write(details.filter)
            }
        }

        val stored =
            dsl.selectFrom(SAVED_VIEW).fetch().map {
                it.name to
                    SavedViewDocument.read(it.filterVersion, it.filter)
            }
        stored.toSet() shouldBe views.map { it.name to SavedViewFilter.Restored(it.filter, adjusted = false) }.toSet()
    }

    @Test
    fun `rejects views the domain rejects`() {
        rejects("saved_view_name_valid") { insert { name = "" } }
        rejects("saved_view_name_valid") { insert { name = "Mine " } }
        rejects("saved_view_name_valid") { insert { name = "x".repeat(SavedViewDetails.MAX_NAME_LENGTH + 1) } }
        rejects("saved_view_filter_valid") { insert { filter = JSONB.valueOf("[]") } }
        rejects("saved_view_filter_version_valid") { insert { filterVersion = 0 } }
        rejects("saved_view_version_valid") { insert { version = -1L } }
        rejects("saved_view_updated_after_created") { insert { updatedAt = NOW.minusSeconds(1) } }
        shouldThrow<DataAccessException> { insert { name = "a\u0000b" } }
        shouldThrow<DataAccessException> { insert { filter = JSONB.valueOf("{\"search\": \"a\\u0000b\"}") } }
        dsl.fetchCount(SAVED_VIEW) shouldBe 0
    }

    @Test
    fun `names are unique exactly, the domain folds case`() {
        insert { name = "Active" }
        insert { name = "active" }

        rejects("saved_view_name_unique") { insert { name = "Active" } }
    }

    @Test
    fun `deleting a company or contact a view names neither is blocked nor touches the view`() {
        val company = rows.company()
        val contact = rows.contact(company)
        val details =
            valid("Theirs", ApplicationSearchInput(company = CompanyRef(company), contact = ContactRef(contact)))
        insert { filter = SavedViewDocument.write(details.filter) }

        dsl.deleteFrom(CONTACT).execute()
        dsl.deleteFrom(COMPANY).execute()

        SavedViewDocument.read(1, dsl.fetchSingle(SAVED_VIEW).filter)?.filter shouldBe details.filter
    }

    @Test
    fun `the reader ignores unknown keys and leaves out what it cannot use, marking the view adjusted`() {
        val stored =
            """
            {"search": "Kotlin", "status": ["APPLIED", "SENT_TO_MARS"], "sourceKind": ["URL"], "sort": "MOOD",
             "direction": "SIDEWAYS", "language": ["de", "not a tag"], "wantMin": 4.25, "wantMax": 5,
             "someLaterFilter": {"x": 1}}
            """.trimIndent()

        SavedViewDocument.read(1, JSONB.valueOf(stored)) shouldBe
            SavedViewFilter.Restored(
                SavedViewFilter(
                    text = "Kotlin",
                    statuses = setOf(ApplicationStatus.APPLIED),
                    sourceKinds = setOf(SourceKind.URL),
                ),
                adjusted = true,
            )
        SavedViewDocument.read(1, JSONB.valueOf("{}")) shouldBe SavedViewFilter.Restored(SavedViewFilter(), false)
    }

    @Test
    fun `an unknown format or an unreadable document is no filter`() {
        SavedViewDocument.read(2, JSONB.valueOf("{}")) shouldBe null
        SavedViewDocument.read(1, JSONB.valueOf("{\"companyId\": \"not a uuid\"}")) shouldBe null
        SavedViewDocument.read(1, JSONB.valueOf("{\"createdFrom\": 1.5e400x}")) shouldBe null
    }

    @Test
    fun `the document format is the list's query parameters`() {
        val company = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val written =
            SavedViewDocument.write(
                valid(
                    "v",
                    ApplicationSearchInput(
                        company = CompanyRef(company),
                        statuses = setOf(ApplicationStatus.OFFER),
                        wantMin = BigDecimal("3.5"),
                        sort = ApplicationSortKey.STATUS,
                    ),
                ).filter,
            )

        dsl.fetchValue("select ?::jsonb = ?::jsonb", written.data(), EXPECTED_DOCUMENT) shouldBe true
    }

    private fun fullest(
        company: UUID,
        contact: UUID,
    ): ApplicationSearchInput =
        ApplicationSearchInput(
            text = "x".repeat(ApplicationSearch.MAX_TEXT_LENGTH - 2) + "🚀",
            company = CompanyRef(company),
            contact = ContactRef(contact),
            statuses = ApplicationStatus.entries.toSet(),
            unread = false,
            languages =
                (2..ApplicationSearch.MAX_LANGUAGES).map { "de-${it.toString().padStart(3, '0')}" } +
                    "de-12345678-12345678-12345678-12345",
            sourceKinds = SourceKind.entries.toSet(),
            createdFrom = Instant.parse("2000-01-01T00:00:00.000001Z"),
            createdTo = Instant.parse("2099-12-31T23:59:59.999999Z"),
            updatedTo = Instant.parse("2026-09-30T10:00:00.5Z"),
            wantMin = BigDecimal("0"),
            wantMax = BigDecimal("5.0"),
            fitMin = BigDecimal("2.5"),
            sort = ApplicationSortKey.COMPANY,
            direction = SortDirection.DESCENDING,
        )

    private fun valid(
        name: String,
        filter: ApplicationSearchInput,
    ): SavedViewDetails =
        SavedViewInput(name, filter).validate().shouldBeInstanceOf<SavedViewValidation.Valid>().details

    private fun insert(customize: SavedViewRecord.() -> Unit) {
        dsl
            .newRecord(SAVED_VIEW)
            .apply {
                id = UUID.randomUUID()
                name = "View " + id
                filter = JSONB.valueOf("{}")
                filterVersion = SavedViewDocument.VERSION
                createdAt = NOW
                updatedAt = NOW
                customize()
            }.insert()
    }

    private companion object {
        const val EXPECTED_DOCUMENT =
            """{"companyId": "00000000-0000-0000-0000-0000000000a1", "status": ["OFFER"], "wantMin": 3.5,
                "sort": "STATUS", "direction": "ASCENDING"}"""
    }
}
