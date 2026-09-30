// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.persistence

import io.github.scriptibus.jofi.companies.domain.CompanyDetails
import io.github.scriptibus.jofi.companies.domain.CompanyPreference
import io.github.scriptibus.jofi.companies.domain.CompanyProfile
import io.github.scriptibus.jofi.companies.domain.CompanySize
import io.github.scriptibus.jofi.companies.domain.PreferenceKind
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.CompanyRecord
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
    fun `rejects names the domain rejects`() {
        rejects { insertCompany { name = " " } }
        rejects { insertCompany { name = " ACME" } }
        rejects { insertCompany { name = "\tACME" } }
        rejects { insertCompany { name = "ACME\n" } }
        rejects { insertCompany { name = "x".repeat(CompanyDetails.MAX_NAME_LENGTH + 1) } }
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
        rejects { insertCompany { website = address } }
        rejects { insertCompany { careersPage = address } }
    }

    @Test
    fun `rejects unknown sizes and preferences and a reason without a preference`() {
        rejects { insertCompany { size = "HUGE" } }
        rejects { insertCompany { preference = "MAYBE" } }
        rejects { insertCompany { preferenceReason = "Nice" } }
        rejects {
            insertCompany {
                preference = "BLACKLISTED"
                preferenceReason = " "
            }
        }
        rejects {
            insertCompany {
                preference = "BLACKLISTED"
                preferenceReason = "x".repeat(CompanyPreference.MAX_REASON_LENGTH + 1)
            }
        }
    }

    @Test
    fun `rejects bad locations, texts, profiles, versions and times`() {
        rejects { insertCompany { locations = arrayOf("Berlin", null) } }
        rejects { insertCompany { locations = arrayOf("Berlin", " ") } }
        rejects { insertCompany { locations = arrayOf("Berlin", " Hamburg") } }
        rejects { insertCompany { locations = arrayOf("Berlin", "BERLIN") } }
        rejects { insertCompany { locations = arrayOf("x".repeat(CompanyDetails.MAX_LOCATION_LENGTH + 1)) } }
        rejects { insertCompany { researchNotes = "# Notes\n" } }
        rejects {
            insertCompany {
                preference = "FAVOURITE"
                preferenceReason = "Great\t"
            }
        }
        rejects { insertCompany { locations = Array(CompanyDetails.MAX_LOCATIONS + 1) { "City $it" } } }
        rejects { insertCompany { industry = "" } }
        rejects { insertCompany { researchNotes = " " } }
        rejects { insertCompany { profile = "Builds anvils." } }
        rejects { insertCompany { profileGeneratedAt = NOW } }
        rejects {
            insertCompany {
                profile = "x".repeat(CompanyProfile.MAX_LENGTH + 1)
                profileGeneratedAt = NOW
            }
        }
        rejects { insertCompany { version = -1 } }
        rejects { insertCompany { updatedAt = NOW.minusSeconds(1) } }
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

    private fun rejects(statement: () -> Unit) {
        shouldThrow<DataAccessException> { statement() }
    }

    private companion object {
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-09-30T08:00:00Z")
    }
}
