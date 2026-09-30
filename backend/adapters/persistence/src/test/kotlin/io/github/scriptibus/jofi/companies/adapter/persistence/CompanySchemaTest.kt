// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.persistence

import io.github.scriptibus.jofi.companies.domain.CompanyDetails
import io.github.scriptibus.jofi.companies.domain.CompanyInput
import io.github.scriptibus.jofi.companies.domain.CompanyPreference
import io.github.scriptibus.jofi.companies.domain.CompanyProfile
import io.github.scriptibus.jofi.companies.domain.CompanySize
import io.github.scriptibus.jofi.companies.domain.CompanyValidation
import io.github.scriptibus.jofi.companies.domain.PreferenceKind
import io.github.scriptibus.jofi.companies.domain.WebAddress
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.CompanyRecord
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The `company` table on a real PostgreSQL migrated from zero: its constraints mirror the domain
 * invariants (so a repository bug cannot store what the domain forbids), and the trigram index
 * serves fuzzy name search (spec §8.4).
 */
class CompanySchemaTest {
    private lateinit var dsl: DSLContext

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
    }

    @Test
    fun `stores a company with every field and defaults the rest`() {
        insertCompany {
            website = "https://acme.example"
            industry = "Robotics"
            size = "MEDIUM"
            locations = arrayOf("Berlin", "Remote")
            careersPage = "https://jobs.example/acme?team=backend"
            researchNotes = "# Notes"
            profile = "Builds anvils."
            profileGeneratedAt = NOW
            preference = "FAVOURITE"
            preferenceReason = "Great team"
        }
        dsl
            .insertInto(COMPANY, COMPANY.ID, COMPANY.NAME, COMPANY.CREATED_AT, COMPANY.UPDATED_AT)
            .values(UUID.randomUUID(), "Minimal", NOW, NOW)
            .execute()

        val minimal = dsl.selectFrom(COMPANY).where(COMPANY.NAME.eq("Minimal")).fetchSingle()
        minimal.locations.toList() shouldBe emptyList()
        minimal.preference shouldBe "NONE"
        minimal.version shouldBe 0L
        dsl.fetchCount(COMPANY) shouldBe 2
    }

    @ParameterizedTest
    @EnumSource(CompanySize::class)
    fun `stores every company size the domain knows`(size: CompanySize) {
        insertCompany { this.size = size.name }

        dsl.fetchCount(COMPANY) shouldBe 1
    }

    @ParameterizedTest
    @EnumSource(PreferenceKind::class)
    fun `stores every preference kind the domain knows`(kind: PreferenceKind) {
        insertCompany { preference = kind.name }

        dsl.fetchCount(COMPANY) shouldBe 1
    }

    @Test
    fun `accepts every text, list and address at exactly its domain limit`() {
        val prefix = "https://acme.example/"
        insertCompany {
            name = "n".repeat(CompanyDetails.MAX_NAME_LENGTH)
            website = prefix + "w".repeat(WebAddress.MAX_LENGTH - prefix.length)
            industry = "i".repeat(CompanyDetails.MAX_INDUSTRY_LENGTH)
            locations =
                Array(CompanyDetails.MAX_LOCATIONS) { "City $it".padEnd(CompanyDetails.MAX_LOCATION_LENGTH, 'x') }
            careersPage = prefix + "c".repeat(WebAddress.MAX_LENGTH - prefix.length)
            researchNotes = "r".repeat(CompanyDetails.MAX_NOTES_LENGTH)
            profile = "p".repeat(CompanyProfile.MAX_LENGTH)
            profileGeneratedAt = NOW
            preference = "BLACKLISTED"
            preferenceReason = "b".repeat(CompanyPreference.MAX_REASON_LENGTH)
        }

        dsl.fetchCount(COMPANY) shouldBe 1
    }

    @Test
    fun `stores whatever the domain accepts`() {
        val input =
            CompanyInput(
                name = "Mu\u0308ller Ölwerke GmbH",
                website = "https://bücher.example/stellen/köln",
                industry = "Öl & Gas",
                locations = listOf("İstanbul", "istanbul", "Köln", "Remote"),
                careersPage = "https://my_team.example/careers?q=kotlin#open",
                researchNotes = "# Notizen\n\n\u00a0Straße",
            )
        val details = input.validate().shouldBeInstanceOf<CompanyValidation.Valid<CompanyDetails>>().value

        insertCompany {
            name = details.name
            website = details.website?.value
            industry = details.industry
            locations = details.locations.toTypedArray()
            careersPage = details.careersPage?.value
            researchNotes = details.researchNotes
            preference = "FAVOURITE"
            preferenceReason = "Tolle Leute"
        }

        dsl
            .selectFrom(COMPANY)
            .fetchSingle()
            .locations
            .toList() shouldBe
            listOf("İstanbul", "istanbul", "Köln", "Remote")
    }

    @Test
    fun `every constraint has a name of its own`() {
        val names =
            dsl.fetchValues(
                "select conname from pg_constraint " +
                    "where conrelid = 'company'::regclass and contype <> 'n' order by conname",
            )

        names.map(Any?::toString) shouldBe
            listOf(
                "company_careers_page_valid",
                "company_industry_valid",
                "company_locations_valid",
                "company_name_valid",
                "company_pk",
                "company_preference_reason_valid",
                "company_preference_valid",
                "company_profile_has_generation_time",
                "company_profile_valid",
                "company_reason_needs_preference",
                "company_research_notes_valid",
                "company_size_valid",
                "company_updated_after_created",
                "company_version_valid",
                "company_website_valid",
            )
    }

    @Test
    fun `rejects names the domain rejects`() {
        rejects("company_name_valid") { insertCompany { name = " " } }
        rejects("company_name_valid") { insertCompany { name = " ACME" } }
        rejects("company_name_valid") { insertCompany { name = "\tACME" } }
        rejects("company_name_valid") { insertCompany { name = "ACME\n" } }
        rejects("company_name_valid") { insertCompany { name = "x".repeat(CompanyDetails.MAX_NAME_LENGTH + 1) } }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "acme.example",
            "ftp://acme.example",
            "javascript:alert(1)",
            "https://user:secret@acme.example",
            "https://token@acme.example/jobs",
        ],
    )
    fun `rejects web addresses that are not http(s) or carry credentials`(address: String) {
        rejects("company_website_valid") { insertCompany { website = address } }
        rejects("company_careers_page_valid") { insertCompany { careersPage = address } }
    }

    @Test
    fun `rejects web addresses beyond the limit`() {
        val tooLong = "https://acme.example/" + "x".repeat(WebAddress.MAX_LENGTH)

        rejects("company_website_valid") { insertCompany { website = tooLong } }
        rejects("company_careers_page_valid") { insertCompany { careersPage = tooLong } }
    }

    @Test
    fun `rejects unknown sizes and preferences and a reason without a preference`() {
        rejects("company_size_valid") { insertCompany { size = "HUGE" } }
        rejects("company_preference_valid") { insertCompany { preference = "MAYBE" } }
        rejects("company_reason_needs_preference") { insertCompany { preferenceReason = "Nice" } }
        rejects("company_preference_reason_valid") { flagged(" ") }
        rejects("company_preference_reason_valid") { flagged("Great\t") }
        rejects("company_preference_reason_valid") { flagged("x".repeat(CompanyPreference.MAX_REASON_LENGTH + 1)) }
    }

    @Test
    fun `rejects bad locations`() {
        rejects("company_locations_valid") { insertCompany { locations = arrayOf("Berlin", null) } }
        rejects("company_locations_valid") { insertCompany { locations = arrayOf("Berlin", " ") } }
        rejects("company_locations_valid") { insertCompany { locations = arrayOf("Berlin", " Hamburg") } }
        rejects("company_locations_valid") { insertCompany { locations = arrayOf("Berlin", "Berlin") } }
        rejects("company_locations_valid") {
            insertCompany { locations = arrayOf("x".repeat(CompanyDetails.MAX_LOCATION_LENGTH + 1)) }
        }
        rejects("company_locations_valid") {
            insertCompany { locations = Array(CompanyDetails.MAX_LOCATIONS + 1) { "City $it" } }
        }
    }

    @Test
    fun `accepts locations that differ only in case, which the domain decides about`() {
        insertCompany { locations = arrayOf("Berlin", "BERLIN", "İstanbul", "istanbul") }

        dsl.fetchCount(COMPANY) shouldBe 1
    }

    @Test
    fun `rejects bad texts, profiles, versions and times`() {
        rejects("company_research_notes_valid") { insertCompany { researchNotes = "# Notes\n" } }
        rejects("company_research_notes_valid") { insertCompany { researchNotes = " " } }
        rejects("company_industry_valid") { insertCompany { industry = "" } }
        rejects("company_profile_has_generation_time") { insertCompany { profile = "Builds anvils." } }
        rejects("company_profile_has_generation_time") { insertCompany { profileGeneratedAt = NOW } }
        rejects("company_profile_valid") {
            insertCompany {
                profile = "x".repeat(CompanyProfile.MAX_LENGTH + 1)
                profileGeneratedAt = NOW
            }
        }
        rejects("company_version_valid") { insertCompany { version = -1 } }
        rejects("company_updated_after_created") { insertCompany { updatedAt = NOW.minusSeconds(1) } }
        dsl.fetchCount(COMPANY) shouldBe 0
    }

    @Test
    fun `finds companies by similar names through the trigram index`() {
        listOf("Globex Corporation", "ACME GmbH & Co. KG", "ACME GmbH").forEach { insertCompany { name = it } }

        val matches =
            dsl
                .select(COMPANY.NAME)
                .from(COMPANY)
                .where("name % ?", "acme gmbj")
                .orderBy(DSL.field("similarity(name, ?)", Float::class.java, "acme gmbj").desc())
                .fetch(COMPANY.NAME)

        matches shouldContainExactly listOf("ACME GmbH", "ACME GmbH & Co. KG")
        indexDefinition() shouldContain "gin (name gin_trgm_ops)"
    }

    private fun indexDefinition(): String =
        dsl.fetchValue("select indexdef from pg_indexes where indexname = 'company_name_trgm_idx'").toString()

    private fun insertCompany(customize: CompanyRecord.() -> Unit) {
        dsl
            .newRecord(COMPANY)
            .apply {
                id = UUID.randomUUID()
                name = "ACME GmbH"
                createdAt = NOW
                updatedAt = NOW
                customize()
            }.insert()
    }

    private fun flagged(reason: String) =
        insertCompany {
            preference = "BLACKLISTED"
            preferenceReason = reason
        }

    /** The statement fails on exactly the named constraint (repositories map violations by name). */
    private fun rejects(
        constraint: String,
        statement: () -> Unit,
    ) {
        shouldThrow<DataAccessException> { statement() }.message shouldContain "\"$constraint\""
    }

    private companion object {
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-09-30T08:00:00Z")
    }

    @Test
    fun `PostgreSQL cannot store U+0000, which is why the domain rejects it`() {
        shouldThrow<DataAccessException> { insertCompany { name = "AC\u0000ME" } }
        CompanyInput("AC\u0000ME").validate().shouldBeInstanceOf<CompanyValidation.Invalid>()
        dsl.fetchCount(COMPANY) shouldBe 0
    }
}
